import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));

function keychain(service) {
  const result = spawnSync("/usr/bin/security", ["find-generic-password", "-a", os.userInfo().username, "-s", service, "-w"], {
    encoding: "utf8",
  });
  if (result.status !== 0) throw new Error(`Missing Keychain entry: ${service}`);
  const value = result.stdout.trim();
  if (value.length < 32) throw new Error(`Invalid Keychain entry: ${service}`);
  return value;
}

const child = spawn(process.execPath, [path.join(here, "server.mjs")], {
  cwd: path.dirname(here),
  env: {
    ...process.env,
    RELAY_HOST: process.env.RELAY_HOST || "127.0.0.1",
    RELAY_PORT: process.env.RELAY_PORT || "18880",
    RELAY_MAC_TOKEN: keychain("CodexMobileBridgeRelayMacToken"),
    RELAY_ANDROID_TOKEN: keychain("CodexMobileBridgeRelayAndroidTokenDirect"),
    RELAY_MAX_REPLAY: process.env.RELAY_MAX_REPLAY || "200",
  },
  stdio: ["ignore", "inherit", "inherit"],
});

function stop(signal) {
  child.kill(signal);
  setTimeout(() => process.exit(0), 1000).unref();
}

child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
process.on("SIGTERM", () => stop("SIGTERM"));
process.on("SIGINT", () => stop("SIGINT"));
