# DR Candle Tracker — Agreed Build Specification

## Main screen
- Copy only the old AADI bot's compact layout: LIVE TICKER at the top, WAIT / CALL / PUT beneath it, then WINS / LOSSES.
- Leave the remaining screen for the original Market-QX chart and native trading controls.
- No floating bubble, NEXT / QUANT mini-card, or full-screen technical dashboard.
- Do not reuse old AADI signal functions, indicators, formulas, or calculations.

## Background reference-video elements
Memory Stream OHLC is imported only from original Market-QX candle/history events. Live quote ticks feed the rolling price tape, normalized four-component vector (momentum, acceleration, path efficiency, tick pressure), settlement, source-to-arrival latency, processing cycle counter, node state, scan state, and bounded event log. Ticks are NEVER aggregated into synthetic OHLC candles. These are transparent measurable implementations, not claims to reproduce undisclosed proprietary video formulas.

## Signal engine
- Native Java backend processes validated received QX ticks.
- Build 10-second OHLC candles from actual source timestamps.
- Analyze momentum, candle body pressure, wick rejection, recent structure, support/resistance liquidity sweeps, pullback recovery, live tick direction, and sideways/chop conditions.
- Output CALL / PUT / WAIT without probability percentages.
- Keep the existing CALL / PUT / WAIT scan rules and thresholds unchanged.
- Save the exact signal timestamp and entry price. At signal timestamp + 10 seconds, settle against the first genuine QX quote tick at or after expiry: CALL wins if price is higher, PUT wins if price is lower, opposite movement is LOSS, equal price is TIE. If no live tick arrives, keep the result pending; never fabricate a result.
- Keep each pending signal independent; no result is settled from a synthetic candle close.
- Manual trading only; never place orders automatically.
- The reference video does not disclose exact hidden weights/formula; the implementation is a transparent approximation, not a claim of exact source reproduction.

## QX data and cryptography — verified scope
- Observe recognized Market-QX WebSocket text events from the signed-in WebView session.
- Also passively observe same-origin HTTP fetch/XHR JSON responses only on market/quote/price/tick/candle/chart/history paths; clone responses without altering requests, and never read cookies, authorization headers, or account endpoints. Normalize recognized quote/history payloads through the same validators as WebSocket events.
- Preserve the source timestamp and SHA-256 fingerprint of the observed text frame; reject missing/invalid timestamps or fingerprints.
- Do not guess prices from unknown binary, compressed, or encrypted frames.
- App-local RSA-OAEP/AES-GCM protects the WebView-to-Android bridge only. It does not decrypt Market-QX proprietary server-side encryption. A QX protocol decoder requires captured original frames and a verified protocol specification.
- TLS certificate errors fail closed; native WebMessage listener is origin/main-frame restricted.

## Verification
- CI: JavaScript syntax check, JVM unit tests, Android debug APK assembly.
- Physical-device checks are still required for authenticated QX feed, asset mapping, original encrypted protocol handling, reconnect stability, native control visibility, and real next-candle settlement.


## Build 35 protocol and signal pipeline additions
- Observe QX WebSocket text frames and pair Socket.IO binary-event headers with their following binary attachments.\n- Passively observe the result of QX's own successful WebCrypto decrypt calls; do not extract keys or modify encryption. Only allow-listed market/candle event payloads are parsed and forwarded; unrelated plaintext is discarded.
- Resolve Socket.IO binary placeholders only when an attachment is valid UTF-8 JSON; retain unknown binary attachments as opaque evidence and report their byte counts rather than inventing a decoder. WebSocket Blob/ArrayBuffer attachments are passed intact to this parser.\n- Observe QX's own WebCrypto decrypt result and forward only allow-listed market/candle events; no QX key extraction or authentication bypass.
- Observe same-origin market/history JSON delivered through fetch, XMLHttpRequest, and EventSource. Do not read cookies, authorization headers, account endpoints, or alter broker requests.
- Keep SHA-256 as a local digest of the observed raw frame/response only; it is not represented as an official QX fingerprint.
- Preserve QX source timestamps, asset identity, event source, and native validation before feeding the live signal engine.
- Expand the 10-second next-candle feature families: candle body pressure, bullish/bearish votes, momentum, directional agreement, wick rejection, support/resistance, liquidity sweep, failed breakout, pullback detection, market chop, volatility analysis, and vector/microstructure.
- The RSA-OAEP/AES-GCM cipher remains the authenticated local WebView-to-Android bridge cipher. QX application-level WebCrypto decryption is observed after the QX page itself successfully decrypts; encrypted formats handled by other undocumented mechanisms remain opaque.
- No fake/demo ticks, fabricated fingerprints, guessed cipher keys, or unsupported accuracy claims.
