#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PAIRING="$ROOT/config/pairing.json"
PACKAGE="org.codexmobile.bridge"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"

[[ -x "$ADB" ]] || { echo "adb not found; set ANDROID_HOME or ADB." >&2; exit 2; }
[[ -f "$PAIRING" ]] || { echo "Run scripts/generate_pairing.sh first." >&2; exit 2; }

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  devices=("${(@f)$($ADB devices | awk 'NR > 1 && $2 == "device" {print $1}')}")
  devices=("${(@)devices:#}")
  if (( ${#devices} != 1 )); then
    echo "Expected one authorized Android device; found ${#devices}. Set ANDROID_SERIAL." >&2
    exit 2
  fi
  export ANDROID_SERIAL="$devices[1]"
fi

RELAY_URL="$(node -e 'const p=require(process.argv[1]); process.stdout.write(p.relayUrl)' "$PAIRING")"
"$ROOT/gradlew" -p "$ROOT" -PrelayUrl="$RELAY_URL" :app:testDebugUnitTest :app:assembleDebug
"$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"

REMOTE="/data/local/tmp/codex-mobile-pairing-${RANDOM}.json"
cleanup() {
  "$ADB" shell rm -f "$REMOTE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$ADB" push "$PAIRING" "$REMOTE" >/dev/null
"$ADB" shell chmod 644 "$REMOTE"
"$ADB" shell run-as "$PACKAGE" mkdir -p files
"$ADB" shell run-as "$PACKAGE" cp "$REMOTE" files/pairing.json
"$ADB" shell run-as "$PACKAGE" chmod 600 files/pairing.json
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
echo "Android app installed and paired on $ANDROID_SERIAL."
