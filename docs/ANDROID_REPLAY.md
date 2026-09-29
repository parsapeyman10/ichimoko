# Android learning lab: real-data bar replay

## Contract

`ReplayEngine.Session` is immutable. Its `cursor` is an information boundary:

- `visibleBars` is exactly the verified, closed prefix ending at the cursor;
- `SignalEngine.series()` and `SignalEngine.decide()` receive only that prefix;
- `Backtester.run()` is rerun on the same prefix, so next-open fills, gap handling, SL/TP ambiguity, fees and open-at-end rules do not diverge between batch backtest and replay;
- moving the cursor backwards cannot retain a signal, fill, or indicator value from the discarded future;
- play, pause, step, seek, speed, reset, and selecting a new reset point are UI operations on copied state.

A cursor before the engine warm-up is a valid state. It shows real candles and `NO_TRADE`/no result rather than estimated indicator values.

## Ichimoku contract

`Ichimoku` exposes all five standard lines:

1. Tenkan-sen;
2. Kijun-sen;
3. Senkou Span A;
4. Senkou Span B;
5. Chikou Span.

Raw Senkou values remain at their computation index. `cloudAt(index)`, `spanAAt(index)`, and `spanBAt(index)` read the values computed `displacement` bars earlier, which is the execution-safe visible cloud. Chikou is indexed by its display bar and reads the close `displacement` bars ahead; the right edge is `null` until that source close exists. No padding, interpolation, or estimated line is allowed.

`SignalEngine` uses current-bar Chikou confirmation (`close` above the high or below the low of the Kijun lookback structure). It is enabled by default and can be disabled only as an explicit `SignalProfile` experiment. The same profile is used by live, replay, and batch backtest paths.

## Provider policy

For chart/research history the public, keyless history adapter is tried first. A user-entered Twelve Data key is the last fallback, not the primary. Provider output is checked for symbol, currency, interval, timestamps, OHLC validity, duplicate timestamps, freshness and minimum real-bar count. Observed time gaps are retained in provenance and passed through unchanged; a gap never becomes a synthetic candle.

The app is a forex/XAU workspace. Crypto-only APIs such as Binance, Kraken, CoinGecko, and similar services are not treated as valid XAU/USD or FX history providers. A source must match the requested instrument and timeframe; broad coverage is not a substitute for venue/instrument identity.

Research/replay requires at least 3,000 closed real candles. The live chart may bootstrap with more than 1,000 and grow its cache toward the 3,000 target. Failure to obtain a validated window is a visible error, never an invented result.

## Educational scope

Replay and reports are paper-only. They display hypothetical strategy decisions, fills, fees/spread assumptions, drawdown, win rate, profit factor, open positions, gaps, and the exact data source. An actionable cursor can be saved as an educational replay decision in the separate replay journal; that record is explicitly a decision marker, not a broker fill and not a live paper position. They do not place broker orders and do not claim that historical results prove future profitability.

## Interactive paper test

A saved decision starts as `PENDING_ENTRY` while the cursor is on its decision candle. The evaluator uses the next revealed real candle's open as the educational fill, then checks SL/TP only through the current cursor. Therefore a target or stop visible in the downloaded dataset is not an outcome until replay has stepped over that candle. `DECISION_ONLY` is used when the cursor is before the decision candle; `OPEN` and `OPEN_AT_END` mean that no exit level has been revealed yet, while `WIN` and `LOSS` include the revealed outcome candle and price. A missing decision candle or real time gap produces `DATA_GAP`, never an interpolated result. If a revealed bar touches both SL and TP, the conservative stop-first rule records `LOSS`. Moving backwards reevaluates only the newly visible prefix, so a future outcome cannot survive behind the cursor.

These fields are updated atomically in `replay_decisions.json`; a failed update preserves the previous file. The interactive evaluator is a pure adapter around the same candle/interval contract and does not create a second trading strategy or any live order.
