from __future__ import annotations

import asyncio
import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from config import HOST, PORT
from scanner import scanner

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AADI BOT</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#080a0f;color:#eef;font-family:system-ui;padding:16px;max-width:760px;margin:auto}
.card{background:#111620;border:1px solid #28303d;border-radius:18px;padding:18px;margin:10px 0}
.sig{font-size:44px;font-weight:900}.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.v{font-size:20px;font-weight:700;overflow-wrap:anywhere}.muted{color:#9099aa}
</style></head><body>
<div class="card"><h1>AADI BOT</h1><div class="muted">REAL QUOTEX LIVE SCANNER • MANUAL TRADES ONLY</div>
<div id="status">Connecting…</div><div id="age" class="muted"></div><div id="event" class="muted"></div></div>
<div class="card"><div id="sig" class="sig">WAIT</div><div id="score" class="muted">Confluence 0</div></div>
<div class="grid">
<div class="card"><div class="muted">Asset</div><div id="asset" class="v">—</div></div>
<div class="card"><div class="muted">Live price</div><div id="price" class="v">—</div></div>
<div class="card"><div class="muted">EMA Fast</div><div id="ef" class="v">—</div></div>
<div class="card"><div class="muted">EMA Slow</div><div id="es" class="v">—</div></div>
<div class="card"><div class="muted">RSI</div><div id="rsi" class="v">—</div></div>
<div class="card"><div class="muted">Momentum</div><div id="mom" class="v">—</div></div>
</div><div class="muted">Signals are analysis only, not guarantees. Verify live data before trading.</div>
<script>
const $=id=>document.getElementById(id);
function n(v,d=6){return typeof v==='number'?v.toFixed(d):'—'}
async function tick(){try{
 const r=await fetch('/api/status',{cache:'no-store'}); const x=await r.json();
 $('status').textContent='● '+(x.market_status||'UNKNOWN');
 $('age').textContent=x.data_age==null?'No live tick received':'Last tick '+x.data_age.toFixed(1)+'s ago';
 $('event').textContent='Last event: '+(x.last_event||'none');
 $('sig').textContent=x.signal||'WAIT'; $('score').textContent='Confluence '+(x.score??0)+' • closed candles '+(x.candle_count??0);
 $('asset').textContent=x.asset||'—'; $('price').textContent=x.price??'—';
 $('ef').textContent=n(x.ema_fast); $('es').textContent=n(x.ema_slow);
 $('rsi').textContent=n(x.rsi,2); $('mom').textContent=n(x.momentum);
}catch(e){$('status').textContent='SERVER ERROR'}}
setInterval(tick,1000);tick();
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api/status":
            payload = json.dumps(scanner.snapshot(), separators=(",", ":")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if self.path in {"/", "/index.html"}:
            payload = HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_error(404)

    def log_message(self, fmt, *args):
        logging.info("HTTP %s - %s", self.address_string(), fmt % args)


def run_scanner():
    asyncio.run(scanner.run())


if __name__ == "__main__":
    threading.Thread(target=run_scanner, name="aadi-live-scanner", daemon=True).start()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"AADI BOT dashboard: http://127.0.0.1:{PORT}")
    print("This server uses real received data only; it never creates demo prices.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nAADI BOT stopped.")
    finally:
        server.server_close()
