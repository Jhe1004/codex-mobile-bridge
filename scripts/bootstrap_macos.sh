#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NODE_BIN="$(command -v node)"
LAUNCH_DIR="$HOME/Library/LaunchAgents"
GENERATED="$ROOT/config/launchagents"
LOG_DIR="$HOME/Library/Logs/CodexMobileBridge"

"$ROOT/scripts/doctor.sh"
[[ -f "$ROOT/config/pairing.json" ]] || {
  echo "Run scripts/generate_pairing.sh before bootstrapping services." >&2
  exit 2
}

npm --prefix "$ROOT/local_relay" ci
npm --prefix "$ROOT/mac_bridge" install
mkdir -p "$LAUNCH_DIR" "$LOG_DIR"
node "$ROOT/scripts/render_launchagents.mjs" "$ROOT" "$NODE_BIN" "$GENERATED"

for label in org.codexmobile.bridge.relay org.codexmobile.bridge.mac; do
  launchctl bootout "gui/$(id -u)/$label" >/dev/null 2>&1 || true
  cp "$GENERATED/$label.plist" "$LAUNCH_DIR/$label.plist"
  launchctl bootstrap "gui/$(id -u)" "$LAUNCH_DIR/$label.plist"
  launchctl kickstart -k "gui/$(id -u)/$label"
done

if [[ "${INSTALL_MACOS_MANAGER:-1}" = "1" ]]; then
  "$ROOT/macos_manager/scripts/install_app.sh"
fi

echo "Mac relay and bridge services are running."
if [[ "${INSTALL_MACOS_MANAGER:-1}" = "1" ]]; then
  echo "Manager app: $HOME/Applications/Codex 手机桥接.app"
fi
echo "Logs: $LOG_DIR"
