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
    private boolean botActive = false;
    private SignalEngine engine = new SignalEngine();
    private FiveSecondSignalEngine fiveSecondEngine = new FiveSecondSignalEngine();
    private final DailyContextTracker dailyContext = new DailyContextTracker();
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
        scanStatus.setTextColor(Color.LTGRAY);
        scanStatus.setTextSize(11);
        scanStatus.setGravity(Gravity.CENTER);
        scanStatus.setPadding(8,4,8,8);
        scanStatus.setText("Automatic live signal analysis • waiting for verified market data");
        root.addView(scanStatus,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        statsView = new TextView(this);
        statsView.setTextColor(Color.WHITE);
        statsView.setTextSize(18);
        statsView.setGravity(Gravity.CENTER);
        statsView.setPadding(8, 4, 8, 10);
        statsView.setText("WINS 0    LOSSES 0");
        root.addView(statsView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button geminiSettingsButton=new Button(this);
        geminiSettingsButton.setText(GeminiSignalAdvisor.loadApiKey(this).isEmpty()
                ? "SET UP FREE GEMINI API KEY" : "GEMINI AI • API KEY CONFIGURED");
        geminiSettingsButton.setOnClickListener(v -> showGeminiSettings(geminiSettingsButton));
        root.addView(geminiSettingsButton);

        Button auditButton=new Button(this);
        auditButton.setText("OPEN SIGNAL AUDIT • LAST 30 EVENTS");
        auditButton.setOnClickListener(v -> showAuditLog());
        root.addView(auditButton);

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
            JSONArray trimmed=new JSONArray();
            int start=Math.max(0,saved.length()-30);
            for(int i=start;i<saved.length();i++) {
                JSONObject item=saved.optJSONObject(i);
                if(item!=null) trimmed.put(item);
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
                .setMessage("Free Gemini 3.6 Flash second-opinion gate. Create a key in Google AI Studio, then paste it here. The key is encrypted with Android Keystore on this device.")
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

    private void requestGeminiReview(final String asset,final String side,final double price,
                                     final String baseAnalysis,final String dailySummary,
                                     final double move3s,final double move1s,final long token) {
        final String key=GeminiSignalAdvisor.loadApiKey(this);
        geminiExecutor.execute(()->{
            GeminiSignalAdvisor.Decision decision=null;
            String error=null;
            try{
                decision=GeminiSignalAdvisor.review(key,asset,side,price,baseAnalysis,dailySummary,move3s,move1s);
            }catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            final GeminiSignalAdvisor.Decision answer=decision;
            final String failure=error;
            runOnUiThread(()->{
                if(token!=geminiReviewToken||!botActive||!asset.equals(activeMarketAsset))return;
                geminiReviewPending=false;
                FiveSecondSignalEngine.Result finalResult;
                if(answer!=null&&answer.approved
                        &&latestLiveTickMs>0&&System.currentTimeMillis()-latestLiveTickMs<=1500L){
                    finalResult=fiveSecondEngine.approvePendingByAi(latestLiveFeedTimeMs,latestLivePrice,
                            answer.confidence,answer.reason);
                }else{
                    String why=answer==null?"Gemini unavailable • "+(failure==null?"request failed":failure)
                            :answer.reason;
                    finalResult=fiveSecondEngine.rejectPendingByAi(why);
                }
                if(!"[]".equals(finalResult.auditEvents))persistAuditEvents(finalResult.auditEvents);
                signalView.setText(finalResult.signal);
                signalView.setTextColor("CALL".equals(finalResult.signal)?Color.rgb(40,230,130)
                        :"PUT".equals(finalResult.signal)?Color.rgb(255,75,85):Color.WHITE);
                if(scanStatus!=null)scanStatus.setText(finalResult.reason);
                if(finalResult.analysis!=null)analysisView.setText(finalResult.analysis);
            });
        });
    }

    private boolean marketLoginDetected = false;

    private void setAutoAnalysis(boolean enabled) {
        if (enabled == botActive) return;
        botActive = enabled;
        if (enabled) {
            engine = new SignalEngine();
            fiveSecondEngine = new FiveSecondSignalEngine();
            geminiReviewToken++;geminiReviewPending=false;
            receivedTicks = 0;
            status.setText("AADI • LIVE FEED READY — AUTOMATIC SIGNALS");
            scanInProgress=false;
            if(scanStatus!=null)scanStatus.setText("Automatic signal analysis active • CALL / PUT / WAIT updates from live ticks");
            lastTick.setText("Collecting real ticks from logged-in chart");
            signalView.setText("WAIT");
            signalView.setTextColor(Color.WHITE);
            analysisView.setText("MARKET TREND • Waiting for verified candles");
        } else {
            status.setText("AADI • Logged out — analysis paused");
            scanInProgress=false;
            engine.cancelScan();
            fiveSecondEngine.cancelScan();
            geminiReviewToken++;geminiReviewPending=false;
            if(generateSignalButton!=null)generateSignalButton.setText("GENERATE SIGNAL");
            if(scanProgress!=null)scanProgress.setVisibility(ViewGroup.GONE);
            signalView.setText("WAIT");
            signalView.setTextColor(Color.WHITE);
        }
    }

    private void startManualScan() {
        if(scanInProgress)return;
        if(!botActive||latestLiveTickMs<=0||System.currentTimeMillis()-latestLiveTickMs>2500L) {
            generateSignalButton.setText("WAIT");
            scanStatus.setText("WAIT • No fresh live market ticks");
            return;
        }
        if(!fiveSecondEngine.requestScan()) {
            generateSignalButton.setText("WAIT");
            scanStatus.setText("WAIT • Previous 10-second signal is still being verified");
            return;
        }
        engine.requestScan();
        scanInProgress=true;
        scanGeneration++;
        final long thisScan=scanGeneration;
        scanProgressValue=0;
        scanProgress.setProgress(0);
        scanProgress.setVisibility(ViewGroup.VISIBLE);
        scanStatus.setText("Scanning trend, momentum, support/resistance and live tick confirmation…");
        generateSignalButton.setText("SCANNING 0%");
        uiHandler.removeCallbacks(scanProgressRunnable);
        uiHandler.post(scanProgressRunnable);
        uiHandler.postDelayed(() -> {
            if(scanInProgress&&scanGeneration==thisScan) {
                scanInProgress=false;
                engine.cancelScan();
                fiveSecondEngine.cancelScan();
                uiHandler.removeCallbacks(scanProgressRunnable);
                scanProgress.setProgress(0);
                scanProgress.setVisibility(ViewGroup.GONE);
                generateSignalButton.setText("WAIT");
                scanStatus.setText("WAIT • Scan timed out: fresh confirmation ticks were insufficient");
            }
        },5000L);
    }

    private void finishManualScan(FiveSecondSignalEngine.Result result,double tickTimestamp,String asset) {
        if(!scanInProgress)return;
        scanInProgress=false;
        uiHandler.removeCallbacks(scanProgressRunnable);
        scanProgress.setProgress(100);
        scanProgress.setVisibility(ViewGroup.GONE);
        showSignal(result,tickTimestamp,asset);
        String reason=result==null?"No confirmed entry":result.reason;
        scanStatus.setText(("CALL".equals(result==null?"WAIT":result.signal)?"CALL • "
                :"PUT".equals(result==null?"WAIT":result.signal)?"PUT • ":"WAIT • ")+reason);
    }

    private void showSignal(FiveSecondSignalEngine.Result result, double tickTimestamp, String asset) {
        String signal=result==null?"WAIT":result.signal;
        signalView.setText(signal);
        if("CALL".equals(signal)) signalView.setTextColor(Color.rgb(40,230,130));
        else if("PUT".equals(signal)) signalView.setTextColor(Color.rgb(255,75,85));
        else signalView.setTextColor(Color.WHITE);
        if(generateSignalButton!=null)generateSignalButton.setText(signal);
        if(result!=null && result.analysis!=null && analysisView!=null)
            analysisView.setText(result.analysis);
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
                    scanInProgress=false;
                    if(generateSignalButton!=null)generateSignalButton.setText("GENERATE SIGNAL");
                    if(scanProgress!=null)scanProgress.setVisibility(ViewGroup.GONE);
                    engine.cancelScan();
                    fiveSecondEngine.cancelScan();
                    engine = new SignalEngine();
                    fiveSecondEngine.reset("");
                    geminiReviewToken++;geminiReviewPending=false;
                    receivedTicks = 0;
                    lastTick.setText("Chart feed disconnected • waiting for verified reconnect");
                    signalView.setText("WAIT");
                    signalView.setTextColor(Color.WHITE);
                }
            });
        }

        @JavascriptInterface public synchronized void onAssetChanged(String asset) {
            if (asset == null || asset.trim().isEmpty()) return;
            scanInProgress=false;
            if(generateSignalButton!=null)generateSignalButton.setText("GENERATE SIGNAL");
            if(scanProgress!=null)scanProgress.setVisibility(ViewGroup.GONE);
            engine = new SignalEngine();
            activeMarketAsset = asset;
            latestLivePrice = Double.NaN;
            latestLiveTickMs = 0;
            fiveSecondEngine.reset(asset);
            geminiReviewToken++;geminiReviewPending=false;
            latestLiveFeedTimeMs=0;
            receivedTicks = 0;
            runOnUiThread(() -> {
                lastSignalFlashBucket = Long.MIN_VALUE;
                lastSignalFlashAsset = asset;
                lastTick.setText(asset + " • refreshing full history and daily reference");
                signalView.setText("WAIT");
                signalView.setTextColor(Color.WHITE);
                analysisView.setText("MARKET TREND • Loading previous day + current day context");
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
                    engine.onOrderBook(asset, book.imbalance, received);
                }
                // #124 depth/change samples may be [asset, scalar]. Use only as a provisional signal vote.
                if ("depth/change".equals(event) && raw instanceof JSONArray) {
                    JSONArray depth = (JSONArray) raw;
                    if (depth.length() >= 2 && depth.opt(1) instanceof Number) {
                        engine.onDepthChange(asset, depth.optDouble(1, Double.NaN), received);
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
            try {
                JSONObject packet = new JSONObject(json);
                String asset = packet.optString("asset", "");
                JSONArray rows = packet.optJSONArray("candles");
                if (asset.isEmpty() || rows == null || !botActive) return;
                
                int imported = engine.importHistoricalCandles(asset, rows);
                dailyContext.importHistory(asset, rows);
                if (imported > 0) runOnUiThread(() -> {
                    lastTick.setText(asset + " • IMPORTED " + imported + " VERIFIED HISTORICAL 10s CANDLES");
                    status.setText("AADI • HISTORY READY | " + engine.currentResult().closedCandles + " candles");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("AADI • Historical candle parse error"));
            }
        }

        @JavascriptInterface public synchronized void onTick(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String asset = o.optString("asset", "");
                double price = o.optDouble("price", Double.NaN);
                double ts = o.optDouble("timestamp", 0);
                if (asset.trim().isEmpty() || !Double.isFinite(price) || price <= 0 || !Double.isFinite(ts)) return;
                double feedSeconds = ts > 10000000000.0 ? ts / 1000.0 : ts;
                double wallSeconds = System.currentTimeMillis() / 1000.0;
                if (feedSeconds <= 0 || feedSeconds < wallSeconds - 45 || feedSeconds > wallSeconds + 8) return;
                if (!botActive) return;
                activeMarketAsset = asset;
                latestLivePrice = price;
                latestLiveTickMs = System.currentTimeMillis();
                latestLiveFeedTimeMs = (long)(feedSeconds*1000.0);
                SignalEngine.Result baseResult = engine.onTick(asset, price, ts);
                long nowSeconds=(long)feedSeconds;
                int dailyBias = dailyContext.bias(asset,price,nowSeconds);
                String dailySummary = dailyContext.summary(asset,price,nowSeconds);
                FiveSecondSignalEngine.Result result = fiveSecondEngine.onTick(asset, price,
                        latestLiveFeedTimeMs, baseResult.signal, dailyBias, dailySummary, baseResult.analysis);

                JSONObject issuedCandidate=null;
                try{
                    JSONArray events=new JSONArray(result.auditEvents);
                    for(int i=0;i<events.length();i++){
                        JSONObject event=events.optJSONObject(i);
                        if(event!=null&&"ISSUED".equals(event.optString("type"))){issuedCandidate=event;break;}
                    }
                }catch(Exception ignored){}

                if(issuedCandidate!=null&&!geminiReviewPending){
                    String apiKey=GeminiSignalAdvisor.loadApiKey(MainActivity.this);
                    if(apiKey.isEmpty()){
                        result=fiveSecondEngine.rejectPendingByAi("Set up a free Gemini API key first");
                    }else{
                        geminiReviewPending=true;
                        final long token=++geminiReviewToken;
                        final JSONObject candidate=issuedCandidate;
                        requestGeminiReview(asset,candidate.optString("signal"),candidate.optDouble("entryPrice",price),
                                candidate.optString("baseAnalysis",baseResult.analysis),
                                candidate.optString("dailySummary",dailySummary),
                                candidate.optDouble("microMove3s",0),candidate.optDouble("microMove1s",0),token);
                        result=new FiveSecondSignalEngine.Result("WAIT","Gemini reviewing OTC setup • waiting for AI confirmation",
                                baseResult.analysis+"\n"+dailySummary+"\nGEMINI REVIEW: PENDING • no entry until approved");
                    }
                }else if(geminiReviewPending){
                    result=new FiveSecondSignalEngine.Result("WAIT","Gemini reviewing OTC setup • waiting for AI confirmation",
                            baseResult.analysis+"\n"+dailySummary+"\nGEMINI REVIEW: PENDING • no entry until approved");
                }

                String safeAudit=withoutIssuedEvents(result.auditEvents);
                if(!"[]".equals(safeAudit))persistAuditEvents(safeAudit);
                final FiveSecondSignalEngine.Result displayResult=result;
                long count = ++receivedTicks;
                String line = asset + "  " + price + "  • TICK #" + count + "  |  REAL FEED";
                long uiNow=SystemClock.uptimeMillis();
                // Analyze every genuine tick, but coalesce display updates to protect the UI thread.
                if(uiNow-lastUiTickAt>=200) {
                    lastUiTickAt=uiNow;
                    runOnUiThread(() -> {
                        lastTick.setText(line);
                        signalView.setText(displayResult==null?"WAIT":displayResult.signal);
                        if(displayResult!=null&&"CALL".equals(displayResult.signal))signalView.setTextColor(Color.rgb(40,230,130));
                        else if(displayResult!=null&&"PUT".equals(displayResult.signal))signalView.setTextColor(Color.rgb(255,75,85));
                        else signalView.setTextColor(Color.WHITE);
                        if(scanStatus!=null)scanStatus.setText(displayResult==null?"Waiting for live analysis":displayResult.reason);
                        if(displayResult!=null&&displayResult.analysis!=null)analysisView.setText(displayResult.analysis);
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
        geminiReviewToken++;geminiReviewPending=false;
        geminiExecutor.shutdownNow();
        super.onDestroy();
    }
}