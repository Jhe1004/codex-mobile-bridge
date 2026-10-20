import { closeSync, fstatSync, openSync, readSync } from "node:fs";

const MAX_TAIL_BYTES = 8 * 1024 * 1024;
const ACTIVE_STATUS_TYPES = new Set(["active", "running", "inProgress", "in_progress"]);

function fallbackRevision(thread) {
  const updatedAt = Number(thread.updatedAt ?? thread.updated_at ?? 0);
  return updatedAt > 0 ? `updated:${updatedAt}` : "";
}

export function inspectRolloutTail(filePath, maxBytes = MAX_TAIL_BYTES) {
  if (!filePath || typeof filePath !== "string") return null;
  let file;
  try {
    file = openSync(filePath, "r");
    const stat = fstatSync(file);
    const length = Math.min(stat.size, maxBytes);
    if (length <= 0) return { activity: "idle", completionRevision: "", completedAt: 0 };
    const buffer = Buffer.allocUnsafe(length);
    readSync(file, buffer, 0, length, stat.size - length);
    let text = buffer.toString("utf8");
    if (length < stat.size) text = text.slice(text.indexOf("\n") + 1);
    const lines = text.trimEnd().split("\n");
    for (let index = lines.length - 1; index >= 0; index -= 1) {
      let record;
      try { record = JSON.parse(lines[index]); }
      catch { continue; }
      if (record.type === "event_msg") {
        const eventType = record.payload?.type;
        if (eventType === "task_complete" || eventType === "turn_aborted") {
          const turnId = String(record.payload?.turn_id ?? record.payload?.id ?? "");
          return {
            activity: "idle",
            completionRevision: turnId || `completed:${record.timestamp ?? stat.mtimeMs}`,
            completedAt: Date.parse(record.timestamp ?? "") || stat.mtimeMs,
          };
        }
      }
      if (record.type === "turn_context") {
        return {
          activity: "running",
          completionRevision: "",
          completedAt: 0,
          activeTurnId: String(record.payload?.turn_id ?? ""),
        };
      }
    }
    return null;
  } catch {
    return null;
  } finally {
    if (file !== undefined) closeSync(file);
  }
}

export function createThreadStatusAnalyzer({ maxInspectedThreads = 60 } = {}) {
  const cache = new Map();
  return function annotateThreadStatuses(threads) {
    return threads.map((thread, index) => {
      let observed = null;
      let signature = "";
      if (thread.path) {
        try {
          const file = openSync(thread.path, "r");
          try {
            const stat = fstatSync(file);
            signature = `file:${stat.size}:${Math.trunc(stat.mtimeMs)}`;
          } finally {
            closeSync(file);
          }
        } catch {}
      }
      if (index < maxInspectedThreads && thread.path) {
        const cached = cache.get(thread.path);
        if (signature && cached?.signature === signature) observed = cached.observed;
        else {
          observed = inspectRolloutTail(thread.path);
          if (signature) cache.set(thread.path, { signature, observed });
        }
      }
      const nativeType = String(thread.status?.type ?? thread.status ?? "");
      const activity = observed?.activity ?? (ACTIVE_STATUS_TYPES.has(nativeType) ? "running" : "idle");
      return {
        ...thread,
        mobileActivity: activity,
        contentRevision: signature || fallbackRevision(thread),
        // Unread state must follow a real completed turn. Codex can change
        // updatedAt for metadata-only writes, which must never create a false
        // "new reply" badge on Android. Threads outside the inspected window
        // deliberately expose no completion cursor instead of guessing.
        completionRevision: observed?.completionRevision ?? "",
        completedAt: observed?.completedAt ?? 0,
      };
    });
  };
}
