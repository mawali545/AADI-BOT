(function () {
'use strict';
if (window.__DR_CANDLE_TRACKER__) return;
window.__DR_CANDLE_TRACKER__ = true;
var NativeWS=window.WebSocket,sockets=0,frames=0,jsonFrames=0,sent=0,rejected=0,activeAsset='',logs=[],host,root;
var nativePublicKey=null,aesKey=null,wrappedAesKey=null,sendQueue=Promise.resolve();
var lastState={ticks:0,asset:'WAITING FOR QX',price:null,lastTickAt:0,connected:false,signal:'WAIT',wins:0,losses:0};
var backend={memoryStream:[],cycle:0,node:'ARMED',cipher:'LOCKED',fingerprint:'',latency:null,scanState:'WAITING'};
function log(m){logs.push('['+new Date().toISOString()+'] '+String(m));if(logs.length>120)logs.shift();}
function assetOf(o){if(!o||typeof o!=='object')return'';for(var k of ['asset','symbol','pair','instrument','assetName','ticker','active'])if(typeof o[k]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(o[k].trim()))return o[k].trim();return'';}
function num(o,keys){if(!o||typeof o!=='object')return null;for(var k of keys){var v=o[k];if((typeof v==='number'||(typeof v==='string'&&v.trim()))&&Number.isFinite(Number(v)))return Number(v);}return null;}
function sourceTime(v){if(typeof v==='string'&&!/^\d+(\.\d+)?$/.test(v)){var p=Date.parse(v);return Number.isFinite(p)&&p>0?p:null;}var n=Number(v);if(!Number.isFinite(n)||n<=0)return null;if(n<1e11)n*=1000;return Number.isSafeInteger(Math.round(n))?Math.round(n):null;}
function b64(bytes){var s='';new Uint8Array(bytes).forEach(function(x){s+=String.fromCharCode(x);});return btoa(s);}
async function makeEnvelope(payload){if(!nativePublicKey||!aesKey||!wrappedAesKey)throw new Error('Android secure key not ready');var iv=crypto.getRandomValues(new Uint8Array(12));var plain=new TextEncoder().encode(JSON.stringify(payload));var encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,tagLength:128},aesKey,plain);return{wrappedKey:wrappedAesKey,iv:b64(iv),ciphertext:b64(encrypted)};}
function send(price,asset,timestamp,fingerprint,frameBytes){price=Number(price);asset=String(asset||activeAsset||'').trim();timestamp=sourceTime(timestamp);if(!Number.isFinite(price)||price<=0||price>=1e12||!asset||!/^[A-Z0-9_./-]{3,32}$/i.test(asset)||!timestamp||!/^[a-f0-9]{64}$/i.test(fingerprint||'')||!Number.isInteger(frameBytes)||frameBytes<1||frameBytes>1048576){rejected++;return;}var payload={price:price,asset:asset,timestamp:timestamp,frameFingerprint:fingerprint,frameBytes:frameBytes};sendQueue=sendQueue.then(async function(){var envelope=await makeEnvelope(payload);if(!window.DRSecure||typeof window.DRSecure.postMessage!=='function')throw new Error('Origin-restricted Android channel unavailable');window.DRSecure.postMessage(JSON.stringify(envelope));sent++;}).catch(function(e){rejected++;log('SECURE BRIDGE SEND ERROR: '+(e&&e.message||e));});}
function walk(v,inherited,depth,fp,size){if(depth>10||v==null)return;if(Array.isArray(v)){if(v.length>=3&&typeof v[0]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(v[0])&&sourceTime(v[1])&&Number(v[2])>0){send(v[2],v[0],v[1],fp,size);return;}v.forEach(function(x){walk(x,inherited,depth+1,fp,size);});return;}if(typeof v!=='object')return;var a=assetOf(v)||inherited||activeAsset,p=num(v,['price','currentPrice','lastPrice','closePrice','quotePrice','value']),t=null;for(var k of ['timestamp','time','ts','created_at','at','serverTime'])if(v[k]!=null){t=sourceTime(v[k]);if(t)break;}if(p!==null&&a&&t){send(p,a,t,fp,size);return;}Object.keys(v).forEach(function(k){if(v[k]&&typeof v[k]==='object')walk(v[k],a,depth+1,fp,size);});}
async function parseFrame(raw){frames++;if(typeof raw!=='string'||!window.crypto||!crypto.subtle)return;var bytes=new TextEncoder().encode(raw),digest=await crypto.subtle.digest('SHA-256',bytes),fp=Array.from(new Uint8Array(digest)).map(x=>x.toString(16).padStart(2,'0')).join(''),text=raw.trim();try{if(text.startsWith('42'))text=text.slice(2);else if(text.charCodeAt(0)===4)text=text.slice(1);var o=JSON.parse(text);jsonFrames++;if(Array.isArray(o)&&typeof o[0]==='string'){var name=o[0],data=o[1],a=assetOf(data);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|candle|market)/i.test(name)){activeAsset=a;log('MARKET EVENT '+name+' · '+a);}if(!/(profile|balance|history|auth|login|token|session|user)/i.test(name))walk(data,activeAsset,0,fp,bytes.length);}else walk(o,activeAsset,0,fp,bytes.length);backend.fingerprint=fp;}catch(_){/* Unknown binary/compressed formats are never guessed into prices. */}}
try{if(NativeWS)window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){var ws=Reflect.construct(T,args,T);sockets++;try{var old=ws.send;ws.send=function(d){try{if(typeof d==='string'){var q=d.startsWith('42')?d.slice(2):d,o=JSON.parse(q);if(Array.isArray(o)){var a=assetOf(o[1]);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick)/i.test(o[0]))activeAsset=a;}}}catch(_){}return old.call(this,d);};}catch(_){}ws.addEventListener('open',()=>log('QX WEBSOCKET OPEN'));ws.addEventListener('message',e=>{if(typeof e.data==='string')parseFrame(e.data);else if(e.data instanceof Blob)e.data.text().then(parseFrame).catch(()=>{});else if(e.data instanceof ArrayBuffer){try{parseFrame(new TextDecoder().decode(e.data));}catch(_){}}});ws.addEventListener('close',()=>log('QX WEBSOCKET CLOSED'));ws.addEventListener('error',()=>log('QX WEBSOCKET ERROR'));return ws;}});}catch(e){log('WEBSOCKET OBSERVER ERROR '+(e&&e.message||e));}
var css=[
'*{box-sizing:border-box}',
'#strip{position:fixed;z-index:2147483647;top:var(--dr-safe-top,4px);left:4px;right:4px;height:91px;background:rgba(3,13,8,.91);border:1px solid rgba(91,255,151,.55);border-radius:9px;color:#e8fff0;padding:6px 10px;font:12px/1.2 Arial,sans-serif;pointer-events:none;box-shadow:0 2px 14px rgba(0,0,0,.35);backdrop-filter:blur(5px)}',
'#ticker{height:27px;display:flex;align-items:center;justify-content:space-between;gap:8px;border-bottom:1px solid rgba(100,255,160,.22);padding-bottom:5px}',
'#brand{font-size:10px;font-weight:800;letter-spacing:1px;color:#a8ffc5}',
'#market{font-size:13px;font-weight:800;color:#fff;text-align:right;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
'#status{font-size:9px;font-weight:800;color:#8cffb4;white-space:nowrap}',
'#signals{height:35px;display:grid;grid-template-columns:repeat(3,1fr);gap:7px;align-items:center;padding:5px 0}',
'.sig{height:25px;border:1px solid #355a43;border-radius:5px;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:900;color:#9eb9a7;background:rgba(7,23,13,.7)}',
'.sig.active.wait{color:#ffe09a;border-color:#ffe09a;background:rgba(90,63,8,.35)}',
'.sig.active.call{color:#72ffa7;border-color:#72ffa7;background:rgba(12,82,40,.38)}',
'.sig.active.put{color:#ff8098;border-color:#ff8098;background:rgba(93,14,31,.38)}',
'#stats{height:18px;display:flex;align-items:center;justify-content:center;gap:24px;font-size:10px;font-weight:800;letter-spacing:.6px;color:#c4d7ca}',
'#stats b{font-size:12px;margin-left:4px}#wins{color:#71fba2}#losses{color:#ff8296}',
'@media(max-height:620px){#strip{height:84px;padding:5px 8px}#signals{height:31px;padding:3px 0}.sig{height:23px}}'
].join('');
host=document.createElement('div');host.id='dr-candle-tracker-host';Object.assign(host.style,{position:'fixed',inset:'0',zIndex:'2147483646',pointerEvents:'none'});var mount=document.documentElement||document.body;if(mount)mount.appendChild(host);else document.addEventListener('DOMContentLoaded',function(){var t=document.documentElement||document.body;if(t&&!host.isConnected)t.appendChild(host);},{once:true});root=host.attachShadow({mode:'open'});
root.innerHTML='<style>'+css+'</style><section id="strip" aria-label="AADI live market ticker"><div id="ticker"><span id="brand">AADI · LIVE TICKER</span><span id="status">WAITING FOR QX</span><span id="market">—</span></div><div id="signals"><span id="wait" class="sig active wait">WAIT</span><span id="call" class="sig">CALL</span><span id="put" class="sig">PUT</span></div><div id="stats"><span id="wins">WINS <b>0</b></span><span id="losses">LOSSES <b>0</b></span></div></section>';
function el(id){return root.getElementById(id);}
function updateSafeTop(){
 var vw=Math.max(document.documentElement.clientWidth,window.innerWidth||0),bottom=0;
 function visible(r){return r&&r.width>vw*.62&&r.height>=30&&r.height<=180&&r.top>=-4&&r.top<90&&r.bottom>0;}
 var labels=Array.from(document.querySelectorAll('button,[role="button"],a,[aria-label],header,[role="banner"]'));
 var deposit=labels.find(function(n){var t=((n.innerText||n.getAttribute('aria-label')||'').replace(/\\s+/g,' ')).trim();return /deposit/i.test(t)&&t.length<90;});
 if(deposit){
  var n=deposit;
  for(var i=0;n&&i<7;i++,n=n.parentElement){var r=n.getBoundingClientRect();if(visible(r)){bottom=Math.max(bottom,r.bottom);break;}}
 }
 if(!bottom){
  document.querySelectorAll('header,[role="banner"],nav,[class*="header"],[class*="topbar"],[class*="top-bar"]').forEach(function(n){
   if(n===host||host.contains(n))return;
   var r=n.getBoundingClientRect(),p=getComputedStyle(n).position;
   if(visible(r)&&(p==='fixed'||p==='sticky'||r.top<12))bottom=Math.max(bottom,r.bottom);
  });
 }
 var safe=Math.max(4,Math.min(190,Math.ceil(bottom+5)));
 host.style.setProperty('--dr-safe-top',safe+'px');
}
window.addEventListener('resize',updateSafeTop,{passive:true});
window.addEventListener('scroll',updateSafeTop,{passive:true});
setInterval(updateSafeTop,900);
function render(s){var age=s.lastTickAt?Date.now()-Number(s.lastTickAt):Infinity,live=!!s.lastTickAt&&age>=-30000&&age<8000;el('status').textContent=live?'● LIVE':(s.ticks?'● RECONNECTING':'WAITING FOR QX');el('status').style.color=live?'#7cffaa':'#ffd58a';el('market').textContent=(live?(s.asset||'MARKET'):(s.asset||'WAITING FOR QX'))+'  '+(s.price==null?'—':String(s.price));var sig=['CALL','PUT'].includes(s.signal)?s.signal:'WAIT';['wait','call','put'].forEach(function(id){el(id).className='sig'+(id.toUpperCase()===sig?' active '+id:'');});el('wins').innerHTML='WINS <b>'+(Number(s.wins)||0)+'</b>';el('losses').innerHTML='LOSSES <b>'+(Number(s.losses)||0)+'</b>';}
window.DRUpdate=function(s){if(!s||typeof s!=='object')return;lastState=Object.assign(lastState,s);backend.cycle++;backend.scanState=s.signal||'WAIT';backend.latency=s.lastTickAt?Math.max(0,Date.now()-Number(s.lastTickAt)):null;if(s.fingerprint)backend.fingerprint=s.fingerprint;render(lastState);};
window.DRSetPublicKey=async function(encoded){try{if(!window.crypto||!crypto.subtle)throw new Error('WebCrypto unavailable');var b=atob(encoded),bytes=new Uint8Array(b.length);for(var i=0;i<b.length;i++)bytes[i]=b.charCodeAt(i);nativePublicKey=await crypto.subtle.importKey('spki',bytes,{name:'RSA-OAEP',hash:'SHA-256'},false,['encrypt']);aesKey=await crypto.subtle.generateKey({name:'AES-GCM',length:256},true,['encrypt','decrypt']);var raw=await crypto.subtle.exportKey('raw',aesKey);wrappedAesKey=b64(await crypto.subtle.encrypt({name:'RSA-OAEP'},nativePublicKey,raw));backend.cipher='LOCAL BRIDGE READY';log('LOCAL WEBVIEW-TO-ANDROID RSA-OAEP/AES-GCM CHANNEL READY');}catch(e){backend.cipher='LOCKED';log('LOCAL CRYPTO INIT FAILED '+(e&&e.message||e));}};
window.DRBack=function(){return false;};
log('COMPACT AADI INTERFACE READY · NATIVE SIGNAL ENGINE ACTIVE');updateSafeTop();render(lastState);setInterval(()=>{updateSafeTop();render(lastState);},1000);
})();