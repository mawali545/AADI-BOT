package com.dr.candletracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private Scan scan(String symbol, long currentBucket, double livePrice) {
        List<Candle> closed = new ArrayList<>();
        for (Candle c : candles)
            if (c.asset.equals(symbol) && c.bucket < currentBucket) closed.add(c);
        int n = closed.size();
        if (n < 3)
            return new Scan("WAIT", 0, "Collecting genuine completed QX candles (" + n + "/3).");
        int size = Math.min(12, n);
        List<Candle> recent = closed.subList(n - size, n);
        Candle last = recent.get(size - 1);
        double atr = 0, bodyPressure = 0, wickPressure = 0, bodyAbs = 0;
        double weightSum = 0;
        for (int i = 0; i < size; i++) {
            Candle c = recent.get(i);
            double range = safeRange(c);
            double weight = 1.0 + (double) i / Math.max(1, size - 1);
            double body = clamp((c.close - c.open) / range, -1, 1);
            double upper = Math.max(0, c.high - Math.max(c.open, c.close));
            double lower = Math.max(0, Math.min(c.open, c.close) - c.low);
            bodyPressure += body * weight;
            wickPressure += clamp((lower - upper) / range, -1, 1) * weight;
            bodyAbs += Math.abs(body);
            atr += range;
            weightSum += weight;
        }
        atr /= size;
        bodyPressure = clamp(bodyPressure / weightSum, -1, 1);
        wickPressure = clamp(wickPressure / weightSum, -1, 1);
        double averageBody = bodyAbs / size;

        // Higher-high/higher-low or lower-high/lower-low structure.
        double structure = 0; int structureVotes = 0;
        for (int i = Math.max(1, size - 6); i < size; i++) {
            Candle a = recent.get(i - 1), b = recent.get(i);
            int hi = direction(b.high - a.high), lo = direction(b.low - a.low);
            if (hi > 0 && lo > 0) structure += 1;
            else if (hi < 0 && lo < 0) structure -= 1;
            else structure += direction(b.close - a.close) * 0.25;
            structureVotes++;
        }
        structure = structureVotes == 0 ? 0 : clamp(structure / structureVotes, -1, 1);

        // Price momentum normalized by actual recent candle range, not an indicator.
        Candle first = recent.get(0);
        double momentum = clamp((last.close - first.close) /
            Math.max(atr * Math.max(2, size - 1) * 0.72, Math.abs(last.close) * 1e-10), -1, 1);

        // Nearby swing levels are calculated from preceding genuine candles.
        double support = Double.POSITIVE_INFINITY, resistance = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, size - 9); i < size - 1; i++) {
            support = Math.min(support, recent.get(i).low);
            resistance = Math.max(resistance, recent.get(i).high);
        }
        double liquidity = 0, levelReaction = 0;
        if (Double.isFinite(support) && Double.isFinite(resistance)) {
            double tolerance = Math.max(atr * 0.08, Math.abs(last.close) * 1e-10);
            if (last.low < support - tolerance && last.close > support) liquidity += 1;
            if (last.high > resistance + tolerance && last.close < resistance) liquidity -= 1;
            double supportDistance = Math.abs(livePrice - support) / Math.max(atr, 1e-12);
            double resistanceDistance = Math.abs(livePrice - resistance) / Math.max(atr, 1e-12);
            if (supportDistance < 0.28 && livePrice >= last.close) levelReaction += 0.65;
            if (resistanceDistance < 0.28 && livePrice <= last.close) levelReaction -= 0.65;
        }

        // A trend pullback is counted only when live price begins recovering in trend direction.
        double priorTrend = direction(recent.get(size - 2).close - recent.get(Math.max(0, size - 4)).close);
        double lastBody = direction(last.close - last.open);
        double liveMove = direction(livePrice - last.close);
        double pullback = 0;
        if (priorTrend > 0 && lastBody < 0 && liveMove > 0) pullback = 1;
        else if (priorTrend < 0 && lastBody > 0 && liveMove < 0) pullback = -1;

        double tickFlow = tickFlow(symbol);
        int alternations = 0;
        for (int i = 1; i < size; i++) {
            int a = direction(recent.get(i - 1).close - recent.get(i - 1).open);
            int b = direction(recent.get(i).close - recent.get(i).open);
            if (a != 0 && b != 0 && a != b) alternations++;
        }
        double path = 0;
        for (int i = 1; i < size; i++)
            path += Math.abs(recent.get(i).close - recent.get(i - 1).close);
        double efficiency = path <= 1e-12 ? 0 :
            Math.abs(last.close - first.close) / path;
        boolean choppy = size >= 5 && alternations >= 3 && efficiency < 0.24 && averageBody < 0.42;
        double volatilityRatio = safeRange(last) / Math.max(atr, 1e-12);
        double volatilityPenalty = volatilityRatio > 2.6 ? 0.08 :
            volatilityRatio < 0.30 ? 0.06 : 0;

        double score = 0.18 * bodyPressure + 0.18 * structure + 0.16 * momentum +
            0.08 * wickPressure + 0.13 * liquidity + 0.10 * pullback +
            0.11 * tickFlow + 0.06 * clamp(levelReaction, -1, 1);
        score = clamp(score, -1, 1);
        int bullVotes = 0, bearVotes = 0;
        double[] components = {bodyPressure, structure, momentum, wickPressure,
            liquidity, pullback, tickFlow, levelReaction};
        for (double v : components) {
            if (v > 0.12) bullVotes++;
            else if (v < -0.12) bearVotes++;
        }
        double threshold = 0.27 + volatilityPenalty + (choppy ? 0.16 : 0);
        if (choppy)
            return new Scan("WAIT", Math.min(69, 50 + Math.abs(score) * 18),
                "WAIT · Sideways/choppy flow; directional agreement is weak.");
        if (Math.abs(score) < threshold || Math.max(bullVotes, bearVotes) < 2)
            return new Scan("WAIT", Math.min(69, 50 + Math.abs(score) * 18),
                "WAIT · Mixed evidence; bull " + bullVotes + " / bear " + bearVotes + ".");

        String direction = score > 0 ? "CALL" : "PUT";
        double probability = clamp(50 + Math.abs(score) * 47 - volatilityPenalty * 25, 50, 95);
        String reason = direction + " · " + Math.round(probability) + "% estimated confidence" +
            " · bull " + bullVotes + " / bear " + bearVotes +
            " · pressure " + signed(bodyPressure) +
            " · structure " + signed(structure) +
            " · momentum " + signed(momentum) +
            " · wick " + signed(wickPressure) +
            " · sweep " + signed(liquidity) +
            " · pullback " + signed(pullback) +
            " · tick flow " + signed(tickFlow);
        return new Scan(direction, probability, reason);
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
