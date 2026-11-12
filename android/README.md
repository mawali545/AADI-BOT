# AADI Android app

This branch adds a native Android WebView container for Market-QX. It loads the real Market-QX page and installs a document-start WebSocket hook when the installed Android System WebView supports it. Market authentication remains inside the official site page; no session cookie or SSID is copied into app code.

## Current scope
- Real Market-QX page inside the Android app.
- Early hook of WebSockets created by the page.
- Forwarding recognized price/asset/timestamp messages to the native status display.
- No sample prices and no automatic trade execution.

## Important limitation
This first Android build stage is a real-feed connection client, not yet the complete signal dashboard. The existing Python scanner remains separate until its candle/history and signal engine are ported and tested in the app. Binary frames that are not UTF-8 JSON are not guessed as prices. Update Android System WebView if document-start script support is unavailable.

A debug APK is built by the GitHub Actions workflow on this branch. The APK is unsigned for Play Store distribution.

## Multi-layer signal engine

The Android app uses `SignalEngine.java` to evaluate the next 10-second candle from genuine Market-QX ticks. The signal decision has four independent evidence layers:

- Market trend: EMA 9/21/50, normalized slope, and higher-high/higher-low or lower-high/lower-low structure.
- Price action: candle body and close location, displacement, breakout/rejection, and pullback context.
- Market patterns: engulfing, hammer/shooting-star rejection, morning/evening star, indecision, and double-test rejection.
- Momentum: RSI, EMA-based MACD spread, and ATR-normalized displacement.

The finalizer is regime-aware. In a trend it requires trend and price-action agreement plus a third aligned layer; in a range it requires both strong price-action and reversal-pattern evidence. Mixed or weak evidence returns WAIT.

The accuracy display is not a prediction-confidence score. It is calculated only from completed CALL/PUT predictions versus the actual next 10-second candle close direction, using a rolling window of up to 100 outcomes. It is hidden as a percentage until 20 outcomes have been evaluated. Single-tick bars are excluded. Observed accuracy is retrospective and does not guarantee future results.

The engine warms up on 50 completed live candles. It does not fabricate missing candles, use unverified order-book scalars as Level-2 data, or place trades. Android JVM tests cover warm-up, flat-market WAIT behavior, sustained-trend agreement, measured outcomes, and exclusion of incomplete candles.

