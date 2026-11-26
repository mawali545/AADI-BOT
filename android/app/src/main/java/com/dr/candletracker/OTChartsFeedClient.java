package com.dr.candletracker;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** OTCharts is the only market-data provider. No Quotex session or fallback feed is used. */
public final class OTChartsFeedClient {
    public interface Listener {
        void onStatus(String status);
        void onHistory(JSONArray candles, int missingBars);
        void onTick(double price, long timeSeconds);
        void onClosed(boolean closed);
    }
    private final String apiKey, symbol;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile HttpURLConnection activeConnection;
    private Thread worker;

    public OTChartsFeedClient(String apiKey, String symbol, Listener listener) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.symbol = symbol == null ? "" : symbol.trim();
        this.listener = listener;
    }
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this::runLoop, "otcharts-live-feed");
        worker.setDaemon(true); worker.start();
    }
    public void stop() {
        running.set(false);
        HttpURLConnection c = activeConnection;
        if (c != null) c.disconnect();
        if (worker != null) worker.interrupt();
    }
    private void runLoop() {
        long retryMs = 2000L;
        boolean historyLoaded = false;
        while (running.get()) {
            try {
                if (!historyLoaded) {
                    listener.onStatus("OTCharts: loading Quotex candles…");
                    loadHistory(); historyLoaded = true;
                }
                listener.onStatus("OTCharts: opening live stream…");
                streamOnce(); retryMs = 2000L;
            } catch (PlanException e) {
                listener.onStatus(e.getMessage());
                if (e.stopRetry) { running.set(false); break; }
                sleep(60000L);
            } catch (Exception e) {
                if (!running.get()) break;
                listener.onStatus("OTCharts stream disconnected; reconnecting…");
                sleep(retryMs); retryMs = Math.min(30000L, retryMs * 2L);
            }
        }
    }
    private void loadHistory() throws Exception {
        String address = "https://otcharts.com/v1/candles?venue=quotex&symbol=" +
            URLEncoder.encode(symbol, "UTF-8") + "&tf=5&limit=300";
        HttpURLConnection c = open(address);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw refusal(c, code);
            JSONObject body = new JSONObject(readAll(c.getInputStream()));
            JSONArray candles = body.optJSONArray("candles");
            if (candles == null || candles.length() == 0)
                throw new PlanException("OTCharts returned no candles for " + symbol, true);
            JSONObject gaps = body.optJSONObject("gaps");
            listener.onHistory(candles, gaps == null ? 0 : gaps.optInt("missingBars", 0));
        } finally { c.disconnect(); activeConnection = null; }
    }
    private void streamOnce() throws Exception {
        String address = "https://otcharts.com/v1/stream?venue=quotex&symbol=" +
            URLEncoder.encode(symbol, "UTF-8");
        HttpURLConnection c = open(address);
        try {
            c.setRequestProperty("Accept", "text/event-stream");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw refusal(c, code);
            listener.onStatus("OTCharts connected · waiting for live ticks");
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String event = "message", line;
            StringBuilder data = new StringBuilder();
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatch(event, data.toString()); event = "message"; data.setLength(0);
                } else if (line.startsWith("event:")) event = line.substring(6).trim();
                else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            if (running.get()) throw new java.io.IOException("OTCharts stream ended");
        } finally { c.disconnect(); activeConnection = null; }
    }
    private void dispatch(String event, String json) throws Exception {
        if (json == null || json.isEmpty()) return;
        JSONObject o = new JSONObject(json);
        if ("connected".equals(event)) { listener.onClosed(false); listener.onStatus("OTCharts live stream subscribed"); }
        else if ("closed".equals(event)) {
            listener.onClosed(true); listener.onStatus("OTCharts: instrument is CLOSED for trading");
        } else if ("tick".equals(event)) {
            double price = o.optDouble("price", Double.NaN);
            long time = o.optLong("time", 0L);
            if (symbol.equals(o.optString("symbol", "")) && Double.isFinite(price) && price > 0 && time > 0)
                listener.onTick(price, time);
        } else if ("dropped".equals(event)) {
            listener.onStatus("OTCharts dropped this instrument; reconnecting");
            throw new java.io.IOException("OTCharts dropped " + symbol);
        } else if ("replaced".equals(event)) throw new java.io.IOException("OTCharts stream replaced");
        else if ("error".equals(event)) listener.onStatus("OTCharts stream error: " + o.optString("error", "unknown"));
    }
    private HttpURLConnection open(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(45000);
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("User-Agent", "AADI-DR-Candle-Tracker/259");
        activeConnection = c; return c;
    }
    private PlanException refusal(HttpURLConnection c, int code) throws Exception {
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = stream == null ? "" : readAll(stream);
        String detail;
        try { detail = new JSONObject(body).optString("error", body); }
        catch (Exception ignored) { detail = body; }
        if (code == 401) return new PlanException("OTCharts API key rejected (401). Check your key.", true);
        if (code == 403) return new PlanException("OTCharts plan does not allow Quotex live streaming or this instrument (403). No fallback feed is used.", true);
        if (code == 429) return new PlanException("OTCharts quota/stream limit reached (429). " + detail, false);
        if (code == 404 || code == 400) return new PlanException("OTCharts symbol/venue error (" + code + "): " + detail, true);
        return new PlanException("OTCharts HTTP " + code + ": " + detail, false);
    }
    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) b.append(line);
        return b.toString();
    }
    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    private static final class PlanException extends Exception {
        final boolean stopRetry;
        PlanException(String message, boolean stop) { super(message); stopRetry = stop; }
    }
}
