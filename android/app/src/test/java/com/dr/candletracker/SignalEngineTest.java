package com.dr.candletracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    private static final long T = 1800000000000L;

    @Test public void fiveGenuineSixtySecondHistoryCandlesArmSignals() throws JSONException {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc", 60L, historyRows(T, 5, 60000L));
        assertTrue(e.getReason().contains("5 original QX 60-second"));
        e.acceptTick("EURUSD_otc", 1.016, T + 300000L, 60L);
        assertEquals("Five real broker candles are sufficient to start signal analysis", "CALL", e.getSignal());
    }

    @Test public void rejectsTenSecondHistoryAndDoesNotFabricateBrokerCandles() throws JSONException {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc", 10L, historyRows(T, 5, 10000L));
        assertTrue(e.getReason().contains("Only genuine QX 60-second broker history"));
        for (int i = 0; i < 8; i++) {
            long ts = T + i * 60000L;
            e.acceptTick("EURUSD_otc", 1.1 + i * .001, ts, 60L);
        }
        assertEquals("WAIT", e.getSignal());
        assertTrue(e.getReason().contains("original broker-history candles"));
    }

    @Test public void liveTicksAreBucketedIntoRealSixtySecondWindows() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", 1.1000, T, 10L);
        e.acceptTick("EURUSD_otc", 1.1005, T + 59000L, 10L);
        assertEquals(1, e.getCandleCount());
        e.acceptTick("EURUSD_otc", 1.1010, T + 60000L, 10L);
        assertEquals("Chart metadata cannot change the required 60-second signal window", 2, e.getCandleCount());
    }

    @Test public void signalsContinueAfterInitialFiveHistoryCandles() throws JSONException {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc", 60L, historyRows(T, 5, 60000L));
        e.acceptTick("EURUSD_otc", 1.016, T + 300000L, 60L);
        assertEquals("CALL", e.getSignal());
        e.acceptTick("EURUSD_otc", 1.017, T + 360000L, 60L);
        assertTrue("Once five history candles arm the engine, it continues scanning live market data",
            !"Collecting original broker-history candles (0/5).".equals(e.getReason()));
    }

    @Test public void acceptsQxObjectFormatSixtySecondHistory() throws JSONException {
        SignalEngine e = new SignalEngine();
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 5; i++) {
            double open = 1.010 + i * .001, close = open + .001;
            org.json.JSONObject row = new org.json.JSONObject();
            row.put("time", T + i * 60000L);
            row.put("open", open); row.put("close", close);
            row.put("high", close); row.put("low", open);
            rows.put(row);
        }
        e.acceptHistory("EURUSD_otc", 60L, rows);
        e.acceptTick("EURUSD_otc", 1.016, T + 300000L, 60L);
        assertEquals("QX object-format 60-second history must arm signals", "CALL", e.getSignal());
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

    @Test public void settlesSignalAtExistingTenSecondEvaluationWindow() throws JSONException {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc", 60L, historyRows(T, 5, 60000L));
        e.acceptTick("EURUSD_otc", 1.016, T + 300000L, 60L);
        assertEquals("CALL", e.getSignal());
        e.acceptTick("EURUSD_otc", 1.017, T + 310000L, 60L);
        assertEquals(1, e.getWins());
    }

    private JSONArray historyRows(long start, int count, long interval) throws JSONException {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) {
            double open = 1.010 + i * .001, close = open + .001;
            JSONArray row = new JSONArray();
            row.put(start + i * interval).put(open).put(close).put(close).put(open);
            rows.put(row);
        }
        return rows;
    }
}
