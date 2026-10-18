import { mkdir, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const [root, nodeBin, outputDir] = process.argv.slice(2);
if (!root || !nodeBin || !outputDir) throw new Error("root, nodeBin, and outputDir are required");

const esc = (value) => String(value)
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;")
  .replaceAll("'", "&apos;");
const logs = path.join(os.homedir(), "Library", "Logs", "CodexMobileBridge");
const projectRoot = process.env.CODEX_PROJECT_ROOT || os.homedir();
const fileRoots = process.env.CODEX_MOBILE_FILE_ROOTS || "";

function plist({ label, script, cwd, environment = {}, logName }) {
  const vars = Object.entries(environment).map(([key, value]) =>
    `      <key>${esc(key)}</key><string>${esc(value)}</string>`).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>${esc(label)}</string>
  <key>ProgramArguments</key><array><string>${esc(nodeBin)}</string><string>${esc(script)}</string></array>
  <key>WorkingDirectory</key><string>${esc(cwd)}</string>
  <key>EnvironmentVariables</key><dict>
${vars}
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>${esc(path.join(logs, `${logName}.stdout.log`))}</string>
  <key>StandardErrorPath</key><string>${esc(path.join(logs, `${logName}.stderr.log`))}</string>
</dict></plist>
`;
}

await mkdir(outputDir, { recursive: true });
await writeFile(path.join(outputDir, "org.codexmobile.bridge.relay.plist"), plist({
  label: "org.codexmobile.bridge.relay",
  script: path.join(root, "local_relay", "src", "launcher.mjs"),
  cwd: path.join(root, "local_relay"),
  environment: {
    RELAY_HOST: process.env.RELAY_HOST || "127.0.0.1",
    RELAY_PORT: process.env.RELAY_PORT || "18880",
    RELAY_MAX_REPLAY: process.env.RELAY_MAX_REPLAY || "200",
  },
  logName: "relay",
}));
await writeFile(path.join(outputDir, "org.codexmobile.bridge.mac.plist"), plist({
  label: "org.codexmobile.bridge.mac",
  script: path.join(root, "mac_bridge", "src", "bridge.mjs"),
  cwd: path.join(root, "mac_bridge"),
  environment: {
    RELAY_URL: `ws://127.0.0.1:${process.env.RELAY_PORT || "18880"}/v1/ws`,
    CODEX_PROJECT_ROOT: projectRoot,
    CODEX_MOBILE_FILE_ROOTS: fileRoots,
  },
  logName: "mac-bridge",
}));
