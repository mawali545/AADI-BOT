package com.aadi.bot;

import org.json.JSONObject;

/**
 * Builds genuine 10-second OHLC candles from accepted live QX ticks only.
 * No history import, gap filling, interpolation, or sample data is performed.
 */
public final class LiveCandleBuilder {
    public static final long PERIOD_MS = 10_000L;

    private String asset = "";
    private long bucketStartMs;
    private long lastTickTimestampMs;
    private double open;
    private double high;
    private double low;
    private double close;
    private boolean active;

    /**
     * Adds one real tick. Returns the candle that just closed when the tick enters
     * a later 10-second bucket; otherwise returns null.
     */
    public synchronized JSONObject addTick(String nextAsset, long timestampMs, double price) {
        if (nextAsset == null || nextAsset.trim().isEmpty()
                || timestampMs <= 0 || !Double.isFinite(price) || price <= 0) return null;
        String normalizedAsset = nextAsset.trim();
        if (!normalizedAsset.equals(asset)) {
            reset();
            asset = normalizedAsset;
        }
        if (active && timestampMs <= lastTickTimestampMs) return null;
        long bucket = (timestampMs / PERIOD_MS) * PERIOD_MS;
        if (!active) {
            begin(bucket, price);
            lastTickTimestampMs = timestampMs;
            return null;
        }
        if (bucket < bucketStartMs) return null;
        if (bucket == bucketStartMs) {
            high = Math.max(high, price);
            low = Math.min(low, price);
            close = price;
            lastTickTimestampMs = timestampMs;
            return null;
        }
        JSONObject completed = toJson();
        begin(bucket, price);
        lastTickTimestampMs = timestampMs;
        return completed;
    }

    private void begin(long bucket, double price) {
        bucketStartMs = bucket;
        open = high = low = close = price;
        active = true;
    }

    private JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("timestamp", bucketStartMs)
                    .put("open", open)
                    .put("high", high)
                    .put("low", low)
                    .put("close", close)
                    .put("source", "live_qx_ticks");
        } catch (Exception impossible) {
            return null;
        }
    }

    public synchronized JSONObject getFormingCandle() {
        return active ? toJson() : null;
    }

    public synchronized void reset() {
        asset = "";
        bucketStartMs = 0;
        lastTickTimestampMs = 0;
        open = high = low = close = 0;
        active = false;
    }
}
