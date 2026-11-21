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
function sendHistory(data,fp,size,sourceEvent){if(!data||typeof data!=='object')return;var asset=assetOf(data)||activeAsset,period=num(data,['period','timePeriod'])||activePeriod,rows=Array.isArray(data.candles)?data.candles:(Array.isArray(data.history)?data.history:(Array.isArray(data.data)?data.data:null));if(!asset||!rows)return;var candles=[];rows.slice(-900).forEach(function(c){if(!Array.isArray(c)||c.length<5)return;var t=sourceTime(c[0]),o=Number(c[1]),cl=Number(c[2]),h=Number(c[3]),l=Number(c[4]);if(t&&[o,cl,h,l].every(Number.isFinite)&&o>0&&cl>0&&h>0&&l>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl))candles.push({time:t,open:o,close:cl,high:h,low:l});});if(candles.length)postPayload({type:'history',source:String(sourceEvent||'history/list/v2'),asset:asset,period:Number(period)||60,candles:candles},fp,size);}
function walk(v,inherited,depth,fp,size,sourceEvent){if(depth>10||v==null)return;if(Array.isArray(v)){if(v.length>=3&&typeof v[0]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(v[0])&&sourceTime(v[1])&&Number(v[2])>0){send(v[2],v[0],v[1],fp,size,sourceEvent);return;}v.forEach(function(x){walk(x,inherited,depth+1,fp,size,sourceEvent);});return;}if(typeof v!=='object')return;var a=assetOf(v)||inherited||activeAsset,p=num(v,['price','currentPrice','lastPrice','closePrice','quotePrice','value']),t=null;for(var k of ['timestamp','time','ts','created_at','at','serverTime'])if(v[k]!=null){t=sourceTime(v[k]);if(t)break;}if(p!==null&&a&&t){send(p,a,t,fp,size,sourceEvent);return;}Object.keys(v).forEach(function(k){if(v[k]&&typeof v[k]==='object')walk(v[k],a,depth+1,fp,size,sourceEvent);});}
function handleData(o,eventName,fp,size){if(eventName==='history/list/v2'||eventName==='history/load'||eventName==='chart_notification/get'||eventName==='loadHistoryPeriod'){sendHistory(o,fp,size,eventName);return;}if(eventName==='candle-generated'&&o&&typeof o==='object'){var ca=assetOf(o)||activeAsset,cp=num(o,['price','close']);var ct=null;for(var k of ['timestamp','time','ts'])if(o[k]!=null){ct=sourceTime(o[k]);if(ct)break;}if(cp!==null&&ca&&ct){send(cp,ca,ct,fp,size,eventName);return;}}walk(o,activeAsset,0,fp,size,eventName);}

var binaryPackets=[],httpEvents=0;
function safeUrl(value){try{var u=new URL(String(value),location.href);return u.origin+u.pathname;}catch(_){return '[unavailable]';}}
function binaryBytes(value){if(value instanceof ArrayBuffer)return new Uint8Array(value);if(ArrayBuffer.isView(value))return new Uint8Array(value.buffer,value.byteOffset,value.byteLength);return null;}
async function fingerprintBytes(parts){var total=0;parts.forEach(function(p){total+=p.byteLength;});var joined=new Uint8Array(total),offset=0;parts.forEach(function(p){joined.set(p,offset);offset+=p.byteLength;});var digest=await crypto.subtle.digest('SHA-256',joined);return{hash:Array.from(new Uint8Array(digest)).map(x=>x.toString(16).padStart(2,'0')).join(''),size:total};}
function inspectAttachment(buffer,index){
 var bytes=binaryBytes(buffer);if(!bytes)return{index:index,kind:'UNAVAILABLE'};
 var out={__qxBinaryAttachment:true,index:index,byteLength:bytes.byteLength,base64:b64(bytes.buffer),hex:Array.from(bytes.slice(0,64)).map(x=>x.toString(16).padStart(2,'0')).join('')};
 try{
  // Quotex/Engine.IO binary message payloads may prefix JSON with byte 0x04.
  var payloadBytes=(bytes.length&&bytes[0]===4)?bytes.subarray(1):bytes;
  var decoder=new TextDecoder('utf-8',{fatal:true}),decoded=decoder.decode(payloadBytes),trimmed=decoded.trim();
  if(trimmed&&(/^[{[]/.test(trimmed))){try{out.decodedJson=JSON.parse(trimmed);out.decode='UTF8_JSON';}catch(_){out.decode='UTF8_TEXT_NOT_JSON';out.utf8=decoded.slice(0,4096);}}
  else if(trimmed&&/^[\\x20-\\x7e\\r\\n\\t]+$/.test(trimmed)){out.decode='UTF8_TEXT';out.utf8=decoded.slice(0,4096);}
  else out.decode='BINARY_OPAQUE';
 }catch(_){out.decode='NON_UTF8_BINARY_OPAQUE';}
 return out;
}
function replaceBinaryPlaceholders(value,attachments){
 if(!value||typeof value!=='object')return value;
 if(Array.isArray(value))return value.map(x=>replaceBinaryPlaceholders(x,attachments));
 if(value._placeholder===true&&Number.isInteger(value.num)){var a=attachments[value.num];return a?inspectAttachment(a,value.num):value;}
 var out={};Object.keys(value).forEach(function(k){out[k]=replaceBinaryPlaceholders(value[k],attachments);});return out;
}
async function dispatchPacket(packet,attachments){
 var rawParts=[packet.headerBytes].concat(attachments.map(binaryBytes).filter(Boolean)),meta=await fingerprintBytes(rawParts);
 var data=packet.data,eventName=packet.eventName;
 if(data&&typeof data==='object'){data=replaceBinaryPlaceholders(data,attachments);var decoded=[];(function scan(v,d){if(d>10||!v||typeof v!=='object')return;if(v.__qxBinaryAttachment){if(v.decodedJson!==undefined)decoded.push(v.decodedJson);else log('QX ATTACHMENT '+v.index+' · '+v.decode+' · '+v.byteLength+' bytes · hex '+v.hex);}else if(Array.isArray(v))v.forEach(x=>scan(x,d+1));else Object.keys(v).forEach(k=>scan(v[k],d+1));})(data,0);if(decoded.length){data={socketEventData:data,decodedAttachments:decoded};log('QX BINARY ATTACHMENT UTF-8 JSON DECODED · '+decoded.length+' item(s)');}}
 log('SOCKET.IO '+(packet.binary?'BINARY':'TEXT')+' EVENT '+eventName+' · attachments '+attachments.length+' · '+meta.size+' bytes');
 if(eventName==='quotes/stream'||eventName==='history/list/v2'||eventName==='history/load'||eventName==='chart_notification/get'||eventName==='candle-generated'||eventName==='loadHistoryPeriod')handleData(data,eventName,meta.hash,meta.size);
 backend.fingerprint=meta.hash;
}
async function parseFrame(raw){
 frames++;
 if(!window.crypto||!crypto.subtle)return;
 if(typeof raw!=='string'){
  var bytes=binaryBytes(raw);if(!bytes)return;
  if(binaryPackets.length){
   var packet=binaryPackets[0];packet.attachments.push(bytes.slice().buffer);
   if(packet.attachments.length>=packet.expected){binaryPackets.shift();await dispatchPacket(packet,packet.attachments);}
   return;
  }
  log('UNASSOCIATED BINARY WEBSOCKET FRAME · '+bytes.byteLength+' bytes · retained as opaque');
  return;
 }
 var rawBytes=new TextEncoder().encode(raw),text=raw.trim();
 try{
  if(/^45[0-9]+-/.test(text)){
   var dash=text.indexOf('-'),expected=Number(text.slice(2,dash)),packetJson=JSON.parse(text.slice(dash+1));
   if(!Number.isInteger(expected)||expected<1||expected>64||!Array.isArray(packetJson)||typeof packetJson[0]!=='string')throw new Error('Invalid Socket.IO binary header');
   var headerDigest=await crypto.subtle.digest('SHA-256',rawBytes);
   binaryPackets.push({expected:expected,attachments:[],eventName:packetJson[0],data:packetJson[1],headerBytes:rawBytes,binary:true,createdAt:Date.now()});
   while(binaryPackets.length>8)binaryPackets.shift();
   log('SOCKET.IO BINARY HEADER '+packetJson[0]+' · expecting '+expected+' attachment(s)');
   return;
  }
  var eventName='',body=text;
  if(body.charCodeAt(0)===4)body=body.slice(1);
  if(body.startsWith('42'))body=body.slice(2);
  if(!body||body[0]!=='['&&body[0]!=='{')return;
  var o=JSON.parse(body);jsonFrames++;
  if(Array.isArray(o)&&typeof o[0]==='string'){
   eventName=o[0];var data=o[1],a=assetOf(data);
   if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|candle|market)/i.test(eventName)){activeAsset=a;log('MARKET EVENT '+eventName+' · '+a);}
   if(eventName==='quotes/stream'||eventName==='history/list/v2'||eventName==='history/load'||eventName==='chart_notification/get'||eventName==='candle-generated'||eventName==='loadHistoryPeriod')handleData(data,eventName,(await fingerprintBytes([rawBytes])).hash,rawBytes.length);
   else log('IGNORED NON-MARKET QX EVENT '+eventName);
  }else handleData(o,eventName,(await fingerprintBytes([rawBytes])).hash,rawBytes.length);
 }catch(e){log('QX FRAME PARSE '+(e&&e.message||e));}
}
function observeHttp(kind,method,url,status,duration,headers){
 httpEvents++;var safe=safeUrl(url);log(kind+' '+String(method||'GET').toUpperCase()+' '+safe+' · '+(status==null?'PENDING':status)+' · '+Math.max(0,Math.round(duration||0))+'ms'+(headers?' · headers '+headers:''));
}
try{
 var nativeFetch=window.fetch;
 if(nativeFetch)window.fetch=function(input,init){var url=typeof input==='string'?input:(input&&input.url)||'',method=(init&&init.method)||(input&&input.method)||'GET',began=performance.now();return nativeFetch.apply(this,arguments).then(function(response){var headers=[];try{['content-type','content-length','cache-control','server','date'].forEach(function(k){var v=response.headers.get(k);if(v)headers.push(k+':'+v);});}catch(_){}observeHttp('FETCH',method,url,response.status,performance.now()-began,headers.join('; '));return response;},function(error){observeHttp('FETCH-ERROR',method,url,0,performance.now()-began,'');throw error;});};
 var NativeXHR=window.XMLHttpRequest;
 if(NativeXHR){var xhrOpen=NativeXHR.prototype.open,xhrSend=NativeXHR.prototype.send;
 NativeXHR.prototype.open=function(method,url){this.__drMeta={method:method,url:url};return xhrOpen.apply(this,arguments);};
 NativeXHR.prototype.send=function(){var xhr=this,meta=xhr.__drMeta||{method:'GET',url:''},began=performance.now();xhr.addEventListener('loadend',function(){var headers=[];try{['content-type','content-length','cache-control','date'].forEach(function(k){var v=xhr.getResponseHeader(k);if(v)headers.push(k+':'+v);});}catch(_){}observeHttp('XHR',meta.method,meta.url,xhr.status,performance.now()-began,headers.join('; '));},{once:true});return xhrSend.apply(this,arguments);};}
}catch(e){log('HTTP OBSERVER UNAVAILABLE '+(e&&e.message||e));}

try{if(NativeWS)window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){var ws=Reflect.construct(T,args,T);sockets++;try{var old=ws.send;ws.send=function(d){try{if(typeof d==='string'){var q=d.startsWith('42')?d.slice(2):d,o=JSON.parse(q);if(Array.isArray(o)){var a=assetOf(o[1]);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|history)/i.test(o[0])){activeAsset=a;if(o[0]==='instruments/update'&&o[1]&&Number(o[1].period)>0)activePeriod=Number(o[1].period);}}}}catch(_){}return old.call(this,d);};}catch(_){}ws.addEventListener('open',()=>log('QX WEBSOCKET OPEN'));ws.addEventListener('message',e=>{if(typeof e.data==='string')parseFrame(e.data);else if(e.data instanceof Blob)e.data.arrayBuffer().then(parseFrame).catch(()=>{});else if(e.data instanceof ArrayBuffer)parseFrame(e.data);});ws.addEventListener('close',()=>log('QX WEBSOCKET CLOSED'));ws.addEventListener('error',()=>log('QX WEBSOCKET ERROR'));return ws;}});}catch(e){log('WEBSOCKET OBSERVER ERROR '+(e&&e.message||e));}
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