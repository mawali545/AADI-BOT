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
            Scan scan = scan(symbol, bucket, price, timestamp);
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
    private Scan scan(String symbol, long currentBucket, double livePrice, long nowMillis) {
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
        boolean tickDerivedBars = false;
        if (recentMemory.size() < 3) {
            List<Candle> microBars = buildTickBars(symbol, nowMillis, 1000L);
            if (microBars.size() >= 3) {
                recentMemory.clear();
                recentMemory.addAll(microBars);
                count = recentMemory.size();
                tickDerivedBars = true;
            }
        }
        if (count < 3)
            return new Scan("WAIT", 0,
                "MEMORY STREAM · collecting 3 completed broker/tick-derived bars (" + count + "/3).");

        // Use a short adaptive memory: two completed broker candles are the minimum.
        // The forming candle is excluded from structure decisions; current real
        // QX ticks corroborate momentum, so the engine does not wait for 8-12 bars.
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

        // Regression slope across the completed candle closes, normalized by ATR.
        // This is price momentum from genuine QX OHLC, not a synthetic indicator.
        double meanX = (length - 1) * 0.5, meanY = 0.0, covariance = 0.0, varianceX = 0.0;
        for (int i = 0; i < length; i++) meanY += stream.get(i).close;
        meanY /= length;
        for (int i = 0; i < length; i++) {
            double dx = i - meanX;
            covariance += dx * (stream.get(i).close - meanY);
            varianceX += dx * dx;
        }
        double slopeMomentum = varianceX <= 0 ? 0 : clamp((covariance / varianceX) / Math.max(atr, 1e-12), -1, 1);
        int liveTickCount = recentTickCount(symbol);
        double liveTickMomentum = tickFlow(symbol);
        double momentum = liveTickCount >= 4
            ? clamp(0.65 * slopeMomentum + 0.35 * liveTickMomentum, -1, 1)
            : slopeMomentum;

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
        for (int i = 0; i < length - 1; i++) {
            Candle c = stream.get(i);
            if (c.close < c.open || (i > 0 &&
                c.low < stream.get(i - 1).close - atr * 0.22)) bullishPullback = true;
            if (c.close > c.open || (i > 0 &&
                c.high > stream.get(i - 1).close + atr * 0.22)) bearishPullback = true;
            if (c.close > c.open) { greenRun++; redRun = 0; }
            else if (c.close < c.open) { redRun++; greenRun = 0; }
            else { greenRun = 0; redRun = 0; }
            maxGreenRun = Math.max(maxGreenRun, greenRun);
            maxRedRun = Math.max(maxRedRun, redRun);
            highestBeforeLast = Math.max(highestBeforeLast, c.high);
            lowestBeforeLast = Math.min(lowestBeforeLast, c.low);
        }

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

        // Full binary-price-action context, computed only from the already available
        // completed OHLC window. This is bounded to 12 candles (O(12)); it adds no
        // sleep, network request, or extra candle wait to the live signal cycle.
        BinaryContext binary = analyzeBinaryContext(stream, atr);
        double[] awarenessOpen = new double[length], awarenessHigh = new double[length];
        double[] awarenessLow = new double[length], awarenessClose = new double[length];
        for (int i = 0; i < length; i++) {
            Candle c = stream.get(i);
            awarenessOpen[i] = c.open; awarenessHigh[i] = c.high;
            awarenessLow[i] = c.low; awarenessClose[i] = c.close;
        }
        QxMarketAwareness.Context awareness = QxMarketAwareness.analyze(
            awarenessOpen, awarenessHigh, awarenessLow, awarenessClose);

        // Broker-feed liquidity proxy: repeated OHLC wick tests create local
        // support/resistance zones. This is NOT traded volume or a full order book.
        int supportTouches = 0, resistanceTouches = 0;
        for (int i = 0; i < length - 1; i++) {
            Candle c = stream.get(i);
            if (Math.abs(c.low - lowestBeforeLast) <= atr * 0.22) supportTouches++;
            if (Math.abs(c.high - highestBeforeLast) <= atr * 0.22) resistanceTouches++;
        }
        boolean supportHold = last.low <= lowestBeforeLast + atr * 0.24 &&
            last.close > lowestBeforeLast && lowerWick >= atr * 0.08;
        boolean resistanceReject = last.high >= highestBeforeLast - atr * 0.24 &&
            last.close < highestBeforeLast && upperWick >= atr * 0.08;
        boolean bullishLiquiditySweep = last.low < lowestBeforeLast - atr * 0.025 &&
            last.close > lowestBeforeLast && lowerWick >= atr * 0.08;
        boolean bearishLiquiditySweep = last.high > highestBeforeLast + atr * 0.025 &&
            last.close < highestBeforeLast && upperWick >= atr * 0.08;

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
        if (momentum > 0.12) bullishVotes++;
        else if (momentum < -0.12) bearishVotes++;
        // Require two independent directional reads, not one isolated vote.
        // A decisive structure break remains a separate valid reversal trigger.
        boolean alignedUp = bullishVotes >= 2 || bullishBreak;
        boolean alignedDown = bearishVotes >= 2 || bearishBreak;

        // A fresh confirming candle plus ANY one meaningful context signal
        // (structure, pullback, or aligned OHLC pressure) can qualify.
        boolean callContinuation = bullishConfirmation &&
            (bullishStructure || bullishPullback || alignedUp || binary.bullishBreakout) &&
            !overextendedUp && !binary.blocksCallContinuation && !awareness.blocksCall;
        boolean putContinuation = bearishConfirmation &&
            (bearishStructure || bearishPullback || alignedDown || binary.bearishBreakout) &&
            !overextendedDown && !binary.blocksPutContinuation && !awareness.blocksPut;
        boolean callReversal = (bullishBreak && alignedUp && !overextendedUp) ||
            bullishRejection || bullishLiquiditySweep || binary.bullishFakeoutReclaim ||
            binary.bullishPinBar || (supportHold && supportTouches >= 1 &&
            lastClosePosition > 0.10 && momentum > -0.25);
        boolean putReversal = (bearishBreak && alignedDown && !overextendedDown) ||
            bearishRejection || bearishLiquiditySweep || binary.bearishFakeoutReject ||
            binary.bearishPinBar || (resistanceReject && resistanceTouches >= 1 &&
            lastClosePosition < -0.10 && momentum < 0.25);

        if ((callContinuation || callReversal) && (putContinuation || putReversal))
            return new Scan("WAIT", 0, "WAIT · CALL and PUT evidence conflict; no forced direction.");
        if (!callContinuation && !putContinuation && !callReversal && !putReversal) {
            String why = "WAIT · no confirmed pullback/continuation or structure-break reversal";
            if (overextendedUp || overextendedDown) why += " · move extended; do not chase";
            else if (binary.blocksCallContinuation || binary.blocksPutContinuation)
                why += " · opposing candle/level context blocks continuation";
            else if (!alignedUp && !alignedDown) why += " · slope/momentum/pressure disagree";
            else if (!(bullishConfirmation || bearishConfirmation)) why += " · candle confirmation missing";
            return new Scan("WAIT", 0, why + " · " + binary.summary +
                " · bull " + bullishBars + " / bear " + bearishBars + ".");
        }

        String result = (callContinuation || callReversal) ? "CALL" : "PUT";
        int expected = "CALL".equals(result) ? 1 : -1;
        boolean reversalEntry = callReversal || putReversal;
        if (!reversalEntry && awareness.bias * expected < -0.68)
            return new Scan("WAIT", 0, "WAIT · market-state bias strongly opposes " +
                result + " · " + awareness.summary);
        if (!reversalEntry && (("CALL".equals(result) && awareness.nearUpperRange) ||
                ("PUT".equals(result) && awareness.nearLowerRange)) &&
                !(binary.bullishBreakout || binary.bearishBreakout))
            return new Scan("WAIT", 0, "WAIT · entry would chase the wrong range extreme · " +
                awareness.summary);
        // The current quote is only a confirmation check, never a candle source.
        double liveAgainst = (livePrice - last.close) * expected;
        // Real quote tape is corroborating evidence, not a blanket veto.
        // Reject only when both price displacement and recent tick pressure
        // strongly oppose the broker-candle setup.
        double liveFlow = tickFlow(symbol);
        // Binary expiry is fixed at 10 seconds. Inspect only genuine quote events
        // from that same horizon; this adds computation, never a waiting delay.
        MicroContext micro = analyzeTenSecondTape(symbol, nowMillis, livePrice, atr);
        if (liveAgainst < -atr * 0.85 && liveFlow * expected <= -0.70)
            return new Scan("WAIT", 0,
                "WAIT · completed-candle setup and live QX tick-flow both oppose entry.");
        // If the last ten seconds show a strong opposing impulse AND a reversal
        // in tick acceleration, do not enter against the live microstructure.
        if (micro.samples >= 6 && micro.directionalPressure * expected < -0.72 &&
            micro.displacementAtr * expected < -0.10 &&
            micro.acceleration * expected < -0.18)
            return new Scan("WAIT", 0,
                "WAIT · 10-second quote impulse/acceleration opposes " + result +
                " · " + micro.summary);

        double expectedDirection = "CALL".equals(result) ? 1.0 : -1.0;
        TickContext tickContext = analyzeTickContext(symbol);
        // Weighted, normalized evidence model. Configured weights total 100%;
        // unavailable horizons are omitted and remaining weights renormalized.
        double weighted = 0.0, weightTotal = 0.0;
        if (tickContext.samples >= 12) { weighted += 0.15*tickContext.pressure12; weightTotal += 0.15; }
        if (tickContext.samples >= 36) { weighted += 0.11*tickContext.pressure36; weightTotal += 0.11; }
        if (tickContext.samples >= 90) { weighted += 0.07*tickContext.pressure90; weightTotal += 0.07; }
        if (tickContext.samples >= 300) { weighted += 0.04*tickContext.pressure300; weightTotal += 0.04; }
        if (tickContext.samples >= 900) { weighted += 0.03*tickContext.pressure900; weightTotal += 0.03; }
        if (tickContext.samples >= 24) { weighted += 0.04*tickContext.acceleration; weightTotal += 0.04; }
        weighted += 0.10*histogram + 0.08*vector + 0.08*displacement +
            0.08*slopeMomentum +
            0.08*(bullishStructure ? 1.0 : bearishStructure ? -1.0 : 0.0) +
            0.06*(binary.bullishPattern ? 1.0 : binary.bearishPattern ? -1.0 : 0.0);
        weightTotal += 0.48;
        if (micro.samples >= 2) { weighted += 0.05*micro.directionalPressure; weightTotal += 0.05; }
        if (length >= 4) { weighted += 0.03*awareness.bias; weightTotal += 0.03; }
        double evidence = weightTotal <= 0 ? 0.0 : clamp(weighted/weightTotal, -1.0, 1.0);
        double callEvidence = 50.0 + 35.0 * evidence;
        double setupScore = "CALL".equals(result) ? callEvidence : 100.0 - callEvidence;
        // A candidate setup is not enough: require a clear weighted edge in its
        // own direction. This is a directional evidence score, not a win-rate.
        if (setupScore < 62.0)
            return new Scan("WAIT", 0, "WAIT · weighted evidence below entry threshold · CALL " +
                Math.round(callEvidence) + "% / PUT " + Math.round(100.0-callEvidence) +
                "% · " + tickContext.summary);
        if (tickContext.pressure12 * expectedDirection < -0.65 &&
            tickContext.pressure36 * expectedDirection < -0.30)
            return new Scan("WAIT", 0, "WAIT · short and medium tick pressure oppose setup · " +
                tickContext.summary);
        String setup = (callReversal || putReversal) ?
            (rejectionSetup ? "LEVEL REJECTION / REVERSAL" :
                ((bullishLiquiditySweep || bearishLiquiditySweep || supportHold || resistanceReject)
                    ? "LIQUIDITY SWEEP / LEVEL REVERSAL" : "STRUCTURE BREAK / REVERSAL"))
            : "PULLBACK / CONTINUATION";
        return new Scan(result, setupScore,
            result + " · " + setup + " · directional evidence " + Math.round(setupScore) +
            "/100 (not a measured win probability) · CALL " + Math.round(callEvidence) +
            "% / PUT " + Math.round(100.0-callEvidence) + "% · " + tickContext.summary +
            " · histogram " + signed(histogram) +
            " · vector " + signed(vector) + " · slope " + signed(slopeMomentum) +
            " · live momentum " + signed(momentum) + " · S/R touches " + supportTouches + "/" +
            resistanceTouches + " · " + binary.summary +
            " · QX market awareness " + awareness.summary +
            " · trend efficiency " + signed(binary.trendEfficiency) +
            " · volatility " + signed(binary.volatilityRatio) +
            " · " + micro.summary +
            (tickDerivedBars ? " · 1s bars aggregated from genuine QX ticks" : " · broker OHLC bars") +
            " · bull " + bullishBars + " / bear " + bearishBars);
    }

    /**
     * Compact binary-trading context over completed candles:
     * candle anatomy, engulfing/pin/doji/inside patterns, swing levels,
     * breakout versus failed-break reclaim, trend efficiency and volatility.
     * It is deliberately deterministic and bounded to the current 12-bar stream.
     */
    private static BinaryContext analyzeBinaryContext(List<Candle> bars, double atr) {
        BinaryContext out = new BinaryContext();
        int n = bars.size();
        if (n < 3) return out;
        Candle last = bars.get(n - 1);
        Candle prev = bars.get(n - 2);
        double range = safeRange(last);
        double body = Math.abs(last.close - last.open);
        double upper = last.high - Math.max(last.open, last.close);
        double lower = Math.min(last.open, last.close) - last.low;
        double closePos = clamp(((last.close - last.low) -
            (last.high - last.close)) / range, -1.0, 1.0);
        boolean bullEngulf = last.close > last.open && prev.close < prev.open &&
            last.open <= prev.close && last.close >= prev.open &&
            body >= Math.abs(prev.close - prev.open) * 0.85;
        boolean bearEngulf = last.close < last.open && prev.close > prev.open &&
            last.open >= prev.close && last.close <= prev.open &&
            body >= Math.abs(prev.close - prev.open) * 0.85;
        out.bullishPinBar = lower >= Math.max(body * 2.0, atr * 0.12) &&
            closePos >= 0.30 && body / range <= 0.38;
        out.bearishPinBar = upper >= Math.max(body * 2.0, atr * 0.12) &&
            closePos <= -0.30 && body / range <= 0.38;
        boolean doji = body / range <= 0.10;
        boolean inside = last.high <= prev.high && last.low >= prev.low;
        out.pattern = bullEngulf ? "BULL ENGULF" : bearEngulf ? "BEAR ENGULF" :
            out.bullishPinBar ? "BULL PIN" : out.bearishPinBar ? "BEAR PIN" :
            doji ? "DOJI" : inside ? "INSIDE BAR" :
            last.close > last.open ? "BULL BODY" : last.close < last.open ? "BEAR BODY" : "NEUTRAL";
        out.bullishPattern = bullEngulf || out.bullishPinBar;
        out.bearishPattern = bearEngulf || out.bearishPinBar;

        // Confirmed local swing levels; ignore the current candle when deriving
        // the prior range so a breakout cannot move its own reference level.
        double support = Double.POSITIVE_INFINITY;
        double resistance = Double.NEGATIVE_INFINITY;
        int start = Math.max(1, n - 9);
        for (int i = start; i < n - 1; i++) {
            Candle c = bars.get(i);
            Candle left = bars.get(i - 1);
            Candle right = bars.get(Math.min(n - 1, i + 1));
            if (c.low <= left.low && c.low <= right.low) support = Math.min(support, c.low);
            if (c.high >= left.high && c.high >= right.high) resistance = Math.max(resistance, c.high);
        }
        if (!Double.isFinite(support)) {
            support = Double.POSITIVE_INFINITY;
            for (int i = Math.max(0, n - 7); i < n - 1; i++)
                support = Math.min(support, bars.get(i).low);
        }
        if (!Double.isFinite(resistance)) {
            resistance = Double.NEGATIVE_INFINITY;
            for (int i = Math.max(0, n - 7); i < n - 1; i++)
                resistance = Math.max(resistance, bars.get(i).high);
        }
        out.support = support;
        out.resistance = resistance;
        double tol = Math.max(atr * 0.10, Math.abs(last.close) * 1e-8);
        boolean nearResistance = resistance >= last.close && resistance - last.close <= atr * 0.18;
        boolean nearSupport = support <= last.close && last.close - support <= atr * 0.18;
        out.bullishBreakout = last.close > resistance + tol &&
            last.close > last.open && body >= atr * 0.25 && closePos >= 0.25;
        out.bearishBreakout = last.close < support - tol &&
            last.close < last.open && body >= atr * 0.25 && closePos <= -0.25;
        out.bearishFakeoutReject = last.high > resistance + tol &&
            last.close < resistance && upper >= Math.max(body * 0.65, atr * 0.10);
        out.bullishFakeoutReclaim = last.low < support - tol &&
            last.close > support && lower >= Math.max(body * 0.65, atr * 0.10);
        out.blocksCallContinuation = doji || (out.bearishPattern && !out.bullishBreakout) ||
            (nearResistance && !out.bullishBreakout && !out.bearishFakeoutReject);
        out.blocksPutContinuation = doji || (out.bullishPattern && !out.bearishBreakout) ||
            (nearSupport && !out.bearishBreakout && !out.bullishFakeoutReclaim);

        double path = 0.0;
        for (int i = 1; i < n; i++)
            path += Math.abs(bars.get(i).close - bars.get(i - 1).close);
        out.trendEfficiency = path <= 1e-12 ? 0.0 :
            clamp((last.close - bars.get(0).close) / path, -1.0, 1.0);
        double recentRange = 0.0, baseRange = 0.0;
        int recentCount = 0, baseCount = 0;
        for (int i = 0; i < n; i++) {
            double r = safeRange(bars.get(i));
            if (i >= n - 3) { recentRange += r; recentCount++; }
            else { baseRange += r; baseCount++; }
        }
        double recentAvg = recentCount == 0 ? 0 : recentRange / recentCount;
        double baseAvg = baseCount == 0 ? recentAvg : baseRange / baseCount;
        out.volatilityRatio = baseAvg <= 1e-12 ? 1.0 : recentAvg / baseAvg;
        out.confluenceBonus = 0.0;
        if ((out.bullishPattern && !out.blocksCallContinuation) ||
            (out.bearishPattern && !out.blocksPutContinuation)) out.confluenceBonus += 2.0;
        if (out.bullishBreakout || out.bearishBreakout ||
            out.bullishFakeoutReclaim || out.bearishFakeoutReject) out.confluenceBonus += 2.0;
        if (Math.abs(out.trendEfficiency) >= 0.45) out.confluenceBonus += 1.0;
        if (out.volatilityRatio >= 1.15 && out.volatilityRatio <= 2.8) out.confluenceBonus += 1.0;
        out.summary = out.pattern + " · support " + signed(support) +
            " · resistance " + signed(resistance) +
            (out.bullishBreakout ? " · BULL BREAKOUT" : "") +
            (out.bearishBreakout ? " · BEAR BREAKOUT" : "") +
            (out.bullishFakeoutReclaim ? " · BULL SWEEP RECLAIM" : "") +
            (out.bearishFakeoutReject ? " · BEAR SWEEP REJECT" : "");
        return out;
    }

    private static final class BinaryContext {
        String pattern = "N/A", summary = "binary context warming";
        double support = Double.NaN, resistance = Double.NaN;
        double trendEfficiency, volatilityRatio = 1.0, confluenceBonus;
        boolean bullishPattern, bearishPattern, bullishPinBar, bearishPinBar;
        boolean bullishBreakout, bearishBreakout, bullishFakeoutReclaim, bearishFakeoutReject;
        boolean blocksCallContinuation, blocksPutContinuation;
    }

    /**
     * Ten-second binary-expiry tape context. Uses only genuine QX quote events:
     * directional pressure, first/second-half acceleration, displacement and
     * path efficiency. No volume, spread, order-book depth or synthetic ticks
     * are fabricated when the broker feed does not provide them.
     */
    private MicroContext analyzeTenSecondTape(String symbol, long nowMillis,
            double livePrice, double atr) {
        List<Tick> window = new ArrayList<>();
        for (int i = tape.size() - 1; i >= 0; i--) {
            Tick tick = tape.get(i);
            if (!tick.asset.equals(symbol)) continue;
            if (nowMillis - tick.timestamp > 10_000L) break;
            if (tick.timestamp <= nowMillis) window.add(0, tick);
        }
        MicroContext out = new MicroContext();
        out.samples = window.size();
        if (window.size() < 2) {
            out.summary = "10s tape warming " + window.size() + " quotes";
            return out;
        }
        int up = 0, down = 0;
        double path = 0.0;
        int split = Math.max(1, window.size() / 2);
        int firstHalfUp = 0, firstHalfDown = 0, secondHalfUp = 0, secondHalfDown = 0;
        for (int i = 1; i < window.size(); i++) {
            Tick a = window.get(i - 1), b = window.get(i);
            int d = b.brokerDirection != null ? b.brokerDirection.intValue()
                : direction(b.price - a.price);
            path += Math.abs(b.price - a.price);
            if (d > 0) { up++; if (i < split) firstHalfUp++; else secondHalfUp++; }
            else if (d < 0) { down++; if (i < split) firstHalfDown++; else secondHalfDown++; }
        }
        int directional = up + down;
        out.directionalPressure = directional == 0 ? 0.0 :
            clamp((double) (up - down) / directional, -1.0, 1.0);
        Tick first = window.get(0);
        Tick last = window.get(window.size() - 1);
        double scale = Math.max(atr, Math.max(Math.abs(livePrice) * 1e-10, 1e-12));
        out.displacementAtr = (last.price - first.price) / scale;
        double firstPressure = firstHalfUp + firstHalfDown == 0 ? 0.0 :
            (double) (firstHalfUp - firstHalfDown) / (firstHalfUp + firstHalfDown);
        double secondPressure = secondHalfUp + secondHalfDown == 0 ? 0.0 :
            (double) (secondHalfUp - secondHalfDown) / (secondHalfUp + secondHalfDown);
        out.acceleration = secondPressure - firstPressure;
        out.pathEfficiency = path <= 1e-12 ? 0.0 :
            clamp((last.price - first.price) / path, -1.0, 1.0);
        out.summary = "10s tape n=" + out.samples +
            " · pressure " + signed(out.directionalPressure) +
            " · impulse/ATR " + signed(out.displacementAtr) +
            " · acceleration " + signed(out.acceleration) +
            " · efficiency " + signed(out.pathEfficiency);
        return out;
    }

    private static final class MicroContext {
        int samples;
        double directionalPressure, displacementAtr, acceleration, pathEfficiency;
        String summary = "10s tape unavailable";
    }

    /** Build closed one-second OHLC bars from genuine QX quotes; no price is interpolated. */
    private List<Candle> buildTickBars(String symbol, long nowMillis, long bucketMillis) {
        Map<Long, Candle> grouped = new java.util.TreeMap<>();
        for (Tick tick : tape) {
            if (!tick.asset.equals(symbol)) continue;
            long bucket = tick.timestamp / bucketMillis * bucketMillis;
            if (bucket + bucketMillis > nowMillis) continue;
            Candle bar = grouped.get(bucket);
            if (bar == null) grouped.put(bucket, new Candle(symbol, bucket, tick.price));
            else bar.update(tick.price);
        }
        List<Candle> result = new ArrayList<>(grouped.values());
        if (result.size() > 12) return new ArrayList<>(result.subList(result.size()-12, result.size()));
        return result;
    }

    /** Multi-horizon quote pressure over the complete retained 900-tick tape. */
    private TickContext analyzeTickContext(String symbol) {
        List<Tick> values = new ArrayList<>();
        for (Tick tick : tape) if (tick.asset.equals(symbol)) values.add(tick);
        TickContext out = new TickContext();
        int n = values.size();
        out.samples = n;
        out.pressure12 = pressure(values, Math.max(0,n-12));
        out.pressure36 = pressure(values, Math.max(0,n-36));
        out.pressure90 = pressure(values, Math.max(0,n-90));
        out.pressure300 = pressure(values, Math.max(0,n-300));
        out.pressure900 = pressure(values, Math.max(0,n-900));
        int split = Math.max(2, n/2);
        out.acceleration = pressure(values, split, n) - pressure(values, 0, split);
        out.summary = "tick pressure 12/36/90/300/900=" +
            signed(out.pressure12) + "/" + signed(out.pressure36) + "/" +
            signed(out.pressure90) + "/" + signed(out.pressure300) + "/" +
            signed(out.pressure900) + " · acceleration " + signed(out.acceleration) +
            " · tick weights 15/11/7/4/3% + acceleration 4%" +
            " · OHLC weights histogram/vector/displacement/slope=10/8/8/8%" +
            " · structure/pattern/micro/regime=8/6/5/3%" + " · n=" + n;
        return out;
    }

    private static double pressure(List<Tick> values, int start) {
        return pressure(values, start, values.size());
    }
    private static double pressure(List<Tick> values, int start, int end) {
        int up=0, down=0;
        for (int i=Math.max(1,start); i<Math.min(end,values.size()); i++) {
            Tick current=values.get(i), previous=values.get(i-1);
            int d=current.brokerDirection != null ? current.brokerDirection.intValue()
                : direction(current.price-previous.price);
            if (d>0) up++; else if (d<0) down++;
        }
        int total=up+down;
        return total==0?0.0:clamp((double)(up-down)/total,-1.0,1.0);
    }

    private static final class TickContext {
        int samples;
        double pressure12, pressure36, pressure90, pressure300, pressure900, acceleration;
        String summary = "tick context warming";
    }

    private int recentTickCount(String symbol) {
        int count = 0;
        for (int i = tape.size() - 1; i >= 0 && count < 12; i--)
            if (tape.get(i).asset.equals(symbol)) count++;
        return count;
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
        if (recent.isEmpty()) recent.addAll(buildTickBars(asset, lastTimestamp, 1000L));
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
        return Math.max(count, buildTickBars(asset, lastTimestamp, 1000L).size());
    }
    public synchronized int getCandleCount() { return candles.size(); }
    public synchronized long getLastTimestamp() { return lastTimestamp; }
    public synchronized double getLastPrice() { return lastPrice; }
    private Candle displayCandle() {
        Candle c = lastCandle(asset);
        if (c != null) return c;
        List<Candle> bars = buildTickBars(asset, lastTimestamp, 1000L);
        return bars.isEmpty() ? null : bars.get(bars.size()-1);
    }
    public synchronized double getOpen() { Candle c = displayCandle(); return c == null ? Double.NaN : c.open; }
    public synchronized double getHigh() { Candle c = displayCandle(); return c == null ? Double.NaN : c.high; }
    public synchronized double getLow() { Candle c = displayCandle(); return c == null ? Double.NaN : c.low; }
    public synchronized double getClose() { Candle c = displayCandle(); return c == null ? Double.NaN : c.close; }

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
