const MAX_TEXT_CHARACTERS = 24_000;

function userText(item) {
  if (typeof item.text === "string" && item.text.trim()) return item.text;
  if (!Array.isArray(item.content)) return "";
  return item.content
    .map((part) => typeof part?.text === "string" ? part.text : "")
    .filter(Boolean)
    .join("\n");
}

export function visibleMessagesFromEntries(entries = []) {
  return entries.flatMap((entry) => {
    const item = entry?.item;
    if (!item || !["userMessage", "agentMessage"].includes(item.type)) return [];
    const text = item.type === "userMessage" ? userText(item) : String(item.text ?? "");
    if (!text.trim()) return [];
    return [{
      id: String(item.id || `${entry.turnId || "turn"}-${entry.startedAtMs || 0}`),
      clientId: item.type === "userMessage" ? String(item.clientId ?? "") : "",
      role: item.type === "userMessage" ? "user" : "assistant",
      text: text.slice(0, MAX_TEXT_CHARACTERS),
      truncated: text.length > MAX_TEXT_CHARACTERS,
      turnId: String(entry.turnId ?? ""),
      startedAtMs: Number(entry.startedAtMs ?? 0),
      completedAtMs: Number(entry.completedAtMs ?? 0),
    }];
  });
}

export async function listLatestVisibleMessages(rpc, threadId, maxMessages = 2_000) {
  const newestFirst = [];
  let cursor = null;
  let pageCount = 0;
  do {
    const page = await rpc("thread/items/list", {
      threadId,
      cursor,
      limit: 200,
      sortDirection: "desc",
    });
    newestFirst.push(...visibleMessagesFromEntries(page.data ?? []));
    cursor = page.nextCursor ?? null;
    pageCount += 1;
  } while (cursor && newestFirst.length < maxMessages && pageCount < 40);

  return {
    threadId,
    messages: newestFirst.slice(0, maxMessages).reverse(),
    hasOlder: Boolean(cursor) || newestFirst.length > maxMessages,
    syncedAt: new Date().toISOString(),
  };
}
