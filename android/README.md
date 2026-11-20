# DR CANDLE TRACKER — Android 1.0.4

## Main screen
- Compact old-AADI-style layout only: LIVE TICKER, WAIT / CALL / PUT, then WINS / LOSSES.
- The rest of the screen remains the original Market-QX trade page/chart.
- No bubble, mini-card, large technical dashboard, or old AADI signal engine/indicators.
- Overlay has pointer events disabled so it does not intercept website controls.

## Background engine
- Native Java SignalEngine builds 10-second OHLC candles from validated received ticks.
- It evaluates candle momentum, body pressure, wick rejection, recent structure, support/resistance liquidity sweeps, pullback recovery, live tick direction, and sideways/chop conditions.
- It emits CALL / PUT / WAIT; no probability percentages are shown.
- A signal is settled only against the actual received target candle. Missing target data is not counted as a win or loss.
- Manual trading only; no orders are placed automatically. No sample/demo/synthetic market ticks or candles are generated.
- The reference video does not disclose the exact hidden formula or weights. The native rules are an explicit approximation, not claimed source-code reproduction.

## QX data integrity and cryptography
- The WebView observer reads recognized JSON text frames from the existing signed-in Market-QX page WebSocket. Unknown binary/compressed/encrypted formats are not guessed into prices.
- Each recognized source frame receives a SHA-256 fingerprint of the observed WebSocket text frame; its source timestamp and byte length travel through the secure bridge.
- RSA-OAEP-2048 / SHA-256 and AES-256-GCM protect the WebView-to-Android bridge. TLS errors fail closed and the native listener is restricted to the Market-QX HTTPS origin and main frame.
- Important: this local bridge encryption is not Market-QX's proprietary server-side encryption and does not prove QX encrypted frames have been decrypted. The frame fingerprint is an observed-frame hash, not a QX-issued signature. A QX-specific decoder requires actual original protocol frames and a verifiable protocol specification.

## Verification
- CI checks JavaScript syntax, runs JVM unit tests, and assembles the Android debug APK.
- Physical Android 10/Vivo launch, authenticated QX live feed, event/asset mapping, reconnect stability, native control visibility, and real candle settlement still require on-device verification.
