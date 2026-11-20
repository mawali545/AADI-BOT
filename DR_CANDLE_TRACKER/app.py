#!/usr/bin/env python3
"""DR Candle Tracker: real Market-QX ticks only; no demo feed or order execution."""
import json, math, os, threading, time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT=Path(__file__).resolve().parent
HOST=os.environ.get("DR_HOST","127.0.0.1"); PORT=int(os.environ.get("DR_PORT","8765"))
PERIOD=10; FRESH=5; LOCK=threading.RLock()
TICKS=deque(maxlen=5000); CANDLES=deque(maxlen=600); EVENTS=deque(maxlen=100); ANALYSES=deque(maxlen=100)
STATE={"asset":"WAITING","session":f"{int(time.time())&0xffffff:06X}","source":"No verified browser feed"}

def event(msg): EVENTS.append({"id":str(time.time_ns()),"message":msg,"at":time.time()})
def timestamp(v):
    try: t=float(v)
    except (TypeError,ValueError): return time.time()
    if not math.isfinite(t) or t<=0 or t>time.time()+60 or t<946684800: return time.time()
    return t/1000 if t>10**12 else t

def accept(obj):
    if isinstance(obj,dict) and isinstance(obj.get("data"),dict): obj=obj["data"]
    if not isinstance(obj,dict): raise ValueError("Expected JSON object")
    try: price=float(obj.get("price",obj.get("value")))
    except (TypeError,ValueError): raise ValueError("Missing numeric price") from None
    if not math.isfinite(price) or price<=0: raise ValueError("Price must be positive and finite")
    asset=str(obj.get("asset",obj.get("symbol",""))).strip()[:64]
    if not asset or asset.lower() in ("unknown","unknown asset","none","null"): raise ValueError("Missing verified asset/symbol")
    ts=timestamp(obj.get("timestamp",obj.get("time",time.time()))); now=time.time()
    row={"price":price,"asset":asset,"timestamp":ts,"received":now}; bucket=int(ts//PERIOD)*PERIOD
    with LOCK:
        old=STATE["asset"]; TICKS.append(row); STATE["asset"]=asset
        STATE["source"]=str(obj.get("source","Market-QX browser WebSocket"))[:80]
        if old!=asset: event("LIVE ASSET STREAM: "+asset)
        if not CANDLES or CANDLES[-1]["asset"]!=asset or CANDLES[-1]["bucket"]!=bucket:
            if not CANDLES or bucket>=CANDLES[-1]["bucket"]:
                CANDLES.append({"asset":asset,"bucket":bucket,"open":price,"high":price,"low":price,"close":price,"ticks":1})
        else:
            c=CANDLES[-1]; c["high"]=max(c["high"],price); c["low"]=min(c["low"],price); c["close"]=price; c["ticks"]+=1
    return {"ok":True,"accepted":True,"tick_count":len(TICKS),"candle_count":len(CANDLES)}

def analyze():
    with LOCK:
        asset=STATE["asset"]; rows=[dict(c) for c in CANDLES if c["asset"]==asset]
    bucket=int(time.time()//PERIOD)*PERIOD; closed=[c for c in rows if c["bucket"]<bucket]
    if len(closed)<8: return {"signal":"WAIT","ready":False,"score":0,"candles_used":len(closed),"reason":f"Need 8 completed real 10-second candles; have {len(closed)}","asset":asset}
    rows=closed[-8:]; closes=[c["close"] for c in rows]; votes=[]
    for c in rows:
        span=max(c["high"]-c["low"],1e-12); body=(c["close"]-c["open"])/span
        votes.append(1 if body>.12 else -1 if body<-.12 else 0)
    momentum=(closes[-1]-closes[0])/max(abs(closes[0]),1e-12)
    score=max(-100,min(100,round(sum(votes)*9+max(-1,min(1,momentum*10000))*28)))
    aligned=sum(1 for a,b in zip(closes,closes[1:]) if (b-a)*(closes[-1]-closes[0])>0)
    if score>=42 and aligned>=4: sig,reason="CALL","Completed candle structure and momentum are bullish"
    elif score<=-42 and aligned>=4: sig,reason="PUT","Completed candle structure and momentum are bearish"
    else: sig,reason="WAIT","Recent candles are mixed or lack directional agreement"
    return {"signal":sig,"ready":True,"score":score,"candles_used":8,"asset":asset,"momentum_pct":round(momentum*100,5),"reason":reason,"generated_at":time.time(),"method":"8 completed 10-second candles"}

def status():
    now=time.time()
    with LOCK:
        ticks=list(TICKS); asset=STATE["asset"]; last=ticks[-1] if ticks else None
        fresh=bool(last and last["asset"]==asset and now-last["received"]<=FRESH)
        candles=[dict(c) for c in list(CANDLES)[-30:] if c["asset"]==asset]
        recent=[x["price"] for x in ticks if x["asset"]==asset and now-x["received"]<=60]
        buf=recent[-28:]; direction="UP" if len(recent)>1 and recent[-1]>recent[0] else "DOWN" if len(recent)>1 and recent[-1]<recent[0] else "FLAT" if recent else None
        return {"connected":fresh,"tick_count":len(ticks),"last_tick":last,"asset":asset,"session":STATE["session"],"source":STATE["source"],"candle":candles[-1] if candles else {},"candles":candles,"direction":direction,"latency_ms":round((now-last["received"])*1000,1) if last else None,"data_valid":bool(last),"buffer":buf,"cycle_pct":int(now%PERIOD/PERIOD*100),"events":list(EVENTS)[-8:],"analysis":ANALYSES[-1] if ANALYSES else None}

class Handler(BaseHTTPRequestHandler):
    def send_json(self,obj,code=200):
        b=json.dumps(obj,separators=(",",":"),allow_nan=False).encode(); self.send_response(code)
        self.send_header("Content-Type","application/json; charset=utf-8"); self.send_header("Access-Control-Allow-Origin","*")
        self.send_header("Access-Control-Allow-Headers","Content-Type"); self.send_header("Access-Control-Allow-Methods","GET,POST,OPTIONS")
        self.send_header("Cache-Control","no-store"); self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_OPTIONS(self): self.send_json({"ok":True})
    def do_GET(self):
        p=urlparse(self.path).path
        if p=="/health": self.send_json({"ok":True,"service":"DR Candle Tracker"}); return
        if p=="/api/status": self.send_json(status()); return
        if p=="/api/candles":
            with LOCK: rows=[dict(c) for c in CANDLES]
            self.send_json({"candles":rows}); return
        if p in ("/","/index.html"):
            b=(ROOT/"index.html").read_bytes(); self.send_response(200); self.send_header("Content-Type","text/html; charset=utf-8"); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b); return
        self.send_json({"ok":False,"error":"Not found"},404)
    def do_POST(self):
        p=urlparse(self.path).path
        if p=="/api/analyze":
            result=analyze()
            with LOCK: ANALYSES.append(result)
            self.send_json(result); return
        if p!="/bridge": self.send_json({"ok":False,"error":"Use /bridge or /api/analyze"},404); return
        try:
            n=int(self.headers.get("Content-Length","0"))
            if n<2 or n>65536: raise ValueError("Invalid payload size")
            self.send_json(accept(json.loads(self.rfile.read(n))))
        except (ValueError,TypeError,json.JSONDecodeError) as e: self.send_json({"ok":False,"accepted":False,"error":str(e)},400)
    def log_message(self,*args): pass

if __name__=="__main__":
    print(f"DR CANDLE TRACKER: http://{HOST}:{PORT}")
    print(f"Market-QX receiver: POST http://{HOST}:{PORT}/bridge")
    print("REAL feed only; no sample ticks, synthetic candles, or auto trades.")
    ThreadingHTTPServer((HOST,PORT),Handler).serve_forever()
