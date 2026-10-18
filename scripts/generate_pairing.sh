#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELAY_URL="${CODEX_MOBILE_RELAY_URL:-${1:-}}"
ACCOUNT="$(id -un)"

if [[ -z "$RELAY_URL" ]]; then
  echo "Set CODEX_MOBILE_RELAY_URL or pass the relay URL as the first argument." >&2
  exit 2
fi
if [[ "$RELAY_URL" != http://* && "$RELAY_URL" != https://* && "$RELAY_URL" != ws://* && "$RELAY_URL" != wss://* ]]; then
  echo "Relay URL must start with http://, https://, ws://, or wss://" >&2
  exit 2
fi

MAC_TOKEN="$(openssl rand -hex 32)"
ANDROID_TOKEN="$(openssl rand -hex 32)"

/usr/bin/security add-generic-password -U -a "$ACCOUNT" -s CodexMobileBridgeRelayMacToken -w "$MAC_TOKEN" >/dev/null
/usr/bin/security add-generic-password -U -a "$ACCOUNT" -s CodexMobileBridgeRelayAndroidTokenDirect -w "$ANDROID_TOKEN" >/dev/null

mkdir -p "$ROOT/config"
PAIRING_URL="$RELAY_URL" PAIRING_TOKEN="$ANDROID_TOKEN" node -e '
  const fs = require("node:fs");
  const out = JSON.stringify({ relayUrl: process.env.PAIRING_URL, relayToken: process.env.PAIRING_TOKEN }, null, 2) + "\n";
  fs.writeFileSync(process.argv[1], out, { mode: 0o600 });
' "$ROOT/config/pairing.json"
chmod 600 "$ROOT/config/pairing.json"

unset MAC_TOKEN ANDROID_TOKEN PAIRING_TOKEN
echo "Pairing credentials created in macOS Keychain and config/pairing.json."
echo "The pairing file is ignored by Git. Do not share or commit it."
