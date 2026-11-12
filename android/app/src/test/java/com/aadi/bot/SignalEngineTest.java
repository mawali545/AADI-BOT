package com.aadi.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignalEngineTest {
    private static final String ASSET = "EURUSD_otc";

    private void feedCandle(SignalEngine engine, int index,
                            double open, double high, double low, double close) {
        long bucket = 1_800_000_000L + index * 10L;
        engine.onTick(ASSET, open, bucket + 1);
        engine.onTick(ASSET, high, bucket + 3);
        engine.onTick(ASSET, low, bucket + 6);
        engine.onTick(ASSET, close, bucket + 9);
    }

    @Test public void warmupDoesNotIssueDirectionalSignals() {
        SignalEngine engine = new SignalEngine();
        for (int i = 0; i < 30; i++) {
            double p = 1.1000 + i * 0.00001;
            feedCandle(engine, i, p, p + 0.00003, p - 0.00002, p + 0.00001);
        }
        SignalEngine.Result result = engine.currentResult();
        assertEquals("WAIT", result.signal);
        assertTrue(result.closedCandles < 50);
        assertEquals(0, result.accuracySamples);
        assertEquals(-1.0, result.accuracyPct, 0.0001);
    }

    @Test public void flatMarketDoesNotInventDirectionOrAccuracy() {
        SignalEngine engine = new SignalEngine();
        for (int i = 0; i < 90; i++) {
            double p = 1.2500;
            feedCandle(engine, i, p, p + 0.00002, p - 0.00002, p);
        }
        SignalEngine.Result result = engine.currentResult();
        assertEquals("WAIT", result.signal);
        assertEquals(0, result.accuracySamples);
        assertEquals(-1.0, result.accuracyPct, 0.0001);
    }

    @Test public void sustainedTrendProducesLayerAgreementAndMeasuredOutcomes() {
        SignalEngine engine = new SignalEngine();
        double p = 1.0800;
        for (int i = 0; i < 100; i++) {
            double open = p;
            double close = open + 0.00018;
            double high = close + 0.00002;
            double low = open - 0.00002;
            feedCandle(engine, i, open, high, low, close);
            p = close;
        }

        SignalEngine.Result result = engine.currentResult();
        assertEquals("CALL", result.signal);
        assertTrue("Trend, price action and momentum must agree",
                result.score >= 3);
        assertTrue("Completed predictions must be evaluated",
                result.accuracySamples >= 20);
        assertEquals(100.0, result.accuracyPct, 0.0001);
    }

    @Test public void singleTickBarIsNotUsedToScoreAccuracy() {
        SignalEngine engine = new SignalEngine();
        double p = 1.0800;
        for (int i = 0; i < 65; i++) {
            double close = p + 0.00018;
            feedCandle(engine, i, p, close + 0.00002, p - 0.00002, close);
            p = close;
        }
        int before = engine.currentResult().accuracySamples;
        long oneTickBucket = 1_800_000_000L + 65 * 10L;
        engine.onTick(ASSET, p, oneTickBucket + 1);
        engine.onTick(ASSET, p + 0.00018, oneTickBucket + 11);
        SignalEngine.Result result = engine.currentResult();
        assertEquals(before, result.accuracySamples);
    }

    @Test public void assetChangeResetsAccuracyAndCandleHistory() {
        SignalEngine engine = new SignalEngine();
        double p = 1.0800;
        for (int i = 0; i < 80; i++) {
            double close = p + 0.00018;
            feedCandle(engine, i, p, close + 0.00002, p - 0.00002, close);
            p = close;
        }
        engine.onTick("GBPUSD_otc", 1.2700, 1_800_001_000L);
        SignalEngine.Result result = engine.currentResult();
        assertEquals("WAIT", result.signal);
        assertEquals(0, result.closedCandles);
        assertEquals(0, result.accuracySamples);
    }
}
