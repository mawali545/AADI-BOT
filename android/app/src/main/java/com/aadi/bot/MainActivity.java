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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
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
    private final LiveCandleBuilder liveCandleBuilder = new LiveCandleBuilder();
    private volatile long aiTradeEntryTime = 0;
    private volatile double aiTradeEntryPrice = Double.NaN;
    private volatile String aiTradeSide = "";
    private volatile String aiTradeReason = "";
    private volatile long geminiReviewToken = 0;
    private final java.util.concurrent.ExecutorService geminiExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final String MARKET_URL = "https://market-qx.trade/en/trade";
    private static final String HOOK = "(function(){'use strict';if(window.__AADI_HOOKED__)return;window.__AADI_HOOKED__=true;const NativeWS=window.WebSocket;let activeAsset=null,chartWs=null,pendingBinaryPacket=null;function normTime(v){let n=Number(v);if(!Number.isFinite(n)||n<=0)return 0;if(n>1e12)n/=1000;return n;}function emit(price,asset,time){price=Number(price);if(!Number.isFinite(price)||price<=0||!asset||!activeAsset||String(asset)!==String(activeAsset))return;const t=normTime(time);if(!t)return;const now=Date.now()/1000;if(t<now-45||t>now+8)return;try{AadiNative.onTick(JSON.stringify({price:price,asset:String(asset),timestamp:t}));}catch(e){}}function walkTicks(x,d){if(d>12||x==null)return;if(Array.isArray(x)){if(x.length>=3&&typeof x[0]==='string'&&Number.isFinite(Number(x[1]))&&Number(x[1])>1000000000&&Number.isFinite(Number(x[2]))&&Number(x[2])>0){emit(x[2],x[0],x[1]);return;}for(const v of x)walkTicks(v,d+1);return;}if(typeof x!=='object')return;const asset=x.asset||x.symbol||x.pair||x.instrument||x.assetName||x.ticker||x.active;const time=x.timestamp||x.time||x.ts||x.created_at;for(const [k,v] of Object.entries(x)){if(/^(price|currentprice|lastprice|closeprice|rate|quote)$/i.test(k)&&asset&&(typeof v==='number'||typeof v==='string')&&Number(v)>0){emit(v,asset,time);return;}}for(const v of Object.values(x))if(v&&typeof v==='object')walkTicks(v,d+1);}function parse(s){try{let q=String(s).trim();if(q.startsWith('451-')){pendingBinaryPacket=JSON.parse(q.slice(4));return;}if(pendingBinaryPacket){if(q.charCodeAt(0)===4)q=q.slice(1);const attachment=JSON.parse(q),packet=pendingBinaryPacket;pendingBinaryPacket=null;function hydrate(x){if(!x||typeof x!=='object')return x;if(Array.isArray(x))return x.map(hydrate);if(x._placeholder===true)return attachment;const z={};for(const [k,v] of Object.entries(x))z[k]=hydrate(v);return z;}const rebuilt=[packet[0],hydrate(packet[1])];if(!/history|candle/i.test(String(rebuilt[0])))walkTicks(rebuilt[1],0);return;}if(q.startsWith('42')&&(q[2]==='['||q[2]==='{'))q=q.slice(2);else if(q.charCodeAt(0)===4)q=q.slice(1);const o=JSON.parse(q);if(Array.isArray(o)&&typeof o[0]==='string'){if(!/history|candle/i.test(String(o[0])))walkTicks(o[1],0);}else walkTicks(o,0);}catch(e){}}window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){const ws=Reflect.construct(T,args,T),nativeSend=ws.send.bind(ws);ws.send=function(data){try{const raw=String(data);if(raw.startsWith('42[')){const msg=JSON.parse(raw.slice(2));if(msg[0]==='instruments/update'&&msg[1]&&msg[1].asset){const a=String(msg[1].asset),changed=chartWs!==ws||activeAsset!==a;activeAsset=a;chartWs=ws;if(changed){try{AadiNative.onAssetChanged(a);}catch(e){}try{AadiNative.onSocket('CHART SOCKET ACTIVE • '+a);}catch(e){}}}}catch(e){}return nativeSend(data);};ws.addEventListener('message',function(ev){if(chartWs!==ws)return;if(typeof ev.data==='string')parse(ev.data);else if(ev.data instanceof Blob)ev.data.text().then(parse).catch(function(){});else if(ev.data instanceof ArrayBuffer){try{parse(new TextDecoder().decode(ev.data));}catch(e){}}});ws.addEventListener('close',function(){if(chartWs===ws){chartWs=null;try{AadiNative.onSocket('CHART WEBSOCKET CLOSED • feed paused');}catch(e){}}});return ws;}});try{AadiNative.onSocket('Live QX tick scanner enabled • history disabled');}catch(e){}let loginState=null;function checkLogin(){try{const hasPassword=!!document.querySelector('input[type=password]'),hasLoginText=Array.from(document.querySelectorAll('button,[role=button],input[type=submit]')).some(e=>/^(log in|login|sign in)$/i.test((e.innerText||e.value||'').trim())),hasChart=!!document.querySelector('canvas,[class*=chart i],[id*=chart i]'),logged=hasChart&&!hasPassword&&!hasLoginText&&document.readyState==='complete';if(logged!==loginState){loginState=logged;try{AadiNative.onLoginState(logged);}catch(e){}}}catch(e){}}setInterval(checkLogin,2000);})();";

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
        scanStatus.setTextColor(Color.rgb(90,210,255));
        scanStatus.setTextSize(9);
        scanStatus.setSingleLine(true);
        scanStatus.setEllipsize(TextUtils.TruncateAt.END);
        scanStatus.setIncludeFontPadding(false);
        scanStatus.setGravity(Gravity.CENTER_VERTICAL);
        scanStatus.setPadding(8,2,8,2);
        scanStatus.setText("QX LIVE CANDLES 0/3 • Waiting for real QX ticks");
        root.addView(scanStatus,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,(int)(22*getResources().getDisplayMetrics().density)));

        statsView = new TextView(this);
        statsView.setTextColor(Color.WHITE);
        statsView.setTextSize(18);
        statsView.setGravity(Gravity.CENTER);
        statsView.setPadding(8, 4, 8, 10);
        statsView.setText("WINS 0    LOSSES 0");
        root.addView(statsView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statsView.setOnClickListener(v -> showAuditLog());

        Button hiddenGeminiSettings=new Button(this);
        hiddenGeminiSettings.setText(GeminiSignalAdvisor.loadApiKey(this).isEmpty()
                ? "SET UP FREE GROQ AI KEY" : "AI RESULT • API KEY CONFIGURED");
        root.addView(hiddenGeminiSettings,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        hiddenGeminiSettings.setOnClickListener(v -> showGeminiSettings(hiddenGeminiSettings));
        generateSignalButton=new Button(this);
        generateSignalButton.setText("ANALYZE QX CHART • AI");
        root.addView(generateSignalButton,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        scanProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        scanProgress.setMax(100);scanProgress.setProgress(0);scanProgress.setVisibility(android.view.View.GONE);
        root.addView(scanProgress,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        generateSignalButton.setOnClickListener(v -> startManualChartAnalysis());

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
        web.setBackgroundColor(Color.WHITE);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        restoreStats();

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

    private void restoreStats() {
        sessionWins=0;sessionLosses=0;
        try{
            android.content.SharedPreferences prefs=getSharedPreferences("aadi_signal_audit",MODE_PRIVATE);
            JSONArray saved=new JSONArray(prefs.getString("events","[]"));
            java.util.LinkedHashSet<String> recentSettledIds=new java.util.LinkedHashSet<>();
            for(int i=saved.length()-1;i>=0&&recentSettledIds.size()<30;i--){
                JSONObject item=saved.optJSONObject(i);
                if(item!=null&&"SETTLED".equals(item.optString("type")))
                    recentSettledIds.add(item.optString("tradeId",
                            Long.toString(item.optLong("entryTimeMs",0))));
            }
            for(int i=0;i<saved.length();i++){
                JSONObject item=saved.optJSONObject(i);
                if(item==null||!"SETTLED".equals(item.optString("type")))continue;
                String id=item.optString("tradeId",Long.toString(item.optLong("entryTimeMs",0)));
                if(!recentSettledIds.contains(id))continue;
                if("WIN".equals(item.optString("outcome")))sessionWins++;
                else if("LOSS".equals(item.optString("outcome")))sessionLosses++;
            }
        }catch(Exception ignored){}
        refreshStats();
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
        keyInput.setHint("Paste GroqCloud API key (gsk_...)");
        keyInput.setText(GeminiSignalAdvisor.loadApiKey(this));
        new AlertDialog.Builder(this)
                .setTitle("AADI • GROQ QWEN VISION AI")
                .setMessage("Qwen 3.8 27B Vision analyzes the live QX chart screenshot together with genuine QX candles and ticks when you press ANALYZE. Create a free GroqCloud API key and paste it here. The key is encrypted on this device. Free quota is limited.")
                .setView(keyInput)
                .setPositiveButton("SAVE KEY",(dialog,which)->{
                    try{
                        GeminiSignalAdvisor.saveApiKey(this,keyInput.getText().toString());
                        settingsButton.setText(keyInput.getText().toString().trim().isEmpty()
                                ? "SET UP FREE GROQ AI KEY" : "GROQ QWEN VISION • API KEY CONFIGURED");
                        android.widget.Toast.makeText(this,"Groq API key saved securely on this device",android.widget.Toast.LENGTH_SHORT).show();
                    }catch(Exception e){
                        android.widget.Toast.makeText(this,"Could not save Gemini key: "+e.getClass().getSimpleName(),android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("REMOVE KEY",(dialog,which)->{
                    try{
                        GeminiSignalAdvisor.saveApiKey(this,"");
                        settingsButton.setText("SET UP FREE GROQ AI KEY");
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
            snapshot.put("tickFeedType","real Market-QX chart WebSocket ticks; 10-second OHLC candles built locally from those ticks; no history or synthetic data");
            JSONArray ticks=new JSONArray();
            synchronized(aiRecentTicks){for(JSONObject item:aiRecentTicks)ticks.put(item);}
            JSONArray candles=new JSONArray();
            synchronized(aiRecentCandles){for(JSONObject item:aiRecentCandles)candles.put(item);}
            snapshot.put("recentLiveTicks",ticks);
            snapshot.put("recentLive10SecondCandles",candles);
            JSONObject forming=liveCandleBuilder.getFormingCandle();
            if(forming!=null)snapshot.put("currentlyFormingLiveCandle",forming);
            snapshot.put("totalLiveTicksReceived",receivedTicks);
            snapshot.put("ticksIncluded",ticks.length());
            snapshot.put("candlesIncluded",candles.length());
            snapshot.put("candleIntervalSeconds",10);
            snapshot.put("candleSource","live QX ticks only; closed candles only in recentLive10SecondCandles");
            snapshot.put("snapshotCreatedAt",System.currentTimeMillis());
        } catch(Exception ignored) {}
        return snapshot;
    }

    private int snapshotTickCount(JSONObject snapshot) {
        return snapshot == null ? 0 : snapshot.optInt("ticksIncluded",0);
    }

    private String captureQxChartImageDataUrl() throws Exception {
        if(web==null||web.getWidth()<2||web.getHeight()<2)throw new Exception("QX chart view is not visible yet");
        int width=web.getWidth(),height=web.getHeight();
        float scale=Math.min(1.0f,1100.0f/Math.max(width,height));
        int outW=Math.max(1,Math.round(width*scale)),outH=Math.max(1,Math.round(height*scale));
        Bitmap bitmap=Bitmap.createBitmap(outW,outH,Bitmap.Config.RGB_565);
        Canvas canvas=new Canvas(bitmap);canvas.scale(scale,scale);web.draw(canvas);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG,78,bytes);bitmap.recycle();
        return "data:image/jpeg;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
    }

    private void startManualChartAnalysis() {
        if(scanInProgress||geminiReviewPending)return;
        if(!botActive||web==null){if(scanStatus!=null)scanStatus.setText("WAIT • QX chart login/feed is not ready");return;}
        String key=GeminiSignalAdvisor.loadApiKey(this);
        if(key.isEmpty()){if(scanStatus!=null)scanStatus.setText("GROQ KEY REQUIRED • Tap SET UP FREE GROQ AI KEY");return;}
        if(latestLiveTickMs<=0||System.currentTimeMillis()-latestLiveTickMs>10000L){if(scanStatus!=null)scanStatus.setText("WAIT • No fresh QX tick; chart feed must be live");return;}
        JSONObject snapshot=buildAiMarketSnapshot();
        if(snapshot.optInt("candlesIncluded",0)<3){if(scanStatus!=null)scanStatus.setText("WAIT • Need 3 completed live QX candles; keep chart running");return;}
        final String imageData;
        try{imageData=captureQxChartImageDataUrl();}
        catch(Exception e){if(scanStatus!=null)scanStatus.setText("CHART CAPTURE ERROR • "+e.getMessage());return;}
        final String asset=activeMarketAsset;final long token=++geminiReviewToken;
        geminiReviewPending=true;scanInProgress=true;scanProgressValue=8;
        if(generateSignalButton!=null){generateSignalButton.setEnabled(false);generateSignalButton.setText("GROQ AI ANALYZING…");}
        if(scanProgress!=null){scanProgress.setVisibility(android.view.View.VISIBLE);scanProgress.setProgress(8);}
        if(scanStatus!=null)scanStatus.setText("AI LIVE SCAN • Sending chart screenshot + "+snapshot.optInt("candlesIncluded")+" real candles + "+snapshot.optInt("ticksIncluded")+" ticks…");
        geminiExecutor.execute(()->{
            GeminiSignalAdvisor.Decision decision=null;String failure=null;
            try{decision=GeminiSignalAdvisor.reviewMarket(key,snapshot,imageData);}
            catch(Exception e){failure=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            final GeminiSignalAdvisor.Decision answer=decision;final String error=failure;
            runOnUiThread(()->finishManualChartAnalysis(asset,token,snapshot,answer,error));
        });
    }

    private void finishManualChartAnalysis(String asset,long token,JSONObject snapshot,
            GeminiSignalAdvisor.Decision answer,String error) {
        if(token!=geminiReviewToken||!asset.equals(activeMarketAsset))return;
        geminiReviewPending=false;scanInProgress=false;
        if(generateSignalButton!=null){generateSignalButton.setEnabled(true);generateSignalButton.setText("ANALYZE QX CHART • AI");}
        if(scanProgress!=null){scanProgress.setProgress(100);scanProgress.setVisibility(android.view.View.GONE);}
        if(latestLiveTickMs<=0||System.currentTimeMillis()-latestLiveTickMs>10000L){
            signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
            if(scanStatus!=null)scanStatus.setText("WAIT • QX quote became stale during analysis");return;
        }
        String side="WAIT";
        if(answer!=null&&answer.approved){if(answer.reason.startsWith("CALL"))side="CALL";else if(answer.reason.startsWith("PUT"))side="PUT";}
        if("CALL".equals(side)||"PUT".equals(side)){
            aiTradeEntryTime=latestLiveFeedTimeMs;aiTradeEntryPrice=latestLivePrice;aiTradeSide=side;aiTradeReason=answer.reason;
            signalView.setText(side);signalView.setTextColor("CALL".equals(side)?Color.rgb(40,230,130):Color.rgb(255,75,85));
            if(scanStatus!=null)scanStatus.setText("AI SIGNAL • "+answer.reason+" • confidence "+answer.confidence+" • screenshot + candles reviewed");
        }else{
            signalView.setText("WAIT");signalView.setTextColor(Color.WHITE);
            if(scanStatus!=null)scanStatus.setText(answer==null?"GROQ AI ERROR • "+(error==null?"request failed":error):"GROQ QWEN VISION • "+answer.reason+" • confidence "+answer.confidence);
        }
    }


    // JavaScript bridge used by the live Market-QX WebSocket hook.
    private class Bridge {
        @JavascriptInterface public void onLoginState(boolean loggedIn) {
            runOnUiThread(() -> {
                botActive = loggedIn;
                if (!loggedIn) {
                    latestLiveTickMs = 0;
                    latestLivePrice = Double.NaN;
                    latestLiveFeedTimeMs = 0;
                    synchronized (aiRecentTicks) { aiRecentTicks.clear(); }
                    synchronized (aiRecentCandles) { aiRecentCandles.clear(); }
                    liveCandleBuilder.reset();
                    if (signalView != null) { signalView.setText("WAIT"); signalView.setTextColor(Color.WHITE); }
                    if (scanStatus != null) scanStatus.setText("WAIT • Market-QX login/feed disconnected");
                } else if (scanStatus != null) {
                    scanStatus.setText("QX LIVE CANDLES 0/3 • Logged in, waiting for real QX ticks");
                }
            });
        }

        @JavascriptInterface public void onSocket(String message) {
            runOnUiThread(() -> {
                if (status != null) status.setText("AADI • " + message);
                if (message != null && message.contains("CLOSED")) {
                    latestLiveTickMs = 0;
                    latestLivePrice = Double.NaN;
                    latestLiveFeedTimeMs = 0;
                    synchronized (aiRecentTicks) { aiRecentTicks.clear(); }
                    synchronized (aiRecentCandles) { aiRecentCandles.clear(); }
                    if (signalView != null) { signalView.setText("WAIT"); signalView.setTextColor(Color.WHITE); }
                    if (scanStatus != null) scanStatus.setText("QX SOCKET CLOSED • Waiting for reconnect");
                }
            });
        }

        @JavascriptInterface public synchronized void onAssetChanged(String asset) {
            if (asset == null || asset.trim().isEmpty()) return;
            activeMarketAsset = asset;
            latestLivePrice = Double.NaN;
            latestLiveTickMs = 0;
            latestLiveFeedTimeMs = 0;
            receivedTicks = 0;
            aiTradeEntryTime = 0;
            aiTradeEntryPrice = Double.NaN;
            aiTradeSide = "";
            aiTradeReason = "";
            synchronized (aiRecentTicks) { aiRecentTicks.clear(); }
            synchronized (aiRecentCandles) { aiRecentCandles.clear(); }
            liveCandleBuilder.reset();
            runOnUiThread(() -> {
                if (lastTick != null) lastTick.setText(asset + " • Waiting for genuine QX ticks");
                if (signalView != null) { signalView.setText("WAIT"); signalView.setTextColor(Color.WHITE); }
                if (scanStatus != null) scanStatus.setText("QX LIVE CANDLES 0/3 • " + asset + " • waiting for real ticks");
            });
        }

        @JavascriptInterface public void onDepthMessage(String json) {
            // Depth packets are diagnostic only; the AI decision uses the chart, candles and ticks.
        }

        @JavascriptInterface public synchronized void onTick(String json) {
            try {
                JSONObject packet = new JSONObject(json);
                String asset = packet.optString("asset", "");
                double price = packet.optDouble("price", Double.NaN);
                double ts = packet.optDouble("timestamp", 0);
                if (!botActive || asset.isEmpty() || !asset.equals(activeMarketAsset)
                        || !Double.isFinite(price) || price <= 0 || !Double.isFinite(ts)) return;
                double seconds = ts > 10000000000.0 ? ts / 1000.0 : ts;
                long feedTimeMs = (long) (seconds * 1000.0);
                long wallMs = System.currentTimeMillis();
                if (feedTimeMs <= 0 || feedTimeMs < wallMs - 45000L || feedTimeMs > wallMs + 8000L) return;
                latestLivePrice = price;
                latestLiveTickMs = wallMs;
                latestLiveFeedTimeMs = feedTimeMs;
                JSONObject tick = new JSONObject();
                tick.put("timestamp", feedTimeMs);
                tick.put("price", price);
                synchronized (aiRecentTicks) {
                    aiRecentTicks.addLast(tick);
                    while (!aiRecentTicks.isEmpty()
                            && feedTimeMs - aiRecentTicks.peekFirst().optLong("timestamp") > 60000L)
                        aiRecentTicks.removeFirst();
                    while (aiRecentTicks.size() > 120) aiRecentTicks.removeFirst();
                }
                JSONObject completedCandle = liveCandleBuilder.addTick(asset, feedTimeMs, price);
                if (completedCandle != null) {
                    synchronized (aiRecentCandles) {
                        aiRecentCandles.addLast(completedCandle);
                        while (aiRecentCandles.size() > 60) aiRecentCandles.removeFirst();
                    }
                }
                final int liveCandleCount;
                synchronized (aiRecentCandles) { liveCandleCount = aiRecentCandles.size(); }
                final String candleStatus = "QX LIVE CANDLES " + liveCandleCount + "/3 • "
                        + (liveCandleCount < 3 ? "building from real ticks" : "ready for AI scan");
                runOnUiThread(() -> { if (scanStatus != null && !scanInProgress) scanStatus.setText(candleStatus); });

                // Settle a manual AI decision after its 10-second observation window.
                if (aiTradeEntryTime > 0 && feedTimeMs - aiTradeEntryTime >= 10000L) {
                    String outcome = "WAIT".equals(aiTradeSide) ? "WAIT"
                            : (("CALL".equals(aiTradeSide) && price > aiTradeEntryPrice)
                               || ("PUT".equals(aiTradeSide) && price < aiTradeEntryPrice)) ? "WIN" : "LOSS";
                    JSONObject event = new JSONObject();
                    event.put("type", "SETTLED");
                    event.put("tradeId", Long.toString(aiTradeEntryTime));
                    event.put("entryTimeMs", aiTradeEntryTime);
                    event.put("asset", activeMarketAsset);
                    event.put("signal", aiTradeSide);
                    event.put("outcome", outcome);
                    event.put("entryPrice", aiTradeEntryPrice);
                    event.put("expiryPrice", price);
                    event.put("baseAnalysis", aiTradeReason);
                    JSONArray events = new JSONArray();
                    events.put(event);
                    persistAuditEvents(events.toString());
                    aiTradeEntryTime = 0;
                    aiTradeEntryPrice = Double.NaN;
                    aiTradeSide = "";
                    aiTradeReason = "";
                }
                final long count = ++receivedTicks;
                final String line = asset + "  " + price + "  • REAL QX TICK #" + count;
                if (SystemClock.uptimeMillis() - lastUiTickAt >= 200) {
                    lastUiTickAt = SystemClock.uptimeMillis();
                    runOnUiThread(() -> { if (lastTick != null) lastTick.setText(line); });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (scanStatus != null) scanStatus.setText("QX BRIDGE ERROR • " + e.getClass().getSimpleName());
                });
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