import assert from "node:assert/strict";
import { appendFile, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { syncLatestVisibleMessages, threadContentRevision } from "../mac_bridge/src/thread_sync.mjs";

const root = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-thread-sync-"));
try {
  const rollout = path.join(root, "rollout.jsonl");
  await writeFile(rollout, "first\n");
  const thread = { id: "thread-1", path: rollout, updatedAt: 1 };
  const firstRevision = threadContentRevision(thread);
  let rpcCalls = 0;
  const unchanged = await syncLatestVisibleMessages(async () => {
    rpcCalls += 1;
    throw new Error("unchanged cache must not read full history");
  }, thread.id, thread, { knownRevision: firstRevision });
  assert.equal(unchanged.notModified, true);
  assert.equal(rpcCalls, 0);

  await new Promise((resolve) => setTimeout(resolve, 5));
  await appendFile(rollout, "second\n");
  const changed = await syncLatestVisibleMessages(async (method) => {
    rpcCalls += 1;
    assert.equal(method, "thread/items/list");
    return {
      data: [{ turnId: "turn-1", item: { type: "agentMessage", id: "a1", text: "new" } }],
      nextCursor: null,
    };
  }, thread.id, thread, { knownRevision: firstRevision });
  assert.equal(changed.notModified, false);
  assert.equal(changed.messages[0].text, "new");
  assert.notEqual(changed.revision, firstRevision);
  assert.equal(rpcCalls, 1);
  console.log(JSON.stringify({ ok: true, skipsUnchangedHistory: true, syncsChangedHistory: true }));
} finally {
  await rm(root, { recursive: true, force: true });
}
