from __future__ import annotations
import json, time, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from collections import deque
from urllib.parse import urlparse

HOST, PORT = "127.0.0.1", 8765
lock = threading.Lock()
state = {"connected": False, "last_seen": None, "packets": 0, "ticks": 0,
         "price": None, "asset": None, "last_event": "Waiting for browser bridge",
         "recent": deque(maxlen=40), "candles": deque(maxlen=180)}

HTML = r"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><title>AADI X</title>
<style>
:root{--bg:#070a11;--panel:#0d1420;--line:#1c2b3b;--gold:#e5bd67;--teal:#22e6cf;--muted:#8190a5;--red:#ff5877;--green:#37edb1}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at top,#13212b 0,#070a11 55%);color:#eaf2fb;font:13px system-ui,Arial;padding:12px;max-width:820px;margin:auto}
header{display:flex;align-items:center;gap:12px;padding:8px 2px 14px}.logo{width:48px;height:48px;border:1px solid var(--gold);border-radius:15px;display:grid;place-items:center;color:var(--gold);font-size:24px;font-weight:900;box-shadow:0 0 22px #e5bd6722}
h1{font-size:20px;letter-spacing:3px;margin:0;color:var(--gold)}.sub{font-size:10px;color:var(--muted);letter-spacing:2px;margin-top:3px}
.pill{margin-left:auto;border:1px solid var(--line);border-radius:99px;padding:7px 9px;color:var(--muted);font-size:10px}.dot{color:var(--red)}.panel{background:linear-gradient(145deg,#101a27,#0a101a);border:1px solid var(--line);border-radius:17px;padding:14px;margin:9px 0;box-shadow:0 10px 28px #0003}
.kicker{font-size:10px;letter-spacing:2px;color:var(--muted);text-transform:uppercase}.hero{display:grid;grid-template-columns:1fr auto;gap:12px;align-items:center}.sig{font-size:35px;font-weight:900;letter-spacing:2px;color:var(--gold);margin:6px 0}.price{font-size:23px;font-weight:800;color:var(--teal);font-variant-numeric:tabular-nums}.hint{color:var(--muted);font-size:11px;line-height:1.6}.orb{width:78px;height:78px;border-radius:50%;border:1px solid #22e6cf66;display:grid;place-items:center;color:var(--teal);font-size:10px;text-align:center;background:radial-gradient(circle,#22e6cf18,transparent 70%);box-shadow:0 0 28px #22e6cf15}
.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}.metric{background:#0a111b;border:1px solid #1a2838;border-radius:13px;padding:12px}.val{font-size:17px;font-weight:750;margin-top:7px;overflow-wrap:anywhere}.teal{color:var(--teal)}.gold{color:var(--gold)}.bars{display:flex;gap:5px;margin-top:12px;height:35px;align-items:end}.bar{flex:1;border-radius:3px 3px 0 0;background:linear-gradient(0deg,#137c83,#22e6cf);opacity:.75}.chart{height:125px;display:flex;align-items:center;justify-content:center;border:1px dashed #26394a;border-radius:12px;margin-top:10px;color:var(--muted);text-align:center;padding:10px}.row{display:flex;justify-content:space-between;gap:10px;padding:8px 0;border-bottom:1px solid #1a2633}.row:last-child{border:0}.small{font-size:10px;color:var(--muted)}pre{white-space:pre-wrap;overflow-wrap:anywhere;color:#91a5bb;font-size:10px;max-height:170px;overflow:auto}.footer{text-align:center;color:#65758a;font-size:10px;padding:12px}
</style></head><body>
<header><div class="logo">X</div><div><h1>AADI X</h1><div class="sub">NEURAL MARKET SCANNER</div></div><div class="pill"><span id="dot" class="dot">●</span> <span id="conn">BRIDGE OFFLINE</span></div></header>
<section class="panel hero"><div><div class="kicker">Live market intelligence</div><div id="signal" class="sig">WAIT</div><div id="asset" class="hint">Waiting for browser market data</div><div id="reason" class="hint">No synthetic ticks are generated.</div></div><div class="orb" id="orb">SCAN<br>STANDBY</div></section>
<div class="grid">
<section class="metric"><div class="kicker">Live price</div><div id="price" class="val teal">—</div><div id="age" class="small">No tick received</div></section>
<section class="metric"><div class="kicker">Market packets</div><div id="packets" class="val gold">0</div><div id="ticks" class="small">Parsed ticks: 0</div></section>
<section class="metric"><div class="kicker">EMA 9 / EMA 21</div><div id="ema" class="val">— / —</div><div class="small">Calculated from real parsed ticks only</div></section>
<section class="metric"><div class="kicker">RSI / Momentum</div><div id="rsi" class="val">—</div><div id="mom" class="small">Momentum —</div></section>
</div>
<section class="panel"><div class="kicker">Candle structure · real received data</div><div id="candle" class="chart">Waiting for recognizable candle/tick packets.<br>Dashboard will not invent candles.</div><div class="bars" id="bars"></div></section>
<section class="panel"><div class="kicker">Signal diagnostics</div><div class="row"><span>Bridge</span><b id="bridge">Waiting</b></div><div class="row"><span>Last market event</span><b id="event">—</b></div><div class="row"><span>Recognized price format</span><b id="format">Not detected</b></div><div class="row"><span>Signal confidence</span><b id="score">0 / 3</b></div></section>
<section class="panel"><div class="kicker">Recent received messages</div><pre id="recent">No messages received yet.</pre></section>
<div class="footer">ANALYSIS ONLY · MANUAL TRADES ONLY · NO FAKE MARKET DATA</div>
<script>
const $=id=>document.getElementById(id);function fmt(x){return typeof x==='number'&&Number.isFinite(x)?x.toPrecision(8):'—'}
async function update(){try{let r=await fetch('/api/status',{cache:'no-store'}),x=await r.json();let online=x.last_seen&&Date.now()/1000-x.last_seen<8;
$('dot').style.color=online?'var(--green)':'var(--red)';$('conn').textContent=online?'BRIDGE ONLINE':'BRIDGE OFFLINE';$('bridge').textContent=online?'Receiving messages':'Waiting for browser bridge';
$('packets').textContent=x.packets;$('ticks').textContent='Parsed ticks: '+x.ticks;$('price').textContent=fmt(x.price);$('age').textContent=x.last_seen?'Last message '+Math.max(0,Date.now()/1000-x.last_seen).toFixed(1)+'s ago':'No tick received';
$('asset').textContent=x.asset||'Waiting for browser market data';$('event').textContent=x.last_event||'—';$('format').textContent=x.format||'Not detected';$('recent').textContent=(x.recent||[]).slice().reverse().join('\n')||'No messages received yet.';
$('candle').textContent=x.candle?('O '+fmt(x.candle.open)+'   H '+fmt(x.candle.high)+'   L '+fmt(x.candle.low)+'   C '+fmt(x.candle.close)+'\n'+(x.candle.count||0)+' real updates in current candle'):'No valid candle can be drawn until price packets are recognized.';
$('ema').textContent=x.ema_fast&&x.ema_slow?fmt(x.ema_fast)+' / '+fmt(x.ema_slow):'— / —';$('rsi').textContent=x.rsi==null?'—':x.rsi.toFixed(1);$('mom').textContent='Momentum '+(x.momentum==null?'—':fmt(x.momentum));
$('signal').textContent=x.signal||'WAIT';$('signal').style.color=x.signal==='CALL'?'var(--green)':x.signal==='PUT'?'var(--red)':'var(--gold)';$('reason').textContent=x.reason||'Waiting for enough real market data';$('score').textContent=(x.score||0)+' / 3';$('orb').innerHTML=online?'LIVE<br>SCANNING':'SCAN<br>STANDBY';
$('bars').innerHTML='';(x.spark||[]).forEach((v)=>{let b=document.createElement('div');b.className='bar';b.style.height=Math.max(8,Math.min(100,Math.abs(v)*100))+'%';if(v<0)b.style.background='linear-gradient(0deg,#7f263e,#ff5877)';$('bars').appendChild(b)});
}catch(e){$('conn').textContent='SERVER ERROR'}}setInterval(update,1000);update();
</script></body></html>"""

def numeric(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0 and v < 1e9

def inspect_payload(obj):
    """Conservative parsing: only count prices in messages that look like market data."""
    if isinstance(obj, str):
        try: obj = json.loads(obj)
        except Exception: return None
    text = json.dumps(obj, ensure_ascii=False)[:12000].lower()
    if not any(k in text for k in ("quote", "tick", "candle", "price", "asset", "history", "ohlc", "market", "stream")):
        return None
    candidates = []
    def walk(x, path=""):
        if isinstance(x, dict):
            for k,v in x.items():
                kl = str(k).lower()
                if numeric(v) and any(t in kl for t in ("price","close","rate","quote","value","bid","ask","open","high","low")):
                    candidates.append((kl,float(v),path+"."+kl))
                elif isinstance(v,(dict,list)): walk(v,path+"."+kl)
        elif isinstance(x,list):
            for i,v in enumerate(x[:100]): walk(v,path+f"[{i}]")
    walk(obj)
    # Prefer explicit price/quote/close keys over arbitrary numeric values.
    candidates.sort(key=lambda z: (0 if any(k in z[0] for k in ("price","close","quote","rate")) else 1))
    if not candidates: return None
    price = candidates[0][1]
    return {"price":price,"key":candidates[0][2],"event":"market-payload"}

def snapshot():
    with lock:
        out={k:v for k,v in state.items() if k not in ("recent","candles")}
        out["recent"]=list(state["recent"]);out["candles"]=list(state["candles"])
        return out

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path=="/api/status":
            data=json.dumps(snapshot(),separators=(",",":")).encode()
            self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Cache-Control","no-store");self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data);return
        if self.path in ("/","/index.html"):
            data=HTML.encode();self.send_response(200);self.send_header("Content-Type","text/html; charset=utf-8");self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data);return
        self.send_error(404)
    def do_POST(self):
        if self.path!="/bridge": self.send_error(404);return
        try:
            length=min(int(self.headers.get("Content-Length","0")),65536)
            raw=self.rfile.read(length).decode("utf-8","replace")
            payload=json.loads(raw)
            msg=payload.get("data",payload)
            parsed=inspect_payload(msg)
            with lock:
                state["packets"]+=1;state["connected"]=True;state["last_seen"]=time.time()
                summary=json.dumps(msg,ensure_ascii=False)[:240].replace("\n"," ")
                state["recent"].append(time.strftime("%H:%M:%S")+" "+summary)
                state["last_event"]=str(payload.get("event","websocket-message"))[:80]
                if parsed:
                    state["price"]=parsed["price"];state["format"]=parsed["key"];state["ticks"]+=1
                    state["asset"]=payload.get("asset") or state["asset"]
                    now=int(time.time()); c=state.get("candle")
                    if not c or c.get("bucket")!=now//60:
                        c={"bucket":now//60,"time":now,"open":parsed["price"],"high":parsed["price"],"low":parsed["price"],"close":parsed["price"],"count":1}
                    else:
                        c["high"]=max(c["high"],parsed["price"]);c["low"]=min(c["low"],parsed["price"]);c["close"]=parsed["price"];c["count"]+=1
                    state["candle"]=c
                    vals=[q["close"] for q in state["candles"]]
                    if not vals or vals[-1].get("bucket")!=c["bucket"]:
                        state["candles"].append(dict(c))
                    else: state["candles"][-1]=dict(c)
                    closes=[q["close"] for q in state["candles"]]
                    def ema(period):
                        if len(closes)<period:return None
                        a=2/(period+1);e=closes[0]
                        for p in closes[1:]:e=p*a+e*(1-a)
                        return e
                    ef,es=ema(9),ema(21);state["ema_fast"],state["ema_slow"]=ef,es
                    state["momentum"]=closes[-1]-closes[-4] if len(closes)>=4 else None
                    if len(closes)>=15:
                        changes=[closes[i]-closes[i-1] for i in range(max(1,len(closes)-14),len(closes))]
                        gains=sum(max(v,0) for v in changes)/len(changes);loss=sum(max(-v,0) for v in changes)/len(changes)
                        state["rsi"]=100 if loss==0 else 100-100/(1+gains/loss)
                    score=0;signal="WAIT";reason="Need more recognized live prices"
                    if ef is not None and es is not None and state.get("rsi") is not None and state.get("momentum") is not None:
                        score=int(ef>es)+int(state["rsi"]>50)+int(state["momentum"]>0)
                        if score==3:signal="CALL";reason="EMA9>EMA21, RSI>50, momentum positive"
                        elif score==0:signal="PUT";reason="EMA9<EMA21, RSI<50, momentum negative"
                        else:reason="Mixed indicator confluence"
                    state["signal"],state["score"],state["reason"]=signal,score,reason
            out=b'{"ok":true}'
            self.send_response(200);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(out)));self.end_headers();self.wfile.write(out)
        except Exception as e:
            out=json.dumps({"ok":False,"error":str(e)[:120]}).encode()
            self.send_response(400);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(out)));self.end_headers();self.wfile.write(out)
    def log_message(self,*args): pass

if __name__=="__main__":
    print(f"AADI X dashboard: http://127.0.0.1:{PORT}")
    print(f"Browser bridge: POST http://127.0.0.1:{PORT}/bridge")
    print("Waiting for real browser market messages; no demo ticks are generated.")
    ThreadingHTTPServer((HOST,PORT),Handler).serve_forever()
