(function () {
'use strict';
if (window.__DR_CANDLE_TRACKER__) return;
window.__DR_CANDLE_TRACKER__ = true;
var NativeWS=window.WebSocket,sockets=0,frames=0,jsonFrames=0,sent=0,rejected=0,activeAsset='',activePeriod=10,pendingBinaryEvent='',logs=[],host,root;
var nativePublicKey=null,aesKey=null,wrappedAesKey=null,sendQueue=Promise.resolve();
var lastState={ticks:0,asset:'WAITING FOR QX',price:null,lastTickAt:0,connected:false,signal:'WAIT',wins:0,losses:0};
var backend={memoryStream:[],cycle:0,node:'ARMED',cipher:'LOCKED',fingerprint:'',latency:null,scanState:'WAITING'};
var qxHttpRttMs=null,qxHttpRttAt=0,qxHttpRttSamples=[],qxHeartbeatPingAt=0,qxHeartbeatResponseMs=null,qxHeartbeatAt=0,qxRttProbeBusy=false;
function log(m){logs.push('['+new Date().toISOString()+'] '+String(m));if(logs.length>120)logs.shift();}
function assetOf(o){if(!o||typeof o!=='object')return'';for(var k of ['asset','symbol','pair','instrument','assetName','ticker','active'])if(typeof o[k]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(o[k].trim()))return o[k].trim();return'';}
function num(o,keys){if(!o||typeof o!=='object')return null;for(var k of keys){var v=o[k];if((typeof v==='number'||(typeof v==='string'&&v.trim()))&&Number.isFinite(Number(v)))return Number(v);}return null;}
function sourceTime(v){if(typeof v==='string'&&!/^\d+(\.\d+)?$/.test(v)){var p=Date.parse(v);return Number.isFinite(p)&&p>0?p:null;}var n=Number(v);if(!Number.isFinite(n)||n<=0)return null;if(n<1e11)n*=1000;return Number.isSafeInteger(Math.round(n))?Math.round(n):null;}
function b64(bytes){var s='';new Uint8Array(bytes).forEach(function(x){s+=String.fromCharCode(x);});return btoa(s);}
async function makeEnvelope(payload){if(!nativePublicKey||!aesKey||!wrappedAesKey)throw new Error('Android secure key not ready');var iv=crypto.getRandomValues(new Uint8Array(12));var plain=new TextEncoder().encode(JSON.stringify(payload));var encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,tagLength:128},aesKey,plain);return{wrappedKey:wrappedAesKey,iv:b64(iv),ciphertext:b64(encrypted)};}
function postPayload(payload,fingerprint,frameBytes){if(!/^[a-f0-9]{64}$/i.test(fingerprint||'')||!Number.isInteger(frameBytes)||frameBytes<1||frameBytes>1048576){rejected++;return;}payload.frameFingerprint=fingerprint;payload.frameBytes=frameBytes;sendQueue=sendQueue.then(async function(){var envelope=await makeEnvelope(payload);if(!window.DRSecure||typeof window.DRSecure.postMessage!=='function')throw new Error('Origin-restricted Android channel unavailable');window.DRSecure.postMessage(JSON.stringify(envelope));sent++;}).catch(function(e){rejected++;log('SECURE BRIDGE SEND ERROR: '+(e&&e.message||e));});}
function postTelemetry(metrics){if(!nativePublicKey||!aesKey||!wrappedAesKey||!window.DRSecure||typeof window.DRSecure.postMessage!=='function')return;sendQueue=sendQueue.then(async function(){var envelope=await makeEnvelope(Object.assign({type:'telemetry'},metrics||{}));window.DRSecure.postMessage(JSON.stringify(envelope));}).catch(function(e){log('SECURE TELEMETRY SEND ERROR: '+(e&&e.message||e));});}
function directionOf(value){if(typeof value==='number'&&Number.isFinite(value)&&Number.isInteger(value)&&value>=-1&&value<=1)return value;if(typeof value==='string'){var s=value.trim().toLowerCase();if(s==='1'||s==='up'||s==='buy'||s==='bull'||s==='call')return 1;if(s==='-1'||s==='down'||s==='sell'||s==='bear'||s==='put')return -1;if(s==='0'||s==='flat'||s==='neutral')return 0;}return null;}
function send(price,asset,timestamp,tickDirection,fingerprint,frameBytes,sourceEvent){price=Number(price);asset=String(asset||activeAsset||'').trim();timestamp=sourceTime(timestamp);tickDirection=directionOf(tickDirection);if(!Number.isFinite(price)||price<=0||price>=1e12||!asset||!/^[A-Z0-9_./-]{3,32}$/i.test(asset)||!timestamp)return;postPayload({type:'tick',source:String(sourceEvent||'unclassified'),price:price,asset:asset,timestamp:timestamp,tickDirection:tickDirection,period:Number(activePeriod)||10,heartbeatSendMs:qxHeartbeatResponseMs,heartbeatAt:qxHeartbeatAt,httpResponseMs:qxHttpRttMs,httpResponseAt:qxHttpRttAt},fingerprint,frameBytes);}
function sendHistory(data,fp,size,sourceEvent){if(!data||typeof data!=='object')return;var asset=assetOf(data)||activeAsset,period=num(data,['period','timePeriod'])||activePeriod,rows=Array.isArray(data.candles)?data.candles:(Array.isArray(data.history)?data.history:(Array.isArray(data.data)?data.data:null));if(!asset||!rows)return;var candles=[];rows.slice(-900).forEach(function(c){if(!Array.isArray(c)||c.length<5)return;var t=sourceTime(c[0]),o=Number(c[1]),cl=Number(c[2]),h=Number(c[3]),l=Number(c[4]);if(t&&[o,cl,h,l].every(Number.isFinite)&&o>0&&cl>0&&h>0&&l>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl))candles.push({time:t,open:o,close:cl,high:h,low:l});});if(candles.length)postPayload({type:'history',source:String(sourceEvent||'history/list/v2'),asset:asset,period:Number(period)||10,candles:candles},fp,size);}
function walk(v,inherited,depth,fp,size,sourceEvent){if(depth>10||v==null)return;if(Array.isArray(v)){if(v.length>=3&&typeof v[0]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(v[0])&&sourceTime(v[1])&&Number(v[2])>0){send(v[2],v[0],v[1],v.length>=4?v[3]:null,fp,size,sourceEvent);return;}v.forEach(function(x){walk(x,inherited,depth+1,fp,size,sourceEvent);});return;}if(typeof v!=='object')return;var a=assetOf(v)||inherited||activeAsset,p=num(v,['price','currentPrice','lastPrice','closePrice','quotePrice','value']),t=null;for(var k of ['timestamp','time','ts','created_at','at','serverTime'])if(v[k]!=null){t=sourceTime(v[k]);if(t)break;}if(p!==null&&a&&t){send(p,a,t,num(v,['tickDirection','direction','dir'])!==null?num(v,['tickDirection','direction','dir']):(v.side||null),fp,size,sourceEvent);return;}Object.keys(v).forEach(function(k){if(v[k]&&typeof v[k]==='object')walk(v[k],a,depth+1,fp,size,sourceEvent);});}
function handleData(o,eventName,fp,size){if(eventName==='history/list/v2'||eventName==='history/load'||eventName==='chart_notification/get'||eventName==='loadHistoryPeriod'){sendHistory(o,fp,size,eventName);return;}if(eventName==='candle-generated'&&o&&typeof o==='object'){var ca=assetOf(o)||activeAsset,cp=num(o,['price','close']);var ct=null;for(var k of ['timestamp','time','ts'])if(o[k]!=null){ct=sourceTime(o[k]);if(ct)break;}if(cp!==null&&ca&&ct){send(cp,ca,ct,null,fp,size,eventName);return;}}walk(o,activeAsset,0,fp,size,eventName);}
async function parseFrame(raw){frames++;if(typeof raw!=='string')return;var text=raw.trim();if(text==='2'||text.startsWith('2')){qxHeartbeatPingAt=performance.now();qxHeartbeatResponseMs=null;qxHeartbeatAt=0;log('QX ENGINE.IO SERVER PING');return;}if(text==='3'||text.startsWith('3')){log('QX ENGINE.IO PONG RECEIVED');return;}if(!window.crypto||!crypto.subtle)return;var bytes=new TextEncoder().encode(raw),digest=await crypto.subtle.digest('SHA-256',bytes),fp=Array.from(new Uint8Array(digest)).map(x=>x.toString(16).padStart(2,'0')).join('');try{if(text.startsWith('451-')){var head=JSON.parse(text.slice(4));if(Array.isArray(head)&&typeof head[0]==='string'){pendingBinaryEvent=head[0];log('QX BINARY EVENT '+pendingBinaryEvent);}return;}var eventName='';if(text.charCodeAt(0)===4){text=text.slice(1);eventName=pendingBinaryEvent;pendingBinaryEvent='';}else if(text.startsWith('42'))text=text.slice(2);var o=JSON.parse(text);jsonFrames++;if(Array.isArray(o)&&typeof o[0]==='string'){eventName=o[0];var data=o[1],a=assetOf(data);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|candle|market)/i.test(eventName)){activeAsset=a;log('MARKET EVENT '+eventName+' · '+a);}if(eventName==='quotes/stream'||eventName==='history/list/v2'||eventName==='history/load'||eventName==='chart_notification/get'||eventName==='candle-generated'||eventName==='loadHistoryPeriod')handleData(data,eventName,fp,bytes.length);else log('IGNORED NON-MARKET QX EVENT '+eventName);}else handleData(o,eventName,fp,bytes.length);backend.fingerprint=fp;}catch(e){log('QX FRAME PARSE '+(e&&e.message||e));}}
try{if(NativeWS)window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){var ws=Reflect.construct(T,args,T);sockets++;try{var old=ws.send;ws.send=function(d){try{if(typeof d==='string'){var heartbeatText=d.trim();if((heartbeatText==='3'||heartbeatText.startsWith('3'))&&qxHeartbeatPingAt>0){qxHeartbeatResponseMs=Math.max(0,performance.now()-qxHeartbeatPingAt);qxHeartbeatAt=Date.now();qxHeartbeatPingAt=0;log('QX ENGINE.IO PONG SEND (LOCAL) · '+qxHeartbeatResponseMs.toFixed(1)+' ms local response');postTelemetry({heartbeatSendMs:qxHeartbeatResponseMs,heartbeatAt:qxHeartbeatAt});}var q=d.startsWith('42')?d.slice(2):d,o=JSON.parse(q);if(Array.isArray(o)){var a=assetOf(o[1]);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|history)/i.test(o[0])){activeAsset=a;/* Keep the tracker signal clock fixed at the requested 10-second period. */}}}}catch(_){}return old.call(this,d);};}catch(_){}ws.addEventListener('open',()=>log('QX WEBSOCKET OPEN'));ws.addEventListener('message',e=>{if(typeof e.data==='string')parseFrame(e.data);else if(e.data instanceof Blob)e.data.text().then(parseFrame).catch(()=>{});else if(e.data instanceof ArrayBuffer){try{parseFrame(new TextDecoder().decode(e.data));}catch(_){}}});ws.addEventListener('close',()=>log('QX WEBSOCKET CLOSED'));ws.addEventListener('error',()=>log('QX WEBSOCKET ERROR'));return ws;}});}catch(e){log('WEBSOCKET OBSERVER ERROR '+(e&&e.message||e));}
var css=[
'*{box-sizing:border-box}',
'#strip{position:fixed;z-index:2147483647;top:0;left:4px;right:4px;height:88px;background:rgba(3,13,8,.94);border:1px solid rgba(91,255,151,.62);border-radius:9px;color:#e8fff0;padding:6px 10px;font:12px/1.2 Arial,sans-serif;pointer-events:none;box-shadow:0 2px 14px rgba(0,0,0,.35);backdrop-filter:blur(5px)}',
'#ticker{height:27px;display:grid;grid-template-columns:auto auto minmax(0,1fr);align-items:center;column-gap:7px;border-bottom:1px solid rgba(100,255,160,.22);padding:0 8px 5px 0;min-width:0;max-width:100%;overflow:hidden}',
'#brand{font-size:10px;font-weight:800;letter-spacing:.8px;color:#a8ffc5;white-space:nowrap}',
'#market{font-size:clamp(8px,3.6vw,13px);font-weight:800;color:#fff;text-align:right;white-space:nowrap;overflow:hidden;text-overflow:clip;min-width:0;max-width:100%;transform:none;letter-spacing:-.25px;font-variant-numeric:tabular-nums}',
'#status{font-size:9px;font-weight:800;color:#8cffb4;white-space:nowrap}',
'#signals{height:35px;display:grid;grid-template-columns:repeat(3,1fr);gap:7px;align-items:center;padding:5px 0}',
'.sig{height:25px;border:1px solid #355a43;border-radius:5px;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:900;color:#9eb9a7;background:rgba(7,23,13,.7);transition:opacity .35s ease}',
'.sig.active.wait{color:#ffe09a;border-color:#ffe09a;background:rgba(90,63,8,.35)}',
'.sig.active.call{color:#72ffa7;border-color:#72ffa7;background:rgba(12,82,40,.38)}',
'.sig.active.put{color:#ff8098;border-color:#ff8098;background:rgba(93,14,31,.38)}',
'#stats{height:18px;display:flex;align-items:center;justify-content:center;gap:24px;font-size:10px;font-weight:800;letter-spacing:.6px;color:#c4d7ca}',
'#stats b{font-size:12px;margin-left:4px}#wins{color:#71fba2}#losses{color:#ff8296}',
'@media(max-height:620px){#strip{height:84px;padding:5px 8px}#signals{height:31px;padding:3px 0}.sig{height:23px}#market{font-size:12px}}'
].join('');
host=document.createElement('div');host.id='dr-candle-tracker-host';Object.assign(host.style,{position:'fixed',inset:'0',zIndex:'2147483646',pointerEvents:'none'});var mount=document.documentElement||document.body;if(mount)mount.appendChild(host);else document.addEventListener('DOMContentLoaded',function(){var t=document.documentElement||document.body;if(t&&!host.isConnected)t.appendChild(host);},{once:true});root=host.attachShadow({mode:'open'});
root.innerHTML='<style>'+css+
'#quant-hud{position:fixed;right:8px;bottom:10px;width:188px;padding:8px 9px;background:rgba(2,12,8,.94);border:1px solid rgba(87,255,145,.72);border-radius:7px;color:#baffcf;font:9px/1.35 monospace;letter-spacing:.35px;box-shadow:0 0 15px rgba(40,255,115,.13);pointer-events:none}'+
'#quant-hud .qh-title{font-weight:900;color:#83ffad;font-size:10px;letter-spacing:1px;border-bottom:1px solid rgba(100,255,160,.28);padding-bottom:4px;margin-bottom:5px}'+
'#quant-hud .qh-row{display:flex;justify-content:space-between;gap:6px;white-space:nowrap}'+
'#quant-hud .qh-value{color:#effff3;font-weight:800;text-align:right}'+
'#quant-hud .qh-ok{color:#78ffa4}#quant-hud .qh-warn{color:#ffd58a}'+
'#quant-hud .qh-bar{height:4px;background:#183323;margin:5px 0 6px;border-radius:4px;overflow:hidden}'+
'#quant-hud .qh-fill{height:100%;width:0;background:#65ff9b;box-shadow:0 0 6px #65ff9b}'+
'#quant-hud .qh-hist{height:24px;display:flex;align-items:flex-end;gap:4px;padding:3px 1px;border-bottom:1px solid rgba(100,255,160,.2)}'+
'#quant-hud .qh-hist i{display:block;flex:1;min-width:0;border-radius:2px 2px 0 0;opacity:.9}'+
'#quant-hud .qh-section{color:#78ffa4;font-weight:900;margin-top:5px;font-size:8px;letter-spacing:.8px}'+
'#quant-hud .qh-check{margin-top:5px;padding-top:4px;border-top:1px solid rgba(100,255,160,.2)}'+
'#quant-hud .qh-check div{display:flex;justify-content:space-between;font-size:8px;line-height:1.45;color:#86b997}'+
'#quant-hud .qh-check b{color:#72ffa7;font-weight:900}'+
'#quant-hud .qh-log{color:#82b995;font-size:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}'+
'#quant-hud{width:156px;max-width:calc(100vw - 20px);max-height:44vh;bottom:10px;right:8px;padding:6px 8px;overflow:hidden;z-index:2147483647;pointer-events:auto}'+
'#quant-hud.collapsed{width:156px;height:34px;max-height:34px;padding:4px 7px}'+
'#quant-hud.expanded{width:min(272px,calc(100vw - 20px));max-height:44vh}'+
'#quant-hud .qh-toggle{width:100%;min-height:24px;display:flex;align-items:center;justify-content:space-between;gap:6px;padding:0 1px;border:0;background:transparent;color:#83ffad;font:900 10px/1.2 monospace;letter-spacing:.65px;text-align:left;pointer-events:auto;touch-action:none;cursor:move;user-select:none}'+
'#quant-hud .qh-toggle span:last-child{font-size:12px;color:#d2ffe0}'+
'#quant-hud .qh-body{max-height:calc(44vh - 38px);overflow-y:auto;overscroll-behavior:contain;scrollbar-width:thin;pointer-events:auto;padding-top:4px}'+
'#quant-hud.collapsed .qh-body{display:none}'+
'#quant-hud .qh-row{gap:4px;font-size:9px;min-width:0}'+
'#quant-hud .qh-row span{min-width:0;overflow:hidden;text-overflow:ellipsis}'+
'#quant-hud .qh-value{max-width:58%;overflow:hidden;text-overflow:ellipsis}'+
'#quant-hud .qh-hist{height:21px}'+
'#quant-hud .qh-check div{font-size:8px}'+
'@media(max-width:380px){#quant-hud.expanded{width:calc(100vw - 16px);right:8px}#quant-hud .qh-title{font-size:9px}}'+
'</style><section id="strip" aria-label="AADI live market ticker"><div id="ticker"><span id="brand">AADI · LIVE TICKER</span><span id="status">WAITING FOR QX</span><span id="market">—</span></div><div id="signals"><span id="wait" class="sig active wait">WAIT</span><span id="call" class="sig">CALL</span><span id="put" class="sig">PUT</span></div><div id="stats"><span id="wins">WINS <b>0</b></span><span id="losses">LOSSES <b>0</b></span></div></section>'+
'<aside id="quant-hud" class="collapsed" aria-label="Quant core connection telemetry"><button type="button" id="qh-toggle" class="qh-toggle" aria-expanded="false"><span>QUANT CORE · LIVE</span><span id="qh-chevron">＋</span></button><div class="qh-body"><div class="qh-row"><span>CRYPTO BRIDGE</span><b id="qh-cipher" class="qh-value qh-warn">HANDSHAKE PENDING</b></div><div class="qh-row"><span>BREACH NODE</span><b id="qh-node" class="qh-value qh-warn">STANDBY</b></div><div class="qh-row"><span>FEED AGE</span><b id="qh-latency" class="qh-value">-- ms</b></div><div class="qh-row"><span>QX HTTP RTT</span><b id="qh-rtt" class="qh-value qh-warn">N/A</b></div><div class="qh-row"><span>ENGINE.IO PONG SEND (LOCAL)</span><b id="qh-heartbeat" class="qh-value qh-warn">N/A</b></div><div class="qh-row"><span>LIVE FEED</span><b id="qh-live" class="qh-value qh-warn">WAITING</b></div><div class="qh-row"><span>0x25 VECTOR</span><b id="qh-vector" class="qh-value">0.00</b></div><div class="qh-row"><span>HISTOGRAM</span><b id="qh-hist-value" class="qh-value">0.00</b></div><div class="qh-hist" id="qh-hist"></div><div class="qh-row"><span>OHLC MEMORY</span><b id="qh-ohlc" class="qh-value">--</b></div><div class="qh-row"><span>OHLC MEMORY WINDOW</span><b id="qh-cycle" class="qh-value">0/12</b></div><div class="qh-bar"><div id="qh-fill" class="qh-fill"></div></div><div class="qh-row"><span>NODE HASH</span><b id="qh-hash" class="qh-value">--</b></div><div class="qh-row"><span>SCAN RESULT</span><b id="qh-reason" class="qh-value">WAITING</b></div><div class="qh-check" id="qh-check"></div><div id="qh-log" class="qh-log">Awaiting authenticated QX feed…</div></div></aside>';
function el(id){return root.getElementById(id);}
var hud=el('quant-hud'),hudToggle=el('qh-toggle');
var dragState=null,dragMoved=false,suppressNextHudClick=false;
function placeHud(left,top){var rect=hud.getBoundingClientRect(),maxLeft=Math.max(0,window.innerWidth-rect.width),maxTop=Math.max(0,window.innerHeight-rect.height);left=Math.max(0,Math.min(maxLeft,left));top=Math.max(0,Math.min(maxTop,top));hud.style.left=Math.round(left)+'px';hud.style.top=Math.round(top)+'px';hud.style.right='auto';hud.style.bottom='auto';}
function restoreHudPosition(){try{var p=JSON.parse(window.localStorage.getItem('dr-quant-hud-position')||'null');if(p&&Number.isFinite(p.left)&&Number.isFinite(p.top))placeHud(p.left,p.top);}catch(_){}}
hudToggle.addEventListener('pointerdown',function(ev){if(ev.pointerType==='mouse'&&ev.button!==0)return;var r=hud.getBoundingClientRect();dragState={id:ev.pointerId,x:ev.clientX,y:ev.clientY,left:r.left,top:r.top};dragMoved=false;try{hudToggle.setPointerCapture(ev.pointerId);}catch(_){}});
hudToggle.addEventListener('pointermove',function(ev){if(!dragState||ev.pointerId!==dragState.id)return;var dx=ev.clientX-dragState.x,dy=ev.clientY-dragState.y;if(Math.abs(dx)>3||Math.abs(dy)>3)dragMoved=true;if(dragMoved){placeHud(dragState.left+dx,dragState.top+dy);ev.preventDefault();}});
function finishHudDrag(ev){if(!dragState||ev.pointerId!==dragState.id)return;if(dragMoved){var r=hud.getBoundingClientRect();try{window.localStorage.setItem('dr-quant-hud-position',JSON.stringify({left:r.left,top:r.top}));}catch(_){}suppressNextHudClick=true;}dragState=null;}
hudToggle.addEventListener('pointerup',finishHudDrag);hudToggle.addEventListener('pointercancel',finishHudDrag);
hudToggle.addEventListener('click',function(){if(suppressNextHudClick){suppressNextHudClick=false;return;}var expanded=hud.classList.toggle('expanded');hud.classList.toggle('collapsed',!expanded);hudToggle.setAttribute('aria-expanded',expanded?'true':'false');el('qh-chevron').textContent=expanded?'−':'＋';});
restoreHudPosition();window.addEventListener('resize',function(){var r=hud.getBoundingClientRect();placeHud(r.left,r.top);},{passive:true});
function reserveTopSpace(){
 if(!document.body)return;
 var height=92;
 var oldSpace=document.getElementById('dr-ticker-reserved-space');
 if(oldSpace)oldSpace.remove();
 // Use the page's own top padding instead of a full-width flex/grid child.
 // A 100%-width spacer can push QX's chart sideways when its body is a flex row.
 document.body.style.setProperty('padding-top',height+'px','important');
 document.body.style.setProperty('box-sizing','border-box','important');
 document.documentElement.style.setProperty('overflow-x','hidden','important');
 document.body.style.setProperty('overflow-x','hidden','important');
}
function fitMarketText(){
 var market=el('market');if(!market)return;
 market.style.fontSize='';
 var size=13;
 while(size>7&&market.scrollWidth>market.clientWidth){size-=.5;market.style.fontSize=size+'px';}
}
window.addEventListener('resize',function(){reserveTopSpace();fitMarketText();},{passive:true});
var layoutObserver=new MutationObserver(function(){reserveTopSpace();});
function startLayoutReservation(){
 reserveTopSpace();
 if(document.body)layoutObserver.observe(document.body,{childList:true});
}
if(document.body)startLayoutReservation();
else document.addEventListener('DOMContentLoaded',startLayoutReservation,{once:true});
var lastSignalCount=-1,fadeTimer=null;
function renderTelemetry(s,age,live){
 var cipher=!!s.secureBridge,hasMemory=Number(s.assetCandleCount)>0;
 el('qh-cipher').textContent=cipher?'VERIFIED':'HANDSHAKE PENDING';
 el('qh-cipher').className='qh-value '+(cipher?'qh-ok':'qh-warn');
 el('qh-node').textContent=live?'QX NODE LIVE':'NODE STANDBY';
 el('qh-node').className='qh-value '+(live?'qh-ok':'qh-warn');
 el('qh-latency').textContent=Number.isFinite(age)&&age>=0&&age<60000?Math.round(age)+' ms':'-- ms';
 var rttValue=Number(qxHttpRttMs!=null?qxHttpRttMs:(s.qxHttpResponseMs!=null?s.qxHttpResponseMs:s.qxHttpRttMs)),rttAt=Number(qxHttpRttAt||s.qxHttpResponseAt||s.qxHttpRttAt);var rttFresh=rttAt>0&&Date.now()-rttAt<90000&&Number.isFinite(rttValue);el('qh-rtt').textContent=rttFresh?Math.round(rttValue)+' ms':'N/A';el('qh-rtt').className='qh-value '+(rttFresh?'qh-ok':'qh-warn');el('qh-rtt').title='Same-origin QX HTTP request/response time; not WebSocket RTT or TCP ACK RTT.';var hbValue=Number(qxHeartbeatResponseMs!=null?qxHeartbeatResponseMs:(s.qxHeartbeatSendMs!=null?s.qxHeartbeatSendMs:s.qxHeartbeatResponseMs)),hbAt=Number(qxHeartbeatAt||s.qxHeartbeatAt);var hbFresh=hbAt>0&&Date.now()-hbAt<90000&&Number.isFinite(hbValue);el('qh-heartbeat').textContent=hbFresh?hbValue.toFixed(1)+' ms':'N/A';el('qh-heartbeat').className='qh-value '+(hbFresh?'qh-ok':'qh-warn');el('qh-heartbeat').title='Local time from server ping receipt to client pong send; not server-acknowledged RTT.';
 el('qh-live').textContent=live?'LIVE':'WAITING';
 el('qh-live').className='qh-value '+(live?'qh-ok':'qh-warn');
 var vector=Number(s.vector)||0,hist=Number(s.histogram)||0;
 el('qh-vector').textContent=(vector>=0?'+':'')+vector.toFixed(2);
 el('qh-hist-value').textContent=(hist>=0?'+':'')+hist.toFixed(2);
 var bars=Array.isArray(s.histogramBars)?s.histogramBars:[];
 el('qh-hist').innerHTML=bars.map(function(v){var n=Math.max(-1,Math.min(1,Number(v)||0));var h=Math.max(2,Math.round(Math.abs(n)*20));return '<i style="height:'+h+'px;background:'+(n>=0?'#65ff9b':'#ff6d83')+'"></i>';}).join('');
 function fmt(v){return v==null||!Number.isFinite(Number(v))?'--':Number(v).toFixed(5);}
 el('qh-ohlc').textContent=fmt(s.open)+' / '+fmt(s.high)+' / '+fmt(s.low)+' / '+fmt(s.close);
 var memoryCount=Math.max(0,Number(s.assetCandleCount)||0);
 var cycle=Math.min(100,Math.round(memoryCount/12*100));
 el('qh-cycle').textContent=Math.min(12,memoryCount)+'/12';
 el('qh-fill').style.width=cycle+'%';
 var fingerprint=String(s.fingerprint||'');
 el('qh-hash').textContent=fingerprint?fingerprint.slice(0,12).toUpperCase()+'…':'--';var reason=String(s.signalReason||'Waiting for QX data');el('qh-reason').textContent=reason.length>44?reason.slice(0,43)+'…':reason;el('qh-reason').title=reason;
 var checks=[
  ['QX SOCKET OBSERVER',live],
  ['CRYPTO HANDSHAKE',cipher],
  ['OHLC MEMORY',hasMemory],
  ['HISTOGRAM + VECTOR',Number(s.assetCandleCount)>=8],
  ['SIGNAL ENGINE',live&&Number(s.assetCandleCount)>=8]
 ];
 el('qh-check').innerHTML=checks.map(function(item){return '<div><span>'+item[0]+'</span><b>'+(item[1]?'✓':'…')+'</b></div>';}).join('');
 var jitter=s.tickJitterMs==null?'--':Math.round(Number(s.tickJitterMs))+'ms',gaps=Number(s.tickGapCount)||0,ooo=Number(s.outOfOrderCount)||0,duplicates=Number(s.duplicateTimestampCount)||0,historyBad=Number(s.historyInvalid)||0,historyDup=Number(s.historyDuplicateBuckets)||0,historyGaps=Number(s.historyGapCount)||0;el('qh-log').textContent=!cipher?'[LOCK] secure bridge initializing':
  (!live?'[WAIT] scanning QX websocket / reconnecting':
   (hasMemory?'[OK] OHLC memory · histogram/vector scan':'[OK] live feed · loading OHLC memory')+' · jitter '+jitter+' · gaps '+gaps+' · out-of-order '+ooo+' · duplicate '+duplicates+' · OHLC invalid '+historyBad+' · OHLC duplicate '+historyDup+' · OHLC gaps '+historyGaps);
}
function render(s){var receivedAt=Number(s.lastTickReceivedAt)||Number(s.lastTickAt)||0;var age=receivedAt?Date.now()-receivedAt:Infinity,live=!!s.lastTickAt&&age>=-30000&&age<8000;el('status').textContent=live?'● QX WS LIVE':(s.ticks?'● RECONNECTING':'WAITING FOR QX');el('status').style.color=live?'#7cffaa':'#ffd58a';el('market').textContent=(live?(s.asset||'MARKET'):(s.asset||'WAITING FOR QX'))+'  '+(s.price==null?'—':String(s.price));fitMarketText();var sig=live&&['CALL','PUT'].includes(s.signal)?s.signal:'WAIT';var count=Number(s.signalCount)||0,prob=Math.round(Number(s.signalProbability)||0);['wait','call','put'].forEach(function(id){el(id).className='sig'+(id.toUpperCase()===sig?' active '+id:'');el(id).textContent=id==='wait'?'WAIT':(id.toUpperCase()===sig&&prob>0?id.toUpperCase()+' '+prob+'/100':id.toUpperCase());});if(sig==='WAIT'){lastSignalCount=count;el('call').style.opacity='1';el('put').style.opacity='1';if(fadeTimer!==null){clearTimeout(fadeTimer);fadeTimer=null;}}else if(count!==lastSignalCount){lastSignalCount=count;el('call').style.opacity='1';el('put').style.opacity='1';var target=el(sig.toLowerCase());if(fadeTimer!==null)clearTimeout(fadeTimer);target.style.opacity='1';fadeTimer=setTimeout(function(){target.style.opacity='.68';fadeTimer=null;},450);}el('wins').innerHTML='WINS <b>'+(Number(s.wins)||0)+'</b>';el('losses').innerHTML='LOSSES <b>'+(Number(s.losses)||0)+'</b>';renderTelemetry(s,age,live);}
window.DRUpdate=function(s){if(!s||typeof s!=='object')return;lastState=Object.assign(lastState,s);lastState.qxHttpRttMs=qxHttpRttMs;lastState.qxHttpRttAt=qxHttpRttAt;lastState.qxHeartbeatResponseMs=qxHeartbeatResponseMs;lastState.qxHeartbeatAt=qxHeartbeatAt;backend.cycle++;backend.scanState=s.signal||'WAIT';backend.latency=s.lastTickAt?Math.max(0,Date.now()-Number(s.lastTickAt)):null;if(s.fingerprint)backend.fingerprint=s.fingerprint;render(lastState);};
window.DRSetPublicKey=async function(encoded){try{if(!window.crypto||!crypto.subtle)throw new Error('WebCrypto unavailable');var b=atob(encoded),bytes=new Uint8Array(b.length);for(var i=0;i<b.length;i++)bytes[i]=b.charCodeAt(i);nativePublicKey=await crypto.subtle.importKey('spki',bytes,{name:'RSA-OAEP',hash:'SHA-256'},false,['encrypt']);aesKey=await crypto.subtle.generateKey({name:'AES-GCM',length:256},true,['encrypt','decrypt']);var raw=await crypto.subtle.exportKey('raw',aesKey);wrappedAesKey=b64(await crypto.subtle.encrypt({name:'RSA-OAEP'},nativePublicKey,raw));if(!window.DRSecure||typeof window.DRSecure.postMessage!=='function')throw new Error('Native secure channel unavailable');var probe=await makeEnvelope({type:'crypto_probe',suite:'RSA-OAEP-256/AES-256-GCM'});window.DRSecure.postMessage(JSON.stringify(probe));backend.cipher='HANDSHAKE SENT';log('RSA-OAEP/AES-GCM encrypted handshake probe sent to native verifier');}catch(e){backend.cipher='LOCKED';log('LOCAL CRYPTO INIT FAILED '+(e&&e.message||e));}};
window.DRBack=function(){return false;};
async function probeQxHttpRtt(){if(qxRttProbeBusy||document.visibilityState==='hidden'||!window.fetch||!window.AbortController)return;qxRttProbeBusy=true;var controller=new AbortController(),timer=setTimeout(function(){controller.abort();},5000),started=performance.now();try{var url=new URL('/',location.origin);url.searchParams.set('_dr_rtt',String(Date.now()));var response=await fetch(url.href,{method:'HEAD',cache:'no-store',credentials:'include',mode:'same-origin',redirect:'follow',signal:controller.signal});var elapsed=performance.now()-started;if(response&&response.type!=='opaque'){qxHttpRttSamples.push(elapsed);if(qxHttpRttSamples.length>5)qxHttpRttSamples.shift();var sortedRtt=qxHttpRttSamples.slice().sort(function(a,b){return a-b;});qxHttpRttMs=sortedRtt[Math.floor(sortedRtt.length/2)];qxHttpRttAt=Date.now();lastState.qxHttpRttMs=qxHttpRttMs;lastState.qxHttpRttAt=qxHttpRttAt;log('QX HTTP RESPONSE · '+Math.round(elapsed)+' ms · HTTP '+response.status);postTelemetry({httpResponseMs:qxHttpRttMs,httpResponseAt:qxHttpRttAt});render(lastState);}else log('QX HTTP RTT unavailable: opaque response');}catch(e){log('QX HTTP RTT probe failed: '+(e&&e.name||'network error'));}finally{clearTimeout(timer);qxRttProbeBusy=false;}}log('COMPACT AADI INTERFACE READY · NATIVE SIGNAL ENGINE ACTIVE');reserveTopSpace();render(lastState);setTimeout(probeQxHttpRtt,5000);setInterval(probeQxHttpRtt,30000);setInterval(()=>{reserveTopSpace();render(lastState);},1000);
})();