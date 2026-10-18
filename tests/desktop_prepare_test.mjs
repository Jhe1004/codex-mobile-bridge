import assert from "node:assert/strict";
import { createDesktopThreadPreparer } from "../mac_bridge/src/desktop_prepare.mjs";

const calls = [];
let releaseOwner;
const ownerReady = new Promise((resolve) => { releaseOwner = resolve; });
const preparer = createDesktopThreadPreparer({
  desktopIpc: {
    async findThreadOwner(threadId) {
      calls.push(["find", threadId]);
      return null;
    },
    async waitForThreadOwner(threadId, options) {
      calls.push(["wait", threadId, options.timeoutMs]);
      return ownerReady;
    },
  },
  async wakeDesktopThread(threadId) {
    calls.push(["wake", threadId]);
  },
  timeoutMs: 3210,
});

const first = preparer.prepare("thread-slow");
const second = preparer.prepare("thread-slow");
assert.equal(first, second, "concurrent preparation must share one operation");
await new Promise((resolve) => setTimeout(resolve, 5));
assert.deepEqual(calls, [
  ["find", "thread-slow"],
  ["wake", "thread-slow"],
  ["wait", "thread-slow", 3210],
]);
releaseOwner("desktop-owner");
const result = await first;
assert.deepEqual(result, { ready: true, ownerClientId: "desktop-owner", alreadyReady: false });
assert.equal(preparer.isPreparing("thread-slow"), false);

let wokeReadyThread = false;
const alreadyReady = createDesktopThreadPreparer({
  desktopIpc: {
    async findThreadOwner() { return "existing-owner"; },
    async waitForThreadOwner() { throw new Error("must not wait"); },
  },
  async wakeDesktopThread() { wokeReadyThread = true; },
});
assert.deepEqual(await alreadyReady.prepare("thread-ready"), {
  ready: true,
  ownerClientId: "existing-owner",
  alreadyReady: true,
});
assert.equal(wokeReadyThread, false);

console.log(JSON.stringify({ ok: true, sharedPreparation: true, readOnlyPreheat: true }));
