package com.dr.candletracker;

import static org.junit.Assert.*;
import org.junit.Test;

public class QxMarketAwarenessTest {
    @Test public void recognizesDirectionalPriceStructure() {
        double[] o={1.000,1.010,1.020,1.030,1.040,1.050,1.060,1.070};
        double[] h={1.012,1.022,1.032,1.042,1.052,1.062,1.072,1.082};
        double[] l={0.998,1.008,1.018,1.028,1.038,1.048,1.058,1.068};
        double[] c={1.010,1.020,1.030,1.040,1.050,1.060,1.070,1.080};
        QxMarketAwareness.Context result=QxMarketAwareness.analyze(o,h,l,c);
        assertEquals("DIRECTIONAL TREND", result.regime);
        assertTrue(result.bias > 0.5);
    }

    @Test public void identifiesRotationalChopInsteadOfInventingDirection() {
        double[] o={1.000,1.004,1.000,1.004,1.000,1.004,1.000,1.004};
        double[] h={1.010,1.010,1.010,1.010,1.010,1.010,1.010,1.010};
        double[] l={0.990,0.990,0.990,0.990,0.990,0.990,0.990,0.990};
        double[] c={1.004,1.000,1.004,1.000,1.004,1.000,1.004,1.000};
        QxMarketAwareness.Context result=QxMarketAwareness.analyze(o,h,l,c);
        assertEquals("ROTATIONAL / CHOP", result.regime);
        assertTrue(result.choppy);
        assertTrue(Math.abs(result.bias) < 0.24);
    }
}
