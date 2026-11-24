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
        assertEquals("WAIT",e.getSignal()); // straight green run is not a pullback entry
    }

    @Test public void continuationNeedsPullbackAndFreshConfirmation() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        e.acceptTick("EURUSD_otc",1.1019,T+482000,60);
        assertEquals("CALL",e.getSignal());
        assertTrue(e.getReason().contains("TREND PULLBACK / CONTINUATION") || e.getReason().contains("LIQUIDITY SWEEP / STRUCTURE REVERSAL"));
    }

    @Test public void signalScoreIsExplicitlyAnEvidenceScoreNotAWinProbability() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        e.acceptTick("EURUSD_otc",1.1019,T+482000,60);
        assertEquals("CALL",e.getSignal());
        assertTrue(e.getSignalProbability() >= 45.0);
        assertTrue(e.getSignalProbability() <= 85.0);
        assertTrue(e.getReason().contains("setup score"));
        assertTrue(e.getReason().contains("not a measured win probability"));
    }

    @Test public void threeCandlePullbackCanStillSignalAfterThreeLiveQxQuotes() throws Exception {
        SignalEngine e = new SignalEngine();
        double[][] bars = new double[][] {
            {1.1000,1.1004,1.1005,1.0999},
            {1.1004,1.1003,1.1005,1.1002},
            {1.1003,1.1008,1.1009,1.10025}
        };
        e.acceptHistory("EURUSD_otc",60,history(bars));
        e.acceptTick("EURUSD_otc",1.1008,T+180000,60);
        e.acceptTick("EURUSD_otc",1.1009,T+181000,60);
        e.acceptTick("EURUSD_otc",1.1010,T+182000,60);
        assertEquals("CALL",e.getSignal());
    }

    @Test public void liveOpposingQxQuotesBlockOtherwiseValidCall() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1010,T+481000,60);
        e.acceptTick("EURUSD_otc",1.1008,T+482000,60);
        assertEquals("WAIT",e.getSignal());
        assertTrue(e.getReason().contains("live QX quote pressure"));
    }

    @Test public void touchingPriorHighWithoutLiquiditySweepDoesNotCreateReversal() throws Exception {
        SignalEngine e = new SignalEngine();
        double[][] bars = new double[][] {
            {1.1000,1.1003,1.1004,1.0999},
            {1.1003,1.1006,1.1007,1.1002},
            {1.1006,1.1009,1.1010,1.1005},
            {1.1009,1.1012,1.1013,1.1008},
            {1.1012,1.1015,1.1016,1.1011},
            {1.1015,1.1018,1.1019,1.1014},
            {1.1018,1.1021,1.1022,1.1017},
            {1.1021,1.1019,1.1022,1.1018}
        };
        e.acceptHistory("EURUSD_otc",60,history(bars));
        e.acceptTick("EURUSD_otc",1.1019,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        e.acceptTick("EURUSD_otc",1.1017,T+482000,60);
        assertEquals("WAIT",e.getSignal());
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
        e.acceptTick("EURUSD_otc",1.0984,T+482000,60);
        assertEquals("PUT",e.getSignal()); // decisive structure break qualifies as reversal
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
        assertEquals("PUT",e.getSignal());
        assertTrue(e.getReason().contains("LIQUIDITY SWEEP / STRUCTURE REVERSAL"));
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
        assertTrue(e.getReason().contains("LIQUIDITY SWEEP / STRUCTURE REVERSAL"));
    }

    @Test public void telemetryMemoryExcludesTheCurrentlyFormingQxCandle() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+420000,60);
        assertEquals(7,e.getAssetCandleCount());
        assertEquals(7,e.getHistogramBars().length());
    }

    @Test public void tenSecondSettlementUsesFirstRealTickAtOrAfterExpiry() throws Exception {
        SignalEngine e = new SignalEngine();
        e.acceptHistory("EURUSD_otc",60,history(bullishPullbackSetup()));
        e.acceptTick("EURUSD_otc",1.1012,T+480000,60);
        e.acceptTick("EURUSD_otc",1.1018,T+481000,60);
        assertEquals("CALL",e.getSignal());
        e.acceptTick("EURUSD_otc",1.1013,T+491999,60);
        assertEquals(0,e.getWins());
        assertEquals(0,e.getLosses());
        e.acceptTick("EURUSD_otc",1.1021,T+492000,60);
        assertEquals(1,e.getWins());
        e.acceptTick("EURUSD_otc",1.1015,T+493000,60);
        assertEquals(1,e.getWins());
    }
}
