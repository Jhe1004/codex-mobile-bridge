const DEFAULT_TTL_MS = 12 * 60 * 60 * 1000;
const COMPLETION_CLOCK_SLOP_MS = 5_000;

export class DesktopCompletionReconciler {
  constructor({ desktopIpc, now = () => Date.now(), ttlMs = DEFAULT_TTL_MS, onEvent = () => {} } = {}) {
    if (!desktopIpc) throw new Error("desktopIpc is required");
    this.desktopIpc = desktopIpc;
    this.now = now;
    this.ttlMs = ttlMs;
    this.onEvent = onEvent;
    this.pending = new Map();
  }

  track({ threadId, ownerClientId, clientUserMessageId, baselineRevision = "", startedAtMs = this.now() }) {
    if (!threadId) throw new Error("threadId is required");
    this.pending.set(threadId, {
      threadId,
      ownerClientId: ownerClientId || null,
      clientUserMessageId: clientUserMessageId || null,
      baselineRevision: String(baselineRevision || ""),
      startedAtMs,
      seenRunning: false,
      reconciling: false,
      attempts: 0,
    });
  }

  observe(threads = []) {
    const byId = new Map(threads.map((thread) => [thread.id, thread]));
    for (const entry of this.pending.values()) {
      if (this.now() - entry.startedAtMs > this.ttlMs) {
        if (!entry.reconciling) void this.#release(entry, "desktop_turn_follow_expired");
        continue;
      }
      const thread = byId.get(entry.threadId);
      if (!thread) continue;
      if (thread.mobileActivity === "running") {
        entry.seenRunning = true;
        continue;
      }
      const revision = String(thread.completionRevision || "");
      const revisionAdvanced = Boolean(revision && revision !== entry.baselineRevision);
      const completedAt = Number(thread.completedAt || 0);
      const terminalAfterStart = completedAt >= entry.startedAtMs - COMPLETION_CLOCK_SLOP_MS;
      if (!terminalAfterStart && !(entry.seenRunning && revisionAdvanced)) continue;
      if (!entry.reconciling) void this.#release(entry, "desktop_turn_follow_released", revision);
    }
  }

  async #release(entry, event, completionRevision = "") {
    entry.reconciling = true;
    entry.attempts += 1;
    try {
      await this.desktopIpc.setThreadFollowing(entry.threadId, entry.ownerClientId, false);
      this.pending.delete(entry.threadId);
      this.onEvent(event, {
        threadId: entry.threadId,
        ownerClientId: entry.ownerClientId,
        completionRevision,
        attempts: entry.attempts,
      });
    } catch (error) {
      entry.reconciling = false;
      this.onEvent("desktop_turn_follow_release_failed", {
        threadId: entry.threadId,
        error: error instanceof Error ? error.message : String(error),
        attempts: entry.attempts,
      });
    }
  }
}
