import assert from "node:assert/strict";
import { wakeDesktopThread } from "../mac_bridge/src/desktop_wake.mjs";

await assert.rejects(
  wakeDesktopThread("not-a-thread-id", { openBin: "/usr/bin/true" }),
  /invalid Codex thread id/,
);

await wakeDesktopThread("01a13fe2-bb25-7143-b379-73a005c2ff38", { openBin: "/usr/bin/true" });

console.log(JSON.stringify({ ok: true, validatesThreadId: true, usesArgumentArray: true }));
