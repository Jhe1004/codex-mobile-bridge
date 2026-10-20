import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { createThreadStatusAnalyzer, inspectRolloutTail } from "../mac_bridge/src/thread_status.mjs";

const directory = mkdtempSync(path.join(os.tmpdir(), "codex-mobile-thread-status-"));
const runningPath = path.join(directory, "running.jsonl");
const completedPath = path.join(directory, "completed.jsonl");

writeFileSync(runningPath, [
  JSON.stringify({ timestamp: "2026-10-19T01:00:00Z", type: "event_msg", payload: { type: "task_complete", turn_id: "old-turn" } }),
  JSON.stringify({ timestamp: "2026-10-19T01:01:00Z", type: "turn_context", payload: { turn_id: "new-turn" } }),
  JSON.stringify({ timestamp: "2026-10-19T01:01:01Z", type: "response_item", payload: { type: "reasoning" } }),
].join("\n") + "\n");

writeFileSync(completedPath, [
  JSON.stringify({ timestamp: "2026-10-19T01:02:00Z", type: "turn_context", payload: { turn_id: "done-turn" } }),
  JSON.stringify({ timestamp: "2026-10-19T01:03:00Z", type: "event_msg", payload: { type: "task_complete", turn_id: "done-turn" } }),
].join("\n") + "\n");

assert.deepEqual(inspectRolloutTail(runningPath), {
  activity: "running",
  completionRevision: "",
  completedAt: 0,
  activeTurnId: "new-turn",
});
assert.equal(inspectRolloutTail(completedPath)?.activity, "idle");
assert.equal(inspectRolloutTail(completedPath)?.completionRevision, "done-turn");

const annotate = createThreadStatusAnalyzer();
const annotated = annotate([
  { id: "running", path: runningPath, updatedAt: 10, status: { type: "notLoaded" } },
  { id: "completed", path: completedPath, updatedAt: 20, status: { type: "notLoaded" } },
  { id: "native-active", updatedAt: 30, status: { type: "active" } },
]);
assert.deepEqual(annotated.map((thread) => thread.mobileActivity), ["running", "idle", "running"]);
assert.equal(annotated[1].completionRevision, "done-turn");
assert.match(annotated[1].contentRevision, /^file:/);
assert.equal(annotated[2].completionRevision, "");

console.log("thread status projection: ok");
