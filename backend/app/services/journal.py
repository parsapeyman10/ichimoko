import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from app.models import Candle, Direction, JournalEntry, TradeSignal, Timeframe

STORE = Path(__file__).resolve().parent.parent / "data" / "journal.json"
STORE.parent.mkdir(parents=True, exist_ok=True)

def _load() -> list[dict]:
    if not STORE.exists():
        return []
    try:
        return json.loads(STORE.read_text())
    except Exception:
        return []

def _save(entries: list[dict]):
    STORE.write_text(json.dumps(entries, ensure_ascii=False, indent=2, default=str))

def list_entries(limit: int = 100) -> list[JournalEntry]:
    raw = _load()
    out = []
    for r in raw[-limit:]:
        try:
            out.append(JournalEntry(**r))
        except Exception:
            continue
    return list(reversed(out))

def add_signal(signal: TradeSignal, timeframe: Timeframe) -> JournalEntry | None:
    if signal.action not in (Direction.BUY, Direction.SELL):
        return None
    if signal.entry is None or signal.stop_loss is None or signal.take_profit is None:
        return None
    entry = JournalEntry(
        id=str(uuid.uuid4())[:8],
        timeframe=timeframe,
        action=signal.action,
        entry=float(signal.entry),
        stop_loss=float(signal.stop_loss),
        take_profit=float(signal.take_profit),
        confidence=float(signal.confidence),
        risk_reward=float(signal.risk_reward or 1.8),
        reasons=signal.reasons,
        blockers=signal.blockers,
        status="OPEN",
    )
    raw = _load()
    raw.append(entry.model_dump(mode="json"))
    # keep last 500
    raw = raw[-500:]
    _save(raw)
    return entry

def close_entry(entry_id: str, exit_price: float, reason: str) -> JournalEntry | None:
    raw = _load()
    for r in raw:
        if r["id"] == entry_id and r["status"] == "OPEN":
            r["closed_at"] = datetime.now(timezone.utc).isoformat()
            r["exit_price"] = exit_price
            r["exit_reason"] = reason
            r["status"] = "CLOSED"
            # compute pnl for 1 oz
            entry_price = r["entry"]
            is_buy = r["action"] == "BUY"
            pnl = (exit_price - entry_price) if is_buy else (entry_price - exit_price)
            r["pnl"] = round(pnl, 2)
            r["pnl_percent"] = round(pnl / entry_price * 100, 3)
            _save(raw)
            return JournalEntry(**r)
    return None

def stats() -> dict:
    entries = list_entries(500)
    closed = [e for e in entries if e.status == "CLOSED" and e.pnl is not None]
    if not closed:
        return {"total": len(entries), "closed": 0, "win_rate": 0, "profit_factor": 0, "total_pnl": 0, "avg_rr": 0}
    wins = [e for e in closed if e.pnl > 0]
    losses = [e for e in closed if e.pnl <= 0]
    gross_profit = sum(e.pnl for e in wins) if wins else 0
    gross_loss = abs(sum(e.pnl for e in losses)) if losses else 0
    total_pnl = sum(e.pnl for e in closed)  # type: ignore
    return {
        "total": len(entries),
        "closed": len(closed),
        "open": len([e for e in entries if e.status == "OPEN"]),
        "wins": len(wins),
        "losses": len(losses),
        "win_rate": round(len(wins)/len(closed)*100, 1),
        "profit_factor": round(gross_profit / gross_loss, 2) if gross_loss else 99.9,
        "total_pnl_1oz": round(total_pnl, 2),  # type: ignore
        "avg_rr": round(sum(e.risk_reward for e in closed)/len(closed), 2),
        "avg_confidence": round(sum(e.confidence for e in closed)/len(closed), 1),
    }

