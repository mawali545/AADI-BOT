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
import android.graphics.Color;
import android.view.ViewGroup;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private TextView status, lastTick, signalView;
    private final SignalEngine engine = new SignalEngine();
    private WebView web;
    private long receivedTicks = 0;

    private static final String MARKET_URL = "https://market-qx.trade/en/trade";
    private static final String HOOK =
        "(function(){'use strict';" +
        "if(window.__AADI_HOOKED__)return;window.__AADI_HOOKED__=true;" +
        "const NativeWS=window.WebSocket;" +
        "function emit(price,asset,time){" +
        "price=Number(price);if(!Number.isFinite(price)||price<=0||!asset)return;" +
        "try{AadiNative.onTick(JSON.stringify({price:price,asset:String(asset),timestamp:Number(time)||Date.now()/1000}));}catch(e){}" +
        "}" +
        "function walk(x,d){" +
        "if(d>10||x==null)return;" +
        "if(Array.isArray(x)){" +
        "if(x.length>=3&&typeof x[0]==='string'&&Number.isFinite(Number(x[1]))&&Number(x[1])>1000000000&&Number.isFinite(Number(x[2]))&&Number(x[2])>0){emit(x[2],x[0],x[1]);return;}" +
        "for(const v of x)walk(v,d+1);return;}" +
        "if(typeof x!=='object')return;" +
        "const asset=x.asset||x.symbol||x.pair||x.instrument||x.assetName||x.ticker||x.active;" +
        "const time=x.timestamp||x.time||x.ts||x.created_at;" +
        "for(const [k,v] of Object.entries(x)){" +
        "if(/^(price|currentprice|lastprice|closeprice|rate|quote)$/i.test(k)&&asset&&(typeof v==='number'||typeof v==='string')&&Number(v)>0){emit(v,asset,time);return;}" +
        "}" +
        "for(const v of Object.values(x))if(v&&typeof v==='object')walk(v,d+1);" +
        "}" +
        "function parse(s){" +
        "try{let q=String(s).trim();if(q.startsWith('42')&&(q[2]==='['||q[2]==='{'))q=q.slice(2);else if(q.charCodeAt(0)===4)q=q.slice(1);" +
        "const o=JSON.parse(q);if(Array.isArray(o)&&typeof o[0]==='string')walk(o[1],0);else walk(o,0);" +
        "}catch(e){}" +
        "}" +
        "window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){" +
        "const ws=Reflect.construct(T,args,T);" +
        "try{AadiNative.onSocket('Market-QX WebSocket opened');}catch(e){}" +
        "ws.addEventListener('message',function(ev){" +
        "if(typeof ev.data==='string')parse(ev.data);" +
        "else if(ev.data instanceof Blob)ev.data.text().then(parse).catch(function(){});" +
        "else if(ev.data instanceof ArrayBuffer){try{parse(new TextDecoder().decode(ev.data));}catch(e){}}" +
        "});" +
        "ws.addEventListener('close',function(){try{AadiNative.onSocket('Market WebSocket closed');}catch(e){}});" +
        "return ws;" +
        "}});" +
        "try{AadiNative.onSocket('Market-QX WebSocket hook installed');}catch(e){}" +
        "})();";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 12, 18));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setPadding(14, 12, 14, 6);
        status.setText("AADI • Starting Market-QX hook…");
        lastTick = new TextView(this);
        lastTick.setTextColor(Color.rgb(80, 220, 150));
        lastTick.setTextSize(12);
        lastTick.setPadding(14, 4, 14, 10);
        lastTick.setText("No real tick received");
        signalView = new TextView(this);
        signalView.setTextColor(Color.WHITE);
        signalView.setTextSize(16);
        signalView.setPadding(14, 4, 14, 10);
        signalView.setText("SIGNAL: WAIT • Waiting for real candles");
        root.addView(status);
        root.addView(lastTick);
        root.addView(signalView);

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

    private class Bridge {
        @JavascriptInterface public void onSocket(String message) {
            runOnUiThread(() -> status.setText("AADI • " + message));
        }

        @JavascriptInterface public void onTick(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String asset = o.optString("asset", "");
                double price = o.optDouble("price", Double.NaN);
                double ts = o.optDouble("timestamp", System.currentTimeMillis() / 1000.0);
                if (asset.trim().isEmpty() || !Double.isFinite(price) || price <= 0) return;
                SignalEngine.Result result = engine.onTick(asset, price, ts);
                long count = ++receivedTicks;
                String line = asset + "  " + price + "  • REAL PAGE TICK #" + count;
                String signal = "SIGNAL: " + result.signal + "  |  Score: " + result.score +
                        "\n" + result.reason + "  |  Closed candles: " + result.closedCandles;
                runOnUiThread(() -> {
                    lastTick.setText(line);
                    signalView.setText(signal);
                    status.setText("AADI • Receiving Market-QX page data");
                });
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
