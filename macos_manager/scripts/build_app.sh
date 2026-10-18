#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="$ROOT/artifacts/Codex 手机桥接.app"

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT/Contents/MacOS" "$OUTPUT/Contents/Resources"
clang -fobjc-arc -O2 -mmacosx-version-min=13.0 \
  -framework Cocoa \
  "$ROOT/Sources/main.m" \
  -o "$OUTPUT/Contents/MacOS/CodexBridgeManager"
cp "$ROOT/Resources/AppIcon.png" "$OUTPUT/Contents/Resources/AppIcon.png"
cp "$ROOT/Resources/Info.plist" "$OUTPUT/Contents/Info.plist"

codesign --force --deep --sign - "$OUTPUT"
echo "$OUTPUT"
