package com.dr.candletracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Measures timestamps at the native WebView bridge boundary. These are
 * application-level arrival metrics, not TCP ACK or server-confirmed RTT.
 */
public final class FeedIntegrityMonitor {
    private static final int WINDOW = 32;
    private final Map<String, State> states = new HashMap<>();

    public synchronized boolean recordTick(String asset, long serverTimeMs,
            long arrivalElapsedMs, long periodSeconds) {
        if (asset == null || serverTimeMs <= 0 || arrivalElapsedMs <= 0) return false;
        State s = states.get(asset);
        if (s == null) { s = new State(); states.put(asset, s); }
        if (s.lastServerTime > 0 && serverTimeMs < s.lastServerTime) {
            s.outOfOrder++;
            return false;
        }
        if (s.lastServerTime == serverTimeMs) { s.duplicateTimestamps++; return false; }
        if (s.lastServerTime > 0 && serverTimeMs > s.lastServerTime) {
            long sourceDelta = serverTimeMs - s.lastServerTime;
            long arrivalDelta = Math.max(0, arrivalElapsedMs - s.lastArrivalElapsed);
            if (sourceDelta > Math.max(5000L, Math.max(1L, periodSeconds) * 1500L)) s.gaps++;
            long jitter = Math.abs(arrivalDelta - sourceDelta);
            s.jitter.addLast(jitter);
            while (s.jitter.size() > WINDOW) s.jitter.removeFirst();
        }
        s.lastServerTime = Math.max(s.lastServerTime, serverTimeMs);
        s.lastArrivalElapsed = arrivalElapsedMs;
        s.accepted++;
        return true;
    }

    public synchronized boolean recordHistory(String asset, long periodSeconds, JSONArray rows) {
        if (asset == null || rows == null || rows.length() == 0) return false;
        State s = states.get(asset);
        if (s == null) { s = new State(); states.put(asset, s); }
        long duration = Math.max(1L, Math.min(86400L, periodSeconds)) * 1000L;
        java.util.TreeSet<Long> buckets = new java.util.TreeSet<>();
        boolean valid = true;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject c = rows.optJSONObject(i);
            if (c == null) { s.historyInvalid++; valid = false; continue; }
            long time = c.optLong("time", c.optLong("timestamp", 0));
            double open = c.optDouble("open", Double.NaN), close = c.optDouble("close", Double.NaN);
            double high = c.optDouble("high", Double.NaN), low = c.optDouble("low", Double.NaN);
            if (time > 0 && time < 100000000000L) time *= 1000L;
            if (time <= 0 || !Double.isFinite(open) || !Double.isFinite(close) ||
                    !Double.isFinite(high) || !Double.isFinite(low) || open <= 0 || close <= 0 || low <= 0 ||
                    high < Math.max(open, close) || low > Math.min(open, close)) {
                s.historyInvalid++; valid = false; continue;
            }
            long bucket = time / duration * duration;
            if (!buckets.add(bucket)) { s.historyDuplicateBuckets++; valid = false; }
        }
        s.historyRows = rows.length();
        if (buckets.size() > 1) {
            Long previous = null;
            for (Long bucket : buckets) {
                if (previous != null && bucket - previous > duration) s.historyGaps++;
                previous = bucket;
            }
        }
        return valid;
    }

    public synchronized JSONObject snapshot(String asset, long nowElapsedMs) {
        JSONObject out = new JSONObject();
        State s = states.get(asset);
        try {
            out.put("nativeArrivalMs", s == null || s.lastArrivalElapsed == 0
                    ? JSONObject.NULL : Math.max(0, nowElapsedMs - s.lastArrivalElapsed));
            out.put("tickJitterMs", s == null ? JSONObject.NULL : median(s.jitter));
            out.put("tickGapCount", s == null ? 0 : s.gaps);
            out.put("outOfOrderCount", s == null ? 0 : s.outOfOrder);
            out.put("duplicateTimestampCount", s == null ? 0 : s.duplicateTimestamps);
            out.put("nativeAcceptedTicks", s == null ? 0 : s.accepted);\n            out.put("historyRows", s == null ? 0 : s.historyRows);\n            out.put("historyInvalid", s == null ? 0 : s.historyInvalid);\n            out.put("historyDuplicateBuckets", s == null ? 0 : s.historyDuplicateBuckets);\n            out.put("historyGapCount", s == null ? 0 : s.historyGaps);
        } catch (Exception ignored) {}
        return out;
    }

    private static long median(Deque<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static final class State {
        long lastServerTime, lastArrivalElapsed, gaps, outOfOrder, duplicateTimestamps, accepted;\n        long historyRows, historyInvalid, historyDuplicateBuckets, historyGaps;
        final Deque<Long> jitter = new ArrayDeque<>();
    }
}
