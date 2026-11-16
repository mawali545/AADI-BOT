package com.aadi.bot;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.text.InputType;
import android.text.TextUtils;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.Gravity;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import org.json.JSONArray;

public class MainActivity extends Activity {
    private TextView status, lastTick, signalView, analysisView, depthView, statsView, scanStatus;
    private Button generateSignalButton;
    private ProgressBar scanProgress;
    private volatile boolean scanInProgress=false;
    private int scanProgressValue=0;
    private long scanGeneration=0;
    private final Runnable scanProgressRunnable=new Runnable(){
        @Override public void run(){
            if(!scanInProgress||scanProgress==null||generateSignalButton==null)return;
            scanProgressValue=Math.min(95,scanProgressValue+8);
            scanProgress.setProgress(scanProgressValue);
            generateSignalButton.setText("SCANNING "+scanProgressValue+"%");
            uiHandler.postDelayed(this,120L);
        }
    };
    private long depthEventCount = 0;
    private final java.util.ArrayDeque<String> depthRecent = new java.util.ArrayDeque<>();
    private volatile boolean botActive = false;
    private WebView web;
    private long receivedTicks = 0;
    private int sessionWins = 0;
    private int sessionLosses = 0;
    private volatile long lastUiTickAt = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable signalColorReset;
    private long lastSignalFlashBucket = Long.MIN_VALUE;
    private long signalFlashUntil = 0;
    private String lastSignalFlashAsset = "";
    private volatile String activeMarketAsset = "";
    private volatile double latestLivePrice = Double.NaN;
    private volatile long latestLiveTickMs = 0;
    private volatile long latestLiveFeedTimeMs = 0;
    private volatile boolean geminiReviewPending = false;
    private volatile long lastGeminiRequestAt = 0;
    private final java.util.ArrayDeque<JSONObject> aiRecentTicks = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<JSONObject> aiRecentCandles = new java.util.ArrayDeque<>();
    private volatile long aiTradeEntryTime = 0;
    private volatile double aiTradeEntryPrice = Double.NaN;
    private volatile String aiTradeSide = "";
    private volatile String aiTradeReason = "";
    private volatile long geminiReviewToken = 0;
    private final java.util.concurrent.ExecutorService geminiExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final String MARKET_URL = "https://market-qx.trade/en/trade";
    private static final String HOOK =
        "(function(){'use strict';" +
        "if(window.__AADI_HOOKED__)return;window.__AADI_HOOKED__=true;" +
        "const NativeWS=window.WebSocket;let activeAsset=null,chartWs=null,pendingBinaryPacket=null,depthRequestedAsset=null;let historyPages=0,historyOldest=0,historyTarget=0;function requestHistory(asset,anchorTime){try{if(!chartWs||chartWs.readyState!==1||!asset||historyPages>=64)return;const payload={asset:String(asset),index:Math.floor(Date.now()/10)+historyPages,offset:3600,period:10,time:Math.floor(anchorTime||Date.now()/1000)};chartWs.send('42[\"history/load\",'+JSON.stringify(payload)+']');historyPages++;AadiNative.onDepthStatus('Historical candles request '+historyPages+' sent');}catch(e){}}function collectHistory(root,assetHint){const out=[],seen=new Set();function walk(x,asset,d){if(!x||typeof x!=='object'||d>10||seen.has(x))return;seen.add(x);if(Array.isArray(x)){const rows=[];if(x.length&&Array.isArray(x[0])){for(const r of x){if(!Array.isArray(r)||r.length<5)continue;const t=normTime(r[0]),o=Number(r[1]),cl=Number(r[2]),h=Number(r[3]),l=Number(r[4]);if(t&&[o,cl,h,l].every(Number.isFinite)&&o>0&&cl>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl)&&l>0&&h>=l)rows.push({timestamp:t,open:o,high:h,low:l,close:cl});}}else if(x.length&&x[0]&&typeof x[0]==='object'){for(const r of x){if(!r||typeof r!=='object')continue;const t=normTime(r.timestamp||r.time||r.ts||r.date),o=Number(r.open??r.o),h=Number(r.high??r.h),l=Number(r.low??r.l),cl=Number(r.close??r.c);if(t&&[o,h,l,cl].every(Number.isFinite)&&o>0&&h>=Math.max(o,cl)&&l<=Math.min(o,cl)&&l>0&&h>=l&&cl>0)rows.push({timestamp:t,open:o,high:h,low:l,close:cl});}}if(rows.length>=10)out.push({asset:asset||assetHint||activeAsset||'',candles:rows});for(const v of x)walk(v,asset,d+1);return;}const a=x.asset||x.symbol||x.pair||x.instrument||asset;for(const v of Object.values(x))if(v&&typeof v==='object')walk(v,a,d+1);}walk(root,assetHint,0);return out;}function depthTap(ev,payload){try{const raw=JSON.stringify(payload);const txt=String(ev||'')+' '+raw.slice(0,12000);if(/depth|order.?book|book|bids|asks/i.test(txt)){AadiNative.onDepthMessage(JSON.stringify({asset:activeAsset||'',event:String(ev||'unknown'),receivedAt:Date.now(),payload:payload}));}}catch(e){}}function followDepth(){try{if(!chartWs||chartWs.readyState!==1||!activeAsset||depthRequestedAsset===activeAsset)return;depthRequestedAsset=activeAsset;chartWs.send('42[\"depth/follow\",'+JSON.stringify(activeAsset)+']');AadiNative.onDepthStatus('depth/follow sent • '+activeAsset);}catch(e){depthRequestedAsset=null;AadiNative.onDepthStatus('depth/follow send failed');}}" +
        "function networkTap(source,url,text){try{if(!/json/i.test(String(text).slice(0,80))&&!/bid|ask|depth|book/i.test(String(text).slice(0,3000)))return;const obj=JSON.parse(text);depthTap('network:'+source+':'+String(url||'').slice(-180),obj);}catch(e){}}try{const nativeFetch=window.fetch;if(nativeFetch)window.fetch=function(){const args=arguments;return nativeFetch.apply(this,args).then(function(response){try{const clone=response.clone();clone.text().then(function(t){networkTap('fetch',args[0]&&args[0].url||args[0],t);}).catch(function(){});}catch(e){}return response;});};}catch(e){}try{const xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this.__aadiUrl=u;return xo.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{if(this.responseType===''||this.responseType==='text')networkTap('xhr',this.__aadiUrl,this.responseText);}catch(e){};});return xs.apply(this,arguments);};}catch(e){}function normTime(v){let n=Number(v);if(!Number.isFinite(n)||n<=0)return 0;if(n>1e12)n/=1000;return n;}" +
        "function emit(price,asset,time){price=Number(price);if(!Number.isFinite(price)||price<=0||!asset||!activeAsset||String(asset)!==String(activeAsset))return;const feedTime=normTime(time);if(!feedTime)return;const now=Date.now()/1000;if(feedTime<now-45||feedTime>now+8)return;try{AadiNative.onTick(JSON.stringify({price:price,asset:String(asset),timestamp:feedTime}));}catch(e){}}" +
        "function walkTicks(x,d){if(d>10||x==null)return;if(Array.isArray(x)){if(x.length>=3&&typeof x[0]==='string'&&Number.isFinite(Number(x[1]))&&Number(x[1])>1000000000&&Number.isFinite(Number(x[2]))&&Number(x[2])>0){emit(x[2],x[0],x[1]);return;}for(const v of x)walkTicks(v,d+1);return;}if(typeof x!=='object')return;const asset=x.asset||x.symbol||x.pair||x.instrument||x.assetName||x.ticker||x.active;const time=x.timestamp||x.time||x.ts||x.created_at;for(const [k,v] of Object.entries(x)){if(/^(price|currentprice|lastprice|closeprice|rate|quote)$/i.test(k)&&asset&&(typeof v==='number'||typeof v==='string')&&Number(v)>0){emit(v,asset,time);return;}}for(const v of Object.values(x))if(v&&typeof v==='object')walkTicks(v,d+1);}" +
        "function parse(s){try{let q=String(s).trim();if(q.startsWith('451-')){pendingBinaryPacket=JSON.parse(q.slice(4));return;}if(pendingBinaryPacket){if(q.charCodeAt(0)===4)q=q.slice(1);const attachment=JSON.parse(q),packet=pendingBinaryPacket;pendingBinaryPacket=null;function hydrate(x){if(!x||typeof x!=='object')return x;if(Array.isArray(x))return x.map(hydrate);if(x._placeholder===true)return attachment;const z={};for(const [k,v] of Object.entries(x))z[k]=hydrate(v);return z;}const rebuilt=[packet[0],hydrate(packet[1])];const ev=String(rebuilt[0]);depthTap(ev,rebuilt[1]);if(ev==='history/load'||ev==='history'||ev==='candles/load'){const packets=collectHistory(rebuilt[1],activeAsset);for(const p of packets){try{AadiNative.onHistory(JSON.stringify(p));}catch(e){}}if(packets.length){let oldest=Infinity;for(const p of packets)for(const c of p.candles)oldest=Math.min(oldest,c.timestamp);if(Number.isFinite(oldest)){historyOldest=oldest; if(oldest>historyTarget&&historyPages<64)setTimeout(function(){requestHistory(activeAsset,oldest-3600);},350);}}}else walkTicks(rebuilt[1],0);return;}if(q.startsWith('42')&&(q[2]==='['||q[2]==='{'))q=q.slice(2);else if(q.charCodeAt(0)===4)q=q.slice(1);const o=JSON.parse(q);if(Array.isArray(o)&&typeof o[0]==='string'){const ev=String(o[0]);depthTap(ev,o[1]);if(ev==='history/load'||ev==='history'||ev==='candles/load'){const packets=collectHistory(o[1],activeAsset);for(const p of packets){try{AadiNative.onHistory(JSON.stringify(p));}catch(e){}}if(packets.length){let oldest=Infinity;for(const p of packets)for(const c of p.candles)oldest=Math.min(oldest,c.timestamp);if(Number.isFinite(oldest)){historyOldest=oldest;if(oldest>historyTarget&&historyPages<64)setTimeout(function(){requestHistory(activeAsset,oldest-3600);},350);}}}else walkTicks(o[1],0);}else walkTicks(o,0);}catch(e){}}" +
        "window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){const ws=Reflect.construct(T,args,T);const nativeSend=ws.send.bind(ws);ws.send=function(data){try{const raw=String(data);if(raw.startsWith('42[')){const msg=JSON.parse(raw.slice(2));if(msg[0]==='instruments/update'&&msg[1]&&msg[1].asset){const a=String(msg[1].asset);const changed=chartWs!==ws||activeAsset!==a;activeAsset=a;chartWs=ws;if(changed){historyPages=0;historyOldest=0;historyTarget=(Math.floor((Date.now()/1000-new Date().getTimezoneOffset()*60)/86400)*86400+new Date().getTimezoneOffset()*60)-86400;try{AadiNative.onAssetChanged(a);}catch(e){}try{AadiNative.onSocket('CHART SOCKET ACTIVE • '+a);}catch(e){}if(loginState===true)setTimeout(function(){requestHistory(a,Date.now()/1000);},900);}}}}catch(e){}const sent=nativeSend(data);if(chartWs===ws)followDepth();return sent;};ws.addEventListener('message',function(ev){if(chartWs!==ws)return;if(typeof ev.data==='string')parse(ev.data);else if(ev.data instanceof Blob)ev.data.text().then(parse).catch(function(){});else if(ev.data instanceof ArrayBuffer){try{parse(new TextDecoder().decode(ev.data));}catch(e){}}});ws.addEventListener('close',function(){if(chartWs===ws){chartWs=null;depthRequestedAsset=null;try{AadiNative.onSocket('CHART WEBSOCKET CLOSED • feed paused');AadiNative.onDepthStatus('Waiting for chart WebSocket');}catch(e){}}});return ws;}});" +
        "try{AadiNative.onSocket('Live chart tick scanner enabled');}catch(e){}" +
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
        lastTick.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        lastTick.setMarqueeRepeatLimit(-1);
        lastTick.setFocusable(true);
        lastTick.setFocusableInTouchMode(true);
        lastTick.setSelected(true);
        lastTick.setPadding(14, 4, 14, 10);
        lastTick.setText("No real tick received");
        root.addView(lastTick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Retain internal diagnostic sinks without adding them to the compact screen.
        analysisView = new TextView(this);
        depthView = new TextView(this);
        signalView = new TextView(this);
        signalView.setTextColor(Color.WHITE);
        signalView.setTextSize(40);
        signalView.setTypeface(null, android.graphics.Typeface.BOLD);
        signalView.setGravity(Gravity.CENTER);
        signalView.setSingleLine(true);
        signalView.setIncludeFontPadding(true);
        int signalPad = (int) (8 * getResources().getDisplayMetrics().density);
        signalView.setPadding(signalPad, signalPad, signalPad, signalPad);
        signalView.setMinHeight((int) (88 * getResources().getDisplayMetrics().density));
        signalView.setText("WAIT");
        root.addView(signalView,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        scanStatus=new TextView(this);
        scanStatus.setText("Automatic live signal analysis active");

        statsView = new TextView(this);
        statsView.setTextColor(Color.WHITE);
        statsView.setTextSize(18);
        statsView.setGravity(Gravity.CENTER);
        statsView.setPadding(8, 4, 8, 10);
        statsView.setText("WINS 0    LOSSES 0");
        root.addView(statsView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button hiddenGeminiSettings=new Button(this);
        hiddenGeminiSettings.setText(GeminiSignalAdvisor.loadApiKey(this).isEmpty()
                ? "SET UP FREE GEMINI API KEY" : "GEMINI AI • API KEY CONFIGURED");
        statsView.setOnLongClickListener(v -> {
            showGeminiSettings(hiddenGeminiSettings);
            return true;
        });

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
        // Keep the live QX WebView attached for its real WebSocket feed, but hide it from the compact dashboard.
        root.addView(web, new LinearLayout.LayoutParams(1, 1));
        setContentView(root);
        refreshStats();

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, HOOK,
                    java.util.Collections.singleton("https://market-qx.trade"));
            status.setText("AADI • Early WebSocket hook enabled");
        } else {
            status.setText("AADI • Update Android System WebView; early hook unsupported");
        }
        web.loadUrl(MARKET_URL);
    }



    private void persistAuditEvents(String json) {
        try {
            JSONArray incoming=new JSONArray(json==null?"[]":json);
            android.content.SharedPreferences prefs=getSharedPreferences("aadi_signal_audit",MODE_PRIVATE);
            JSONArray saved=new JSONArray(prefs.getString("events","[]"));
            for(int i=0;i<incoming.length();i++) {
                JSONObject item=incoming.optJSONObject(i);
                if(item==null) continue;
                String type=item.optString("type");
                String tradeId=item.optString("tradeId",Long.toString(item.optLong("entryTimeMs",0)));
                String identity=type+":"+tradeId;
                boolean alreadySaved=false;
                for(int j=0;j<saved.length();j++) {
                    JSONObject old=saved.optJSONObject(j);
                    if(old==null) continue;
                    String oldType=old.optString("type");
                    String oldTradeId=old.optString("tradeId",Long.toString(old.optLong("entryTimeMs",0)));
                    if(identity.equals(oldType+":"+oldTradeId)){alreadySaved=true;break;}
                }
                if(alreadySaved) continue;
                saved.put(item);
                if(!"SETTLED".equals(type)) continue;
                String outcome=item.optString("outcome");
                if("WIN".equals(outcome)) sessionWins++;
                else if("LOSS".equals(outcome)) sessionLosses++;
            }
            // Keep the last 30 settled trades (not merely 30 event rows).
            java.util.LinkedHashSet<String> recentSettledIds=new java.util.LinkedHashSet<>();
            for(int i=saved.length()-1;i>=0&&recentSettledIds.size()<30;i--) {
                JSONObject item=saved.optJSONObject(i);
                if(item!=null&&"SETTLED".equals(item.optString("type"))) {
                    recentSettledIds.add(item.optString("tradeId",
                            Long.toString(item.optLong("entryTimeMs",0))));
                }
            }
            JSONArray trimmed=new JSONArray();
            sessionWins=0;sessionLosses=0;
            for(int i=0;i<saved.length();i++) {
                JSONObject item=saved.optJSONObject(i);
                if(item==null)continue;
                String id=item.optString("tradeId",Long.toString(item.optLong("entryTimeMs",0)));
                if(!recentSettledIds.contains(id))continue;
                trimmed.put(item);
                if("SETTLED".equals(item.optString("type"))) {
                    if("WIN".equals(item.optString("outcome")))sessionWins++;
                    else if("LOSS".equals(item.optString("outcome")))sessionLosses++;
                }
            }
            prefs.edit().putString("events",trimmed.toString()).apply();
            runOnUiThread(() -> refreshStats());
        } catch(Exception ignored) {}
    }

    private void refreshStats() {
        if(statsView==null) return;
        statsView.setText("WINS "+sessionWins+"    LOSSES "+sessionLosses);
    }

    private void showAuditLog() {
        try {
            JSONArray saved=new JSONArray(getSharedPreferences("aadi_signal_audit",MODE_PRIVATE).getString("events","[]"));
            StringBuilder out=new StringBuilder();
            int start=Math.max(0,saved.length()-30);
            for(int i=saved.length()-1;i>=start;i--) {
                JSONObject e=saved.optJSONObject(i);
                if(e==null) continue;
                long stamp=e.optLong("entryTimeMs",0);
                String tradeId=e.optString("tradeId",Long.toString(stamp));
                out.append("EVENT #").append(i+1).append(" • TRADE ").append(tradeId)
                    .append(" • ").append(e.optString("type"))
                    .append(" • ").append(e.optString("asset")).append(" • ").append(e.optString("signal"));
                if("SETTLED".equals(e.optString("type")))
                    out.append(" • ").append(e.optString("outcome")).append(" • entry ")
                       .append(e.optDouble("entryPrice")).append(" → expiry ").append(e.optDouble("expiryPrice"));
                else out.append(" • entry ").append(e.optDouble("entryPrice"));
                if(stamp>0) out.append(" • ").append(new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date(stamp)));
                out.append("\\n").append(e.optString("baseAnalysis")).append("\\n")
                    .append(e.optString("dailySummary")).append("\\n")
                    .append("5s momentum: ").append(e.optDouble("microMove3s")).append(" / ").append(e.optDouble("microMove1s"))
                    .append("\\n\\n------------------------------\\n\\n");
            }
            if(out.length()==0) out.append("No audit records yet. New signals will be recorded when issued and settled.");
            new AlertDialog.Builder(this).setTitle("AADI • SIGNAL AUDIT")
                .setMessage(out.toString())
                .setNeutralButton("COPY AUDIT", (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "AADI Signal Audit", out.toString()));
                        android.widget.Toast.makeText(this, "Signal audit copied", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("CLOSE",null).show();
        } catch(Exception e) {
            new AlertDialog.Builder(this).setTitle("AADI • SIGNAL AUDIT")
                .setMessage("Audit log could not be read.").setPositiveButton("CLOSE",null).show();
        }
    }

    private void showGeminiSettings(Button settingsButton) {
        EditText keyInput=new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setHint("Paste Google AI Studio API key");
        keyInput.setText(GeminiSignalAdvisor.loadApiKey(this));
        new AlertDialog.Builder(this)
                .setTitle("AADI • GEMINI OTC AI")
                .setMessage("Gemini is the only signal decision-maker. It receives genuine QX ticks and candles, then returns CALL, PUT or WAIT. Create a free-tier key in Google AI Studio and paste it here. The key is encrypted on this device.")
                .setView(keyInput)
                .setPositiveButton("SAVE KEY",(dialog,which)->{
                    try{
                        GeminiSignalAdvisor.saveApiKey(this,keyInput.getText().toString());
                        settingsButton.setText(keyInput.getText().toString().trim().isEmpty()
                                ? "SET UP FREE GEMINI API KEY" : "GEMINI AI • API KEY CONFIGURED");
                        android.widget.Toast.makeText(this,"Gemini key saved securely on this device",android.widget.Toast.LENGTH_SHORT).show();
                    }catch(Exception e){
                        android.widget.Toast.makeText(this,"Could not save Gemini key: "+e.getClass().getSimpleName(),android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("REMOVE KEY",(dialog,which)->{
                    try{
                        GeminiSignalAdvisor.saveApiKey(this,"");
                        settingsButton.setText("SET UP FREE GEMINI API KEY");
                        android.widget.Toast.makeText(this,"Gemini key removed",android.widget.Toast.LENGTH_SHORT).show();
                    }catch(Exception ignored){}
                })
                .setNegativeButton("CANCEL",null).show();
    }

    private String withoutIssuedEvents(String json) {
        try{
            JSONArray input=new JSONArray(json==null?"[]":json);
            JSONArray kept=new JSONArray();
            for(int i=0;i<input.length();i++){
                JSONObject event=input.optJSONObject(i);
                if(event!=null&&!"ISSUED".equals(event.optString("type")))kept.put(event);
            }
            return kept.toString();
        }catch(Exception ignored){return "[]";}
    }

    private JSONObject buildAiMarketSnapshot() {
        JSONObject snapshot=new JSONObject();
        try {
            snapshot.put("asset",activeMarketAsset);
            snapshot.put("decisionHorizonSeconds",10);
            snapshot.put("currentQuote",latestLivePrice);
            snapshot.put("tickFeedType","real Market-QX chart WebSocket ticks");
            JSONArray ticks=new JSONArray();
            synchronized(aiRecentTicks){for(JSONObject item:aiRecentTicks)ticks.put(item);}
            JSONArray candles=new JSONArray();
            synchronized(aiRecentCandles){for(JSONObject item:aiRecentCandles)candles.put(item);}
            snapshot.put("recentLiveTicks",ticks);
            snapshot.put("recentHistorical10SecondCandles",candles);
        } catch(Exception ignored) {}
        return snapshot;
    }

    private void requestGeminiMarketDecision(final String asset,final long token) {
        final String key=GeminiSignalAdvisor.loadApiKey(MainActivity.this);
        final JSONObject snapshot=buildAiMarketSnapshot();
        final long snapshotFeedTime=latestLiveFeedTimeMs;
        geminiExecutor.execute(()->{
            GeminiSignalAdvisor.Decision decision=null;
            String failure=null;
            try{decision=GeminiSignalAdvisor.reviewMarket(key,snapshot);}
            catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            final GeminiSignalAdvisor.Decision answer=decision;
            final String error=failure;
            runOnUiThread(()->{
                if(token!=geminiReviewToken||!botActive||!asset.equals(activeMarketAsset))return;
                geminiReviewPending=false;
                if(latestLiveTickMs<=0||System.currentTimeMillis()-latestLiveTickMs>1800L
                        ||latestLiveFeedTimeMs-snapshotFeedTime>3500L){
                    signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
                    if(scanStatus!=null)scanStatus.setText("WAIT • QX live quote became stale during Gemini analysis");
                    return;
                }
                if(aiTradeEntryTime>0)return;
                String side="WAIT";
                if(answer!=null&&answer.approved){
                    if(answer.reason.startsWith("CALL"))side="CALL";
                    else if(answer.reason.startsWith("PUT"))side="PUT";
                }
                if("CALL".equals(side)||"PUT".equals(side)){
                    aiTradeEntryTime=latestLiveFeedTimeMs;
                    aiTradeEntryPrice=latestLivePrice;
                    aiTradeSide=side;
                    aiTradeReason=answer.reason;
                    signalView.setText(side);
                    signalView.setTextColor("CALL".equals(side)?Color.rgb(40,230,130):Color.rgb(255,75,85));
                    if(scanStatus!=null)scanStatus.setText("GEMINI AI • "+answer.reason+" • confidence "+answer.confidence);
                }else{
                    signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
                    if(scanStatus!=null)scanStatus.setText(answer==null
                            ?"WAIT • Gemini connection error: "+(error==null?"request failed":error)
                            :"GEMINI AI • "+answer.reason+" • confidence "+answer.confidence);
                }
            });
        });
    }

    private void settleAiTradeIfDue(long feedTimeMs,double price) {
        if(aiTradeEntryTime<=0||feedTimeMs<aiTradeEntryTime+10000L)return;
        long expiry=aiTradeEntryTime+10000L;
        JSONObject event=new JSONObject();
        try{
            event.put("type","SETTLED");
            event.put("tradeId",Long.toString(aiTradeEntryTime));
            event.put("asset",activeMarketAsset);
            event.put("signal",aiTradeSide);
            event.put("entryTimeMs",aiTradeEntryTime);
            event.put("entryPrice",aiTradeEntryPrice);
            event.put("expiryTimeMs",expiry);
            event.put("expiryPrice",price);
            event.put("source","Gemini AI-only decision");
            event.put("aiReason",aiTradeReason);
            if(feedTimeMs-expiry>1500L){
                event.put("outcome","UNVERIFIED");
                event.put("reason","No QX quote close enough to the 10-second expiry");
            }else{
                boolean win="CALL".equals(aiTradeSide)?price>aiTradeEntryPrice:price<aiTradeEntryPrice;
                event.put("outcome",price==aiTradeEntryPrice?"FLAT":win?"WIN":"LOSS");
            }
        }catch(Exception ignored){}
        JSONArray events=new JSONArray();events.put(event);
        persistAuditEvents(events.toString());
        aiTradeEntryTime=0;aiTradeEntryPrice=Double.NaN;aiTradeSide="";aiTradeReason="";
        signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
        if(scanStatus!=null)scanStatus.setText("Trade settled • waiting for Gemini's next independent decision");
    }

    private boolean marketLoginDetected = false;

    private void setAutoAnalysis(boolean enabled) {
        if(enabled==botActive)return;
        botActive=enabled;
        geminiReviewToken++;geminiReviewPending=false;
        if(enabled){
            synchronized(aiRecentTicks){aiRecentTicks.clear();}
            synchronized(aiRecentCandles){aiRecentCandles.clear();}
            aiTradeEntryTime=0;aiTradeEntryPrice=Double.NaN;aiTradeSide="";aiTradeReason="";
            receivedTicks=0;
            status.setText("AADI • REAL QX FEED READY — GEMINI AI DECISIONS");
            lastTick.setText("Collecting genuine QX ticks and historical candles");
            signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
            if(scanStatus!=null)scanStatus.setText("Gemini is the only signal decision-maker");
        }else{
            status.setText("AADI • Logged out — Gemini decisions paused");
            signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
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
            runOnUiThread(() -> {
                status.setText("AADI • " + message);
                if (message != null && message.contains("CLOSED")) {
                    geminiReviewToken++;geminiReviewPending=false;
                    receivedTicks=0;
                    latestLivePrice=Double.NaN;latestLiveTickMs=0;latestLiveFeedTimeMs=0;
                    synchronized(aiRecentTicks){aiRecentTicks.clear();}
                    synchronized(aiRecentCandles){aiRecentCandles.clear();}
                    aiTradeEntryTime=0;aiTradeEntryPrice=Double.NaN;aiTradeSide="";aiTradeReason="";
                    lastTick.setText("Chart feed disconnected • waiting for verified reconnect");
                    signalView.setText("WAIT");
                    signalView.setTextColor(Color.WHITE);
                }
            });
        }

        @JavascriptInterface public synchronized void onAssetChanged(String asset) {
            if (asset == null || asset.trim().isEmpty()) return;
            activeMarketAsset=asset;
            latestLivePrice=Double.NaN;latestLiveTickMs=0;latestLiveFeedTimeMs=0;
            geminiReviewToken++;geminiReviewPending=false;lastGeminiRequestAt=0;
            aiTradeEntryTime=0;aiTradeEntryPrice=Double.NaN;aiTradeSide="";aiTradeReason="";
            synchronized(aiRecentTicks){aiRecentTicks.clear();}
            synchronized(aiRecentCandles){aiRecentCandles.clear();}
            receivedTicks=0;
            runOnUiThread(() -> {
                lastSignalFlashBucket = Long.MIN_VALUE;
                lastSignalFlashAsset = asset;
                lastTick.setText(asset + " • loading real QX candles for Gemini AI");
                signalView.setText("WAIT");
                signalView.setTextColor(Color.WHITE);
                analysisView.setText("GEMINI AI • Waiting for genuine QX market data");
            });
        }

        @JavascriptInterface public void onDepthStatus(String message) {
            runOnUiThread(() -> depthView.setText("ORDER BOOK • " + message));
        }

        @JavascriptInterface public void onDepthMessage(String json) {
            try {
                JSONObject packet = new JSONObject(json);
                String asset = packet.optString("asset", "unknown");
                String event = packet.optString("event", "unknown");
                long received = packet.optLong("receivedAt", 0);
                Object raw = packet.opt("payload");
                OrderBookSnapshot book = OrderBookSnapshot.parse(raw);
                long nowMs = System.currentTimeMillis();
                boolean sameAsset = asset.equals(activeMarketAsset);
                boolean freshDepth = received > 0 && Math.abs(nowMs - received) <= 5000L;
                boolean freshQuote = latestLiveTickMs > 0 && nowMs - latestLiveTickMs <= 3000L;
                boolean quoteMatch = book != null && book.matchesQuote(latestLivePrice);
                boolean level2Validated = book != null && sameAsset && freshDepth && freshQuote && quoteMatch;
                if (level2Validated) {
                    // Order-book data is diagnostic only; Gemini makes every signal decision.
                }
                // Depth events are diagnostic only. They never create or veto a signal.
                if ("depth/change".equals(event) && raw instanceof JSONArray) {
                    JSONArray depth = (JSONArray) raw;
                    if (depth.length() >= 2 && depth.opt(1) instanceof Number) {
                        // Depth samples are not converted into local signal votes.
                    }
                }
                String payload = raw == null ? "null" : String.valueOf(raw);
                if (raw instanceof JSONObject || raw instanceof JSONArray) payload = raw.toString();
                if (payload.length() > 2400) payload = payload.substring(0, 2400) + "…[truncated]";
                String stamp = received > 0
                        ? new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date(received))
                        : "unknown";
                final String line = "#" + (++depthEventCount) + " " + stamp + " " + event
                        + " • " + asset + "\\n" + payload;
                synchronized (depthRecent) {
                    depthRecent.addFirst(line);
                    while (depthRecent.size() > 8) depthRecent.removeLast();
                }
                StringBuilder display = new StringBuilder("ORDER BOOK STREAM • ")
                        .append(asset).append("\\nEvents captured: ").append(depthEventCount)
                        .append("\\nLEVEL 2 VALIDATION: ");
                String lower = payload.toLowerCase(java.util.Locale.US);
                boolean bidAsk = (lower.contains("bid") || lower.contains("buy"))
                        && (lower.contains("ask") || lower.contains("sell"));
                boolean hasPrice = lower.contains("price") || lower.contains("rate");
                boolean hasSize = lower.contains("volume") || lower.contains("quantity")
                        || lower.contains("amount") || lower.contains("size");
                if (bidAsk && hasPrice && hasSize) {
                    display.append("candidate bid/ask + price + size fields (schema still requires validation)");
                } else {
                    display.append("NOT CONFIRMED • waiting for explicit bid/ask price and quantity levels");
                }
                if (book != null) {
                    display.append(level2Validated ? "\\nLEVEL 2 VERIFIED • fresh same-asset bid/ask bracket live quote"
                            : "\\nLEVEL 2 CANDIDATE ONLY • failed asset/freshness/live-quote check");
                    display.append("\\nBID/ASK LEVELS • ").append(book.bidLevels.size())
                           .append(" bids / ").append(book.askLevels.size()).append(" asks")
                           .append("\\nBest bid/ask: ").append(String.format(java.util.Locale.US,"%.8f / %.8f",book.bestBid,book.bestAsk))
                           .append(" • Spread: ").append(String.format(java.util.Locale.US,"%.8f",book.spread))
                           .append(" • Imbalance: ").append(String.format(java.util.Locale.US,"%+.1f%%",book.imbalance*100))
                           .append("\\nHEATMAP (price × size)\\n").append(book.heatmap());
                }
                display.append("\\n--- LAST DEPTH MESSAGES ---");
                synchronized (depthRecent) {
                    for (String item : depthRecent) display.append("\\n\\n").append(item);
                }
                final String text = display.toString();
                runOnUiThread(() -> depthView.setText(text));
            } catch (Exception e) {
                runOnUiThread(() -> depthView.setText("ORDER BOOK • Depth message parse error: " + e.getClass().getSimpleName()));
            }
        }

        @JavascriptInterface public synchronized void onHistory(String json) {
            try{
                JSONObject packet=new JSONObject(json);
                String asset=packet.optString("asset","");
                JSONArray rows=packet.optJSONArray("candles");
                if(!botActive||!activeMarketAsset.equals(asset)||rows==null)return;
                synchronized(aiRecentCandles){
                    for(int i=0;i<rows.length();i++){
                        JSONObject row=rows.optJSONObject(i);
                        if(row==null)continue;
                        long stamp=row.optLong("timestamp",0);
                        if(stamp>0&&stamp<10000000000L)stamp*=1000L;
                        double open=row.optDouble("open",Double.NaN),high=row.optDouble("high",Double.NaN);
                        double low=row.optDouble("low",Double.NaN),close=row.optDouble("close",Double.NaN);
                        if(stamp<=0||!Double.isFinite(open)||!Double.isFinite(high)
                                ||!Double.isFinite(low)||!Double.isFinite(close))continue;
                        JSONObject candle=new JSONObject();
                        candle.put("timestamp",stamp);candle.put("open",open);candle.put("high",high);
                        candle.put("low",low);candle.put("close",close);
                        boolean duplicate=false;
                        for(JSONObject old:aiRecentCandles)if(old.optLong("timestamp")==stamp){duplicate=true;break;}
                        if(!duplicate)aiRecentCandles.addLast(candle);
                    }
                    while(aiRecentCandles.size()>60)aiRecentCandles.removeFirst();
                }
                runOnUiThread(()->status.setText("AADI • QX CANDLES READY FOR GEMINI"));
            }catch(Exception ignored){}
        }

        @JavascriptInterface public synchronized void onTick(String json) {
            try{
                JSONObject packet=new JSONObject(json);
                String asset=packet.optString("asset","");
                double price=packet.optDouble("price",Double.NaN);
                double ts=packet.optDouble("timestamp",0);
                if(asset.trim().isEmpty()||!Double.isFinite(price)||price<=0||!Double.isFinite(ts))return;
                double seconds=ts>10000000000.0?ts/1000.0:ts;
                long feedTimeMs=(long)(seconds*1000.0);
                long wallMs=System.currentTimeMillis();
                if(feedTimeMs<=0||feedTimeMs<wallMs-45000L||feedTimeMs>wallMs+8000L||!botActive)return;
                if(!asset.equals(activeMarketAsset))return;
                latestLivePrice=price;latestLiveTickMs=wallMs;latestLiveFeedTimeMs=feedTimeMs;
                JSONObject tick=new JSONObject();
                tick.put("timestamp",feedTimeMs);tick.put("price",price);
                synchronized(aiRecentTicks){
                    aiRecentTicks.addLast(tick);
                    while(!aiRecentTicks.isEmpty()&&feedTimeMs-aiRecentTicks.peekFirst().optLong("timestamp")>30000L)
                        aiRecentTicks.removeFirst();
                    while(aiRecentTicks.size()>80)aiRecentTicks.removeFirst();
                }
                settleAiTradeIfDue(feedTimeMs,price);
                long count=++receivedTicks;
                String line=asset+"  "+price+"  • TICK #"+count+"  |  REAL QX FEED";
                if(aiTradeEntryTime<=0&&!geminiReviewPending&&count>=5
                        &&wallMs-lastGeminiRequestAt>=5000L){
                    String key=GeminiSignalAdvisor.loadApiKey(MainActivity.this);
                    if(key.isEmpty()){
                        signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
                        if(scanStatus!=null)scanStatus.setText("Set up Gemini API key • long-press WINS/LOSSES");
                    }else{
                        geminiReviewPending=true;lastGeminiRequestAt=wallMs;
                        long token=++geminiReviewToken;
                        signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
                        if(scanStatus!=null)scanStatus.setText("Gemini is independently analyzing live QX ticks and candles…");
                        requestGeminiMarketDecision(asset,token);
                    }
                }
                long uiNow=SystemClock.uptimeMillis();
                if(uiNow-lastUiTickAt>=200){
                    lastUiTickAt=uiNow;
                    runOnUiThread(()->lastTick.setText(line));
                }
            }catch(Exception ignored){}
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
        geminiReviewToken++;geminiReviewPending=false;
        geminiExecutor.shutdownNow();
        super.onDestroy();
    }
}