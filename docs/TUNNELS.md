# Tunnel and reverse-proxy contract

The project is provider-neutral. Any route is acceptable if it preserves
WebSocket upgrades and forwards these paths to `127.0.0.1:18880`:

- `GET /health`
- `GET /v1/ws` with WebSocket upgrade

## Requirements

- Public traffic uses HTTPS/WSS with a certificate trusted by Android.
- `Authorization` headers reach the relay unchanged.
- Idle WebSockets remain open for at least 60 seconds.
- Request bodies up to 8 MiB are allowed unless `RELAY_MAX_PAYLOAD` is reduced.
- The public endpoint does not rewrite `/v1/ws`.

FRP/SakuraFrp, Cloudflare Tunnel, Tailscale, nginx, Caddy, and SSH reverse
tunnels can all satisfy this contract. Keep provider credentials outside this
repository and outside shell history when possible.

## LAN development

For a trusted LAN only, run the relay with `RELAY_HOST=0.0.0.0` and pair the
phone to `http://MAC_LAN_IP:18880/v1/ws`. Return to loopback binding before
enabling a public tunnel.
