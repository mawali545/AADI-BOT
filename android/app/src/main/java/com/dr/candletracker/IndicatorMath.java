package com.dr.candletracker;

import java.util.List;

/**
 * Deterministic short-horizon indicators calculated from completed genuine OHLC bars.
 * No volume-derived indicator is fabricated because the QX feed does not expose
 * verified traded volume. EMA uses the standard recursive formula; RSI uses
 * Wilder smoothing; ATR uses true range.
 */
final class IndicatorMath {
    private IndicatorMath() {}

    static Result calculate(List<SignalEngine.Candle> bars) {
        Result r = new Result();
        if (bars == null || bars.size() < 9) return r;
        int n = bars.size();
        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = bars.get(i).close;
        r.ema5 = ema(close, 5);
        r.ema9 = ema(close, 9);
        r.ema5Previous = emaPrevious(close, 5);
        r.ema9Previous = emaPrevious(close, 9);
        r.emaReady = true;
        r.emaBull = r.ema5 > r.ema9 && r.ema5 >= r.ema5Previous && r.ema9 >= r.ema9Previous;
        r.emaBear = r.ema5 < r.ema9 && r.ema5 <= r.ema5Previous && r.ema9 <= r.ema9Previous;
        r.rsi7 = rsi(close, 7);
        r.rsi7Previous = rsiPrevious(close, 7);
        r.rsiReady = Double.isFinite(r.rsi7) && Double.isFinite(r.rsi7Previous);
        r.rsiRising = r.rsiReady && r.rsi7 > r.rsi7Previous;
        r.rsiFalling = r.rsiReady && r.rsi7 < r.rsi7Previous;
        double tr = 0.0;
        int start = Math.max(1, n - 7);
        for (int i = start; i < n; i++) {
            SignalEngine.Candle c = bars.get(i), p = bars.get(i - 1);
            tr += Math.max(c.high - c.low,
                Math.max(Math.abs(c.high - p.close), Math.abs(c.low - p.close)));
        }
        r.atr7 = tr / Math.max(1, n - start);
        r.ready = r.emaReady && r.rsiReady && r.atr7 > 0.0;
        return r;
    }

    private static double ema(double[] v, int period) {
        double alpha = 2.0 / (period + 1.0);
        double out = v[0];
        for (int i = 1; i < v.length; i++) out = alpha * v[i] + (1.0 - alpha) * out;
        return out;
    }
    private static double emaPrevious(double[] v, int period) {
        if (v.length < 2) return Double.NaN;
        double[] p = new double[v.length - 1];
        System.arraycopy(v, 0, p, 0, p.length);
        return ema(p, period);
    }
    private static double rsi(double[] v, int period) {
        if (v.length <= period) return Double.NaN;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = v[i] - v[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        gain /= period; loss /= period;
        for (int i = period + 1; i < v.length; i++) {
            double d = v[i] - v[i - 1];
            gain = (gain * (period - 1) + Math.max(0, d)) / period;
            loss = (loss * (period - 1) + Math.max(0, -d)) / period;
        }
        if (loss == 0) return gain == 0 ? 50.0 : 100.0;
        if (gain == 0) return 0.0;
        return 100.0 - 100.0 / (1.0 + gain / loss);
    }
    private static double rsiPrevious(double[] v, int period) {
        if (v.length <= period + 1) return Double.NaN;
        double[] p = new double[v.length - 1];
        System.arraycopy(v, 0, p, 0, p.length);
        return rsi(p, period);
    }

    static final class Result {
        boolean ready, emaReady, rsiReady, emaBull, emaBear, rsiRising, rsiFalling;
        double ema5, ema9, ema5Previous, ema9Previous, rsi7 = Double.NaN,
            rsi7Previous = Double.NaN, atr7;
        String summary() {
            if (!ready) return "EMA/RSI warming · need 9 completed bars";
            return "EMA5 " + fmt(ema5) + " / EMA9 " + fmt(ema9) +
                (emaBull ? " rising bullish" : emaBear ? " falling bearish" : " mixed") +
                " · RSI7 " + fmt(rsi7) + (rsiRising ? " rising" : rsiFalling ? " falling" : " flat") +
                " · ATR7 " + fmt(atr7);
        }
        private static String fmt(double v) {
            return String.format(java.util.Locale.US, "%.5f", v);
        }
    }
}
