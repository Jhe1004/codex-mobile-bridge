# Tunnel and reverse-proxy contract

The project is provider-neutral. Any route is acceptable if it preserves
WebSocket upgrades and forwards these paths to `127.0.0.1:18880`:

- `GET /health`
- `GET /v1/ws` with WebSocket upgrade

## Requirements

- Public traffic uses HTTPS/WSS. Prefer a publicly trusted certificate. If the
  tunnel uses a private or self-signed certificate, provision that exact CA
  certificate through the ignored pairing file; never disable TLS validation.
- `Authorization` headers reach the relay unchanged.
- Idle WebSockets remain open for at least 60 seconds.
- Request bodies up to 8 MiB are allowed unless `RELAY_MAX_PAYLOAD` is reduced.
- The public endpoint does not rewrite `/v1/ws`.

FRP/SakuraFrp, Cloudflare Tunnel, Tailscale, nginx, Caddy, and SSH reverse
tunnels can all satisfy this contract. Keep provider credentials outside this
repository and outside shell history when possible.

## Private or self-signed certificate

Pass the CA certificate as the second argument when generating pairing data:

```bash
./scripts/generate_pairing.sh https://your-host/v1/ws /private/path/relay-ca.crt
```

The certificate is stored only in ignored `config/pairing.json`, transferred by
USB, and encrypted with Android Keystore. The Android client then trusts that
certificate for this relay connection only. It does not install a system CA and
does not accept arbitrary certificates.

## LAN development

For a trusted LAN only, run the relay with `RELAY_HOST=0.0.0.0` and pair the
phone to `http://MAC_LAN_IP:18880/v1/ws`. Return to loopback binding before
enabling a public tunnel.
