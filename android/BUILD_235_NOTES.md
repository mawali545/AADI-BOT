# DR Candle Tracker Build 235
Version: 1.1.1 (versionCode 12)
Base: Build 234

## QX connection telemetry
- QX origin HTTP response timing probe is now actually scheduled after page load and every 30 seconds while visible; it measures a same-origin GET response and reports the median of up to five samples.
- Engine.IO server ping detection now handles text, ArrayBuffer, and Blob WebSocket message payloads, and recognizes ping/pong packets with optional payloads.
- Local PONG SEND remains explicitly labelled LOCAL; it is not server-acknowledged RTT and does not prove account authentication.
- QX account status is only marked VERIFIED when an explicit signed-in account/logout UI marker is visible; visible login controls produce LOGIN REQUIRED; ambiguous page state remains NOT VERIFIED.

## Preserved behavior
- Real QX feed parsing, secure Android bridge, asset/history normalization, tick reorder window, signal thresholds, warm-up, and settlement logic are unchanged.
- No synthetic feed, fake login status, or fabricated telemetry values are introduced.

## Validation caveat
- JavaScript syntax validation passed locally before upload.
- GitHub Actions build and live Market-QX verification are still required. The account UI selectors must be confirmed against the actual QX page before treating login status as verified.
