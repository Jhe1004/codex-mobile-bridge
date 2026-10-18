import { spawn } from "node:child_process";

const THREAD_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function wakeDesktopThread(threadId, { openBin = "/usr/bin/open" } = {}) {
  if (!THREAD_ID_PATTERN.test(threadId)) {
    return Promise.reject(new Error("Refusing to open an invalid Codex thread id"));
  }
  return new Promise((resolve, reject) => {
    const child = spawn(openBin, ["-g", `codex://threads/${threadId}`], {
      stdio: "ignore",
    });
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`Unable to wake Codex Desktop (${code ?? signal})`));
    });
  });
}
