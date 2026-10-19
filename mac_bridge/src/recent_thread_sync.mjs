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
} = {}) {
  const selected = selectRecentlyActiveThreads(threads, { nowMs, windowMs });
  const data = await mapWithConcurrency(selected, concurrency, async (thread) => {
    try {
      return await syncLatestVisibleMessages(
        rpc,
        thread.id,
        getSourceThread(thread),
        { knownRevision: String(knownRevisions[thread.id] ?? ""), limit },
      );
    } catch (error) {
      return {
        threadId: thread.id,
        failed: true,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  });
  return {
    checkedAt: new Date(nowMs).toISOString(),
    windowMs,
    checkedCount: selected.length,
    changedCount: data.filter((entry) => !entry.failed && !entry.notModified).length,
    failedCount: data.filter((entry) => entry.failed).length,
    data,
  };
}
