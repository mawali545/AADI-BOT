from __future__ import annotations

import asyncio
import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from config import HOST, PORT
from scanner import scanner

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

DASHBOARD = r"""<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#080a0f"><title>AADI BOT</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}
body{margin:0;background:#080a0f;color:#eef2ff;font-family:system-ui,Arial,sans-serif;padding:16px;max-width:760px;margin-inline:auto}
.card{background:#111620;border:1px solid #28303d;border-radius:16px;padding:16px;margin:10px 0}
h1{margin:0 0 6px;color:#72e3c2}.muted{color:#9099aa;font-size:13px}
.sig{font-size:42px;font-weight:900;margin:12px 0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.v{font-size:19px;font-weight:700;overflow-wrap:anywhere}.status{font-weight:700;margin-top:14px}
.note{font-size:12px;line-height:1.5;color:#aeb8c9}
</style></head><body>
<div class="card"><h1>AADI BOT</h1><div class="muted">REAL QUOTEX DATA · MANUAL TRADES ONLY</div>
<div id="status" class="status">Checking local scanner…</div><div id="age" class="muted">Waiting for feed status</div></div>
<div class="card"><div class="muted">CURRENT DECISION</div><div id="sig" class="sig">WAIT</div>
<div id="score" class="muted">Confluence 0</div><div class="note">A signal is analysis, not a guarantee. AADI Bot never places trades.</div></div>
<div class="grid">
<div class="card"><div class="muted">Asset</div><div id="asset" class="v">—</div></div>
<div class="card"><div class="muted">Latest price</div><div id="price" class="v">—</div></div>
<div class="card"><div class="muted">EMA 9</div><div id="ef" class="v">—</div></div>
<div class="card"><div class="muted">EMA 21</div><div id="es" class="v">—</div></div>
<div class="card"><div class="muted">RSI 14</div><div id="rsi" class="v">—</div></div>
<div class="card"><div class="muted">Momentum</div><div id="mom" class="v">—</div></div>
</div>
<div class="card"><div class="muted">CLOSED CANDLES</div><div id="candles" class="v">0</div>
<div class="note">Candles and indicators are calculated only from received Quotex data. If the session is missing, rejected, or stale, the bot stays disconnected/waiting.</div></div>
<script>
const $=id=>document.getElementById(id);
function n(v,d=6){return typeof v==='number'&&Number.isFinite(v)?v.toFixed(d):'—'}
async function tick(){try{
const r=await fetch('/api/status',{cache:'no-store'});if(!r.ok)throw Error('HTTP '+r.status);
const x=await r.json(),st=x.market_status||'UNKNOWN';
$('status').textContent='● '+st;$('status').style.color=st==='LIVE'?'#72e3c2':st==='STALE'?'#ffd166':'#ff9a9a';
$('age').textContent=x.data_age==null?'No real live tick received':'Last real tick '+x.data_age.toFixed(1)+' seconds ago';
$('sig').textContent=x.signal||'WAIT';$('sig').style.color=x.signal==='CALL'?'#72e3c2':x.signal==='PUT'?'#ff7185':'#ffd166';
$('score').textContent='Confluence '+(x.score??0)+' · closed candles '+(x.candle_count??0);
$('asset').textContent=x.asset||'—';$('price').textContent=n(x.price);
$('ef').textContent=n(x.ema_fast);$('es').textContent=n(x.ema_slow);$('rsi').textContent=n(x.rsi,2);$('mom').textContent=n(x.momentum);$('candles').textContent=x.candle_count??0;
}catch(e){$('status').textContent='LOCAL SERVER ERROR';$('age').textContent=String(e)}}
tick();setInterval(tick,1000);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/":
            self._send(200, DASHBOARD.encode("utf-8"), "text/html; charset=utf-8")
        elif self.path == "/api/status":
            body = json.dumps(scanner.snapshot(), allow_nan=False).encode("utf-8")
            self._send(200, body, "application/json; charset=utf-8")
        elif self.path == "/favicon.ico":
            self._send(204, b"", "image/x-icon")
        else:
            self._send(404, b'{"error":"not found"}', "application/json")

    def log_message(self, fmt: str, *args: object) -> None:
        logging.getLogger("aadi.http").info("%s - %s", self.address_string(), fmt % args)


def _run_scanner() -> None:
    asyncio.run(scanner.run())


def main() -> None:
    threading.Thread(target=_run_scanner, name="aadi-live-feed", daemon=True).start()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"AADI BOT dashboard: http://127.0.0.1:{PORT}", flush=True)
    print("Live status stays DISCONNECTED until Quotex authentication and real ticks are received.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nAADI BOT stopped.", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
