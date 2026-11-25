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
 * and validated live Market-QX quote events. Fast opposing quote pressure is
 * checked at entry time without waiting for another chart candle. No synthetic
 * candles or legacy EMA/RSI/MACD indicators are used.
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

        // Signal cadence is independent of the chart timeframe. QX may display
        // 60s candles while this tracker settles trades after 10s; using the
        // chart bucket here accidentally limited the engine to one entry/minute.
        // Re-evaluate the latest closed-candle context once per 10s decision slot.
        long decisionSlot = timestamp / 10_000L;
        Long issued = issuedBucketByAsset.get(symbol);
        if (issued == null || issued.longValue() != decisionSlot) {
            Scan scan = scan(symbol, bucket, price);
            signal = scan.signal;
            signalProbability = scan.probability;
            reason = scan.reason;
            if (!"WAIT".equals(scan.signal)) {
                signalCount++;
                pendingResults.add(new Pending(symbol, scan.signal, price, timestamp + 10_000L));
                issuedBucketByAsset.put(symbol, decisionSlot);
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
    private Scan scan(String symbol, long currentBucket, double livePrice) {
        // Entry decisions are based ONLY on closed broker candles.
        // The live/forming candle is deliberately excluded: its wick and body
        // can reverse before close, creating transient false CALL/PUT entries.
        // Walk backward and retain the latest 12 completed candles only.
        List<Candle> recentMemory = new ArrayList<>();
        int count = 0;
        for (int i = candles.size() - 1; i >= 0; i--) {
            Candle c = candles.get(i);
            if (!c.asset.equals(symbol) || c.bucket >= currentBucket) continue;
            count++;
            recentMemory.add(0, c);
            if (recentMemory.size() >= 12) break;
        }
        if (count < 3)
            return new Scan("WAIT", 0,
                "MEMORY STREAM · collecting 3 completed genuine QX candles (" + count + "/3).");

        // Use a short adaptive memory: three completed broker candles are enough
        // to form a live directional read; additional candles improve context.
        // Never block a valid live setup solely because eight candles are absent.
        int completedWindow = Math.min(12, recentMemory.size());
        List<Candle> stream = new ArrayList<>(
            recentMemory.subList(recentMemory.size() - completedWindow, recentMemory.size()));
        int length = stream.size();
        Candle last = stream.get(length - 1);
        Candle previous = stream.get(length - 2);

        double atr = 0.0, histogram = 0.0, vectorBody = 0.0, vectorClose = 0.0;
        double totalWeight = 0.0, totalPath = 0.0;
        int bullishBars = 0, bearishBars = 0, reversals = 0, priorDirection = 0;
        for (int i = 0; i < length; i++) {
            Candle c = stream.get(i);
            double range = safeRange(c);
            double body = clamp((c.close - c.open) / range, -1.0, 1.0);
            double closePosition = clamp(((c.close - c.low) - (c.high - c.close)) / range, -1.0, 1.0);
            double weight = 1.0 + (double) i / Math.max(1, length - 1);
            histogram += (0.68 * body + 0.32 * closePosition) * weight;
            vectorBody += body * weight;
            vectorClose += closePosition * weight;
            totalWeight += weight;
            atr += range;
            int d = direction(c.close - c.open);
            if (d > 0) bullishBars++;
            else if (d < 0) bearishBars++;
            if (d != 0 && priorDirection != 0 && d != priorDirection) reversals++;
            if (d != 0) priorDirection = d;
            if (i > 0) totalPath += Math.abs(c.close - stream.get(i - 1).close);
        }
        atr /= length;
        histogram = clamp(histogram / totalWeight, -1, 1);
        double vector = clamp(0.62 * (vectorBody / totalWeight) +
            0.38 * (vectorClose / totalWeight), -1, 1);
        double displacement = totalPath <= 1e-12 ? 0 :
            clamp((last.close - stream.get(0).close) / totalPath, -1, 1);
        lastHistogram = histogram;
        lastVector = vector;
        lastDisplacement = displacement;

        // Compare the earlier and later halves for higher-high/higher-low
        // or lower-high/lower-low structure rather than following candle color.
        int half = length / 2;
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
            lateLow > earlyLow - atr * 0.12;
        boolean bearishStructure = lateLow < earlyLow - atr * 0.08 &&
            lateHigh < earlyHigh + atr * 0.12;

        // Detect a pullback before continuation; a string of green/red candles
        // by itself cannot qualify as an entry.
        boolean bullishPullback = false, bearishPullback = false;
        int greenRun = 0, redRun = 0, maxGreenRun = 0, maxRedRun = 0;
        double highestBeforeLast = Double.NEGATIVE_INFINITY;
        double lowestBeforeLast = Double.POSITIVE_INFINITY;
        // Pullback evidence must be recent (within the four candles immediately
        // preceding the confirmation candle). An unrelated red/green candle
        // many bars ago must not qualify a fresh continuation.
        int pullbackStart = Math.max(1, length - 5);
        double recentPullbackHigh = Double.NEGATIVE_INFINITY;
        double recentPullbackLow = Double.POSITIVE_INFINITY;
        for (int i = 0; i < length - 1; i++) {
            Candle c = stream.get(i);
            if (i >= pullbackStart) {
                recentPullbackHigh = Math.max(recentPullbackHigh, c.high);
                recentPullbackLow = Math.min(recentPullbackLow, c.low);
                if (c.close < c.open || (i > 0 &&
                    c.low < stream.get(i - 1).low - atr * 0.08)) bullishPullback = true;
                if (c.close > c.open || (i > 0 &&
                    c.high > stream.get(i - 1).high + atr * 0.08)) bearishPullback = true;
            }
            if (c.close > c.open) { greenRun++; redRun = 0; }
            else if (c.close < c.open) { redRun++; greenRun = 0; }
            else { greenRun = 0; redRun = 0; }
            maxGreenRun = Math.max(maxGreenRun, greenRun);
            maxRedRun = Math.max(maxRedRun, redRun);
            highestBeforeLast = Math.max(highestBeforeLast, c.high);
            lowestBeforeLast = Math.min(lowestBeforeLast, c.low);
        }
        // Measure retracement against the impulse boundary BEFORE the pullback.
        // Comparing a subset with the min/max of the whole prior window was
        // mathematically ineffective (the subset is always inside that range).
        // This rejects both negligible noise and deep breaks of the impulse.
        double impulseHigh = Double.NEGATIVE_INFINITY;
        double impulseLow = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pullbackStart; i++) {
            impulseHigh = Math.max(impulseHigh, stream.get(i).high);
            impulseLow = Math.min(impulseLow, stream.get(i).low);
        }
        double bullishRetracement = impulseHigh - recentPullbackLow;
        double bearishRetracement = recentPullbackHigh - impulseLow;
        bullishPullback = bullishPullback && Double.isFinite(recentPullbackLow) &&
            Double.isFinite(impulseHigh) && Double.isFinite(impulseLow) &&
            bullishRetracement >= atr * 0.12 && bullishRetracement <= atr * 1.60 &&
            recentPullbackLow >= impulseLow - atr * 0.15;
        bearishPullback = bearishPullback && Double.isFinite(recentPullbackHigh) &&
            Double.isFinite(impulseHigh) && Double.isFinite(impulseLow) &&
            bearishRetracement >= atr * 0.12 && bearishRetracement <= atr * 1.60 &&
            recentPullbackHigh <= impulseHigh + atr * 0.15;

        double lastRange = safeRange(last);
        double lastBody = Math.abs(last.close - last.open);
        double lastClosePosition = clamp(
            ((last.close - last.low) - (last.high - last.close)) / lastRange, -1, 1);
        boolean bullishConfirmation = last.close > last.open &&
            last.close > previous.close && lastBody >= atr * 0.28 &&
            lastClosePosition >= 0.28;
        boolean bearishConfirmation = last.close < last.open &&
            last.close < previous.close && lastBody >= atr * 0.28 &&
            lastClosePosition <= -0.28;

        // A confirmed structure break can qualify as a reversal even while the
        // older trend points the other way. It must close beyond prior structure.
        boolean bearishBreak = last.close < lowestBeforeLast - atr * 0.04 &&
            last.close < last.open && lastBody >= atr * 0.42 &&
            lastClosePosition <= -0.38;
        boolean bullishBreak = last.close > highestBeforeLast + atr * 0.04 &&
            last.close > last.open && lastBody >= atr * 0.42 &&
            lastClosePosition >= 0.38;

        // Video chart example: price rebounds into a prior high, rejects it,
        // and turns red. Treat this as a reversal only when the preceding
        // directional move is still visible in the candle-memory vector.
        double upperWick = last.high - Math.max(last.open, last.close);
        double lowerWick = Math.min(last.open, last.close) - last.low;
        int priorBullishBars = 0, priorBearishBars = 0;
        for (int i = 0; i < length - 1; i++) {
            Candle c = stream.get(i);
            if (c.close > c.open) priorBullishBars++;
            else if (c.close < c.open) priorBearishBars++;
        }
        // A wick alone is not a reversal. Require visible prior pressure in the
        // opposite direction and a rejection close beyond the previous close.
        boolean priorBullishPressure = previous.close > stream.get(0).close + atr * 0.20 ||
            priorBullishBars >= Math.max(2, (length - 1 + 1) / 2);
        boolean priorBearishPressure = previous.close < stream.get(0).close - atr * 0.20 ||
            priorBearishBars >= Math.max(2, (length - 1 + 1) / 2);
        boolean bearishRejection = priorBullishPressure &&
            last.high >= highestBeforeLast - atr * 0.18 &&
            upperWick >= Math.max(lastBody * 0.55, atr * 0.10) &&
            lastClosePosition <= 0.22 && last.close < last.open &&
            last.close < previous.close;
        boolean bullishRejection = priorBearishPressure &&
            last.low <= lowestBeforeLast + atr * 0.18 &&
            lowerWick >= Math.max(lastBody * 0.55, atr * 0.10) &&
            lastClosePosition >= -0.22 && last.close > last.open &&
            last.close > previous.close;

        double recentLow = Double.POSITIVE_INFINITY;
        double recentHigh = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, length - 6); i < length; i++) {
            recentLow = Math.min(recentLow, stream.get(i).low);
            recentHigh = Math.max(recentHigh, stream.get(i).high);
        }
        boolean overextendedUp = last.close - recentLow > atr * 3.6 || maxGreenRun >= 4;
        boolean overextendedDown = recentHigh - last.close > atr * 3.6 || maxRedRun >= 4;

        boolean chop = reversals >= length - 4 &&
            Math.abs(displacement) < 0.24 &&
            Math.abs(histogram) < 0.28 && Math.abs(vector) < 0.28;
        if (chop)
            return new Scan("WAIT", 0,
                "WAIT · alternating candles / weak directional structure; no clean setup.");

        int histDir = direction(histogram);
        int vectorDir = direction(vector);
        int displacementDir = direction(displacement);
        // Two-of-three OHLC evidence is sufficient; do not require every
        // derived measure to agree before accepting genuine broker candles.
        int bullishVotes = (histDir > 0 ? 1 : 0) +
            (vectorDir > 0 ? 1 : 0) + (displacementDir > 0 ? 1 : 0);
        int bearishVotes = (histDir < 0 ? 1 : 0) +
            (vectorDir < 0 ? 1 : 0) + (displacementDir < 0 ? 1 : 0);
        // Require two independent directional reads, not one isolated vote.
        // A decisive structure break remains a separate valid reversal trigger.
        boolean alignedUp = bullishVotes >= 2 || bullishBreak;
        boolean alignedDown = bearishVotes >= 2 || bearishBreak;

        // A fresh confirming candle plus ANY one meaningful context signal
        // (structure, pullback, or aligned OHLC pressure) can qualify.
        boolean callContinuation = bullishConfirmation && bullishPullback &&
            (bullishStructure || alignedUp) && !overextendedUp;
        boolean putContinuation = bearishConfirmation && bearishPullback &&
            (bearishStructure || alignedDown) && !overextendedDown;

        // A continuation must not fight a clear majority of the completed-candle
        // evidence. This is an immediate decision guard, not an extra candle/timer:
        // it preserves the existing first-live-tick scan timing and 10s settlement.
        // Do not use the numeric setup score as a probability or a hard threshold.
        if (bearishVotes >= 2) callContinuation = false;
        if (bullishVotes >= 2) putContinuation = false;

        // Reversal patterns remain separately eligible: a confirmed level rejection
        // or decisive structure break is allowed to turn against the prior trend.
        boolean callReversal = (bullishBreak && alignedUp && !overextendedUp) ||
            bullishRejection;
        boolean putReversal = (bearishBreak && alignedDown && !overextendedDown) ||
            bearishRejection;

        if (!callContinuation && !putContinuation && !callReversal && !putReversal) {
            String why = "WAIT · no confirmed pullback/continuation or structure-break reversal";
            if (overextendedUp || overextendedDown) why += " · move extended; do not chase";
            else if (!alignedUp && !alignedDown) why += " · histogram/vector/displacement disagree";
            else if (!(bullishConfirmation || bearishConfirmation)) why += " · candle confirmation missing";
            return new Scan("WAIT", 0, why + " · bull " + bullishBars + " / bear " + bearishBars + ".");
        }

        // If both directions qualify (usually a noisy/ambiguous structure),
        // do not silently prefer CALL. Resolve the conflict on this same scan.
        boolean callEligible = callContinuation || callReversal;
        boolean putEligible = putContinuation || putReversal;
        if (callEligible && putEligible)
            return new Scan("WAIT", 0,
                "WAIT · conflicting bullish and bearish setups on the same completed-candle window.");
        String result = callEligible ? "CALL" : "PUT";
        int expected = "CALL".equals(result) ? 1 : -1;
        // The current quote is only a confirmation check, never a candle source.
        double liveAgainst = (livePrice - last.close) * expected;
        // Real quote tape is corroborating evidence, not a blanket veto.
        // Reject only when both price displacement and recent tick pressure
        // strongly oppose the broker-candle setup.
        double liveFlow = tickFlow(symbol);
        // Fast anti-fake-entry check: do not wait for another chart candle.
        // When the latest genuine QX quote tape is already moving against the
        // proposed side, suppress the entry immediately instead of following
        // a stale completed-candle pattern into a 10s expiry.
        if (liveAgainst < -atr * 0.08 && liveFlow * expected <= -0.25)
            return new Scan("WAIT", 0,
                "WAIT · recent genuine QX tick pressure opposes this entry; rescan on next 10s slot.");

        double structureScore = ("CALL".equals(result) ?
            (bullishStructure ? 1.0 : 0.75) : (bearishStructure ? 1.0 : 0.75));
        double expectedDirection = "CALL".equals(result) ? 1.0 : -1.0;
        double agreement = (clamp(histogram * expectedDirection, 0.0, 1.0) +
            clamp(vector * expectedDirection, 0.0, 1.0) +
            clamp(displacement * expectedDirection, 0.0, 1.0)) / 3.0;
        double bodyQuality = clamp(lastBody / Math.max(atr * 0.9, 1e-12), 0.0, 1.0);
        double closeQuality = "CALL".equals(result) ?
            clamp((lastClosePosition + 1.0) * 0.5, 0.0, 1.0) :
            clamp((1.0 - lastClosePosition) * 0.5, 0.0, 1.0);
        boolean rejectionSetup = bullishRejection || bearishRejection;
        double patternQuality = rejectionSetup ?
            clamp(Math.max(upperWick, lowerWick) / lastRange, 0.0, 1.0) : 1.0;
        double setupScore;
        if (rejectionSetup) {
            setupScore = 48.0 + patternQuality * 14.0 + bodyQuality * 10.0 +
                closeQuality * 8.0 + Math.min(1.0, Math.abs(displacement)) * 8.0;
        } else {
            setupScore = 45.0 + agreement * 22.0 + structureScore * 8.0 +
                bodyQuality * 10.0 + closeQuality * 8.0 + patternQuality * 7.0;
        }
        // Weak directional agreement must not be inflated by structure/body bonuses.
        // Keep the signal available, but cap its evidence score when continuation/break
        // evidence is weak; confirmed level-rejection reversals use their own candle score.
        if (!rejectionSetup && agreement < 0.40)
            setupScore = Math.min(setupScore, 55.0 + agreement * 25.0);
        setupScore = clamp(setupScore, 45.0, 85.0);
        String setup = (callReversal || putReversal) ?
            (rejectionSetup ? "LEVEL REJECTION / REVERSAL" :
                "STRUCTURE BREAK / REVERSAL") : "PULLBACK / CONTINUATION";
        return new Scan(result, setupScore,
            result + " · " + setup + " · setup score " + Math.round(setupScore) +
            "/100 (evidence score, not a measured win probability) · histogram " + signed(histogram) +
            " · vector " + signed(vector) + " · displacement " +
            signed(displacement) + " · bull " + bullishBars + " / bear " + bearishBars);
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
