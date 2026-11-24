(function () {
'use strict';
if (window.__DR_CANDLE_TRACKER__) return;
window.__DR_CANDLE_TRACKER__ = true;
var NativeWS=window.WebSocket,sockets=0,frames=0,jsonFrames=0,sent=0,rejected=0,activeAsset='',activePeriod=10,pendingBinaryEvent='',pendingBinaryPacket=null,logs=[],host,root;
var nativePublicKey=null,aesKey=null,wrappedAesKey=null,sendQueue=Promise.resolve(),frameDiagnostics=[];
var lastState={ticks:0,asset:'WAITING FOR QX',price:null,lastTickAt:0,connected:false,signal:'WAIT',wins:0,losses:0};
var backend={memoryStream:[],cycle:0,node:'ARMED',cipher:'LOCKED',fingerprint:'',latency:null,scanState:'WAITING'};
var qxHttpRttMs=null,qxHttpRttAt=0,qxHttpRttSamples=[],qxHeartbeatResponseMs=null,qxHeartbeatAt=0,qxRttProbeBusy=false,qxLoginState='NOT VERIFIED',qxLoginLastSent='';
function log(m){logs.push('['+new Date().toISOString()+'] '+String(m));if(logs.length>120)logs.shift();}
var DIAG_PREFIX_BYTES=96;
function valueShape(v,depth){depth=depth||0;if(depth>3)return'…';if(v===null)return'null';if(Array.isArray(v)){var types={};v.slice(0,12).forEach(function(x){var t=Array.isArray(x)?'array':(x===null?'null':typeof x);types[t]=(types[t]||0)+1;});return'array['+v.length+'] '+JSON.stringify(types);}if(typeof v==='object'){var keys=Object.keys(v);return'object{'+keys.slice(0,32).join(',')+(keys.length>32?',…':'')+'}';}return typeof v;}
function safeDiagEvent(name){return /^(quotes\/stream|depth\/change|history\/list\/v2|history\/load|chart_notification\/get|loadHistoryPeriod|instruments\/list)$/.test(String(name||''));}
function bytePreview(bytes){var prefix=bytes.subarray(0,Math.min(bytes.length,DIAG_PREFIX_BYTES));return{hex:Array.from(prefix).map(function(x){return x.toString(16).padStart(2,'0');}).join(''),base64:b64(prefix),previewBytes:prefix.length,truncated:bytes.length>prefix.length};}
function recordFrameDiag(kind,size,eventName,keys,hex,extra){var item={at:new Date().toISOString(),kind:String(kind||'frame').slice(0,48),bytes:Number(size)||0,event:String(eventName||'').slice(0,96),keys:Array.isArray(keys)?keys.slice(0,24).map(function(k){return String(k).slice(0,48);}):[],hex:String(hex||'').slice(0,192),meta:extra&&typeof extra==='object'?extra:{}};frameDiagnostics.push(item);if(frameDiagnostics.length>80)frameDiagnostics.shift();try{window.localStorage.setItem('dr-qx-frame-diagnostics-v1',JSON.stringify(frameDiagnostics));}catch(_){}}
try{var priorDiagnostics=JSON.parse(window.localStorage.getItem('dr-qx-frame-diagnostics-v1')||'[]');if(Array.isArray(priorDiagnostics))frameDiagnostics=priorDiagnostics.slice(-80);}catch(_){}
function diagnosticCopyText(){var saved=frameDiagnostics.slice();try{var persisted=JSON.parse(window.localStorage.getItem('dr-qx-frame-diagnostics-v1')||'[]');if(Array.isArray(persisted)&&persisted.length>saved.length)saved=persisted.slice(-80);}catch(_){}return 'DR CANDLE TRACKER · QX FRAME DIAGNOSTICS\nCaptured: '+new Date().toISOString()+'\nPrivacy: credentials, cookies, authorization headers, SSID, tokens and full payloads are excluded. Approved market-data previews may contain raw market values such as prices. Only safe event metadata and a maximum '+DIAG_PREFIX_BYTES+'-byte binary prefix for approved market-data events is included.\n\nFRAME RECORDS\n'+saved.map(function(x){var m=x.meta||{};return [x.at,x.kind,'bytes='+x.bytes,'event='+x.event,'keys='+x.keys.join(','),m.shape?'shape='+m.shape:'',m.socketIoType?'socketio='+m.socketIoType:'',m.attachments!=null?'attachments='+m.attachments:'',m.attachmentIndex!=null?'attachment='+m.attachmentIndex+'/'+m.attachments:'',m.offset!=null?'offset='+m.offset:'',m.headerBytes!=null?'headerBytes='+m.headerBytes:'',m.previewBytes!=null?'previewBytes='+m.previewBytes:'',m.base64?'base64='+m.base64:'',m.truncated?'preview-truncated=true':'',x.hex?'hex='+x.hex:''].filter(Boolean).join(' | ');}).join('\n')+'\n\nMARKET SCANNER DIAGNOSTICS\n'+JSON.stringify({scanner:lastState.scanner||{},signalScanner:lastState.signalScanner||{}},null,2)+'\n\nRECENT PARSER LOGS\n'+logs.slice(-80).join('\n');}
function copyDiagnostics(){var value=diagnosticCopyText(),ok=false;try{var box=document.createElement('textarea');box.value=value;box.setAttribute('readonly','');box.style.position='fixed';box.style.opacity='0';document.body.appendChild(box);box.select();ok=document.execCommand('copy');box.remove();}catch(_){}var b=el('qh-copy');if(b)b.textContent=ok?'COPIED ✓':'COPY FAILED';setTimeout(function(){if(b)b.textContent='COPY QX DIAGNOSTICS';},1800);}
function assetOf(o){if(!o||typeof o!=='object')return'';for(var k of ['asset','symbol','pair','instrument','assetName','ticker','active'])if(typeof o[k]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(o[k].trim()))return o[k].trim();return'';}
function num(o,keys){if(!o||typeof o!=='object')return null;for(var k of keys){var v=o[k];if((typeof v==='number'||(typeof v==='string'&&v.trim()))&&Number.isFinite(Number(v)))return Number(v);}return null;}
function sourceTime(v){if(typeof v==='string'&&!/^\d+(\.\d+)?$/.test(v)){var p=Date.parse(v);return Number.isFinite(p)&&p>0?p:null;}var n=Number(v);if(!Number.isFinite(n)||n<=0)return null;if(n<1e11)n*=1000;return Number.isSafeInteger(Math.round(n))?Math.round(n):null;}
function b64(bytes){var s='';new Uint8Array(bytes).forEach(function(x){s+=String.fromCharCode(x);});return btoa(s);}
async function makeEnvelope(payload){if(!nativePublicKey||!aesKey||!wrappedAesKey)throw new Error('Android secure key not ready');var iv=crypto.getRandomValues(new Uint8Array(12));var plain=new TextEncoder().encode(JSON.stringify(payload));var encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,tagLength:128},aesKey,plain);return{wrappedKey:wrappedAesKey,iv:b64(iv),ciphertext:b64(encrypted)};}
function postPayload(payload,fingerprint,frameBytes){if(!/^[a-f0-9]{64}$/i.test(fingerprint||'')||!Number.isInteger(frameBytes)||frameBytes<1||frameBytes>1048576){rejected++;return;}payload.frameFingerprint=fingerprint;payload.frameBytes=frameBytes;sendQueue=sendQueue.then(async function(){var envelope=await makeEnvelope(payload);if(!window.DRSecure||typeof window.DRSecure.postMessage!=='function')throw new Error('Origin-restricted Android channel unavailable');window.DRSecure.postMessage(JSON.stringify(envelope));sent++;}).catch(function(e){rejected++;log('SECURE BRIDGE SEND ERROR: '+(e&&e.message||e));});}
function postTelemetry(metrics){if(!nativePublicKey||!aesKey||!wrappedAesKey||!window.DRSecure||typeof window.DRSecure.postMessage!=='function')return;sendQueue=sendQueue.then(async function(){var envelope=await makeEnvelope(Object.assign({type:'telemetry'},metrics||{}));window.DRSecure.postMessage(JSON.stringify(envelope));}).catch(function(e){log('SECURE TELEMETRY SEND ERROR: '+(e&&e.message||e));});}
function directionOf(value){if(typeof value==='number'&&Number.isFinite(value)&&Number.isInteger(value)&&value>=-1&&value<=1)return value;if(typeof value==='string'){var s=value.trim().toLowerCase();if(s==='1'||s==='up'||s==='buy'||s==='bull'||s==='call')return 1;if(s==='-1'||s==='down'||s==='sell'||s==='bear'||s==='put')return -1;if(s==='0'||s==='flat'||s==='neutral')return 0;}return null;}
function send(price,asset,timestamp,tickDirection,fingerprint,frameBytes,sourceEvent){price=Number(price);asset=String(asset||activeAsset||'').trim();timestamp=sourceTime(timestamp);tickDirection=directionOf(tickDirection);if(!Number.isFinite(price)||price<=0||price>=1e12||!asset||!/^[A-Z0-9_./-]{3,32}$/i.test(asset)||!timestamp)return;postPayload({type:'tick',source:String(sourceEvent||'unclassified'),price:price,asset:asset,timestamp:timestamp,tickDirection:tickDirection,period:Number(activePeriod)||10,heartbeatSendMs:qxHeartbeatResponseMs,heartbeatAt:qxHeartbeatAt,httpResponseMs:qxHttpRttMs,httpResponseAt:qxHttpRttAt},fingerprint,frameBytes);}
function sendHistory(data,fp,size,sourceEvent){
 if(!data||typeof data!=='object')return;
 var asset=assetOf(data)||activeAsset,period=num(data,['period','timePeriod'])||activePeriod;
 var rows=Array.isArray(data.candles)?data.candles:(Array.isArray(data.history)?data.history:(Array.isArray(data.data)?data.data:null));
 if(!asset||!rows)return;
 period=Number(period);if(!Number.isFinite(period)||period<1||period>86400)period=activePeriod;
 var duration=period*1000,byBucket=Object.create(null);
 rows.slice(-3600).forEach(function(c){
  if(Array.isArray(c)&&c.length<5)return;
  if(!Array.isArray(c)&&(!c||typeof c!=='object'))return;
  var t,o,cl,h,l;
  if(Array.isArray(c)){t=sourceTime(c[0]);o=Number(c[1]);cl=Number(c[2]);h=Number(c[3]);l=Number(c[4]);}
  else{t=sourceTime(c.time??c.timestamp??c.t);o=Number(c.open??c.o);cl=Number(c.close??c.c);h=Number(c.high??c.h);l=Number(c.low??c.l);}
  if(t&&[o,cl,h,l].every(Number.isFinite)&&o>0&&cl>0&&h>0&&l>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl)){
   var bucket=Math.floor(t/duration)*duration;
   byBucket[String(bucket)]={time:bucket,open:o,close:cl,high:h,low:l};
  }
 });
 var candles=Object.keys(byBucket).map(Number).sort(function(a,b){return a-b;}).slice(-1800).map(function(k){return byBucket[String(k)];});
 if(candles.length){activePeriod=period;postPayload({type:'history',source:String(sourceEvent||'history/list/v2'),asset:asset,period:period,candles:candles},fp,size);}
}
function walk(v,inherited,depth,fp,size,sourceEvent){if(depth>10||v==null)return;if(Array.isArray(v)){if(v.length>=3&&typeof v[0]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(v[0])&&sourceTime(v[1])&&Number(v[2])>0){
 // Forward QX positional field 4 only when it is the validated -1/0/+1 direction enum.
 var qxDir=v.length>=4?directionOf(v[3]):null;
 send(v[2],v[0],v[1],qxDir,fp,size,sourceEvent);return;}v.forEach(function(x){walk(x,inherited,depth+1,fp,size,sourceEvent);});return;}if(typeof v!=='object')return;var a=assetOf(v)||inherited||activeAsset,p=num(v,['price','currentPrice','lastPrice','closePrice','quotePrice','value']),t=null;for(var k of ['timestamp','time','ts','created_at','at','serverTime'])if(v[k]!=null){t=sourceTime(v[k]);if(t)break;}if(p!==null&&a&&t){send(p,a,t,num(v,['tickDirection','direction','dir'])!==null?num(v,['tickDirection','direction','dir']):(v.side||null),fp,size,sourceEvent);return;}Object.keys(v).forEach(function(k){if(v[k]&&typeof v[k]==='object')walk(v[k],a,depth+1,fp,size,sourceEvent);});}
function handleData(o,eventName,fp,size){
 if(eventName==='history/list/v2'||eventName==='history/load'||eventName==='chart_notification/get'||eventName==='loadHistoryPeriod'){
  if(Array.isArray(o))sendHistory({asset:activeAsset,period:activePeriod,candles:o},fp,size,eventName);
  else sendHistory(o,fp,size,eventName);
  return;
 }
 if(eventName==='candle-generated'&&o&&typeof o==='object'){var ca=assetOf(o)||activeAsset,cp=num(o,['price','close']);var ct=null;for(var k of ['timestamp','time','ts'])if(o[k]!=null){ct=sourceTime(o[k]);if(ct)break;}if(cp!==null&&ca&&ct){send(cp,ca,ct,null,fp,size,eventName);return;}}
 walk(o,activeAsset,0,fp,size,eventName);
}
function bytesFingerprint(bytes){return crypto.subtle.digest('SHA-256',bytes).then(function(d){return Array.from(new Uint8Array(d)).map(x=>x.toString(16).padStart(2,'0')).join('');});}
function quoteAssetOf(value,depth){
 if(depth>7||value==null)return'';
 if(Array.isArray(value)){
  if(value.length>=3&&typeof value[0]==='string'&&/^[A-Z0-9_./-]{3,32}$/i.test(value[0])&&sourceTime(value[1])&&Number(value[2])>0)return value[0];
  for(var i=0;i<value.length;i++){var found=quoteAssetOf(value[i],depth+1);if(found)return found;}
 }else if(typeof value==='object'){
  var direct=assetOf(value);if(direct)return direct;
  var keys=Object.keys(value);for(var k=0;k<keys.length;k++){var nested=quoteAssetOf(value[keys[k]],depth+1);if(nested)return nested;}
 }
 return'';
}
function processSocketEvent(packet,fp,size){
 if(!Array.isArray(packet)||typeof packet[0]!=='string'){log('QX SOCKET EVENT SHAPE UNRECOGNIZED');return;}
 var eventName=packet[0],data=packet.length>1?packet[1]:null,a=assetOf(data);
 if(!a&&eventName==='quotes/stream')a=quoteAssetOf(data,0);
 jsonFrames++;
 recordFrameDiag('socket-event',size,eventName,data&&typeof data==='object'&&!Array.isArray(data)?Object.keys(data):[],'',{shape:valueShape(data,0),socketIoType:'EVENT',packetLength:packet.length});
 if(a&&(/^(quotes\/stream|depth\/change)$/.test(eventName)||/(subscribe|select|changeAsset|setAsset)/i.test(eventName))){activeAsset=a;log('QX EVENT '+eventName+' · '+a);}
 else log('QX EVENT '+eventName);
 // Do not whitelist event names: broker event names change. Only validated
 // timestamp+price or valid OHLC fields are forwarded by the parsers above.
 handleData(data,eventName,fp,size);
}
function decodeQxBinaryJson(bytes){
 if(!(bytes instanceof Uint8Array))return null;
 var start=bytes.length&&bytes[0]===4?1:0;
 if(start>=bytes.length)return null;
 try{return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes.subarray(start)));}catch(_){}
 return null;
}
function restoreBinaryPlaceholders(value,attachments){
 if(!value||typeof value!=='object')return value;
 if(value._placeholder===true&&Number.isInteger(value.num)&&value.num>=0&&value.num<attachments.length)return attachments[value.num];
 if(Array.isArray(value))return value.map(v=>restoreBinaryPlaceholders(v,attachments));
 Object.keys(value).forEach(k=>{value[k]=restoreBinaryPlaceholders(value[k],attachments);});
 return value;
}
function handleDecodedBinary(value,fp,size){
 recordFrameDiag('binary-json',size,'binary-json',value&&typeof value==='object'&&!Array.isArray(value)?Object.keys(value):[],'',{shape:valueShape(value,0),payloadType:Array.isArray(value)?'array':'object'});
 if(value&&typeof value==='object'&&!Array.isArray(value)&&
   (Array.isArray(value.history)||Array.isArray(value.candles)||
    (Array.isArray(value.data)&&assetOf(value)))){
  var eventName=Array.isArray(value.candles)||Array.isArray(value.history)?'history/list/v2':'history/load';
  log('QX BINARY JSON HISTORY · '+eventName+' · '+(assetOf(value)||'asset pending'));
  sendHistory(value,fp,size,eventName);return;
 }
 if(Array.isArray(value)&&typeof value[0]==='string'&&value.length<=3){processSocketEvent(value,fp,size);return;}
 if(Array.isArray(value)&&value.length&&value.every(function(c){return Array.isArray(c)&&c.length>=5&&sourceTime(c[0])&&[c[1],c[2],c[3],c[4]].every(function(n){return Number.isFinite(Number(n));});})){
  log('QX BINARY JSON CANDLE ARRAY · '+value.length+' rows');
  sendHistory({asset:activeAsset,period:activePeriod,candles:value},fp,size,'history/list/v2');return;
 }
 log('QX BINARY JSON DATA · '+(Array.isArray(value)?'array':'object')+' · '+valueShape(value,0));
 walk(value,activeAsset,0,fp,size,'quotes/stream');
}
async function parseFrame(raw){
 frames++;
 var bytes;
 if(typeof raw==='string')bytes=new TextEncoder().encode(raw);
 else if(raw instanceof ArrayBuffer)bytes=new Uint8Array(raw);
 else if(ArrayBuffer.isView(raw))bytes=new Uint8Array(raw.buffer,raw.byteOffset,raw.byteLength);
 else return;
 if(!window.crypto||!crypto.subtle)return;
 var fp=await bytesFingerprint(bytes),textFrame=typeof raw==='string'?raw:new TextDecoder('utf-8',{fatal:false}).decode(bytes),text=textFrame.trim();
 if(/^2(?:$|[^0-9])/.test(text)){log('QX ENGINE.IO SERVER PING');return;}
 if(/^3(?:$|[^0-9])/.test(text)){log('QX ENGINE.IO PONG FRAME RECEIVED');return;}
 try{
  // Socket.IO binary event: Engine.IO message(4) + Socket.IO type(5)
  // + attachment count + '-' + JSON header. Attachments follow as binary frames.
  var binaryHeader=text.match(/^4(5\d*)-(.*)$/s);
  if(binaryHeader){
   var count=parseInt(binaryHeader[1].slice(1),10),packet=JSON.parse(binaryHeader[2]);
   if(!Array.isArray(packet)||typeof packet[0]!=='string'||!Number.isInteger(count)||count<0||count>64)throw new Error('invalid binary event header');
   var headerEvent=packet[0],headerData=packet.length>1?packet[1]:null,headerPrefix=text.slice(0,Math.max(0,text.indexOf('['))),headerSafe=headerPrefix.match(/^4(5[0-9]*)-((?:\/[A-Za-z0-9_-]+,)?)([0-9]*)$/);
   recordFrameDiag('socketio-binary-header',bytes.length,headerEvent,headerData&&typeof headerData==='object'&&!Array.isArray(headerData)?Object.keys(headerData):[], '',{socketIoType:'BINARY_EVENT',attachments:count,shape:valueShape(headerData,0),headerBytes:bytes.length,namespace:headerSafe&&headerSafe[2]?headerSafe[2].replace(/[,/]/g,''):'default',ackId:headerSafe&&headerSafe[3]?headerSafe[3]:'none',socketIoHeaderPrefix:headerSafe?headerPrefix:'unparsed',attachmentBoundary:'header-then-'+count+'-binary-frame(s)'});
   if(count===0)processSocketEvent(packet,fp,bytes.length);
   else{pendingBinaryPacket={packet:packet,expected:count,attachments:[],fingerprint:fp,headerBytes:bytes.length,eventName:headerEvent,attachmentBytes:0};log('QX BINARY EVENT '+packet[0]+' · waiting '+count+' attachment(s)');}
   backend.fingerprint=fp;return;
  }
  // Binary attachment frames are paired in arrival order with the header.
  if(typeof raw!=='string'&&pendingBinaryPacket){
   var pending=pendingBinaryPacket,decodedAttachment=decodeQxBinaryJson(bytes);
   var attachmentIndex=pending.attachments.length+1,offset=pending.attachmentBytes;
   pending.attachments.push(decodedAttachment!==null?decodedAttachment:bytes);
   pending.attachmentBytes+=bytes.length;
   var approved=safeDiagEvent(pending.eventName),preview=approved?bytePreview(bytes):{hex:'',base64:'',previewBytes:0,truncated:false};
   recordFrameDiag(decodedAttachment!==null?'binary-attachment-json':'binary-attachment-opaque',bytes.length,pending.eventName,decodedAttachment&&typeof decodedAttachment==='object'&&!Array.isArray(decodedAttachment)?Object.keys(decodedAttachment):[],preview.hex,{shape:decodedAttachment!==null?valueShape(decodedAttachment,0):'opaque-binary',payloadType:decodedAttachment!==null?(Array.isArray(decodedAttachment)?'array':'object'):'binary',socketIoType:'BINARY_ATTACHMENT',attachmentIndex:attachmentIndex,attachments:pending.expected,offset:offset,headerBytes:pending.headerBytes,previewBytes:preview.previewBytes,base64:preview.base64,truncated:preview.truncated,rawPrefixCaptured:approved});
   log('QX BINARY ATTACHMENT '+attachmentIndex+'/'+pending.expected+' · '+bytes.length+' bytes · '+(decodedAttachment!==null?'JSON decoded':'opaque bytes')+' · '+valueShape(decodedAttachment,0));
   if(pending.attachments.length===pending.expected){
    pendingBinaryPacket=null;
    var restored=restoreBinaryPlaceholders(pending.packet,pending.attachments);
    processSocketEvent(restored,pending.fingerprint,pending.headerBytes+pending.attachmentBytes);
   }
   return;
  }
  if(typeof raw!=='string'){
   var binaryJson=decodeQxBinaryJson(bytes);
   if(binaryJson!==null){log('QX ENGINE.IO BINARY JSON DECODED · '+bytes.length+' bytes');handleDecodedBinary(binaryJson,fp,bytes.length);backend.fingerprint=fp;return;}
   recordFrameDiag('opaque-binary-unmatched',bytes.length,'unmatched',[],'',{payloadType:'unknown-binary',rawPrefixCaptured:false});
   log('QX OPAQUE BINARY FRAME · '+bytes.length+' bytes · raw preview withheld until event is identified');return;
  }
  if(text.startsWith('42'))text=text.slice(2);
  else if(text.charCodeAt(0)===4)text=text.slice(1);
  else if(text.startsWith('0')||text.startsWith('1')||text.startsWith('6'))return;
  var packet=JSON.parse(text);
  if(Array.isArray(packet)&&typeof packet[0]==='string')processSocketEvent(packet,fp,bytes.length);
  else{jsonFrames++;handleData(packet,'',fp,bytes.length);}
  backend.fingerprint=fp;
 }catch(e){log('QX FRAME PARSE '+(e&&e.message||e));}
}
try{if(NativeWS)window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){var ws=Reflect.construct(T,args,T);sockets++;var socketPingAt=0;function markServerPing(value){if(typeof value==='string'&&/^2(?:$|[^0-9])/.test(value.trim()))socketPingAt=performance.now();}try{var old=ws.send;ws.send=function(d){try{if(typeof d==='string'){var heartbeatText=d.trim();if(/^3(?:$|[^0-9])/.test(heartbeatText)&&socketPingAt>0){qxHeartbeatResponseMs=Math.max(0,performance.now()-socketPingAt);qxHeartbeatAt=Date.now();socketPingAt=0;log('QX ENGINE.IO PONG SEND (LOCAL) · '+qxHeartbeatResponseMs.toFixed(1)+' ms');postTelemetry({heartbeatSendMs:qxHeartbeatResponseMs,heartbeatAt:qxHeartbeatAt});}var q=d.startsWith('42')?d.slice(2):d,o=JSON.parse(q);if(Array.isArray(o)){var a=assetOf(o[1]);if(a&&/(asset|instrument|symbol|subscribe|stream|quote|change|price|tick|history)/i.test(o[0])){activeAsset=a;/* Keep the tracker signal clock fixed at the requested 10-second period. */}}}}catch(_){}return old.call(this,d);};}catch(_){}var socketFrameQueue=Promise.resolve();function enqueueSocketFrame(raw){socketFrameQueue=socketFrameQueue.then(async function(){var frame=raw;if(raw instanceof Blob){try{markServerPing(await raw.text());}catch(_){}frame=await raw.arrayBuffer();}else if(raw instanceof ArrayBuffer){try{markServerPing(new TextDecoder().decode(raw));}catch(_){}}else if(typeof raw==='string')markServerPing(raw);await parseFrame(frame);}).catch(function(e){log('QX FRAME QUEUE ERROR '+(e&&e.message||e));});}ws.addEventListener('open',()=>log('QX WEBSOCKET OPEN'));ws.addEventListener('message',e=>{if(typeof e.data==='string')markServerPing(e.data);enqueueSocketFrame(e.data);});ws.addEventListener('close',()=>log('QX WEBSOCKET CLOSED'));ws.addEventListener('error',()=>log('QX WEBSOCKET ERROR'));return ws;}});}catch(e){log('WEBSOCKET OBSERVER ERROR '+(e&&e.message||e));}
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
'#qh-copy{display:block;width:100%;margin-top:7px;padding:7px 5px;border:1px solid rgba(105,255,155,.65);border-radius:4px;background:rgba(14,68,35,.65);color:#9dffbc;font:900 9px monospace;letter-spacing:.45px;pointer-events:auto;touch-action:manipulation}'+
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
'<aside id="quant-hud" class="collapsed" aria-label="Quant core connection telemetry"><button type="button" id="qh-toggle" class="qh-toggle" aria-expanded="false"><span>QUANT CORE · LIVE</span><span id="qh-chevron">＋</span></button><div class="qh-body"><div class="qh-row"><span>ANDROID BRIDGE</span><b id="qh-cipher" class="qh-value qh-warn">LOCAL HANDSHAKE PENDING</b></div><div class="qh-row"><span>QX LOGIN</span><b id="qh-node" class="qh-value qh-warn">NOT VERIFIED</b></div><div class="qh-row"><span>FEED AGE</span><b id="qh-latency" class="qh-value">-- ms</b></div><div class="qh-row"><span>QX HTTP RTT</span><b id="qh-rtt" class="qh-value qh-warn">N/A</b></div><div class="qh-row"><span>ENGINE.IO PONG SEND (LOCAL)</span><b id="qh-heartbeat" class="qh-value qh-warn">N/A</b></div><div class="qh-row"><span>LIVE FEED</span><b id="qh-live" class="qh-value qh-warn">WAITING</b></div><div class="qh-row"><span>0x25 VECTOR</span><b id="qh-vector" class="qh-value">0.00</b></div><div class="qh-row"><span>HISTOGRAM</span><b id="qh-hist-value" class="qh-value">0.00</b></div><div class="qh-hist" id="qh-hist"></div><div class="qh-row"><span>OHLC MEMORY</span><b id="qh-ohlc" class="qh-value">--</b></div><div class="qh-row"><span>OHLC MEMORY WINDOW</span><b id="qh-cycle" class="qh-value">0/12</b></div><div class="qh-bar"><div id="qh-fill" class="qh-fill"></div></div><div class="qh-row"><span>NODE HASH</span><b id="qh-hash" class="qh-value">--</b></div><div class="qh-row"><span>SCAN RESULT</span><b id="qh-reason" class="qh-value">WAITING</b></div><div class="qh-scan-title">FULL CHART SCANNER</div><div class="qh-row"><span>MARKET PATTERN</span><b id="qh-pattern" class="qh-value">COLLECTING</b></div><div class="qh-row"><span>DIRECTION / PACE</span><b id="qh-direction" class="qh-value">-- / --</b></div><div class="qh-row"><span>STRUCTURE</span><b id="qh-structure" class="qh-value">--</b></div><div class="qh-row"><span>SCANNED CANDLES / TICKS</span><b id="qh-counts" class="qh-value">0 / 0</b></div><div class="qh-row"><span>SCAN TIME LAST / AVG</span><b id="qh-perf" class="qh-value">--</b></div><div class="qh-row"><span>SCANNER WITH SIGNAL</span><b id="qh-signal-scan" class="qh-value">WAITING</b></div><div class="qh-check" id="qh-check"></div><div id="qh-log" class="qh-log">Awaiting QX market data…</div><button type="button" id="qh-copy">COPY QX DIAGNOSTICS</button></div></aside>';
function el(id){return root.getElementById(id);}
var hud=el('quant-hud'),hudToggle=el('qh-toggle');
el('qh-copy').addEventListener('click',copyDiagnostics);
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
 var bridgeVerified=!!s.secureBridge,qxDataConfirmed=!!s.qxDataConfirmed&&live,hasMemory=Number(s.assetCandleCount)>0;
 el('qh-cipher').textContent=bridgeVerified?'LOCAL VERIFIED':'LOCAL HANDSHAKE PENDING';
 el('qh-cipher').className='qh-value '+(bridgeVerified?'qh-ok':'qh-warn');
 var loginState=String(s.qxLoginState||qxLoginState||'NOT VERIFIED');el('qh-node').textContent=loginState;el('qh-node').className='qh-value '+(loginState==='VERIFIED'?'qh-ok':'qh-warn');el('qh-node').title='Verified only when the live QX page exposes an explicit signed-in account control; market ticks alone do not prove login.';
 el('qh-latency').textContent=Number.isFinite(age)&&age>=0&&age<60000?Math.round(age)+' ms':'-- ms';
 var httpValue=Number(qxHttpRttMs!=null?qxHttpRttMs:(s.qxHttpRttMs!=null?s.qxHttpRttMs:s.qxHttpResponseMs)),httpAt=Number(qxHttpRttAt||s.qxHttpRttAt||s.qxHttpResponseAt);var httpFresh=httpAt>0&&Date.now()-httpAt<90000&&Number.isFinite(httpValue);el('qh-rtt').textContent=httpFresh?httpValue.toFixed(1)+' ms':'N/A';el('qh-rtt').className='qh-value '+(httpFresh?'qh-ok':'qh-warn');el('qh-rtt').title='QX-origin HTTP response time, measured by same-origin request; this is not quote-stream latency.';var hbValue=Number(qxHeartbeatResponseMs!=null?qxHeartbeatResponseMs:(s.qxHeartbeatSendMs!=null?s.qxHeartbeatSendMs:s.qxHeartbeatResponseMs)),hbAt=Number(qxHeartbeatAt||s.qxHeartbeatAt);var hbFresh=hbAt>0&&Date.now()-hbAt<90000&&Number.isFinite(hbValue);el('qh-heartbeat').textContent=hbFresh?hbValue.toFixed(1)+' ms':'N/A';el('qh-heartbeat').className='qh-value qh-warn';el('qh-heartbeat').title='Local time from server ping receipt to client pong send; not server-acknowledged RTT and not proof of QX login.';
 el('qh-live').textContent=qxDataConfirmed?'DATA LIVE':(live?'UNVERIFIED DATA':'WAITING');
 el('qh-live').className='qh-value '+(qxDataConfirmed?'qh-ok':'qh-warn');
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
 var scanner=s.scanner&&typeof s.scanner==='object'?s.scanner:{},signalScanner=s.signalScanner&&typeof s.signalScanner==='object'?s.signalScanner:scanner;
 el('qh-pattern').textContent=String(scanner.pattern||'COLLECTING');
 el('qh-direction').textContent=String(scanner.direction||'--')+' / '+String(scanner.pace||'--');
 el('qh-structure').textContent=String(scanner.structure||'--');
 el('qh-counts').textContent=(Number(scanner.candlesScanned)||0)+' / '+(Number(scanner.ticksScanned)||0);
 el('qh-perf').textContent=(Number(scanner.lastScanUs)||0).toFixed(0)+' / '+(Number(scanner.avgScanUs)||0).toFixed(0)+' µs';
 el('qh-signal-scan').textContent=String(signalScanner.pattern||'WAITING')+' · '+String(signalScanner.direction||'--')+' · '+String(signalScanner.pace||'--');
 el('qh-signal-scan').title=String(signalScanner.detail||'Scanner result captured alongside latest signal');
 var checks=[
  ['QX LIVE TICKS',live],
  ['ANDROID BRIDGE',bridgeVerified],
  ['QX MARKET DATA',qxDataConfirmed],
  ['OHLC MEMORY',hasMemory],
  ['OHLC + VECTOR FLOW',Number(s.assetCandleCount)>=3],
  ['SIGNAL ENGINE',qxDataConfirmed&&Number(s.assetCandleCount)>=3]
 ];
 el('qh-check').innerHTML=checks.map(function(item){return '<div><span>'+item[0]+'</span><b>'+(item[1]?'✓':'…')+'</b></div>';}).join('');
 var jitter=s.tickJitterMs==null?'--':Math.round(Number(s.tickJitterMs))+'ms',gaps=Number(s.tickGapCount)||0,ooo=Number(s.outOfOrderCount)||0,duplicates=Number(s.duplicateTimestampCount)||0,historyBad=Number(s.historyInvalid)||0,historyDup=Number(s.historyDuplicateBuckets)||0,historyGaps=Number(s.historyGapCount)||0;el('qh-log').textContent=!bridgeVerified?'[LOCK] local Android bridge initializing':
  (!qxDataConfirmed?'[WAIT] no verified QX market data received':
   (hasMemory?'[OK] QX market data · OHLC memory scan':'[OK] QX market ticks · loading OHLC memory')+' · jitter '+jitter+' · gaps '+gaps+' · out-of-order '+ooo+' · duplicate '+duplicates+' · OHLC invalid '+historyBad+' · OHLC duplicate '+historyDup+' · OHLC gaps '+historyGaps);
}
function render(s){var receivedAt=Number(s.lastTickReceivedAt)||Number(s.lastTickAt)||0;var age=receivedAt?Date.now()-receivedAt:Infinity,live=!!s.lastTickAt&&age>=-30000&&age<8000;el('status').textContent=live?'● QX DATA LIVE':(s.ticks?'● RECONNECTING':'WAITING FOR QX');el('status').style.color=live?'#7cffaa':'#ffd58a';el('market').textContent=(live?(s.asset||'MARKET'):(s.asset||'WAITING FOR QX'))+'  '+(s.price==null?'—':String(s.price));fitMarketText();var sig=live&&['CALL','PUT'].includes(s.signal)?s.signal:'WAIT';var count=Number(s.signalCount)||0,prob=Math.round(Number(s.signalProbability)||0);['wait','call','put'].forEach(function(id){el(id).className='sig'+(id.toUpperCase()===sig?' active '+id:'');el(id).textContent=id==='wait'?'WAIT':(id.toUpperCase()===sig&&prob>0?id.toUpperCase()+' '+prob+'/100':id.toUpperCase());});if(sig==='WAIT'){lastSignalCount=count;el('call').style.opacity='1';el('put').style.opacity='1';if(fadeTimer!==null){clearTimeout(fadeTimer);fadeTimer=null;}}else if(count!==lastSignalCount){lastSignalCount=count;el('call').style.opacity='1';el('put').style.opacity='1';var target=el(sig.toLowerCase());if(fadeTimer!==null)clearTimeout(fadeTimer);target.style.opacity='1';fadeTimer=setTimeout(function(){target.style.opacity='.68';fadeTimer=null;},450);}el('wins').innerHTML='WINS <b>'+(Number(s.wins)||0)+'</b>';el('losses').innerHTML='LOSSES <b>'+(Number(s.losses)||0)+'</b>';renderTelemetry(s,age,live);}
window.DRUpdate=function(s){if(!s||typeof s!=='object')return;lastState=Object.assign(lastState,s);lastState.qxHttpRttMs=qxHttpRttMs;lastState.qxHttpRttAt=qxHttpRttAt;lastState.qxHeartbeatResponseMs=qxHeartbeatResponseMs;lastState.qxHeartbeatAt=qxHeartbeatAt;backend.cycle++;backend.scanState=s.signal||'WAIT';backend.latency=s.lastTickAt?Math.max(0,Date.now()-Number(s.lastTickAt)):null;if(s.fingerprint)backend.fingerprint=s.fingerprint;render(lastState);};
window.DRSetPublicKey=async function(encoded){try{if(!window.crypto||!crypto.subtle)throw new Error('WebCrypto unavailable');var b=atob(encoded),bytes=new Uint8Array(b.length);for(var i=0;i<b.length;i++)bytes[i]=b.charCodeAt(i);nativePublicKey=await crypto.subtle.importKey('spki',bytes,{name:'RSA-OAEP',hash:'SHA-256'},false,['encrypt']);aesKey=await crypto.subtle.generateKey({name:'AES-GCM',length:256},true,['encrypt','decrypt']);var raw=await crypto.subtle.exportKey('raw',aesKey);wrappedAesKey=b64(await crypto.subtle.encrypt({name:'RSA-OAEP'},nativePublicKey,raw));if(!window.DRSecure||typeof window.DRSecure.postMessage!=='function')throw new Error('Native secure channel unavailable');var probe=await makeEnvelope({type:'crypto_probe',suite:'RSA-OAEP-256/AES-256-GCM'});window.DRSecure.postMessage(JSON.stringify(probe));backend.cipher='HANDSHAKE SENT';log('LOCAL ANDROID BRIDGE ENCRYPTION TEST sent to native verifier; this does not verify QX login or QX encryption');}catch(e){backend.cipher='LOCKED';log('LOCAL CRYPTO INIT FAILED '+(e&&e.message||e));}};
window.DRBack=function(){return false;};
async function probeQxHttpRtt(){if(qxRttProbeBusy||document.visibilityState==='hidden'||!window.fetch||!window.AbortController||location.origin!=='https://market-qx.trade')return;qxRttProbeBusy=true;var controller=new AbortController(),timer=setTimeout(function(){controller.abort();},8000),started=performance.now();try{var url=new URL('/en/trade',location.origin);url.searchParams.set('_dr_http_probe',String(Date.now()));var response=await fetch(url.href,{method:'GET',cache:'no-store',credentials:'include',mode:'same-origin',redirect:'follow',signal:controller.signal});var elapsed=performance.now()-started;if(response&&response.type!=='opaque'&&response.url.indexOf(location.origin)===0){qxHttpRttSamples.push(elapsed);if(qxHttpRttSamples.length>5)qxHttpRttSamples.shift();var sortedRtt=qxHttpRttSamples.slice().sort(function(a,b){return a-b;});qxHttpRttMs=sortedRtt[Math.floor(sortedRtt.length/2)];qxHttpRttAt=Date.now();lastState.qxHttpRttMs=qxHttpRttMs;lastState.qxHttpRttAt=qxHttpRttAt;log('QX ORIGIN HTTP RESPONSE · '+Math.round(elapsed)+' ms · HTTP '+response.status);postTelemetry({httpResponseMs:qxHttpRttMs,httpResponseAt:qxHttpRttAt});render(lastState);}else log('QX origin HTTP response timing unavailable');}catch(e){log('QX origin HTTP probe failed · '+(e&&e.name||'network error'));}finally{clearTimeout(timer);qxRttProbeBusy=false;}}
function visibleElement(elm){return !!(elm&&elm.getClientRects&&elm.getClientRects().length&&getComputedStyle(elm).visibility!=='hidden'&&getComputedStyle(elm).display!=='none');}
function probeQxLoginState(){if(location.origin!=='https://market-qx.trade'||!document.body)return;var logout=document.querySelector('a[href*="logout" i],a[href*="signout" i],button[data-testid*="logout" i],[data-testid*="sign-out" i]');var explicitAccount=document.querySelector('[data-testid*="user-menu" i],[data-testid*="account-menu" i],[aria-label*="account menu" i]');var loginControls=Array.from(document.querySelectorAll('a,button')).filter(function(x){return visibleElement(x)&&/^(log in|login|sign in|sign up|register)$/i.test((x.innerText||x.getAttribute('aria-label')||'').trim());});var next=visibleElement(logout)||visibleElement(explicitAccount)?'VERIFIED':(loginControls.length?'LOGIN REQUIRED':'NOT VERIFIED');qxLoginState=next;if(next!==qxLoginLastSent){qxLoginLastSent=next;postTelemetry({qxLoginState:next,qxLoginAt:Date.now()});log('QX ACCOUNT STATE · '+next);}}
log('COMPACT AADI INTERFACE READY · NATIVE SIGNAL ENGINE ACTIVE');reserveTopSpace();render(lastState);setTimeout(probeQxHttpRtt,2500);setInterval(probeQxHttpRtt,30000);probeQxLoginState();setInterval(probeQxLoginState,2500);setInterval(()=>{reserveTopSpace();render(lastState);},1000);
})();