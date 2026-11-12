package com.aadi.bot;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
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
    private TextView status, lastTick, signalView, depthView;
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
        "const NativeWS=window.WebSocket;let activeAsset=null,chartWs=null,pendingBinaryPacket=null,depthRequestedAsset=null;function depthTap(ev,payload){try{const raw=JSON.stringify(payload);const txt=String(ev||'')+' '+raw.slice(0,12000);if(/depth|order.?book|book|bids|asks/i.test(txt)){AadiNative.onDepthMessage(JSON.stringify({asset:activeAsset||'',event:String(ev||'unknown'),receivedAt:Date.now(),payload:payload}));}}catch(e){}}function followDepth(){try{if(!chartWs||chartWs.readyState!==1||!activeAsset||depthRequestedAsset===activeAsset)return;depthRequestedAsset=activeAsset;chartWs.send('42[\"depth/follow\",'+JSON.stringify(activeAsset)+']');AadiNative.onDepthStatus('depth/follow sent • '+activeAsset);}catch(e){depthRequestedAsset=null;AadiNative.onDepthStatus('depth/follow send failed');}}" +
        "function normTime(v){let n=Number(v);if(!Number.isFinite(n)||n<=0)return 0;if(n>1e12)n/=1000;return n;}" +
        "function emit(price,asset,time){price=Number(price);if(!Number.isFinite(price)||price<=0||!asset||!activeAsset||String(asset)!==String(activeAsset))return;const feedTime=normTime(time);if(!feedTime)return;const now=Date.now()/1000;if(feedTime<now-45||feedTime>now+8)return;try{AadiNative.onTick(JSON.stringify({price:price,asset:String(asset),timestamp:feedTime}));}catch(e){}}" +
        "function walkTicks(x,d){if(d>10||x==null)return;if(Array.isArray(x)){if(x.length>=3&&typeof x[0]==='string'&&Number.isFinite(Number(x[1]))&&Number(x[1])>1000000000&&Number.isFinite(Number(x[2]))&&Number(x[2])>0){emit(x[2],x[0],x[1]);return;}for(const v of x)walkTicks(v,d+1);return;}if(typeof x!=='object')return;const asset=x.asset||x.symbol||x.pair||x.instrument||x.assetName||x.ticker||x.active;const time=x.timestamp||x.time||x.ts||x.created_at;for(const [k,v] of Object.entries(x)){if(/^(price|currentprice|lastprice|closeprice|rate|quote)$/i.test(k)&&asset&&(typeof v==='number'||typeof v==='string')&&Number(v)>0){emit(v,asset,time);return;}}for(const v of Object.values(x))if(v&&typeof v==='object')walkTicks(v,d+1);}" +
        "function parse(s){try{let q=String(s).trim();if(q.startsWith('451-')){pendingBinaryPacket=JSON.parse(q.slice(4));return;}if(pendingBinaryPacket){if(q.charCodeAt(0)===4)q=q.slice(1);const attachment=JSON.parse(q),packet=pendingBinaryPacket;pendingBinaryPacket=null;function hydrate(x){if(!x||typeof x!=='object')return x;if(Array.isArray(x))return x.map(hydrate);if(x._placeholder===true)return attachment;const z={};for(const [k,v] of Object.entries(x))z[k]=hydrate(v);return z;}const rebuilt=[packet[0],hydrate(packet[1])];const ev=String(rebuilt[0]);depthTap(ev,rebuilt[1]);if(ev!=='history/load'&&ev!=='history'&&ev!=='candles/load')walkTicks(rebuilt[1],0);return;}if(q.startsWith('42')&&(q[2]==='['||q[2]==='{'))q=q.slice(2);else if(q.charCodeAt(0)===4)q=q.slice(1);const o=JSON.parse(q);if(Array.isArray(o)&&typeof o[0]==='string'){const ev=String(o[0]);depthTap(ev,o[1]);if(ev!=='history/load'&&ev!=='history'&&ev!=='candles/load')walkTicks(o[1],0);}else walkTicks(o,0);}catch(e){}}" +
        "window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){const ws=Reflect.construct(T,args,T);const nativeSend=ws.send.bind(ws);ws.send=function(data){try{const raw=String(data);if(raw.startsWith('42[')){const msg=JSON.parse(raw.slice(2));if(msg[0]==='instruments/update'&&msg[1]&&msg[1].asset){const a=String(msg[1].asset);const changed=chartWs!==ws||activeAsset!==a;activeAsset=a;chartWs=ws;if(changed){try{AadiNative.onSocket('CHART SOCKET ACTIVE • '+a);}catch(e){}}}}}catch(e){}const sent=nativeSend(data);if(chartWs===ws)followDepth();return sent;};ws.addEventListener('message',function(ev){if(chartWs!==ws)return;if(typeof ev.data==='string')parse(ev.data);else if(ev.data instanceof Blob)ev.data.text().then(parse).catch(function(){});else if(ev.data instanceof ArrayBuffer){try{parse(new TextDecoder().decode(ev.data));}catch(e){}}});ws.addEventListener('close',function(){if(chartWs===ws){chartWs=null;depthRequestedAsset=null;try{AadiNative.onSocket('CHART WEBSOCKET CLOSED • feed paused');AadiNative.onDepthStatus('Waiting for chart WebSocket');}catch(e){}}});return ws;}});" +
        "try{AadiNative.onSocket('Live chart tick scanner enabled');}catch(e){}" +
        "let loginState=null;function checkLogin(){try{const hasPassword=!!document.querySelector('input[type=password]');const hasLoginText=Array.from(document.querySelectorAll('button,[role=button],input[type=submit]')).some(e=>/^(log in|login|sign in)$/i.test((e.innerText||e.value||'').trim()));const hasChart=!!document.querySelector('canvas,[class*=chart i],[id*=chart i]');const logged=hasChart&&!hasPassword&&!hasLoginText&&document.readyState==='complete';if(logged!==loginState){loginState=logged;try{AadiNative.onLoginState(logged);}catch(e){}}}catch(e){}}setInterval(checkLogin,2000);" +
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
        depthView = new TextView(this);
        depthView.setTextColor(Color.rgb(115, 220, 205));
        depthView.setTextSize(11);
        depthView.setPadding(14, 10, 14, 10);
        depthView.setMaxLines(7);
        depthView.setText(\"ORDER BOOK • Waiting for live depth response\");
        root.addView(depthView);
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
        String display="SIGNAL: " + result.signal + "\\n"
                + result.reason + "  |  Live candles: " + result.closedCandles;
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
            runOnUiThread(() -> {
                status.setText("AADI • " + message);
                if (message != null && message.contains("CLOSED")) {
                    engine = new SignalEngine();
                    receivedTicks = 0;
                    lastTick.setText("Chart feed disconnected • waiting for verified reconnect");
                    signalView.setText("WAIT • FEED DISCONNECTED");
                    signalView.setTextColor(Color.WHITE);
                }
            });
        }

        @JavascriptInterface public void onDepthStatus(String message) {
            runOnUiThread(() -> depthView.setText(\"ORDER BOOK • \" + message));
        }

        @JavascriptInterface public void onDepthMessage(String json) {
            try {
                JSONObject packet = new JSONObject(json);
                String asset = packet.optString(\"asset\", \"unknown\");
                String event = packet.optString(\"event\", \"unknown\");
                long received = packet.optLong(\"receivedAt\", 0);
                String payload = packet.optJSONObject(\"payload\") != null
                        ? packet.optJSONObject(\"payload\").toString()
                        : String.valueOf(packet.opt(\"payload\"));
                if (payload.length() > 850) payload = payload.substring(0, 850) + \"…\";
                final String display = \"ORDER BOOK RESPONSE • \" + asset + \"\\nEvent: \" + event
                        + \"\\nReceived: \" + (received > 0 ? new java.text.SimpleDateFormat(\"HH:mm:ss.SSS\", java.util.Locale.US).format(new java.util.Date(received)) : \"unknown\")
                        + \"\\nRaw depth payload: \" + payload;
                runOnUiThread(() -> depthView.setText(display));
            } catch (Exception e) {
                runOnUiThread(() -> depthView.setText(\"ORDER BOOK • Depth message parse error\"));
            }
        }

        @JavascriptInterface public void onTick(String json) {
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