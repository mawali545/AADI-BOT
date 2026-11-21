package com.dr.candletracker;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
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
import org.json.JSONArray;

public final class MainActivity extends Activity {
    private WebView web;
    private String trackerScript = "";
    private volatile SecureBridgeCrypto crypto;
    private volatile boolean secureBridgeReady = false;
    private volatile boolean pageReady = false;
    private volatile boolean documentStartScriptInstalled = false;
    private long tickCount = 0, lastTickAt = 0;
    private String currentAsset = "WAITING FOR MARKET";
    private String currentFingerprint = "";
    private int currentFrameBytes = 0;
    private double lastPrice = Double.NaN;
    private final SignalEngine signalEngine = new SignalEngine();
    private final MarketDiagnostics diagnostics = new MarketDiagnostics();
    private WebViewTlsInspector tlsInspector;
    private volatile JSONObject webViewTls = new JSONObject();
    private boolean tlsCdpEnabled;
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
            startWebViewTlsInspection();
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
        tlsCdpEnabled = WebViewTlsInspector.enableIfDebuggable(this);
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
                                String source = tick.optString("source", "");
                                String asset = tick.optString("asset", "");
                                String fingerprint = tick.optString("frameFingerprint", "");
                                int frameBytes = tick.optInt("frameBytes", 0);
                                long period = tick.optLong("period", 60);
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
                                        signalEngine.acceptHistory(asset, period, candles);
                                        diagnostics.onHistory(asset, candles.length());
                                        if (web != null) web.evaluateJavascript(
                                            "window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                                    });
                                    return;
                                }
                                double price = tick.optDouble("price", Double.NaN);
                                long stamp = tick.optLong("timestamp", 0);
                                if (!"tick".equals(type) || !("quotes/stream".equals(source) || "candle-generated".equals(source)) ||
                                    !Double.isFinite(price) || price <= 0 || price >= 1e12 || stamp <= 0) return;
                                final long tickTime = stamp;
                                runOnUiThread(() -> {
                                    tickCount++; lastPrice = price; currentAsset = asset; lastTickAt = tickTime;
                                    currentFingerprint = frameHash; currentFrameBytes = observedFrameBytes;
                                    signalEngine.acceptTick(asset, price, tickTime, period);
                                    diagnostics.onTick(asset, price, tickTime, System.currentTimeMillis());
                                    diagnostics.onSignal(signalEngine.getSignal());
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
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri u = request.getUrl();
                    String host = u == null ? "" : String.valueOf(u.getHost());
                    // Observe request metadata only. Never capture credentials, cookies, bodies or query values.
                    if (u != null && ("market-qx.trade".equals(host) || host.endsWith(".market-qx.trade"))) {
                        diagnostics.onNetworkEvent("HTTP REQUEST " + request.getMethod() + " " +
                            u.getScheme() + "://" + host + u.getPath());
                    }
                } catch (Throwable ignored) {}
                return null;
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                try {
                    Uri u = request.getUrl();
                    String host = u == null ? "" : String.valueOf(u.getHost());
                    if (u != null && ("market-qx.trade".equals(host) || host.endsWith(".market-qx.trade")))
                        diagnostics.onNetworkEvent("HTTP RESPONSE " + response.getStatusCode() + " " +
                            request.getMethod() + " " + u.getScheme() + "://" + host + u.getPath());
                } catch (Throwable ignored) {}
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageReady = false;
                diagnostics.onConnection("PAGE START");
            }
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
                try {
                    android.net.http.SslCertificate cert = error.getCertificate();
                    diagnostics.onNetworkEvent("WEBVIEW TLS VALIDATION ERROR " + error.getPrimaryError() +
                        " · host " + String.valueOf(error.getUrl()) +
                        (cert == null ? "" : " · certificate " + cert.getIssuedTo().getCName()));
                } catch (Throwable ignored) {}
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

    private void startWebViewTlsInspection() {
        if (!tlsCdpEnabled || tlsInspector != null) {
            if (!tlsCdpEnabled) diagnostics.onNetworkEvent("WEBVIEW TLS CDP DISABLED: non-debuggable build");
            return;
        }
        tlsInspector = new WebViewTlsInspector();
        tlsInspector.start(new WebViewTlsInspector.Listener() {
            @Override public void onTlsState(String cipher, String protocol, String keyExchange,
                                             String subject, String issuer) {
                runOnUiThread(() -> {
                    try {
                        JSONObject state = new JSONObject();
                        state.put("source", "THIS_WEBVIEW_CHROMIUM_CDP");
                        state.put("cipher", cipher);
                        state.put("protocol", protocol);
                        state.put("keyExchange", keyExchange);
                        state.put("subject", subject);
                        state.put("issuer", issuer);
                        state.put("capturedAt", System.currentTimeMillis());
                        webViewTls = state;
                        diagnostics.onNetworkEvent("ACTUAL WEBVIEW TLS " + protocol + " · " +
                            cipher + " · key exchange " + keyExchange);
                        if (web != null) web.evaluateJavascript(
                            "window.DRUpdate && window.DRUpdate(" + snapshot().toString() + ")", null);
                    } catch (Exception ignored) {}
                });
            }
            @Override public void onUnavailable(String reason) {
                diagnostics.onNetworkEvent(reason);
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
            o.put("connected", lastTickAt > 0 && System.currentTimeMillis() - lastTickAt < 8000);
            o.put("secureBridge", secureBridgeReady && crypto != null);
            o.put("fingerprint", currentFingerprint);
            o.put("frameBytes", currentFrameBytes);
            o.put("signal", signalEngine.getSignal());
            o.put("signalReason", signalEngine.getReason());
            o.put("wins", signalEngine.getWins());
            o.put("losses", signalEngine.getLosses());
            o.put("ties", signalEngine.getTies());
            o.put("candleCount", signalEngine.getCandleCount());
            o.put("open", Double.isFinite(signalEngine.getOpen()) ? signalEngine.getOpen() : JSONObject.NULL);
            o.put("high", Double.isFinite(signalEngine.getHigh()) ? signalEngine.getHigh() : JSONObject.NULL);
            o.put("low", Double.isFinite(signalEngine.getLow()) ? signalEngine.getLow() : JSONObject.NULL);
            o.put("close", Double.isFinite(signalEngine.getClose()) ? signalEngine.getClose() : JSONObject.NULL);
            JSONObject diagnosticState = diagnostics.snapshot();
            long age = lastTickAt > 0 ? System.currentTimeMillis() - lastTickAt : Long.MAX_VALUE;
            diagnosticState.put("node", age >= 0 && age < 8000 ? "ARMED" : "RECONNECTING");
            diagnosticState.put("scanState", signalEngine.getSignal());
            diagnosticState.put("bridgeCryptoState", secureBridgeReady && crypto != null ? "LOCAL RSA-OAEP/AES-GCM READY" : "LOCKED");
            diagnosticState.put("webViewTls", webViewTls);
            diagnosticState.put("cipherState", webViewTls.optString("cipher", "NOT CAPTURED"));
            diagnosticState.put("tlsProtocol", webViewTls.optString("protocol", "NOT CAPTURED"));
            diagnosticState.put("tlsKeyExchange", webViewTls.optString("keyExchange", "NOT CAPTURED"));
            JSONArray checks = diagnosticState.optJSONArray("scanChecklist");
            if (checks != null && checks.length() >= 6) {
                JSONObject bridgeCheck = checks.optJSONObject(5);
                if (bridgeCheck != null) bridgeCheck.put("passed", secureBridgeReady && crypto != null);
            }
            o.put("diagnostics", diagnosticState);
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
        if (tlsInspector != null) { tlsInspector.stop(); tlsInspector = null; }
        if (web != null) { web.destroy(); web = null; }
        cryptoExecutor.shutdownNow();
        super.onDestroy();
    }
}