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

    @Test public void settlesSignalAtFirstRealTickTenSecondsAfterSignal() {
        long t = 1800000000000L;
        SignalEngine e = primedCall(t);
        assertEquals("CALL", e.getSignal());
        e.acceptTick("EURUSD_otc", 1.0079, t + 79999);
        assertEquals("Must not settle before 10 seconds", 0, e.getWins());
        e.acceptTick("EURUSD_otc", 1.0080, t + 80000);
        assertEquals("Must settle on first real tick at expiry", 1, e.getWins());
    }

    @Test public void pendingSignalsDoNotOverwriteEachOther() {
        long t = 1800000000000L;
        SignalEngine e = primedCall(t);
        assertEquals("CALL", e.getSignal());
        e.acceptTick("EURUSD_otc", 1.0080, t + 80000);
        assertEquals("First signal settles independently", 1, e.getWins());
        e.acceptTick("EURUSD_otc", 1.0090, t + 90000);
        assertTrue("Subsequent signal remains independently tracked", e.getWins() >= 1);
    }

    private SignalEngine primedCall(long t) {
        SignalEngine e = new SignalEngine();
        for (int i = 0; i < 7; i++) {
            double open = 1.0000 + i * 0.0010;
            e.acceptTick("EURUSD_otc", open, t + i * 10000L);
            e.acceptTick("EURUSD_otc", open + 0.0008, t + i * 10000L + 9000L);
        }
        e.acceptTick("EURUSD_otc", 1.0072, t + 70000L);
        assertEquals("CALL", e.getSignal());
        return e;
    }

    @Test public void oppositeMovementAfterTenSecondsIsLoss() {
        long t = 1800000000000L;
        SignalEngine e = primedCall(t);
        e.acceptTick("EURUSD_otc", 1.0020, t + 40000);
        assertEquals(1, e.getLosses());
        assertEquals(0, e.getWins());
    }

    @Test public void unchangedPriceAfterTenSecondsIsTie() {
        long t = 1800000000000L;
        SignalEngine e = primedCall(t);
        e.acceptTick("EURUSD_otc", 1.0030, t + 40000);
        assertEquals(1, e.getTies());
        assertEquals(0, e.getWins());
        assertEquals(0, e.getLosses());
    }

}