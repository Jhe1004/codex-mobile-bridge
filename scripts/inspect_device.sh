#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
OUTPUT="$ROOT/config/device-profile.json"

[[ -x "$ADB" ]] || { echo "adb not found; set ANDROID_HOME or ADB." >&2; exit 2; }
"$ADB" get-state >/dev/null

manufacturer="$($ADB shell getprop ro.product.manufacturer | tr -d '\r')"
model="$($ADB shell getprop ro.product.model | tr -d '\r')"
api="$($ADB shell getprop ro.build.version.sdk | tr -d '\r')"
release="$($ADB shell getprop ro.build.version.release | tr -d '\r')"
size="$($ADB shell wm size | tr '\n' ';' | tr -d '\r')"
density="$($ADB shell wm density | tr '\n' ';' | tr -d '\r')"
abi="$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')"

PROFILE_MANUFACTURER="$manufacturer" PROFILE_MODEL="$model" PROFILE_API="$api" \
PROFILE_RELEASE="$release" PROFILE_SIZE="$size" PROFILE_DENSITY="$density" PROFILE_ABI="$abi" \
node -e '
  const fs = require("node:fs");
  const p = {
    manufacturer: process.env.PROFILE_MANUFACTURER,
    model: process.env.PROFILE_MODEL,
    androidApi: Number(process.env.PROFILE_API),
    androidRelease: process.env.PROFILE_RELEASE,
    displaySize: process.env.PROFILE_SIZE,
    displayDensity: process.env.PROFILE_DENSITY,
    primaryAbi: process.env.PROFILE_ABI,
    capturedAt: new Date().toISOString()
  };
  fs.writeFileSync(process.argv[1], JSON.stringify(p, null, 2) + "\n");
' "$OUTPUT"

echo "Sanitized device profile written to $OUTPUT"
echo "It contains no serial number or account information."
