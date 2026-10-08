# AADI BOT — Live Quotex Scanner

Real-data scanner for Quotex. No demo/fake market feed is included.

## Architecture
Quotex Socket.IO/WebSocket -> live quote/candle stream -> EMA/RSI/momentum confluence -> CALL/PUT/WAIT -> mobile dashboard.

The scanner does **not** place real-money orders.

## Setup
1. Install Python 3.11+.
2. Install dependencies: `pip install -r requirements.txt`
3. Set your Quotex session locally (never commit it):
   - `AADI_QUOTEX_SSID=<your live session>`
   - `AADI_IS_DEMO=0`
   - optional `AADI_WSS_URL`
4. Run: `python app.py`
5. Open the displayed local URL on the phone browser.

If the Quotex WebSocket protocol changes, the connection layer is isolated in `quotex_ws.py`.
