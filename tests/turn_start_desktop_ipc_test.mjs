import assert from "node:assert/strict";
import {
  DESKTOP_HYDRATION_TIMEOUT_MS,
  readableAppServerError,
  startTurnOnThread,
} from "../mac_bridge/src/turn_start.mjs";
import { DesktopOwnerUnavailableError } from "../mac_bridge/src/desktop_ipc.mjs";

const calls = [];
const result = await startTurnOnThread(async (method, params) => {
  calls.push({ method, params });
  return { turn: { id: "turn-1" } };
}, { threadId: "thread-1", text: "  测试消息  " }, {
  desktopIpc: {
    async startTurn(threadId, text, clientUserMessageId) {
      calls.push({ method: "desktop-ipc", params: { threadId, text, clientUserMessageId } });
      return { ownerClientId: "desktop-owner" };
    },
  },
});

assert.equal(result.accepted, true);
assert.equal(result.delivery, "desktop-ipc");
assert.equal(calls.length, 1);
assert.equal(calls[0].method, "desktop-ipc");
assert.equal(calls[0].params.threadId, "thread-1");
assert.match(calls[0].params.clientUserMessageId, /^[0-9a-f-]{36}$/);
assert.equal(calls[0].params.text, "测试消息");

const wakeCalls = [];
const afterWake = await startTurnOnThread(async () => {
  throw new Error("Bridge app-server must not be used after Desktop wakes");
}, { threadId: "thread-wake", text: "唤醒后发送" }, {
  desktopIpc: {
    attempts: 0,
    async startTurn(threadId, text, clientUserMessageId, ownerClientId) {
      this.attempts += 1;
      wakeCalls.push({ method: "desktop-ipc", threadId, text, clientUserMessageId, ownerClientId });
      if (this.attempts === 1) throw new DesktopOwnerUnavailableError();
      return { ownerClientId };
    },
    async waitForThreadOwner(threadId, options) {
      wakeCalls.push({ method: "wait-owner", threadId, options });
      return "desktop-owner-after-wake";
    },
  },
  async wakeDesktopThread(threadId) {
    wakeCalls.push({ method: "wake", threadId });
  },
});
assert.equal(afterWake.delivery, "desktop-ipc-after-wake");
assert.deepEqual(wakeCalls.map((call) => call.method), ["desktop-ipc", "wake", "wait-owner", "desktop-ipc"]);
assert.equal(wakeCalls[2].options.timeoutMs, DESKTOP_HYDRATION_TIMEOUT_MS);
assert.equal(wakeCalls.at(-1).ownerClientId, "desktop-owner-after-wake");

const sharedPrepareCalls = [];
const afterPreheat = await startTurnOnThread(async () => {
  throw new Error("Bridge app-server must not be used after preheat");
}, { threadId: "thread-preheated", text: "预热后发送" }, {
  desktopIpc: {
    attempts: 0,
    async startTurn(threadId, text, clientUserMessageId, ownerClientId) {
      this.attempts += 1;
      sharedPrepareCalls.push({ method: "desktop-ipc", threadId, text, clientUserMessageId, ownerClientId });
      if (this.attempts === 1) throw new DesktopOwnerUnavailableError();
      return { ownerClientId };
    },
  },
  async prepareDesktopThread(threadId) {
    sharedPrepareCalls.push({ method: "prepare", threadId });
    return { ready: true, ownerClientId: "prepared-owner" };
  },
});
assert.equal(afterPreheat.delivery, "desktop-ipc-after-wake");
assert.deepEqual(sharedPrepareCalls.map((call) => call.method), ["desktop-ipc", "prepare", "desktop-ipc"]);
assert.equal(sharedPrepareCalls.at(-1).ownerClientId, "prepared-owner");

const fallbackCalls = [];
const bridgeOwnership = [];
const fallback = await startTurnOnThread(async (method, params) => {
  fallbackCalls.push({ method, params });
  if (method === "turn/start" && fallbackCalls.length === 1) throw new Error("Thread is not loaded");
  if (method === "thread/resume") return { thread: { id: params.threadId } };
  return { turn: { id: "turn-fallback" } };
}, { threadId: "thread-2", text: "后备消息" }, {
  desktopIpc: {
    async startTurn() { throw new DesktopOwnerUnavailableError(); },
  },
  onBridgeOwnership(threadId) { bridgeOwnership.push(threadId); },
});
assert.equal(fallback.delivery, "bridge-app-server");
assert.equal(fallback.turn.id, "turn-fallback");
assert.deepEqual(fallbackCalls.map((call) => call.method), ["turn/start", "thread/resume", "turn/start"]);
assert.deepEqual(bridgeOwnership, ["thread-2"]);

assert.equal(readableAppServerError(new Error('{"code":-32000,"message":"Thread is not loaded"}')), "Thread is not loaded");
assert.equal(readableAppServerError(new Error("plain failure")), "plain failure");

console.log(JSON.stringify({ ok: true, desktopIpcImmediateStart: true, readableErrors: true }));
