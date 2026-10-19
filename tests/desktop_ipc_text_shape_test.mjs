import assert from "node:assert/strict";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import { DesktopIpcClient } from "../mac_bridge/src/desktop_ipc.mjs";

function encodeFrame(message) {
  const body = Buffer.from(JSON.stringify(message), "utf8");
  const frame = Buffer.allocUnsafe(4 + body.length);
  frame.writeUInt32LE(body.length, 0);
  body.copy(frame, 4);
  return frame;
}

function consumeFrames(socket, onMessage) {
  let buffer = Buffer.alloc(0);
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 4) {
      const size = buffer.readUInt32LE(0);
      if (buffer.length < size + 4) return;
      const body = buffer.subarray(4, size + 4);
      buffer = buffer.subarray(size + 4);
      onMessage(JSON.parse(body.toString("utf8")), socket);
    }
  });
}

const tempDir = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-ipc-shape-"));
const socketPath = path.join(tempDir, "ipc.sock");
let capturedTurnStart = null;
const followingStates = [];
const protocolOrder = [];

const server = net.createServer((socket) => {
  consumeFrames(socket, (message, peer) => {
    if (message.method === "initialize") {
      peer.write(encodeFrame({
        type: "response",
        requestId: message.requestId,
        resultType: "success",
        result: { clientId: "test-client" },
      }));
      return;
    }
    if (message.method === "thread-owner-discovery") {
      peer.write(encodeFrame({
        type: "response",
        requestId: message.requestId,
        resultType: "success",
        handledByClientId: "desktop-owner",
      }));
      return;
    }
    if (message.method === "thread-follower-start-turn") {
      protocolOrder.push("start-turn");
      capturedTurnStart = message.params.turnStart;
      const shouldFail = message.params.turnStart?.request?.input?.[0]?.text === "失败测试";
      peer.write(encodeFrame({
        type: "response",
        requestId: message.requestId,
        resultType: shouldFail ? "error" : "success",
        ...(shouldFail
          ? { error: "rejected-for-test" }
          : { result: { method: "thread-follower-start-turn", result: { ok: true } } }),
      }));
      return;
    }
    if (message.method === "thread-stream-following-changed") {
      protocolOrder.push(message.params.following ? "follow" : "unfollow");
      followingStates.push(message.params.following);
    }
  });
});

await new Promise((resolve, reject) => {
  server.once("error", reject);
  server.listen(socketPath, resolve);
});

const client = new DesktopIpcClient({ socketPath, clientType: "shape-test" });
try {
  await client.startTurn("01a13fe2-bb25-7143-b379-73a005c2ff38", [{ type: "text", text: "手机文本", text_elements: [] }]);
  await client.setThreadFollowing("01a13fe2-bb25-7143-b379-73a005c2ff38", "desktop-owner", false);
  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.deepEqual(capturedTurnStart?.request?.input, [{
    type: "text",
    text: "手机文本",
    text_elements: [],
  }]);
  assert.deepEqual(followingStates, [true, false]);
  assert.deepEqual(protocolOrder, ["follow", "start-turn", "unfollow"]);
  await assert.rejects(
    client.startTurn("01a13fe2-bb25-7143-b379-73a005c2ff38", [{ type: "text", text: "失败测试", text_elements: [] }]),
    /rejected-for-test/,
  );
  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.deepEqual(followingStates, [true, false, true, false]);
  assert.deepEqual(protocolOrder, [
    "follow", "start-turn", "unfollow",
    "follow", "start-turn", "unfollow",
  ]);
  console.log(JSON.stringify({ ok: true, inputShape: true, followLifecycle: true }));
} finally {
  client.close();
  await new Promise((resolve) => server.close(resolve));
  await rm(tempDir, { recursive: true, force: true });
}
