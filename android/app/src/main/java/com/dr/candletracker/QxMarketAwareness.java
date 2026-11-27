package com.dr.candletracker;

/**
 * Expert market-state overlay using only fields confirmed in the QX feed:
 * completed OHLC candles. It estimates regime, trend quality, range location,
 * wick rejection, volatility expansion/compression and directional conflict.
 * It does not claim access to volume, spread, order-book depth or broker payout.
 */
final class QxMarketAwareness {
    private QxMarketAwareness() {}

    static Context analyze(double[] open, double[] high, double[] low, double[] close) {
        Context out = new Context();
        int n = Math.min(Math.min(open.length, high.length),
                Math.min(Math.min(low.length, close.length), 12));
        if (n < 4) return out;
        int start = Math.max(0, open.length - n);
        double atr = 0, path = 0, upperWick = 0, lowerWick = 0;
        double recentRange = 0, baseRange = 0;
        int recentCount = 0, baseCount = 0, overlaps = 0;
        int bullBars = 0, bearBars = 0;
        double firstClose = close[start], lastClose = close[start + n - 1];
        double highest = Double.NEGATIVE_INFINITY, lowest = Double.POSITIVE_INFINITY;
        double earlyHigh = Double.NEGATIVE_INFINITY, earlyLow = Double.POSITIVE_INFINITY;
        double lateHigh = Double.NEGATIVE_INFINITY, lateLow = Double.POSITIVE_INFINITY;
        double bodyFirst = 0, bodySecond = 0;
        for (int i = 0; i < n; i++) {
            int k = start + i;
            double range = Math.max(high[k] - low[k], Math.max(Math.abs(close[k]) * 1e-10, 1e-12));
            double body = close[k] - open[k];
            atr += range;
            highest = Math.max(highest, high[k]);
            lowest = Math.min(lowest, low[k]);
            upperWick += Math.max(0, high[k] - Math.max(open[k], close[k])) / range;
            lowerWick += Math.max(0, Math.min(open[k], close[k]) - low[k]) / range;
            if (body > 0) bullBars++; else if (body < 0) bearBars++;
            if (i < n / 2) {
                earlyHigh = Math.max(earlyHigh, high[k]);
                earlyLow = Math.min(earlyLow, low[k]);
                bodyFirst += Math.abs(body) / range;
            } else {
                lateHigh = Math.max(lateHigh, high[k]);
                lateLow = Math.min(lateLow, low[k]);
                bodySecond += Math.abs(body) / range;
            }
            if (i > 0) {
                int p = k - 1;
                path += Math.abs(close[k] - close[p]);
                if (high[k] <= high[p] && low[k] >= low[p]) overlaps++;
            }
            if (i >= n - 3) { recentRange += range; recentCount++; }
            else { baseRange += range; baseCount++; }
        }
        atr /= n;
        out.efficiency = path <= 1e-12 ? 0 : clamp((lastClose - firstClose) / path, -1, 1);
        out.volatilityRatio = baseCount == 0 || baseRange <= 1e-12
                ? 1 : (recentRange / recentCount) / (baseRange / baseCount);
        out.rangePosition = highest <= lowest ? 0.5 : clamp((lastClose - lowest) / (highest - lowest), 0, 1);
        double structure = 0;
        if (lateHigh > earlyHigh + atr * 0.06 && lateLow > earlyLow - atr * 0.14) structure = 1;
        else if (lateLow < earlyLow - atr * 0.06 && lateHigh < earlyHigh + atr * 0.14) structure = -1;
        double slope = atr <= 1e-12 ? 0 : clamp((lastClose - firstClose) / (atr * Math.max(1, n - 1)), -1, 1);
        double wickBias = (lowerWick - upperWick) / n;
        double impulseChange = bodySecond - bodyFirst;
        double overlapRatio = (double) overlaps / Math.max(1, n - 1);

        // Compare independent calculated conditions as logical votes instead
        // of assigning percentages or blending factors with hand-picked weights.
        int bullishVotes = (structure > 0 ? 1 : 0) + (slope > 0 ? 1 : 0) +
                (out.efficiency > 0 ? 1 : 0) + (wickBias > 0 ? 1 : 0);
        int bearishVotes = (structure < 0 ? 1 : 0) + (slope < 0 ? 1 : 0) +
                (out.efficiency < 0 ? 1 : 0) + (wickBias < 0 ? 1 : 0);
        out.bias = bullishVotes > bearishVotes ? 1.0 :
                bearishVotes > bullishVotes ? -1.0 : 0.0;
        boolean directional = Math.abs(out.efficiency) >= 0.38 &&
                Math.abs(slope) >= 0.10 && structure != 0;
        boolean compressed = out.volatilityRatio < 0.68;
        boolean expanded = out.volatilityRatio > 1.55;
        out.choppy = Math.abs(out.efficiency) < 0.20 &&
                Math.abs(out.bias) < 0.24 && overlapRatio >= 0.30;
        if (directional) out.regime = "DIRECTIONAL TREND";
        else if (out.choppy) out.regime = "ROTATIONAL / CHOP";
        else if (compressed) out.regime = "VOLATILITY COMPRESSION";
        else if (expanded) out.regime = "VOLATILITY EXPANSION";
        else out.regime = "TRANSITION / MIXED";

        out.momentumFading = Math.abs(bodyFirst) > 0.01 &&
                Math.abs(bodySecond) < Math.abs(bodyFirst) &&
                Math.abs(out.efficiency) > 0.25;
        out.nearUpperRange = out.rangePosition > 0.84;
        out.nearLowerRange = out.rangePosition < 0.16;
        out.blocksCall = out.choppy && !out.nearLowerRange;
        out.blocksPut = out.choppy && !out.nearUpperRange;
        out.summary = out.regime + " · bias " + signed(out.bias) +
                " · efficiency " + signed(out.efficiency) +
                " · range-pos " + signed(out.rangePosition) +
                " · volatility x" + signed(out.volatilityRatio) +
                (out.momentumFading ? " · impulse fading" : "") +
                (out.nearUpperRange ? " · upper-range area" : "") +
                (out.nearLowerRange ? " · lower-range area" : "");
        return out;
    }

    static final class Context {
        String regime = "WARMING";
        String summary = "market awareness warming";
        double bias, efficiency, volatilityRatio = 1, rangePosition = 0.5;
        boolean choppy, momentumFading, nearUpperRange, nearLowerRange;
        boolean blocksCall, blocksPut;
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String signed(double v) { return (v >= 0 ? "+" : "") + String.format(java.util.Locale.US, "%.2f", v); }
}
