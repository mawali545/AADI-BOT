# DR Candle Tracker — Build Specification

## Reference-driven interface
- Market-QX trade page inside Android WebView.
- Floating neon-green DR bubble, NEXT / QUANT SIGNALS mini-card, and dark neon-green full tracker panel.
- Live memory stream, OHLC, tick count, feed diagnostics, latency, cipher status, event log, signal history and settled outcome counts.
- Manual signals only. No order placement and no synthetic/demo market ticks.

## Indicator set (only)
- EMA 9, EMA 20, EMA 50, EMA 100, EMA 200
- RSI 14
- MACD 12/26/9

No ADX, DMI, Supertrend, Bollinger Bands, Stochastic, or other unrequested indicators are included.

## Signal readiness
- Candle structure uses the latest eight completed real 10-second candles.
- Full indicator confirmation waits until 200 completed real 10-second candles are collected for EMA 200; RSI 14 and MACD 12/26/9 must also be ready.
- Until warm-up completes, the app displays WAIT and the exact number of completed candles collected. It must not substitute simulated or partial data.
- CALL / PUT require directional candle agreement, ordered EMA stack, RSI range confirmation and MACD histogram direction. Otherwise the result is WAIT.
- Indicator values and the signal score are informational, not a guarantee of trading outcome.

## Secure bridge scope
- WebView JavaScript creates an AES-256-GCM envelope and wraps the AES key using RSA-OAEP-2048 / SHA-256.
- Android decrypts the envelope and validates the received tick fields.
- The RSA private key is encrypted at rest using a non-exportable AES-GCM key in Android Keystore. The RSA private key is decrypted into process memory when needed; it is not itself a hardware-backed non-exportable RSA key.
- This is the app's authenticated-origin secure WebView bridge. It does not establish that Market-QX's own private server protocol uses this envelope.
- TLS certificate errors fail closed. The secure WebMessage listener is restricted to the Market-QX HTTPS origin and main frame.
- Android app backup is disabled so device-bound key material is not restored without its Keystore wrapping key.

## Verification still required
- CI compile and JVM crypto tests.
- Physical Android 10 device launch and Market-QX authenticated session test.
- Confirm real tick subscription, envelope decrypt, asset/price validation, reconnect behavior, and signal settlement using live data.
