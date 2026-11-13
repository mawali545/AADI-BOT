package com.aadi.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SignalEngineHistoryTest {
    @Test public void importsOnlyValidClosedTenSecondCandles() throws Exception {
        SignalEngine engine = new SignalEngine();
        JSONArray rows = new JSONArray();
        long start = 1_700_000_000L;
        for (int i = 0; i < 30; i++) {
            double open = 1.10000 + i * 0.00001;
            double close = open + 0.00002;
            rows.put(new JSONObject()
                    .put("timestamp", start + i * 10L)
                    .put("open", open)
                    .put("high", close + 0.00001)
                    .put("low", open - 0.00001)
                    .put("close", close));
        }
        rows.put(new JSONObject().put("timestamp", start + 400L)
                .put("open", 1.1).put("high", 1.0).put("low", 1.2).put("close", 1.1));

        assertEquals(30, engine.importHistoricalCandles("EURUSD_otc", rows));
        assertEquals(30, engine.currentResult().closedCandles);
        assertEquals("WAIT", engine.currentResult().signal);
    }

    @Test public void duplicateHistoryDoesNotDuplicateCandleCount() throws Exception {
        SignalEngine engine = new SignalEngine();
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 25; i++) {
            double open = 1.2 + i * 0.00001;
            rows.put(new JSONObject().put("timestamp", 1_700_000_000L + i * 10L)
                    .put("open", open).put("high", open + 0.00003)
                    .put("low", open - 0.00001).put("close", open + 0.00002));
        }
        assertEquals(25, engine.importHistoricalCandles("EURUSD_otc", rows));
        assertEquals(0, engine.importHistoricalCandles("EURUSD_otc", rows));
        assertEquals(25, engine.currentResult().closedCandles);
    }

    @Test public void changingHistoryAssetResetsOldAssetContext() throws Exception {
        SignalEngine engine = new SignalEngine();
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 20; i++) {
            double p = 1.3 + i * 0.00001;
            rows.put(new JSONObject().put("timestamp", 1_700_000_000L + i * 10L)
                    .put("open", p).put("high", p + 0.00002)
                    .put("low", p - 0.00001).put("close", p + 0.00001));
        }
        engine.importHistoricalCandles("EURUSD_otc", rows);
        assertTrue(engine.currentResult().closedCandles > 0);
        engine.importHistoricalCandles("GBPUSD_otc", rows);
        assertEquals(20, engine.currentResult().closedCandles);
    }
}