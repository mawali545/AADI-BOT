# DR CANDLE TRACKER

Mobile-first tracker following the supplied reference layout: floating neon-green DR bubble, NEXT / QUANT SIGNALS mini-card, full-screen neon-green-on-black panel, Memory Stream OHLC, animated tick buffer, Vector, Live Feed, RSA Cipher status, Latency, Breach Node, cycle progress, terminal, and tracking sequence.

## Android / Termux
1. Extract the ZIP in Termux.
2. Run `bash start.sh`.
3. Open `http://127.0.0.1:8765`.
4. In Lemur/Tampermonkey, add the complete `dr-market-qx.user.js` as a new userscript, save and enable it.
5. Open `https://market-qx.trade/en/trade`. Tap DR for the mini-card; tap the card for the full tracker.
6. Keep Termux running. LINKED is shown only while ticks arrive within five seconds.

## Real feed
The receiver accepts one verified tick per POST:
`POST http://127.0.0.1:8765/bridge`
`{"asset":"EURUSD_otc","price":1.08234,"timestamp":1795000000000,"source":"DR Market-QX WebSocket"}`
Timestamp may be seconds or milliseconds. Invalid prices and missing assets are rejected. Unknown binary WebSocket packets are not guessed or converted to prices.

## API
- `GET /health`
- `GET /api/status`
- `GET /api/candles`
- `POST /bridge`
- `POST /api/analyze`

The quant scan uses the last eight completed real 10-second candles and returns CALL/PUT/WAIT. It is an informational structure scan, not an AI claim or a guarantee of profit. It does not reuse the old AADI indicator engine and never places trades.

## Build checks
`python -m unittest discover -s tests -v`
`python -m py_compile app.py`
`node --check dr-market-qx.user.js`

GitHub Actions packages a clean Termux ZIP. CI can verify software and receiver behavior but cannot log into Market-QX. The first LIVE LINKED state on the user's device is the confirmation that the current live packet schema is recognized. Never put session cookies or tokens in GitHub.
