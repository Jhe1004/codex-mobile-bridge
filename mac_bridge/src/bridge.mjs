import { spawn, spawnSync } from "node:child_process";
import { createInterface } from "node:readline";
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { WebSocket } from "ws";
import { enrichThreadList, readProjectCatalog } from "./project_catalog.mjs";
import { syncLatestVisibleMessages } from "./thread_sync.mjs";
import { syncRecentlyActiveThreads } from "./recent_thread_sync.mjs";
import { createFileAccess } from "./file_access.mjs";
import { readableAppServerError, startTurnOnThread } from "./turn_start.mjs";
import { DesktopIpcClient } from "./desktop_ipc.mjs";
import { createDesktopThreadPreparer } from "./desktop_prepare.mjs";
import { wakeDesktopThread } from "./desktop_wake.mjs";
import { createThreadStatusAnalyzer } from "./thread_status.mjs";
import { DesktopCompletionReconciler } from "./desktop_completion_reconciler.mjs";
import { createFileUpload, desktopAttachmentContext, turnAttachmentInput } from "./file_upload.mjs";

const projectRoot = process.env.CODEX_PROJECT_ROOT || os.homedir();
const relayBase = process.env.RELAY_URL || "ws://127.0.0.1:18880/v1/ws";
const deviceId = process.env.MAC_DEVICE_ID || os.hostname();
const bundledCodex = "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex";
const codexBin = process.env.CODEX_BIN || (existsSync(bundledCodex) ? bundledCodex : "codex");
const globalStatePath = process.env.CODEX_GLOBAL_STATE || `${os.homedir()}/.codex/.codex-global-state.json`;
const explicitFileRoots = (process.env.CODEX_MOBILE_FILE_ROOTS || "").split(path.delimiter).filter(Boolean);

function iso() { return new Date().toISOString(); }
function log(event, extra = {}) { console.log(JSON.stringify({ time: iso(), event, ...extra })); }

function getRelayToken() {
  if (process.env.RELAY_MAC_TOKEN) return process.env.RELAY_MAC_TOKEN;
  const result = spawnSync("/usr/bin/security", ["find-generic-password", "-s", "CodexMobileBridgeRelayMacToken", "-w"], {
    encoding: "utf8",
  });
  if (result.status !== 0) throw new Error("Relay token is missing from the environment and macOS Keychain");
  return result.stdout.trim();
}

const relayToken = getRelayToken();
let relay = null;
let relayBackoff = 1000;
let stopping = false;
let appServer = null;
let appReady = false;
let nextRpcId = 100;
const pending = new Map();
const bridgeOwnedThreads = new Set();
let bridgeReleaseTimer = null;
let lastProjectCatalog = { projects: [], assignments: {} };
const knownThreads = new Map();
const knownThreadSources = new Map();
let snapshotInFlight = false;

function allowedRootsForThread(threadId) {
  const thread = knownThreads.get(threadId);
  if (!thread) return [];
  const registeredRoots = lastProjectCatalog.projects.flatMap((item) => item.rootPaths ?? []);
  return [...new Set([thread.cwd, ...registeredRoots, ...explicitFileRoots].filter(Boolean))];
}

const fileAccess = createFileAccess({ getAllowedRoots: allowedRootsForThread });
const fileUpload = createFileUpload({ threadExists: (threadId) => knownThreads.has(threadId) });
const desktopIpc = new DesktopIpcClient();
const desktopPreparer = createDesktopThreadPreparer({ desktopIpc, wakeDesktopThread });
const annotateThreadStatuses = createThreadStatusAnalyzer();
const desktopCompletionReconciler = new DesktopCompletionReconciler({
  desktopIpc,
  onEvent: (event, extra) => log(event, extra),
});

function sendRelay(message) {
  if (relay?.readyState !== WebSocket.OPEN) return false;
  relay.send(JSON.stringify(message));
  return true;
}

function sendApp(message) {
  if (!appServer?.stdin?.writable) throw new Error("Codex app-server is not writable");
  appServer.stdin.write(`${JSON.stringify(message)}\n`);
}

function rpc(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++nextRpcId;
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`Codex request timed out: ${method}`));
    }, 120_000);
    pending.set(id, { method, resolve, reject, timeout });
    sendApp({ id, method, params });
  });
}

function currentProjectCatalog() {
  try {
    lastProjectCatalog = readProjectCatalog(globalStatePath);
  } catch (error) {
    log("project_catalog_read_failed", { error: error.message, path: globalStatePath });
  }
  return lastProjectCatalog;
}

async function listAllThreads(options = {}) {
  const all = [];
  let cursor = null;
  do {
    const result = await rpc("thread/list", {
      limit: 200,
      cursor,
      sortKey: options.sortKey ?? "updated_at",
      sortDirection: options.sortDirection ?? "desc",
      useStateDbOnly: false,
      sourceKinds: ["cli", "vscode", "appServer"],
    });
    all.push(...(result.data ?? []));
    cursor = result.nextCursor ?? null;
  } while (cursor);
  const annotated = annotateThreadStatuses(all);
  const enriched = enrichThreadList({ data: annotated, nextCursor: null }, currentProjectCatalog());
  knownThreads.clear();
  for (const thread of enriched.data) knownThreads.set(thread.id, thread);
  knownThreadSources.clear();
  for (const thread of annotated) knownThreadSources.set(thread.id, thread);
  return enriched;
}

async function initialSnapshot() {
  if (snapshotInFlight) return;
  snapshotInFlight = true;
  try {
    const result = await listAllThreads();
    desktopCompletionReconciler.observe(result.data);
    sendRelay({ type: "snapshot", snapshot: "thread.list", result });
  } catch (error) {
    sendRelay({ type: "status", component: "app-server", ok: false, message: error.message });
  } finally {
    snapshotInFlight = false;
  }
}

function handleAppMessage(message) {
  if (Object.hasOwn(message, "id") && (Object.hasOwn(message, "result") || Object.hasOwn(message, "error"))) {
    const entry = pending.get(message.id);
    if (entry) {
      clearTimeout(entry.timeout);
      pending.delete(message.id);
      if (message.error) entry.reject(new Error(JSON.stringify(message.error)));
      else entry.resolve(message.result);
    }
    return;
  }

  if (Object.hasOwn(message, "id") && typeof message.method === "string") {
    sendRelay({ type: "event", event: "appServer.request", data: message });
    return;
  }

  if (typeof message.method === "string") {
    sendRelay({ type: "event", event: message.method, data: message.params ?? {} });
    if (message.method === "turn/completed") {
      const threadId = message.params?.threadId;
      if (threadId && bridgeOwnedThreads.delete(threadId)) {
        log("bridge_thread_turn_completed", { threadId, remainingOwnedThreads: bridgeOwnedThreads.size });
        if (bridgeOwnedThreads.size === 0) scheduleBridgeWriterRelease("turn-completed", 350);
      }
      void initialSnapshot();
    } else if (message.method === "turn/started") {
      void initialSnapshot();
    }
  }
}

function scheduleBridgeWriterRelease(reason, delayMs = 0) {
  if (stopping || bridgeReleaseTimer != null) return;
  bridgeReleaseTimer = setTimeout(() => {
    bridgeReleaseTimer = null;
    if (stopping || bridgeOwnedThreads.size > 0 || !appServer) return;
    log("bridge_writer_release", { reason });
    appReady = false;
    appServer.kill("SIGTERM");
  }, delayMs);
}

async function startAppServer() {
  appServer = spawn(codexBin, ["app-server", "--listen", "stdio://"], {
    cwd: projectRoot,
    env: process.env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  createInterface({ input: appServer.stdout }).on("line", (line) => {
    if (!line.trim()) return;
    try { handleAppMessage(JSON.parse(line)); }
    catch (error) { log("app_server_invalid_json", { error: error.message }); }
  });
  createInterface({ input: appServer.stderr }).on("line", (line) => log("app_server_stderr", { message: line }));
  appServer.on("exit", (code, signal) => {
    appReady = false;
    bridgeOwnedThreads.clear();
    if (bridgeReleaseTimer != null) {
      clearTimeout(bridgeReleaseTimer);
      bridgeReleaseTimer = null;
    }
    for (const [id, entry] of pending) {
      clearTimeout(entry.timeout);
      entry.reject(new Error(`Codex app-server exited (${code ?? signal})`));
      pending.delete(id);
    }
    sendRelay({ type: "status", component: "app-server", ok: false, message: `exited ${code ?? signal}` });
    if (!stopping) setTimeout(startAppServer, 2000);
  });
  try {
    await rpc("initialize", {
      clientInfo: { name: "codex_mobile_bridge", title: "Codex Mobile Bridge", version: "0.20.1" },
      capabilities: { experimentalApi: true },
    });
    appReady = true;
    log("app_server_ready", { codexBin });
    sendRelay({ type: "status", component: "app-server", ok: true });
    if (relay?.readyState === WebSocket.OPEN) await initialSnapshot();
  } catch (error) {
    log("app_server_initialize_failed", { error: error.message });
    appServer.kill("SIGTERM");
  }
}

async function dispatchRequest(message) {
  const { requestId, action, payload = {} } = message;
  try {
    if (!appReady && !["status", "thread.prepare", "file.stat", "file.read", "file.upload.begin", "file.upload.chunk", "file.upload.finish", "file.upload.discard"].includes(action)) throw new Error("Codex app-server is not ready");
    let result;
    switch (action) {
      case "status":
        result = { appServerReady: appReady, codexBin, projectRoot };
        break;
      case "thread.list":
        result = await listAllThreads(payload);
        break;
      case "thread.read":
        result = await rpc("thread/read", { threadId: payload.threadId, includeTurns: payload.includeTurns ?? true });
        break;
      case "thread.resume":
        throw new Error("Mobile bridge does not retain thread ownership; use thread.latest for reading");
      case "thread.prepare":
        if (!payload.threadId) throw new Error("threadId is required");
        result = await desktopPreparer.prepare(payload.threadId);
        log("thread_prepared", {
          threadId: payload.threadId,
          alreadyReady: result.alreadyReady,
          ownerClientId: result.ownerClientId,
        });
        break;
      case "thread.latest":
        if (!payload.threadId) throw new Error("threadId is required");
        result = await syncLatestVisibleMessages(
          rpc,
          payload.threadId,
          knownThreadSources.get(payload.threadId) ?? knownThreads.get(payload.threadId) ?? {},
          { knownRevision: payload.knownRevision, limit: payload.limit ?? 120 },
        );
        break;
      case "thread.recent.sync":
        {
          // Refresh the source list on every pass. This makes archive, title,
          // project, running, and completion changes converge together with
          // message content instead of relying on an older relay snapshot.
          const threadList = await listAllThreads();
          desktopCompletionReconciler.observe(threadList.data);
          const sync = await syncRecentlyActiveThreads(rpc, threadList.data, {
            knownRevisions: payload.knownRevisions ?? {},
            getSourceThread: (thread) => knownThreadSources.get(thread.id) ?? thread,
            limit: 120,
            onProgress: payload.manual === true && payload.syncId ? (progress) => {
              sendRelay({
                type: "event",
                event: "thread.recent.sync.progress",
                data: { syncId: String(payload.syncId), ...progress },
              });
            } : null,
          });
          result = { threadList, sync };
        }
        break;
      case "file.stat":
        result = await fileAccess.metadata(payload.threadId, payload.path);
        break;
      case "file.read":
        result = await fileAccess.readChunk(
          payload.threadId,
          payload.path,
          payload.offset,
          payload.expectedSize,
          payload.expectedMtimeMs,
        );
        break;
      case "file.upload.begin":
        result = await fileUpload.begin(payload.threadId, payload);
        break;
      case "file.upload.chunk":
        result = await fileUpload.append(payload.threadId, payload.uploadId, payload.offset, payload.data);
        break;
      case "file.upload.finish":
        result = await fileUpload.finish(payload.threadId, payload.uploadId);
        break;
      case "file.upload.discard":
        result = await fileUpload.discard(payload.threadId, payload.uploadId);
        break;
      case "thread.start":
        result = await rpc("thread/start", {
          cwd: payload.cwd || projectRoot,
          ephemeral: payload.ephemeral ?? false,
          historyMode: payload.historyMode || "paginated",
        });
        // thread/start necessarily gives this app-server a short-lived writer
        // lease. Do not retain it while the user is merely looking at the new
        // empty chat on the phone; Desktop must remain able to open it.
        if (result.thread?.id) scheduleBridgeWriterRelease("thread-started", 750);
        break;
      case "turn.start":
        {
          const startedAtMs = Date.now();
          const baselineRevision = knownThreads.get(payload.threadId)?.completionRevision ?? "";
          const attachments = await fileUpload.resolve(payload.threadId, payload.attachmentIds ?? []);
          result = await startTurnOnThread(rpc, {
            ...payload,
            attachmentInput: turnAttachmentInput(attachments),
            desktopAttachments: desktopAttachmentContext(attachments),
          }, {
            desktopIpc,
            preferBridge: bridgeOwnedThreads.has(payload.threadId),
            prepareDesktopThread: (threadId) => desktopPreparer.prepare(threadId),
            wakeDesktopThread,
            onBridgeOwnership: (threadId) => {
              bridgeOwnedThreads.add(threadId);
            },
          });
          log("turn_delivery", { threadId: payload.threadId, delivery: result.delivery, attachmentCount: attachments.length });
          if (result.delivery.startsWith("desktop-ipc")) {
            desktopCompletionReconciler.track({
              threadId: payload.threadId,
              ownerClientId: result.ownerClientId,
              clientUserMessageId: result.clientUserMessageId,
              baselineRevision,
              startedAtMs,
            });
          }
        }
        break;
      case "turn.interrupt":
        result = await rpc("turn/interrupt", { threadId: payload.threadId, turnId: payload.turnId });
        break;
      case "turn.steer":
        if (!payload.threadId || !payload.expectedTurnId || typeof payload.text !== "string") throw new Error("threadId, expectedTurnId and text are required");
        result = await rpc("turn/steer", {
          threadId: payload.threadId,
          expectedTurnId: payload.expectedTurnId,
          input: [{ type: "text", text: payload.text }],
        });
        break;
      case "appServer.respond":
        if (!Object.hasOwn(payload, "id") || !Object.hasOwn(payload, "result")) throw new Error("id and result are required");
        sendApp({ id: payload.id, result: payload.result });
        result = { accepted: true };
        break;
      default:
        throw new Error(`Unsupported action: ${action}`);
    }
    sendRelay({ type: "response", requestId, ok: true, result });
  } catch (error) {
    const message = readableAppServerError(error);
    log("request_failed", { action, error: message });
    sendRelay({ type: "response", requestId, ok: false, error: { code: "bridge_error", message } });
    if (action === "turn.start" && bridgeOwnedThreads.delete(payload.threadId)) {
      scheduleBridgeWriterRelease("turn-start-failed");
    }
  }
}

function connectRelay() {
  if (stopping) return;
  const url = new URL(relayBase);
  url.searchParams.set("role", "mac");
  url.searchParams.set("deviceId", deviceId);
  relay = new WebSocket(url, { headers: { Authorization: `Bearer ${relayToken}` } });
  relay.on("open", async () => {
    relayBackoff = 1000;
    log("relay_connected", { url: relayBase });
    sendRelay({ type: "status", component: "mac-bridge", ok: true, appServerReady: appReady });
    if (appReady) await initialSnapshot();
  });
  relay.on("message", (data) => {
    let message;
    try { message = JSON.parse(data.toString("utf8")); }
    catch { return; }
    if (message.type === "request") void dispatchRequest(message);
  });
  relay.on("close", (code) => {
    log("relay_disconnected", { code, retryMs: relayBackoff });
    if (!stopping) {
      setTimeout(connectRelay, relayBackoff);
      relayBackoff = Math.min(relayBackoff * 2, 30_000);
    }
  });
  relay.on("error", (error) => log("relay_error", { error: error.message }));
}

function shutdown(signal) {
  stopping = true;
  log("shutdown", { signal });
  relay?.close(1001, signal);
  desktopIpc.close();
  if (bridgeReleaseTimer != null) clearTimeout(bridgeReleaseTimer);
  appServer?.kill("SIGTERM");
  setTimeout(() => process.exit(0), 500).unref();
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

await startAppServer();
connectRelay();
setInterval(() => {
  if (appReady && relay?.readyState === WebSocket.OPEN) void initialSnapshot();
}, 10_000).unref();
