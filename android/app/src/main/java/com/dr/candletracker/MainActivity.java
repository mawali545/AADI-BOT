package com.dr.candletracker;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat.WebMessageListener;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private WebView web;
    private String trackerScript = "";
    private volatile SecureBridgeCrypto crypto;
    private volatile boolean secureBridgeReady = false;
    private volatile boolean cryptoHandshakeVerified = false;
    private volatile boolean pageReady = false;
    private volatile boolean documentStartScriptInstalled = false;
    private long tickCount = 0, lastTickAt = 0, lastTickReceivedAt = 0;
    private String currentAsset = "WAITING FOR MARKET";
    private String currentFingerprint = "";
    private int currentFrameBytes = 0;
    private double lastPrice = Double.NaN;
    private final SignalEngine signalEngine = new SignalEngine();
    private final FeedIntegrityMonitor feedMonitor = new FeedIntegrityMonitor();
    private volatile double qxHeartbeatSendMs = Double.NaN, qxHttpResponseMs = Double.NaN;
    private volatile long qxHeartbeatWallAt = 0, qxHttpWallAt = 0;
    private final ExecutorService cryptoExecutor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(3, 12, 8));
        getWindow().setNavigationBarColor(Color.rgb(3, 12, 8));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            showStartup();
            loadTrackerScript();
            createWebView();
            setContentView(web, new FrameLayout.LayoutParams(-1, -1));
            web.loadUrl("https://market-qx.trade/en/trade");
            initializeCryptoOffMainThread();
        } catch (Throwable error) {
            showFatal("Startup error", error);
        }
    }

    private void showStartup() {
        TextView v = new TextView(this);
        v.setText("DR CANDLE TRACKER\\n\\nStarting secure Market-QX connection…");
        v.setTextColor(Color.rgb(150, 255, 185));
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(3, 12, 8));
        setContentView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private void loadTrackerScript() {
        try (InputStream in = getAssets().open("dr-tracker.js");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            trackerScript = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            trackerScript = "";
        }
    }

    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(3, 12, 8));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { settings.setSafeBrowsingEnabled(true); } catch (Throwable ignored) {}
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(web, "DRSecure",
                    Collections.singleton("https://market-qx.trade"),
                    new WebMessageListener() {
                        @Override public void onPostMessage(WebView view, WebMessageCompat message,
                                Uri origin, boolean mainFrame, JavaScriptReplyProxy reply) {
                            SecureBridgeCrypto active = crypto;
                            if (!mainFrame || origin == null ||
                                !"https".equals(origin.getScheme()) ||
                                !"market-qx.trade".equals(origin.getHost()) ||
                                active == null || message == null) return;
                            try {
                                JSONObject tick = new JSONObject(active.decryptEnvelope(message.getData()));
                                String type = tick.optString("type", "tick");
                                if ("crypto_probe".equals(type)) {
                                    cryptoHandshakeVerified = "RSA-OAEP-256/AES-256-GCM".equals(tick.optString("suite", ""));
                                    runOnUiThread(() -> {
                                        if (web != null) web.evaluateJavascript(
                                            "window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                                    });
                                    return;
                                }
                                if ("telemetry".equals(type)) {
                                    double heartbeatMs = tick.optDouble("heartbeatSendMs", Double.NaN);
                                    long heartbeatAt = tick.optLong("heartbeatAt", 0);
                                    double httpMs = tick.optDouble("httpResponseMs", Double.NaN);
                                    long httpAt = tick.optLong("httpResponseAt", 0);
                                    if (Double.isFinite(heartbeatMs) && heartbeatMs >= 0 && heartbeatMs < 60000 && heartbeatAt > 0) {
                                        qxHeartbeatSendMs = heartbeatMs;
                                        qxHeartbeatWallAt = heartbeatAt;
                                    }
                                    if (Double.isFinite(httpMs) && httpMs >= 0 && httpMs < 60000 && httpAt > 0) {
                                        qxHttpResponseMs = httpMs;
                                        qxHttpWallAt = httpAt;
                                    }
                                    runOnUiThread(() -> {
                                        if (web != null) web.evaluateJavascript(
                                            "window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                                    });
                                    return;
                                }
                                String source = tick.optString("source", "");
                                final double heartbeatMs = tick.optDouble("heartbeatSendMs", Double.NaN);
                                final long heartbeatAt = tick.optLong("heartbeatAt", 0);
                                final double httpMs = tick.optDouble("httpResponseMs", Double.NaN);
                                final long httpAt = tick.optLong("httpResponseAt", 0);
                                String asset = tick.optString("asset", "");
                                String fingerprint = tick.optString("frameFingerprint", "");
                                int frameBytes = tick.optInt("frameBytes", 0);
                                long period = tick.optLong("period", 10);
                                if (!asset.matches("[A-Za-z0-9_./-]{3,32}") ||
                                    !fingerprint.matches("(?i)[a-f0-9]{64}") ||
                                    frameBytes < 1 || frameBytes > 1048576 || period < 1 || period > 86400) return;
                                final String frameHash = fingerprint.toLowerCase(java.util.Locale.ROOT);
                                final int observedFrameBytes = frameBytes;
                                if ("history".equals(type)) {
                                    if (!("history/list/v2".equals(source) || "history/load".equals(source) ||
                                        "chart_notification/get".equals(source) || "loadHistoryPeriod".equals(source))) return;
                                    org.json.JSONArray candles = tick.optJSONArray("candles");
                                    if (candles == null || candles.length() == 0) return;
                                    runOnUiThread(() -> {
                                        currentAsset = asset;
                                        currentFingerprint = frameHash; currentFrameBytes = observedFrameBytes;
                                        if (!feedMonitor.recordHistory(asset, period, candles)) return;
                                        signalEngine.acceptHistory(asset, period, candles);
                                        if (web != null) web.evaluateJavascript(
                                            "window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                                    });
                                    return;
                                }
                                double price = tick.optDouble("price", Double.NaN);
                                long stamp = tick.optLong("timestamp", 0);
                                Object rawDirection = tick.opt("tickDirection");
                                Integer brokerDirection = null;
                                if (rawDirection instanceof Number) {
                                    int d = ((Number) rawDirection).intValue();
                                    if (d >= -1 && d <= 1) brokerDirection = d;
                                }
                                final Integer tickDirection = brokerDirection;
                                if (!"tick".equals(type) || source.isEmpty() ||
                                    !Double.isFinite(price) || price <= 0 || price >= 1e12 || stamp <= 0) return;
                                final long tickTime = stamp;
                                final long nativeArrivalElapsed = SystemClock.elapsedRealtime();
                                if (tickTime > System.currentTimeMillis() + 5000L) return;
                                runOnUiThread(() -> {
                                    if (!feedMonitor.recordTick(asset, tickTime, nativeArrivalElapsed, period)) return;
                                    tickCount++; lastPrice = price; currentAsset = asset; lastTickAt = tickTime; lastTickReceivedAt = System.currentTimeMillis();
                                    if (Double.isFinite(heartbeatMs) && heartbeatMs >= 0 && heartbeatMs < 60000) { qxHeartbeatSendMs = heartbeatMs; qxHeartbeatWallAt = heartbeatAt; }
                                    if (Double.isFinite(httpMs) && httpMs >= 0 && httpMs < 60000) { qxHttpResponseMs = httpMs; qxHttpWallAt = httpAt; }
                                    currentFingerprint = frameHash; currentFrameBytes = observedFrameBytes;
                                    signalEngine.acceptTick(asset, price, tickTime, period, tickDirection);
                                    if (web != null) web.evaluateJavascript(
                                        "window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                                });
                            } catch (Exception ignored) {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "Secure market message rejected.", Toast.LENGTH_SHORT).show());
                            }
                        }
                    });
                secureBridgeReady = true;
            } catch (Throwable listenerError) {
                secureBridgeReady = false;
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !trackerScript.isEmpty()) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, trackerScript,
                    Collections.singleton("https://market-qx.trade"));
                documentStartScriptInstalled = true;
            } catch (Throwable ignored) {
                documentStartScriptInstalled = false;
            }
        }

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                // Feature support alone does not prove document-start registration succeeded.
                if (!documentStartScriptInstalled && !trackerScript.isEmpty())
                    view.evaluateJavascript(trackerScript, null);
                publishNativeKey();
                view.evaluateJavascript("window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(MainActivity.this, "TLS validation failed. Secure connection blocked.", Toast.LENGTH_LONG).show();
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != null) view.destroy();
                web = null;
                showFatal("Market chart stopped", new RuntimeException("Android WebView renderer exited. Tap RESTART to reopen."));
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String host = request.getUrl().getHost();
                return host == null || !(host.equals("market-qx.trade") || host.endsWith(".market-qx.trade"));
            }
        });
    }

    private void initializeCryptoOffMainThread() {
        cryptoExecutor.execute(() -> {
            try {
                SecureBridgeCrypto generated = new SecureBridgeCrypto(AndroidKeyStoreRsaKeyStore.loadOrCreate(MainActivity.this));
                crypto = generated;
                runOnUiThread(this::publishNativeKey);
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this,
                    "Secure key initialization failed. Live feed remains locked.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void publishNativeKey() {
        WebView current = web;
        SecureBridgeCrypto active = crypto;
        if (!pageReady || current == null || active == null || !secureBridgeReady) return;
        try {
            String key = JSONObject.quote(active.publicKeyBase64());
            current.evaluateJavascript("window.DRSetPublicKey && window.DRSetPublicKey(" + key + ")", null);
        } catch (Throwable ignored) {}
    }

    private JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("ticks", tickCount); o.put("asset", currentAsset);
            o.put("price", Double.isFinite(lastPrice) ? lastPrice : JSONObject.NULL);
            o.put("lastTickAt", lastTickAt);
            o.put("lastTickReceivedAt", lastTickReceivedAt);
            o.put("connected", lastTickReceivedAt > 0 && System.currentTimeMillis() - lastTickReceivedAt < 8000);
            o.put("qxHeartbeatSendMs", Double.isFinite(qxHeartbeatSendMs) ? qxHeartbeatSendMs : JSONObject.NULL);
            o.put("qxHeartbeatAt", qxHeartbeatWallAt);
            o.put("qxHttpResponseMs", Double.isFinite(qxHttpResponseMs) ? qxHttpResponseMs : JSONObject.NULL);
            o.put("qxHttpResponseAt", qxHttpWallAt);
            JSONObject feed = feedMonitor.snapshot(currentAsset, SystemClock.elapsedRealtime());
            java.util.Iterator<String> feedKeys = feed.keys();
            while (feedKeys.hasNext()) { String key = feedKeys.next(); o.put(key, feed.opt(key)); }
            o.put("secureBridge", cryptoHandshakeVerified);
            o.put("secureBridgeAvailable", secureBridgeReady && crypto != null);
            o.put("fingerprint", currentFingerprint);
            o.put("frameBytes", currentFrameBytes);
            o.put("signal", signalEngine.getSignal());
            o.put("qxDirectionalUpTicks", signalEngine.getDirectionalUpTicks());
            o.put("qxDirectionalDownTicks", signalEngine.getDirectionalDownTicks());
            o.put("qxDirectionalNeutralTicks", signalEngine.getDirectionalNeutralTicks());
            o.put("qxDirectionalTicks", signalEngine.getBrokerDirectionTickCount());
            o.put("signalCount", signalEngine.getSignalCount());
            o.put("signalProbability", signalEngine.getSignalProbability());
            o.put("histogram", signalEngine.getHistogram());
            o.put("vector", signalEngine.getVector());
            o.put("displacement", signalEngine.getDisplacement());
            o.put("histogramBars", signalEngine.getHistogramBars());
            o.put("signalReason", signalEngine.getReason());
            o.put("wins", signalEngine.getWins());
            o.put("losses", signalEngine.getLosses());
            o.put("ties", signalEngine.getTies());
            o.put("candleCount", signalEngine.getCandleCount());
            o.put("assetCandleCount", signalEngine.getAssetCandleCount());
            o.put("open", Double.isFinite(signalEngine.getOpen()) ? signalEngine.getOpen() : JSONObject.NULL);
            o.put("high", Double.isFinite(signalEngine.getHigh()) ? signalEngine.getHigh() : JSONObject.NULL);
            o.put("low", Double.isFinite(signalEngine.getLow()) ? signalEngine.getLow() : JSONObject.NULL);
            o.put("close", Double.isFinite(signalEngine.getClose()) ? signalEngine.getClose() : JSONObject.NULL);
        } catch (Exception ignored) {}
        return o;
    }

    private void showFatal(String heading, Throwable error) {
        String detail = error == null ? "Unknown startup failure" :
            error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        TextView v = new TextView(this);
        v.setText("DR CANDLE TRACKER\\n\\n" + heading + "\\n\\n" + detail +
            "\\n\\nThe app protected the startup screen instead of closing.\\nTap to restart.");
        v.setTextColor(Color.rgb(160, 255, 190));
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(28, 28, 28, 28);
        v.setBackgroundColor(Color.rgb(3, 12, 8));
        v.setOnClickListener(x -> recreate());
        setContentView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.DRBack && window.DRBack()", null);
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        cryptoExecutor.shutdownNow();
        super.onDestroy();
    }
}