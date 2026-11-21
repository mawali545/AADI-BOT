package com.dr.candletracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Video-style probability engine. It uses only broker-supplied OHLC history
 * and validated live Market-QX quote events. No synthetic candles or legacy
 * EMA/RSI/MACD indicators are used.
 */
public final class SignalEngine {
    private static final int MAX_CANDLES = 900, MAX_TICKS = 900;
    private final List<Candle> candles = new ArrayList<>();
    private final List<Tick> tape = new ArrayList<>();
    private final Map<String, Long> lastTimestampByAsset = new HashMap<>();
    private final Map<String, Long> issuedBucketByAsset = new HashMap<>();
    private final List<Pending> pendingResults = new ArrayList<>();
    private int signalCount;
    private String asset = "WAITING FOR QX", signal = "WAIT";
    private String reason = "Waiting for real Market-QX candles.";
    private int wins, losses, ties;
    private long lastTimestamp;
    private double lastPrice = Double.NaN, signalProbability;

    public synchronized void acceptTick(String symbol, double price, long timestamp) {
        acceptTick(symbol, price, timestamp, 60L);
    }

    /** Consume a genuine QX quote using the chart's currently selected period. */
    public synchronized void acceptTick(String symbol, double price, long timestamp, long periodSeconds) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") ||
            !Double.isFinite(price) || price <= 0 || timestamp <= 0) return;
        long duration = Math.max(1L, Math.min(86400L, periodSeconds)) * 1000L;
        Long previous = lastTimestampByAsset.get(symbol);
        if (previous != null && timestamp < previous) return;

        // Each result is settled once, on the first actual QX quote at/after expiry.
        settlePending(symbol, timestamp, price);
        lastTimestampByAsset.put(symbol, timestamp);
        long bucket = timestamp / duration * duration;
        Candle current = lastCandle(symbol);
        if (current != null && bucket < current.bucket) return;
        if (current == null || bucket > current.bucket) {
            current = new Candle(symbol, bucket, price);
            candles.add(current);
            if (candles.size() > MAX_CANDLES) candles.remove(0);
            if (!symbol.equals(asset)) {
                asset = symbol; signal = "WAIT"; signalCount = 0; signalProbability = 0;
                reason = "Scanning real QX candle flow for " + symbol;
            }
        }
        current.update(price);
        lastTimestamp = timestamp;
        lastPrice = price;
        tape.add(new Tick(symbol, price, timestamp));
        if (tape.size() > MAX_TICKS) tape.remove(0);

        Long issued = issuedBucketByAsset.get(symbol);
        if (issued == null || issued.longValue() != bucket) {
            Scan scan = scan(symbol, bucket, price);
            signal = scan.signal;
            signalProbability = scan.probability;
            reason = scan.reason;
            if (!"WAIT".equals(scan.signal)) {
                signalCount++;
                pendingResults.add(new Pending(symbol, scan.signal, price, timestamp + 10_000L));
                issuedBucketByAsset.put(symbol, bucket);
            } else {
                signalCount = 0;
            }
        }
    }

    /**
     * Broker history arrives from the JS bridge as objects {time,open,close,high,low}.
     * Array rows [time,open,close,high,low] are also accepted for protocol variants.
     */
    public synchronized void acceptHistory(String symbol, long periodSeconds, JSONArray rows) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") || rows == null) return;
        long duration = Math.max(1L, Math.min(86400L, periodSeconds)) * 1000L;
        Map<Long, Candle> unique = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                double rawTime, open, close, high, low;
                JSONObject object = rows.optJSONObject(i);
                if (object != null) {
                    rawTime = number(object, "time", "timestamp", "t");
                    open = number(object, "open", "o");
                    close = number(object, "close", "c");
                    high = number(object, "high", "h");
                    low = number(object, "low", "l");
                } else {
                    JSONArray row = rows.optJSONArray(i);
                    if (row == null || row.length() < 5) continue;
                    rawTime = row.getDouble(0);
                    open = row.getDouble(1); close = row.getDouble(2);
                    high = row.getDouble(3); low = row.getDouble(4);
                }
                long time = (long) rawTime;
                if (time > 0 && time < 100000000000L) time *= 1000L;
                if (time <= 0 || !Double.isFinite(open) || !Double.isFinite(close) ||
                    !Double.isFinite(high) || !Double.isFinite(low) || open <= 0 || close <= 0 ||
                    high < Math.max(open, close) || low > Math.min(open, close) || low <= 0) continue;
                long bucket = time / duration * duration;
                unique.put(bucket, new Candle(symbol, bucket, open, high, low, close));
            } catch (JSONException ignored) {}
        }
        if (unique.isEmpty()) return;
        List<Candle> imported = new ArrayList<>(unique.values());
        imported.sort((a, b) -> Long.compare(a.bucket, b.bucket));
        for (int i = candles.size() - 1; i >= 0; i--)
            if (candles.get(i).asset.equals(symbol)) candles.remove(i);
        candles.addAll(imported);
        while (candles.size() > MAX_CANDLES) candles.remove(0);
        asset = symbol;
        issuedBucketByAsset.remove(symbol);
        signal = "WAIT";
        signalCount = 0;
        signalProbability = 0;
        reason = "Loaded " + imported.size() + " genuine QX OHLC candles; scanning live flow.";
    }

    private static double number(JSONObject o, String... keys) {
        for (String key : keys) {
            Object v = o.opt(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) {
                try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
            }
        }
        return Double.NaN;
    }

    private Candle lastCandle(String symbol) {
        for (int i = candles.size() - 1; i >= 0; i--)
            if (candles.get(i).asset.equals(symbol)) return candles.get(i);
        return null;
    }

    private void settlePending(String symbol, long timestamp, double expiryPrice) {
        for (int i = pendingResults.size() - 1; i >= 0; i--) {
            Pending p = pendingResults.get(i);
            if (!p.asset.equals(symbol) || timestamp < p.expiresAt) continue;
            double delta = expiryPrice - p.entry;
            if (Math.abs(delta) < Math.max(1e-12, Math.abs(p.entry) * 1e-12)) ties++;
            else if (("CALL".equals(p.signal) && delta > 0) || ("PUT".equals(p.signal) && delta < 0)) wins++;
            else losses++;
            pendingResults.remove(i);
        }
    }

    /**
     * Weighted multi-factor directional forecast:
     * candle pressure, structure, momentum, rejection wicks, liquidity sweeps,
     * pullback recovery, support/resistance reaction, live tick flow and chop/
     * volatility regime. This is an estimate, not a claimed hidden broker formula.
     */
    /**
     * Video-inferred NEXT QUANT SIGNAL procedure.
     *
     * The visible panel exposes an OHLC memory stream, a directional bar
     * histogram, a live feed and a vector readout. The forecast therefore
     * uses only those observable data concepts:
     * 1) encode each genuine completed OHLC candle as a normalized direction;
     * 2) build a recency-weighted green/red pressure histogram;
     * 3) calculate the aggregate price-direction vector;
     * 4) issue the next-candle direction only when histogram and vector agree.
     *
     * No invented liquidity, support/resistance, EMA/RSI/MACD, or synthetic
     * broker candles are part of this procedure.
     */
    private Scan scan(String symbol, long currentBucket, double livePrice) {
        List<Candle> memory = new ArrayList<>();
        for (Candle c : candles)
            if (c.asset.equals(symbol) && c.bucket < currentBucket) memory.add(c);
        int count = memory.size();
        if (count < 3)
            return new Scan("WAIT", 0, "MEMORY STREAM · collecting completed QX OHLC candles (" + count + "/3).");

        int length = Math.min(8, count);
        List<Candle> stream = memory.subList(count - length, count);
        double histogram = 0.0, vectorBody = 0.0, vectorClose = 0.0;
        double totalWeight = 0.0, totalPath = 0.0;
        int bullishBars = 0, bearishBars = 0, reversals = 0;
        int previousDirection = 0;
        double firstClose = stream.get(0).close;
        double lastClose = stream.get(length - 1).close;

        for (int i = 0; i < length; i++) {
            Candle c = stream.get(i);
            double range = safeRange(c);
            double body = clamp((c.close - c.open) / range, -1.0, 1.0);
            double closePosition = clamp(((c.close - c.low) - (c.high - c.close)) / range, -1.0, 1.0);
            double weight = 1.0 + (double) i / Math.max(1, length - 1);

            // Histogram: signed candle pressure, weighted toward the newest bars.
            double barPressure = 0.72 * body + 0.28 * closePosition;
            histogram += barPressure * weight;
            vectorBody += body * weight;
            vectorClose += closePosition * weight;
            totalWeight += weight;

            int direction = direction(c.close - c.open);
            if (direction > 0) bullishBars++;
            else if (direction < 0) bearishBars++;
            if (direction != 0 && previousDirection != 0 && direction != previousDirection) reversals++;
            if (direction != 0) previousDirection = direction;
            if (i > 0) totalPath += Math.abs(c.close - stream.get(i - 1).close);
        }

        histogram = clamp(histogram / Math.max(totalWeight, 1e-12), -1.0, 1.0);
        double bodyVector = clamp(vectorBody / Math.max(totalWeight, 1e-12), -1.0, 1.0);
        double closeVector = clamp(vectorClose / Math.max(totalWeight, 1e-12), -1.0, 1.0);
        double vector = clamp(0.65 * bodyVector + 0.35 * closeVector, -1.0, 1.0);
        double displacementVector = totalPath <= 1e-12 ? 0.0 :
            clamp((lastClose - firstClose) / totalPath, -1.0, 1.0);

        // The live quote confirms that the current feed is still moving with the
        // completed-candle vector; it does not create or modify a broker candle.
        double liveDirection = direction(livePrice - lastClose);
        int histogramDirection = direction(histogram);
        int vectorDirection = direction(vector);
        int displacementDirection = direction(displacementVector);

        boolean aligned = histogramDirection != 0 &&
            histogramDirection == vectorDirection &&
            (displacementDirection == 0 || displacementDirection == histogramDirection);
        boolean weakMemory = Math.abs(histogram) < 0.16 || Math.abs(vector) < 0.16;
        boolean alternating = length >= 5 && reversals >= length - 3 &&
            Math.abs(displacementVector) < 0.22 &&
            Math.abs(histogram) < 0.30;

        if (alternating || weakMemory || !aligned) {
            double clarity = Math.min(69.0, 50.0 + Math.max(Math.abs(histogram), Math.abs(vector)) * 18.0);
            return new Scan("WAIT", clarity,
                "WAIT · MEMORY STREAM / HISTOGRAM / VECTOR not aligned · bull " +
                bullishBars + " / bear " + bearishBars + ".");
        }

        // If the latest live quote sharply opposes the completed memory vector,
        // wait for confirmation instead of forcing a direction.
        if (liveDirection != 0 && liveDirection != histogramDirection &&
            Math.abs(livePrice - lastClose) > safeRange(stream.get(length - 1)) * 0.35) {
            return new Scan("WAIT", 55.0,
                "WAIT · LIVE FEED opposes the completed-candle vector; waiting for alignment.");
        }

        String result = histogramDirection > 0 ? "CALL" : "PUT";
        double agreement = (Math.abs(histogram) + Math.abs(vector) +
            Math.abs(displacementVector)) / 3.0;
        double probability = clamp(50.0 + agreement * 44.0, 50.0, 94.0);
        return new Scan(result, probability,
            result + " · " + Math.round(probability) + "% estimated direction · " +
            "histogram " + signed(histogram) + " · vector " + signed(vector) +
            " · displacement " + signed(displacementVector) +
            " · bull " + bullishBars + " / bear " + bearishBars);
    }

    private double tickFlow(String symbol) {
        List<Tick> recent = new ArrayList<>();
        for (int i = tape.size() - 1; i >= 0 && recent.size() < 12; i--)
            if (tape.get(i).asset.equals(symbol)) recent.add(0, tape.get(i));
        if (recent.size() < 4) return 0;
        int up = 0, down = 0;
        for (int i = 1; i < recent.size(); i++) {
            int d = direction(recent.get(i).price - recent.get(i - 1).price);
            if (d > 0) up++; else if (d < 0) down++;
        }
        int total = up + down;
        return total == 0 ? 0 : clamp((double) (up - down) / total, -1, 1);
    }

    private static double safeRange(Candle c) {
        return Math.max(c.high - c.low, Math.max(Math.abs(c.close) * 1e-10, 1e-12));
    }
    private static int direction(double value) { return value > 0 ? 1 : value < 0 ? -1 : 0; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String signed(double v) { return (v >= 0 ? "+" : "") + String.format(java.util.Locale.US, "%.2f", v); }

    public synchronized String getAsset() { return asset; }
    public synchronized String getSignal() { return signal; }
    public synchronized String getReason() { return reason; }
    public synchronized int getWins() { return wins; }
    public synchronized int getLosses() { return losses; }
    public synchronized int getTies() { return ties; }
    public synchronized int getSignalCount() { return signalCount; }
    public synchronized double getSignalProbability() { return signalProbability; }
    public synchronized int getCandleCount() { return candles.size(); }
    public synchronized long getLastTimestamp() { return lastTimestamp; }
    public synchronized double getLastPrice() { return lastPrice; }
    public synchronized double getOpen() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.open; }
    public synchronized double getHigh() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.high; }
    public synchronized double getLow() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.low; }
    public synchronized double getClose() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.close; }

    private static final class Candle {
        final String asset; final long bucket; final double open; double high, low, close;
        Candle(String a, long b, double p) { this(a, b, p, p, p, p); }
        Candle(String a, long b, double o, double h, double l, double c) {
            asset = a; bucket = b; open = o; high = h; low = l; close = c;
        }
        void update(double p) { high = Math.max(high, p); low = Math.min(low, p); close = p; }
    }
    private static final class Tick {
        final String asset; final double price; final long timestamp;
        Tick(String a, double p, long t) { asset = a; price = p; timestamp = t; }
    }
    private static final class Pending {
        final String asset, signal; final double entry; final long expiresAt;
        Pending(String a, String s, double e, long t) { asset = a; signal = s; entry = e; expiresAt = t; }
    }
    private static final class Scan {
        final String signal, reason; final double probability;
        Scan(String s, double p, String r) { signal = s; probability = p; reason = r; }
    }
}
