import assert from "node:assert/strict";
import { appendFile, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { syncLatestVisibleMessages, threadContentRevision, visibleMessageRevision } from "../mac_bridge/src/thread_sync.mjs";

const root = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-thread-sync-"));
try {
  const rollout = path.join(root, "rollout.jsonl");
  await writeFile(rollout, "first\n");
  const thread = { id: "thread-1", path: rollout, updatedAt: 1 };
  const firstSourceRevision = threadContentRevision(thread);
  let rpcCalls = 0;
  const first = await syncLatestVisibleMessages(async (method) => {
    rpcCalls += 1;
    assert.equal(method, "thread/items/list");
    return {
      data: [{ turnId: "turn-1", item: { type: "agentMessage", id: "a1", text: "old" } }],
      nextCursor: null,
    };
  }, thread.id, thread, { knownRevision: firstSourceRevision });
  assert.equal(first.notModified, false);
  assert.equal(first.messages[0].text, "old");
  assert.match(first.revision, /^messages:/);
  assert.equal(rpcCalls, 1);

  const unchanged = await syncLatestVisibleMessages(async () => {
    rpcCalls += 1;
    return {
      data: [{ turnId: "turn-1", item: { type: "agentMessage", id: "a1", text: "old" } }],
      nextCursor: null,
    };
  }, thread.id, thread, { knownRevision: first.revision });
  assert.equal(unchanged.notModified, true);
  assert.equal(rpcCalls, 2);
  assert.equal(unchanged.revision, first.revision);

  // A restored or compacted Desktop chat can continue in a replacement
  // rollout while the original file signature remains unchanged.
  const migrated = await syncLatestVisibleMessages(async () => {
    rpcCalls += 1;
    return {
      data: [
        { turnId: "turn-2", item: { type: "agentMessage", id: "a2", text: "new from continuation" } },
        { turnId: "turn-1", item: { type: "agentMessage", id: "a1", text: "old" } },
      ],
      nextCursor: null,
    };
  }, thread.id, thread, { knownRevision: first.revision });
  assert.equal(migrated.notModified, false);
  assert.equal(migrated.messages.at(-1).text, "new from continuation");
  assert.notEqual(migrated.revision, first.revision);
  assert.equal(migrated.sourceRevision, firstSourceRevision);

  await new Promise((resolve) => setTimeout(resolve, 5));
  await appendFile(rollout, "second\n");
  const changed = await syncLatestVisibleMessages(async (method) => {
    rpcCalls += 1;
    assert.equal(method, "thread/items/list");
    return {
      data: [{ turnId: "turn-1", item: { type: "agentMessage", id: "a1", text: "new" } }],
      nextCursor: null,
    };
  }, thread.id, thread, { knownRevision: migrated.revision });
  assert.equal(changed.notModified, false);
  assert.equal(changed.messages[0].text, "new");
  assert.notEqual(changed.sourceRevision, firstSourceRevision);
  assert.equal(rpcCalls, 4);
  assert.equal(visibleMessageRevision(changed.messages), changed.revision);
  console.log(JSON.stringify({ ok: true, verifiesActualMessages: true, detectsContinuationHistory: true }));
} finally {
  await rm(root, { recursive: true, force: true });
}
