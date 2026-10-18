# Changelog

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
