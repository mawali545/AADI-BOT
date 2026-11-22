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
        assertTrue(m.recordTick("EURUSD_otc", 110000, 11000, 60));
        org.json.JSONObject s = m.snapshot("EURUSD_otc", 12000);
        assertEquals(1, s.optInt("tickGapCount"));
        assertEquals(10000, s.optLong("tickJitterMs"));
    }

    @Test public void recordsNativeArrivalAge() {
        FeedIntegrityMonitor m = new FeedIntegrityMonitor();
        m.recordTick("EURUSD_otc", 100000, 5000, 60);
        assertEquals(250, m.snapshot("EURUSD_otc", 5250).optLong("nativeArrivalMs"));
    }
}
