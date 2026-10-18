import net from "node:net";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";

const INITIAL_CLIENT_ID = "initializing-client";
const DEFAULT_TIMEOUT_MS = 10_000;
const METHOD_VERSIONS = {
  "thread-stream-following-changed": 1,
  "thread-owner-discovery": 1,
  "thread-follower-start-turn": 2,
};

export class DesktopOwnerUnavailableError extends Error {
  constructor(message = "No desktop owner is available for this thread") {
    super(message);
    this.name = "DesktopOwnerUnavailableError";
  }
}

function encodeFrame(message) {
  const body = Buffer.from(JSON.stringify(message), "utf8");
  const frame = Buffer.allocUnsafe(4 + body.length);
  frame.writeUInt32LE(body.length, 0);
  body.copy(frame, 4);
  return frame;
}

export class DesktopIpcClient {
  constructor({ socketPath = path.join(os.homedir(), ".codex", "ipc", "ipc.sock"), clientType = "codex-mobile-bridge" } = {}) {
    this.socketPath = socketPath;
    this.clientType = clientType;
    this.socket = null;
    this.clientId = INITIAL_CLIENT_ID;
    this.pending = new Map();
    this.buffer = Buffer.alloc(0);
    this.connecting = null;
  }

  async connect() {
    if (this.socket?.writable && this.clientId !== INITIAL_CLIENT_ID) return;
    if (this.connecting) return this.connecting;
    this.connecting = new Promise((resolve, reject) => {
      const socket = net.createConnection(this.socketPath);
      const fail = (error) => {
        socket.destroy();
        reject(new DesktopOwnerUnavailableError(`Desktop IPC is unavailable: ${error.message}`));
      };
      socket.once("error", fail);
      socket.once("connect", async () => {
        socket.off("error", fail);
        this.socket = socket;
        socket.on("data", (chunk) => this.#onData(chunk));
        socket.on("error", (error) => this.#close(error));
        socket.on("close", () => this.#close(new Error("Desktop IPC connection closed")));
        try {
          const response = await this.#request("initialize", { clientType: this.clientType }, { version: 0 });
          if (response.resultType !== "success" || !response.result?.clientId) {
            throw new Error(response.error || "Desktop IPC initialization failed");
          }
          this.clientId = response.result.clientId;
          resolve();
        } catch (error) {
          socket.destroy();
          reject(error);
        }
      });
    }).finally(() => {
      this.connecting = null;
    });
    return this.connecting;
  }

  async findThreadOwner(threadId) {
    await this.connect();
    const response = await this.#request(
      "thread-owner-discovery",
      { hostId: "local", conversationId: threadId },
      { version: METHOD_VERSIONS["thread-owner-discovery"], timeoutMs: 12_000 },
    );
    if (response.resultType === "error" && response.error === "no-client-found") return null;
    if (response.resultType !== "success") throw new Error(response.error || "Desktop owner discovery failed");
    return response.handledByClientId || null;
  }

  async waitForThreadOwner(threadId, { timeoutMs = 10_000, intervalMs = 250 } = {}) {
    const deadline = Date.now() + timeoutMs;
    do {
      try {
        const ownerClientId = await this.findThreadOwner(threadId);
        if (ownerClientId) return ownerClientId;
      } catch (error) {
        if (!(error instanceof DesktopOwnerUnavailableError)) throw error;
      }
      if (Date.now() >= deadline) break;
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
    } while (true);
    return null;
  }

  async startTurn(threadId, text, clientUserMessageId = randomUUID(), knownOwnerClientId = null) {
    const ownerClientId = knownOwnerClientId || await this.findThreadOwner(threadId);
    if (!ownerClientId) throw new DesktopOwnerUnavailableError();
    let following = false;
    try {
      // Register the bridge as a read-only follower before the turn starts.
      // Codex keeps an inactive Desktop owner alive while it has followers, so
      // its completion observer cannot be retired when the user switches chats.
      await this.setThreadFollowing(threadId, ownerClientId, true);
      following = true;
      const response = await this.#request(
        "thread-follower-start-turn",
        {
          conversationId: threadId,
          turnStart: {
            request: {
              threadId,
              // Desktop keeps this richer text shape in its in-memory turn state.
              // The app-server accepts plain { type, text }, but the Desktop
              // renderer also reads text_elements while rendering the turn.
              input: [{ type: "text", text, text_elements: [] }],
              clientUserMessageId,
            },
            context: {},
          },
        },
        {
          version: METHOD_VERSIONS["thread-follower-start-turn"],
          targetClientId: ownerClientId,
          timeoutMs: 120_000,
        },
      );
      if (response.resultType !== "success") throw new Error(response.error || "Desktop owner rejected the turn");
      return { ownerClientId, response, clientUserMessageId, following };
    } catch (error) {
      if (following) await this.setThreadFollowing(threadId, ownerClientId, false).catch(() => {});
      throw error;
    }
  }

  async setThreadFollowing(threadId, ownerClientId, following) {
    await this.connect();
    if (!ownerClientId) throw new DesktopOwnerUnavailableError();
    this.#broadcast(
      "thread-stream-following-changed",
      { conversationId: threadId, hostId: "local", following: Boolean(following) },
      { version: METHOD_VERSIONS["thread-stream-following-changed"], targetClientIds: [ownerClientId] },
    );
  }

  close() {
    this.socket?.destroy();
    this.#close(new Error("Desktop IPC client closed"));
  }

  #request(method, params, { version = 0, targetClientId, timeoutMs = DEFAULT_TIMEOUT_MS } = {}) {
    if (!this.socket?.writable) return Promise.reject(new DesktopOwnerUnavailableError("Desktop IPC is not connected"));
    const requestId = randomUUID();
    const message = {
      type: "request",
      requestId,
      sourceClientId: this.clientId,
      version,
      method,
      params,
      timeoutMs,
    };
    if (targetClientId) message.targetClientId = targetClientId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new Error(`Desktop IPC request timed out: ${method}`));
      }, timeoutMs + 1_000);
      this.pending.set(requestId, { resolve, reject, timer });
      this.socket.write(encodeFrame(message));
    });
  }

  #broadcast(method, params, { version = 0, targetClientIds } = {}) {
    if (!this.socket?.writable) throw new DesktopOwnerUnavailableError("Desktop IPC is not connected");
    if (targetClientIds?.length === 0) return;
    const message = {
      type: "broadcast",
      sourceClientId: this.clientId,
      version,
      method,
      params,
    };
    if (targetClientIds) message.targetClientIds = targetClientIds;
    this.socket.write(encodeFrame(message));
  }

  #onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 4) {
      const size = this.buffer.readUInt32LE(0);
      if (size <= 0 || size > 256 * 1024 * 1024) {
        this.#close(new Error(`Invalid Desktop IPC frame size: ${size}`));
        return;
      }
      if (this.buffer.length < 4 + size) return;
      const body = this.buffer.subarray(4, 4 + size);
      this.buffer = this.buffer.subarray(4 + size);
      let message;
      try {
        message = JSON.parse(body.toString("utf8"));
      } catch {
        continue;
      }
      this.#handleMessage(message);
    }
  }

  #handleMessage(message) {
    if (message.type === "response") {
      const entry = this.pending.get(message.requestId);
      if (!entry) return;
      clearTimeout(entry.timer);
      this.pending.delete(message.requestId);
      entry.resolve(message);
      return;
    }
    if (message.type === "client-discovery-request") {
      this.socket?.write(encodeFrame({
        type: "client-discovery-response",
        requestId: message.requestId,
        response: { canHandle: false },
      }));
      return;
    }
    if (message.type === "request") {
      this.socket?.write(encodeFrame({
        type: "response",
        requestId: message.requestId,
        resultType: "error",
        error: "no-handler-for-request",
      }));
    }
  }

  #close(error) {
    this.socket = null;
    this.clientId = INITIAL_CLIENT_ID;
    this.buffer = Buffer.alloc(0);
    for (const entry of this.pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    this.pending.clear();
  }
}
