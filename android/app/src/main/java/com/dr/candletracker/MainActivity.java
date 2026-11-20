package com.dr.candletracker;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.widget.Toast;
import android.view.WindowManager;
import android.graphics.Color;
import android.os.Build;
import android.widget.FrameLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat.WebMessageListener;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private WebView web;
    private String trackerScript = "";
    private SecureBridgeCrypto crypto;
    private long tickCount = 0;
    private String currentAsset = "WAITING FOR MARKET";
    private double lastPrice = Double.NaN;
    private long lastTickAt = 0;
    private boolean secureBridgeReady = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(3, 9, 8));
        getWindow().setNavigationBarColor(Color.rgb(3, 9, 8));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { crypto = new SecureBridgeCrypto(); }
        catch (Exception e) { crypto = null; }
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(3, 9, 8));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            web.getSettings().setSafeBrowsingEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        try {
            InputStream in = getAssets().open("dr-tracker.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            trackerScript = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { trackerScript = ""; }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "DRSecure",
                Collections.singleton("https://market-qx.trade"),
                new WebMessageListener() {
                    @Override public void onPostMessage(WebView view, WebMessageCompat message,
                            android.net.Uri sourceOrigin, boolean isMainFrame,
                            androidx.webkit.JavaScriptReplyProxy replyProxy) {
                        if (!isMainFrame || sourceOrigin == null ||
                                !"https".equals(sourceOrigin.getScheme()) ||
                                !"market-qx.trade".equals(sourceOrigin.getHost()) ||
                                crypto == null || message == null) return;
                        try {
                            JSONObject tick = new JSONObject(crypto.decryptEnvelope(message.getData()));
                            final double price = tick.optDouble("price", Double.NaN);
                            final String asset = tick.optString("asset", "");
                            long sourceTime = tick.optLong("timestamp", System.currentTimeMillis());
                            if (!Double.isFinite(price) || price <= 0 || price >= 1e12 ||
                                    !asset.matches("[A-Za-z0-9_./-]{3,32}")) return;
                            final long tickTime = sourceTime > 0 ? sourceTime : System.currentTimeMillis();
                            runOnUiThread(() -> {
                                tickCount++; lastPrice = price; currentAsset = asset; lastTickAt = tickTime;
                                if (web != null) web.evaluateJavascript("window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Secure market bridge rejected an invalid message.", Toast.LENGTH_SHORT).show());
                        }
                    }
                });
            secureBridgeReady = true;
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !trackerScript.isEmpty())
            WebViewCompat.addDocumentStartJavaScript(web, trackerScript,
                Collections.singleton("https://market-qx.trade/*"));

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !trackerScript.isEmpty())
                    view.evaluateJavascript(trackerScript, null);
                if (crypto != null && secureBridgeReady) {
                    String key = JSONObject.quote(crypto.publicKeyBase64());
                    view.evaluateJavascript("window.DRSetPublicKey && window.DRSetPublicKey(" + key + ")", null);
                }
                view.evaluateJavascript("window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                final String detail = error == null ? "TLS certificate validation failed" :
                    "TLS certificate validation failed (" + error.getPrimaryError() + ")";
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    detail + ". Secure connection blocked.", Toast.LENGTH_LONG).show());
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (view != null) view.destroy();
                web = null;
                android.widget.TextView recovery = new android.widget.TextView(MainActivity.this);
                recovery.setText("DR CANDLE TRACKER\n\nMarket-QX page stopped unexpectedly.\nTap to reopen the secure chart.");
                recovery.setTextColor(Color.rgb(160, 255, 190));
                recovery.setTextSize(16);
                recovery.setGravity(android.view.Gravity.CENTER);
                recovery.setBackgroundColor(Color.rgb(3, 9, 8));
                recovery.setOnClickListener(v -> recreate());
                setContentView(recovery, new FrameLayout.LayoutParams(-1, -1));
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String host = request.getUrl().getHost();
                return host == null || !(host.equals("market-qx.trade") || host.endsWith(".market-qx.trade"));
            }
        });
        setContentView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl("https://market-qx.trade/en/trade");
    }

    private JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("ticks", tickCount); o.put("asset", currentAsset);
            o.put("price", Double.isFinite(lastPrice) ? lastPrice : JSONObject.NULL);
            o.put("lastTickAt", lastTickAt);
            o.put("connected", lastTickAt > 0 && System.currentTimeMillis() - lastTickAt < 8000);
            o.put("secureBridge", secureBridgeReady && crypto != null);
        } catch (Exception ignored) {}
        return o;
    }

    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.DRBack && window.DRBack()", null);
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
