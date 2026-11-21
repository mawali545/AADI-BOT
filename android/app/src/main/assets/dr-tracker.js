(function () {
'use strict';
if (window.__DR_CANDLE_TRACKER__) return;
window.__DR_CANDLE_TRACKER__ = true;
var NativeWS=window.WebSocket,sockets=0,frames=0,jsonFrames=0,sent=0,rejected=0,activeAsset='',activePeriod=60,pendingBinaryEvent='',logs=[],host,root;
var nativePublicKey=null,aesKey=null,wrappedAesKey=null,sendQueue=Promise.resolve();
var lastState={ticks:0,asset:'WAITING FOR QX',price:null,lastTickAt:0,connected:false,signal:'WAIT',wins:0,losses:0};
var backend={memoryStream:[],cycle:0,node:'ARMED',cipher:'LOCKED',fingerprint:'',latency:null,scanState:'WAITING'};
function log(m){logs.push('['+new Date().toISOString()+'] '+String(m));if(logs.length>120)logs.shift();}
function assetOf(o){if(!o||typeof o!=='object')return'';for(var k of ['asset','symbol','pair','instrument','assetName','ticker','active'])if(typeof o[k]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(o[k].trim()))return o[k].trim();return'';}
function num(o,keys){if(!o||typeof o!=='object')return null;for(var k of keys){var v=o[k];if((typeof v==='number'||(typeof v==='string'&&v.trim()))&&Number.isFinite(Number(v)))return Number(v);}return null;}
function sourceTime(v){if(typeof v==='string'&&!/^\d+(\.\d+)?$/.test(v)){var p=Date.parse(v);return Number.isFinite(p)&&p>0?p:null;}var n=Number(v);if(!Number.isFinite(n)||n<=0)return null;if(n<1e11)n*=1000;return Number.isSafeInteger(Math.round(n))?Math.round(n):null;}
function b64(bytes){var s='';new Uint8Array(bytes).forEach(function(x){s+=String.fromCharCode(x);});return btoa(s);}
async function makeEnvelope(payload){if(!nativePublicKey||!aesKey||!wrappedAesKey)throw new Error('Android secure key not ready');var iv=crypto.getRandomValues(new Uint8Array(12));var plain=new TextEncoder().encode(JSON.stringify(payload));var encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,tagLength:128},aesKey,plain);return{wrappedKey:wrappedAesKey,iv:b64(iv),ciphertext:b64(encrypted)};}
function postPayload(payload,fingerprint,frameBytes){if(!/^[a-f0-9]{64}$/i.test(fingerprint||'')||!Number.isInteger(frameBytes)||frameBytes<1||frameBytes>1048576){rejected++;return;}payload.frameFingerprint=fingerprint;payload.frameBytes=frameBytes;sendQueue=sendQueue.then(async function(){var envelope=await makeEnvelope(payload);if(!window.DRSecure||typeof window.DRSecure.postMessage!=='function')throw new Error('Origin-restricted Android channel unavailable');window.DRSecure.postMessage(JSON.stringify(envelope));sent++;}).catch(function(e){rejected++;log('SECURE BRIDGE SEND ERROR: '+(e&&e.message||e));});}
function send(price,asset,timestamp,fingerprint,frameBytes,sourceEvent){price=Number(price);asset=String(asset||activeAsset||'').trim();timestamp=sourceTime(timestamp);if(!Number.isFinite(price)||price<=0||price>=1e12||!asset||!/^[A-Z0-9_./-]{3,32}$/i.test(asset)||!timestamp)return;postPayload({type:'tick',source:String(sourceEvent||'unclassified'),price:price,asset:asset,timestamp:timestamp,period:Number(activePeriod)||60},fingerprint,frameBytes);}
function sendHistory(data,fp,size,sourceEvent){
 if(!data||typeof data!=='object')return;
 var asset=assetOf(data)||activeAsset,period=num(data,['period','timePeriod','interval','resolution'])||activePeriod;
 var rows=Array.isArray(data.candles)?data.candles:(Array.isArray(data.history)?data.history:(Array.isArray(data.data)?data.data:null));
 if(!rows&&data.candle&&typeof data.candle==='object')rows=[data.candle];
 if(!rows&&(['candle-generated','quotes/candle'].includes(sourceEvent)))rows=[data];
 if(!asset||!rows)return;
 var candles=[];
 rows.slice(-900).forEach(function(c){
  if(!c)return;
  var t,o,cl,h,l;
  if(Array.isArray(c)&&c.length>=5){t=sourceTime(c[0]);o=Number(c[1]);cl=Number(c[2]);h=Number(c[3]);l=Number(c[4]);}
  else if(typeof c==='object'){
   t=null;for(var k of ['timestamp','time','ts','at','created_at'])if(c[k]!=null){t=sourceTime(c[k]);if(t)break;}
   o=num(c,['open','o','openPrice']);cl=num(c,['close','c','closePrice','price']);h=num(c,['high','h','highPrice']);l=num(c,['low','l','lowPrice']);
  }
  if(t&&[o,cl,h,l].every(Number.isFinite)&&o>0&&cl>0&&h>0&&l>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl))
   candles.push({time:t,open:o,close:cl,high:h,low:l});
 });
 if(candles.length)postPayload({type:'history',source:String(sourceEvent||'history/list/v2'),asset:asset,period:Number(period)||60,candles:candles},fp,size);
}
function walk(v,inherited,depth,fp,size,sourceEvent){if(depth>10||v==null)return;if(Array.isArray(v)){if(v.length>=3&&typeof v[0]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(v[0])&&sourceTime(v[1])&&Number(v[2])>0){send(v[2],v[0],v[1],fp,size,sourceEvent);return;}v.forEach(function(x){walk(x,inherited,depth+1,fp,size,sourceEvent);});return;}if(typeof v!=='object')return;var a=assetOf(v)||inherited||activeAsset,p=num(v,['price','currentPrice','lastPrice','closePrice','quotePrice','value']),t=null;for(var k of ['timestamp','time','ts','created_at','at','serverTime'])if(v[k]!=null){t=sourceTime(v[k]);if(t)break;}if(p!==null&&a&&t){send(p,a,t,fp,size,sourceEvent);return;}Object.keys(v).forEach(function(k){if(v[k]&&typeof v[k]==='object')walk(v[k],a,depth+1,fp,size,sourceEvent);});}
function handleData(o,eventName,fp,size){
 if(eventName==='history/list/v2'||eventName==='history/load'||eventName==='chart_notification/get'||eventName==='loadHistoryPeriod'){
  sendHistory(o,fp,size,eventName);return;
 }
 if(eventName==='candle-generated'||eventName==='quotes/candle'){
  sendHistory(o,fp,size,eventName);return;
 }
 walk(o,activeAsset,0,fp,size,eventName);
}

/* Observe QX's own successful WebCrypto decrypt result. The browser/QX app
   retains its keys and performs decryption; this observer never extracts keys.
   Only recognized public market/candle event names are forwarded to the engine. */
try{
 if(window.crypto&&crypto.subtle&&typeof crypto.subtle.decrypt==='function'){
  var nativeSubtleDecrypt=crypto.subtle.decrypt.bind(crypto.subtle);
  crypto.subtle.decrypt=function(){
   var args=arguments,encrypted=args[2],cipherBytes=null;
   try{
    if(encrypted instanceof ArrayBuffer)cipherBytes=new Uint8Array(encrypted.slice(0));
    else if(ArrayBuffer.isView(encrypted))cipherBytes=new Uint8Array(encrypted.buffer.slice(encrypted.byteOffset,encrypted.byteOffset+encrypted.byteLength));
   }catch(_){}
   var result=nativeSubtleDecrypt.apply(this,args);
   return result.then(function(plain){
    try{
     var bytes=new Uint8Array(plain),decoded=new TextDecoder('utf-8',{fatal:true}).decode(bytes),parsed=JSON.parse(decoded);
     var eventName='',payload=parsed;
     if(Array.isArray(parsed)&&typeof parsed[0]==='string'){eventName=parsed[0];payload=parsed[1];}
     else if(parsed&&typeof parsed==='object')eventName=String(parsed.event||parsed.type||parsed.name||'');
     var allowed=['quotes/stream','history/list/v2','history/load','chart_notification/get','candle-generated','quotes/candle','loadHistoryPeriod'];
     if(allowed.indexOf(eventName)>=0){
      var fpBytes=cipherBytes||bytes;
      sha256Hex(fpBytes).then(function(fp){
       handleData(payload,eventName,fp,fpBytes.byteLength);
       log('QX APP-DECRYPTED MARKET PAYLOAD · '+eventName+' · plaintext '+bytes.byteLength+' bytes');
      }).catch(function(){});
     }
    }catch(_){/* Ignore non-market decryptions; never log or forward arbitrary plaintext. */}
    return plain;
   });
  };
  log('QX WEBCRYPTO DECRYPT OBSERVER ARMED · market events only');
 }
}catch(e){log('QX DECRYPT OBSERVER UNAVAILABLE '+(e&&e.message||e));}

var pendingBinary=null;
function sha256Hex(bytes){return crypto.subtle.digest('SHA-256',bytes).then(function(d){return Array.from(new Uint8Array(d)).map(x=>x.toString(16).padStart(2,'0')).join('');});}
function replaceBinaryPlaceholders(value,attachments){
 if(!value||typeof value!=='object')return value;
 if(value._placeholder===true&&Number.isInteger(value.num)&&attachments[value.num]){
  var raw=attachments[value.num],text=new TextDecoder('utf-8',{fatal:true}).decode(raw);
  try{return JSON.parse(text);}catch(_){return {__opaqueBinary:true,byteLength:raw.byteLength};}
 }
 if(Array.isArray(value))return value.map(v=>replaceBinaryPlaceholders(v,attachments));
 Object.keys(value).forEach(function(k){value[k]=replaceBinaryPlaceholders(value[k],attachments);});
 return value;
}
async function consumeBinaryAttachment(raw){
 if(!pendingBinary)return false;
 var bytes=raw instanceof Blob?await raw.arrayBuffer():(raw instanceof ArrayBuffer?raw:(ArrayBuffer.isView(raw)?raw.buffer.slice(raw.byteOffset,raw.byteOffset+raw.byteLength):null));
 if(!bytes)return false;
 pendingBinary.attachments.push(bytes);
 if(pendingBinary.attachments.length<pendingBinary.count)return true;
 var packet=pendingBinary;pendingBinary=null;
 var eventName=Array.isArray(packet.header)?packet.header[0]:'';
 var data=Array.isArray(packet.header)?packet.header[1]:null;
 var resolved=replaceBinaryPlaceholders(data,packet.attachments);
 var opaque=JSON.stringify(resolved).indexOf('"__opaqueBinary":true')>=0;
 var all=new Uint8Array(packet.attachments.reduce((n,a)=>n+a.byteLength,0)),offset=0;
 packet.attachments.forEach(function(a){all.set(new Uint8Array(a),offset);offset+=a.byteLength;});
 var headerBytes=new TextEncoder().encode(packet.rawHeader),joined=new Uint8Array(headerBytes.length+all.length);joined.set(headerBytes);joined.set(all,headerBytes.length);
 var fp=await sha256Hex(joined);
 if(opaque){log('QX BINARY EVENT '+eventName+' · '+packet.attachments.length+' attachment(s) · '+all.length+' bytes · opaque payload retained, schema required');return true;}
 jsonFrames++;log('QX BINARY EVENT DECODED '+eventName+' · '+packet.attachments.length+' attachment(s)');
 if(['quotes/stream','history/list/v2','history/load','chart_notification/get','candle-generated','quotes/candle','loadHistoryPeriod'].includes(eventName))handleData(resolved,eventName,fp,joined.length);
 return true;
}
async function parseFrame(raw){
 frames++;
 if(raw instanceof Blob||raw instanceof ArrayBuffer||ArrayBuffer.isView(raw)){
  if(pendingBinary){try{await consumeBinaryAttachment(raw);}catch(e){log('QX BINARY ATTACHMENT ERROR '+(e&&e.message||e));pendingBinary=null;}return;}
  if(raw instanceof Blob){var ab=await raw.arrayBuffer();try{var decoded=new TextDecoder('utf-8',{fatal:true}).decode(ab);await parseFrame(decoded);}catch(_){log('QX OPAQUE BINARY FRAME · '+raw.size+' bytes · no matching Socket.IO binary header');}return;}
  var view=raw instanceof ArrayBuffer?new Uint8Array(raw):new Uint8Array(raw.buffer,raw.byteOffset,raw.byteLength);
  try{await parseFrame(new TextDecoder('utf-8',{fatal:true}).decode(view));}catch(_){log('QX OPAQUE BINARY FRAME · '+view.byteLength+' bytes · no matching Socket.IO binary header');}
  return;
 }
 if(typeof raw!=='string'||!window.crypto||!crypto.subtle)return;
 var bytes=new TextEncoder().encode(raw),fp=await sha256Hex(bytes),text=raw.trim();
 try{
  var binaryMatch=text.match(/^45(\d+)-([\s\S]+)$/);
  if(binaryMatch){var count=Number(binaryMatch[1]),header=JSON.parse(binaryMatch[2]);if(!Number.isInteger(count)||count<1||count>32||!Array.isArray(header)){log('QX BINARY HEADER REJECTED');return;}pendingBinary={count:count,header:header,attachments:[],rawHeader:text};log('QX BINARY HEADER '+(header[0]||'unknown')+' · expecting '+count+' attachment(s)');return;}
  var eventName='';
  if(text.charCodeAt(0)===4){text=text.slice(1);eventName='';}
  else if(text.startsWith('42'))text=text.slice(2);
  var o=JSON.parse(text);jsonFrames++;
  if(Array.isArray(o)&&typeof o[0]==='string'){eventName=o[0];var data=o[1],a=assetOf(data);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|candle|market)/i.test(eventName)){activeAsset=a;log('MARKET EVENT '+eventName+' · '+a);}if(['quotes/stream','history/list/v2','history/load','chart_notification/get','candle-generated','quotes/candle','loadHistoryPeriod'].includes(eventName))handleData(data,eventName,fp,bytes.length);else log('IGNORED NON-MARKET QX EVENT '+eventName);}
  else handleData(o,eventName,fp,bytes.length);
  backend.fingerprint=fp;
 }catch(e){log('QX FRAME PARSE '+(e&&e.message||e));}
}

try{if(NativeWS)window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){var ws=Reflect.construct(T,args,T);sockets++;try{var old=ws.send;ws.send=function(d){try{if(typeof d==='string'){var q=d.startsWith('42')?d.slice(2):d,o=JSON.parse(q);if(Array.isArray(o)){var a=assetOf(o[1]);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|history)/i.test(o[0])){activeAsset=a;if(o[0]==='instruments/update'&&o[1]&&Number(o[1].period)>0)activePeriod=Number(o[1].period);}}}}catch(_){}return old.call(this,d);};}catch(_){}ws.addEventListener('open',()=>log('QX WEBSOCKET OPEN'));ws.addEventListener('message',e=>{parseFrame(e.data).catch(err=>log('QX FRAME OBSERVER ERROR '+(err&&err.message||err)));});ws.addEventListener('close',()=>log('QX WEBSOCKET CLOSED'));ws.addEventListener('error',()=>log('QX WEBSOCKET ERROR'));return ws;}});}catch(e){log('WEBSOCKET OBSERVER ERROR '+(e&&e.message||e));}

/* Passive same-origin HTTP market observer: allow-listed market/history JSON only.
   No cookies, auth headers, account data, or broker requests are read or modified. */
function marketHttpKind(url){
 try{var u=new URL(url,location.href);if(u.origin!==location.origin)return '';
 var p=u.pathname.toLowerCase();if(!/(quote|price|tick|candle|chart|history|market)/.test(p))return '';
 if(/history/.test(p))return 'history/list/v2';if(/candle/.test(p))return 'candle-generated';return 'quotes/stream';
 }catch(_){return '';}
}
async function inspectHttpResponse(url,body,status){
 var kind=marketHttpKind(url);if(!kind||status<200||status>=300||typeof body!=='string'||body.length<2||body.length>1048576)return;
 try{var bytes=new TextEncoder().encode(body),digest=await crypto.subtle.digest('SHA-256',bytes);
 var fp=Array.from(new Uint8Array(digest)).map(x=>x.toString(16).padStart(2,'0')).join('');
 handleData(JSON.parse(body),kind,fp,bytes.length);log('HTTP MARKET JSON '+kind+' · '+status+' · '+bytes.length+' bytes');
 }catch(e){log('HTTP MARKET RESPONSE IGNORED '+(e&&e.message||e));}
}
try{
 if(typeof window.fetch==='function'){var nativeFetch=window.fetch;window.fetch=function(){
  var args=arguments,url=args[0]&&args[0].url?args[0].url:String(args[0]||''),kind=marketHttpKind(url),p=nativeFetch.apply(this,args);
  if(kind)p.then(function(r){try{r.clone().text().then(function(body){inspectHttpResponse(url,body,r.status);}).catch(function(){});}catch(_){}});
  return p;};}
 if(window.XMLHttpRequest){var NativeXHR=window.XMLHttpRequest;window.XMLHttpRequest=new Proxy(NativeXHR,{construct:function(T,args){
  var xhr=Reflect.construct(T,args,T),url='',open=xhr.open,send=xhr.send;
  xhr.open=function(method,u){url=String(u||'');return open.apply(this,arguments);};
  xhr.send=function(){if(marketHttpKind(url))xhr.addEventListener('load',function(){try{inspectHttpResponse(url,xhr.responseText,xhr.status);}catch(_){}});return send.apply(this,arguments);};
  return xhr;}});}
}catch(e){log('HTTP OBSERVER ERROR '+(e&&e.message||e));}


/* Observe EventSource market feeds when QX exposes them in the authenticated page. */
try{if(window.EventSource){var NativeES=window.EventSource;window.EventSource=new Proxy(NativeES,{construct:function(T,args){
 var es=Reflect.construct(T,args,T),url=String(args&&args[0]||'');
 if(marketHttpKind(url)){es.addEventListener('message',function(ev){if(typeof ev.data==='string'){var kind=marketHttpKind(url);var b=new TextEncoder().encode(ev.data);sha256Hex(b).then(function(fp){try{handleData(JSON.parse(ev.data),kind,fp,b.length);log('QX EVENTSOURCE '+kind+' · '+b.length+' bytes');}catch(_){}});}});}
 return es;}});}}catch(e){log('EVENTSOURCE OBSERVER ERROR '+(e&&e.message||e));}

var css=[
'*{box-sizing:border-box}',
'#strip{position:fixed;z-index:2147483647;top:0;left:4px;right:4px;height:88px;background:rgba(3,13,8,.94);border:1px solid rgba(91,255,151,.62);border-radius:9px;color:#e8fff0;padding:6px 10px;font:12px/1.2 Arial,sans-serif;pointer-events:none;box-shadow:0 2px 14px rgba(0,0,0,.35);backdrop-filter:blur(5px)}',
'#ticker{height:27px;display:grid;grid-template-columns:auto auto minmax(0,1fr);align-items:center;column-gap:7px;border-bottom:1px solid rgba(100,255,160,.22);padding:0 8px 5px 0;min-width:0;max-width:100%;overflow:hidden}',
'#brand{font-size:10px;font-weight:800;letter-spacing:.8px;color:#a8ffc5;white-space:nowrap}',
'#market{font-size:clamp(8px,3.6vw,13px);font-weight:800;color:#fff;text-align:right;white-space:nowrap;overflow:hidden;text-overflow:clip;min-width:0;max-width:100%;transform:none;letter-spacing:-.25px;font-variant-numeric:tabular-nums}',
'#status{font-size:9px;font-weight:800;color:#8cffb4;white-space:nowrap}',
'#signals{height:35px;display:grid;grid-template-columns:repeat(3,1fr);gap:7px;align-items:center;padding:5px 0}',
'.sig{height:25px;border:1px solid #355a43;border-radius:5px;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:900;color:#9eb9a7;background:rgba(7,23,13,.7)}',
'.sig.active.wait{color:#ffe09a;border-color:#ffe09a;background:rgba(90,63,8,.35)}',
'.sig.active.call{color:#72ffa7;border-color:#72ffa7;background:rgba(12,82,40,.38)}',
'.sig.active.put{color:#ff8098;border-color:#ff8098;background:rgba(93,14,31,.38)}',
'#stats{height:18px;display:flex;align-items:center;justify-content:center;gap:24px;font-size:10px;font-weight:800;letter-spacing:.6px;color:#c4d7ca}',
'#stats b{font-size:12px;margin-left:4px}#wins{color:#71fba2}#losses{color:#ff8296}',
'@media(max-height:620px){#strip{height:84px;padding:5px 8px}#signals{height:31px;padding:3px 0}.sig{height:23px}#market{font-size:12px}}'
].join('');
host=document.createElement('div');host.id='dr-candle-tracker-host';Object.assign(host.style,{position:'fixed',inset:'0',zIndex:'2147483646',pointerEvents:'none'});var mount=document.documentElement||document.body;if(mount)mount.appendChild(host);else document.addEventListener('DOMContentLoaded',function(){var t=document.documentElement||document.body;if(t&&!host.isConnected)t.appendChild(host);},{once:true});root=host.attachShadow({mode:'open'});
root.innerHTML='<style>'+css+'</style><section id="strip" aria-label="AADI live market ticker"><div id="ticker"><span id="brand">AADI · LIVE TICKER</span><span id="status">WAITING FOR QX</span><span id="market">—</span></div><div id="signals"><span id="wait" class="sig active wait">WAIT</span><span id="call" class="sig">CALL</span><span id="put" class="sig">PUT</span></div><div id="stats"><span id="wins">WINS <b>0</b></span><span id="losses">LOSSES <b>0</b></span></div></section>';
function el(id){return root.getElementById(id);}
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
function render(s){var age=s.lastTickAt?Date.now()-Number(s.lastTickAt):Infinity,live=!!s.lastTickAt&&age>=-30000&&age<8000;el('status').textContent=live?'● QX WS LIVE':(s.ticks?'● RECONNECTING':'WAITING FOR QX');el('status').style.color=live?'#7cffaa':'#ffd58a';el('market').textContent=(live?(s.asset||'MARKET'):(s.asset||'WAITING FOR QX'))+'  '+(s.price==null?'—':String(s.price));fitMarketText();var sig=['CALL','PUT'].includes(s.signal)?s.signal:'WAIT';['wait','call','put'].forEach(function(id){el(id).className='sig'+(id.toUpperCase()===sig?' active '+id:'');});el('wins').innerHTML='WINS <b>'+(Number(s.wins)||0)+'</b>';el('losses').innerHTML='LOSSES <b>'+(Number(s.losses)||0)+'</b>';}
window.DRUpdate=function(s){if(!s||typeof s!=='object')return;lastState=Object.assign(lastState,s);backend.cycle++;backend.scanState=s.signal||'WAIT';backend.latency=s.lastTickAt?Math.max(0,Date.now()-Number(s.lastTickAt)):null;if(s.fingerprint)backend.fingerprint=s.fingerprint;if(s.diagnostics&&typeof s.diagnostics==='object'){backend.nativeDiagnostics=s.diagnostics;backend.memoryStream=s.diagnostics.memoryStream||[];backend.vector=s.diagnostics.vector||[];backend.vectorMagnitude=Number(s.diagnostics.vectorMagnitude)||0;backend.cycle=Number(s.diagnostics.cycle)||backend.cycle;backend.node=s.diagnostics.node||backend.node;backend.cipher=s.diagnostics.cipherState||backend.cipher;backend.scanState=s.diagnostics.scanState||backend.scanState;backend.latency=s.diagnostics.latencyMs>=0?Number(s.diagnostics.latencyMs):backend.latency;backend.eventLog=s.diagnostics.eventLog||[];backend.scanChecklist=s.diagnostics.scanChecklist||[];}render(lastState);};
window.DRSetPublicKey=async function(encoded){try{if(!window.crypto||!crypto.subtle)throw new Error('WebCrypto unavailable');var b=atob(encoded),bytes=new Uint8Array(b.length);for(var i=0;i<b.length;i++)bytes[i]=b.charCodeAt(i);nativePublicKey=await crypto.subtle.importKey('spki',bytes,{name:'RSA-OAEP',hash:'SHA-256'},false,['encrypt']);aesKey=await crypto.subtle.generateKey({name:'AES-GCM',length:256},true,['encrypt','decrypt']);var raw=await crypto.subtle.exportKey('raw',aesKey);wrappedAesKey=b64(await crypto.subtle.encrypt({name:'RSA-OAEP'},nativePublicKey,raw));backend.cipher='LOCAL BRIDGE READY';log('LOCAL WEBVIEW-TO-ANDROID RSA-OAEP/AES-GCM CHANNEL READY');}catch(e){backend.cipher='LOCKED';log('LOCAL CRYPTO INIT FAILED '+(e&&e.message||e));}};
window.DRBack=function(){return false;};
log('COMPACT AADI INTERFACE READY · NATIVE SIGNAL ENGINE ACTIVE');startLayoutReservation();render(lastState);setInterval(()=>{reserveTopSpace();fitMarketText();render(lastState);},1000);
})();