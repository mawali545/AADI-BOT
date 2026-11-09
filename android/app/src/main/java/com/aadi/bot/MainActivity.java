package com.aadi.bot;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.view.ViewGroup;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private TextView status, lastTick;
    private WebView web;

    private static final String MARKET_URL = "https://market-qx.trade/en/trade";
    private static final String HOOK = "(function(){'use strict';" +
      "if(window.__AADI_HOOKED__)return;window.__AADI_HOOKED__=true;" +
      "const NativeWS=window.WebSocket;let count=0;" +
      "function send(price,asset,time){price=Number(price);if(!Number.isFinite(price)||price<=0||!asset)return;" +
      "try{AadiNative.onTick(JSON.stringify({price:price,asset:String(asset),timestamp:Number(time)||Date.now()/1000}));}catch(e){}}" +
      "function walk(x,d){if(d>8||x==null)return;if(Array.isArray(x)){" +
      "if(x.length>=3&&typeof x[0]==='string'&&Number.isFinite(Number(x[1]))&&Number(x[1])>1000000000&&Number.isFinite(Number(x[2]))&&Number(x[2])>0){send(x[2],x[0],x[1]);return;}" +
      "x.forEach(v=>walk(v,d+1));return;}if(typeof x!=='object')return;" +
      "const a=x.asset||x.symbol||x.pair||x.instrument||x.assetName||x.ticker;" +
      "const t=x.timestamp||x.time||x.ts;" +
      "for(const [k,v] of Object.entries(x)){if(/^(price|currentprice|lastprice|closeprice)$/i.test(k)&&a&&(typeof v==='number'||typeof v==='string')&&Number(v)>0){send(v,a,t);return;}}" +
      "Object.values(x).forEach(v=>{if(v&&typeof v==='object')walk(v,d+1);});}" +
      "function parse(s){try{let q=String(s);if(q.startsWith('42'))q=q.slice(2);if(q.charCodeAt(0)===4)q=q.slice(1);let o=JSON.parse(q);if(Array.isArray(o)&&typeof o[0]==='string')walk(o[1],0);else walk(o,0);}catch(e){}}" +
      "window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){const ws=Reflect.construct(T,args,T);count++;AadiNative.onSocket('WebSocket opened by Market-QX ('+count+')');ws.addEventListener('message',e=>{if(typeof e.data==='string')parse(e.data);else if(e.data instanceof Blob)e.data.text().then(parse).catch(()=>{});});ws.addEventListener('close',()=>AadiNative.onSocket('Market WebSocket closed'));return ws;}});" +
      "AadiNative.onSocket('Market-QX WebSocket hook installed');})();";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8,12,18));
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14);
        status.setPadding(14,12,14,6); status.setText("AADI • Starting Market-QX hook…");
        lastTick = new TextView(this); lastTick.setTextColor(Color.rgb(80,220,150)); lastTick.setTextSize(12);
        lastTick.setPadding(14,4,14,10); lastTick.setText("Waiting for real market ticks");
        root.addView(status); root.addView(lastTick);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString());
        web.addJavascriptInterface(new Bridge(), "AadiNative");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        setContentView(root);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, HOOK,
                java.util.Collections.singleton("https://market-qx.trade"));
            status.setText("AADI • Early page hook enabled");
        } else {
            status.setText("AADI • This Android System WebView lacks early-script support; update WebView");
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
                final String line = o.optString("asset","?") + "  " + o.optDouble("price") +
                    "  • real page tick";
                runOnUiThread(() -> lastTick.setText(line));
            } catch (Exception ignored) { }
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
