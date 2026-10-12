# AADI BOT — Browser-based live Quotex scanner

AADI BOT is a local mobile dashboard and read-only market-data scanner. It does not place trades. CALL/PUT/WAIT are analysis signals, not guaranteed outcomes.

## What it does
- Connects to the configured Quotex Socket.IO/WebSocket endpoint.
- Builds candle closes from received market data/history.
- Calculates EMA 9/21, RSI 14, momentum, and a simple confluence score.
- Shows connection state and data freshness in a local browser dashboard.
- Does not create fake/demo market ticks. Without an accepted authenticated session and live ticks, it remains disconnected/waiting.

## Termux setup
Python and pip are required. Install the only Python dependency:
```sh
python -m pip install -r requirements.txt
```

Set your own session locally in Termux; never paste it into chat, screenshots, or GitHub:
```sh
export AADI_QUOTEX_SSID='PASTE_YOUR_CURRENT_SESSION_HERE'
export AADI_ASSET='EURUSD_otc'
export AADI_TIMEFRAME='60'
python app.py
```

Open `http://127.0.0.1:8000` in the phone browser. Keep Termux running while using the dashboard.

## Configuration
- `AADI_QUOTEX_SSID`: current authenticated Quotex session token (required)
- `AADI_ASSET`: exact Quotex asset symbol; default `EURUSD_otc`
- `AADI_TIMEFRAME`: candle period in seconds; default `60`
- `AADI_WSS_URL`, `AADI_WS_ORIGIN`: WebSocket endpoint/origin
- `AADI_HISTORY_CANDLES`: history request size
- `AADI_HOST`, `AADI_PORT`: local server bind/port
- `AADI_EMA_FAST`, `AADI_EMA_SLOW`, `AADI_RSI_PERIOD`, `AADI_MIN_CONFLUENCE`
- `AADI_STALE_AFTER`: seconds before data is marked stale

## Important limitations
The Quotex endpoint and event format can change. A successful Python start or dashboard load does not prove that a session was accepted or that live ticks arrived. Confirm the dashboard reports `LIVE` and the tick age continues to update before relying on any analysis. Never share your session token.
