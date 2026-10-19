import { realpath } from "node:fs/promises";
import path from "node:path";

const NEGATIVE_CACHE_MS = 5_000;

function normalizeMarkdownDestination(rawDestination) {
  let value = String(rawDestination ?? "").trim();
  if (value.startsWith("<") && value.endsWith(">")) value = value.slice(1, -1);
  else value = value.split(/\s+/u, 1)[0] ?? "";
  value = value.replace(/\\([\\() ])/g, "$1");
  if (!value || value.startsWith("#")) return null;
  if (value.startsWith("file://")) {
    try { value = new URL(value).pathname; } catch { return null; }
  }
  value = value.split("#", 1)[0];
  try { value = decodeURIComponent(value); } catch { return null; }
  const location = value.match(/^(\/.*?):(\d+)(?::\d+)?$/);
  if (location) value = location[1];
  if (!path.isAbsolute(value)) return null;
  return path.normalize(value);
}

export function extractLocalFileLinkTargets(text) {
  const targets = new Set();
  const markdownLink = /!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|(?:\\.|[^)\n])+)\s*\)/g;
  for (const match of String(text ?? "").matchAll(markdownLink)) {
    const target = normalizeMarkdownDestination(match[1]);
    if (target) targets.add(target);
  }
  return [...targets];
}

export async function listAssistantMessagesForFileLinks(rpc, threadId, maxPages = 8) {
  const messages = [];
  let cursor = null;
  let pageCount = 0;
  do {
    const page = await rpc("thread/items/list", {
      threadId,
      cursor,
      limit: 200,
      sortDirection: "desc",
    });
    for (const entry of page.data ?? []) {
      if (entry?.item?.type === "agentMessage" && typeof entry.item.text === "string") {
        messages.push({ role: "assistant", text: entry.item.text });
      }
    }
    cursor = page.nextCursor ?? null;
    pageCount += 1;
  } while (cursor && pageCount < maxPages);
  return messages;
}

export function createThreadFileLinkAuthorizer({ loadMessages }) {
  if (typeof loadMessages !== "function") throw new Error("loadMessages is required");
  const allowedByThread = new Map();
  const negativeUntil = new Map();

  async function rememberMessages(threadId, messages = []) {
    const allowed = allowedByThread.get(threadId) ?? new Set();
    for (const message of messages) {
      if (message?.role !== "assistant") continue;
      for (const target of extractLocalFileLinkTargets(message.text)) {
        try { allowed.add(await realpath(target)); } catch { /* Ignore stale links. */ }
      }
    }
    allowedByThread.set(threadId, allowed);
    return allowed.size;
  }

  async function authorize(threadId, resolvedPath) {
    if (!threadId || !resolvedPath) return false;
    if (allowedByThread.get(threadId)?.has(resolvedPath)) return true;
    const cacheKey = `${threadId}\0${resolvedPath}`;
    if ((negativeUntil.get(cacheKey) ?? 0) > Date.now()) return false;
    const messages = await loadMessages(threadId);
    await rememberMessages(threadId, messages);
    if (allowedByThread.get(threadId)?.has(resolvedPath)) {
      negativeUntil.delete(cacheKey);
      return true;
    }
    negativeUntil.set(cacheKey, Date.now() + NEGATIVE_CACHE_MS);
    return false;
  }

  return { authorize, rememberMessages };
}
