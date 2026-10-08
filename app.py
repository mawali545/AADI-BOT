from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import HTMLResponse

from config import HOST, PORT
from scanner import scanner


@asynccontextmanager
async def lifespan(app):
    task = asyncio.create_task(scanner.run())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(title="AADI BOT", lifespan=lifespan)


@app.get("/api/status")
async def status():
    return scanner.snapshot()


@app.get("/", response_class=HTMLResponse)
async def dashboard():
    return """<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AADI BOT</title>
<style>
body{margin:0;background:#080a0f;color:#eef;font-family:system-ui;padding:16px}
.card{background:#111620;border:1px solid #28303d;border-radius:18px;padding:18px;margin:10px 0}
.sig{font-size:44px;font-weight:900}.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.v{font-size:20px;font-weight:700}.muted{color:#9099aa}
</style>
</head>
<body>
<div class="card"><h1>AADI BOT</h1>
<div class="muted">REAL QUOTEX LIVE SCANNER • MANUAL TRADES ONLY</div>
<div id="status">Connecting…</div>
<div id="age" class="muted"></div></div>
<div class="card"><div id="sig" class="sig">WAIT</div>
<div id="score" class="muted">Confluence 0</div></div>
<div class="grid">
<div class="card"><div class="muted">Asset</div><div id="asset" class="v">—</div></div>
<div class="card"><div class="muted">Price</div><div id="price" class="v">—</div></div>
<div class="card"><div class="muted">EMA Fast</div><div id="ef" class="v">—</div></div>
<div class="card"><div class="muted">EMA Slow</div><div id="es" class="v">—</div></div>
<div class="card"><div class="muted">RSI 14</div><div id="rsi" class="v">—</div></div>
<div class="card"><div class="muted">Momentum</div><div id="mom" class="v">—</div></div>
</div>
<script>
const $=id=>document.getElementById(id);
function n(v,d=6){return typeof v==='number'?v.toFixed(d):'—'}
async function tick(){
 try{
  const x=await (await fetch('/api/status',{cache:'no-store'})).json();
  $('status').textContent='● '+(x.market_status||'UNKNOWN');
  $('age').textContent=x.data_age==null?'No live tick received':'Last tick '+x.data_age.toFixed(1)+'s ago';
  $('sig').textContent=x.signal||'WAIT';
  $('score').textContent='Confluence '+(x.score??0)+' • closed candles '+(x.candle_count??0);
  $('asset').textContent=x.asset||'—'; $('price').textContent=x.price??'—';
  $('ef').textContent=n(x.ema_fast); $('es').textContent=n(x.ema_slow);
  $('rsi').textContent=n(x.rsi,2); $('mom').textContent=n(x.momentum);
 }catch(e){$('status').textContent='○ SERVER ERROR'}
}
setInterval(tick,1000); tick();
</script>
</body>
</html>"""
    

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT)
