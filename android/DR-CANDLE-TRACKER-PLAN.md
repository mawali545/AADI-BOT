# DR Candle Tracker — Agreed Build Specification

## Main screen
- Copy only the old AADI bot's compact layout: LIVE TICKER at the top, WAIT / CALL / PUT beneath it, then WINS / LOSSES.
- Leave the remaining screen for the original Market-QX chart and native trading controls.
- No floating bubble, NEXT / QUANT mini-card, or full-screen technical dashboard.
- Do not reuse old AADI signal functions, indicators, formulas, or calculations.

## Background reference-video elements
Memory Stream OHLC, live candle buffer, live-feed tracking, vector/market-structure analysis, latency and cycle monitoring, node state, event/terminal logging, scanning checklist, and cipher/bridge status remain background diagnostics only.

## Signal engine
- Native Java backend processes validated received QX ticks.
- Build 10-second OHLC candles from actual source timestamps.
- Analyze momentum, candle body pressure, wick rejection, recent structure, support/resistance liquidity sweeps, pullback recovery, live tick direction, and sideways/chop conditions.
- Output CALL / PUT / WAIT without probability percentages.
- Record a next-candle signal once per candle and settle it only against the actual received target candle. Missing data remains unresolved.
- Manual trading only; never place orders automatically.
- The reference video does not disclose exact hidden weights/formula; the implementation is a transparent approximation, not a claim of exact source reproduction.

## QX data and cryptography — verified scope
- Observe recognized Market-QX WebSocket text events from the signed-in WebView session.
- Preserve the source timestamp and SHA-256 fingerprint of the observed text frame; reject missing/invalid timestamps or fingerprints.
- Do not guess prices from unknown binary, compressed, or encrypted frames.
- App-local RSA-OAEP/AES-GCM protects the WebView-to-Android bridge only. It does not decrypt Market-QX proprietary server-side encryption. A QX protocol decoder requires captured original frames and a verified protocol specification.
- TLS certificate errors fail closed; native WebMessage listener is origin/main-frame restricted.

## Verification
- CI: JavaScript syntax check, JVM unit tests, Android debug APK assembly.
- Physical-device checks are still required for authenticated QX feed, asset mapping, original encrypted protocol handling, reconnect stability, native control visibility, and real next-candle settlement.
