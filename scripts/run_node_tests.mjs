import { readdir } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const tests = (await readdir(path.join(root, "tests")))
  .filter((name) => name.endsWith("_test.mjs"))
  .sort();

for (const test of tests) {
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(root, "tests", test)], {
      cwd: root,
      stdio: "inherit",
    });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolve() : reject(new Error(`${test} exited ${code}`)));
  });
}

console.log(JSON.stringify({ ok: true, tests: tests.length }));
