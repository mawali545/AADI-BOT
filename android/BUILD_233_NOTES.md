# DR Candle Tracker Build 233

Version: 1.0.9 (versionCode 10)

## QX frame diagnostics
- Handles JSON object and array payload shapes separately.
- Routes history rows supplied as arrays or objects through the OHLC validator.
- Records Socket.IO binary-event type, event name, attachment count, header size, attachment index/count, byte offset, and payload shape.
- Captures at most a 96-byte HEX and Base64 prefix for approved market-data events only.
- Does not record cookies, authorization headers, SSID, tokens, prices, or complete payload values.
- Binary attachment order is preserved by the serialized WebSocket frame queue.

## Signal behavior
- Keeps the existing eight-completed-candle warm-up, pattern confirmation thresholds, and 10-second result settlement.
- Retains intrabar evaluation on genuine live QX ticks; no synthetic/demo feed is introduced.
- Faster signal arrival is not a claim of higher win rate; live paper testing is required.
