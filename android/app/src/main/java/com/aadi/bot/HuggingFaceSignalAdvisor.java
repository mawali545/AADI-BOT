package com.aadi.bot;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public Hugging Face Gradio Space client.
 * Uploads the captured QX screenshot, then submits the live snapshot only
 * when the user presses AI GENERATE SIGNAL.
 */
public final class HuggingFaceSignalAdvisor {
    private static final String PREFS = "aadi_huggingface";
    private static final String SPACE_URL_KEY = "space_url";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 210000;

    public static final class Decision {
        public final boolean approved;
        public final int confidence;
        public final String reason;
        Decision(boolean ok, int score, String why) {
            approved = ok;
            confidence = score;
            reason = why == null ? "No explanation returned" : why;
        }
    }

    private HuggingFaceSignalAdvisor() {}

    public static String loadSpaceUrl(android.content.Context context) {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(SPACE_URL_KEY, "");
    }

    public static void saveSpaceUrl(android.content.Context context, String value) {
        String url = value == null ? "" : value.trim();
        if (!url.isEmpty()) {
            if (!url.startsWith("https://")) throw new IllegalArgumentException("Use the public https://*.hf.space app URL");
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (!url.toLowerCase(Locale.US).contains(".hf.space"))
                throw new IllegalArgumentException("Paste the Space app URL ending in .hf.space, not the huggingface.co source page");
        }
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(SPACE_URL_KEY, url).apply();
    }

    public static Decision reviewMarket(String spaceUrl, JSONObject snapshot, String imageDataUrl) throws Exception {
        if (spaceUrl == null || spaceUrl.trim().isEmpty()) throw new Exception("Hugging Face Space URL is not configured");
        String base = spaceUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("https://") || !base.contains(".hf.space"))
            throw new Exception("Invalid Space URL. Use the public https://<space-name>.hf.space address.");
        if (imageDataUrl == null || !imageDataUrl.startsWith("data:image/"))
            throw new Exception("QX chart screenshot is missing");

        String encoded = imageDataUrl.substring(imageDataUrl.indexOf(',') + 1);
        byte[] imageBytes = Base64.decode(encoded, Base64.DEFAULT);
        String uploadedPath = uploadImage(base, imageBytes);
        JSONObject fileData = new JSONObject()
                .put("path", uploadedPath)
                .put("orig_name", "aadi-qx-live-chart.jpg")
                .put("size", imageBytes.length)
                .put("mime_type", "image/jpeg")
                .put("meta", new JSONObject().put("_type", "gradio.FileData"));

        JSONArray data = new JSONArray();
        data.put(fileData);
        data.put(snapshot.optString("asset", "Unknown asset"));
        data.put("10-second");
        data.put(snapshot.toString());
        data.put("Captured directly from the live Market-QX WebView at analysis time. Use only the supplied live ticks and 10-second OHLC candles. Do not invent missing values.");
        JSONObject request = new JSONObject().put("data", data);
        JSONObject accepted = postJson(base + "/gradio_api/call/analyze_chart", request);
        String eventId = accepted.optString("event_id", "");
        if (eventId.isEmpty()) throw new Exception("Hugging Face Space did not return an analysis job ID: " + accepted);
        String raw = awaitResult(base + "/gradio_api/call/analyze_chart/" + eventId);
        return parseDecision(raw);
    }

    private static String uploadImage(String base, byte[] bytes) throws Exception {
        String boundary = "----AADI" + Long.toHexString(System.nanoTime());
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/gradio_api/upload").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = c.getOutputStream()) {
                out.write(("--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"files\"; filename=\"aadi-qx-live-chart.jpg\"\r\n" +
                        "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            String body = readBody(status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream());
            if (status < 200 || status >= 300) throw new Exception("Hugging Face image upload HTTP " + status + ": " + body);
            JSONArray paths = new JSONArray(body);
            if (paths.length() == 0) throw new Exception("Hugging Face did not return an uploaded image path");
            return paths.optString(0, "");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static JSONObject postJson(String url, JSONObject body) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            int status = c.getResponseCode();
            String response = readBody(status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream());
            if (status < 200 || status >= 300) throw new Exception("Hugging Face request HTTP " + status + ": " + response);
            return new JSONObject(response);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String awaitResult(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Accept", "text/event-stream");
            int status = c.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            if (status < 200 || status >= 300)
                throw new Exception("Hugging Face analysis HTTP " + status + ": " + readBody(stream));
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line, event = "", lastData = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) event = line.substring(6).trim();
                    else if (line.startsWith("data:")) lastData = line.substring(5).trim();
                    else if (line.isEmpty()) {
                        if ("complete".equals(event) && !lastData.isEmpty()) return extractOutput(lastData);
                        if ("error".equals(event)) throw new Exception("Hugging Face AI error: " + lastData);
                        event = "";
                        lastData = "";
                    }
                }
                if (!lastData.isEmpty()) return extractOutput(lastData);
                throw new Exception("Hugging Face Space closed the stream without an AI result");
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String extractOutput(String data) throws Exception {
        Object parsed = new org.json.JSONTokener(data).nextValue();
        if (parsed instanceof JSONArray) {
            JSONArray a = (JSONArray) parsed;
            return a.length() == 0 ? "" : String.valueOf(a.opt(0));
        }
        if (parsed instanceof JSONObject) {
            JSONObject o = (JSONObject) parsed;
            if (o.has("error")) throw new Exception(o.optString("error"));
            return o.optString("text", o.toString());
        }
        return String.valueOf(parsed);
    }

    private static Decision parseDecision(String raw) {
        String text = raw == null ? "" : raw.trim();
        Matcher decisionMatcher = Pattern.compile("(?im)^\\s*(?:\\d+[.)]\\s*)?(?:\\*\\*)?DECISION(?:\\*\\*)?\\s*[:：-]\\s*(CALL|PUT|WAIT)\\b").matcher(text);
        String side = "";
        if (decisionMatcher.find()) side = decisionMatcher.group(1).toUpperCase(Locale.US);
        if (side.isEmpty()) {
            Matcher firstLine = Pattern.compile("(?i)^\\s*(CALL|PUT|WAIT)\\b").matcher(text);
            if (firstLine.find()) side = firstLine.group(1).toUpperCase(Locale.US);
        }
        if (!"CALL".equals(side) && !"PUT".equals(side)) side = "WAIT";
        Matcher confidenceMatcher = Pattern.compile("(?i)CONFIDENCE\\s*[:：-]?\\s*(\\d{1,3})\\s*%?").matcher(text);
        int confidence = confidenceMatcher.find() ? Math.max(0, Math.min(100, Integer.parseInt(confidenceMatcher.group(1)))) : 0;
        String compact = text.length() > 900 ? text.substring(0, 900) + "…" : text;
        return new Decision(!"WAIT".equals(side), confidence, side + " • " + compact);
    }

    private static String readBody(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
