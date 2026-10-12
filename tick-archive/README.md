# Nobitex public-trade archive (opt-in, market data only)

A single always-on host subscribes via the official Centrifugo client to **Nobitex public trades**, commits each received publication to SQLite WAL with `synchronous=FULL`, and exposes authenticated ordered pages. The Android client replays them into the existing paper journal after reconnect; **no positions, account credentials, or trading keys leave the phone**. Upstream reconnection, archive restart, malformed rows and offset jumps are durable gap records. The server's `seq` is a local DB cursor, not a Nobitex trade ID and not proof of venue-wide completeness.

## Deploy on an owned always-on VPS

1. Install Docker + Compose and a TLS reverse proxy (e.g. Caddy or nginx). Copy this folder onto that VPS. Install disk monitoring and back up the named volume; **do not rely on this sandbox preview or GitHub Actions for 24/7 hosting**.
2. Create `tick-archive/.env` **on the VPS only**, with `TICK_ARCHIVE_TOKEN=` followed by at least 32 characters of cryptographically random text (e.g. `openssl rand -hex 32`). Do not commit/share this file. The default attempts the 45 curated USDT pairs from the Android catalog. Set `TICK_ARCHIVE_SYMBOLS=` to the pairs your Nobitex venue actually publishes; verify each channel is active, and capacity-plan disk/network before subscribing to 45 pairs. Inactive/unsupported channels leave `/health` at 503 and are not silently treated as monitored.
3. Run `docker compose up -d --build`. Docker binds the HTTP server **only to 127.0.0.1:8787** on the VPS. Publish HTTPS from your reverse proxy to that port; configure TLS and restrict traffic/rate-limit the public endpoint. **Never publish HTTP or a bearer token over plaintext**. Check `curl -fsS http://127.0.0.1:8787/health` on the VPS; `/health` reports transport/subscriptions, not proof of all ticks.
4. In Android Settings → بایگانی تیک کریپتو, enter the public **HTTPS base URL** (no path) and the token, then save. Only **new** crypto paper positions choose this mode; prior positions retain their old feed. Do not change/remove the archive host or its secret while archive-mode positions are open. The phone downloads only, using its private stored token; settlement and journal balance update **after the phone reconnects**, not while the phone is off. The host continuously records trades, it does *not* receive positions or execute trades.
5. Watch the journal's gap flag, server `/health`, disk, and logs. Configure external uptime alerts; if ingestion fails, do not label the missing interval as complete. Size the volume for unbounded growth; this initial version does not delete past ticks, because silent retention expiry would undermine replay. Use backups and migration with downtime marked as a gap.

### API

`GET /v1/trades/BTCUSDT?after=0&from=1760000000000&limit=500` with `Authorization: Bearer <token>` returns `venue`, `symbol`, `head`, ordered `trades` (local `seq`, exchange `time`, `price`, `volume`, `type`, publisher `offset`), `gaps` (time/reason/afterSeq), and `next`. `from` is for the **first** request of a position. Subsequent pages pass `after=next`. Open trade replay accepts historical exchange times only from this explicit authenticated archive, not from a cached live quote. Restart/process gaps remain visible; a finite REST page cannot prove their recovery.

## Development/test

`python3 -m venv .venv && .venv/bin/pip install -r requirements.txt && .venv/bin/python -m unittest discover -s tests -v`

A local test does not prove that a Nobitex channel supports recovery or that every venue execution was published. Forex/metal/stocks need a properly licensed feed for **their own execution venue**; this service intentionally does not mix their prices with Nobitex trades.
