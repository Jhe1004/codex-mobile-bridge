#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

patterns='(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|github_pat_|ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}|Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9._-]{20,}|relayToken[[:space:]]*[=:][[:space:]]*"[A-Za-z0-9._-]{32,}")'

if rg -n --hidden \
  --glob '!.git/**' --glob '!**/build/**' --glob '!**/node_modules/**' \
  --glob '!config/pairing.json' --glob '!config/pairing.example.json' --glob '!scripts/scan_secrets.sh' \
  --glob '!*.png' --glob '!*.jar' \
  -e "$patterns" .; then
  echo "Potential secret found. Review before committing." >&2
  exit 1
fi

if find . -type f \( -name '*.pem' -o -name '*.p12' -o -name '*.jks' -o -name '*.keystore' -o -name 'pairing.json' -o -name '.env' \) -not -path './.git/*' | grep -q .; then
  echo "Sensitive filename found. Review before committing." >&2
  exit 1
fi

echo "No high-confidence secrets or sensitive filenames found."
