import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createFileAccess } from "../mac_bridge/src/file_access.mjs";

const root = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-file-root-"));
const outside = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-file-outside-"));
const bytes = Buffer.alloc(900_000, 0x61);
const allowedFile = path.join(root, "report.txt");
const outsideFile = path.join(outside, "secret.txt");
await writeFile(allowedFile, bytes);
await writeFile(outsideFile, "outside");
await symlink(outsideFile, path.join(root, "escaped-link.txt"));

const access = createFileAccess({ getAllowedRoots: (threadId) => threadId === "thread-ok" ? [root] : [] });
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
await assert.rejects(access.metadata("unknown-thread", allowedFile), /没有可读取/);
console.log(JSON.stringify({ ok: true, bytes: bytes.length, chunks: chunks.length, checks: ["inside", "line", "sha256", "chunking", "outside-denied", "symlink-denied", "unknown-thread-denied"] }));
