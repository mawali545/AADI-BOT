(function () {
'use strict';
if (window.__DR_CANDLE_TRACKER__) return;
window.__DR_CANDLE_TRACKER__ = true;
var state = {ticks:0, asset:'WAITING FOR MARKET', price:null, lastTickAt:0, connected:false, ohlc:[], logs:[]};
var nativeWS = window.WebSocket, sockets = 0, rxFrames = 0, emitted = 0, activeAsset = '';
function log(s) { try { if (window.DRNative) DRNative.onSocket(String(s)); } catch(e) {} }
function send(price, asset, timestamp) {
  price = Number(price); asset = String(asset || activeAsset || '');
  if (!Number.isFinite(price) || price <= 0 || !asset) return;
  var ts = Number(timestamp) || Date.now()/1000;
  if (ts > 1e12) ts /= 1000;
  if (ts < Date.now()/1000-90 || ts > Date.now()/1000+15) ts = Date.now()/1000;
  try { DRNative.onTick(JSON.stringify({price:price, asset:asset, timestamp:ts})); emitted++; }
  catch(e) {}
}
function assetOf(o) {
  if (!o || typeof o !== 'object') return '';
  for (var k of ['asset','symbol','pair','instrument','assetName','ticker','active']) {
    if (typeof o[k] === 'string' && o[k].length < 40) return o[k];
  }
  return '';
}
function walk(x, depth) {
  if (depth > 12 || x == null) return;
  if (Array.isArray(x)) {
    if (x.length >= 3 && typeof x[0] === 'string' && Number(x[1]) > 1000000000 && Number(x[2]) > 0) {
      send(x[2], x[0], x[1]); return;
    }
    if (x.length >= 2 && Number(x[0]) > 1000000000 && Number(x[1]) > 0) {
      send(x[1], activeAsset, x[0]); return;
    }
    for (var v of x) walk(v, depth+1);
    return;
  }
  if (typeof x !== 'object') return;
  var a = assetOf(x) || activeAsset;
  var t = x.timestamp || x.time || x.ts || x.created_at || x.at;
  for (var k of ['price','currentPrice','lastPrice','closePrice','rate','quote','value','close']) {
    var v = x[k];
    if ((typeof v === 'number' || typeof v === 'string') && Number(v) > 0) { send(v,a,t); return; }
  }
  Object.keys(x).forEach(function(k){ if (x[k] && typeof x[k] === 'object') walk(x[k],depth+1); });
}
function parse(data) {
  try {
    var s = String(data).trim();
    if (s.charAt(0) === '4' && s.charAt(1) === '2') s = s.slice(2);
    if (s.charCodeAt(0) === 4) s = s.slice(1);
    var o = JSON.parse(s);
    if (Array.isArray(o) && typeof o[0] === 'string') {
      var a = assetOf(o[1]);
      if (a && /(instrument|asset|symbol|subscribe|stream|quote|change)/i.test(o[0])) {
        activeAsset = a; log('Market asset detected: ' + a);
      }
      if (!/(history|candle|ohlc)/i.test(o[0])) walk(o[1],0);
    } else walk(o,0);
  } catch(e) {}
}
try {
  window.WebSocket = new Proxy(nativeWS, {
    construct: function(T,args) {
      var ws = Reflect.construct(T,args,T); sockets++;
      var oldSend = ws.send.bind(ws);
      ws.send = function(data) {
        try {
          if (typeof data === 'string') {
            var q = data.startsWith('42') ? data.slice(2) : data;
            var o = JSON.parse(q);
            if (Array.isArray(o)) { var a=assetOf(o[1]); if(a && /(instrument|asset|symbol|subscribe|stream|quote|change)/i.test(String(o[0]))) { activeAsset=a; log('Selected market: '+a); } }
          }
        } catch(e) {}
        return oldSend(data);
      };
      ws.addEventListener('open',function(){log('MARKET WEBSOCKET OPEN • '+sockets);});
      ws.addEventListener('message',function(ev){
        rxFrames++;
        if (typeof ev.data === 'string') parse(ev.data);
        else if (ev.data instanceof Blob) ev.data.text().then(parse).catch(function(){});
        else if (ev.data instanceof ArrayBuffer) { try { parse(new TextDecoder().decode(ev.data)); } catch(e) {} }
        if(rxFrames===1 || rxFrames%50===0) log('FRAME RX '+rxFrames+' • REAL TICKS '+emitted);
      });
      ws.addEventListener('close',function(){log('MARKET WEBSOCKET CLOSED');});
      ws.addEventListener('error',function(){log('MARKET WEBSOCKET ERROR');});
      return ws;
    }
  });
  log('DR WEBSOCKET OBSERVER INSTALLED');
} catch(e) { log('WEBSOCKET HOOK ERROR'); }

var css = `
#dr-bubble{position:fixed;z-index:2147483646;right:14px;top:44%;width:58px;height:58px;border-radius:50%;border:1px solid #9affb2;background:radial-gradient(circle at 35% 25%,#244f38,#06130c 72%);box-shadow:0 0 9px #20ff65,0 0 25px #0bff5b88;display:flex;align-items:center;justify-content:center;color:#8cffaa;font:900 14px Arial;letter-spacing:1px;cursor:pointer;user-select:none;animation:drpulse 2.1s infinite}
@keyframes drpulse{0%,100%{box-shadow:0 0 8px #20ff65,0 0 22px #0bff5b66}50%{box-shadow:0 0 15px #20ff65,0 0 36px #0bff5baa}}
#dr-mini{position:fixed;z-index:2147483646;right:14px;top:calc(44% + 68px);width:206px;padding:12px;background:#06110df2;border:1px solid #42ff7c;border-radius:9px;box-shadow:0 0 18px #00ff5b55;color:#a5ffbd;font:11px monospace;display:none}
#dr-mini .dr-head{font-weight:bold;font-size:14px;letter-spacing:2px;color:#69ff91;margin-bottom:8px}
#dr-mini .dr-lock{border:1px solid #286c42;padding:7px;margin-top:8px;text-align:center;color:#8affaa}
#dr-panel{position:fixed;inset:0;z-index:2147483647;background:#020805;color:#76ff9d;font:12px/1.35 monospace;display:none;overflow:auto;box-sizing:border-box;padding:15px 13px 28px}
#dr-panel *{box-sizing:border-box}
#dr-panel .dr-top{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #1c6837;padding:3px 0 12px;margin-bottom:12px}
#dr-panel .dr-brand{font-size:20px;letter-spacing:2px;font-weight:900;text-shadow:0 0 12px #22ff66}
#dr-panel .dr-sub{font-size:9px;color:#42a765;letter-spacing:2px;margin-top:3px}
#dr-panel .dr-close{background:#07180e;color:#81ffa3;border:1px solid #2bc75b;border-radius:4px;padding:7px 11px;font:12px monospace}
#dr-panel .dr-status{border:1px solid #195d32;background:#04120a;padding:9px;margin-bottom:10px;display:flex;justify-content:space-between;gap:8px}
#dr-panel .dr-dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#53665a;margin-right:6px}
#dr-panel .dr-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}
#dr-panel .dr-box{border:1px solid #17572f;background:linear-gradient(145deg,#06150b,#020905);padding:10px;min-height:74px;box-shadow:inset 0 0 15px #0bff5b08}
#dr-panel .dr-box.wide{grid-column:1/-1}
#dr-panel .dr-label{color:#48b86c;font-size:9px;letter-spacing:1.4px;margin-bottom:8px}
#dr-panel .dr-value{font-size:15px;color:#9affb3;font-weight:bold;overflow-wrap:anywhere}
#dr-panel .dr-small{font-size:10px;color:#5fbb7a;margin-top:5px}
#dr-panel .dr-bar{height:5px;background:#102c1a;margin-top:9px;overflow:hidden}
#dr-panel .dr-bar i{display:block;width:0;height:100%;background:#45ff78;box-shadow:0 0 8px #36ff6b;transition:width .3s}
#dr-panel .dr-terminal{height:130px;overflow:auto;background:#010402;border:1px solid #164c2a;padding:8px;color:#63d987;font-size:10px;white-space:pre-wrap}
#dr-panel .dr-tabs{display:flex;gap:6px;margin:12px 0}
#dr-panel .dr-tab{flex:1;background:#06150b;color:#54b873;border:1px solid #1a5d32;padding:9px 2px;font:10px monospace}
#dr-panel .dr-tab.active{color:#9affb3;border-color:#5aff85;box-shadow:0 0 8px #28ff6444}
#dr-panel .dr-check{padding:7px 0;border-bottom:1px solid #10351d;color:#71c98a}
#dr-panel .dr-check b{color:#a0ffb8;font-weight:normal}
#dr-panel .dr-check em{float:right;color:#579b6a;font-style:normal}
#dr-panel .dr-foot{color:#36764b;text-align:center;font-size:9px;margin:14px 0 4px;letter-spacing:1px}
`;
var style=document.createElement('style'); style.textContent=css; (document.head||document.documentElement).appendChild(style);
var bubble=document.createElement('div'); bubble.id='dr-bubble'; bubble.textContent='DR';
var mini=document.createElement('div'); mini.id='dr-mini'; mini.innerHTML='<div class="dr-head">NEXT / QUANT SIGNALS</div><div>MARKET: <span id="dr-m-asset">WAITING</span></div><div>PRICE: <span id="dr-m-price">--</span></div><div class="dr-lock">◈ LOCKED • TRACKER STANDBY</div><div style="margin-top:8px;text-align:center;color:#63c881">TAP DR TO OPEN TRACKER</div>';
var panel=document.createElement('div'); panel.id='dr-panel';
panel.innerHTML='<div class="dr-top"><div><div class="dr-brand">DR CANDLE TRACKER</div><div class="dr-sub">REAL MARKET STREAM • LIVE DIAGNOSTICS</div></div><button class="dr-close" id="dr-close">CLOSE ✕</button></div>'+
'<div class="dr-status"><span><i class="dr-dot" id="dr-dot"></i><span id="dr-connection">WAITING FOR REAL FEED</span></span><span id="dr-clock">--:--:--</span></div>'+
'<div class="dr-grid">'+
'<div class="dr-box"><div class="dr-label">MEMORY STREAM / OHLC</div><div class="dr-value" id="dr-ohlc">O -- H -- L -- C --</div><div class="dr-small" id="dr-ohlc-sub">Awaiting genuine ticks</div></div>'+
'<div class="dr-box"><div class="dr-label">LIVE FEED</div><div class="dr-value" id="dr-price">--</div><div class="dr-small" id="dr-asset">NO ASSET SELECTED</div></div>'+
'<div class="dr-box"><div class="dr-label">ANIMATED BUFFER</div><div class="dr-value" id="dr-buffer">0 / 60</div><div class="dr-bar"><i id="dr-buffer-bar"></i></div></div>'+
'<div class="dr-box"><div class="dr-label">VECTOR</div><div class="dr-value" id="dr-vector">UNRESOLVED</div><div class="dr-small">Derived only from received stream</div></div>'+
'<div class="dr-box"><div class="dr-label">RSA CIPHER</div><div class="dr-value">NOT INITIALIZED</div><div class="dr-small">No encryption claim / no key configured</div></div>'+
'<div class="dr-box"><div class="dr-label">LATENCY</div><div class="dr-value" id="dr-latency">-- ms</div><div class="dr-small">Local receive timing</div></div>'+
'<div class="dr-box"><div class="dr-label">BREACH NODE</div><div class="dr-value" id="dr-breach">IDLE</div><div class="dr-small">Connection integrity monitor</div></div>'+
'<div class="dr-box"><div class="dr-label">CYCLE PROGRESS</div><div class="dr-value" id="dr-cycle">0%</div><div class="dr-bar"><i id="dr-cycle-bar"></i></div></div>'+
'<div class="dr-box wide"><div class="dr-label">TERMINAL EVENT LOGS</div><div class="dr-terminal" id="dr-terminal">[BOOT] DR CANDLE TRACKER INITIALIZED\n[FEED] Waiting for Market-QX WebSocket frames...</div></div>'+
'<div class="dr-box wide"><div class="dr-label">TRACKING SEQUENCE / SYSTEM CHECKLIST</div>'+
'<div class="dr-check">01 / WebSocket observer <em id="dr-c1">WAITING</em></div>'+
'<div class="dr-check">02 / Market asset identification <em id="dr-c2">WAITING</em></div>'+
'<div class="dr-check">03 / Genuine price tick reception <em id="dr-c3">WAITING</em></div>'+
'<div class="dr-check">04 / OHLC memory accumulation <em id="dr-c4">WAITING</em></div>'+
'<div class="dr-check">05 / Stream continuity <em id="dr-c5">WAITING</em></div>'+
'<div class="dr-check">06 / Signal analysis <em id="dr-c6">LOCKED</em></div></div></div>'+
'<div class="dr-tabs"><button class="dr-tab active" data-tab="TRACKER">TRACKER</button><button class="dr-tab" data-tab="FEED">LIVE FEED</button><button class="dr-tab" data-tab="LOGS">EVENT LOGS</button></div>'+
'<div class="dr-foot">DR SYSTEM • REAL DATA ONLY • NO DEMO TICKS</div>';
document.documentElement.appendChild(bubble); document.documentElement.appendChild(mini); document.documentElement.appendChild(panel);
var expanded=false, miniOpen=false, lastPrice=null, previousPrice=null, ohlc=null, tickN=0, logLines=[];
function by(id){return document.getElementById(id);}
function addLog(s){var line='['+new Date().toLocaleTimeString()+'] '+String(s);logLines.push(line);if(logLines.length>80)logLines.shift();if(by('dr-terminal'))by('dr-terminal').textContent=logLines.join('\n');}
function setText(id,v){var el=by(id);if(el)el.textContent=v;}
function render(s){
  if(s && typeof s.ticks==='number')tickN=s.ticks;
  if(s && s.asset)state.asset=s.asset;
  if(s && s.price!==null && s.price!==undefined){previousPrice=lastPrice;lastPrice=Number(s.price);state.price=lastPrice;var sec=Math.floor(Date.now()/60000);if(!ohlc || ohlc.sec!==sec)ohlc={sec:sec,o:lastPrice,h:lastPrice,l:lastPrice,c:lastPrice};else{ohlc.h=Math.max(ohlc.h,lastPrice);ohlc.l=Math.min(ohlc.l,lastPrice);ohlc.c=lastPrice;}}
  state.lastTickAt=s && s.lastTickAt || state.lastTickAt;
  state.connected=!!(state.lastTickAt && Date.now()-state.lastTickAt<8000);
  var priceText=lastPrice===null?'--':lastPrice.toFixed(6);
  setText('dr-price',priceText);setText('dr-m-price',priceText);
  setText('dr-asset',state.asset);setText('dr-m-asset',state.asset);
  setText('dr-buffer',Math.min(tickN,60)+' / 60');if(by('dr-buffer-bar'))by('dr-buffer-bar').style.width=(Math.min(tickN,60)/60*100)+'%';
  setText('dr-cycle',Math.floor((tickN%60)/60*100)+'%');if(by('dr-cycle-bar'))by('dr-cycle-bar').style.width=((tickN%60)/60*100)+'%';
  setText('dr-ohlc',ohlc?'O '+ohlc.o+' H '+ohlc.h+' L '+ohlc.l+' C '+ohlc.c:'O -- H -- L -- C --');
  setText('dr-ohlc-sub',ohlc?'Local tick bucket • '+tickN+' real ticks':'Awaiting genuine ticks');
  setText('dr-vector',previousPrice===null?'UNRESOLVED':lastPrice>previousPrice?'UP TICK':lastPrice<previousPrice?'DOWN TICK':'FLAT TICK');
  setText('dr-latency',state.lastTickAt?Math.max(0,Date.now()-state.lastTickAt)+' ms':'-- ms');
  setText('dr-connection',state.connected?'LIVE STREAM RECEIVING':'WAITING / FEED STALE');
  if(by('dr-dot'))by('dr-dot').style.background=state.connected?'#4cff78':'#53665a';
  setText('dr-breach',state.connected?'MONITORING':'NO LIVE LINK');
  setText('dr-c1',s&&s.observer?'ACTIVE':'INSTALLED');
  setText('dr-c2',state.asset!=='WAITING FOR MARKET'?'DETECTED':'WAITING');
  setText('dr-c3',tickN>0?'RECEIVING':'WAITING');
  setText('dr-c4',tickN>0?'BUILDING':'WAITING');
  setText('dr-c5',state.connected?'STABLE':'CHECKING');
  setText('dr-clock',new Date().toLocaleTimeString());
}
bubble.addEventListener('click',function(){miniOpen=!miniOpen;mini.style.display=miniOpen?'block':'none';if(expanded){panel.style.display='none';expanded=false;}});
mini.addEventListener('click',function(){expanded=true;mini.style.display='none';panel.style.display='block';miniOpen=false;});
by('dr-close').addEventListener('click',function(){panel.style.display='none';expanded=false;});
panel.querySelectorAll('.dr-tab').forEach(function(b){b.addEventListener('click',function(){panel.querySelectorAll('.dr-tab').forEach(x=>x.classList.remove('active'));b.classList.add('active');addLog('NAVIGATION • '+b.dataset.tab+' view selected');});});
window.DRUpdate=function(s){render(s||{});};
window.DRLog=function(s){addLog(s);if(/WEBSOCKET OBSERVER INSTALLED/.test(s))setText('dr-c1','ACTIVE');};
window.DRBack=function(){if(expanded){panel.style.display='none';expanded=false;}else if(miniOpen){mini.style.display='none';miniOpen=false;}else{history.back();}};
setInterval(function(){if(state.lastTickAt){state.connected=Date.now()-state.lastTickAt<8000;setText('dr-connection',state.connected?'LIVE STREAM RECEIVING':'WAITING / FEED STALE');if(by('dr-dot'))by('dr-dot').style.background=state.connected?'#4cff78':'#53665a';}},1000);
log('DR tracker overlay injected • genuine data only');
})();