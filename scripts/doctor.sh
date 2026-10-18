#!/bin/zsh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
failures=0

check() {
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf 'ok   %s\n' "$label"
  else
    printf 'FAIL %s\n' "$label"
    failures=$((failures + 1))
  fi
}

check "macOS" test "$(uname -s)" = "Darwin"
check "Node.js 20+" node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 20 ? 0 : 1)'
check "Java 17+" zsh -c 'java -version 2>&1 | head -1 | grep -Eq "(17|18|19|2[0-9])"'
check "Gradle wrapper" test -x "$ROOT/gradlew"
check "Android adb" zsh -c 'command -v adb >/dev/null || test -x "$HOME/Library/Android/sdk/platform-tools/adb"'
check "OpenSSL" command -v openssl
check "Clang (macOS manager)" command -v clang
check "Codex CLI or Desktop bundle" zsh -c 'command -v codex >/dev/null || test -x "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"'

if (( failures > 0 )); then
  printf '\n%d prerequisite check(s) failed. See docs/INSTALL.md.\n' "$failures"
  exit 1
fi

printf '\nAll required local tools are available.\n'
