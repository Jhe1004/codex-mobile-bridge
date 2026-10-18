# Security

## Rules

- Never commit `config/pairing.json`, `.env`, tunnel credentials, signing keys,
  tokens, logs, or screenshots containing private content.
- Use a unique random token for the Mac and a different token for Android.
- Keep the relay bound to `127.0.0.1`; expose it through a TLS tunnel or reverse
  proxy. Do not open the raw relay port to the internet.
- `CODEX_MOBILE_FILE_ROOTS` is an allow-list. Add only folders that the phone is
  allowed to read.
- The Android token is encrypted with Android Keystore after import. The
  temporary pairing file is deleted after a successful import.
- Rotate both tokens after a lost phone, a shared log, or a suspected leak.

## Reporting

Please open a GitHub security advisory instead of a public issue when a report
contains an exploitable vulnerability or sensitive information.
