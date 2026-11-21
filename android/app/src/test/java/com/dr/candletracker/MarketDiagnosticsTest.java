package com.dr.candletracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class MarketDiagnosticsTest {
    @Test public void buildsRealTenSecondMemoryOhlcAndVector() throws Exception {
        MarketDiagnostics d = new MarketDiagnostics();
        long t = 1800000000000L;
        d.onTick("EURUSD_otc", 1.1000, t, t + 30);
        d.onTick("EURUSD_otc", 1.1005, t + 1000, t + 1030);
        d.onTick("EURUSD_otc", 1.0995, t + 9000, t + 9030);
        assertEquals(1, d.getMemoryCount());
        assertEquals(3, d.getCycles());
        assertEquals(30, d.getLatencyMs());
        assertTrue(d.snapshot().getJSONArray("memoryStream").getJSONObject(0).getDouble("high") > 1.1000);
        assertEquals(4, d.snapshot().getJSONArray("vector").length());
    }

    @Test public void startsWithoutInventedMarketData() {
        MarketDiagnostics d = new MarketDiagnostics();
        assertEquals(0, d.getMemoryCount());
        assertEquals(0, d.getCycles());
        assertEquals(-1, d.getLatencyMs());
    }
}