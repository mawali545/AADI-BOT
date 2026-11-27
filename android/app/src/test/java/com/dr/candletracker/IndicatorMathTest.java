package com.dr.candletracker;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class IndicatorMathTest {
    private List<SignalEngine.Candle> bars(double[] closes) {
        List<SignalEngine.Candle> out = new ArrayList<>();
        for (int i=0;i<closes.length;i++) {
            double c=closes[i], o=i==0?c:closes[i-1];
            out.add(new SignalEngine.Candle("EURUSD_otc", i*10000L,
                o, Math.max(o,c)+0.0001, Math.min(o,c)-0.0001, c));
        }
        return out;
    }

    @Test public void risingSeriesProducesBullishEmaAndRisingRsi() {
        double[] c={1.00,1.01,1.02,1.015,1.025,1.03,1.04,1.035,1.05,1.06,1.07,1.08};
        IndicatorMath.Result r=IndicatorMath.calculate(bars(c));
        assertTrue(r.ready);
        assertTrue(r.emaBull);
        assertTrue(r.rsiRising);
        assertTrue(r.rsi7>50.0);
    }

    @Test public void fallingSeriesProducesBearishEmaAndFallingRsi() {
        double[] c={1.08,1.07,1.06,1.065,1.055,1.05,1.04,1.045,1.03,1.02,1.01,1.00};
        IndicatorMath.Result r=IndicatorMath.calculate(bars(c));
        assertTrue(r.ready);
        assertTrue(r.emaBear);
        assertTrue(r.rsiFalling);
        assertTrue(r.rsi7<50.0);
    }

    @Test public void insufficientHistoryDoesNotInventIndicatorValues() {
        IndicatorMath.Result r=IndicatorMath.calculate(bars(new double[]{1,1.1,1.2,1.1,1.2,1.3,1.2,1.3}));
        assertFalse(r.ready);
        assertTrue(Double.isNaN(r.rsi7));
    }

    @Test public void flatMarketHasNeutralRsiInsteadOfDivisionError() {
        IndicatorMath.Result r=IndicatorMath.calculate(bars(new double[]{1,1,1,1,1,1,1,1,1,1}));
        assertTrue(r.ready);
        assertEquals(50.0,r.rsi7,0.000001);
    }
}
