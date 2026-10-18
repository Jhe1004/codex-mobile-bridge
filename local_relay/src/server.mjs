import http from "node:http";
import crypto from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";

const host = process.env.RELAY_HOST || "127.0.0.1";
const port = Number(process.env.RELAY_PORT || "18880");
const macToken = process.env.RELAY_MAC_TOKEN || "";
const androidToken = process.env.RELAY_ANDROID_TOKEN || "";
const maxReplay = Number(process.env.RELAY_MAX_REPLAY || "1000");
const maxPayload = Number(process.env.RELAY_MAX_PAYLOAD || String(8 * 1024 * 1024));

if (!macToken || !androidToken) {
  console.error("RELAY_MAC_TOKEN and RELAY_ANDROID_TOKEN are required");
  process.exit(2);
}

let mac = null;
const androids = new Map();
const replay = [];
let sequence = 0;

function now() {
  return new Date().toISOString();
}

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(payload),
    "cache-control": "no-store",
  });
  res.end(payload);
}

function safeSend(ws, message) {
  if (ws?.readyState !== WebSocket.OPEN) return false;
  ws.send(JSON.stringify(message));
  return true;
}

function publicEnvelope(message) {
  sequence += 1;
  const envelope = { ...message, seq: sequence, serverTime: now() };
  if (["event", "snapshot", "status"].includes(message.type)) {
    replay.push(envelope);
    if (replay.length > maxReplay) replay.shift();
  }
  return envelope;
}

function broadcastAndroid(message) {
  const envelope = publicEnvelope(message);
  for (const ws of androids.values()) safeSend(ws, envelope);
  return envelope;
}

function parseMessage(data) {
  const value = JSON.parse(data.toString("utf8"));
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("message must be a JSON object");
  }
  return value;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
  if (req.method === "GET" && url.pathname === "/health") {
    return json(res, 200, {
      ok: true,
      service: "codex-mobile-relay",
      macConnected: mac?.readyState === WebSocket.OPEN,
      androidConnections: androids.size,
      sequence,
      time: now(),
    });
  }
  if (req.method === "GET" && url.pathname === "/") {
    return json(res, 200, { service: "codex-mobile-relay", endpoint: "/v1/ws" });
  }
  return json(res, 404, { error: "not_found" });
});

const wss = new WebSocketServer({ noServer: true, maxPayload });

server.on("upgrade", (req, socket, head) => {
  let url;
  try {
    url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
  } catch {
    socket.destroy();
    return;
  }
  if (url.pathname !== "/v1/ws") {
    socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  const role = url.searchParams.get("role");
  const token = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  const expected = role === "mac" ? macToken : role === "android" ? androidToken : "";
  const authorized = expected && token.length === expected.length && crypto.timingSafeEqual(Buffer.from(token), Buffer.from(expected));
  if (!authorized) {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  req.bridgeRole = role;
  req.bridgeDeviceId = url.searchParams.get("deviceId") || crypto.randomUUID();
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
});

wss.on("connection", (ws, req) => {
  const role = req.bridgeRole;
  const deviceId = req.bridgeDeviceId;
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });

  if (role === "mac") {
    if (mac && mac.readyState === WebSocket.OPEN) mac.close(4001, "replaced by newer Mac bridge");
    mac = ws;
    safeSend(ws, { type: "relay.ready", role, serverTime: now() });
    broadcastAndroid({ type: "relay.status", macConnected: true });
  } else {
    const old = androids.get(deviceId);
    if (old && old.readyState === WebSocket.OPEN) old.close(4001, "replaced by newer device connection");
    androids.set(deviceId, ws);
    const sinceRaw = new URL(req.url, "http://localhost").searchParams.get("since");
    const since = sinceRaw === null ? null : Number(sinceRaw);
    safeSend(ws, {
      type: "relay.ready",
      role,
      deviceId,
      macConnected: mac?.readyState === WebSocket.OPEN,
      currentSequence: sequence,
      serverTime: now(),
    });
    if (since !== null && Number.isFinite(since) && since >= 0) {
      for (const item of replay) if (item.seq > since) safeSend(ws, item);
    }
  }

  ws.on("message", (data, isBinary) => {
    if (isBinary) {
      safeSend(ws, { type: "relay.error", code: "binary_not_supported" });
      return;
    }
    let message;
    try {
      message = parseMessage(data);
    } catch (error) {
      safeSend(ws, { type: "relay.error", code: "invalid_json", message: error.message });
      return;
    }

    if (role === "mac") {
      if (!["event", "response", "snapshot", "status"].includes(message.type)) {
        safeSend(ws, { type: "relay.error", code: "unsupported_mac_message" });
        return;
      }
      broadcastAndroid(message);
      return;
    }

    if (message.type !== "request" || typeof message.requestId !== "string" || typeof message.action !== "string") {
      safeSend(ws, { type: "relay.error", code: "invalid_request" });
      return;
    }
    if (!safeSend(mac, { ...message, deviceId, receivedAt: now() })) {
      safeSend(ws, {
        type: "response",
        requestId: message.requestId,
        ok: false,
        error: { code: "mac_offline", message: "Mac bridge is not connected" },
      });
    }
  });

  ws.on("close", () => {
    if (role === "mac" && mac === ws) {
      mac = null;
      broadcastAndroid({ type: "relay.status", macConnected: false });
    } else if (role === "android" && androids.get(deviceId) === ws) {
      androids.delete(deviceId);
    }
  });
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 25_000);

server.listen(port, host, () => {
  console.log(JSON.stringify({ event: "listening", host, port, time: now() }));
});

function shutdown(signal) {
  clearInterval(heartbeat);
  for (const ws of wss.clients) ws.close(1001, signal);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
