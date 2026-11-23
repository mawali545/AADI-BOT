# DR Candle Tracker Build 234

Version: 1.1.0 (versionCode 11)
Base: Build 233

## QX asset and history integrity
- Active asset inference now prioritizes validated quote-stream rows and subscription events, rather than arbitrary incoming events such as instrument lists.
- Broker history is normalized into ascending candle-bucket order and duplicate buckets are collapsed before native validation.
- History rows without an explicit symbol use the most recent validated quote asset, not an arbitrary event's symbol.

## Tick ordering
- A bounded 220 ms per-asset reorder window sorts short out-of-order quote bursts by broker timestamp before integrity monitoring and OHLC construction.
- Duplicate broker timestamps within the pending window are ignored.
- A maximum 64-tick queue per asset bounds memory and flushes immediately at capacity.
- Ticks arriving later than the reorder window remain rejected by the existing integrity/engine timestamp guards.

## Signal safeguards
- Existing price-action thresholds, 8-completed-candle warm-up, 10-second result settlement, and real-feed-only rule are unchanged.
- No signal is forced when the validated market structure does not confirm an entry.
- Successful compilation/tests do not establish live signal accuracy; paper-trading validation on the selected QX asset is still required.
