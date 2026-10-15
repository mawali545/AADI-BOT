# AADI BOT

A phone-first, read-only Quotex scanner with a dark mobile dashboard. It is intended to calculate EMA 9/21, RSI 14, momentum, and a confluence score from received market data. It never places trades; CALL/PUT/WAIT are analysis signals, not guarantees.

## Direct Quotex feed (default)

The default provider is `quotex`. The WebSocket transport uses `curl_cffi` and the `ws2.qxbroker.com` Socket.IO endpoint with `https://qxbroker.com` as Origin. This endpoint was identified in an unofficial community implementation; it is not an official Quotex API, and server behavior can change. A namespace acknowledgement only proves the public transport opened; it does **not** prove account authentication or live market data. The app must remain DISCONNECTED/STALE until actual current ticks arrive.

1. Install dependencies:
   ```sh
   python -m pip install -r requirements.txt
   ```
2. Set your own current Quotex session value locally in Termux. Never send it in chat, screenshots, or GitHub:
   ```sh
   export AADI_FEED_PROVIDER='quotex'
   export AADI_QUOTEX_SSID='YOUR_CURRENT_QUOTEX_SESSION'
   export AADI_ASSET='EURUSD_otc'
   export AADI_TIMEFRAME='60'
   python app.py
   ```
3. Open `http://127.0.0.1:8000` in your phone browser. Do not use signals unless the dashboard shows LIVE and the displayed price/time match the same instrument in Quotex.

**Android compatibility is not yet verified:** `curl_cffi` can be difficult to install on Termux because its bundled native TLS library may not provide an Android wheel. The GitHub runner can test the transport, but that alone does not prove it installs or connects on your phone. Do not remove working packages or overwrite your backup until this is verified on-device.

## Public endpoint probe (no login)

To check only the unauthenticated handshake and see whether any public market-data event arrives:
```sh
python tools/probe_curl_cffi_endpoint.py
```
The probe sends no credentials and makes no trades. It tests the public namespace only and cannot confirm account authentication. A successful namespace ACK is not counted as live ticks. If the server requires authentication, the public probe stops without trying to evade that requirement.

## Optional third-party feed

OTCharts support remains optional and is not the default. It is an independent service, not official or endorsed by Quotex, and its free streaming entitlement must be verified directly before use. AADI Bot does not require an OTCharts API key in the default Quotex mode.

## Configuration

- `AADI_FEED_PROVIDER`: `quotex` (default) or optional `otcharts`
- `AADI_QUOTEX_SSID`: current session value for direct mode; keep it private
- `AADI_ASSET`: instrument ID, default `EURUSD_otc`
- `AADI_TIMEFRAME`: candle period in seconds, default `60`
- `AADI_WSS_URL`, `AADI_WS_ORIGIN`: optional WebSocket settings
- `AADI_HOST`, `AADI_PORT`: local dashboard bind/port
- `AADI_EMA_FAST`, `AADI_EMA_SLOW`, `AADI_RSI_PERIOD`, `AADI_MIN_CONFLUENCE`
- `AADI_STALE_AFTER`: seconds before data is marked stale

## Data integrity and safety

- No synthetic prices or demo ticks are used.
- A handshake, a successful app launch, or historical candles alone does not prove a live feed.
- The dashboard reports LIVE only while a recent tick has been received by the configured provider.
- No trades are placed automatically. All trade decisions and any real-money clicks remain manual.
