# DR CANDLE TRACKER — Android

This is a separate Android application built from a clean DR package namespace. It does not compile or package the AADI signal-engine source files.

## App behavior
- Opens the official Market-QX trade page in an Android WebView so the user signs in on the official site.
- Installs a document-start WebSocket observer on the Market-QX origin when supported by Android System WebView.
- Displays the DR floating bubble, NEXT / QUANT SIGNALS mini-card, and neon green full-screen DR CANDLE TRACKER panel.
- Shows only market ticks recognized from the live page stream. No sample/demo prices are generated.
- OHLC memory is accumulated locally from received live ticks; no historical candles are fabricated.
- Signal analysis stays locked until a separately specified and verified analysis system is implemented.

## Honest limitations
The WebSocket parser is a diagnostic first pass. Market-QX may change its message format; if the actual feed uses an unrecognized or binary protocol, the dashboard will remain in WAITING rather than inventing prices. A successful APK build does not prove that Market-QX live ticks were received on a physical device. The RSA CIPHER tile is explicitly marked NOT INITIALIZED; it does not claim to encrypt data.

## Build
The AADI Android build and tests GitHub Actions workflow is reused as the build mechanism only. This branch builds com.dr.candletracker and uploads dr-candle-tracker-debug-apk.
