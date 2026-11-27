# Binary Trading Knowledge Layer

The QX SignalEngine evaluates only completed, broker-supplied OHLC candles and the genuine live quote tape. The bounded 12-candle scan is O(n) and does not add a candle wait or timer.

## Concepts encoded
- Candle anatomy: body, upper/lower wick, close location, doji, pin bars and bullish/bearish engulfing.
- Market structure: higher-high/higher-low and lower-high/lower-low context, prior swing boundaries and decisive structure breaks.
- Support/resistance: nearest confirmed local swing pivots, not only whole-window extremes.
- Breakout quality: close beyond a prior boundary versus a sweep that closes back inside (failed breakout).
- Trend/momentum quality: histogram, weighted candle vector, displacement, path efficiency and directional changes.
- Volatility regime: average true candle range, compression and expansion.
- Entry discipline: continuation requires pullback plus renewed confirmation; reversals require rejection or failed-break evidence; extended moves, doji candles at entry and nearby opposing levels suppress continuation.
- Live confirmation: genuine QX quote pressure is checked immediately; the engine does not wait for another candle.
- Binary expiry evaluation: pending signals are settled once at the first actual quote at/after the configured 10-second expiry.

## Important limits
A setup score is an evidence/confluence score, not a calibrated probability of winning. Binary options remain probabilistic; no rule set guarantees a win. Validate each asset, feed and expiry with out-of-sample replay before using live money. Never fabricate candles, volume, order flow or broker fields that the feed does not provide.
