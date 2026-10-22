// ==UserScript==
// @name         AADI X - Market Message Bridge
// @namespace    aadi-x
// @version      1.1.0
// @description  Forwards likely market-data WebSocket messages to your local AADI X dashboard. Does not read or send login/session credentials.
// @match        https://market-qx.trade/*
// @match        https://*.market-qx.trade/*
// @match        https://qxbroker.com/*
// @match        https://*.qxbroker.com/*
// @match        https://quotex.com/*
// @match        https://*.quotex.com/*
// @grant        GM_xmlhttpRequest
// @connect      127.0.0.1
// @connect      localhost
// @run-at       document-start
// ==/UserScript==
(function(){
  'use strict';
  const ENDPOINT='http://127.0.0.1:8765/bridge';
  const seen=new Set();
  let socketCount=0, forwarded=0;
  function likelyMarket(s){
    if(typeof s!=='string') return false;
    const t=s.toLowerCase();
    if(/session|authorization|token|password|profile|balance|user\/|auth|login/.test(t)) return false;
    return /quote|tick|candle|price|history|ohlc|market|asset|stream/.test(t);
  }
  function send(data){
    let body;
    try { body=JSON.stringify({event:'browser-websocket',url:location.hostname,data:data}); } catch(_){return;}
    if(body.length>60000) return;
    if(typeof GM_xmlhttpRequest==='function'){
      GM_xmlhttpRequest({method:'POST',url:ENDPOINT,data:body,headers:{'Content-Type':'application/json'},timeout:2500,
        onload:()=>{forwarded++},onerror:()=>{}});
    }
  }
  const NativeWS=window.WebSocket;
  function WrappedWebSocket(...args){
    const ws=new NativeWS(...args); socketCount++;
    ws.addEventListener('message',ev=>{
      let s=ev.data;
      if(typeof s!=='string') return; // Binary frames are intentionally not guessed or decoded as prices.
      if(!likelyMarket(s)) return;
      const key=s.slice(0,600);
      if(seen.has(key)) return;
      seen.add(key);if(seen.size>300)seen.clear();
      send(s);
    });
    return ws;
  }
  WrappedWebSocket.prototype=NativeWS.prototype;
  Object.setPrototypeOf(WrappedWebSocket,NativeWS);
  for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED']) WrappedWebSocket[k]=NativeWS[k];
  try { window.WebSocket=WrappedWebSocket; } catch(_){}
  // Lightweight page-side diagnostic; contains no credentials or message payloads.
  setInterval(()=>{try{document.documentElement.dataset.aadiXBridge='installed';document.documentElement.dataset.aadiXSocketCount=String(socketCount);document.documentElement.dataset.aadiXForwarded=String(forwarded)}catch(_){}},2000);
})();
