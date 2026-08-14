from dataclasses import dataclass
from datetime import datetime, timezone
from app.models import Candle, Tick, Timeframe


@dataclass
class _MutableCandle:
    bucket: int
    open: float
    high: float
    low: float
    close: float
    volume: float = 0


class CandleBuilder:
    """Builds deterministic event-time OHLC bars and rejects stale ticks.

    Production feeds should supply actual trade volume. OTC spot-gold feeds often
    provide tick volume; this implementation records one unit per accepted tick.
    """

    def __init__(self, timeframe: Timeframe, max_lateness_seconds: int = 2):
        self.timeframe = timeframe
        self.max_lateness_seconds = max_lateness_seconds
        self.current: _MutableCandle | None = None
        self.last_timestamp = 0.0

    def ingest(self, tick: Tick) -> tuple[Candle | None, Candle]:
        epoch = tick.timestamp.timestamp()
        if epoch < self.last_timestamp - self.max_lateness_seconds:
            raise ValueError("Tick arrived outside the lateness window")
        self.last_timestamp = max(self.last_timestamp, epoch)
        bucket = int(epoch // self.timeframe.seconds) * self.timeframe.seconds
        price = tick.mid
        completed = None

        if self.current is None:
            self.current = _MutableCandle(bucket, price, price, price, price, 1)
        elif bucket > self.current.bucket:
            completed = self._snapshot(tick.symbol, complete=True)
            self.current = _MutableCandle(bucket, price, price, price, price, 1)
        elif bucket == self.current.bucket:
            self.current.high = max(self.current.high, price)
            self.current.low = min(self.current.low, price)
            self.current.close = price
            self.current.volume += 1
        # A late tick within tolerance belongs to an already closed bucket and is ignored.

        return completed, self._snapshot(tick.symbol, complete=False)

    def _snapshot(self, symbol: str, complete: bool) -> Candle:
        assert self.current
        c = self.current
        return Candle(
            symbol=symbol,
            timeframe=self.timeframe,
            timestamp=datetime.fromtimestamp(c.bucket, tz=timezone.utc),
            open=c.open, high=c.high, low=c.low, close=c.close,
            volume=c.volume, complete=complete,
        )
