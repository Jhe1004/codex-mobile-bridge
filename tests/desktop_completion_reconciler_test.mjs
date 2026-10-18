import assert from "node:assert/strict";
import { DesktopCompletionReconciler } from "../mac_bridge/src/desktop_completion_reconciler.mjs";

const calls = [];
const events = [];
let now = 10_000;
const reconciler = new DesktopCompletionReconciler({
  now: () => now,
  desktopIpc: {
    async setThreadFollowing(threadId, ownerClientId, following) {
      calls.push(["set-following", threadId, ownerClientId, following]);
    },
  },
  onEvent(event, detail) { events.push([event, detail]); },
});

reconciler.track({
  threadId: "thread-1",
  ownerClientId: "original-owner",
  clientUserMessageId: "message-1",
  baselineRevision: "updated:1",
  startedAtMs: now,
});

reconciler.observe([{
  id: "thread-1",
  mobileActivity: "running",
  completionRevision: "updated:2",
  completedAt: 0,
}]);
await new Promise((resolve) => setImmediate(resolve));
assert.deepEqual(calls, []);

now += 3_000;
reconciler.observe([{
  id: "thread-1",
  mobileActivity: "idle",
  completionRevision: "updated:3",
  completedAt: now,
}]);
await new Promise((resolve) => setImmediate(resolve));
assert.deepEqual(calls, [
  ["set-following", "thread-1", "original-owner", false],
]);
assert.equal(events.at(-1)[0], "desktop_turn_follow_released");

const expiredEvents = [];
const expired = new DesktopCompletionReconciler({
  now: () => now,
  ttlMs: 1,
  desktopIpc: {
    async setThreadFollowing(threadId, ownerClientId, following) {
      calls.push(["expired-following", threadId, ownerClientId, following]);
    },
  },
  onEvent(event, detail) { expiredEvents.push([event, detail]); },
});
expired.track({ threadId: "thread-2", ownerClientId: "old-owner", startedAtMs: now - 10 });
expired.observe([]);
await new Promise((resolve) => setImmediate(resolve));
assert.equal(expiredEvents.at(-1)[0], "desktop_turn_follow_expired");

console.log(JSON.stringify({ ok: true, keepsOwnerUntilCompletion: true, releasesFollower: true }));
