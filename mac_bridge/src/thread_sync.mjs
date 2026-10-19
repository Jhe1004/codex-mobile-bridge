import { statSync } from "node:fs";
import { listLatestVisibleMessages } from "./thread_messages.mjs";

export function threadContentRevision(thread = {}) {
  if (thread.path) {
    try {
      const info = statSync(thread.path);
      return `file:${info.size}:${Math.trunc(info.mtimeMs)}`;
    } catch {}
  }
  const updatedAt = Number(thread.updatedAt ?? thread.updated_at ?? 0);
  if (updatedAt > 0) return `updated:${updatedAt}`;
  return String(thread.contentRevision || thread.completionRevision || "");
}

export async function syncLatestVisibleMessages(rpc, threadId, thread, {
  knownRevision = "",
  limit = 120,
} = {}) {
  const before = threadContentRevision(thread);
  // A rollout-file signature can be checked without asking app-server for the
  // conversation. Updated-at fallbacks are not authoritative enough to skip a
  // read, so pathless threads deliberately keep the older full-sync behavior.
  if (knownRevision && before.startsWith("file:") && knownRevision === before) {
    return {
      threadId,
      messages: [],
      notModified: true,
      revision: before,
      syncedAt: new Date().toISOString(),
    };
  }

  const result = await listLatestVisibleMessages(rpc, threadId, limit);
  const after = threadContentRevision(thread) || before;
  return { ...result, notModified: false, revision: after };
}
