# Installation

## 1. Prepare the Mac

Install Node.js 20+, JDK 17+, and the Android SDK platform tools. Codex Desktop
must be installed, or `codex` must be available on `PATH`.

```bash
./scripts/doctor.sh
```

If Java or Android SDK is installed in a nonstandard location, export
`JAVA_HOME` and `ANDROID_HOME` before running Gradle.

## 2. Choose the phone-to-Mac route

The Android phone must reach a URL that forwards WebSocket traffic to the local
relay. The safe remote pattern is:

```text
https://your-host/v1/ws -> TLS tunnel -> 127.0.0.1:18880/v1/ws
```

Do not publish port 18880 directly. Use TLS and keep the local relay on loopback.

## 3. Create credentials

```bash
./scripts/generate_pairing.sh https://your-host/v1/ws
```

This creates two different 256-bit random tokens. Both are stored in macOS
Keychain. The Android token is also written to the ignored file
`config/pairing.json` for USB provisioning.

## 4. Configure file access

By default, a chat can read files inside its project roots. To permit additional
folders, export a colon-separated allow-list before bootstrapping:

```bash
export CODEX_MOBILE_FILE_ROOTS="$HOME/Documents:$HOME/Projects"
```

Do not use `$HOME` as a broad file root unless that is deliberately required.

## 5. Start the Mac services

```bash
./scripts/bootstrap_macos.sh
```

The script installs Node dependencies and creates two user LaunchAgents. Logs
are written to `~/Library/Logs/CodexMobileBridge/`.

Health check:

```bash
curl http://127.0.0.1:18880/health
```

## 6. Install Android

Enable Developer options and USB debugging. Accept the authorization prompt.
Then run:

```bash
./scripts/inspect_device.sh
./scripts/install_android.sh
```

If more than one device is connected, set `ANDROID_SERIAL` to the desired adb
device ID for that shell only. Never store it in the repository.

## 7. Remove the background services

```bash
./scripts/uninstall_macos_services.sh
```

This preserves Keychain tokens. Delete the two Keychain entries manually if the
installation is being retired.
