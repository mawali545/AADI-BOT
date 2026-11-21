package com.dr.candletracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignalEngineTest {
    @Test public void liveTicksNeverCreateSyntheticCandles() {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        e.acceptTick("EURUSD_otc", 1.1000, t);
        e.acceptTick("EURUSD_otc", 1.1002, t + 1000);
        e.acceptTick("EURUSD_otc", 1.0995, t + 9000);
        assertEquals("WAIT", e.getSignal());
        assertEquals(0, e.getCandleCount());
        assertTrue(Double.isNaN(e.getOpen()));
        assertTrue(e.getReason().contains("synthetic candles are disabled"));
    }

    @Test public void importsOriginalQxOhlcWithoutRebuildingIt() throws JSONException {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        JSONArray rows = new JSONArray();
        rows.put(new JSONArray().put(t / 1000).put(1.1000).put(1.1004).put(1.1005).put(1.0999));
        e.acceptHistory("EURUSD_otc", 10, rows);
        assertEquals(1, e.getCandleCount());
        assertEquals(1.1000, e.getOpen(), 0.0000001);
        assertEquals(1.1005, e.getHigh(), 0.0000001);
        assertEquals(1.0999, e.getLow(), 0.0000001);
        assertEquals(1.1004, e.getClose(), 0.0000001);
    }

    @Test public void importsQxObjectCandlePayload() throws JSONException {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        JSONArray rows = new JSONArray();
        rows.put(new org.json.JSONObject().put("time", t).put("open", 1.2000)
            .put("close", 1.2008).put("high", 1.2010).put("low", 1.1999));
        e.acceptHistory("EURUSD_otc", 10, rows);
        assertEquals(1, e.getCandleCount());
        assertEquals(1.2000, e.getOpen(), 0.0000001);
        assertEquals(1.2008, e.getClose(), 0.0000001);
    }

    @Test public void rejectsNonTenSecondBrokerHistory() throws JSONException {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        JSONArray rows = new JSONArray();
        rows.put(new JSONArray().put(t / 1000).put(1.1).put(1.2).put(1.2).put(1.1));
        e.acceptHistory("EURUSD_otc", 60, rows);
        assertEquals(0, e.getCandleCount());
        assertEquals("WAIT", e.getSignal());
    }

    @Test public void ignoresOlderTicks() {
        SignalEngine e = new SignalEngine(); long t = 1800000000000L;
        e.acceptTick("EURUSD_otc", 1.1000, t);
        e.acceptTick("EURUSD_otc", 1.1010, t + 1000);
        e.acceptTick("EURUSD_otc", 1.0900, t - 1000);
        assertEquals(1.1010, e.getLastPrice(), 0.0000001);
        assertEquals(0, e.getCandleCount());
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
        e.acceptTick("EURUSD_otc", 1.0079, t + 83999);
        assertEquals(0, e.getWins());
        e.acceptTick("EURUSD_otc", 1.0080, t + 84000);
        assertEquals(1, e.getWins());
    }

    @Test public void oppositeMovementAfterTenSecondsIsLoss() {
        long t = 1800000000000L;
        SignalEngine e = primedCall(t);
        e.acceptTick("EURUSD_otc", 1.0020, t + 84000);
        assertEquals(1, e.getLosses());
        assertEquals(0, e.getWins());
    }

    @Test public void unchangedPriceAfterTenSecondsIsTie() {
        long t = 1800000000000L;
        SignalEngine e = primedCall(t);
        e.acceptTick("EURUSD_otc", 1.0074, t + 84000);
        assertEquals(1, e.getTies());
        assertEquals(0, e.getWins());
        assertEquals(0, e.getLosses());
    }

    private SignalEngine primedCall(long t) {
        SignalEngine e = new SignalEngine();
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 7; i++) {
            double open = 1.0000 + i * 0.0010;
            rows.put(new JSONArray().put((t + i * 10000L) / 1000)
                .put(open).put(open + 0.0008).put(open + 0.0010).put(open - 0.0001));
        }
        e.acceptHistory("EURUSD_otc", 10, rows);
        e.acceptTick("EURUSD_otc", 1.0070, t + 70000L);
        e.acceptTick("EURUSD_otc", 1.0071, t + 71000L);
        e.acceptTick("EURUSD_otc", 1.0072, t + 72000L);
        e.acceptTick("EURUSD_otc", 1.0073, t + 73000L);
        e.acceptTick("EURUSD_otc", 1.0074, t + 74000L);
        assertEquals("CALL", e.getSignal());
        return e;
    }
}
