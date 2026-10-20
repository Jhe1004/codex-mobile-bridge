import assert from "node:assert/strict";
import { responseEnvelopes } from "../mac_bridge/src/response_chunking.mjs";

const envelope = {
  type: "response",
  requestId: "request-1",
  ok: true,
  result: { messages: [{ text: "长内容".repeat(2_000) }] },
};
const parts = responseEnvelopes(envelope, 512);
assert.ok(parts.length > 1);
assert.ok(parts.every((part, index) =>
  part.type === "response.chunk" &&
  part.requestId === envelope.requestId &&
  part.index === index &&
  part.total === parts.length));
const restored = JSON.parse(Buffer.concat(parts.map((part) => Buffer.from(part.data, "base64"))).toString("utf8"));
assert.deepEqual(restored, envelope);

const direct = responseEnvelopes({ type: "response", requestId: "small", ok: true, result: {} }, 4_096);
assert.equal(direct.length, 1);
assert.equal(direct[0].type, "response");

console.log(JSON.stringify({ ok: true, chunks: parts.length, preservesUtf8: true }));
