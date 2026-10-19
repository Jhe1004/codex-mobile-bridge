import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, realpath, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createFileAccess } from "../mac_bridge/src/file_access.mjs";

const root = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-file-root-"));
const outside = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-file-outside-"));
const bytes = Buffer.alloc(900_000, 0x61);
const allowedFile = path.join(root, "report.txt");
const outsideFile = path.join(outside, "secret.txt");
const linkedFile = path.join(outside, "figure.png");
const linkedHtml = path.join(outside, "viewer", "report.html");
const linkedHtmlAsset = path.join(outside, "viewer", "assets", "plot.png");
await writeFile(allowedFile, bytes);
await writeFile(outsideFile, "outside");
await writeFile(linkedFile, "linked-image");
await mkdir(path.dirname(linkedHtmlAsset), { recursive: true });
await writeFile(linkedHtml, "<img src=\"assets/plot.png\">");
await writeFile(linkedHtmlAsset, "html-asset");
await symlink(outsideFile, path.join(root, "escaped-link.txt"));

const linkedTargets = new Set([await realpath(linkedFile), await realpath(linkedHtml)]);
const access = createFileAccess({
  getAllowedRoots: (threadId) => threadId === "thread-ok" ? [root] : [],
  authorizeLinkedFile: async (threadId, resolvedPath) => threadId === "thread-ok" && linkedTargets.has(resolvedPath),
});
const meta = await access.metadata("thread-ok", `${allowedFile}:27`);
assert.equal(meta.name, "report.txt");
assert.equal(meta.line, 27);
assert.equal(meta.size, bytes.length);
assert.equal(meta.sha256, createHash("sha256").update(bytes).digest("hex"));

const chunks = [];
let offset = 0;
while (offset < meta.size) {
  const chunk = await access.readChunk("thread-ok", allowedFile, offset, meta.size, meta.mtimeMs);
  chunks.push(Buffer.from(chunk.data, "base64"));
  offset = chunk.nextOffset;
}
assert.deepEqual(Buffer.concat(chunks), bytes);

await assert.rejects(access.metadata("thread-ok", outsideFile), /安全限制/);
await assert.rejects(access.metadata("thread-ok", path.join(root, "escaped-link.txt")), /安全限制/);
assert.equal((await access.metadata("thread-ok", linkedFile)).mimeType, "image/png");
await assert.rejects(access.metadata("thread-ok", linkedHtmlAsset), /安全限制/);
assert.equal((await access.metadata("thread-ok", linkedHtml)).mimeType, "text/html");
assert.equal((await access.metadata("thread-ok", linkedHtmlAsset)).mimeType, "image/png");
await assert.rejects(access.metadata("unknown-thread", linkedFile), /没有可读取/);
await assert.rejects(access.metadata("unknown-thread", allowedFile), /没有可读取/);
console.log(JSON.stringify({ ok: true, bytes: bytes.length, chunks: chunks.length, checks: ["inside", "line", "sha256", "chunking", "outside-denied", "symlink-denied", "assistant-link", "html-resource-scope", "unknown-thread-denied"] }));
