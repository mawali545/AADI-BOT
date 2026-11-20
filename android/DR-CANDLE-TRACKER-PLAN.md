# DR Candle Tracker — Build Specification

## Current interface
- Market-QX trade page inside Android WebView.
- One simple, always-visible DR Candle Tracker panel over the chart.
- No floating bubble and no NEXT / QUANT mini-card.
- Panel shows next-candle CALL / PUT / WAIT, estimated probabilities, live asset/price, current OHLC, latency, tick count, secure bridge status, event log and settled results.
- Manual trading only. No order placement and no synthetic/demo market ticks.

## Probability-based signal approximation
The reference video does not expose its hidden model weights or source code. The app therefore uses a transparent, best-effort probability approximation from live observations only:
- Short-term real-tick momentum.
- Recent tick-direction consistency.
- Current real candle body pressure.
- Upper/lower wick imbalance.
- Price location inside the current candle range.
- Latest tick impulse relative to recent absolute tick movement.

These features are blended into a bounded direction score and converted to CALL/PUT probabilities with a sigmoid mapping. CALL/PUT are issued only when the estimated directional probability reaches 58%; otherwise the result is WAIT. The first directional estimate is available after three real ticks; there is no 200-candle warm-up, no EMA/RSI/MACD gating and no invented historical candles. The probability is a heuristic estimate, not a statistically calibrated win probability or a guarantee.

## Data integrity
- Only accepted real Market-QX messages enter the tick tape and OHLC stream.
- No synthetic/demo/sample prices or candles are generated.
- Signals are for the next 10-second candle and remain manual; no trade orders are placed.
- Outcomes are evaluated against the real candle data received for the target interval.

## Secure bridge scope
- WebView JavaScript creates an AES-256-GCM envelope and wraps the AES key using RSA-OAEP-2048 / SHA-256.
- Android decrypts the envelope and validates the received tick fields.
- The RSA private key is encrypted at rest using a non-exportable AES-GCM key in Android Keystore. The RSA private key is decrypted into process memory when needed; it is not itself a hardware-backed non-exportable RSA key.
- This is the app's authenticated-origin secure WebView bridge. It does not establish that Market-QX's own private server protocol uses this envelope.
- TLS certificate errors fail closed. The secure WebMessage listener is restricted to the Market-QX HTTPS origin and main frame.
- Android app backup is disabled so device-bound key material is not restored without its Keystore wrapping key.

## Verification
- CI JavaScript syntax validation, JVM crypto tests and Android APK assembly are required.
- Physical Android 10 launch, authenticated Market-QX live tick capture, real candle comparison, reconnect handling and on-device signal settlement remain separate device checks.
