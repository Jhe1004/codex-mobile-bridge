# Agent guide

This file is the entry point for AI coding agents working in this repository.

## Objective

Keep one Android client connected to one user's Codex environment on macOS
without publishing that user's secrets, paths, account data, or chat contents.

## Repository map

- `app/`: Kotlin/Jetpack Compose Android client.
- `local_relay/`: authenticated WebSocket router. It must not read Codex data.
- `mac_bridge/`: Codex app-server/Desktop IPC adapter and guarded file reader.
- `scripts/`: reproducible setup, pairing, diagnostics, and tests.
- `tests/`: offline Node tests. Tests must use fixtures or temporary folders.
- `config/`: examples only. Real generated files are ignored.
- `docs/`: installation, device, compatibility, and tunnel contracts.

## README writing style

- Keep `README.md` Chinese-first and keep `docs/README_EN.md` aligned with it.
- Write for someone deciding whether and how to use the project: begin with the
  real problem, explain the self-hosted solution, then show the architecture,
  features, requirements, and quickest safe setup path.
- Use natural, concrete prose and short sections. Prefer user-visible outcomes
  over implementation or agent-process commentary.
- Do not stuff SEO keywords into sentences or add a keyword block. Let accurate
  terms such as Codex, Android, macOS, self-hosted, FRP, WebSocket, and reverse
  proxy appear naturally where they are useful.
- Keep claims technically precise. In particular, say clearly that this project
  connects the phone to the user's Mac; it is not an OpenAI API proxy, VPN, or
  network accelerator.
- Avoid exposing repository-cleanup details, private deployment history, or
  author-specific configuration in public-facing copy.

## Non-negotiable safety rules

1. Never commit `.env`, `config/pairing.json`, signing material, tunnel config,
   real URLs, tokens, absolute user home paths, device serials, logs, or chat
   exports.
2. Never broaden file access. `mac_bridge/src/file_access.mjs` must keep realpath
   containment, symlink escape prevention, size limits, and per-thread roots.
3. Never hard-code a phone model. Detect Android API level, window size classes,
   insets, density, and capabilities.
4. Keep the relay provider-neutral. FRP, Cloudflare Tunnel, Tailscale, and reverse
   proxies are deployment choices, not source-code dependencies.
5. Do not claim Codex compatibility without running the offline gates and one
   redacted end-to-end test on the target Codex version.
6. Keep Super Island strictly gated to Xiaomi-family HyperOS 4 devices with the
   official island capability, focus protocol, App ID, and permission checks.
   Never commit Xiaomi account data, OAIDs, signing fingerprints, or a user's
   private registration material, and preserve the 12-hour session limit.
7. A private relay CA may enter only through ignored pairing data. Never commit
   a user's tunnel certificate or endpoint, and never replace certificate
   validation with an all-trusting TLS client.

## Configuration contract

- Android pairing JSON:
  `{"relayUrl":"https://host/v1/ws","relayToken":"32+ random chars"}`.
- Mac bridge token: Keychain service `CodexMobileBridgeRelayMacToken`.
- Android relay token: Keychain service
  `CodexMobileBridgeRelayAndroidTokenDirect`.
- Mac bridge variables: `RELAY_URL`, `CODEX_BIN`, `CODEX_PROJECT_ROOT`,
  `CODEX_GLOBAL_STATE`, and `CODEX_MOBILE_FILE_ROOTS`.
- Relay variables: `RELAY_HOST`, `RELAY_PORT`, `RELAY_MAX_REPLAY`, and
  `RELAY_MAX_PAYLOAD`.

## Required validation

```bash
./scripts/doctor.sh
npm --prefix local_relay ci
npm --prefix mac_bridge install
npm test
npm --prefix local_relay run test:integration
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

For an Android change, also run `scripts/inspect_device.sh` and verify on at
least one API 28+ device or emulator. Record only manufacturer, model, API,
resolution, density, and ABI; do not record serial numbers.

## Common adaptation tasks

- Different relay: change pairing JSON or `-PrelayUrl`; do not edit Kotlin.
- Different Android device: inspect first, then use responsive Compose layout
  and system insets. Do not branch on a personal serial.
- Different Mac paths: set environment variables; do not commit absolute paths.
- Different tunnel provider: forward HTTPS/WebSocket traffic to the relay's
  `/v1/ws` and `/health` endpoints.
- Codex upgrade: run Node tests, open a harmless test chat, verify list/read/send,
  then verify Codex Desktop can still open the same chat.
