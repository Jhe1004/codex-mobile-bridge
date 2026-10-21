import { randomUUID } from "node:crypto";
import { DesktopOwnerUnavailableError } from "./desktop_ipc.mjs";
import { DESKTOP_HYDRATION_TIMEOUT_MS } from "./desktop_prepare.mjs";

export { DESKTOP_HYDRATION_TIMEOUT_MS } from "./desktop_prepare.mjs";

function validatedAdditionalContext(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const entries = Object.entries(value).flatMap(([sourceId, entry]) => {
    if (!sourceId.trim() || !entry || typeof entry !== "object" || Array.isArray(entry)) return [];
    if (!["application", "untrusted"].includes(entry.kind)) return [];
    if (typeof entry.value !== "string" || !entry.value.trim()) return [];
    return [[sourceId, { kind: entry.kind, value: entry.value }]];
  });
  return entries.length > 0 ? Object.fromEntries(entries) : null;
}

export async function startTurnOnThread(rpc, payload = {}, {
  desktopIpc,
  preferBridge = false,
  prepareDesktopThread,
  wakeDesktopThread,
  onBridgeOwnership,
} = {}) {
  const attachmentInput = Array.isArray(payload.attachmentInput) ? payload.attachmentInput : [];
  if (!payload.threadId || typeof payload.text !== "string" || (!payload.text.trim() && attachmentInput.length === 0)) {
    throw new Error("threadId and text or attachments are required");
  }

  const text = payload.text.trim() || "请查看我附加的文件。";
  const input = [{ type: "text", text, text_elements: [] }, ...attachmentInput];
  const clientUserMessageId = payload.clientUserMessageId || randomUUID();
  const additionalContext = validatedAdditionalContext(payload.additionalContext);
  const turnRequest = {
    threadId: payload.threadId,
    input,
    clientUserMessageId,
    ...(additionalContext ? { additionalContext } : {}),
  };

  // Desktop owns most saved conversations. Its IPC follower path asks that
  // exact owner to start the turn immediately, even while the chat is closed.
  if (desktopIpc && !preferBridge) {
    try {
      const accepted = await desktopIpc.startTurn(
        payload.threadId,
        input,
        clientUserMessageId,
        null,
        payload.desktopAttachments ?? [],
        additionalContext,
      );
      return {
        accepted: true,
        delivery: "desktop-ipc",
        clientUserMessageId,
        ownerClientId: accepted.ownerClientId,
        attachmentContextInjected: attachmentInput.length === 0 || Boolean(additionalContext),
      };
    } catch (error) {
      if (!(error instanceof DesktopOwnerUnavailableError)) throw error;
    }

    // A saved desktop thread may not currently have a renderer owner. Ask macOS
    // to hydrate that exact thread in Codex, then retry through the supported
    // follower path. This keeps Desktop as the sole writer whenever possible.
    if (prepareDesktopThread || (wakeDesktopThread && typeof desktopIpc.waitForThreadOwner === "function")) {
      try {
        let ownerClientId;
        if (prepareDesktopThread) {
          const prepared = await prepareDesktopThread(payload.threadId);
          ownerClientId = prepared?.ownerClientId ?? prepared;
        } else {
          await wakeDesktopThread(payload.threadId);
          // Large saved chats can take well over ten seconds to hydrate in the
          // Desktop renderer. Keep the original phone request pending until the
          // renderer has had a realistic chance to register as the sole writer.
          ownerClientId = await desktopIpc.waitForThreadOwner(payload.threadId, {
            timeoutMs: DESKTOP_HYDRATION_TIMEOUT_MS,
          });
        }
        if (ownerClientId) {
          const accepted = await desktopIpc.startTurn(
            payload.threadId,
            input,
            clientUserMessageId,
            ownerClientId,
            payload.desktopAttachments ?? [],
            additionalContext,
          );
          return {
            accepted: true,
            delivery: "desktop-ipc-after-wake",
            clientUserMessageId,
            ownerClientId: accepted.ownerClientId,
            attachmentContextInjected: attachmentInput.length === 0 || Boolean(additionalContext),
          };
        }
      } catch (error) {
        if (!(error instanceof DesktopOwnerUnavailableError)) throw error;
      }
    }
  }

  // If Desktop is not the owner (for example, a mobile-created thread), this
  // bridge app-server can own and start it directly. Never report a queued-only
  // submission as sent.
  onBridgeOwnership?.(payload.threadId);
  try {
    const result = await rpc("turn/start", turnRequest);
    return {
      ...result,
      delivery: "bridge-app-server",
      clientUserMessageId,
      attachmentContextInjected: attachmentInput.length === 0 || Boolean(additionalContext),
    };
  } catch (error) {
    if (!String(error?.message ?? error).toLowerCase().includes("not loaded")) throw error;
  }

  await rpc("thread/resume", { threadId: payload.threadId, excludeTurns: true });
  const result = await rpc("turn/start", turnRequest);
  return {
    ...result,
    delivery: "bridge-app-server",
    clientUserMessageId,
    attachmentContextInjected: attachmentInput.length === 0 || Boolean(additionalContext),
  };
}

export function readableAppServerError(error) {
  const raw = error instanceof Error ? error.message : String(error);
  try {
    const parsed = JSON.parse(raw);
    return parsed.message || parsed.data?.message || raw;
  } catch {
    return raw;
  }
}
