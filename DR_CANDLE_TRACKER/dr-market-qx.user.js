// ==UserScript==
// @name         DR Candle Tracker - Market-QX Live Bridge
// @namespace    dr-candle-tracker
// @version      1.0.0
// @description  Forwards named price+asset updates from Market-QX WebSocket to local DR tracker.
// @match        https://market-qx.trade/*
// @match        https://*.qxbroker.com/*
// @match        https://*.quotex.io/*
// @match        https://*.quotex.com/*
// @run-at       document-start
// @grant        unsafeWindow
// @grant        GM_xmlhttpRequest
// @connect      127.0.0.1
// ==/UserScript==
(function(){
'use strict';
const BRIDGE='http://127.0.0.1:8765/bridge',STATUS='http://127.0.0.1:8765/api/status',DASH='http://127.0.0.1:8765/?embed=1';
const page=typeof unsafeWindow!=='undefined'?unsafeWindow:window;
let sockets=0,textFrames=0,binaryFrames=0,jsonFrames=0,forwarded=0,rejected=0,lastInfo='Starting page hook',lastPrice=null,lastAsset='—',root=null;
const recent=new Map();
function send(price,asset,ts){
 const p=Number(price);if(!Number.isFinite(p)||p<=0||typeof asset!=='string'||!asset.trim())return;
 asset=asset.trim().slice(0,64);const now=Date.now(),key=asset+':'+p;
 if(recent.has(key)&&now-recent.get(key)<250)return;recent.set(key,now);
 if(recent.size>400)for(const[k,t]of recent)if(now-t>15000)recent.delete(k);
 GM_xmlhttpRequest({method:'POST',url:BRIDGE,headers:{'Content-Type':'application/json'},data:JSON.stringify({price:p,asset,timestamp:Number(ts)||now,source:'DR Market-QX WebSocket'}),timeout:4000,
 onload:r=>{try{const d=JSON.parse(r.responseText||'{}');if(r.status>=200&&r.status<300&&d.accepted){forwarded++;lastPrice=p;lastAsset=asset;lastInfo='Tick accepted'}else{rejected++;lastInfo='Receiver rejected: '+(d.error||r.status)}}catch(_){rejected++;lastInfo='Bad receiver response'}render()},
 onerror:()=>{lastInfo='Local receiver unavailable';render()},ontimeout:()=>{lastInfo='Local receiver timeout';render()}});
}
// Forward only explicit named price fields paired with a named asset; unknown binary is never guessed.
function walk(x,depth){
 if(depth>8||x==null)return;
 if(Array.isArray(x)){
  if(x.length>=3&&typeof x[0]==='string'&&/^[A-Z0-9_./-]{3,24}$/i.test(x[0])&&Number(x[1])>1000000000&&Number.isFinite(Number(x[2]))&&Number(x[2])>0){send(x[2],x[0],x[1]);return}
  x.forEach(v=>walk(v,depth+1));return;
 }
 if(typeof x!=='object')return;
 const asset=x.asset||x.symbol||x.pair||x.instrument||x.assetName||x.ticker,ts=x.timestamp||x.time||x.ts;
 for(const[k,v]of Object.entries(x))if(/^(price|currentprice|lastprice|closeprice)$/i.test(k)&&asset&&(typeof v==='number'||typeof v==='string')&&String(v).trim()!==''&&Number.isFinite(Number(v))&&Number(v)>0)send(v,String(asset),ts);
 Object.values(x).forEach(v=>{if(v&&typeof v==='object')walk(v,depth+1)});
}
function textFrame(s){
 textFrames++;if(typeof s!=='string')return;
 try{if(s.startsWith('42'))s=s.slice(2);else if(s.charCodeAt(0)===4)s=s.slice(1);const o=JSON.parse(s);jsonFrames++;if(Array.isArray(o)&&typeof o[0]==='string')walk(o[1],0);else walk(o,0);lastInfo='Text frame parsed'}catch(_){lastInfo='Text frame not recognized'}render();
}
async function binaryFrame(data){
 binaryFrames++;try{let b;if(data instanceof Blob)b=await data.arrayBuffer();else if(data instanceof ArrayBuffer)b=data;else if(ArrayBuffer.isView(data))b=data.buffer.slice(data.byteOffset,data.byteOffset+data.byteLength);else throw Error('unsupported');
 let s=new TextDecoder('utf-8',{fatal:true}).decode(new Uint8Array(b));if(s.charCodeAt(0)===4)s=s.slice(1);
 try{const o=JSON.parse(s);jsonFrames++;walk(o,0);lastInfo='Binary UTF-8 JSON parsed'}catch(_){lastInfo='Binary format not decoded; no price guessed'}
 }catch(_){lastInfo='Binary frame not UTF-8 JSON; no price guessed'}render();
}
function attach(ws){sockets++;lastInfo='Market WebSocket created';render();ws.addEventListener('open',()=>{lastInfo='Market WebSocket open';render()});ws.addEventListener('message',e=>typeof e.data==='string'?textFrame(e.data):binaryFrame(e.data));ws.addEventListener('close',()=>{lastInfo='Market WebSocket closed';render()});ws.addEventListener('error',()=>{lastInfo='Market WebSocket error';render()})}
try{const Native=page.WebSocket;if(!Native)lastInfo='Page WebSocket unavailable';else{const Hook=new Proxy(Native,{construct(T,args){const ws=Reflect.construct(T,args,T);attach(ws);return ws}});page.WebSocket=Hook;lastInfo=page.WebSocket===Hook?'Page WebSocket hook installed':'Hook not verified'}}catch(e){lastInfo='Hook error: '+String(e).slice(0,80)}
function ui(){
 if(root||!document.documentElement)return;
 const host=document.createElement('div');host.id='dr-ct-host';Object.assign(host.style,{position:'fixed',inset:0,zIndex:2147483646,pointerEvents:'none'});document.documentElement.appendChild(host);root=host.attachShadow({mode:'open'});
 root.innerHTML=`<style>
 *{box-sizing:border-box}#bubble{pointer-events:auto;position:fixed;right:16px;top:47%;width:66px;height:66px;border-radius:50%;border:2px solid #66ff9e;background:radial-gradient(circle,#1a9a56,#06331f 65%,#03140c);box-shadow:0 0 12px #39ff8a,0 0 34px #24ff7755,inset 0 0 15px #a4ffc477;color:#dffff0;font:900 20px monospace;animation:pulse 2s infinite} @keyframes pulse{50%{box-shadow:0 0 20px #39ff8a,0 0 48px #24ff7780}}
 #mini{pointer-events:auto;display:none;position:fixed;right:91px;top:calc(47% + 2px);width:205px;padding:11px;border:1px solid #49f58b;border-radius:12px;background:linear-gradient(145deg,#071a11,#020806);box-shadow:0 0 18px #24ff7755;color:#caffdc;font:11px monospace;cursor:pointer}#mini.show{display:block}#title{font-weight:900;letter-spacing:1px;color:#8bffb4}#state{font-size:16px;font-weight:900;margin:8px 0}#detail{color:#85b99a;font-size:10px;line-height:1.5}#lock{color:#ffdc8b;font-weight:bold;margin-top:7px}#diag{color:#74a98a;font-size:9px;margin-top:7px;line-height:1.5}
 #shade{pointer-events:auto;display:none;position:fixed;inset:0;background:#000d;z-index:2147483647}#shade.show{display:block}#frame{width:100%;height:100%;border:0;background:#020806}@media(max-width:390px){#bubble{right:10px;width:60px;height:60px}#mini{right:80px;width:185px}}
 </style><button id="bubble">DR</button><div id="mini"><div id="title">NEXT / QUANT SIGNALS</div><div id="state">WAITING FOR FEED</div><div id="detail">Waiting for verified Market-QX ticks</div><div id="lock">◈ NODE LOCKED</div><div id="diag"></div></div><div id="shade"><iframe id="frame" title="DR Candle Tracker"></iframe></div>`;
 const $=id=>root.getElementById(id);
 $('bubble').onclick=()=>{$('mini').classList.toggle('show');poll()};
 $('mini').onclick=()=>{if(!$('frame').getAttribute('src'))$('frame').src=DASH;$('shade').classList.add('show');$('mini').classList.remove('show')};
 $('shade').onclick=e=>{if(e.target===$('shade'))$('shade').classList.remove('show')};
 window.addEventListener('message',e=>{if(e.data&&e.data.type==='DR_CANDLE_TRACKER_CLOSE')$('shade').classList.remove('show')});
}
function render(){ui();if(!root)return;root.getElementById('diag').textContent='WS '+sockets+' · JSON '+jsonFrames+' · accepted '+forwarded+' · rejected '+rejected;root.getElementById('detail').textContent=lastInfo+' · '+lastAsset+' · '+(lastPrice??'—')}
function poll(){GM_xmlhttpRequest({method:'GET',url:STATUS,timeout:2500,onload:r=>{try{const d=JSON.parse(r.responseText);if(!root)return;root.getElementById('state').textContent=d.connected?'LIVE FEED LINKED':'WAITING FOR FEED';root.getElementById('detail').textContent=d.connected?d.asset+' · '+d.tick_count+' real ticks · '+Math.round(d.latency_ms||0)+'ms':'No recent verified Market-QX ticks';root.getElementById('lock').textContent=d.connected?'◈ TRACKING · ANALYZE IN PANEL':'◈ NODE LOCKED'}catch(_){root.getElementById('state').textContent='LOCAL ENGINE UNAVAILABLE'}},onerror:()=>{if(root)root.getElementById('state').textContent='LOCAL ENGINE UNAVAILABLE'}})}
setInterval(()=>{ui();render()},1000);console.log('[DR Candle Tracker] '+lastInfo);
})();
