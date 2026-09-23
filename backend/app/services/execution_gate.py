"""Fail-closed execution seam. No venue adapter is installed and no real order is sent.

A future live adapter must run server-side with authenticated users, durable idempotency,
verified server quotes/news, per-venue contract rules, reconciliation and an audited kill switch.
Never accept broker/Nobitex secrets or a user-supplied 'safe' market state in an order body.
"""
from __future__ import annotations

from datetime import datetime, timezone
from enum import StrEnum
from typing import Protocol
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class Venue(StrEnum):
    NOBITEX = "NOBITEX"
    MT5_BRIDGE = "MT5_BRIDGE"


class OrderSide(StrEnum):
    BUY = "BUY"
    SELL = "SELL"


class OrderIntent(BaseModel):
    model_config = ConfigDict(extra="forbid")  # in particular, do not accept API keys or passwords

    venue: Venue
    symbol: str = Field(min_length=3, max_length=30, pattern=r"^[A-Za-z0-9/_-]+$")
    side: OrderSide
    quantity: float = Field(gt=0, le=1_000_000, allow_inf_nan=False)
    stop_loss: float = Field(gt=0, allow_inf_nan=False)
    idempotency_key: UUID
    client_note: str = Field(default="", max_length=100)


class VenueAdapter(Protocol):
    """Contract only; the APK must never directly implement/sign a live order."""

    async def instrument_rules(self, symbol: str) -> dict: ...
    async def quote(self, symbol: str) -> dict: ...
    async def submit(self, intent: OrderIntent, user_id: str) -> dict: ...
    async def reconcile(self, external_id: str) -> dict: ...
    async def cancel(self, external_id: str) -> dict: ...


def preflight(intent: OrderIntent) -> dict:
    """Informational only: no trusted account/quote/consensus/news/identity inputs exist yet."""
    return {
        "allowed": False,
        "venue": intent.venue.value,
        "symbol": intent.symbol,
        "idempotency_key": str(intent.idempotency_key),
        "blockers": [
            "کلید توقف اجرای واقعی فعال است (هیچ آداپتر سفارش متصل نیست)",
            "هویت و مجوز مالک حساب روی سرور تأیید نشده است",
            "مشخصات قرارداد، موجودی و سقف ریسک حساب روی سرور تأیید نشده‌اند",
            "قیمت تازهٔ سرور و تایید دو منبع مستقل وجود ندارد",
            "وضعیت خبر/تقویم اقتصادی برای این نماد روی سرور تأیید نشده است",
            "انبار تراکنشی idempotency و تطبیق وضعیت سفارش بروکر نصب نشده‌اند",
        ],
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "note": "این پیش‌بررسی، تایید معامله نیست و هیچ درخواستی به بروکر نمی‌فرستد.",
    }


def status() -> dict:
    return {
        "can_submit": False,
        "kill_switch": True,
        "venues": {
            Venue.NOBITEX.value: {"connected": False, "orders_enabled": False},
            Venue.MT5_BRIDGE.value: {"connected": False, "orders_enabled": False},
        },
        "missing": ["server_auth", "licensed_live_quotes", "source_consensus", "news_calendar",
                    "venue_credentials_in_kms", "broker_rules", "durable_idempotency", "reconciliation", "audited_release"],
    }
