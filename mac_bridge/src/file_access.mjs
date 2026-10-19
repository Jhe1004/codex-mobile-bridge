import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { open, realpath, stat } from "node:fs/promises";
import path from "node:path";

const DEFAULT_MAX_FILE_BYTES = 100 * 1024 * 1024;
const DEFAULT_CHUNK_BYTES = 512 * 1024;

function mimeTypeFor(filePath) {
  const extension = path.extname(filePath).toLowerCase();
  return ({
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".bmp": "image/bmp",
    ".heic": "image/heic",
    ".heif": "image/heif",
    ".avif": "image/avif",
    ".svg": "image/svg+xml",
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".markdown": "text/markdown",
    ".csv": "text/csv",
    ".tsv": "text/tab-separated-values",
    ".json": "application/json",
    ".xml": "application/xml",
    ".html": "text/html",
    ".htm": "text/html",
    ".log": "text/plain",
    ".py": "text/x-python",
    ".r": "text/plain",
    ".js": "text/javascript",
    ".mjs": "text/javascript",
    ".ts": "text/plain",
    ".kt": "text/plain",
    ".java": "text/plain",
    ".sh": "text/plain",
    ".yaml": "text/yaml",
    ".yml": "text/yaml",
    ".doc": "application/msword",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xls": "application/vnd.ms-excel",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".ppt": "application/vnd.ms-powerpoint",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".zip": "application/zip",
    ".7z": "application/x-7z-compressed",
    ".rar": "application/vnd.rar",
    ".tar": "application/x-tar",
    ".gz": "application/gzip",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".aac": "audio/aac",
    ".wav": "audio/wav",
    ".ogg": "audio/ogg",
    ".flac": "audio/flac",
    ".mp4": "video/mp4",
    ".m4v": "video/x-m4v",
    ".webm": "video/webm",
    ".mov": "video/quicktime",
    ".3gp": "video/3gpp",
  })[extension] ?? "application/octet-stream";
}

function normalizeTarget(rawTarget) {
  let value = String(rawTarget ?? "").trim();
  if (value.startsWith("<") && value.endsWith(">")) value = value.slice(1, -1);
  if (value.startsWith("file://")) value = new URL(value).pathname;
  value = decodeURIComponent(value);
  let line = null;
  const location = value.match(/^(\/.*?):(\d+)(?::\d+)?$/);
  if (location) {
    value = location[1];
    line = Number(location[2]);
  }
  if (!path.isAbsolute(value)) throw new Error("只允许打开绝对路径的本地文件");
  return { requestedPath: path.normalize(value), line };
}

function belongsToRoot(filePath, rootPath) {
  const relative = path.relative(rootPath, filePath);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

async function sha256(filePath) {
  const hash = createHash("sha256");
  await new Promise((resolve, reject) => {
    const stream = createReadStream(filePath);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", reject);
    stream.on("end", resolve);
  });
  return hash.digest("hex");
}

export function createFileAccess({
  getAllowedRoots,
  authorizeLinkedFile = async () => false,
  maxFileBytes = DEFAULT_MAX_FILE_BYTES,
  chunkBytes = DEFAULT_CHUNK_BYTES,
}) {
  const linkedHtmlRoots = new Map();

  async function resolveAllowedFile(threadId, rawTarget) {
    if (!threadId) throw new Error("threadId is required");
    const { requestedPath, line } = normalizeTarget(rawTarget);
    const roots = [...new Set((getAllowedRoots(threadId) ?? []).filter(Boolean))];
    const resolvedPath = await realpath(requestedPath);
    const resolvedRoots = [];
    for (const root of roots) {
      try { resolvedRoots.push(await realpath(root)); } catch { /* Ignore stale project roots. */ }
    }
    const projectAllowed = resolvedRoots.some((root) => belongsToRoot(resolvedPath, root));
    const htmlResourceAllowed = [...(linkedHtmlRoots.get(threadId) ?? [])]
      .some((root) => belongsToRoot(resolvedPath, root));
    const linkedFileAllowed = !projectAllowed && !htmlResourceAllowed
      ? await authorizeLinkedFile(threadId, resolvedPath)
      : false;
    if (!projectAllowed && !htmlResourceAllowed && !linkedFileAllowed) {
      if (roots.length === 0) throw new Error("当前会话没有可读取的项目目录或已授权文件链接");
      throw new Error("安全限制：只能读取 Codex 项目目录或当前会话中由 Codex 提供的本地文件链接");
    }
    const info = await stat(resolvedPath);
    if (!info.isFile()) throw new Error("链接目标不是普通文件");
    if (info.size > maxFileBytes) throw new Error(`文件超过手机传输上限 ${Math.round(maxFileBytes / 1024 / 1024)} MB`);
    if (linkedFileAllowed && [".html", ".htm"].includes(path.extname(resolvedPath).toLowerCase())) {
      const rootsForThread = linkedHtmlRoots.get(threadId) ?? new Set();
      rootsForThread.add(path.dirname(resolvedPath));
      linkedHtmlRoots.set(threadId, rootsForThread);
    }
    return { resolvedPath, info, line };
  }

  async function metadata(threadId, rawTarget) {
    const { resolvedPath, info, line } = await resolveAllowedFile(threadId, rawTarget);
    return {
      path: resolvedPath,
      name: path.basename(resolvedPath),
      mimeType: mimeTypeFor(resolvedPath),
      size: info.size,
      mtimeMs: Math.trunc(info.mtimeMs),
      sha256: await sha256(resolvedPath),
      line,
      chunkBytes,
    };
  }

  async function readChunk(threadId, rawTarget, offset, expectedSize, expectedMtimeMs) {
    const { resolvedPath, info } = await resolveAllowedFile(threadId, rawTarget);
    const safeOffset = Number(offset);
    if (!Number.isSafeInteger(safeOffset) || safeOffset < 0 || safeOffset > info.size) throw new Error("无效的文件偏移量");
    if (Number(expectedSize) !== info.size || Number(expectedMtimeMs) !== Math.trunc(info.mtimeMs)) {
      throw new Error("文件在传输过程中发生了变化，请重新点击链接");
    }
    const length = Math.min(chunkBytes, info.size - safeOffset);
    const buffer = Buffer.alloc(length);
    const handle = await open(resolvedPath, "r");
    try {
      const { bytesRead } = await handle.read(buffer, 0, length, safeOffset);
      return {
        offset: safeOffset,
        bytesRead,
        nextOffset: safeOffset + bytesRead,
        done: safeOffset + bytesRead >= info.size,
        data: buffer.subarray(0, bytesRead).toString("base64"),
      };
    } finally {
      await handle.close();
    }
  }

  return { metadata, readChunk };
}
