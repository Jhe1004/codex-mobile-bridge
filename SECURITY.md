# Security

## Rules

- Never commit `config/pairing.json`, `.env`, tunnel credentials, signing keys,
  tokens, logs, or screenshots containing private content.
- Use a unique random token for the Mac and a different token for Android.
- Keep the relay bound to `127.0.0.1`; expose it through a TLS tunnel or reverse
  proxy. Do not open the raw relay port to the internet.
- `CODEX_MOBILE_FILE_ROOTS` is an allow-list. Add only folders that the phone is
  allowed to read.
- A conversation may also read an exact existing local-file link emitted by an
  assistant message in that same conversation. User-authored paths, arbitrary
  phone requests, and links from another conversation do not grant access. Once
  an authorized HTML file is opened, its real parent directory is scoped to that
  conversation so the page can load its own images, styles, and scripts.
- Reverse uploads never accept a destination path from Android. The Mac bridge
  writes random names under its user-scoped Application Support directory,
  limits each file to 100 MB, and verifies size plus SHA-256. Unsent uploads
  expire after seven days; files actually attached to a Codex turn move into a
  persistent `sent` directory so old chats do not lose their attachment path.
  `CODEX_MOBILE_UPLOAD_ROOT` may override the directory for advanced deployments;
  keep that directory private to the current macOS user.
- Android stores recent visible chat text only in its app-private internal
  storage. The cache is bounded to 64 MB and 40 chats, uses atomic replacement,
  and is removed when app data is cleared or the app is uninstalled.
- The Android token is encrypted with Android Keystore after import. The
  temporary pairing file is deleted after a successful import.
- Rotate both tokens after a lost phone, a shared log, or a suspected leak.

## Reporting

Please open a GitHub security advisory instead of a public issue when a report
contains an exploitable vulnerability or sensitive information.
