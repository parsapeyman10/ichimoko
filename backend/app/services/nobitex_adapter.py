"""Read-only Nobitex spot market-data adapter.

This module intentionally implements only the *read* half of the
`VenueAdapter` protocol declared in `app.services.execution_gate`. It is a
forward-compatible building block for the "Nobitex live trading" phase
described in `docs/ROADMAP_FA.md` ("قرارداد و امنیت برای مرحلهٔ سفارش
واقعی") — NOT a live-trading feature by itself.

`submit`, `cancel` and `reconcile` all raise `NobitexExecutionDisabled`
unconditionally. Real order placement requires, at minimum, everything the
roadmap and `execution_gate.status()` already list as missing: a dedicated
execution server, short-lived per-user auth, the user's own Nobitex API
token (never bundled with the app, never accepted by this API — see
`OrderIntent`'s `extra="forbid"` config), confirmed per-market amount/price
precision from Nobitex, durable idempotency storage, order reconciliation,
and an audited kill switch. None of that infrastructure exists yet, so this
adapter must never be wired into `/api/v1/execution/orders`.

Only Nobitex's documented, tokenless public endpoints are used:
- GET /market/stats           https://apidocs.nobitex.ir/en/#latest-market-stats
- GET /v3/orderbook/{symbol}  https://apidocs.nobitex.ir/en/#orderbook-v3

No API key, login password, TOTP or trading token is read, stored, or sent
by this module — matching the app's existing "read-only public data only"
policy for Nobitex (see `android/.../data/NobitexPublicData.kt`).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timezone

import httpx

BASE_URL = "https://apiv2.nobitex.ir"

# Fixed allowlist mirroring the Android app's NobitexMarket enum. Do not widen
# this without also confirming the destination currency's stats key and the
# BTCIRT OHLC/stats unit discrepancy noted in NobitexPublicData.kt.
_MARKETS: dict[str, dict[str, str]] = {
    "BTCUSDT": {"stats_src": "btc", "stats_dst": "usdt", "orderbook_symbol": "BTCUSDT", "quote_unit": "USDT"},
    "BTCIRT": {"stats_src": "btc", "stats_dst": "rls", "orderbook_symbol": "BTCIRT", "quote_unit": "IRT (rial, stats only)"},
}


class NobitexExecutionDisabled(RuntimeError):
    """Raised by every order-affecting method. No real order is ever sent from this module."""


class NobitexAdapterError(RuntimeError):
    """A Nobitex response could not be honestly interpreted as a valid quote."""


@dataclass(frozen=True)
class NobitexQuote:
    symbol: str
    quote_unit: str
    latest: float
    best_buy: float
    best_sell: float
    is_closed: bool
    day_change_pct: float | None
    order_book_bid: float | None
    order_book_ask: float | None
    order_book_updated_at: datetime | None
    fetched_at: datetime


def _finite(value) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if math.isfinite(parsed) else None


class NobitexAdapter:
    """Implements the read-only half of `execution_gate.VenueAdapter`.

    `submit`, `cancel` and `reconcile` are deliberately unimplemented; see the
    module docstring. This class never imports or calls anything from
    `execution_gate` beyond documenting the contract it partially satisfies,
    so it cannot accidentally loosen the existing kill switch.
    """

    venue = "NOBITEX"

    async def _get(self, client: httpx.AsyncClient, path: str, params: dict | None = None) -> dict:
        response = await client.get(f"{BASE_URL}{path}", params=params)
        response.raise_for_status()  # 401/403/429/5xx must never be read as "market closed"
        if len(response.content) > 2_000_000:
            raise NobitexAdapterError("پاسخ نوبیتکس بیش از حد بزرگ است")
        return response.json()

    async def instrument_rules(self, symbol: str) -> dict:
        """Per-market amount/price precision and minimum order size.

        Deliberately unimplemented: Nobitex does not document a stable public
        endpoint for this in https://apidocs.nobitex.ir, and guessing a
        precision/step value here is exactly the kind of fabricated number
        this project refuses to produce (it could also cause a real order to
        be mis-sized). Confirm the values with Nobitex support/docs and hard
        -code them with a citation before removing this guard.
        """
        raise NobitexExecutionDisabled(
            "دقت حجم/قیمت (amount/price precision) بازار "
            f"{symbol} از منبع رسمی و پایدار نوبیتکس تأیید نشده است؛ "
            "به‌جای حدس زدن، این متد عمداً غیرفعال است."
        )

    async def quote(self, symbol: str, client: httpx.AsyncClient) -> NobitexQuote:
        """A real, public, tokenless spot quote. Never a synthesized or cached-as-live price."""
        market = _MARKETS.get(symbol.upper())
        if market is None:
            raise NobitexAdapterError(f"نماد {symbol} در فهرست مجاز نوبیتکس این آداپتور نیست")

        stats_payload = await self._get(
            client, "/market/stats",
            params={"srcCurrency": market["stats_src"], "dstCurrency": market["stats_dst"]},
        )
        if stats_payload.get("status") != "ok":
            raise NobitexAdapterError("پاسخ آمار بازار نوبیتکس ok نیست")
        stats_key = f"{market['stats_src']}-{market['stats_dst']}"
        stats = (stats_payload.get("stats") or {}).get(stats_key)
        if not isinstance(stats, dict):
            raise NobitexAdapterError(f"آمار بازار برای {stats_key} در پاسخ نوبیتکس وجود ندارد")
        latest = _finite(stats.get("latest"))
        best_buy = _finite(stats.get("bestBuy"))
        best_sell = _finite(stats.get("bestSell"))
        if latest is None or best_buy is None or best_sell is None or latest <= 0:
            raise NobitexAdapterError("آمار بازار نوبیتکس فاقد قیمت معتبر است")

        book_bid = book_ask = None
        book_updated_at = None
        try:
            book_payload = await self._get(client, f"/v3/orderbook/{market['orderbook_symbol']}")
            if book_payload.get("status") == "ok":
                bids = book_payload.get("bids") or []
                asks = book_payload.get("asks") or []
                book_bid = _finite(bids[0][0]) if bids else None
                book_ask = _finite(asks[0][0]) if asks else None
                last_update_ms = _finite(book_payload.get("lastUpdate"))
                if last_update_ms is not None and last_update_ms > 0:
                    book_updated_at = datetime.fromtimestamp(last_update_ms / 1000.0, tz=timezone.utc)
        except (httpx.HTTPStatusError, NobitexAdapterError):
            # Order book is best-effort here; market/stats above is the source of truth
            # for `latest`/`bestBuy`/`bestSell`. A missing book must not be treated as $0.
            pass

        return NobitexQuote(
            symbol=market["orderbook_symbol"],
            quote_unit=market["quote_unit"],
            latest=latest,
            best_buy=best_buy,
            best_sell=best_sell,
            is_closed=bool(stats.get("isClosed", False)),
            day_change_pct=_finite(stats.get("dayChange")),
            order_book_bid=book_bid,
            order_book_ask=book_ask,
            order_book_updated_at=book_updated_at,
            fetched_at=datetime.now(timezone.utc),
        )

    async def submit(self, intent, user_id: str) -> dict:
        raise NobitexExecutionDisabled(
            "ارسال سفارش واقعی به نوبیتکس پیاده‌سازی نشده است؛ به قرارداد امنیتی در "
            "docs/ROADMAP_FA.md و app.services.execution_gate.status() مراجعه کنید."
        )

    async def cancel(self, external_id: str) -> dict:
        raise NobitexExecutionDisabled("لغو سفارش واقعی نوبیتکس پیاده‌سازی نشده است.")

    async def reconcile(self, external_id: str) -> dict:
        raise NobitexExecutionDisabled("تطبیق وضعیت سفارش واقعی نوبیتکس پیاده‌سازی نشده است.")
