#!/bin/zsh
set -euo pipefail

for label in org.codexmobile.bridge.mac org.codexmobile.bridge.relay; do
  launchctl bootout "gui/$(id -u)/$label" >/dev/null 2>&1 || true
  rm -f "$HOME/Library/LaunchAgents/$label.plist"
done
echo "Codex Mobile Bridge LaunchAgents removed. Keychain tokens were preserved."
