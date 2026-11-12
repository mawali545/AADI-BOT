# AADI X — mobile bridge setup

This is the X-style branch of AADI Bot. It is separate from the direct-WebSocket scanner.

## Components
- `xstyle_bridge.py`: local dashboard and HTTP receiver at `127.0.0.1:8765`.
- `aadi_x_bridge.user.js`: browser userscript that watches text WebSocket messages and forwards likely market messages to the local receiver.
- It does not copy passwords, cookies, or session tokens and does not place trades.

## Android setup outline
1. In Termux, install Python if needed.
2. Download the two files from this repository into one folder.
3. Run `python xstyle_bridge.py` and keep Termux open.
4. Open `http://127.0.0.1:8765` in the phone browser.
5. In a browser that supports userscript extensions, install a userscript manager and add `aadi_x_bridge.user.js`.
6. Reload the already logged-in chart page and watch AADI X's packet diagnostics.

## Important limitations
- The userscript must run on the exact chart domain. It currently matches `market-qx.trade`, `qxbroker.com`, and `quotex.com` subdomains.
- Some browsers do not allow userscripts to contact localhost, and some sites use binary WebSocket frames or a page-isolated socket implementation. In those cases the bridge may show no packets; do not call it connected.
- The first version only parses recognizable text JSON price fields. It deliberately does not invent values. Unknown packets remain diagnostic messages and need their real format identified before candle parsing can be made reliable.
- A displayed price is not considered verified until it can be compared with the visible chart and updates continuously.
- Signals are analysis only, not guarantees. Trades remain manual.
- Never put your Quotex password, cookies, or session ID into GitHub or a chat.
