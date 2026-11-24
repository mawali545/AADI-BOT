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
    private final Map<String, Long> liveBucketByAsset = new HashMap<>();
    private final Map<String, Long> issuedBucketByAsset = new HashMap<>();
    private final List<Pending> pendingResults = new ArrayList<>();
    private int signalCount;
    private String asset = "WAITING FOR QX", signal = "WAIT";
    private String reason = "Waiting for real Market-QX candles.";
    private int wins, losses, ties;
    private long lastTimestamp;
    private double lastPrice = Double.NaN, signalProbability;
    private double lastHistogram, lastVector, lastDisplacement;

    public synchronized void acceptTick(String symbol, double price, long timestamp) {
        acceptTick(symbol, price, timestamp, 10L, null);
    }

    /** Consume a genuine QX quote using the chart's currently selected period. */
    public synchronized void acceptTick(String symbol, double price, long timestamp, long periodSeconds) {
        acceptTick(symbol, price, timestamp, periodSeconds, null);
    }

    /** Consume the broker's optional -1/0/+1 tick-direction field without inventing volume. */
    public synchronized void acceptTick(String symbol, double price, long timestamp,
            long periodSeconds, Integer brokerDirection) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") ||
            !Double.isFinite(price) || price <= 0 || timestamp <= 0) return;
        long duration = Math.max(1L, Math.min(86400L, periodSeconds)) * 1000L;
        Long previous = lastTimestampByAsset.get(symbol);
        // QX quote timestamps identify events: ignore duplicate and older frames.
        if (previous != null && timestamp <= previous) return;

        // Each result is settled once, on the first actual QX quote at/after expiry.
        settlePending(symbol, timestamp, price);
        lastTimestampByAsset.put(symbol, timestamp);
        long bucket = timestamp / duration * duration;
        Candle current = lastCandle(symbol);
        if (current != null && bucket < current.bucket) return;
        liveBucketByAsset.put(symbol, bucket);
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
        tape.add(new Tick(symbol, price, timestamp, brokerDirection));
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
        liveBucketByAsset.remove(symbol);
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
     * Price-action signal procedure inferred from the reference panel's OHLC
     * memory, directional histogram, vector and live feed.
     *
     * Direction alone is never an entry. A continuation needs a real pullback
     * followed by renewed candle confirmation. A reversal needs a decisive
     * close through the recent structure boundary. Extended moves, weak closes,
     * opposing live flow and choppy memory remain WAIT.
     */
    /**
     * Market-regime engine. All candle inputs are broker-supplied QX OHLC.
     * It separates trend continuation, liquidity-sweep reversals, volatility
     * shocks and low-quality/range conditions before allowing an entry.
     */
    private Scan scan(String symbol, long currentBucket, double livePrice) {
        List<Candle> stream = new ArrayList<>();
        for (int i = candles.size() - 1; i >= 0 && stream.size() < 12; i--) {
            Candle c = candles.get(i);
            if (c.asset.equals(symbol) && c.bucket < currentBucket) stream.add(0, c);
        }
        int length = stream.size();
        if (length < 3)
            return new Scan("WAIT", 0,
                "MEMORY STREAM · collecting 3 completed genuine QX candles (" + length + "/3).");

        Candle last = stream.get(length - 1);
        Candle previous = stream.get(length - 2);
        double atr = 0.0, histogram = 0.0, vectorBody = 0.0, vectorClose = 0.0;
        double totalWeight = 0.0, totalPath = 0.0, priorRangeSum = 0.0;
        int bullishBars = 0, bearishBars = 0, reversals = 0, priorDirection = 0;
        double priorHigh = Double.NEGATIVE_INFINITY, priorLow = Double.POSITIVE_INFINITY;
        for (int i = 0; i < length; i++) {
            Candle c = stream.get(i);
            double range = safeRange(c);
            double trueRange = range;
            if (i > 0) {
                Candle p = stream.get(i - 1);
                trueRange = Math.max(range, Math.max(Math.abs(c.high - p.close), Math.abs(c.low - p.close)));
            }
            double body = clamp((c.close - c.open) / range, -1.0, 1.0);
            double closePosition = clamp(((c.close - c.low) - (c.high - c.close)) / range, -1.0, 1.0);
            double weight = 1.0 + (double) i / Math.max(1, length - 1);
            histogram += (0.68 * body + 0.32 * closePosition) * weight;
            vectorBody += body * weight;
            vectorClose += closePosition * weight;
            totalWeight += weight;
            atr += trueRange;
            if (i < length - 1) {
                priorRangeSum += trueRange;
                priorHigh = Math.max(priorHigh, c.high);
                priorLow = Math.min(priorLow, c.low);
            }
            int d = direction(c.close - c.open);
            if (d > 0) bullishBars++;
            else if (d < 0) bearishBars++;
            if (d != 0 && priorDirection != 0 && d != priorDirection) reversals++;
            if (d != 0) priorDirection = d;
            if (i > 0) totalPath += Math.abs(c.close - stream.get(i - 1).close);
        }
        atr /= length;
        double priorAtr = priorRangeSum / Math.max(1, length - 1);
        histogram = clamp(histogram / totalWeight, -1, 1);
        double vector = clamp(0.62 * (vectorBody / totalWeight) +
            0.38 * (vectorClose / totalWeight), -1, 1);
        double displacement = totalPath <= 1e-12 ? 0 :
            clamp((last.close - stream.get(0).close) / totalPath, -1, 1);
        lastHistogram = histogram;
        lastVector = vector;
        lastDisplacement = displacement;

        int half = Math.max(1, length / 2);
        double earlyHigh = Double.NEGATIVE_INFINITY, earlyLow = Double.POSITIVE_INFINITY;
        double lateHigh = Double.NEGATIVE_INFINITY, lateLow = Double.POSITIVE_INFINITY;
        for (int i = 0; i < length; i++) {
            Candle c = stream.get(i);
            if (i < half) {
                earlyHigh = Math.max(earlyHigh, c.high);
                earlyLow = Math.min(earlyLow, c.low);
            } else {
                lateHigh = Math.max(lateHigh, c.high);
                lateLow = Math.min(lateLow, c.low);
            }
        }
        boolean bullishStructure = lateHigh > earlyHigh + atr * 0.08 &&
            lateLow > earlyLow + atr * 0.04;
        boolean bearishStructure = lateLow < earlyLow - atr * 0.08 &&
            lateHigh < earlyHigh - atr * 0.04;

        double lastRange = safeRange(last);
        double lastBody = Math.abs(last.close - last.open);
        double lastClosePosition = clamp(
            ((last.close - last.low) - (last.high - last.close)) / lastRange, -1, 1);
        double upperWick = last.high - Math.max(last.open, last.close);
        double lowerWick = Math.min(last.open, last.close) - last.low;

        // A sudden range expansion is a market-shock state, not an invitation
        // to chase the first candle. Wait for the next completed candle to settle.
        boolean volatilityShock = priorAtr > 1e-12 && lastRange > priorAtr * 2.35;
        if (volatilityShock)
            return new Scan("WAIT", 0,
                "WAIT · QX volatility shock / abnormal range expansion; wait for market to stabilize.");

        boolean chop = reversals >= Math.max(2, length - 4) &&
            Math.abs(displacement) < 0.22 &&
            Math.abs(histogram) < 0.30 && Math.abs(vector) < 0.30;
        if (chop)
            return new Scan("WAIT", 0,
                "WAIT · choppy/range regime; alternating candles and weak directional displacement.");

        // Continuations require a measurable counter-trend retracement inside
        // an established structure, followed by a fresh close through the
        // immediately preceding candle's extreme. A single opposite-colour
        // candle is no longer enough to label a pullback.
        double impulseLow = Double.POSITIVE_INFINITY, impulseHigh = Double.NEGATIVE_INFINITY;
        double pullbackLow = Double.POSITIVE_INFINITY, pullbackHigh = Double.NEGATIVE_INFINITY;
        boolean bullishCounterBar = false, bearishCounterBar = false;
        int pullbackStart = Math.max(1, length - 4);
        for (int i = 0; i < length - 1; i++) {
            Candle c = stream.get(i);
            if (i < pullbackStart) {
                impulseLow = Math.min(impulseLow, c.low);
                impulseHigh = Math.max(impulseHigh, c.high);
            } else {
                pullbackLow = Math.min(pullbackLow, c.low);
                pullbackHigh = Math.max(pullbackHigh, c.high);
                if (c.close < c.open || (i > 0 && c.low < stream.get(i - 1).low - atr * 0.08))
                    bullishCounterBar = true;
                if (c.close > c.open || (i > 0 && c.high > stream.get(i - 1).high + atr * 0.08))
                    bearishCounterBar = true;
            }
        }
        double upImpulse = Math.max(0, impulseHigh - impulseLow);
        double downImpulse = Math.max(0, impulseHigh - impulseLow);
        double upRetracement = Math.max(0, impulseHigh - pullbackLow);
        double downRetracement = Math.max(0, pullbackHigh - impulseLow);
        boolean validBullPullback = bullishStructure && bullishCounterBar &&
            upImpulse >= atr * 0.55 &&
            upRetracement >= Math.max(atr * 0.18, upImpulse * 0.12) &&
            upRetracement <= upImpulse * 0.72;
        boolean validBearPullback = bearishStructure && bearishCounterBar &&
            downImpulse >= atr * 0.55 &&
            downRetracement >= Math.max(atr * 0.18, downImpulse * 0.12) &&
            downRetracement <= downImpulse * 0.72;

        boolean bullishConfirmation = last.close > last.open &&
            last.close > previous.high && lastBody >= atr * 0.28 &&
            lastClosePosition >= 0.30;
        boolean bearishConfirmation = last.close < last.open &&
            last.close < previous.low && lastBody >= atr * 0.28 &&
            lastClosePosition <= -0.30;

        // A reversal must sweep beyond a prior extreme and close back through
        // that same level. Merely touching a prior high/low cannot trigger it.
        int priorBullBars = 0, priorBearBars = 0;
        for (int i = Math.max(0, length - 5); i < length - 1; i++) {
            Candle c = stream.get(i);
            if (c.close > c.open) priorBullBars++;
            else if (c.close < c.open) priorBearBars++;
        }
        boolean bearishSweep = last.high > priorHigh + atr * 0.04 &&
            last.close < priorHigh - atr * 0.02 &&
            upperWick >= Math.max(atr * 0.22, lastBody * 0.45) &&
            last.close < last.open && lastClosePosition <= -0.25 &&
            (bullishStructure || priorBullBars >= 2);
        boolean bullishSweep = last.low < priorLow - atr * 0.04 &&
            last.close > priorLow + atr * 0.02 &&
            lowerWick >= Math.max(atr * 0.22, lastBody * 0.45) &&
            last.close > last.open && lastClosePosition >= 0.25 &&
            (bearishStructure || priorBearBars >= 2);

        boolean bearishBreak = last.close < priorLow - atr * 0.08 &&
            last.close < last.open && lastBody >= atr * 0.48 &&
            lastClosePosition <= -0.42;
        boolean bullishBreak = last.close > priorHigh + atr * 0.08 &&
            last.close > last.open && lastBody >= atr * 0.48 &&
            lastClosePosition >= 0.42;

        double recentLow = Double.POSITIVE_INFINITY, recentHigh = Double.NEGATIVE_INFINITY;
        int greenRun = 0, redRun = 0, maxGreenRun = 0, maxRedRun = 0;
        for (int i = Math.max(0, length - 6); i < length; i++) {
            Candle c = stream.get(i);
            recentLow = Math.min(recentLow, c.low);
            recentHigh = Math.max(recentHigh, c.high);
            if (c.close > c.open) { greenRun++; redRun = 0; }
            else if (c.close < c.open) { redRun++; greenRun = 0; }
            else { greenRun = 0; redRun = 0; }
            maxGreenRun = Math.max(maxGreenRun, greenRun);
            maxRedRun = Math.max(maxRedRun, redRun);
        }
        boolean overextendedUp = last.close - recentLow > atr * 3.4 || maxGreenRun >= 4;
        boolean overextendedDown = recentHigh - last.close > atr * 3.4 || maxRedRun >= 4;

        int histDir = direction(histogram), vectorDir = direction(vector);
        int displacementDir = direction(displacement);
        int bullishVotes = (histDir > 0 ? 1 : 0) + (vectorDir > 0 ? 1 : 0) +
            (displacementDir > 0 ? 1 : 0);
        int bearishVotes = (histDir < 0 ? 1 : 0) + (vectorDir < 0 ? 1 : 0) +
            (displacementDir < 0 ? 1 : 0);
        boolean alignedUp = bullishVotes >= 2 && displacement > 0.18;
        boolean alignedDown = bearishVotes >= 2 && displacement < -0.18;

        boolean callContinuation = validBullPullback && bullishConfirmation &&
            alignedUp && !overextendedUp;
        boolean putContinuation = validBearPullback && bearishConfirmation &&
            alignedDown && !overextendedDown;
        boolean callReversal = (bullishSweep || bullishBreak) && !overextendedUp;
        boolean putReversal = (bearishSweep || bearishBreak) && !overextendedDown;

        if ((!callContinuation && !putContinuation && !callReversal && !putReversal) ||
            ((callContinuation || callReversal) && (putContinuation || putReversal))) {
            String why = "WAIT · no high-quality trend pullback or confirmed sweep/structure reversal";
            if (overextendedUp || overextendedDown) why += " · exhausted/extended move";
            else if (volatilityShock) why += " · volatility shock";
            else if (!alignedUp && !alignedDown && !bullishSweep && !bearishSweep &&
                    !bullishBreak && !bearishBreak) why += " · weak or conflicting market direction";
            else if (!(validBullPullback || validBearPullback)) why += " · valid retracement missing";
            else if (!(bullishConfirmation || bearishConfirmation)) why += " · close confirmation missing";
            return new Scan("WAIT", 0, why + " · bull " + bullishBars + " / bear " + bearishBars + ".");
        }

        String result = (callContinuation || callReversal) ? "CALL" : "PUT";
        int expected = "CALL".equals(result) ? 1 : -1;

        // Confirm the new entry candle with a minimum of three genuine QX
        // quotes. This is tick confirmation, not an increase in candle memory.
        int liveTicks = countBucketTicks(symbol, currentBucket);
        if (liveTicks < 3)
            return new Scan("WAIT", 0,
                "WAIT · setup detected; confirming with 3 genuine live QX quotes (" + liveTicks + "/3).");
        double liveFlow = bucketTickFlow(symbol, currentBucket);
        if (liveFlow * expected < -0.25 ||
            (livePrice - last.close) * expected < -atr * 0.22)
            return new Scan("WAIT", 0,
                "WAIT · live QX quote pressure is turning against the completed-candle setup.");

        boolean reversalSetup = callReversal || putReversal;
        double agreement = (clamp(histogram * expected, 0.0, 1.0) +
            clamp(vector * expected, 0.0, 1.0) +
            clamp(displacement * expected, 0.0, 1.0)) / 3.0;
        double bodyQuality = clamp(lastBody / Math.max(atr * 0.9, 1e-12), 0.0, 1.0);
        double closeQuality = "CALL".equals(result) ?
            clamp((lastClosePosition + 1.0) * 0.5, 0.0, 1.0) :
            clamp((1.0 - lastClosePosition) * 0.5, 0.0, 1.0);
        double patternQuality = reversalSetup ?
            clamp(Math.max(upperWick, lowerWick) / lastRange, 0.0, 1.0) : 1.0;
        double setupScore = 45.0 + agreement * 20.0 + bodyQuality * 12.0 +
            closeQuality * 8.0 + patternQuality * 8.0 +
            (reversalSetup ? 4.0 : (validBullPullback || validBearPullback ? 7.0 : 0.0));
        setupScore = clamp(setupScore, 45.0, 85.0);
        String setup = reversalSetup ? "LIQUIDITY SWEEP / STRUCTURE REVERSAL" :
            "TREND PULLBACK / CONTINUATION";
        return new Scan(result, setupScore,
            result + " · " + setup + " · setup score " + Math.round(setupScore) +
            "/100 (evidence score, not a measured win probability) · histogram " + signed(histogram) +
            " · vector " + signed(vector) + " · displacement " + signed(displacement) +
            " · live QX flow " + signed(liveFlow) + " · bull " + bullishBars + " / bear " + bearishBars);
    }

    private int countBucketTicks(String symbol, long bucket) {
        int count = 0;
        for (Tick tick : tape)
            if (tick.asset.equals(symbol) && tick.timestamp >= bucket) count++;
        return count;
    }

    private double bucketTickFlow(String symbol, long bucket) {
        List<Tick> recent = new ArrayList<>();
        for (int i = tape.size() - 1; i >= 0; i--) {
            Tick tick = tape.get(i);
            if (tick.asset.equals(symbol) && tick.timestamp >= bucket) recent.add(0, tick);
        }
        if (recent.size() < 2) return 0;
        int up = 0, down = 0;
        for (int i = 1; i < recent.size(); i++) {
            Tick current = recent.get(i);
            int d = current.brokerDirection != null
                ? current.brokerDirection.intValue()
                : direction(current.price - recent.get(i - 1).price);
            if (d > 0) up++; else if (d < 0) down++;
        }
        int total = up + down;
        return total == 0 ? 0 : clamp((double) (up - down) / total, -1, 1);
    }

    private double tickFlow(String symbol) {
        List<Tick> recent = new ArrayList<>();
        for (int i = tape.size() - 1; i >= 0 && recent.size() < 12; i--)
            if (tape.get(i).asset.equals(symbol)) recent.add(0, tape.get(i));
        if (recent.size() < 4) return 0;
        int up = 0, down = 0;
        for (int i = 1; i < recent.size(); i++) {
            Tick current = recent.get(i);
            int d = current.brokerDirection != null
                ? current.brokerDirection.intValue()
                : direction(current.price - recent.get(i - 1).price);
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
    public synchronized double getHistogram() { return lastHistogram; }
    public synchronized double getVector() { return lastVector; }
    public synchronized double getDisplacement() { return lastDisplacement; }
    private synchronized int countBrokerDirections(int wanted) {
        int count = 0, seen = 0;
        for (int i = tape.size() - 1; i >= 0 && seen < 12; i--) {
            Tick tick = tape.get(i);
            if (!tick.asset.equals(asset) || tick.brokerDirection == null) continue;
            seen++;
            if (tick.brokerDirection.intValue() == wanted) count++;
        }
        return count;
    }
    public synchronized int getDirectionalUpTicks() { return countBrokerDirections(1); }
    public synchronized int getDirectionalDownTicks() { return countBrokerDirections(-1); }
    public synchronized int getDirectionalNeutralTicks() { return countBrokerDirections(0); }
    public synchronized int getBrokerDirectionTickCount() {
        return getDirectionalUpTicks() + getDirectionalDownTicks() + getDirectionalNeutralTicks();
    }
    /** Normalized pressure bars derived from the last eight real QX OHLC candles. */
    public synchronized JSONArray getHistogramBars() {
        JSONArray bars = new JSONArray();
        List<Candle> recent = new ArrayList<>();
        Long forming = liveBucketByAsset.get(asset);
        for (int i = candles.size() - 1; i >= 0 && recent.size() < 8; i--) {
            Candle candle = candles.get(i);
            if (candle.asset.equals(asset) && (forming == null || candle.bucket != forming.longValue()))
                recent.add(0, candle);
        }
        for (Candle candle : recent) {
            double range = safeRange(candle);
            double body = clamp((candle.close - candle.open) / range, -1, 1);
            double closePosition = clamp(((candle.close - candle.low) -
                (candle.high - candle.close)) / range, -1, 1);
            try { bars.put(clamp(0.68 * body + 0.32 * closePosition, -1, 1)); }
            catch (JSONException ignored) { /* Skip an invalid telemetry bar. */ }
        }
        return bars;
    }
    /** Completed broker candles for the currently selected asset; excludes its live forming bucket. */
    public synchronized int getAssetCandleCount() {
        int count = 0;
        Long forming = liveBucketByAsset.get(asset);
        for (Candle candle : candles)
            if (candle.asset.equals(asset) && (forming == null || candle.bucket != forming.longValue())) count++;
        return count;
    }
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
        final String asset; final double price; final long timestamp; final Integer brokerDirection;
        Tick(String a, double p, long t, Integer d) {
            asset = a; price = p; timestamp = t;
            brokerDirection = d != null && d >= -1 && d <= 1 ? d : null;
        }
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
