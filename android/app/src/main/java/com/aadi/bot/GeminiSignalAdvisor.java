package com.aadi.bot;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Free-tier Gemini second-opinion gate. Only real QX-derived data is submitted. */
public final class GeminiSignalAdvisor {
    private static final String MODEL = "gemini-3.8-flash";
    private static final String PREFS = "aadi_gemini";
    private static final String KEY_PREF = "encrypted_api_key";
    private static final String KEY_ALIAS = "aadi_gemini_api_key_v1";

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

    private GeminiSignalAdvisor() {}

    public static void saveApiKey(Context context, String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().remove(KEY_PREF).apply();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(apiKey.trim().getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        String stored = Base64.encodeToString(combined, Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREF, stored).apply();
    }

    public static String loadApiKey(Context context) {
        try {
            String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PREF, "");
            if (stored == null || stored.isEmpty()) return "";
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            if (combined.length <= 12) return "";
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    public static Decision review(String apiKey, String asset, String side, double price,
                                  String baseAnalysis, String dailySummary,
                                  double move3s, double move1s) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty())
            return new Decision(false, 0, "Gemini API key is not configured");
        JSONObject promptData = new JSONObject();
        promptData.put("asset", asset);
        promptData.put("candidate", side);
        promptData.put("entryPrice", price);
        promptData.put("realQxTickNetMove3s", move3s);
        promptData.put("realQxTickNetMove1s", move1s);
        promptData.put("technicalAnalysis", baseAnalysis == null ? "" : baseAnalysis);
        promptData.put("dailyContext", dailySummary == null ? "" : dailySummary);

        String instructions = "You are a conservative second-opinion filter for a 10-second binary-options signal on a platform-specific OTC feed. Analyze ONLY the supplied real QX-derived snapshot. Do not invent candles, indicators, news, prices, or external market facts. The local engine has already proposed " + side + ". Return APPROVE only if trend, momentum, very recent tick direction, and nearby support/resistance are mutually consistent for that exact direction and there is no material reversal, exhaustion, weak-trend, or late-entry warning. If data is incomplete, indicators conflict, support/resistance is too close, or evidence is ambiguous, return WAIT. Do not reverse CALL into PUT or PUT into CALL. Confidence is a subjective quality score, not a probability. Require confidence >= 80 for APPROVE. Respond ONLY as JSON: {\"decision\":\"APPROVE\" or \"WAIT\",\"confidence\":0-100,\"reason\":\"brief reason\"}. Snapshot: " + promptData.toString();

        JSONObject body = new JSONObject();
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", instructions));
        JSONArray contents = new JSONArray();
        contents.put(new JSONObject().put("role", "user").put("parts", parts));
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject()
                .put("temperature", 0.1)
                .put("maxOutputTokens", 160)
                .put("responseMimeType", "application/json"));

        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", apiKey.trim());
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(stream);
            if (status < 200 || status >= 300)
                throw new Exception("Gemini API HTTP " + status + ": " + response);
            JSONObject root = new JSONObject(response);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0)
                throw new Exception("Gemini returned no candidate");
            JSONObject candidate = candidates.getJSONObject(0);
            JSONObject content = candidate.optJSONObject("content");
            JSONArray responseParts = content == null ? null : content.optJSONArray("parts");
            if (responseParts == null || responseParts.length() == 0)
                throw new Exception("Gemini returned an empty response");
            String text = responseParts.getJSONObject(0).optString("text", "").trim();
            int first = text.indexOf('{'), last = text.lastIndexOf('}');
            if (first < 0 || last <= first) throw new Exception("Gemini response was not JSON");
            JSONObject decision = new JSONObject(text.substring(first, last + 1));
            String action = decision.optString("decision", "WAIT").toUpperCase(java.util.Locale.US);
            int confidence = decision.optInt("confidence", 0);
            String reason = decision.optString("reason", "No reason supplied");
            boolean approved = "APPROVE".equals(action) && confidence >= 80;
            return new Decision(approved, confidence, reason);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Gemini is the sole signal decision-maker. Input is raw QX candles and ticks, not a local candidate. */
    public static Decision reviewMarket(String apiKey, JSONObject marketSnapshot) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty())
            throw new Exception("Gemini API key is not configured");
        String instructions = "You are the ONLY decision engine for a 10-second binary-options signal on a platform-specific OTC chart. You receive a snapshot containing ONLY genuine live QX ticks and genuine QX historical 10-second OHLC candles. Independently analyze the market yourself: trend and structure, momentum and acceleration, candle bodies and wicks, support/resistance, breakouts and failed breakouts, reversals, volatility, choppiness, and whether an immediate 10-second entry is timely. Do not assume any local indicator engine has already selected a direction. Do not invent missing data, prices, candles, news, or external facts. Choose exactly one action: CALL, PUT, or WAIT. CALL means the next 10 seconds are more likely to close above the current quote; PUT means more likely below. If the data is insufficient, stale, conflicting, choppy, or the setup is late/unclear, choose WAIT. Be selective, but do not apply a separate numeric confidence threshold: your own market judgment is the decision. Return JSON only: {\"decision\":\"CALL\" or \"PUT\" or \"WAIT\",\"confidence\":0-100,\"reason\":\"brief evidence-based reason\"}. Market snapshot: " + marketSnapshot.toString();
        JSONObject body = new JSONObject();
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", instructions));
        JSONArray contents = new JSONArray();
        contents.put(new JSONObject().put("role", "user").put("parts", parts));
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject()
                .put("temperature", 0.15)
                .put("maxOutputTokens", 180)
                .put("responseMimeType", "application/json"));
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(6500);
            connection.setReadTimeout(6500);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("x-goog-api-key", apiKey.trim());
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(stream);
            if (status < 200 || status >= 300)
                throw new Exception("Gemini API HTTP " + status + ": " + response);
            JSONObject root = new JSONObject(response);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0)
                throw new Exception("Gemini returned no candidate");
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray responseParts = content == null ? null : content.optJSONArray("parts");
            if (responseParts == null || responseParts.length() == 0)
                throw new Exception("Gemini returned an empty response");
            String raw = responseParts.getJSONObject(0).optString("text", "").trim();
            int first = raw.indexOf('{'), last = raw.lastIndexOf('}');
            if (first < 0 || last <= first) throw new Exception("Gemini response was not JSON");
            JSONObject parsed = new JSONObject(raw.substring(first, last + 1));
            String action = parsed.optString("decision", "WAIT").toUpperCase(java.util.Locale.US);
            if (!"CALL".equals(action) && !"PUT".equals(action)) action = "WAIT";
            return new Decision(!"WAIT".equals(action), parsed.optInt("confidence", 0),
                    action + " • " + parsed.optString("reason", "No reason supplied"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
