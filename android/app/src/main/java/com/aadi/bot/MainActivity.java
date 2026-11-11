package com.aadi.bot;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.text.TextUtils;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private TextView status, lastTick, signalView, diagnosticView;
    private Button activateButton;
    private boolean botActive = false;
    private SignalEngine engine = new SignalEngine();
    private WebView web;
    private long receivedTicks = 0;
    private volatile long lastUiTickAt = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable signalColorReset;
    private long lastSignalFlashBucket = Long.MIN_VALUE;
    private long signalFlashUntil = 0;
    private String lastSignalFlashAsset = "";

    private static final String MARKET_URL = "https://market-qx.trade/en/trade";
    private static final String HOOK =
        "(function(){'use strict';" +
        "if(window.__AADI_HOOKED__)return;window.__AADI_HOOKED__=true;" +
        "const NativeWS=window.WebSocket;const seen=new Set();let diagCount=0,historySent=0;" +
        "function emit(price,asset,time){" +
        "price=Number(price);if(!Number.isFinite(price)||price<=0||!asset)return;" +
        "const feedTime=normTime(time);try{AadiNative.onTick(JSON.stringify({price:price,asset:String(asset),timestamp:feedTime||0}));}catch(e){}" +
        "requestLatestHistory(String(asset),feedTime||Date.now()/1000);" +
        "}" +
        "function key(o,names){for(const n of names){if(o[n]!==undefined&&o[n]!==null)return o[n];}return null;}" +
        "function normTime(v){let n=Number(v);if(!Number.isFinite(n)||n<=0)return 0;if(n>1e12)n/=1000;return n;}" +
        "function findHistory(root,assetHint){" +
        "const found=[];const visited=new Set();" +
        "function walk(x,asset,d){if(!x||typeof x!=='object'||d>9||found.length>=700||visited.has(x))return;visited.add(x);" +
        "if(Array.isArray(x)){if(x.length&&x[0]&&typeof x[0]==='object'&&!Array.isArray(x[0])){" +
        "const rows=[];for(const r of x){if(!r||typeof r!=='object'||Array.isArray(r))continue;" +
        "const o=key(r,['open','o']),h=key(r,['high','h']),l=key(r,['low','l']),c=key(r,['close','c']);" +
        "const t=normTime(key(r,['timestamp','time','ts','date','at']));" +
        "if(t&&[o,h,l,c].every(v=>v!==null&&Number.isFinite(Number(v))))rows.push({timestamp:t,open:Number(o),high:Number(h),low:Number(l),close:Number(c)});" +
        "}if(rows.length>=30){const sig=rows.slice(0,3).map(z=>z.timestamp).join(',')+'|'+rows.length;if(!seen.has('hist:'+sig)){seen.add('hist:'+sig);found.push({asset:asset||assetHint||'UNKNOWN',candles:rows});}}}" +
        "for(const v of x)if(v&&typeof v==='object')walk(v,asset,d+1);return;}" +
        "const a=key(x,['asset','symbol','pair','instrument','assetName','ticker'])||asset;" +
        "for(const v of Object.values(x))if(v&&typeof v==='object')walk(v,a,d+1);" +
        "}walk(root,assetHint,0);return found;}" +
        "let activeAsset=null,historyWs=null,historyPages=0,historyOldest=0,historyTarget=0,lastLiveHistoryBucket=0;\n" +
"        function requestHistory(asset,anchor){\n" +
"          if(!asset||!historyWs||historyPages>=64||loginState!==true)return;\n" +
"          const payload={asset:String(asset),index:Math.floor(Date.now()/10)+historyPages,offset:3600,period:10,time:Math.floor(anchor||Date.now()/1000)};\n" +
"          try{historyWs.send('42[\"history/load\",'+JSON.stringify(payload)+']');historyPages++;}catch(e){}\n" +
"        }\n" +
"        function requestLatestHistory(asset,anchor){\n" +
"          const bucket=Math.floor(Number(anchor||Date.now()/1000)/10);if(!asset||!historyWs||loginState!==true||bucket<=lastLiveHistoryBucket)return;\n" +
"          lastLiveHistoryBucket=bucket;const payload={asset:String(asset),index:Math.floor(Date.now()/10)+historyPages+bucket,offset:180,time:Math.floor(Number(anchor)||Date.now()/1000),period:10};\n" +
"          try{historyWs.send('42[\"history/load\",'+JSON.stringify(payload)+']');}catch(e){}\n" +
"        }\n" +
"        function collectHistory(root,assetHint){\n" +
"          const out=[],visited=new Set();\n" +
"          function walk(x,asset,d){\n" +
"            if(!x||typeof x!=='object'||d>10||visited.has(x))return;visited.add(x);\n" +
"            if(Array.isArray(x)){\n" +
"              if(x.length&&Array.isArray(x[0])){\n" +
"                const rows=[];\n" +
"                for(const r of x){if(!Array.isArray(r)||r.length<5)continue;\n" +
"                  const t=normTime(r[0]),o=Number(r[1]),cl=Number(r[2]),h=Number(r[3]),l=Number(r[4]);\n" +
"                  if(t&&[o,cl,h,l].every(Number.isFinite)&&o>0&&cl>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl)&&l>0&&h>=l)rows.push({timestamp:t,open:o,high:h,low:l,close:cl});\n" +
"                }\n" +
"                if(rows.length)out.push({asset:asset||assetHint||activeAsset||'UNKNOWN',candles:rows});\n" +
"              }\n" +
"              for(const v of x)if(v&&typeof v==='object')walk(v,asset,d+1);return;\n" +
"            }\n" +
"            const a=key(x,['asset','symbol','pair','instrument','assetName','ticker'])||asset;\n" +
"            for(const name of ['data','candles']){\n" +
"              const arr=x[name];if(!Array.isArray(arr))continue;\n" +
"              const rows=[];\n" +
"              for(const r of arr){\n" +
"                if(Array.isArray(r)&&r.length>=5){\n" +
"                  const t=normTime(r[0]),o=Number(r[1]),cl=Number(r[2]),h=Number(r[3]),l=Number(r[4]);\n" +
"                  if(t&&[o,cl,h,l].every(Number.isFinite)&&o>0&&cl>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl)&&l>0&&h>=l)rows.push({timestamp:t,open:o,high:h,low:l,close:cl});\n" +
"                }else if(r&&typeof r==='object'){\n" +
"                  const t=normTime(key(r,['timestamp','time','ts'])),o=Number(key(r,['open','o'])),h=Number(key(r,['high','h'])),l=Number(key(r,['low','l'])),cl=Number(key(r,['close','c']));\n" +
"                  if(t&&[o,h,l,cl].every(Number.isFinite)&&o>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl)&&l>0&&h>=l)rows.push({timestamp:t,open:o,high:h,low:l,close:cl});\n" +
"                }\n" +
"              }\n" +
"              // Aggregate genuine Market-QX history ticks into 10-second OHLC bars.\n" +
"              // Derived only from received exchange prices; never demo data.\n" +
"              if(rows.length===0 && name==='data'){\n" +
"                const buckets=new Map();\n" +
"                for(const r of arr){\n" +
"                  if(!r||typeof r!=='object'||Array.isArray(r))continue;\n" +
"                  const t=normTime(key(r,['time','timestamp','ts','created_at']));\n" +
"                  const p=Number(key(r,['price','rate','quote','value']));\n" +
"                  if(!t||!Number.isFinite(p)||p<=0)continue;\n" +
"                  const bucket=Math.floor(t/10)*10;let bar=buckets.get(bucket);\n" +
"                  if(!bar){bar={timestamp:bucket,open:p,high:p,low:p,close:p};buckets.set(bucket,bar);}\n" +
"                  else{bar.high=Math.max(bar.high,p);bar.low=Math.min(bar.low,p);bar.close=p;}\n" +
"                }\n" +
"                for(const bar of buckets.values())rows.push(bar);\n" +
"              }\n" +
"              if(rows.length)out.push({asset:a||assetHint||activeAsset||'UNKNOWN',candles:rows});\n" +
"            }\n" +
"            for(const v of Object.values(x))if(v&&typeof v==='object')walk(v,a,d+1);\n" +
"          }\n" +
"          walk(root,assetHint,0);return out;\n" +
"        }\n" +
"        function process(o){\n" +
"          try{\n" +
"            const event=Array.isArray(o)&&typeof o[0]==='string'?o[0]:'';\n" +
"            const payload=event?o[1]:o;\n" +
"            if(event==='history/load'){\n" +
"              const packets=collectHistory(payload,activeAsset);\n" +
"              let oldest=Number.POSITIVE_INFINITY,total=0;\n" +
"              for(const p of packets){if(p.asset==='UNKNOWN')continue;total+=p.candles.length;\n" +
"                for(const row of p.candles)oldest=Math.min(oldest,row.timestamp);\n" +
"                try{AadiNative.onHistory(JSON.stringify(p));}catch(e){}\n" +
"              }\n" +
"              if(total&&Number.isFinite(oldest)){\n" +
"                const next=oldest<historyOldest||historyOldest===0?oldest:historyOldest-3600;\n" +
"                historyOldest=next;\n" +
"                if(oldest>historyTarget&&historyPages<64)setTimeout(function(){requestHistory(activeAsset,next);},350);\n" +
"              }\n" +
"            }\n" +
"            if(event==='history'||event==='candles/load'){const packets=findHistory(payload,activeAsset);\n" +
"            for(const p of packets){if(p.asset==='UNKNOWN')continue;historySent++;try{AadiNative.onHistory(JSON.stringify(p));}catch(e){}}}\n" +
"          }catch(e){}\n" +
"        }\n" +

        "function safeShape(root){" +
        "const out=[];function walk(x,p,d){if(d>7||x==null||out.length>=12)return;" +
        "if(Array.isArray(x)){if(x.length&&typeof x[0]==='object'&&x[0]!==null&&!Array.isArray(x[0])){const k=Object.keys(x[0]).slice(0,24);const low=k.map(z=>z.toLowerCase());if(low.some(z=>['open','o'].includes(z))&&low.some(z=>['high','h'].includes(z))&&low.some(z=>['low','l'].includes(z))&&low.some(z=>['close','c'].includes(z)))out.push(p+': object-candle-array len='+x.length+' fields='+k.join(','));}" +
        "else if(x.length&&Array.isArray(x[0])){const n=x[0].length;const types=x[0].slice(0,8).map(v=>typeof v).join(',');if(n>=4)out.push(p+': nested-array len='+x.length+' rowLen='+n+' firstTypes='+types);}" +
        "for(let i=0;i<Math.min(x.length,4)&&out.length<12;i++)if(x[i]&&typeof x[i]==='object')walk(x[i],p+'['+i+']',d+1);return;}" +
        "if(typeof x!=='object')return;const keys=Object.keys(x).slice(0,30);const low=keys.map(z=>z.toLowerCase());" +
        "if(low.some(z=>['open','o'].includes(z))&&low.some(z=>['high','h'].includes(z))&&low.some(z=>['low','l'].includes(z))&&low.some(z=>['close','c'].includes(z)))out.push(p+': OHLC-object fields='+keys.join(','));" +
        "if(keys.length)out.push(p+': object fields='+keys.join(','));for(const k of keys){const v=x[k];if(v&&typeof v==='object'&&out.length<12)walk(v,p+'.'+k,d+1);}}" +
        "walk(root,'$',0);return out.slice(0,12).join('\\n');}" +
        "function diagnostic(o){if(diagCount>=20)return;const s=safeShape(o);if(!s)return;const sig=s.split('\\n').join('|');if(seen.has(sig))return;seen.add(sig);diagCount++;try{AadiNative.onDiagnostic('Packet '+diagCount+'\\n'+s);}catch(e){}}" +
        "function walkTicks(x,d){if(d>10||x==null)return;if(Array.isArray(x)){if(x.length>=3&&typeof x[0]==='string'&&Number.isFinite(Number(x[1]))&&Number(x[1])>1000000000&&Number.isFinite(Number(x[2]))&&Number(x[2])>0){emit(x[2],x[0],x[1]);return;}for(const v of x)walkTicks(v,d+1);return;}" +
        "if(typeof x!=='object')return;const asset=x.asset||x.symbol||x.pair||x.instrument||x.assetName||x.ticker||x.active;const time=x.timestamp||x.time||x.ts||x.created_at;" +
        "for(const [k,v] of Object.entries(x)){if(/^(price|currentprice|lastprice|closeprice|rate|quote)$/i.test(k)&&asset&&(typeof v==='number'||typeof v==='string')&&Number(v)>0){emit(v,asset,time);return;}}" +
        "for(const v of Object.values(x))if(v&&typeof v==='object')walkTicks(v,d+1);}" +
        "let pendingBinaryPacket=null;function parse(s){try{let q=String(s).trim();if(q.startsWith('451-')){pendingBinaryPacket=JSON.parse(q.slice(4));return;}if(pendingBinaryPacket){if(q.charCodeAt(0)===4)q=q.slice(1);const attachment=JSON.parse(q),packet=pendingBinaryPacket;pendingBinaryPacket=null;function hydrate(x){if(!x||typeof x!=='object')return x;if(Array.isArray(x))return x.map(hydrate);if(x._placeholder===true)return attachment;const z={};for(const [k,v] of Object.entries(x))z[k]=hydrate(v);return z;}const rebuilt=[packet[0],hydrate(packet[1])];diagnostic(rebuilt);process(rebuilt);if(String(rebuilt[0])!=='history/load'&&String(rebuilt[0])!=='history'&&String(rebuilt[0])!=='candles/load')walkTicks(rebuilt[1],0);return;}if(q.startsWith('42')&&(q[2]==='['||q[2]==='{'))q=q.slice(2);else if(q.charCodeAt(0)===4)q=q.slice(1);const o=JSON.parse(q);diagnostic(o);if(Array.isArray(o)&&typeof o[0]==='string'){const event=String(o[0]);const payload=o[1];process(o);if(event!=='history/load'&&event!=='history'&&event!=='candles/load')walkTicks(payload,0);}else{process(o);walkTicks(o,0);}}catch(e){}}" +
        "window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){const ws=Reflect.construct(T,args,T);historyWs=ws;const nativeSend=ws.send.bind(ws);ws.send=function(data){try{const raw=String(data);if(raw.startsWith('42[')){const msg=JSON.parse(raw.slice(2));if(msg[0]==='instruments/update'&&msg[1]&&msg[1].asset){const a=String(msg[1].asset);if(a!==activeAsset){activeAsset=a;historyPages=0;historyOldest=0;historyTarget=Date.now()/1000-86400;setTimeout(function(){requestHistory(a,Date.now()/1000);},900);}}}}catch(e){}return nativeSend(data);};try{AadiNative.onSocket('Market-QX WebSocket opened');}catch(e){}ws.addEventListener('message',function(ev){if(typeof ev.data==='string')parse(ev.data);else if(ev.data instanceof Blob)ev.data.text().then(parse).catch(function(){});else if(ev.data instanceof ArrayBuffer){try{parse(new TextDecoder().decode(ev.data));}catch(e){}}});ws.addEventListener('close',function(){try{AadiNative.onSocket('Market WebSocket closed');}catch(e){}});return ws;}});" +
        "const of=window.fetch;window.fetch=function(){return of.apply(this,arguments).then(function(res){try{const copy=res.clone();copy.text().then(function(t){if(t&&t.length<4000000)parse(t);}).catch(function(){});}catch(e){}return res;});};" +
        "const xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(){this.addEventListener('load',function(){try{const t=this.responseText;if(t&&t.length<4000000)parse(t);}catch(e){};});return xo.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){return xs.apply(this,arguments);};" +
        "try{AadiNative.onSocket('WebSocket + history response scanner enabled');}catch(e){}" +
        "let loginState=null;function checkLogin(){try{const hasPassword=!!document.querySelector('input[type=password]');const hasLoginText=Array.from(document.querySelectorAll('button,[role=button],input[type=submit]')).some(e=>/^(log in|login|sign in)$/i.test((e.innerText||e.value||'').trim()));const hasChart=!!document.querySelector('canvas,[class*=chart i],[id*=chart i]');const logged=hasChart&&!hasPassword&&!hasLoginText&&document.readyState==='complete';if(logged!==loginState){loginState=logged;try{AadiNative.onLoginState(logged);}catch(e){}if(logged&&activeAsset&&historyPages===0)setTimeout(function(){requestHistory(activeAsset,Date.now()/1000);},700);}}catch(e){}}setInterval(checkLogin,2000);" +
        "})();";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 12, 18));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(14, 12, 14, 6);
        status.setText("AADI • Starting Market-QX hook…");
        lastTick = new TextView(this);
        lastTick.setTextColor(Color.rgb(80, 220, 150));
        lastTick.setTextSize(10);
        lastTick.setSingleLine(true);
        lastTick.setEllipsize(TextUtils.TruncateAt.END);
        lastTick.setPadding(14, 4, 14, 10);
        lastTick.setText("No real tick received");
        signalView = new TextView(this);
        signalView.setTextColor(Color.WHITE);
        signalView.setTextSize(14);
        signalView.setLines(2);
        signalView.setEllipsize(TextUtils.TruncateAt.END);
        signalView.setPadding(14, 4, 14, 10);
        signalView.setText("SIGNAL: WAIT • Waiting for real candles");
        root.addView(status);
        root.addView(lastTick);
        root.addView(signalView);
        diagnosticView = new TextView(this);
        diagnosticView.setTextColor(Color.LTGRAY);
        diagnosticView.setTextSize(9);
        diagnosticView.setLines(3);
        diagnosticView.setEllipsize(TextUtils.TruncateAt.END);
        diagnosticView.setPadding(14, 2, 14, 6);
        diagnosticView.setText("HISTORY DIAGNOSTICS: waiting for Market-QX packets");
        root.addView(diagnosticView);
        signalView.setText("WAIT");
        signalView.setTextColor(Color.WHITE);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AadiNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    runOnUiThread(() -> status.setText("AADI • Page load error: " + error.getDescription()));
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, HOOK,
                    java.util.Collections.singleton("https://market-qx.trade"));
            status.setText("AADI • Early WebSocket hook enabled");
        } else {
            status.setText("AADI • Update Android System WebView; early hook unsupported");
        }
        web.loadUrl(MARKET_URL);
    }


    private boolean marketLoginDetected = false;

    private void setAutoAnalysis(boolean enabled) {
        if (enabled == botActive) return;
        botActive = enabled;
        if (enabled) {
            engine = new SignalEngine();
            receivedTicks = 0;
            status.setText("AADI • LOGIN DETECTED — AUTO ANALYSIS ACTIVE");
            lastTick.setText("Collecting real ticks from logged-in chart");
            signalView.setText("WAIT");
            signalView.setTextColor(Color.WHITE);
        } else {
            status.setText("AADI • Logged out — analysis paused");
            signalView.setText("WAIT");
            signalView.setTextColor(Color.WHITE);
        }
    }

    private void showSignal(SignalEngine.Result result, double tickTimestamp) {
        String display=result.signal + "  •  SCORE " + result.score + "/100\\n"
                + result.reason + "  |  Candles: " + result.closedCandles;
        boolean directional="CALL".equals(result.signal) || "PUT".equals(result.signal);
        if(!directional) {
            if(signalColorReset!=null) uiHandler.removeCallbacks(signalColorReset);
            signalColorReset=null;
            signalFlashUntil=0;
            signalView.setText(display);
            signalView.setTextColor(Color.WHITE);
            return;
        }

        long bucket=((long)tickTimestamp/10L)*10L;
        boolean newSignalEvent=bucket!=lastSignalFlashBucket
                || !result.asset.equals(lastSignalFlashAsset);
        long now=SystemClock.uptimeMillis();
        if(newSignalEvent) {
            lastSignalFlashBucket=bucket;
            lastSignalFlashAsset=result.asset;
            if(signalColorReset!=null) uiHandler.removeCallbacks(signalColorReset);
            signalFlashUntil=now+2000L;
            final String resetText=display;
            signalColorReset=() -> {
                signalView.setText(resetText);
                signalView.setTextColor(Color.WHITE);
                signalFlashUntil=0;
            };
            uiHandler.postDelayed(signalColorReset,2000L);
        }

        // Repeated ticks carrying the same candle's locked signal do not
        // restart the timer. Each new 10-second candle gets its own 2s flash.
        if(now<signalFlashUntil) {
            SpannableString styled=new SpannableString(display);
            int color="CALL".equals(result.signal)
                    ? Color.rgb(40,230,130) : Color.rgb(255,75,85);
            styled.setSpan(new ForegroundColorSpan(color),0,result.signal.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            signalView.setText(styled);
        } else {
            signalView.setText(display);
            signalView.setTextColor(Color.WHITE);
        }
    }

    private class Bridge {
        @JavascriptInterface public void onLoginState(boolean loggedIn) {
            runOnUiThread(() -> {
                marketLoginDetected = loggedIn;
                setAutoAnalysis(loggedIn);
            });
        }

        @JavascriptInterface public void onSocket(String message) {
            runOnUiThread(() -> status.setText("AADI • " + message));
        }

        @JavascriptInterface public void onDiagnostic(String message) {
            runOnUiThread(() -> diagnosticView.setText("FEED SHAPE: " + message));
        }

        @JavascriptInterface public void onHistory(String json) {
            try {
                JSONObject packet = new JSONObject(json);
                String asset = packet.optString("asset", "");
                org.json.JSONArray rows = packet.optJSONArray("candles");
                if (asset.isEmpty() || rows == null || !botActive) return;
                int imported = engine.importHistoricalCandles(asset, rows);
                if (imported > 0) {
                    runOnUiThread(() -> diagnosticView.setText("HISTORY PACKET: asset=" + asset
                            + " | rows=" + rows.length() + " | imported=" + imported));
                    SignalEngine.Result result = engine.currentResult();
                    runOnUiThread(() -> {
                        lastTick.setText(asset + " • UPDATED " + imported + " REAL 10s HISTORY CANDLES");
                        showSignal(result, System.currentTimeMillis() / 1000.0);
                        status.setText("AADI • HISTORY ANALYSIS  |  Candles: " + result.closedCandles + "  |  Score: " + result.score + "/100");
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("AADI • Historical candle parse error"));
            }
        }

        @JavascriptInterface public void onTick(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String asset = o.optString("asset", "");
                double price = o.optDouble("price", Double.NaN);
                double ts = o.optDouble("timestamp", 0);
                if (asset.trim().isEmpty() || !Double.isFinite(price) || price <= 0) return;
                if (!botActive) return;
                SignalEngine.Result result = engine.onTick(asset, price, ts);
                long count = ++receivedTicks;
                String line = asset + "  " + price + "  • TICK #" + count + "  |  REAL FEED";
                long uiNow=SystemClock.uptimeMillis();
                // Analyze every genuine tick, but coalesce display updates to protect the UI thread.
                if(uiNow-lastUiTickAt>=200) {
                    lastUiTickAt=uiNow;
                    runOnUiThread(() -> {
                        lastTick.setText(line);
                        showSignal(result, ts);
                        if (receivedTicks == 1) status.setText("AADI • LIVE FEED CONNECTED");
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("AADI • Tick parse error"));
            }
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AadiNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
