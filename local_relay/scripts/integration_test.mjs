import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { WebSocket } from "ws";

const port = 19880;
const macToken = "test-mac-token";
const androidToken = "test-android-token";
const child = spawn(process.execPath, ["src/server.mjs"], {
  cwd: new URL("..", import.meta.url),
  env: { ...process.env, RELAY_PORT: String(port), RELAY_MAC_TOKEN: macToken, RELAY_ANDROID_TOKEN: androidToken },
  stdio: ["ignore", "pipe", "inherit"],
});

async function open(role, token, deviceId = "test-device") {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/v1/ws?role=${role}&deviceId=${deviceId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  await once(ws, "open");
  return ws;
}

function next(ws) {
  return once(ws, "message").then(([data]) => JSON.parse(data.toString("utf8")));
}

try {
  await once(child.stdout, "data");
  const mac = await open("mac", macToken, "mac");
  assert.equal((await next(mac)).type, "relay.ready");
  const android = await open("android", androidToken);
  const ready = await next(android);
  assert.equal(ready.type, "relay.ready");
  assert.equal(ready.macConnected, true);

  const request = { type: "request", requestId: "r1", action: "thread.list", payload: {} };
  android.send(JSON.stringify(request));
  const relayedRequest = await next(mac);
  assert.equal(relayedRequest.action, "thread.list");
  assert.equal(relayedRequest.deviceId, "test-device");

  mac.send(JSON.stringify({ type: "response", requestId: "r1", ok: true, result: { threads: [] } }));
  const response = await next(android);
  assert.equal(response.requestId, "r1");
  assert.equal(response.ok, true);
  assert.ok(response.seq > 0);

  mac.send(JSON.stringify({ type: "event", event: "turn.completed", data: { turnId: "t1" } }));
  const event = await next(android);
  assert.equal(event.event, "turn.completed");

  mac.close();
  android.close();
  console.log(JSON.stringify({ ok: true, checks: ["authentication", "android_to_mac", "mac_to_android", "event_stream"] }));
} finally {
  child.kill("SIGTERM");
}
