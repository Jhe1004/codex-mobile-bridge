import { statSync } from "node:fs";
import { createHash } from "node:crypto";
import { listLatestVisibleMessages } from "./thread_messages.mjs";

export function threadContentRevision(thread = {}) {
  const updatedAt = Number(thread.updatedAt ?? thread.updated_at ?? 0);
  const parts = [];
  if (updatedAt > 0) parts.push(`updated:${updatedAt}`);
  if (thread.path) {
    try {
      const info = statSync(thread.path);
      parts.push(`file:${info.size}:${Math.trunc(info.mtimeMs)}`);
    } catch {}
  }
  if (parts.length > 0) return parts.join("|");
  return String(thread.contentRevision || thread.completionRevision || "");
}

export function visibleMessageRevision(messages = []) {
  const hash = createHash("sha256");
  for (const message of messages) {
    hash.update(String(message.id ?? ""));
    hash.update("\0");
    hash.update(String(message.role ?? ""));
    hash.update("\0");
    hash.update(String(message.clientId ?? ""));
    hash.update("\0");
    hash.update(String(message.text ?? ""));
    hash.update("\0");
    hash.update(message.truncated ? "1" : "0");
    hash.update("\n");
  }
  return `messages:${messages.length}:${hash.digest("hex")}`;
}

export async function syncLatestVisibleMessages(rpc, threadId, thread, {
  knownRevision = "",
  limit = 120,
} = {}) {
  // A thread can continue in a replacement rollout after context compaction,
  // migration, or Desktop restoration. The original file signature can then
  // remain unchanged while Codex already has newer messages. For correctness,
  // always ask app-server for the current visible tail and only return
  // notModified after comparing a digest of the actual messages.
  const result = await listLatestVisibleMessages(rpc, threadId, limit);
  const revision = visibleMessageRevision(result.messages);
  if (knownRevision && knownRevision === revision) {
    return {
      threadId,
      messages: [],
      notModified: true,
      revision,
      sourceRevision: threadContentRevision(thread),
      syncedAt: new Date().toISOString(),
    };
  }
  return {
    ...result,
    notModified: false,
    revision,
    sourceRevision: threadContentRevision(thread),
  };
}
