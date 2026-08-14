from datetime import datetime, timezone
from app.models import Tick, Timeframe
from app.services.candle_builder import CandleBuilder


def tick(second: int, price: float) -> Tick:
    return Tick(timestamp=datetime.fromtimestamp(second, timezone.utc), bid=price - .1, ask=price + .1)


def test_builds_and_closes_one_minute_bar():
    builder = CandleBuilder(Timeframe.M1)
    closed, active = builder.ingest(tick(120, 3350))
    assert closed is None and active.open == 3350
    closed, active = builder.ingest(tick(150, 3352))
    assert closed is None and active.high == 3352
    closed, active = builder.ingest(tick(180, 3351))
    assert closed is not None and closed.close == 3352 and closed.complete
    assert active.open == 3351 and not active.complete
