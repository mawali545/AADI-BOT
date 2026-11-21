package com.dr.candletracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    private static final long T = 1800000000000L;

    @Test public void waitsForTenHistoryAndThreeRecentCompletedCandles() throws JSONException {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc", 10L, historyRows(T));
        for (int i = 0; i < 2; i++) {
            long start = T + (100000L + i * 10000L);
            e.acceptTick("EURUSD_otc", 1.020 + i * .001, start);
            e.acceptTick("EURUSD_otc", 1.021 + i * .001, start + 9000);
        }
        e.acceptTick("EURUSD_otc", 1.023, T + 120000L);
        assertEquals("WAIT", e.getSignal());
        e.acceptTick("EURUSD_otc", 1.024, T + 130000L);
        assertEquals("CALL", e.getSignal());
    }

    @Test public void buildsOhlcFromGenuineTicksInsideTenSecondBucket() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", 1.1000, T);
        e.acceptTick("EURUSD_otc", 1.1005, T + 1000);
        e.acceptTick("EURUSD_otc", 1.0995, T + 9000);
        assertEquals(1.1000, e.getOpen(), 0.0000001);
        assertEquals(1.1005, e.getHigh(), 0.0000001);
        assertEquals(1.0995, e.getLow(), 0.0000001);
        assertEquals(1.0995, e.getClose(), 0.0000001);
    }

    @Test public void ignoresOlderTicksAndRejectsInvalidInputs() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", 1.1000, T);
        e.acceptTick("EURUSD_otc", 1.1010, T + 1000);
        e.acceptTick("EURUSD_otc", 1.0900, T - 1000);
        e.acceptTick("EURUSD_otc", -1, T + 2000);
        e.acceptTick("bad asset", 1.1, T + 2000);
        assertEquals(1.1010, e.getLastPrice(), 0.0000001);
        assertEquals(1.1010, e.getClose(), 0.0000001);
        assertEquals(1, e.getCandleCount());
    }

    @Test public void refusesHistoryThatIsNotTenSecondQxSeries() throws JSONException {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc", 60L, historyRows(T));
        assertEquals("WAIT", e.getSignal());
        assertTrue(e.getReason().contains("60s"));
    }

    @Test public void settlesAtFirstRealTickTenSecondsAfterSignal() throws JSONException {
        SignalEngine e = primedCall();
        assertEquals("CALL", e.getSignal());
        e.acceptTick("EURUSD_otc", 1.030, T + 139999L);
        assertEquals(0, e.getWins());
        e.acceptTick("EURUSD_otc", 1.031, T + 140000L);
        assertEquals(1, e.getWins());
    }

    @Test public void oppositeMovementAfterTenSecondsIsLoss() throws JSONException {
        SignalEngine e = primedCall();
        e.acceptTick("EURUSD_otc", 1.020, T + 140000L);
        assertEquals(1, e.getLosses());
        assertEquals(0, e.getWins());
    }

    @Test public void unchangedPriceAfterTenSecondsIsTie() throws JSONException {
        SignalEngine e = primedCall();
        e.acceptTick("EURUSD_otc", 1.024, T + 140000L);
        assertEquals(1, e.getTies());
        assertEquals(0, e.getWins());
        assertEquals(0, e.getLosses());
    }

    private SignalEngine primedCall() throws JSONException {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc", 10L, historyRows(T));
        for (int i = 0; i < 3; i++) {
            long start = T + (100000L + i * 10000L);
            double open = 1.020 + i * .001;
            e.acceptTick("EURUSD_otc", open, start);
            e.acceptTick("EURUSD_otc", open + .001, start + 9000L);
        }
        e.acceptTick("EURUSD_otc", 1.024, T + 130000L);
        assertEquals("13 consecutive genuine completed candles should arm CALL", "CALL", e.getSignal());
        return e;
    }

    private JSONArray historyRows(long start) throws JSONException {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 10; i++) {
            double open = 1.010 + i * .001, close = open + .001;
            JSONArray row = new JSONArray();
            row.put(start + i * 10000L).put(open).put(close).put(close).put(open);
            rows.put(row);
        }
        return rows;
    }
}
