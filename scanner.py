from __future__ import annotations
import time
from collections import deque
from config import ASSET, EMA_FAST, EMA_SLOW, RSI_PERIOD, MIN_CONFLUENCE
from indicators import signal
from quotex_ws import QuotexSocket

class Scanner:
    def __init__(self):
        self.closes=deque(maxlen=1000)
        self.latest={"asset":ASSET,"signal":"WAIT","score":0,"connected":False,"authenticated":False,"price":None,"timestamp":None}
        self.socket=QuotexSocket(self.handle_event)

    def _price(self,p):
        if isinstance(p,(int,float)): return float(p)
        if isinstance(p,dict):
            for k in ("price","close","value","quote","last"):
                if isinstance(p.get(k),(int,float)): return float(p[k])
        return None

    async def handle_event(self,event,payload):
        self.latest["last_event"]=event
        price=self._price(payload)
        if price is not None: self.latest["price"]=price
        if event in {"history/list/v2","history/list","candles"}:
            rows=payload.get("history",payload) if isinstance(payload,dict) else payload
            if isinstance(rows,list):
                for row in rows:
                    c=self._price(row.get("close") if isinstance(row,dict) else row)
                    if c is not None: self.closes.append(c)
        if event in {"candle-generated","candle","quotes/stream","instruments/update"}:
            c=self._price(payload.get("close") if isinstance(payload,dict) else payload)
            if c is not None:
                if not self.closes or c != self.closes[-1]: self.closes.append(c)
        if len(self.closes)>=RSI_PERIOD+2:
            self.latest.update(signal(self.closes,EMA_FAST,EMA_SLOW,RSI_PERIOD,MIN_CONFLUENCE))
        self.latest["timestamp"]=time.time()
        self.latest["connected"]=self.socket.connected
        self.latest["authenticated"]=self.socket.authenticated

    async def run(self): await self.socket.run()
    def snapshot(self):
        out=dict(self.latest); out["candle_count"]=len(self.closes); return out

scanner=Scanner()
