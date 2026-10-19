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
8. Preview one explicit, non-sensitive image, PDF, HTML, and text fixture.
9. Confirm the open-with icon appears in every preview type.
10. Upload one generated image and one generated text file from Android, then verify both appear in the harmless test chat.

Do not use arbitrary user files or real private chats as public test fixtures.

## Android file preview matrix

| Format family | In-app behavior | Fallback |
| --- | --- | --- |
| PNG, JPEG, WebP, GIF, BMP, HEIC/HEIF, AVIF | Android image decoder; animated GIF/WebP can animate when the system decoder supports it | Open-with icon |
| SVG | JavaScript-disabled, local-only WebView | Open-with icon |
| PDF | Page-by-page `PdfRenderer` | Open-with icon |
| HTML | Immersive local WebView with guarded relative-resource loading | Open-with icon |
| Text, Markdown, CSV/TSV, JSON/XML/YAML, code and logs | Scrollable text viewer | Open-with icon |
| Common audio and video | Android media player | Open-with icon |
| Word, Excel, PowerPoint, archives and unknown binary files | File information screen | Open-with icon to a compatible installed app |

Actual codec support for HEIC/HEIF, AVIF, audio, and video follows the Android
version and codecs installed by the device vendor. A failed internal decode must
show a readable error instead of an endless loading spinner.

## Attachment input contract

The bridge uses the current Codex app-server `UserInput` schema: supported image
types become `localImage`, audio becomes `localAudio`, and other files become
file mentions. Upload storage is server-selected, chunked, capped at 100 MB per
file, limited to 10 files per turn, and accepted only after SHA-256 verification.

## Xiaomi HyperOS 4 Super Island

The optional Super Island integration is enabled only when all of these checks
pass at runtime: Xiaomi-family manufacturer, `OS4` system version, island
feature flag, focus-notification protocol 3 or newer, configured Xiaomi App ID,
and granted focus-notification permission. Failure of any check leaves the
feature off without changing normal chat or connection behavior.

See [HYPEROS_SUPER_ISLAND.md](HYPEROS_SUPER_ISLAND.md) for registration and
testing requirements. Never use a device model or serial number as the gate.
