package com.aadi.bot;

import static org.junit.Assert.*;
import org.junit.Test;

public class EntryQualityGateTest {
    private static final double ATR=0.0001;

    @Test public void blocksPutWhenShortSlopeTurnsStronglyBullishInsideBearTrend() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00001,
                0.636,-0.20,-0.25,-0.30,-0.20,-0.20,42.0,-1.0);
        assertTrue(d.blocked);
        assertTrue(d.reason.contains("counter-momentum"));
    }

    @Test public void blocksPutWhenOversoldRsiAndFastMoveShowBullishRecovery() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,0.000030,
                -0.05,-0.04,-0.02,-0.10,-0.05,-0.04,26.59,0.8);
        assertTrue(d.blocked);
    }

    @Test public void blocksPutWhenRsiIsDeeplyOversoldAndDownsideAccelerationIsWeak() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00001,
                0.038,0.038,-0.03,-0.10,-0.05,-0.04,20.17,-0.2);
        assertTrue(d.blocked);
    }

    @Test public void blocksCallWhenFastMoveTurnsBearishButRecentConfirmationIsWeak() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,-0.00002,
                0.03,0.02,0.01,0.02,0.02,0.01,55.0,-0.2);
        assertTrue(d.blocked);
    }

    @Test public void allowsPutWhenOversoldButStrongDownsideContinuationRemainsConfirmed() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00004,
                -0.35,-0.30,-0.30,-0.32,-0.28,-0.25,24.0,-2.0);
        assertFalse(d.blocked);
    }

    @Test public void blocksCallWhenOverboughtAndPriceMomentumRollsOver() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,-0.00003,
                -0.20,-0.18,-0.18,-0.22,-0.15,-0.16,78.0,-2.0);
        assertTrue(d.blocked);
    }

    @Test public void blocksCallChasingResistanceWhenMomentumFades() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,0.00001,
                0.18,0.35,0.16,-0.12,0.02,0.01,83.65,-0.3,
                0.08,false,true);
        assertTrue(d.blocked);
        assertTrue(d.reason.contains("S/R proximity"));
    }

    @Test public void allowsConfirmedBullishBreakoutDespiteHighRsi() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,0.00004,
                0.35,0.30,0.32,0.10,0.25,0.20,83.65,0.5,
                0.04,true,false);
        assertFalse(d.blocked);
    }

    @Test public void blocksPutAtSupportWhenShortAndMediumMomentumDisagree() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00002,
                -1.337,0.373,-0.20,-0.05,-0.02,-0.01,34.87,-0.2,
                0.08,false,true);
        assertTrue(d.blocked);
    }

    @Test public void blocksCallJustBeyondResistanceWithoutConfirmedBreakout() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,0.00001,
                0.12,0.10,0.08,0.01,0.02,0.01,74.0,1.0,
                -0.20,false,false);
        assertTrue(d.blocked);
        assertTrue(d.reason.contains("S/R proximity"));
    }

    @Test public void doesNotBlockExtremeRsiByItselfAwayFromOpposingLevel() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,0.00004,
                0.30,0.28,0.25,0.10,0.20,0.18,83.0,0.5,
                0.80,false,false);
        assertFalse(d.blocked);
    }

    @Test public void legacyGateStillAllowsStrongAlignedOversoldContinuation() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00004,
                -0.35,-0.30,-0.30,-0.32,-0.28,-0.25,24.0,-2.0);
        assertFalse(d.blocked);
    }


    @Test public void executionTradeUsesTenSecondExpiryAndSettlesAtTenSeconds() throws Exception {
        FiveSecondSignalEngine engine=new FiveSecondSignalEngine();
        engine.requestScan();
        String audit="ATR 0.001 | forecast +0.500 | threshold 0.200";
        FiveSecondSignalEngine.Result issued=null;
        long start=100000L;
        for(long dt=0;dt<=3000;dt+=1000) {
            long t=start+dt;
            FiveSecondSignalEngine.Result current=engine.onTick("TEST",1.0+(dt/1000000.0),t,"CALL",0,"daily",audit);
            if(current.auditEvents.contains("ISSUED")) issued=current;
        }
        assertNotNull(issued);
        assertEquals("CALL",issued.signal);
        assertTrue(issued.auditEvents.contains("\"expirySeconds\":10"));
        long issuedAt=new JSONArray(issued.auditEvents).getJSONObject(0).getLong("entryTimeMs");
        FiveSecondSignalEngine.Result settled=null;
        for(long dt=3000;dt<=16000;dt+=1000) {
            long t=start+dt;
            FiveSecondSignalEngine.Result current=engine.onTick("TEST",1.0+(dt/1000000.0),t,"WAIT",0,"daily",audit);
            if(current.auditEvents.contains("SETTLED")) settled=current;
        }
        assertNotNull("The 10-second result must settle on a verified expiry quote",settled);
        assertTrue(settled.auditEvents.contains("\"expiryTimeMs\":"+(issuedAt+10000L)));
        assertTrue(settled.auditEvents.contains("SETTLED"));
    }
    @Test public void auditParserReadsExplicitPlusSigns() throws Exception {
        FiveSecondSignalEngine engine=new FiveSecondSignalEngine();
        java.lang.reflect.Method parse=FiveSecondSignalEngine.class
                .getDeclaredMethod("extractMetric",String.class,String.class);
        parse.setAccessible(true);
        String audit="slope5 +0.636 | fastMove +0.000030 | pressure +0.200 | flow +0.300"
                +" | RSI7 26.59 | RSI7delta +1.50 | recentBias +0.250";
        assertEquals(0.636,(Double)parse.invoke(engine,audit,"slope5"),0.000001);
        assertEquals(0.000030,(Double)parse.invoke(engine,audit,"fastMove"),0.0000001);
        assertEquals(1.50,(Double)parse.invoke(engine,audit,"RSI7delta"),0.000001);
        assertEquals(0.250,(Double)parse.invoke(engine,audit,"recentBias"),0.000001);
    }
}
