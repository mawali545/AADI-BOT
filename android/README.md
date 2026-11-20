# DR CANDLE TRACKER — Android

Standalone Android package: `com.dr.candletracker` (version 1.0.1). This branch does not compile or package the AADI signal-engine source files.

## Current implementation

- Opens Market-QX in an Android WebView and installs the DR WebSocket observer at document start when supported by Android System WebView.
- Displays the floating DR bubble, NEXT / QUANT SIGNALS card, and neon-on-black tracker panel.
- Builds OHLC candles only from ticks explicitly recognized in Market-QX WebSocket messages. No sample/demo ticks or synthetic candles are generated.
- Includes a separate prototype next-candle scan: the last eight completed real 10-second candles, candle-body votes, close-to-close momentum and directional agreement. This formula comes from the supplied DR prototype ZIP; it is **not confirmed as the hidden algorithm in the reference video**, and its displayed session win rate is not a prediction guarantee.
- Initializes an actual local RSA-OAEP 2048-bit key pair using WebCrypto with SHA-256, performs an encrypt/decrypt round-trip self-test, and displays a public-key fingerprint. Keys are ephemeral and remain in app memory. This validates local cryptography; it does not mean market traffic is encrypted to a remote server.
- Does not place trades or perform unauthorized access, port scanning, credential attacks, or interception of protected account traffic.

## Verification status

A successful GitHub APK build verifies compilation and package identity only. It cannot verify a signed-in Market-QX account or its live WebSocket protocol on the user's physical phone. The observer currently accepts recognized JSON price/asset fields and the documented `[symbol, timestamp, price]` array form; unknown binary/protobuf/compressed frames are not guessed. A physical-device test is still required to confirm actual tick receipt, selected-asset mapping, and reconnect stability.

The reference video is the visual master. The current UI is an implementation pass, not yet certified pixel-for-pixel. Exact comparison and layout refinement must be performed against its captured screens; the video does not expose a verifiable signal formula or prove the displayed security actions are real.

## Build

The existing Android workflow is reused only as build infrastructure (Java 17, Android SDK 35, Gradle 8.9). The DR branch builds `com.dr.candletracker` independently.
