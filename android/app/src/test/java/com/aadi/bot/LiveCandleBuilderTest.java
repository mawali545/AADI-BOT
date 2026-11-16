package com.aadi.bot;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveCandleBuilderTest {
    @Test public void buildsOnlyClosedTenSecondCandlesFromLiveTicks() {
        LiveCandleBuilder builder = new LiveCandleBuilder();
        assertNull(builder.addTick("EURUSD_otc", 1000, 1.1000));
        assertNull(builder.addTick("EURUSD_otc", 4000, 1.1020));
        assertNull(builder.addTick("EURUSD_otc", 9000, 1.0990));

        JSONObject closed = builder.addTick("EURUSD_otc", 10_000, 1.1010);
        assertNotNull(closed);
        assertEquals(0.0, closed.optDouble("timestamp"), 0.0);
        assertEquals(1.1000, closed.optDouble("open"), 0.0000001);
        assertEquals(1.1020, closed.optDouble("high"), 0.0000001);
        assertEquals(1.0990, closed.optDouble("low"), 0.0000001);
        assertEquals(1.0990, closed.optDouble("close"), 0.0000001);
        assertEquals("live_qx_ticks", closed.optString("source"));
    }

    @Test public void doesNotInventCandlesForMissingTimeBuckets() {
        LiveCandleBuilder builder = new LiveCandleBuilder();
        builder.addTick("EURUSD_otc", 1_000, 1.0);
        JSONObject first = builder.addTick("EURUSD_otc", 35_000, 1.2);
        assertNotNull(first);
        assertEquals(0, first.optLong("timestamp"));
        assertNull(builder.addTick("EURUSD_otc", 39_000, 1.3));
        JSONObject second = builder.addTick("EURUSD_otc", 40_000, 1.25);
        assertNotNull(second);
        assertEquals(30_000, second.optLong("timestamp"));
    }

    @Test public void resetsOnAssetChangeAndIgnoresOutOfOrderTicks() {
        LiveCandleBuilder builder = new LiveCandleBuilder();
        builder.addTick("EURUSD_otc", 21_000, 1.0);
        assertNull(builder.addTick("EURUSD_otc", 19_000, 9.0));
        JSONObject forming = builder.getFormingCandle();
        assertEquals(1.0, forming.optDouble("close"), 0.0000001);

        assertNull(builder.addTick("GBPUSD_otc", 31_000, 1.5));
        JSONObject afterSwitch = builder.getFormingCandle();
        assertEquals(30_000, afterSwitch.optLong("timestamp"));
        assertEquals(1.5, afterSwitch.optDouble("open"), 0.0000001);
    }
}
