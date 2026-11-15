package com.aadi.bot;

import static org.junit.Assert.*;
import org.junit.Test;

public class EntryQualityGateTest {
    private static final double ATR=0.0001;

    @Test public void blocksPutWhenShortSlopeTurnsStronglyBullishInsideBearTrend() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00001,
                0.636,-0.20,-0.25,-0.30,-0.20,42.0,-1.0);
        assertTrue(d.blocked);
        assertTrue(d.reason.contains("counter-momentum"));
    }

    @Test public void blocksPutWhenOversoldRsiAndFastMoveShowBullishRecovery() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,0.000030,
                -0.05,-0.02,-0.10,-0.05,-0.04,26.59,0.8);
        assertTrue(d.blocked);
    }

    @Test public void blocksPutWhenRsiIsDeeplyOversoldAndDownsideAccelerationIsWeak() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00001,
                0.038,-0.03,-0.10,-0.05,-0.04,20.17,-0.2);
        assertTrue(d.blocked);
    }

    @Test public void blocksCallWhenFastMoveTurnsBearishButRecentConfirmationIsWeak() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,-0.00002,
                0.03,0.01,0.02,0.02,0.01,55.0,-0.2);
        assertTrue(d.blocked);
    }

    @Test public void allowsPutWhenOversoldButStrongDownsideContinuationRemainsConfirmed() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(-1,ATR,-0.00004,
                -0.35,-0.30,-0.32,-0.28,-0.25,24.0,-2.0);
        assertFalse(d.blocked);
    }

    @Test public void blocksCallWhenOverboughtAndPriceMomentumRollsOver() {
        EntryQualityGate.Decision d=EntryQualityGate.evaluate(1,ATR,-0.00003,
                -0.20,-0.18,-0.22,-0.15,-0.16,78.0,-2.0);
        assertTrue(d.blocked);
    }
}
