# Build 261 — Binary Trading Knowledge Upgrade

This branch is based on `dr-candle-tracker-build261-low-latency-live-flow` and preserves its real QX OHLC history, live tick corroboration, per-period bucket handling, and one-signal-per-bucket behavior.

## Fast binary price-action context

The SignalEngine now evaluates the already available completed-candle window (maximum 12 bars) for:
- candle anatomy and close location;
- bullish/bearish engulfing and pin-bar patterns, doji, and inside bars;
- local swing support and resistance;
- confirmed close-through breakouts versus wick-through failed breaks / liquidity-sweep reclaims;
- trend path efficiency and recent-versus-baseline range expansion.

The context is computed synchronously in bounded O(12) work. It does not add a timer, network call, artificial candle, or wait for another candle. Existing live quote corroboration and closed-candle entry design remain intact.

## Interpretation and limitations

The score remains an evidence/setup score, not a calibrated probability of winning. OHLC-derived support/resistance and wick sweeps are price-action proxies; they are not exchange order-book depth, traded volume, or a true liquidity map. No binary-options strategy can guarantee correct CALL/PUT outcomes. Validate with replay/out-of-sample results and demo trades before risking money.
