# Build 261 — Binary Trading Knowledge Engine

This branch is based on `dr-candle-tracker-build261-low-latency-live-flow`. It preserves the real QX OHLC history, live quote stream, per-period bucket handling, and one-signal-per-bucket behavior.

## Objective: 10-second binary expiry

A qualifying CALL/PUT is evaluated synchronously from the latest available broker candles and quote events. Each emitted signal is tracked against the first genuine quote at or after **10,000 ms** for outcome settlement. The engine does not sleep for ten seconds before emitting, fetch synthetic candles, or wait for a future candle to close. The ten-second period is the evaluation/settlement horizon, not a promise that every market state will produce a trade.

## Knowledge areas represented in code

1. **Market state:** directional trend, range/chop, transition, impulse, exhaustion and volatility expansion/contraction.
2. **Market structure:** swing highs/lows, higher-high/higher-low and lower-high/lower-low context, local support/resistance, structure breaks.
3. **Candle reading:** open/high/low/close anatomy, body-to-range, upper/lower wick, close location, bullish/bearish pressure.
4. **Candle formations:** bullish/bearish engulfing, pin bars, doji, inside bars, rejection candles and strong-body confirmation.
5. **Price action:** pullback/continuation, momentum follow-through, reversal confirmation, failed breakouts, wick-through reclaim/rejection.
6. **Liquidity concepts (OHLC proxy only):** repeated swing-level tests, stop-run-style wick sweeps and close-back-inside confirmation.
7. **Trend and momentum:** normalized regression slope, directional histogram/vector, displacement versus travelled path, tick direction pressure.
8. **Volatility/risk context:** average candle range, recent-versus-baseline range ratio, extended moves and low-quality choppy conditions.
9. **Microstructure for the requested expiry:** genuine quote events from the trailing ten seconds, directional pressure, first-half/second-half pressure change (acceleration), displacement normalized by candle range, and path efficiency.
10. **Signal discipline:** independent evidence confluence, opposing-flow veto, no chasing extended moves, WAIT when evidence conflicts, and one signal per chart bucket.
11. **Outcome accounting:** every signal is checked once against the first actual quote at/after its 10-second expiry; wins, losses and ties are counted from observed prices.

All of these calculations are deterministic and bounded by the existing in-memory candle/tick windows. The ten-second tape analysis adds CPU work only; it does not add an intentional delay or network round trip.

## Data the engine must not pretend to know

The current QX feed contract supplies OHLC and quote events, with an optional broker direction field. Unless the broker actually supplies them, the engine cannot know:
- traded volume, bid/ask spread, depth, order-book imbalance, or institutional order flow;
- exchange-grade volume profile, footprint/delta, gamma exposure, options open interest, or a genuine gamma profile;
- broker payout changes, exact execution latency/slippage, or the broker's internal OTC price-generation process;
- verified news/event calendar, macro releases, or cross-market feeds not connected to this app.

The code must never fabricate those inputs or label a wick-based proxy as actual order-book/liquidity data. Additional providers can be integrated only after their source, timestamps, symbol mapping, licensing, and feed integrity are validated.

## Validation and honest scoring

The displayed score is an **evidence/setup score**, not a measured win probability. A score of 85 does not mean an 85% win rate. Accuracy must be measured using timestamped historical replay and untouched out-of-sample data, grouped by asset, session, market regime and expiry. Track CALL/PUT precision, coverage, false positives, calibration, ties, feed gaps, stale quotes and latency. Do not tune thresholds on the same sample used to report performance. Demo-test before live use; no strategy guarantees a win.
