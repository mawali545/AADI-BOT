# AADI BOT

A phone-first, read-only trading scanner with a dark mobile dashboard. It calculates EMA 9/21, RSI 14, momentum and a confluence score. It never places trades; CALL/PUT/WAIT are analysis signals, not guarantees.

## New alternate data route (no Quotex SSID)

The default provider is **OTCharts**, an independent third-party service that claims to relay Quotex market data. It is **not official or endorsed by Quotex**. It uses its own API key and does not require your Quotex password or session token.

**Important before paying:** the current OTCharts API documentation says its free API tier does not include live streaming; live streams require a paid data plan. Do not pay yet. First use the free tier to check whether its latest Quotex candles match the Quotex app for the same symbol and timeframe. If they do not match, do not use it for signals. Check current limits/prices directly at https://otcharts.com/quotex-api and https://otcharts.com/api.

### Termux setup — OTCharts mode

1. Create an OTCharts account and API key at https://otcharts.com/account. Never share that key in chat or commit it to GitHub.
2. Install the lightweight dependency:
   ```sh
   python -m pip install -r requirements.txt
   ```
3. Set the key only in your local Termux shell and select the symbol:
   ```sh
   export AADI_FEED_PROVIDER='otcharts'
   export AADI_OTCHARTS_API_KEY='YOUR_OTCHARTS_KEY'
   export AADI_ASSET='EURUSD_otc'
   export AADI_TIMEFRAME='60'
   python app.py
   ```
4. Open http://127.0.0.1:8000. The dashboard must show LIVE with the tick age updating before treating the feed as live. If the API key's plan does not include streaming, the app should remain disconnected rather than fabricate ticks.

## Optional direct Quotex WebSocket mode

This older mode needs a currently accepted Quotex session and may be rejected by the broker endpoint. It is not the default, and it is not guaranteed to work.

Install its extra dependency only if you want to test that mode:
```sh
python -m pip install -r requirements-quotex-ws.txt
export AADI_FEED_PROVIDER='quotex'
export AADI_QUOTEX_SSID='YOUR_CURRENT_QUOTEX_SESSION'
python app.py
```

Never paste your Quotex session token into chat, screenshots, or GitHub.

## Configuration

- `AADI_FEED_PROVIDER`: `otcharts` (default) or `quotex`
- `AADI_OTCHARTS_API_KEY`: independent OTCharts key, local only
- `AADI_QUOTEX_SSID`: session token for optional direct WebSocket mode
- `AADI_ASSET`: exact instrument id, default `EURUSD_otc`
- `AADI_TIMEFRAME`: candle period in seconds, default `60`
- `AADI_HISTORY_CANDLES`: requested candle history, default `300`
- `AADI_WSS_URL`, `AADI_WS_ORIGIN`: optional direct WebSocket settings
- `AADI_HOST`, `AADI_PORT`: local dashboard bind/port
- `AADI_EMA_FAST`, `AADI_EMA_SLOW`, `AADI_RSI_PERIOD`, `AADI_MIN_CONFLUENCE`
- `AADI_STALE_AFTER`: seconds before data is marked stale

## Safety and data integrity

- No synthetic/test values are used in the production feed.
- OTCharts is a third-party relay, not the broker. Validate its price/time against the Quotex app before using the scanner.
- The dashboard only reports LIVE when the configured provider has connected and a recent tick was received.
- No trades are placed automatically. The user remains responsible for any manual trade.
- A successful app launch or HTTP connection alone does not prove the feed is accurate or live.
