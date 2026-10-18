# Contributing

1. Create a branch from `main`.
2. Do not add real relay URLs, user names, absolute home paths, tokens, or
   device identifiers.
3. Run `./scripts/doctor.sh`, `npm test`,
   `npm --prefix local_relay run test:integration`, and
   `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
4. Describe the Android model/API level and macOS/Codex version used for manual
   testing. Redact serial numbers and account details.
5. Keep compatibility changes generic. Device-specific workarounds must be
   guarded by capability or API checks rather than a person's device ID.
