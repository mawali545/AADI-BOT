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
