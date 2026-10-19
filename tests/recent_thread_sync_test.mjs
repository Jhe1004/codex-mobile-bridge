import assert from "node:assert/strict";
import {
  RECENT_THREAD_WINDOW_MS,
  selectRecentlyActiveThreads,
  syncRecentlyActiveThreads,
  threadUpdatedAtMs,
} from "../mac_bridge/src/recent_thread_sync.mjs";

const nowMs = Date.UTC(2026, 9, 19, 12, 0, 0);
const recentSeconds = Math.trunc((nowMs - 1_000) / 1000);
const recentMilliseconds = nowMs - 2_000;
const oldSeconds = Math.trunc((nowMs - RECENT_THREAD_WINDOW_MS - 1_000) / 1000);
const threads = [
  { id: "recent-seconds", updatedAt: recentSeconds, mobileActivity: "idle" },
  { id: "recent-ms", updatedAt: recentMilliseconds, mobileActivity: "idle" },
  { id: "old-running", updatedAt: oldSeconds, mobileActivity: "running" },
  { id: "old-idle", updatedAt: oldSeconds, mobileActivity: "idle" },
];

assert.equal(threadUpdatedAtMs(threads[0]), recentSeconds * 1000);
assert.equal(threadUpdatedAtMs(threads[1]), recentMilliseconds);
assert.deepEqual(
  selectRecentlyActiveThreads(threads, { nowMs }).map((thread) => thread.id),
  ["recent-seconds", "recent-ms", "old-running"],
);

let activeCalls = 0;
let maximumActiveCalls = 0;
const knownRevisions = { "recent-seconds": "known-revision" };
const result = await syncRecentlyActiveThreads(async (_method, params) => {
  activeCalls += 1;
  maximumActiveCalls = Math.max(maximumActiveCalls, activeCalls);
  await new Promise((resolve) => setTimeout(resolve, 5));
  activeCalls -= 1;
  if (params.threadId === "recent-ms") throw new Error("isolated failure");
  return {
    data: [{ turnId: "turn", item: { type: "agentMessage", id: params.threadId, text: params.threadId } }],
    nextCursor: null,
  };
}, threads, {
  knownRevisions,
  nowMs,
  concurrency: 2,
  getSourceThread: (thread) => ({ ...thread, sourceMarker: true }),
});

assert.equal(result.checkedCount, 3);
assert.equal(result.changedCount, 2);
assert.equal(result.failedCount, 1);
assert.equal(result.data.length, 3);
assert.equal(result.data[1].threadId, "recent-ms");
assert.equal(result.data[1].failed, true);
assert.match(result.data[1].error, /isolated failure/);
assert.ok(maximumActiveCalls <= 2);
assert.ok(maximumActiveCalls >= 2);
assert.ok(!result.data.some((entry) => entry.threadId === "old-idle"));

console.log(JSON.stringify({
  ok: true,
  checkedCount: result.checkedCount,
  failedCount: result.failedCount,
  maximumActiveCalls,
}));
