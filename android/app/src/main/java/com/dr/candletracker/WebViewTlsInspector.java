package com.dr.candletracker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;

/**
 * Reads the negotiated TLS security state from this WebView's own Chromium
 * DevTools Protocol session. It never performs a second HTTPS probe.
 * Enabled only in debuggable builds because WebView remote debugging is sensitive.
 */
final class WebViewTlsInspector {
    interface Listener {
        void onTlsState(String cipher, String protocol, String keyExchange,
                        String subject, String issuer);
        void onUnavailable(String reason);
    }

    private volatile boolean stopped;
    private LocalSocket activeSocket;
    private Thread worker;

    static boolean enableIfDebuggable(Context context) {
        boolean debug = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debug);
        return debug;
    }

    synchronized void start(Listener listener) {
        if (worker != null) return;
        worker = new Thread(() -> inspect(listener), "DR-WebView-TLS-CDP");
        worker.setDaemon(true);
        worker.start();
    }

    private void inspect(Listener listener) {
        try {
            String socketName = findDevToolsSocket();
            if (socketName == null) {
                listener.onUnavailable("WEBVIEW CDP SOCKET NOT FOUND");
                return;
            }
            String body = httpGet(socketName, "/json/list");
            JSONArray targets = new JSONArray(body);
            String wsPath = null;
            for (int i = 0; i < targets.length(); i++) {
                JSONObject target = targets.optJSONObject(i);
                if (target == null) continue;
                String type = target.optString("type", "");
                String url = target.optString("webSocketDebuggerUrl", "");
                if ("page".equals(type) && url.startsWith("ws://")) {
                    wsPath = new URI(url).getRawPath();
                    if (wsPath != null) break;
                }
            }
            if (wsPath == null) throw new IllegalStateException("NO WEBVIEW PAGE TARGET");
            LocalSocket socket = connect(socketName);
            activeSocket = socket;
            socket.setSoTimeout(15000);
            websocketHandshake(socket, wsPath);
            sendCommand(socket, 1, "Security.enable");
            sendCommand(socket, 2, "Security.getVisibleSecurityState");
            long deadline = System.currentTimeMillis() + 20000L;
            while (!stopped && System.currentTimeMillis() < deadline) {
                String message = readWebSocketText(socket);
                if (message == null) continue;
                JSONObject event = new JSONObject(message);
                JSONObject state = null;
                if ("Security.visibleSecurityStateChanged".equals(event.optString("method"))) {
                    JSONObject params = event.optJSONObject("params");
                    if (params != null) state = params.optJSONObject("visibleSecurityState");
                }
                JSONObject result = event.optJSONObject("result");
                if (state == null && result != null)
                    state = result.optJSONObject("visibleSecurityState");
                if (state == null) continue;
                JSONObject cert = state.optJSONObject("certificateSecurityState");
                if (cert == null) continue;
                String cipher = cert.optString("cipher", "");
                String protocol = cert.optString("protocol", "");
                String exchange = cert.optString("keyExchange", "");
                String subject = cert.optString("subjectName", "");
                String issuer = cert.optString("issuer", "");
                if (!cipher.isEmpty() || !protocol.isEmpty()) {
                    listener.onTlsState(cipher, protocol, exchange, subject, issuer);
                    return;
                }
            }
            listener.onUnavailable("WEBVIEW TLS STATE NOT REPORTED BY CHROMIUM CDP");
        } catch (Throwable error) {
            if (!stopped) listener.onUnavailable("WEBVIEW CDP TLS READ FAILED: " +
                error.getClass().getSimpleName() + " " + String.valueOf(error.getMessage()));
        } finally {
            closeSocket();
        }
    }

    private String findDevToolsSocket() throws Exception {
        String wanted = "webview_devtools_remote_" + android.os.Process.myPid();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream("/proc/net/unix"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null)
                if (line.contains(wanted)) return wanted;
        }
        return null;
    }

    private LocalSocket connect(String name) throws Exception {
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
        socket.setSoTimeout(5000);
        return socket;
    }

    private String httpGet(String name, String path) throws Exception {
        try (LocalSocket socket = connect(name)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            String response = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            int split = response.indexOf("\r\n\r\n");
            if (split < 0 || !response.startsWith("HTTP/1.1 200"))
                throw new IllegalStateException("CDP TARGET LIST HTTP ERROR");
            return response.substring(split + 4).trim();
        }
    }

    private void websocketHandshake(LocalSocket socket, String path) throws Exception {
        byte[] nonce = new byte[16];
        new Random().nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        OutputStream out = socket.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Key: " + key +
            "\r\nSec-WebSocket-Version: 13\r\nOrigin: http://localhost\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0, b;
        while ((b = in.read()) != -1 && header.size() < 8192) {
            header.write(b);
            if ((matched == 0 || matched == 2) && b == '\r') matched++;
            else if ((matched == 1 || matched == 3) && b == '\n') matched++;
            else matched = b == '\r' ? 1 : 0;
            if (matched == 4) break;
        }
        String response = new String(header.toByteArray(), StandardCharsets.US_ASCII);
        if (!response.startsWith("HTTP/1.1 101"))
            throw new IllegalStateException("CDP WEBSOCKET UPGRADE REJECTED");
    }

    private void sendCommand(LocalSocket socket, int id, String method) throws Exception {
        byte[] payload = new JSONObject().put("id", id).put("method", method)
            .toString().getBytes(StandardCharsets.UTF_8);
        int mask = new Random().nextInt();
        OutputStream out = socket.getOutputStream();
        out.write(0x81);
        if (payload.length < 126) out.write(0x80 | payload.length);
        else { out.write(0x80 | 126); out.write((payload.length >>> 8) & 255); out.write(payload.length & 255); }
        byte[] key = new byte[] {(byte)(mask >>> 24),(byte)(mask >>> 16),(byte)(mask >>> 8),(byte)mask};
        out.write(key);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ key[i & 3]);
        out.flush();
    }

    private String readWebSocketText(LocalSocket socket) throws Exception {
        InputStream in = socket.getInputStream();
        int first = in.read(), second = in.read();
        if (first < 0 || second < 0) return null;
        int opcode = first & 15;
        long length = second & 127;
        if (length == 126) length = ((long)in.read() << 8) | in.read();
        else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | (in.read() & 255);
        }
        if (length < 0 || length > 1048576) throw new IllegalStateException("CDP FRAME SIZE LIMIT");
        boolean masked = (second & 128) != 0;
        byte[] mask = masked ? readFully(in, 4) : null;
        byte[] payload = readFully(in, (int)length);
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        if (opcode == 8) throw new IllegalStateException("CDP SOCKET CLOSED");
        if (opcode == 9) return null;
        if (opcode != 1) return null;
        return new String(payload, StandardCharsets.UTF_8);
    }

    private byte[] readFully(InputStream in, int size) throws Exception {
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int n = in.read(data, offset, size - offset);
            if (n < 0) throw new IllegalStateException("CDP FRAME TRUNCATED");
            offset += n;
        }
        return data;
    }

    synchronized void stop() {
        stopped = true;
        closeSocket();
        if (worker != null) worker.interrupt();
    }

    private synchronized void closeSocket() {
        try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) {}
        activeSocket = null;
    }
}
