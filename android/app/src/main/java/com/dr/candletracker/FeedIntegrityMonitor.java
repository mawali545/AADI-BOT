package com.dr.candletracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

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
        if (s.lastServerTime == serverTimeMs) s.duplicateTimestamps++;
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
            out.put("nativeAcceptedTicks", s == null ? 0 : s.accepted);
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
        long lastServerTime, lastArrivalElapsed, gaps, outOfOrder, duplicateTimestamps, accepted;
        final Deque<Long> jitter = new ArrayDeque<>();
    }
}
