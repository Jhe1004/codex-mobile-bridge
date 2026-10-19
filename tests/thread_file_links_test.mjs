import assert from "node:assert/strict";
import { mkdtemp, realpath, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  createThreadFileLinkAuthorizer,
  extractLocalFileLinkTargets,
  listAssistantMessagesForFileLinks,
} from "../mac_bridge/src/thread_file_links.mjs";

const temp = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-thread-links-"));
const image = path.join(temp, "figure with spaces.png");
const svg = path.join(temp, "figure.svg");
const userOnly = path.join(temp, "private.txt");
await writeFile(image, "image");
await writeFile(svg, "svg");
await writeFile(userOnly, "private");
const resolvedImage = await realpath(image);
const resolvedSvg = await realpath(svg);
const resolvedUserOnly = await realpath(userOnly);

assert.deepEqual(
  extractLocalFileLinkTargets(`![图](<${image}>) [SVG](file://${svg}) [网页](https://example.com/x)`),
  [image, svg],
);

let loadCount = 0;
const authorizer = createThreadFileLinkAuthorizer({
  loadMessages: async () => {
    loadCount += 1;
    return [
      { role: "assistant", text: `![图](<${image}>)\n[SVG](file://${svg})` },
      { role: "user", text: `[不应授权](${userOnly})` },
    ];
  },
});

assert.equal(await authorizer.authorize("thread-1", resolvedImage), true);
assert.equal(await authorizer.authorize("thread-1", resolvedSvg), true);
assert.equal(await authorizer.authorize("thread-1", resolvedUserOnly), false);
assert.equal(await authorizer.authorize("thread-1", resolvedUserOnly), false);
assert.equal(await authorizer.authorize("thread-2", resolvedImage), true);
assert.equal(loadCount, 3);

const longAssistantText = `${"x".repeat(30_000)}\n[长回复末尾文件](<${image}>)`;
const untruncated = await listAssistantMessagesForFileLinks(async (method, params) => {
  assert.equal(method, "thread/items/list");
  assert.equal(params.threadId, "thread-long");
  return { data: [{ item: { type: "agentMessage", text: longAssistantText } }], nextCursor: null };
}, "thread-long");
assert.equal(extractLocalFileLinkTargets(untruncated[0].text)[0], image);

console.log(JSON.stringify({ ok: true, checks: ["absolute-link-extraction", "file-url", "remote-url-ignored", "assistant-only", "per-thread", "negative-cache", "long-message-tail"] }));
