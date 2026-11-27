package com.dr.candletracker;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONArray;

public class SignalEngineTest {
    private static final long T = 1800000000000L;

    private JSONArray history(double[][] bars) throws Exception {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < bars.length; i++) {
            if (i > 0) s.append(",");
            double[] b = bars[i]; // open, close, high, low
            s.append("{\"time\":").append(T + i * 60000L)
             .append(",\"open\":").append(b[0])
             .append(",\"close\":").append(b[1])
             .append(",\"high\":").append(b[2])
             .append(",\"low\":").append(b[3]).append("}");
        }
        return new JSONArray(s.append("]").toString());
    }

    private double[][] bullishPullbackSetup() {
        return new double[][] {
            {1.1000,1.1002,1.1003,1.0999},
            {1.1002,1.1004,1.1005,1.1001},
            {1.1004,1.1006,1.1007,1.1003},
            {1.1006,1.1005,1.1008,1.1004},
            {1.1005,1.1007,1.1008,1.1004},
            {1.1007,1.10065,1.1009,1.1006},
            {1.10065,1.1009,1.1010,1.1006},
            {1.1009,1.1012,1.1013,1.1008}
        };
    }

    @Test public void waitsForEightCompletedRealCandles() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", 1.1000, T, 60);
        e.acceptTick("EURUSD_otc", 1.1002, T + 60000, 60);
        assertEquals("WAIT", e.getSignal());
    }

    @Test public void buildsOhlcOnlyFromAcceptedTicks() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc",1.1000,T);
        e.acceptTick("EURUSD_otc",1.1005,T+1000);
        e.acceptTick("EURUSD_otc",1.0995,T+2000);
        assertEquals(1.1000,e.getOpen(),0.0000001);
        assertEquals(1.1005,e.getHigh(),0.0000001);
        assertEquals(1.0995,e.getLow(),0.0000001);
        assertEquals(1.0995,e.getClose(),0.0000001);
    }

    @Test public void ignoresOlderTicks() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc",1.1000,T);
        e.acceptTick("EURUSD_otc",1.1010,T+1000);
        e.acceptTick("EURUSD_otc",1.0900,T-1000);
        assertEquals(1.1010,e.getLastPrice(),0.0000001);
        assertEquals(1.1010,e.getClose(),0.0000001);
    }

    @Test public void ignoresDuplicateTimestampTicks() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc",1.1000,T,10);
        e.acceptTick("EURUSD_otc",1.1050,T,10); // duplicate QX event timestamp
        assertEquals(1.1000,e.getLastPrice(),0.0000001);
        assertEquals(1.1000,e.getClose(),0.0000001);
    }


    @Test public void derivesDirectionFromPriceNotUndocumentedBrokerEnum() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", 1.1000, T, 10, 1);
        e.acceptTick("EURUSD_otc", 1.0990, T + 1000, 10, 1);
        assertEquals(0, e.getDirectionalUpTicks());
        assertEquals(1, e.getDirectionalDownTicks());
    }

    @Test public void rejectsInvalidInputs() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc",-1,T);
        e.acceptTick("EURUSD_otc",1.1,0);
        e.acceptTick("bad asset",1.1,T);
        assertEquals(0,e.getCandleCount());
    }

    @Test public void threeCompletedBrokerCandlesCanProduceSignal() throws Exception {
        SignalEngine e = new SignalEngine();
        double[][] bars = new double[][] {
            {1.1000,1.1002,1.1003,1.0999},
            {1.1002,1.1004,1.1005,1.1001},
            {1.1004,1.1007,1.1008,1.1003}
        };
        e.acceptHistory("EURUSD_otc",60,history(bars));
        e.acceptTick("EURUSD_otc",1.1007,T+180000,60);
        assertEquals("CALL",e.getSignal());
    }

    @Test public void continuationNeedsPullbackAndFreshConfirmation() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        assertEquals("CALL",e.getSignal());
        assertTrue(e.getReason().contains("PULLBACK / CONTINUATION") || e.getReason().contains("STRUCTURE BREAK / REVERSAL"));
    }

    @Test public void signalScoreIsExplicitlyAnEvidenceScoreNotAWinProbability() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        assertEquals("CALL",e.getSignal());
        assertTrue(e.getSignalProbability() >= 45.0);
        assertTrue(e.getSignalProbability() <= 85.0);
        assertTrue(e.getReason().contains("directional evidence"));
        assertTrue(e.getReason().contains("not a measured win probability"));
    }

    @Test public void straightExtendedGreenRunDoesNotChaseWithCall() throws Exception {
        SignalEngine e = new SignalEngine();
        double[][] bars = new double[10][4];
        for (int i=0;i<bars.length;i++) {
            double o=1.1000+i*0.0003;
            bars[i]=new double[]{o,o+0.0003,o+0.0004,o-0.00005};
        }
        e.acceptHistory("EURUSD_otc",60,history(bars));
        e.acceptTick("EURUSD_otc",1.1030,T+600000,60);
        assertEquals("WAIT",e.getSignal());
        assertTrue(e.getReason().contains("extended") ||
            e.getReason().contains("no confirmed pullback"));
    }

    @Test public void structureBreakCanSignalPutAgainstEarlierRise() throws Exception {
        SignalEngine e = new SignalEngine();
        double[][] bars = new double[][] {
            {1.1000,1.1001,1.1004,1.0998},
            {1.1001,1.1002,1.1005,1.0999},
            {1.1002,1.1003,1.1006,1.1000},
            {1.1003,1.1004,1.1007,1.1001},
            {1.1004,1.1005,1.1008,1.1002},
            {1.1005,1.1000,1.1007,1.0999},
            {1.1000,1.0995,1.1002,1.0994},
            {1.0995,1.0985,1.0998,1.0984}
        };
        e.acceptHistory("EURUSD_otc",60,history(bars));
        e.acceptTick("EURUSD_otc",1.0988,T+480000,60);
        e.acceptTick("EURUSD_otc",1.0985,T+481000,60);
        assertEquals("Expected PUT; actual=" + e.getSignal() + "; reason=" + e.getReason(), "PUT", e.getSignal());
    }

    @Test public void reboundIntoPriorHighWithUpperWickCanSignalPutReversal() throws Exception {
        SignalEngine e = new SignalEngine();
        double[][] bars = new double[][] {
            {1.1000,1.1003,1.1004,1.0999},
            {1.1003,1.1006,1.1007,1.1002},
            {1.1006,1.1009,1.1010,1.1005},
            {1.1009,1.1012,1.1013,1.1008},
            {1.1012,1.1015,1.1016,1.1011},
            {1.1015,1.1018,1.1019,1.1014},
            {1.1018,1.1021,1.1022,1.1017},
            {1.1021,1.1019,1.1028,1.1018}
        };
        e.acceptHistory("EURUSD_otc",60,history(bars));
        e.acceptTick("EURUSD_otc",1.1019,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1028,T+480500,60);
        e.acceptTick("EURUSD_otc",1.1013,T+481000,60);
        assertEquals("Expected PUT reversal; actual=" + e.getSignal() +
            "; reason=" + e.getReason(), "PUT", e.getSignal());
        assertTrue(e.getReason().contains("LEVEL REJECTION / REVERSAL"));
    }

    @Test public void selloffIntoPriorLowWithLowerWickCanSignalCallReversal() throws Exception {
        SignalEngine e = new SignalEngine();
        double[][] bars = new double[][] {
            {1.1021,1.1018,1.1022,1.1017},
            {1.1018,1.1015,1.1019,1.1014},
            {1.1015,1.1012,1.1016,1.1011},
            {1.1012,1.1009,1.1013,1.1008},
            {1.1009,1.1006,1.1010,1.1005},
            {1.1006,1.1003,1.1007,1.1002},
            {1.1003,1.1000,1.1004,1.0999},
            {1.1000,1.1002,1.1003,1.0992}
        };
        e.acceptHistory("EURUSD_otc",60,history(bars));
        e.acceptTick("EURUSD_otc",1.1002,T+480000,60);
        e.acceptTick("EURUSD_otc",1.0992,T+480500,60);
        e.acceptTick("EURUSD_otc",1.1008,T+481000,60);
        assertEquals("CALL",e.getSignal());
        assertTrue(e.getReason().contains("LEVEL REJECTION / REVERSAL"));
    }

    @Test public void telemetryMemoryExcludesTheCurrentlyFormingQxCandle() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+420000,60);
        assertEquals(7,e.getAssetCandleCount());
        assertEquals(7,e.getHistogramBars().length());
    }


    @Test public void createsHonestTickBarsWhenBrokerHistoryIsUnavailable() {
        SignalEngine e = new SignalEngine();
        for (int i = 0; i < 9; i++) {
            double price = 1.1000 + i * 0.0001;
            e.acceptTick("EURUSD_otc", price, T + i * 1000L, 60);
        }
        assertTrue(e.getAssetCandleCount() >= 3);
        assertTrue(e.getHistogramBars().length() >= 3);
        assertEquals(1, e.getCandleCount()); // only one 60-second forming tick candle
    }


    @Test public void measuresPriceVelocityAndAccelerationBetweenEveryAcceptedTick() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", 1.10000, T, 10);
        double price = 1.10000;
        for (int i = 1; i <= 20; i++) {
            price += 0.00001;
            e.acceptTick("EURUSD_otc", price, T + i * 1000L, 10);
        }
        for (int i = 21; i <= 40; i++) {
            price += 0.00010;
            e.acceptTick("EURUSD_otc", price, T + i * 1000L, 10);
        }
        assertTrue("upward quote velocity must be positive", e.getTickVelocity() > 0.0);
        assertTrue("increasing upward speed must produce positive velocity acceleration",
            e.getTickVelocityAcceleration() > 0.0);
    }

    @Test public void measuresNegativeVelocityWhenRecentPriceFalls() {
        SignalEngine e = new SignalEngine();
        e.acceptTick("EURUSD_otc", 1.10000, T, 10);
        double price = 1.10000;
        for (int i = 1; i <= 30; i++) {
            price -= 0.00010;
            e.acceptTick("EURUSD_otc", price, T + i * 1000L, 10);
        }
        assertTrue("downward quote velocity must be negative", e.getTickVelocity() < 0.0);
    }

    @Test public void oneSignalPerCandleAndNoNextSignalBeforeExpiry() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        assertEquals("CALL",e.getSignal());

        // More quote events during the active 10-second contract cannot emit
        // another signal, even if the scanner is invoked repeatedly.
        e.acceptTick("EURUSD_otc",1.1017,T+482000,60);
        e.acceptTick("EURUSD_otc",1.1016,T+489999,60);
        assertEquals("CALL",e.getSignal());

        // First quote at expiry settles the contract. The same chart candle
        // remains locked, so it cannot immediately generate a replacement.
        e.acceptTick("EURUSD_otc",1.1019,T+490000,60);
        assertEquals(1,e.getWins());
        assertEquals("WAIT",e.getSignal());
        e.acceptTick("EURUSD_otc",1.1018,T+491000,60);
        assertEquals("WAIT",e.getSignal());
    }

    @Test public void tenSecondSettlementUsesFirstRealTickAtOrAfterExpiry() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        assertEquals("CALL",e.getSignal());
        e.acceptTick("EURUSD_otc",1.1013,T+489999,60);
        assertEquals(0,e.getWins());
        assertEquals(0,e.getLosses());
        e.acceptTick("EURUSD_otc",1.1019,T+490000,60);
        assertEquals(1,e.getWins());
        e.acceptTick("EURUSD_otc",1.1015,T+491000,60);
        assertEquals(1,e.getWins());
    }
}
