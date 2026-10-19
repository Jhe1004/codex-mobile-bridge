# Changelog

## 0.22.0 - 2026-10-19

- 修复部分澎湃 OS WebView 无法显示 SVG 的问题：SVG 现在直接从手机本地缓存嵌入预览页，不再依赖内部网址拦截。
- PNG、JPEG、WebP、GIF、HEIC、AVIF 等普通图片支持双指缩放和放大后的单指拖动，缩放范围为 1–8 倍。
- SVG 预览同样支持双指缩放与拖动，仍保留右上角“使用其他应用打开”的备用入口。

## 0.21.0 - 2026-10-19

- 允许手机打开当前会话中由 Codex 回复明确提供的 Mac 本地文件链接，即使文件位于项目目录之外。
- 仍然拒绝用户消息里的路径、手机伪造的任意路径，以及其他会话没有授权过的链接。
- 打开已授权 HTML 后，仅向同一会话开放该 HTML 所在目录的资源，保证图片、样式和脚本可以正常载入。
- 保留真实路径校验、符号链接防越界、100 MB 上限、分块传输和 SHA-256 完整性校验。

## 0.20.1 - 2026-10-19

- 修复带页内定位符的本地 HTML 链接：下载前会把 `#fragment` 与真实 Mac 文件路径分开，避免把定位符误当成文件名。
- 打开网页后仍保留原来的页内定位，缓存命中和手动重新下载时也不会丢失定位。

## 0.20.0 - 2026-10-19

- Turn the home-screen heartbeat button into a manual verification of every conversation active within the last 24 hours.
- Stream per-conversation progress from the Mac so Android shows a determinate progress bar, changed count, and isolated failure count.
- Queue a manual verification behind any active automatic pass instead of running overlapping Codex history reads.
- Keep the completed summary visible briefly before returning to the compact home header.

## 0.19.0 - 2026-10-19

- While the phone is connected, refresh the Codex thread list every five seconds and verify every conversation updated within the last 24 hours.
- Keep running conversations in the five-second verification set even when their recorded update time is older.
- Cache newly changed conversations on Android before they are opened, so recent chats appear immediately from local storage.
- Isolate per-conversation failures and retry them on the next pass without blocking the remaining conversations.
- Limit Mac-side history reads to four concurrent conversations and prevent overlapping Android synchronization rounds.
- Run the persistent bridge as Android's cross-device remote-messaging service instead of a time-limited data-sync service.

## 0.18.0 - 2026-10-19

- Replaced rollout-file-only cache validation with a digest of the actual latest visible Codex messages.
- Always verifies an instantly rendered phone cache against Codex, including after context compaction, thread migration, or Desktop restoration.
- Keeps polling while a turn is active so a missed completion event cannot permanently freeze the phone on an older result.
- Added request timeouts and relay sequence-gap recovery; stalled requests are released and retried automatically.
- Marks a conversation as synchronized only after the bridge has checked the current Codex message tail.

## 0.17.0 - 2026-10-19

- Restore the last 120 visible messages from Android's private on-device cache when a chat opens.
- Compare a lightweight rollout-file revision before asking Codex app-server for full message history; unchanged chats avoid the expensive history query.
- Stop waking Codex Desktop merely to read a chat. Desktop ownership is acquired only when the user actually sends a message.
- Keep cached history across app and Mac restarts, with a 64 MB / 40-chat bounded LRU policy and atomic cache writes.

## 0.16.0 - 2026-10-19

- Replace the image-preview spinner failure with format-aware PNG/JPEG/WebP/GIF/BMP/HEIC/HEIF/AVIF decoding and a readable fallback.
- Add internal SVG, audio, and video preview paths while retaining PDF, HTML, text, code, and cached-file support.
- Put an open-with icon in every file viewer, including the immersive HTML reader.
- Add multi-file Android picking, visible upload progress, cancellation, 512 KB chunk transfer, size limits, and SHA-256 verification.
- Send verified images as Codex local-image input, audio as local-audio input, and other files as file mentions without allowing Android to choose a Mac path.

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
