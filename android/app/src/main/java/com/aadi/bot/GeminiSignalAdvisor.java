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

/** Groq-hosted Qwen Vision chart analyzer. Invoked only by the user's Analyze button. */
public final class GeminiSignalAdvisor {
    private static final String MODEL = "qwen/qwen3.8-27b";
    private static final String PREFS = "aadi_groq";
    private static final String KEY_PREF = "encrypted_api_key";
    private static final String KEY_ALIAS = "aadi_groq_api_key_v1";

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
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PREF).apply();
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREF, Base64.encodeToString(combined, Base64.NO_WRAP)).apply();
    }

    public static String loadApiKey(Context context) {
        try {
            String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PREF, "");
            if (stored == null || stored.isEmpty()) return "";
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            if (combined.length <= 12) return "";
            byte[] iv = new byte[12], encrypted = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    /** One user-triggered request containing the live QX screenshot and genuine tick/candle snapshot. */
    public static Decision reviewMarket(String apiKey, JSONObject marketSnapshot, String chartDataUrl) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("Groq API key is not configured");
        if (chartDataUrl == null || !chartDataUrl.startsWith("data:image/")) throw new Exception("QX chart screenshot is missing");
        String instructions = "You are the sole market analyst and sole signal decision-maker. Scan the ENTIRE visible Market-QX chart screenshot, not just the latest candle: inspect every candle that is visible, price structure, swing highs/lows, candle sequences, momentum shifts, breakouts, rejections, consolidation, volatility and nearby support/resistance directly from the chart. Cross-check the visual reading against the supplied genuine live QX ticks and 10-second OHLC candles. The OHLC candles were built only from live WebSocket ticks; no historical, demo, synthetic or fabricated candles are included. Do NOT calculate or rely on EMA, RSI, MACD, ADX, moving-average systems, indicator scores, or any external/local signal engine. Do not accept a precomputed CALL/PUT candidate. Make your own independent decision from the chart and live price action. Decide only for the next 10-second expiry: CALL if the full chart and live data favor a higher close than the current quote, PUT if they favor a lower close, otherwise WAIT. Return WAIT only when the evidence is genuinely unclear, the screenshot is unreadable, or live data conflicts/is stale. Return JSON only with keys decision (CALL, PUT, or WAIT), confidence (integer 0-100), reason (short concrete chart-based explanation), expirySeconds (10). Do not promise accuracy. Live QX data: " + marketSnapshot.toString();
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type","text").put("text",instructions));
        content.put(new JSONObject().put("type","image_url")
                .put("image_url",new JSONObject().put("url",chartDataUrl)));
        JSONObject message = new JSONObject().put("role","user").put("content",content);
        JSONArray messages = new JSONArray().put(message);
        JSONObject body = new JSONObject().put("model",MODEL).put("messages",messages)
                .put("temperature",0.2).put("max_completion_tokens",512)
                .put("response_format",new JSONObject().put("type","json_object"));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL("https://api.groq.com/openai/v1/chat/completions").openConnection();
            connection.setRequestMethod("POST");connection.setConnectTimeout(15000);connection.setReadTimeout(45000);
            connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            connection.setRequestProperty("Authorization","Bearer "+apiKey.trim());
            byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try(OutputStream out=connection.getOutputStream()){out.write(payload);}
            int status=connection.getResponseCode();
            InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            String response=readAll(stream);
            if(status<200||status>=300)throw new Exception("Groq API HTTP "+status+": "+response);
            JSONObject root=new JSONObject(response);
            JSONArray choices=root.optJSONArray("choices");
            if(choices==null||choices.length()==0)throw new Exception("Groq returned no choices");
            JSONObject msg=choices.getJSONObject(0).optJSONObject("message");
            String raw=msg==null?"":msg.optString("content","").trim();
            int first=raw.indexOf('{'),last=raw.lastIndexOf('}');
            if(first<0||last<=first)throw new Exception("Qwen response was not valid JSON: "+raw);
            JSONObject parsed=new JSONObject(raw.substring(first,last+1));
            String action=parsed.optString("decision","WAIT").toUpperCase(java.util.Locale.US);
            if(!"CALL".equals(action)&&!"PUT".equals(action))action="WAIT";
            return new Decision(!"WAIT".equals(action),parsed.optInt("confidence",0),
                    action+" • "+parsed.optString("reason","No reason supplied"));
        } finally { if(connection!=null)connection.disconnect(); }
    }

    private static String readAll(InputStream stream) throws Exception {
        if(stream==null)return "";
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
