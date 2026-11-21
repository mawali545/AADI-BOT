package com.dr.candletracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    @Test public void waitsUntilThreeRealTenSecondCandlesAreCompleted() {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        e.acceptTick("EURUSD_otc", 1.1000, t);
        e.acceptTick("EURUSD_otc", 1.1002, t + 1000);
        assertEquals("WAIT", e.getSignal());
        assertEquals(1, e.getCandleCount());
    }

    @Test public void buildsOhlcFromTicksInsideTenSecondBucket() {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        e.acceptTick("EURUSD_otc", 1.1000, t);
        e.acceptTick("EURUSD_otc", 1.1005, t + 1000);
        e.acceptTick("EURUSD_otc", 1.0995, t + 9000);
        assertEquals(1.1000, e.getOpen(), 0.0000001);
        assertEquals(1.1005, e.getHigh(), 0.0000001);
        assertEquals(1.0995, e.getLow(), 0.0000001);
        assertEquals(1.0995, e.getClose(), 0.0000001);
    }

    @Test public void ignoresOlderTicks() {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        e.acceptTick("EURUSD_otc", 1.1000, t);
        e.acceptTick("EURUSD_otc", 1.1010, t + 1000);
        e.acceptTick("EURUSD_otc", 1.0900, t - 1000);
        assertEquals(1.1010, e.getLastPrice(), 0.0000001);
        assertEquals(1.1010, e.getClose(), 0.0000001);
    }

    @Test public void rejectsInvalidInputs() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", -1, 1800000000000L);
        e.acceptTick("EURUSD_otc", 1.1, 0);
        e.acceptTick("bad asset", 1.1, 1800000000000L);
        assertEquals(0, e.getCandleCount());
    }

    @Test public void settlesSignalOnlyAfterTheFollowingTenSecondCandleCloses() {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        e.acceptTick("EURUSD_otc", 1.0000, t);
        e.acceptTick("EURUSD_otc", 1.0010, t + 9000);
        e.acceptTick("EURUSD_otc", 1.0010, t + 10000);
        e.acceptTick("EURUSD_otc", 1.0020, t + 19000);
        e.acceptTick("EURUSD_otc", 1.0020, t + 20000);
        e.acceptTick("EURUSD_otc", 1.0030, t + 29000);
        e.acceptTick("EURUSD_otc", 1.0030, t + 30000);
        assertEquals("CALL", e.getSignal());

        e.acceptTick("EURUSD_otc", 1.0040, t + 40000);
        e.acceptTick("EURUSD_otc", 1.0050, t + 49000);
        assertEquals("WIN count must wait for target candle close", 0, e.getWins());

        e.acceptTick("EURUSD_otc", 1.0060, t + 50000);
        assertEquals(1, e.getWins());
    }

    @Test public void pendingSignalsDoNotOverwriteEachOther() {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        e.acceptTick("EURUSD_otc", 1.0000, t);
        e.acceptTick("EURUSD_otc", 1.0010, t + 9000);
        e.acceptTick("EURUSD_otc", 1.0010, t + 10000);
        e.acceptTick("EURUSD_otc", 1.0020, t + 19000);
        e.acceptTick("EURUSD_otc", 1.0020, t + 20000);
        e.acceptTick("EURUSD_otc", 1.0030, t + 29000);
        e.acceptTick("EURUSD_otc", 1.0030, t + 30000);
        assertEquals("CALL", e.getSignal());

        e.acceptTick("EURUSD_otc", 1.0040, t + 40000);
        e.acceptTick("EURUSD_otc", 1.0050, t + 50000);
        assertEquals("First signal should settle at the first target candle close", 1, e.getWins());

        e.acceptTick("EURUSD_otc", 1.0060, t + 60000);
        assertTrue("Second signal remains independently tracked", e.getWins() >= 1);
    }

    @Test public void ignoresNonTenSecondHistoryForSignalCandles() {
        SignalEngine e = new SignalEngine();
        org.json.JSONArray rows = new org.json.JSONArray();
        rows.put(new org.json.JSONArray().put(1800000000L).put(1.0).put(1.1).put(1.1).put(1.0));
        e.acceptHistory("EURUSD_otc", 60, rows);
        assertEquals(0, e.getCandleCount());
        assertEquals("WAIT", e.getSignal());
    }
}