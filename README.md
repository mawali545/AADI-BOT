# AADI BOT — Live Quotex Scanner

A real-market Quotex scanner. **No fake, demo, mock, or generated market feed is used at runtime.**

## Architecture

Quotex live Socket.IO/WebSocket
→ real historical candles
→ real-time quote ticks
→ local OHLC candle builder
→ EMA 9 / EMA 21 + RSI 14 + momentum
→ CALL / PUT / WAIT
→ mobile dashboard

The scanner **does not place real-money orders**. Any trade remains manual.

## Live connection

The current connection targets Quotex's EIO=3 WebSocket endpoint:

`wss://ws2.qxbroker.com/socket.io/?EIO=3&transport=websocket`

Community implementations document `quotes/stream` as real-time quote data and `history/list/v2` / `history/load` as candle-history flows. The code handles the Socket.IO `451-` binary-event headers and binary JSON payloads used by these implementations.

## Setup

1. Install Python 3.11+.
2. Install dependencies:
   `pip install -r requirements.txt`
3. Set the live Quotex SSID **locally**. Never commit the SSID:
   - `AADI_QUOTEX_SSID=<your-live-ssid>`
   - `AADI_ASSET=EURUSD_otc` (change to the exact asset you want)
   - `AADI_TIMEFRAME=60`
4. Run:
   `python app.py`
5. Open the local dashboard in your phone browser.

### Optional environment settings

- `AADI_WSS_URL`
- `AADI_WS_ORIGIN`
- `AADI_ASSET`
- `AADI_TIMEFRAME`
- `AADI_HISTORY_CANDLES`
- `AADI_STALE_AFTER`
- `AADI_EMA_FAST`
- `AADI_EMA_SLOW`
- `AADI_RSI_PERIOD`
- `AADI_MIN_CONFLUENCE`
- `AADI_HOST`
- `AADI_PORT`

## Important

If the SSID is missing, expired, rejected, or the live WebSocket stops delivering ticks, AADI BOT does **not** invent prices/candles/signals. The dashboard shows the connection/data state instead.

Never paste your SSID into GitHub, chat, screenshots, or public files.
