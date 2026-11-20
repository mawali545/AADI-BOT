package com.dr.candletracker;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Color;
import android.os.Build;
import android.widget.FrameLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private WebView web;
    private String trackerScript = "";
    private long tickCount = 0;
    private String currentAsset = "WAITING FOR MARKET";
    private double lastPrice = Double.NaN;
    private long lastTickAt = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(3, 9, 8));
        getWindow().setNavigationBarColor(Color.rgb(3, 9, 8));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(3, 9, 8));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "DRNative");
        try {
            InputStream in = getAssets().open("dr-tracker.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            trackerScript = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { trackerScript = ""; }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !trackerScript.isEmpty()) {
            WebViewCompat.addDocumentStartJavaScript(web, trackerScript, java.util.Collections.singleton("https://market-qx.trade/*"));
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !trackerScript.isEmpty()) {
                    view.evaluateJavascript(trackerScript, null);
                }
                view.evaluateJavascript("window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && (host.equals("market-qx.trade") || host.endsWith(".market-qx.trade"))) return false;
                return false;
            }
        });
        setContentView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl("https://market-qx.trade/en/trade");
    }

    private JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("ticks", tickCount);
            o.put("asset", currentAsset);
            o.put("price", Double.isFinite(lastPrice) ? lastPrice : JSONObject.NULL);
            o.put("lastTickAt", lastTickAt);
            o.put("connected", lastTickAt > 0 && System.currentTimeMillis() - lastTickAt < 8000);
        } catch (Exception ignored) {}
        return o;
    }

    private final class Bridge {
        @JavascriptInterface public void onTick(String json) {
            try {
                JSONObject o = new JSONObject(json);
                final double p = o.optDouble("price", Double.NaN);
                final String a = o.optString("asset", "");
                if (!Double.isFinite(p) || p <= 0 || a.isEmpty()) return;
                runOnUiThread(() -> {
                    tickCount++;
                    lastPrice = p;
                    currentAsset = a;
                    lastTickAt = System.currentTimeMillis();
                    if (web != null) web.evaluateJavascript("window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                });
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public void onSocket(String message) {
            final String safe = JSONObject.quote(message == null ? "" : message);
            runOnUiThread(() -> {
                if (web != null) web.evaluateJavascript("window.DRLog && window.DRLog(" + safe + ")", null);
            });
        }
    }

    @Override public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript("window.DRBack && window.DRBack()", null);
        } else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) { web.removeJavascriptInterface("DRNative"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
