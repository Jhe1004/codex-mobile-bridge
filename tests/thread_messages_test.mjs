import assert from "node:assert/strict";
import { listLatestVisibleMessages, visibleMessagesFromEntries } from "../mac_bridge/src/thread_messages.mjs";

const entries = [
  { turnId: "t2", startedAtMs: 4, item: { type: "agentMessage", id: "a2", text: "最新回复" } },
  { turnId: "t2", startedAtMs: 3, item: { type: "reasoning", id: "r2", summary: [] } },
  { turnId: "t1", startedAtMs: 2, item: { type: "userMessage", id: "u1", clientId: "client-u1", content: [{ type: "text", text: "用户问题" }] } },
  { turnId: "t1", startedAtMs: 1, item: { type: "commandExecution", id: "e1", command: "secret" } },
];

assert.deepEqual(
  visibleMessagesFromEntries(entries).map(({ id, role, text }) => ({ id, role, text })),
  [
    { id: "a2", role: "assistant", text: "最新回复" },
    { id: "u1", role: "user", text: "用户问题" },
  ],
);

const calls = [];
const result = await listLatestVisibleMessages(async (method, params) => {
  calls.push({ method, params });
  return { data: entries, nextCursor: null };
}, "thread-1", 120);

assert.equal(calls[0].method, "thread/items/list");
assert.equal(calls[0].params.sortDirection, "desc");
assert.deepEqual(result.messages.map((message) => message.id), ["u1", "a2"]);
assert.equal(result.messages[0].clientId, "client-u1");

let page = 0;
const fullHistory = await listLatestVisibleMessages(async () => {
  const currentPage = page++;
  return {
    data: Array.from({ length: 200 }, (_, index) => ({
      turnId: `turn-${currentPage}-${index}`,
      item: { type: "agentMessage", id: `message-${currentPage}-${index}`, text: "历史消息" },
    })),
    nextCursor: currentPage < 9 ? `cursor-${currentPage + 1}` : null,
  };
}, "thread-2000");
assert.equal(fullHistory.messages.length, 2_000);
assert.equal(page, 10);
console.log(JSON.stringify({ ok: true, messageCount: result.messages.length }));
