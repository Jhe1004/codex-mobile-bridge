export const DEFAULT_RESPONSE_CHUNK_BYTES = 512 * 1024;

export function responseEnvelopes(envelope, chunkBytes = DEFAULT_RESPONSE_CHUNK_BYTES) {
  if (!Number.isInteger(chunkBytes) || chunkBytes <= 0) throw new Error("chunkBytes must be positive");
  const serialized = Buffer.from(JSON.stringify(envelope), "utf8");
  if (serialized.length <= chunkBytes) return [envelope];
  const total = Math.ceil(serialized.length / chunkBytes);
  return Array.from({ length: total }, (_, index) => ({
    type: "response.chunk",
    requestId: envelope.requestId,
    index,
    total,
    data: serialized.subarray(index * chunkBytes, (index + 1) * chunkBytes).toString("base64"),
  }));
}
