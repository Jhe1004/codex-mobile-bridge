import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { createFileUpload, desktopAttachmentContext, turnAttachmentInput } from "../mac_bridge/src/file_upload.mjs";

const root = await mkdtemp(path.join(os.tmpdir(), "codex mobile upload test-"));
try {
  const uploads = createFileUpload({
    threadExists: (threadId) => threadId === "thread-1",
    uploadRoot: root,
    maxFileBytes: 1024,
    chunkBytes: 5,
  });
  const bytes = Buffer.from("hello attachment", "utf8");
  const digest = createHash("sha256").update(bytes).digest("hex");
  const started = await uploads.begin("thread-1", {
    name: "../notes.txt",
    mimeType: "text/plain",
    size: bytes.length,
    sha256: digest,
  });
  let offset = 0;
  while (offset < bytes.length) {
    const chunk = bytes.subarray(offset, Math.min(offset + 5, bytes.length));
    const result = await uploads.append("thread-1", started.uploadId, offset, chunk.toString("base64"));
    offset = result.nextOffset;
  }
  const completed = await uploads.finish("thread-1", started.uploadId);
  assert.equal(completed.name, "notes.txt");
  const [descriptor] = await uploads.resolve("thread-1", [started.uploadId]);
  assert.equal((await readFile(descriptor.path)).toString("utf8"), "hello attachment");
  assert.deepEqual(turnAttachmentInput([descriptor]), [{ type: "mention", name: "notes.txt", path: descriptor.path }]);
  assert.equal(desktopAttachmentContext([descriptor])[0].isImageAttachment, false);
  await assert.rejects(() => uploads.begin("missing", { name: "x", size: 0, sha256: createHash("sha256").update("").digest("hex") }), /找不到当前会话/);
  await assert.rejects(() => uploads.resolve("thread-1", Array(11).fill(started.uploadId)), /最多发送 10 个附件/);
  assert.equal((await uploads.discard("thread-1", started.uploadId)).removed, true);
  console.log(JSON.stringify({ ok: true, chunkedUpload: true, integrity: true, containment: true }));
} finally {
  await rm(root, { recursive: true, force: true });
}
