# Codex Mobile Bridge

> A self-hosted Android client for connecting directly to Codex on your Mac over your own tunnel.

[中文说明](../README.md) · [English](README_EN.md)

Using Codex Desktop on a Mac is fast and convenient, but connecting from a phone through the official remote sync path can suffer from high latency or stalled updates—especially across cross-border networks where tasks finished on the desktop take a long time to show up on mobile.

**Codex Mobile Bridge** bypasses the official mobile sync path and connects your Android device directly to your Mac through a tunnel or reverse proxy you control (such as FRP, Cloudflare Tunnel, Tailscale, or Nginx). You can browse projects and chat history in real time, send follow-up prompts, and preview generated Markdown, HTML reports, and local Mac files right on your phone.

## How It Works

```text
Android Client
    │ Encrypted WebSocket (WSS)
    ▼
Your TLS Tunnel / Reverse Proxy
    ▼
Mac Local Relay (local_relay, bound to 127.0.0.1:18880 by default)
    ▼
Mac Bridge (mac_bridge)
    ▼
Codex Desktop / codex app-server
```

> **Note**: This project only handles the connection and control path between your Android phone and your Mac. It is not an OpenAI API proxy, VPN, or network accelerator—Codex on your Mac continues to use your existing OpenAI/Codex login and network configuration.

## Key Features

- **Desktop Project & Chat Sync**: Reads the project tree and conversation list directly from Codex Desktop on your Mac so both views stay in sync.
- **Seamless Chat Handoff**: Send prompts to existing conversations from your phone and automatically hand writer control back to Codex Desktop afterward, preventing session lockups when switching back to your Mac.
- **Connection Health & Heartbeat**: Displays live connection status across your phone, the relay, and the active session, with automatic reconnect and manual refresh.
- **Native Mac Manager**: Installs a native control center by default so you can inspect the relay, bridge, Codex, and phone connection state, then start, stop, or restart the background services.
- **Xiaomi HyperOS 4 Super Island (Optional)**: Shows ready, working, approval, transfer, completion, and disconnection states while a chat is active. It stays disabled on other systems and requires Xiaomi platform registration and scenario approval.
- **Markdown & Immersive HTML Reader**: Renders Markdown natively in chat and streams large HTML documents from on-device storage to avoid memory-heavy blank pages. The reader menu can also hand the file to another Android app.
- **Selectable Chat Text**: Long-press any user or Codex message to adjust the selection and use Android's native copy action, including rendered Markdown and code.
- **Conversation Status Indicators**: Shows Running, New Reply, or Idle on the home screen, summarizes active and unread counts per project, and clears unread state when a conversation is opened.
- **Guarded Local File Preview & Cache**: Tap local Mac file paths to transfer and verify them in chunks. Successful downloads are reused from a bounded on-device cache instead of being downloaded again, while Mac-side access remains restricted to approved directories.
- **Adaptive Layout**: Built with Jetpack Compose to fit phones, foldables, and tablets across Android 9 (API 28) and above.

## Requirements

- **macOS**: Codex Desktop installed (or a compatible `codex` CLI on `PATH`)
- **Node.js**: 20 or newer
- **JDK**: 17 or newer
- **Android SDK**: Platform-Tools (`adb`)
- **Android Device**: Android 9 (API 28) or newer
- **Remote Tunnel**: A TLS-capable tunnel or reverse proxy (such as FRP, Cloudflare Tunnel, Tailscale, Caddy, or Nginx) when accessing your Mac from outside the local network

## Quick Start

```bash
git clone https://github.com/Jhe1004/codex-mobile-bridge.git
cd codex-mobile-bridge

# 1. Verify local development tools
./scripts/doctor.sh

# 2. Generate pairing credentials (replace with your public HTTPS/WSS endpoint forwarding to 127.0.0.1:18880)
./scripts/generate_pairing.sh https://bridge.example.com/v1/ws

# 3. Install dependencies, start the Mac services, and install the native manager
./scripts/bootstrap_macos.sh

# 4. Enable USB debugging on your Android phone, connect via USB, then build, install, and pair
./scripts/inspect_device.sh
./scripts/install_android.sh
```

**LAN Testing**: On a trusted home or lab Wi-Fi network, you can pair directly with your Mac's LAN IP (for example `http://192.168.1.20:18880/v1/ws`) and run the relay with `RELAY_HOST=0.0.0.0`. For remote access over the internet, always use TLS (HTTPS/WSS) and keep the local relay bound to `127.0.0.1`.

After setup, open `~/Applications/Codex 手机桥接.app` to view live status and manage the background services. To install only the services, run `INSTALL_MACOS_MANAGER=0 ./scripts/bootstrap_macos.sh`.

To stop and remove the Mac background services at any time:

```bash
./scripts/uninstall_macos_services.sh
```

## Development & Verification

```bash
npm --prefix local_relay ci
npm --prefix mac_bridge install
npm test
npm --prefix local_relay run test:integration
./gradlew :app:testDebugUnitTest :app:assembleDebug
./macos_manager/scripts/build_app.sh
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`, and the Mac manager is written under `macos_manager/artifacts/`. GitHub Actions validates both builds on each push.

## Security

- **Separate Random Tokens**: `generate_pairing.sh` creates distinct 256-bit random tokens for the Mac bridge and Android client. Mac tokens are stored in macOS Keychain, and Android credentials are stored in Android Keystore.
- **Ignored Local Pairing Config**: `config/pairing.json` is used only for local USB provisioning and is ignored by Git.
- **Scoped File Access**: File previews are restricted to the active conversation's project root plus directories explicitly listed in `CODEX_MOBILE_FILE_ROOTS`. See [SECURITY.md](../SECURITY.md) before exposing a tunnel publicly.

## Documentation

- [Installation Guide](INSTALL.md)
- [Native Mac Manager](../macos_manager/README.md)
- [Tunnel & Reverse Proxy Guide](TUNNELS.md)
- [Compatibility Notes](COMPATIBILITY.md)
- [Device Adaptation Guide](DEVICE_PROFILES.md)
- [HyperOS 4 Super Island Setup (Chinese)](HYPEROS_SUPER_ISLAND.md)
- [Agent Guide](../AGENTS.md)

## License

Released under the [MIT License](../LICENSE). This is an unofficial community project and is not affiliated with or endorsed by OpenAI; see [NOTICE.md](../NOTICE.md).
