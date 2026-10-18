# Changelog

## 0.15.0 - 2026-10-19

- Stream large local HTML files from verified on-device storage instead of copying the whole document into a WebView string.
- Reuse previously verified file downloads across page opens and app restarts, with a bounded 512 MB cache.
- Add an HTML reader menu for opening the downloaded file in another Android app or explicitly fetching a fresh copy from the Mac.

## 0.14.0 - 2026-10-19

- Add a native macOS manager for relay, bridge, Codex, phone, and latency status.
- Add per-service and batch start, stop, and restart controls backed by user LaunchAgents.
- Install the manager by default from `bootstrap_macos.sh`, with an explicit opt-out.
- Keep the tunnel provider-neutral through an optional standard LaunchAgent label.
- Include the project-membership and compaction-message consistency fixes in the public source tree.

## 0.13.1 - 2026-10-19

- Keep a newly sent user message visible while Codex compacts a long conversation.
- Merge server history with the pending local message by `clientUserMessageId` instead of replacing the whole chat.
- Remove the optimistic message only when delivery fails, while preserving the text in the composer for retry.

## 0.13.0 - 2026-10-19

- Added live per-conversation Running, New Reply, and Idle indicators to the Android home screen.
- Added project-level running and unread counters.
- Added local read tracking that establishes a clean first-run baseline and marks a completed conversation read when opened.
- Added a privacy-preserving Mac-side status projection from Codex lifecycle records without sending local file paths to the phone.

## 0.12.0 - 2026-10-19

- Added native long-press text selection and system copy actions to every chat message.
- Kept selection available across rendered Markdown paragraphs, lists, quotes, tables, and code blocks.
- Preserved link opening, guarded local-file download, scrolling, and message rendering behavior.

## 0.11.1 - 2026-10-19

- Restored secure connectivity to self-signed HTTPS tunnels through an optional per-user CA certificate in encrypted pairing data.
- Kept private tunnel certificates and endpoints out of the repository and APK source.
- Preserved normal Android system trust when no private CA is configured.

## 0.11.0 - 2026-10-19

- Added an optional Xiaomi HyperOS 4 Super Island view for active Codex chats.
- Added strict manufacturer, OS version, island-capability, protocol, app-registration, and permission gates; all other devices stay disabled.
- Added live ready, working, approval, transfer, completion, and disconnection states.
- Limited each island lifecycle to one active chat session and at most 12 hours.
- Added unit tests and setup documentation for Xiaomi platform registration without committing App IDs, OAIDs, or signing data.

## 0.10.1 - 2026-10-18

- First public source release.
- Android project/chat hierarchy with collapsible projects.
- Stable mobile-to-Desktop message handoff.
- Connection heartbeat, Markdown rendering, file preview, and immersive HTML.
- Adaptive icon safe zone for vendor launcher masks.
- Provider-neutral relay URL and per-user encrypted pairing.
- Sanitized setup, device inspection, CI, and AI-agent instructions.
- Stable Android API 36 build baseline with support back to API 28.
- Pinned Compose, Lifecycle, and OkHttp versions whose published metadata supports API 36.
