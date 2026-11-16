package com.aadi.bot;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveCandleBuilderTest {
    @Test public void buildsOnlyClosedTenSecondCandlesFromLiveTicks() {
        LiveCandleBuilder builder = new LiveCandleBuilder();
        assertNull(builder.addTick("EURUSD_otc", 1_000_000, 1.1000));
        assertNull(builder.addTick("EURUSD_otc", 1_004_000, 1.1020));
        assertNull(builder.addTick("EURUSD_otc", 1_009_000, 1.0990));

        JSONObject closed = builder.addTick("EURUSD_otc", 1_010_000, 1.1010);
        assertNotNull(closed);
        assertEquals(1_000_000L, closed.optLong("timestamp"));
        assertEquals(1.1000, closed.optDouble("open"), 0.0000001);
        assertEquals(1.1020, closed.optDouble("high"), 0.0000001);
        assertEquals(1.0990, closed.optDouble("low"), 0.0000001);
        assertEquals(1.0990, closed.optDouble("close"), 0.0000001);
        assertEquals("live_qx_ticks", closed.optString("source"));
    }

    @Test public void doesNotInventCandlesForMissingTimeBuckets() {
        LiveCandleBuilder builder = new LiveCandleBuilder();
        builder.addTick("EURUSD_otc", 2_000_000, 1.0);
        JSONObject first = builder.addTick("EURUSD_otc", 2_035_000, 1.2);
        assertNotNull(first);
        assertEquals(2_000_000L, first.optLong("timestamp"));
        assertNull(builder.addTick("EURUSD_otc", 2_039_000, 1.3));
        JSONObject second = builder.addTick("EURUSD_otc", 2_040_000, 1.25);
        assertNotNull(second);
        assertEquals(2_030_000L, second.optLong("timestamp"));
    }

    @Test public void resetsOnAssetChangeAndIgnoresOutOfOrderTicks() {
        LiveCandleBuilder builder = new LiveCandleBuilder();
        builder.addTick("EURUSD_otc", 3_021_000, 1.0);
        assertNull(builder.addTick("EURUSD_otc", 3_019_000, 9.0));
        JSONObject forming = builder.getFormingCandle();
        assertEquals(1.0, forming.optDouble("close"), 0.0000001);

        assertNull(builder.addTick("GBPUSD_otc", 4_031_000, 1.5));
        JSONObject afterSwitch = builder.getFormingCandle();
        assertEquals(4_030_000L, afterSwitch.optLong("timestamp"));
        assertEquals(1.5, afterSwitch.optDouble("open"), 0.0000001);
    }
}
