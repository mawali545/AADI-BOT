from __future__ import annotations
import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from config import HOST,PORT
from scanner import scanner

@asynccontextmanager
async def lifespan(app):
    task=asyncio.create_task(scanner.run())
    yield
    task.cancel()
    try: await task
    except asyncio.CancelledError: pass

app=FastAPI(title="AADI BOT",lifespan=lifespan)

@app.get("/api/status")
async def status(): return scanner.snapshot()

@app.get("/",response_class=HTMLResponse)
async def dashboard():
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>AADI BOT</title>
<style>body{margin:0;background:#080a0f;color:#eef;font-family:system-ui;padding:16px}.card{background:#111620;border:1px solid #28303d;border-radius:18px;padding:18px;margin:10px 0}.sig{font-size:44px;font-weight:900}.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.v{font-size:20px;font-weight:700}.muted{color:#9099aa}</style></head>
<body><div class="card"><h1>AADI BOT</h1><div class="muted">REAL QUOTEX LIVE SCANNER • MANUAL TRADES ONLY</div><div id="status">Connecting…</div></div>
<div class="card"><div id="sig" class="sig">WAIT</div><div id="score" class="muted">Confluence 0</div></div>
<div class="grid"><div class="card"><div class="muted">Asset</div><div id="asset" class="v">—</div></div><div class="card"><div class="muted">Price</div><div id="price" class="v">—</div></div><div class="card"><div class="muted">EMA 9</div><div id="ef" class="v">—</div></div><div class="card"><div class="muted">EMA 21</div><div id="es" class="v">—</div></div><div class="card"><div class="muted">RSI 14</div><div id="rsi" class="v">—</div></div><div class="card"><div class="muted">Momentum</div><div id="mom" class="v">—</div></div></div>
<script>async function tick(){try{let x=await (await fetch('/api/status',{cache:'no-store'})).json();status.textContent=x.authenticated?'● LIVE + AUTHENTICATED':x.connected?'● CONNECTED / AUTH PENDING':'○ DISCONNECTED';sig.textContent=x.signal||'WAIT';score.textContent='Confluence '+(x.score??0)+' • candles '+x.candle_count;asset.textContent=x.asset||'—';price.textContent=x.price??'—';ef.textContent=x.ema_fast?.toFixed(6)||'—';es.textContent=x.ema_slow?.toFixed(6)||'—';rsi.textContent=x.rsi?.toFixed(2)||'—';mom.textContent=x.momentum?.toFixed(6)||'—'}catch(e){status.textContent='○ SERVER ERROR'}}setInterval(tick,1000);tick()</script></body></html>"""

if __name__=="__main__":
 import uvicorn
 uvicorn.run(app,host=HOST,port=PORT)
