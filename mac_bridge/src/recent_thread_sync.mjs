import { syncLatestVisibleMessages } from "./thread_sync.mjs";

export const RECENT_THREAD_WINDOW_MS = 24 * 60 * 60 * 1000;
export const RECENT_THREAD_SYNC_CONCURRENCY = 4;

export function threadUpdatedAtMs(thread = {}) {
  const raw = Number(thread.updatedAt ?? thread.updated_at ?? 0);
  if (!Number.isFinite(raw) || raw <= 0) return 0;
  return raw < 1_000_000_000_000 ? Math.trunc(raw * 1000) : Math.trunc(raw);
}

export function selectRecentlyActiveThreads(threads = [], {
  nowMs = Date.now(),
  windowMs = RECENT_THREAD_WINDOW_MS,
} = {}) {
  const cutoff = nowMs - windowMs;
  return threads.filter((thread) =>
    thread?.id && (thread.mobileActivity === "running" || threadUpdatedAtMs(thread) >= cutoff));
}

async function mapWithConcurrency(items, concurrency, worker) {
  const results = new Array(items.length);
  let nextIndex = 0;
  async function runWorker() {
    while (true) {
      const index = nextIndex++;
      if (index >= items.length) return;
      results[index] = await worker(items[index], index);
    }
  }
  const workerCount = Math.min(Math.max(1, concurrency), Math.max(1, items.length));
  await Promise.all(Array.from({ length: workerCount }, runWorker));
  return results;
}

export async function syncRecentlyActiveThreads(rpc, threads, {
  knownRevisions = {},
  getSourceThread = (thread) => thread,
  nowMs = Date.now(),
  windowMs = RECENT_THREAD_WINDOW_MS,
  limit = 120,
  concurrency = RECENT_THREAD_SYNC_CONCURRENCY,
  onProgress = null,
} = {}) {
  const selected = selectRecentlyActiveThreads(threads, { nowMs, windowMs });
  let completedCount = 0;
  let failedCount = 0;
  let changedCount = 0;
  function reportProgress() {
    if (typeof onProgress !== "function") return;
    try {
      onProgress({
        completedCount,
        totalCount: selected.length,
        failedCount,
        changedCount,
      });
    } catch {}
  }
  reportProgress();
  const data = await mapWithConcurrency(selected, concurrency, async (thread) => {
    let entry;
    try {
      entry = await syncLatestVisibleMessages(
        rpc,
        thread.id,
        getSourceThread(thread),
        { knownRevision: String(knownRevisions[thread.id] ?? ""), limit },
      );
    } catch (error) {
      entry = {
        threadId: thread.id,
        failed: true,
        error: error instanceof Error ? error.message : String(error),
      };
    }
    completedCount += 1;
    if (entry.failed) failedCount += 1;
    else if (!entry.notModified) changedCount += 1;
    reportProgress();
    return entry;
  });
  return {
    checkedAt: new Date(nowMs).toISOString(),
    windowMs,
    checkedCount: selected.length,
    changedCount,
    failedCount,
    data,
  };
}
