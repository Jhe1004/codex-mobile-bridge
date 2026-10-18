import { DesktopOwnerUnavailableError } from "./desktop_ipc.mjs";

export const DESKTOP_HYDRATION_TIMEOUT_MS = 60_000;

export function createDesktopThreadPreparer({
  desktopIpc,
  wakeDesktopThread,
  timeoutMs = DESKTOP_HYDRATION_TIMEOUT_MS,
} = {}) {
  if (!desktopIpc || typeof desktopIpc.findThreadOwner !== "function") {
    throw new Error("desktopIpc with findThreadOwner is required");
  }
  if (typeof wakeDesktopThread !== "function") {
    throw new Error("wakeDesktopThread is required");
  }

  const inFlight = new Map();

  async function hydrate(threadId) {
    let ownerClientId = null;
    try {
      ownerClientId = await desktopIpc.findThreadOwner(threadId);
    } catch (error) {
      if (!(error instanceof DesktopOwnerUnavailableError)) throw error;
    }

    if (ownerClientId) {
      return { ready: true, ownerClientId, alreadyReady: true };
    }

    // This only asks Codex Desktop to load the saved chat. It never resumes the
    // thread through the bridge app-server and therefore never becomes a
    // competing writer.
    await wakeDesktopThread(threadId);
    ownerClientId = await desktopIpc.waitForThreadOwner(threadId, { timeoutMs });
    if (!ownerClientId) {
      throw new DesktopOwnerUnavailableError(
        `Codex Desktop did not finish preparing thread ${threadId} within ${timeoutMs} ms`,
      );
    }
    return { ready: true, ownerClientId, alreadyReady: false };
  }

  return {
    prepare(threadId) {
      if (!threadId) return Promise.reject(new Error("threadId is required"));
      const existing = inFlight.get(threadId);
      if (existing) return existing;

      let operation;
      operation = hydrate(threadId).finally(() => {
        if (inFlight.get(threadId) === operation) inFlight.delete(threadId);
      });
      inFlight.set(threadId, operation);
      return operation;
    },
    isPreparing(threadId) {
      return inFlight.has(threadId);
    },
  };
}
