# AADI BOT — Live Quotex Scanner

A phone-friendly live market scanner intended to display **actual received Quotex data only**. It does not generate demo prices and does not place trades. Real-money trades remain manual.

## Android / Termux setup

1. Install Python and Git in Termux.
2. Clone this repository or enter the existing project folder.
3. Install the single runtime dependency:

   `python -m pip install -r requirements.txt`

   The dashboard uses Python's standard-library HTTP server, so FastAPI, Uvicorn, Pydantic, and Rust build steps are not needed.

4. Set your live Quotex session locally in Termux. Do not share it in chat, screenshots, GitHub, or public files:

   `export AADI_QUOTEX_SSID='PASTE_YOUR_SESSION_VALUE_LOCALLY'`

   `export AADI_ASSET='EURUSD_otc'`

   `export AADI_TIMEFRAME='60'`

5. Start the app:

   `python app.py`

6. Open `http://127.0.0.1:8000` in the phone browser.

## Settings

- `AADI_WSS_URL`: defaults to `wss://ws2.qxbroker.com/socket.io/?EIO=3&transport=websocket`
- `AADI_WS_ORIGIN`: defaults to `https://qxbroker.com`
- `AADI_ASSET`: exact asset symbol supported by the live feed
- `AADI_TIMEFRAME`: candle timeframe in seconds (default `60`)
- `AADI_HISTORY_CANDLES`: history request size
- `AADI_EMA_FAST`, `AADI_EMA_SLOW`, `AADI_RSI_PERIOD`, `AADI_MIN_CONFLUENCE`: signal settings
- `AADI_HOST`, `AADI_PORT`: dashboard bind address and port

## Data integrity and limitations

The dashboard can start without a session, but it will show a disconnected state until a valid live session and working endpoint are configured. A successful HTTP dashboard load does **not** prove that Quotex authentication or live tick subscription works. Verify the status is LIVE and the tick age remains low before considering any signal. If Quotex changes its private protocol or rejects the session, the scanner cannot force a connection.

Keep your session value private. Session tokens may grant account access. Never publish them or commit them to Git.

Signals are technical indicators, not a guarantee of profit. No orders are placed by this program.


## Binary packet diagnostics

The WebSocket client now handles Socket.IO binary-event headers by reading the declared attachment count, collecting attachments, and replacing valid JSON attachments in the event payload. Unknown binary frames are not converted into guessed prices; Termux logs show the frame size and a short byte prefix so protocol changes can be diagnosed without dumping a full account/session payload.

Run the decoder unit tests from the repository root with:

`python -m unittest discover -s tests -v`

If the live feed changes to a non-JSON binary encoding, the scanner will report an unclassified frame and continue without inventing market data. The dashboard now shows event counts, binary frame counts, decoded JSON counts, unclassified frame counts, last frame size, a short byte prefix, and the current candle assembled from received ticks. That frame still needs a protocol-specific decoder based on the actual observed bytes.

GitHub Actions runs the decoder unit tests on pushes and pull requests.
