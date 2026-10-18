# Codex Mobile Bridge

An unofficial Android client for controlling a Codex session running on your
own Mac. It shows Codex projects and chats, sends messages through the Mac, and
can securely preview files linked from a chat.

The project is designed for self-hosting. It does not contain a hosted service,
an OpenAI account, or tunnel credentials.

## How it works

```text
Android app
    │ authenticated WebSocket
    ▼
your TLS tunnel / reverse proxy
    ▼
local relay on the Mac (127.0.0.1 only)
    ▼
Mac bridge
    ▼
Codex Desktop / codex app-server
```

The relay uses separate random tokens for the Mac and Android. Local file
preview is restricted to the active chat's project roots plus folders explicitly
listed in `CODEX_MOBILE_FILE_ROOTS`.

## Features

- Projects and chats use the hierarchy and names from Codex Desktop.
- Stable message sending that hands control back to Codex Desktop.
- Connection-health heartbeat and manual refresh.
- Markdown rendering and an immersive HTML reader.
- Secure, chunked preview of permitted Mac files.
- Adaptive Android launcher icon and phone/tablet layouts.
- Configurable relay URL; no server address is compiled into the public source.
- Device inspection script for model, Android API, density, resolution, and ABI.

## Requirements

- macOS with Codex Desktop or a compatible `codex` CLI
- Node.js 20 or newer
- JDK 17 or newer
- Android SDK with `adb`; Android 9 / API 28 or newer
- A TLS-capable tunnel or reverse proxy if the phone is outside the Mac's LAN

The Mac integration uses local Codex interfaces that can change. See
[compatibility notes](docs/COMPATIBILITY.md).

## Quick start

```bash
git clone https://github.com/Jhe1004/codex-mobile-bridge.git
cd codex-mobile-bridge
./scripts/doctor.sh

# Use the public HTTPS/WSS address that forwards to 127.0.0.1:18880.
./scripts/generate_pairing.sh https://bridge.example.com/v1/ws

# Install dependencies and launch the two Mac background services.
./scripts/bootstrap_macos.sh

# Enable USB debugging, connect one Android device, then install and pair it.
./scripts/inspect_device.sh
./scripts/install_android.sh
```

For LAN-only testing, use the Mac's LAN address, for example
`http://192.168.1.20:18880/v1/ws`, and run the relay with
`RELAY_HOST=0.0.0.0`. This is suitable only on a trusted network. Remote access
should use TLS and keep the relay bound to `127.0.0.1`.

Detailed setup is in [docs/INSTALL.md](docs/INSTALL.md). Tunnel-neutral examples
are in [docs/TUNNELS.md](docs/TUNNELS.md).

## Device adaptation

The app supports API 28+ and uses adaptive resources instead of model-specific
coordinates. Run `scripts/inspect_device.sh` before changing UI behavior. It
creates a sanitized local profile without serial numbers. See
[docs/DEVICE_PROFILES.md](docs/DEVICE_PROFILES.md) for phones, tablets, foldables,
cutouts, keyboard behavior, and vendor battery settings.

## Development

```bash
npm --prefix local_relay ci
npm --prefix mac_bridge install
npm test
npm --prefix local_relay run test:integration
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security and privacy

No real token, private URL, account name, device serial, home path, certificate,
or signing key is committed. Pairing data lives in `config/pairing.json`, which
is ignored by Git and imported into Android Keystore. Read [SECURITY.md](SECURITY.md)
before exposing the relay outside your LAN.

## For AI coding agents

Start with [AGENTS.md](AGENTS.md). It contains the repository map, safe commands,
configuration contract, test gates, and rules for adapting to a new device
without introducing user-specific secrets.

## Status

This is an early community release. It was developed against Codex Desktop on
macOS and Android 16-class devices. Expect compatibility work when Codex changes
its local app-server or desktop IPC protocol.

## License

Code is available under the [MIT License](LICENSE). This project is unofficial;
see [NOTICE.md](NOTICE.md).
