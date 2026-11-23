package com.dr.candletracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class FeedIntegrityMonitorTest {
    @Test public void rejectsOutOfOrderAndDuplicateTicks() {
        FeedIntegrityMonitor m = new FeedIntegrityMonitor();
        assertTrue(m.recordTick("EURUSD_otc", 100000, 1000, 60));
        assertFalse(m.recordTick("EURUSD_otc", 99000, 1100, 60));
        assertFalse(m.recordTick("EURUSD_otc", 100000, 1200, 60));
        org.json.JSONObject s = m.snapshot("EURUSD_otc", 1300);
        assertEquals(1, s.optInt("outOfOrderCount"));
        assertEquals(1, s.optInt("duplicateTimestampCount"));
        assertEquals(1, s.optInt("nativeAcceptedTicks"));
    }

    @Test public void recordsLargeSourceGapsWithoutTreatingThemAsRtt() {
        FeedIntegrityMonitor m = new FeedIntegrityMonitor();
        assertTrue(m.recordTick("EURUSD_otc", 100000, 1000, 60));
        assertTrue(m.recordTick("EURUSD_otc", 110000, 21000, 60));
        org.json.JSONObject s = m.snapshot("EURUSD_otc", 22000);
        assertEquals(1, s.optInt("tickGapCount"));
        assertEquals(10000, s.optLong("tickJitterMs"));
    }

    @Test public void recordsNativeArrivalAge() {
        FeedIntegrityMonitor m = new FeedIntegrityMonitor();
        m.recordTick("EURUSD_otc", 100000, 5000, 60);
        assertEquals(250, m.snapshot("EURUSD_otc", 5250).optLong("nativeArrivalMs"));
    }
    @Test public void rejectsMalformedAndDuplicateBucketHistory() throws Exception {
        FeedIntegrityMonitor m = new FeedIntegrityMonitor();
        org.json.JSONArray rows = new org.json.JSONArray();
        rows.put(new org.json.JSONObject().put("time", 1800000000000L).put("open", 1.0).put("close", 1.1).put("high", 1.2).put("low", 0.9));
        rows.put(new org.json.JSONObject().put("time", 1800000001000L).put("open", 1.0).put("close", 1.1).put("high", 1.2).put("low", 0.9));
        assertFalse(m.recordHistory("EURUSD_otc", 60, rows));
        org.json.JSONObject s = m.snapshot("EURUSD_otc", 0);
        assertEquals(1, s.optInt("historyDuplicateBuckets"));
        assertEquals(2, s.optInt("historyRows"));
    }

    @Test public void acceptsValidHistoryAndCountsMissingBucketsWithoutBlocking() throws Exception {
        FeedIntegrityMonitor m = new FeedIntegrityMonitor();
        org.json.JSONArray rows = new org.json.JSONArray();
        rows.put(new org.json.JSONObject().put("time", 60000).put("open", 1.0).put("close", 1.1).put("high", 1.2).put("low", 0.9));
        rows.put(new org.json.JSONObject().put("time", 1800000120000L).put("open", 1.1).put("close", 1.2).put("high", 1.3).put("low", 1.0));
        assertTrue(m.recordHistory("EURUSD_otc", 60, rows));
        assertEquals(1, m.snapshot("EURUSD_otc", 0).optInt("historyGapCount"));
    }

}
