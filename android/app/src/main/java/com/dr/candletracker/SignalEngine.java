package com.dr.candletracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Background next-candle engine. It consumes validated live ticks only. */
public final class SignalEngine {
    private static final long CANDLE_MS = 10_000L;
    private static final int MAX_CANDLES = 900, MAX_TICKS = 600;
    private final List<Candle> candles = new ArrayList<>();
    private final List<Tick> tape = new ArrayList<>();
    private final List<Pending> pendingSignals = new ArrayList<>();
    private final Map<String, Long> lastTimestampByAsset = new HashMap<>();
    private final Map<String, Long> issuedBucketByAsset = new HashMap<>();
    private String asset = "WAITING FOR QX", signal = "WAIT";
    private String reason = "Waiting for real Market-QX 10-second candles.";
    private int wins, losses, ties;
    private long lastTimestamp;
    private double lastPrice = Double.NaN;

    /** Accept genuine QX quote ticks for live tape and settlement only. Never synthesize OHLC. */
    public synchronized void acceptTick(String symbol, double price, long timestamp) {
        acceptTick(symbol, price, timestamp, 10L);
    }

    public synchronized void acceptTick(String symbol, double price, long timestamp, long ignoredChartPeriodSeconds) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") ||
            !Double.isFinite(price) || price <= 0 || timestamp <= 0) return;
        Long previous = lastTimestampByAsset.get(symbol);
        if (previous != null && timestamp < previous) return;
        lastTimestampByAsset.put(symbol, timestamp);
        lastTimestamp = timestamp;
        lastPrice = price;
        tape.add(new Tick(symbol, price));
        if (tape.size() > MAX_TICKS) tape.remove(0);

        // Live quotes settle signals only. OHLC is accepted exclusively from QX candle/history events.
        settleDueSignals(symbol, price, timestamp);
        if (!symbol.equals(asset)) {
            asset = symbol;
            signal = "WAIT";
            reason = "Waiting for original QX 10-second candle history for " + symbol + ".";
        }
        long liveBucket = timestamp / CANDLE_MS * CANDLE_MS;
        Candle newest = lastCandle(symbol);
        if (newest == null || newest.bucket >= liveBucket) {
            signal = "WAIT";
            reason = newest == null
                ? "No original QX 10-second candles received; synthetic candles are disabled."
                : "Waiting for the next QX candle interval to begin.";
            return;
        }
        Long issued = issuedBucketByAsset.get(symbol);
        if (issued == null || issued.longValue() != liveBucket) {
            Scan scan = scan(symbol, liveBucket);
            signal = scan.signal;
            reason = scan.reason;
            if (!"WAIT".equals(scan.signal)) {
                pendingSignals.add(new Pending(symbol, scan.signal, price, timestamp + CANDLE_MS));
                issuedBucketByAsset.put(symbol, liveBucket);
            }
        }
    }

    /** Import original broker OHLC only. Tick aggregation is deliberately disabled. */
    public synchronized void acceptHistory(String symbol, long periodSeconds, JSONArray rows) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") || rows == null) return;
        if (periodSeconds != 10L) {
            reason = "QX supplied " + periodSeconds + "s candles; original 10-second QX candles are required. No synthetic OHLC.";
            signal = "WAIT";
            return;
        }
        List<Candle> imported = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONArray row = rows.optJSONArray(i);
                JSONObject objectRow = rows.optJSONObject(i);
                if ((row == null || row.length() < 5) && objectRow == null) continue;
                double rawTime, open, close, high, low;
                if (row != null) {
                    rawTime = row.getDouble(0);
                    open = row.getDouble(1); close = row.getDouble(2);
                    high = row.getDouble(3); low = row.getDouble(4);
                } else {
                    rawTime = objectRow.optDouble("time", objectRow.optDouble("timestamp", objectRow.optDouble("ts", 0)));
                    open = objectRow.optDouble("open", objectRow.optDouble("o", Double.NaN));
                    close = objectRow.optDouble("close", objectRow.optDouble("c", Double.NaN));
                    high = objectRow.optDouble("high", objectRow.optDouble("h", Double.NaN));
                    low = objectRow.optDouble("low", objectRow.optDouble("l", Double.NaN));
                }
                long time = (long) rawTime;
                if (time > 0 && time < 100000000000L) time *= 1000L;
                if (time <= 0 || !Double.isFinite(open) || !Double.isFinite(close) ||
                    !Double.isFinite(high) || !Double.isFinite(low) || open <= 0 || close <= 0 ||
                    high < Math.max(open, close) || low > Math.min(open, close) || low <= 0) continue;
                long bucket = time / CANDLE_MS * CANDLE_MS;
                imported.add(new Candle(symbol, bucket, open, high, low, close));
            } catch (JSONException ignored) {}
        }
        if (imported.isEmpty()) return;
        imported.sort((a, b) -> Long.compare(a.bucket, b.bucket));
        for (Candle incoming : imported) {
            boolean replaced = false;
            for (int i = 0; i < candles.size(); i++) {
                Candle existing = candles.get(i);
                if (existing.asset.equals(symbol) && existing.bucket == incoming.bucket) {
                    candles.set(i, incoming);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) candles.add(incoming);
        }
        candles.sort((a, b) -> Long.compare(a.bucket, b.bucket));
        while (candles.size() > MAX_CANDLES) candles.remove(0);
        asset = symbol;
        reason = "Received " + imported.size() + " original QX 10-second OHLC candle(s); awaiting live quote.";
        signal = "WAIT";
    }

    private Candle lastCandle(String symbol) {
        for (int i = candles.size()-1; i >= 0; i--)
            if (candles.get(i).asset.equals(symbol)) return candles.get(i);
        return null;
    }
    private Candle candleAt(String symbol, long bucket) {
        for (int i = candles.size()-1; i >= 0; i--) {
            Candle c = candles.get(i);
            if (c.asset.equals(symbol) && c.bucket == bucket) return c;
        }
        return null;
    }

    /** Settle each signal independently on the first real tick at/after its 10s expiry. */
    private void settleDueSignals(String symbol, double livePrice, long timestamp) {
        Iterator<Pending> it = pendingSignals.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (!p.asset.equals(symbol) || timestamp < p.targetTimestamp) continue;
            double delta = livePrice - p.entry;
            if (Math.abs(delta) < Math.max(1e-12, Math.abs(p.entry) * 1e-12)) ties++;
            else if (("CALL".equals(p.signal) && delta > 0) || ("PUT".equals(p.signal) && delta < 0)) wins++;
            else losses++;
            it.remove();
        }
    }

    /**
     * Multi-factor next-candle scan. Each named feature contributes an explicit
     * directional vote; the engine abstains when votes disagree or volatility/chop
     * conditions make a directional call unreliable.
     */
    private Scan scan(String symbol, long currentBucket) {
        List<Candle> closed = new ArrayList<>();
        for (Candle c : candles) if (c.asset.equals(symbol) && c.bucket < currentBucket) closed.add(c);
        int n = closed.size();
        if (n < 7) return new Scan("WAIT", "Collecting completed real 10-second candles (" + n + "/7).");
        List<Candle> recent = closed.subList(n - 7, n);
        Candle last = recent.get(6), previous = recent.get(5), first = recent.get(0);
        double range = Math.max(last.high - last.low, Math.max(Math.abs(last.close) * 1e-10, 1e-12));
        double bodyPressure = (last.close - last.open) / range;
        double upperWick = (last.high - Math.max(last.open, last.close)) / range;
        double lowerWick = (Math.min(last.open, last.close) - last.low) / range;
        double avgRange = 0, priorAvgRange = 0;
        for (int i = 0; i < recent.size(); i++) avgRange += Math.max(recent.get(i).high - recent.get(i).low, 1e-12);
        avgRange /= recent.size();
        int priorCount = Math.min(12, n - 7);
        for (int i = n - 7 - priorCount; i < n - 7; i++)
            priorAvgRange += Math.max(closed.get(i).high - closed.get(i).low, 1e-12);
        if (priorCount > 0) priorAvgRange /= priorCount;
        double momentum = (last.close - first.close) / Math.max(avgRange * recent.size(), 1e-12);

        int bullish = 0, bearish = 0;
        List<String> evidence = new ArrayList<>();
        // Candle body pressure
        if (bodyPressure > .28) { bullish++; evidence.add("bullish candle body pressure"); }
        else if (bodyPressure < -.28) { bearish++; evidence.add("bearish candle body pressure"); }
        // Momentum
        if (momentum > .22) { bullish++; evidence.add("positive momentum"); }
        else if (momentum < -.22) { bearish++; evidence.add("negative momentum"); }
        // Multi-candle directional structure
        int structure = direction(last.close - first.open);
        if (structure > 0) { bullish++; evidence.add("bullish structure"); }
        else if (structure < 0) { bearish++; evidence.add("bearish structure"); }
        // Wick rejection
        if (lowerWick > .42 && bodyPressure > -.1) { bullish++; evidence.add("lower-wick rejection"); }
        if (upperWick > .42 && bodyPressure < .1) { bearish++; evidence.add("upper-wick rejection"); }

        // Support/resistance and liquidity sweep
        double support = Double.POSITIVE_INFINITY, resistance = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < recent.size() - 1; i++) {
            support = Math.min(support, recent.get(i).low);
            resistance = Math.max(resistance, recent.get(i).high);
        }
        boolean sweptSupport = last.low < support && last.close > support;
        boolean sweptResistance = last.high > resistance && last.close < resistance;
        if (sweptSupport) { bullish += 2; evidence.add("support liquidity sweep / failed breakdown"); }
        else if (sweptResistance) { bearish += 2; evidence.add("resistance liquidity sweep / failed breakout"); }
        else {
            double proximity = Math.max(avgRange * .18, Math.abs(last.close) * 1e-8);
            if (Math.abs(last.close - support) <= proximity && last.close > last.open) {
                bullish++; evidence.add("support response");
            } else if (Math.abs(resistance - last.close) <= proximity && last.close < last.open) {
                bearish++; evidence.add("resistance rejection");
            }
        }

        // Pullback and recovery
        Candle before = recent.get(5), before2 = recent.get(4);
        int priorTrend = direction(before.close - before2.close);
        int pullback = direction(before.close - before.open);
        int recovery = direction(last.close - last.open);
        if (priorTrend > 0 && pullback < 0 && recovery > 0) { bullish++; evidence.add("bullish pullback recovery"); }
        else if (priorTrend < 0 && pullback > 0 && recovery < 0) { bearish++; evidence.add("bearish pullback recovery"); }

        // Tick microstructure vector: pressure, acceleration, and path efficiency.
        VectorStats vector = vectorStats(symbol);
        if (vector.count >= 5) {
            if (vector.pressure > .25 && vector.efficiency > .18) { bullish++; evidence.add("positive tick vector / microstructure"); }
            else if (vector.pressure < -.25 && vector.efficiency > .18) { bearish++; evidence.add("negative tick vector / microstructure"); }
        }

        // Market chop filter: alternating candle bodies and low directional efficiency.
        int alternations = 0, smallBodies = 0;
        for (int i = 0; i < recent.size(); i++) {
            Candle c = recent.get(i); double r = Math.max(c.high - c.low, 1e-12);
            if (Math.abs(c.close - c.open) / r < .22) smallBodies++;
            if (i > 0 && direction(c.close - c.open) != direction(recent.get(i - 1).close - recent.get(i - 1).open)) alternations++;
        }
        if ((alternations >= 3 && smallBodies >= 3) || (vector.count >= 5 && vector.efficiency < .10))
            return new Scan("WAIT", "Market chop detected; directional votes suppressed.");

        // Volatility regime filter avoids chasing abnormal expansion or dead markets.
        if (priorCount >= 5 && priorAvgRange > 0) {
            double ratio = avgRange / priorAvgRange;
            if (ratio > 2.8) return new Scan("WAIT", "Volatility expansion is abnormal; waiting for normalization.");
            if (ratio < .22) return new Scan("WAIT", "Volatility is too compressed; waiting for movement.");
        }

        int total = bullish + bearish;
        if (total == 0) return new Scan("WAIT", "No confirmed directional evidence.");
        double agreement = (double) Math.max(bullish, bearish) / total;
        if (agreement < .72) return new Scan("WAIT", "Bullish/bearish votes disagree; waiting for directional agreement.");
        if (bullish >= 4 && bullish - bearish >= 3 && agreement >= .72)
            return new Scan("CALL", "CALL · " + join(evidence) + " · directional agreement " + Math.round(agreement * 100) + "%");
        if (bearish >= 4 && bearish - bullish >= 3 && agreement >= .72)
            return new Scan("PUT", "PUT · " + join(evidence) + " · directional agreement " + Math.round(agreement * 100) + "%");
        return new Scan("WAIT", "Insufficient directional votes (" + bullish + " bullish / " + bearish + " bearish).");
    }

    private VectorStats vectorStats(String symbol) {
        List<Double> prices = new ArrayList<>();
        for (Tick t : tape) if (t.asset.equals(symbol)) prices.add(t.price);
        int n = prices.size();
        if (n < 5) return new VectorStats(n, 0, 0, 0);
        int from = Math.max(1, n - 12), up = 0, down = 0;
        double path = 0;
        for (int i = from; i < n; i++) {
            double d = prices.get(i) - prices.get(i - 1);
            path += Math.abs(d);
            if (d > 0) up++; else if (d < 0) down++;
        }
        double net = prices.get(n - 1) - prices.get(from - 1);
        double efficiency = path > 0 ? Math.abs(net) / path : 0;
        double pressure = (double) (up - down) / Math.max(1, up + down);
        double acceleration = 0;
        if (n >= 6) {
            double recentMove = prices.get(n - 1) - prices.get(n - 3);
            double priorMove = prices.get(n - 3) - prices.get(n - 5);
            acceleration = recentMove - priorMove;
        }
        double scale = Math.max(Math.abs(prices.get(n - 1)) * 1e-9, 1e-12);
        return new VectorStats(n, pressure + Math.signum(acceleration / scale) * .05, efficiency, acceleration);
    }

    private int recentTickDirection(String symbol) {
        List<Double> p = new ArrayList<>();
        for (Tick t : tape) if (t.asset.equals(symbol)) p.add(t.price);
        if (p.size() < 4) return 0;
        int up = 0, down = 0;
        for (int i = Math.max(1, p.size()-8); i < p.size(); i++) {
            if (p.get(i) > p.get(i-1)) up++;
            else if (p.get(i) < p.get(i-1)) down++;
        }
        if (up >= down+3) return 1;
        if (down >= up+3) return -1;
        return 0;
    }
    private static int direction(double v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }
    private static String join(List<String> a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) { if (b.length() > 0) b.append(", "); b.append(s); }
        return b.length() == 0 ? "no confirmed direction" : b.toString();
    }

    public synchronized String getAsset() { return asset; }
    public synchronized String getSignal() { return signal; }
    public synchronized String getReason() { return reason; }
    public synchronized int getWins() { return wins; }
    public synchronized int getLosses() { return losses; }
    public synchronized int getTies() { return ties; }
    public synchronized int getCandleCount() { return candles.size(); }
    public synchronized long getLastTimestamp() { return lastTimestamp; }
    public synchronized double getLastPrice() { return lastPrice; }
    public synchronized double getOpen() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.open; }
    public synchronized double getHigh() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.high; }
    public synchronized double getLow() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.low; }
    public synchronized double getClose() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.close; }

    private static final class VectorStats { final int count; final double pressure, efficiency, acceleration; VectorStats(int c,double p,double e,double a){count=c;pressure=p;efficiency=e;acceleration=a;} }
    private static final class Candle {
        final String asset; final long bucket; final double open; double high, low, close;
        Candle(String a, long b, double p) { this(a,b,p,p,p,p); }
        Candle(String a, long b, double o, double h, double l, double c) { asset=a; bucket=b; open=o; high=h; low=l; close=c; }
        void update(double p) { high=Math.max(high,p); low=Math.min(low,p); close=p; }
    }
    private static final class Tick { final String asset; final double price; Tick(String a,double p) { asset=a; price=p; } }
    private static final class Pending {
        final String asset, signal; final double entry; final long targetTimestamp;
        Pending(String a,String s,double e,long t) { asset=a; signal=s; entry=e; targetTimestamp=t; }
    }
    private static final class Scan { final String signal, reason; Scan(String s,String r) { signal=s; reason=r; } }
}