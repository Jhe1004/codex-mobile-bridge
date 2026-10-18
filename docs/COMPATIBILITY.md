# Compatibility notes

The Android-to-relay protocol is owned by this repository and is covered by
offline tests. The Mac bridge also touches local Codex interfaces, which are not
guaranteed to remain stable across Codex releases.

## Expected local interfaces

- `codex app-server --listen stdio://`
- Codex state at `~/.codex/.codex-global-state.json`
- Codex Desktop IPC socket at `~/.codex/ipc/ipc.sock`
- On macOS, an embedded CLI may exist inside `/Applications/ChatGPT.app`

Every location can be overridden except Desktop IPC's default, which can be
changed through the `DesktopIpcClient` constructor in tests or code.

## Upgrade checklist

1. Run all offline Node and Android tests.
2. Start the relay and confirm `/health`.
3. Confirm project/chat hierarchy with a harmless test project.
4. Read an existing test chat.
5. Send one test message from Android.
6. Immediately open the same chat in Codex Desktop.
7. Switch chats and send again after the Desktop writer handoff.
8. Preview one explicit, non-sensitive test file.

Do not use arbitrary user files or real private chats as public test fixtures.

## Xiaomi HyperOS 4 Super Island

The optional Super Island integration is enabled only when all of these checks
pass at runtime: Xiaomi-family manufacturer, `OS4` system version, island
feature flag, focus-notification protocol 3 or newer, configured Xiaomi App ID,
and granted focus-notification permission. Failure of any check leaves the
feature off without changing normal chat or connection behavior.

See [HYPEROS_SUPER_ISLAND.md](HYPEROS_SUPER_ISLAND.md) for registration and
testing requirements. Never use a device model or serial number as the gate.
