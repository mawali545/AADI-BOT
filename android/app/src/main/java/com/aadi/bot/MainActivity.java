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
import android.graphics.Color;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private TextView status, lastTick, signalView, diagnosticView;
    private Button analyzeButton;
    private TextView signalBubble;
    private FrameLayout stage;
    private final SignalEngine engine = new SignalEngine();
    private WebView web;
    private long receivedTicks = 0;

    private static final String MARKET_URL = "https://market-qx.trade/en/trade";
    private static final String HOOK =
        "(function(){'use strict';" +
        "if(window.__AADI_HOOKED__)return;window.__AADI_HOOKED__=true;" +
        "const NativeWS=window.WebSocket;const seen=new Set();let diagCount=0;" +
        "function emit(price,asset,time){" +
        "price=Number(price);if(!Number.isFinite(price)||price<=0||!asset)return;" +
        "try{AadiNative.onTick(JSON.stringify({price:price,asset:String(asset),timestamp:Number(time)||Date.now()/1000}));}catch(e){}" +
        "}" +
        "function safeShape(root){" +
        "const out=[];function walk(x,p,d){" +
        "if(d>7||x==null||out.length>=12)return;" +
        "if(Array.isArray(x)){" +
        "if(x.length&&typeof x[0]==='object'&&x[0]!==null&&!Array.isArray(x[0])){" +
        "const k=Object.keys(x[0]).slice(0,24);const low=k.map(z=>z.toLowerCase());" +
        "if(low.some(z=>['open','o'].includes(z))&&low.some(z=>['high','h'].includes(z))&&low.some(z=>['low','l'].includes(z))&&low.some(z=>['close','c'].includes(z)))out.push(p+': object-candle-array len='+x.length+' fields='+k.join(','));" +
        "}" +
        "else if(x.length&&Array.isArray(x[0])){const n=x[0].length;const types=x[0].slice(0,8).map(v=>typeof v).join(',');if(n>=4)out.push(p+': nested-array len='+x.length+' rowLen='+n+' firstTypes='+types);}" +
        "for(let i=0;i<Math.min(x.length,4)&&out.length<12;i++)if(x[i]&&typeof x[i]==='object')walk(x[i],p+'['+i+']',d+1);return;" +
        "}" +
        "if(typeof x!=='object')return;const keys=Object.keys(x).slice(0,30);const low=keys.map(z=>z.toLowerCase());" +
        "if(low.some(z=>['open','o'].includes(z))&&low.some(z=>['high','h'].includes(z))&&low.some(z=>['low','l'].includes(z))&&low.some(z=>['close','c'].includes(z)))out.push(p+': OHLC-object fields='+keys.join(','));" +
        "if(keys.length)out.push(p+': object fields='+keys.join(','));" +
        "for(const k of keys){const v=x[k];if(v&&typeof v==='object'&&out.length<12)walk(v,p+'.'+k,d+1);}" +
        "}" +
        "walk(root,'$',0);return out.slice(0,12).join('\\n');" +
        "}" +
        "function diagnostic(o){" +
        "if(diagCount>=20)return;const s=safeShape(o);if(!s)return;" +
        "const sig=s.split('\\n').join('|');if(seen.has(sig))return;seen.add(sig);diagCount++;" +
        "try{AadiNative.onDiagnostic('Packet '+diagCount+'\\n'+s);}catch(e){}" +
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
        "const o=JSON.parse(q);diagnostic(o);if(Array.isArray(o)&&typeof o[0]==='string')walk(o[1],0);else walk(o,0);" +
        "}catch(e){}}" +
        "window.WebSocket=new Proxy(NativeWS,{construct:function(T,args){" +
        "const ws=Reflect.construct(T,args,T);" +
        "try{AadiNative.onSocket('Market-QX WebSocket opened');}catch(e){}" +
        "ws.addEventListener('message',function(ev){" +
        "if(typeof ev.data==='string')parse(ev.data);" +
        "else if(ev.data instanceof Blob)ev.data.text().then(parse).catch(function(){});" +
        "else if(ev.data instanceof ArrayBuffer){try{parse(new TextDecoder().decode(ev.data));}catch(e){}}" +
        "});" +
        "ws.addEventListener('close',function(){try{AadiNative.onSocket('Market WebSocket closed');}catch(e){}});" +
        "return ws;}});" +
        "try{AadiNative.onSocket('Safe packet diagnostics enabled');}catch(e){}" +
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

        diagnosticView = new TextView(this);
        diagnosticView.setTextColor(Color.rgb(190, 200, 215));
        diagnosticView.setTextSize(10);
        diagnosticView.setPadding(14, 4, 14, 4);
        diagnosticView.setMaxLines(8);
        diagnosticView.setText("SAFE DIAGNOSTICS: waiting for Market-QX packet shapes");
        root.addView(diagnosticView);

        Button copyDiagnostics = new Button(this);
        copyDiagnostics.setText("COPY SAFE DIAGNOSTICS");
        copyDiagnostics.setTextSize(11);
        copyDiagnostics.setOnClickListener(v -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("AADI safe diagnostics", diagnosticView.getText()));
            android.widget.Toast.makeText(this, "Diagnostics copied", android.widget.Toast.LENGTH_SHORT).show();
        });
        root.addView(copyDiagnostics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        signalBubble = new TextView(this);
        signalBubble.setText("WAIT • TAP ANALYZE");
        signalBubble.setTextColor(Color.WHITE);
        signalBubble.setTextSize(12);
        signalBubble.setTypeface(null, android.graphics.Typeface.BOLD);
        signalBubble.setGravity(Gravity.CENTER);
        signalBubble.setPadding(dp(14), dp(11), dp(14), dp(11));
        signalBubble.setBackground(makeBubbleBackground(Color.rgb(75, 85, 99)));

        analyzeButton = new Button(this);
        analyzeButton.setText("ANALYZE MARKET");
        analyzeButton.setTextSize(14);
        analyzeButton.setOnClickListener(v -> {
            SignalEngine.Result result = engine.analyzeNow();
            signalView.setText("ANALYSIS: " + result.signal + "  |  Score: " + result.score +
                    "\n" + result.reason + "  |  Closed candles: " + result.closedCandles);
            signalBubble.setText(result.signal.equals("CALL") ? "CALL • UP" :
                    result.signal.equals("PUT") ? "PUT • DOWN" : "WAIT");
            signalBubble.setBackground(makeBubbleBackground(result.signal.equals("CALL") ?
                    Color.rgb(16, 145, 92) : result.signal.equals("PUT") ?
                    Color.rgb(190, 55, 65) : Color.rgb(75, 85, 99)));
        });
        root.addView(analyzeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        stage = new FrameLayout(this);
        stage.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        bubbleParams.setMargins(0, 0, dp(16), dp(90));
        stage.addView(signalBubble, bubbleParams);
        attachMovableBubble();
        setContentView(stage);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, HOOK,
                    java.util.Collections.singleton("https://market-qx.trade"));
            status.setText("AADI • Early WebSocket hook enabled");
        } else {
            status.setText("AADI • Update Android System WebView; early hook unsupported");
        }
        web.loadUrl(MARKET_URL);
    }


    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.GradientDrawable makeBubbleBackground(int color) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), Color.WHITE);
        return bg;
    }

    private void attachMovableBubble() {
        signalBubble.setElevation(dp(12));
        signalBubble.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downRawX, downRawY, startX, startY;
            boolean dragging = false;

            @Override public boolean onTouch(android.view.View view, MotionEvent event) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = view.getX();
                    startY = view.getY();
                    dragging = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(4)) dragging = true;
                    if (dragging) {
                        int maxX = Math.max(0, stage.getWidth() - view.getWidth());
                        int maxY = Math.max(0, stage.getHeight() - view.getHeight());
                        lp.gravity = Gravity.TOP | Gravity.LEFT;
                        lp.leftMargin = Math.max(0, Math.min(maxX, Math.round(startX + dx)));
                        lp.topMargin = Math.max(0, Math.min(maxY, Math.round(startY + dy)));
                        lp.rightMargin = 0;
                        lp.bottomMargin = 0;
                        view.setLayoutParams(lp);
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP ||
                        event.getAction() == MotionEvent.ACTION_CANCEL) return true;
                return true;
            }
        });
    }

    private class Bridge {
        @JavascriptInterface public void onSocket(String message) {
            runOnUiThread(() -> status.setText("AADI • " + message));
        }

        @JavascriptInterface public void onDiagnostic(String message) {
            runOnUiThread(() -> {
                String old = diagnosticView.getText().toString();
                diagnosticView.setText((old + "\n\n" + message).length() > 5000
                        ? message : old + "\n\n" + message);
            });
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
