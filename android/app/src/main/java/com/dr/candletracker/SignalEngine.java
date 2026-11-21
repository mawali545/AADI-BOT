package com.dr.candletracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;

/** Background next-candle engine. It consumes validated live ticks only. */
public final class SignalEngine {
    private static final long CANDLE_MS = 10_000L;
    private static final int MAX_CANDLES = 900, MAX_TICKS = 600;
    private final List<Candle> candles = new ArrayList<>();
    private final List<Tick> tape = new ArrayList<>();
    private final List<Pending> pendingSignals = new ArrayList<>();
    private final Map<String, Long> lastTimestampByAsset = new HashMap<>();
    private final Map<String, Long> issuedBucketByAsset = new HashMap<>();
    private final Map<String, Long> intervalMsByAsset = new HashMap<>();
    private final Map<String, String> lastIssuedSignalByAsset = new HashMap<>();
    private final Map<String, Integer> signalCountByAsset = new HashMap<>();
    private String asset = "WAITING FOR QX", signal = "WAIT";
    private String reason = "Waiting for real Market-QX 10-second candles.";
    private int wins, losses, ties;
    private long lastTimestamp;
    private double lastPrice = Double.NaN;

    public synchronized void acceptTick(String symbol, double price, long timestamp) {
        acceptTick(symbol, price, timestamp, 10L);
    }

    /** Build fixed 10-second candles from genuine QX quote ticks, regardless of chart timeframe. */
    public synchronized void acceptTick(String symbol, double price, long timestamp, long chartPeriodSeconds) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") ||
            !Double.isFinite(price) || price <= 0 || timestamp <= 0) return;
        if (chartPeriodSeconds > 0 && chartPeriodSeconds <= 3600) intervalMsByAsset.putIfAbsent(symbol, chartPeriodSeconds * 1000L);
        long intervalMs = intervalMsByAsset.containsKey(symbol) ? intervalMsByAsset.get(symbol) : 10_000L;
        Long previous = lastTimestampByAsset.get(symbol);
        if (previous != null && timestamp < previous) return;
        lastTimestampByAsset.put(symbol, timestamp);
        long bucket = timestamp / intervalMs * intervalMs;
        Candle current = lastCandle(symbol);
        if (current != null && bucket < current.bucket) return;
        if (current == null || bucket > current.bucket) {
            current = new Candle(symbol, bucket, price, false);
            candles.add(current);
            if (candles.size() > MAX_CANDLES) candles.remove(0);
            if (!symbol.equals(asset)) {
                asset = symbol; signal = "WAIT";
                reason = "Scanning real 10-second QX candles for " + symbol;
            }
        }
        current.update(price);
        lastTimestamp = timestamp;
        lastPrice = price;
        tape.add(new Tick(symbol, price));
        if (tape.size() > MAX_TICKS) tape.remove(0);

        // Settle at the first genuine quote tick at/after 10 seconds from signal time.
        // This is a time-based expiry, not "wait for the next candle to close".
        settleDueSignals(symbol, price, timestamp);

        Long issued = issuedBucketByAsset.get(symbol);
        if (issued == null || issued.longValue() != bucket) {
            Scan scan = scan(symbol, bucket);
            signal = scan.signal;
            reason = scan.reason;
            if ("WAIT".equals(scan.signal)) {
                lastIssuedSignalByAsset.remove(symbol);
                signalCountByAsset.put(symbol, 0);
            } else {
                String previousSignal = lastIssuedSignalByAsset.get(symbol);
                int count = scan.signal.equals(previousSignal) ? signalCountByAsset.getOrDefault(symbol, 0) + 1 : 1;
                lastIssuedSignalByAsset.put(symbol, scan.signal);
                signalCountByAsset.put(symbol, count);
            }
            if (!"WAIT".equals(scan.signal)) {
                // Save the exact signal tick price/time; evaluate its direction after 10 seconds.
                pendingSignals.add(new Pending(symbol, scan.signal, price, timestamp + CANDLE_MS));
                issuedBucketByAsset.put(symbol, bucket);
            }
        }
    }

    /** Import broker OHLC history only when it is explicitly a 10-second series. */
    public synchronized void acceptHistory(String symbol, long periodSeconds, JSONArray rows) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") || rows == null) return;
        if (periodSeconds < 1L || periodSeconds > 3600L) {
            reason = "QX history period is unavailable or unsupported; waiting for genuine broker history.";
            signal = "WAIT";
            return;
        }
        long intervalMs = periodSeconds * 1000L;
        Long previousInterval = intervalMsByAsset.put(symbol, intervalMs);
        if (previousInterval != null && previousInterval.longValue() != intervalMs) {
            for (int i = candles.size() - 1; i >= 0; i--)
                if (candles.get(i).asset.equals(symbol)) candles.remove(i);
            issuedBucketByAsset.remove(symbol);
            lastTimestampByAsset.remove(symbol);
            lastIssuedSignalByAsset.remove(symbol);
            signalCountByAsset.put(symbol, 0);
        }
        List<Candle> imported = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONArray row = rows.optJSONArray(i);
                if (row == null || row.length() < 5) continue;
                double rawTime = row.getDouble(0);
                long time = (long) rawTime;
                if (time > 0 && time < 100000000000L) time *= 1000L;
                double open = row.getDouble(1), close = row.getDouble(2);
                double high = row.getDouble(3), low = row.getDouble(4);
                if (time <= 0 || !Double.isFinite(open) || !Double.isFinite(close) ||
                    !Double.isFinite(high) || !Double.isFinite(low) || open <= 0 || close <= 0 ||
                    high < Math.max(open, close) || low > Math.min(open, close) || low <= 0) continue;
                long bucket = time / intervalMs * intervalMs;
                imported.add(new Candle(symbol, bucket, open, high, low, close, true));
            } catch (JSONException ignored) {}
        }
        if (imported.isEmpty()) return;
        imported.sort((a, b) -> Long.compare(a.bucket, b.bucket));
        for (int i = candles.size() - 1; i >= 0; i--)
            if (candles.get(i).asset.equals(symbol) && candles.get(i).historical) candles.remove(i);
        for (Candle candle : imported) {
            boolean exists = false;
            for (Candle existing : candles)
                if (existing.asset.equals(symbol) && existing.bucket == candle.bucket) { exists = true; break; }
            if (!exists) candles.add(candle);
        }
        candles.sort((a, b) -> {
            int byAsset = a.asset.compareTo(b.asset);
            return byAsset != 0 ? byAsset : Long.compare(a.bucket, b.bucket);
        });
        while (candles.size() > MAX_CANDLES) candles.remove(0);
        asset = symbol;
        reason = "Loaded " + imported.size() + " original QX 10-second OHLC candles; waiting for live quote.";
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

    private Scan scan(String symbol, long currentBucket) {
        List<Candle> closed = new ArrayList<>();
        for (Candle c : candles) if (c.asset.equals(symbol) && c.bucket < currentBucket) closed.add(c);
        long intervalMs = intervalMsByAsset.containsKey(symbol) ? intervalMsByAsset.get(symbol) : 10_000L;
        List<Candle> history = new ArrayList<>(), live = new ArrayList<>();
        for (Candle candle : closed) {
            if (candle.historical) history.add(candle);
            else live.add(candle);
        }
        if (history.size() < 10)
            return new Scan("WAIT", "Collecting original broker-history candles (" + history.size() + "/10).");
        List<Candle> historyWindow = history.subList(history.size() - 10, history.size());
        Candle lastHistory = historyWindow.get(historyWindow.size() - 1);
        List<Candle> liveAfterHistory = new ArrayList<>();
        for (Candle candle : live) if (candle.bucket > lastHistory.bucket) liveAfterHistory.add(candle);
        if (liveAfterHistory.size() < 3)
            return new Scan("WAIT", "Collecting 3 completed genuine live candles after the 10 broker-history candles (" + liveAfterHistory.size() + "/3).");
        List<Candle> liveWindow = liveAfterHistory.subList(liveAfterHistory.size() - 3, liveAfterHistory.size());
        for (int i = 1; i < historyWindow.size(); i++)
            if (historyWindow.get(i).bucket - historyWindow.get(i - 1).bucket != intervalMs)
                return new Scan("WAIT", "Broker history has a genuine candle gap; waiting for a consecutive 10-candle history window.");
        if (liveWindow.get(0).bucket - lastHistory.bucket != intervalMs)
            return new Scan("WAIT", "Live QX candles do not continue directly after broker history; waiting for a continuous feed.");
        for (int i = 1; i < liveWindow.size(); i++)
            if (liveWindow.get(i).bucket - liveWindow.get(i - 1).bucket != intervalMs)
                return new Scan("WAIT", "A gap exists in the latest genuine live candles; waiting for 3 consecutive candles.");
        List<Candle> recent = new ArrayList<>(historyWindow);
        recent.addAll(liveWindow);
        Candle last = recent.get(recent.size()-1), first = recent.get(0);
        double range = Math.max(last.high-last.low, Math.max(Math.abs(last.close)*1e-10, 1e-12));
        double body = (last.close-last.open)/range;
        double upper = last.high-Math.max(last.open,last.close), lower = Math.min(last.open,last.close)-last.low;
        double wick = (lower-upper)/range, avgRange = 0;
        for (Candle c : recent) avgRange += Math.max(c.high-c.low, 1e-12);
        avgRange /= recent.size();
        double momentum = (last.close-first.close)/Math.max(avgRange*recent.size(), 1e-12);
        int score = 0; List<String> reasons = new ArrayList<>();
        if (momentum > .28) { score++; reasons.add("upward momentum"); }
        else if (momentum < -.28) { score--; reasons.add("downward momentum"); }
        int trend = direction(last.close-first.open);
        if (trend != 0) { score += trend; reasons.add(trend > 0 ? "bullish structure" : "bearish structure"); }
        if (body > .25) { score++; reasons.add("bullish candle pressure"); }
        else if (body < -.25) { score--; reasons.add("bearish candle pressure"); }
        if (wick > .42) { score++; reasons.add("lower-wick rejection"); }
        else if (wick < -.42) { score--; reasons.add("upper-wick rejection"); }
        double priorHigh = Double.NEGATIVE_INFINITY, priorLow = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, recent.size()-6); i < recent.size()-1; i++) {
            priorHigh = Math.max(priorHigh, recent.get(i).high);
            priorLow = Math.min(priorLow, recent.get(i).low);
        }
        if (last.low < priorLow && last.close > priorLow) { score += 2; reasons.add("support liquidity sweep"); }
        else if (last.high > priorHigh && last.close < priorHigh) { score -= 2; reasons.add("resistance liquidity sweep"); }
        if (recent.size() >= 4) {
            Candle before = recent.get(recent.size()-2), before2 = recent.get(recent.size()-3);
            int priorTrend = direction(before.close-before2.close);
            int pullback = direction(before.close-before.open), recovery = direction(last.close-last.open);
            if (priorTrend > 0 && pullback < 0 && recovery > 0) { score++; reasons.add("bullish pullback recovery"); }
            else if (priorTrend < 0 && pullback > 0 && recovery < 0) { score--; reasons.add("bearish pullback recovery"); }
        }
        int tickDirection = recentTickDirection(symbol);
        if (tickDirection != 0) { score += tickDirection; reasons.add(tickDirection > 0 ? "live tick pressure up" : "live tick pressure down"); }
        int alternations = 0, smallBodies = 0;
        for (int i = 0; i < recent.size(); i++) {
            Candle c = recent.get(i); double r = Math.max(c.high-c.low, 1e-12);
            if (Math.abs(c.close-c.open)/r < .22) smallBodies++;
            if (i > 0 && direction(c.close-c.open) != direction(recent.get(i-1).close-recent.get(i-1).open)) alternations++;
        }
        if (recent.size() >= 5 && alternations >= 3 && smallBodies >= 3)
            return new Scan("WAIT", "Sideways/choppy candles detected; no directional signal.");
        if (score >= 3) return new Scan("CALL", "CALL · " + join(reasons));
        if (score <= -3) return new Scan("PUT", "PUT · " + join(reasons));
        return new Scan("WAIT", reasons.isEmpty() ? "Mixed candle evidence; waiting." : "Mixed evidence: " + join(reasons));
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
    public synchronized int getSignalCount() { return signalCountByAsset.getOrDefault(asset, 0); }
    public synchronized int getCandleCount() { return candles.size(); }
    public synchronized long getLastTimestamp() { return lastTimestamp; }
    public synchronized double getLastPrice() { return lastPrice; }
    public synchronized double getOpen() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.open; }
    public synchronized double getHigh() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.high; }
    public synchronized double getLow() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.low; }
    public synchronized double getClose() { Candle c = lastCandle(asset); return c == null ? Double.NaN : c.close; }

    private static final class Candle {
        final String asset; final long bucket; final double open; final boolean historical;
        double high, low, close;
        Candle(String a, long b, double p, boolean fromHistory) {
            this(a,b,p,p,p,p,fromHistory);
        }
        Candle(String a, long b, double o, double h, double l, double c, boolean fromHistory) {
            asset=a; bucket=b; open=o; high=h; low=l; close=c; historical=fromHistory;
        }
        void update(double p) { high=Math.max(high,p); low=Math.min(low,p); close=p; }
    }
    private static final class Tick { final String asset; final double price; Tick(String a,double p) { asset=a; price=p; } }
    private static final class Pending {
        final String asset, signal; final double entry; final long targetTimestamp;
        Pending(String a,String s,double e,long t) { asset=a; signal=s; entry=e; targetTimestamp=t; }
    }
    private static final class Scan { final String signal, reason; Scan(String s,String r) { signal=s; reason=r; } }
}