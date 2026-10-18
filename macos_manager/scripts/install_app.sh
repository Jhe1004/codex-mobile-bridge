#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/artifacts/Codex 手机桥接.app"
DESTINATION="$HOME/Applications/Codex 手机桥接.app"

[[ -d "$SOURCE" ]] || "$ROOT/scripts/build_app.sh"
mkdir -p "$HOME/Applications"
rm -rf "$DESTINATION"
cp -R "$SOURCE" "$DESTINATION"
echo "$DESTINATION"
