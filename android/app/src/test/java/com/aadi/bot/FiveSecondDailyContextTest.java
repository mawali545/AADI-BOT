package com.aadi.bot;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class FiveSecondDailyContextTest {
    @Test public void previousDayAndTodayReferencesNeedCompleteHistory() throws Exception {
        DailyContextTracker tracker=new DailyContextTracker();
        long now=System.currentTimeMillis()/1000L;
        long offset=java.util.TimeZone.getDefault().getOffset(now*1000L)/1000L;
        long today=Math.floorDiv(now+offset,86400L)*86400L-offset;
        JSONArray rows=new JSONArray();
        double lastClose=1.1000;
        for(int i=0;i<8640;i++){
            long stamp=today-86400L+i*10L;
            double open=1.1000+i*0.0000001;
            double close=open+0.00000005;
            rows.put(candle(stamp,open,close+0.00000002,open-0.00000002,close));
            lastClose=close;
        }
        rows.put(candle(today,lastClose,lastClose+0.0001,lastClose-0.0001,lastClose+0.00005));
        tracker.importHistory("EURUSD_otc",rows);
        assertTrue(tracker.isReady("EURUSD_otc",now));
        assertEquals(1,tracker.bias("EURUSD_otc",1.1020,now));
        assertTrue(tracker.summary("EURUSD_otc",1.1020,now).contains("YESTERDAY O/C"));
    }

    @Test public void fiveSecondSignalRequiresTrendDailyAndTickAgreement() {
        FiveSecondSignalEngine e=new FiveSecondSignalEngine();
        long t=System.currentTimeMillis();
        e.onTick("EURUSD_otc",1.10000,t,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10001,t+1000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10002,t+2000,"CALL",1,"daily aligned","base trend CALL");
        FiveSecondSignalEngine.Result r=e.onTick("EURUSD_otc",1.10003,t+3000,"CALL",1,"daily aligned","base trend CALL");
        assertEquals("CALL",r.signal);
        e.onTick("EURUSD_otc",1.10004,t+4000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.100045,t+5000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.100046,t+6000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.100047,t+7000,"CALL",1,"daily aligned","base trend CALL");
        FiveSecondSignalEngine.Result settled=e.onTick("EURUSD_otc",1.10005,t+8000,"CALL",1,"daily aligned","base trend CALL");
        assertTrue(settled.analysis.contains("5s WIN"));
    }

    @Test public void fiveSecondSignalWinsIfExpiryRecoversAfterTemporaryEntryCross() {
        FiveSecondSignalEngine e=new FiveSecondSignalEngine();
        long t=System.currentTimeMillis();
        e.onTick("EURUSD_otc",1.10000,t,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10001,t+1000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10002,t+2000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10003,t+3000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.09999,t+4000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10004,t+5000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10004,t+6000,"CALL",1,"daily aligned","base trend CALL");
        e.onTick("EURUSD_otc",1.10004,t+7000,"CALL",1,"daily aligned","base trend CALL");
        FiveSecondSignalEngine.Result r=e.onTick("EURUSD_otc",1.10005,t+8000,"CALL",1,"daily aligned","base trend CALL");
        assertTrue(r.analysis.contains("5s WIN"));
        assertTrue(r.analysis.contains("crossed entry during hold"));
    }

    @Test public void opposingDailyBiasDoesNotBlockLiveConfirmedCall() {
        FiveSecondSignalEngine e=new FiveSecondSignalEngine();
        long t=System.currentTimeMillis();
        e.onTick("EURUSD_otc",1.1,t,"CALL",-1,"daily bearish","base trend CALL");
        e.onTick("EURUSD_otc",1.1001,t+1000,"CALL",-1,"daily bearish","base trend CALL");
        e.onTick("EURUSD_otc",1.1002,t+2000,"CALL",-1,"daily bearish","base trend CALL");
        FiveSecondSignalEngine.Result r=e.onTick("EURUSD_otc",1.1003,t+3000,"CALL",-1,"daily bearish","base trend CALL");
        assertEquals("CALL",r.signal);
        assertFalse(r.reason.toLowerCase().contains("daily"));
        assertTrue(r.analysis.contains("daily bearish"));
    }

    @Test public void blocksCallWhenBearishMacdAndNegativeFlowConflictWithBullTrend() {
        FiveSecondSignalEngine e=new FiveSecondSignalEngine();
        long t=System.currentTimeMillis();
        String audit="MARKET TREND: UPTREND (UPTREND WITH PULLBACK CHECK)\\n"
                +"MOMENTUM: RSI14 61.4 | MACD BEARISH | ADX 32.6 | EMA stack BULLISH\\n"
                +"FINAL CHECK: forecast +0.42 | threshold 0.26 | votes bull 3 / bear 0\\n"
                +"CALC AUDIT: ATR 0.00015786 | reversalBias +0.197 | forecast +0.424 | threshold 0.258\\n"
                +"TREND/FLOW: slope5 +0.013 | slope12 +2.159 | MACDvote -0.582 | DI+ 33.03 | DI- 16.74 | ADX 32.64 | DMIvote +0.434 | Supertrend 1 | dominant 1\\n"
                +"PRICE ACTION: fastMove +0.00018000 | pressure +0.377 | flow -0.133";
        e.onTick("NZDCHF_otc",0.46420,t,"CALL",0,"daily WAIT",audit);
        e.onTick("NZDCHF_otc",0.46421,t+1000,"CALL",0,"daily WAIT",audit);
        e.onTick("NZDCHF_otc",0.46422,t+2000,"CALL",0,"daily WAIT",audit);
        FiveSecondSignalEngine.Result result=e.onTick("NZDCHF_otc",0.46423,t+3000,"CALL",0,"daily WAIT",audit);
        assertEquals("WAIT",result.signal);
        assertTrue(result.reason.contains("momentum/price-action conflict"));
    }

    @Test public void blocksCallAfterStrongUnconfirmedCounterMove() {
        FiveSecondSignalEngine e=new FiveSecondSignalEngine();
        long t=System.currentTimeMillis();
        String audit="MARKET TREND: UPTREND\\nMOMENTUM: RSI14 62.5 | MACD BULLISH | ADX 43.5 | EMA stack BULLISH\\n"
                +"FINAL CHECK: forecast +0.38 | threshold 0.26\\n"
                +"CALC AUDIT: ATR 0.00016000 | reversalBias +0.039 | forecast +0.382 | threshold 0.261\\n"
                +"TREND/FLOW: slope5 +2.613 | slope12 +1.953 | MACDvote +1.000 | DI+ 43.75 | DI- 20.98 | DMIvote +0.651 | Supertrend 1 | dominant 1\\n"
                +"PRICE ACTION: fastMove -0.00014000 | pressure +0.381 | flow +0.280";
        e.onTick("NZDCHF_otc",0.46420,t,"CALL",0,"daily WAIT",audit);
        e.onTick("NZDCHF_otc",0.46421,t+1000,"CALL",0,"daily WAIT",audit);
        e.onTick("NZDCHF_otc",0.46422,t+2000,"CALL",0,"daily WAIT",audit);
        FiveSecondSignalEngine.Result result=e.onTick("NZDCHF_otc",0.46423,t+3000,"CALL",0,"daily WAIT",audit);
        assertEquals("WAIT",result.signal);
    }

    private JSONObject candle(long t,double o,double h,double l,double c) throws Exception {
        return new JSONObject().put("timestamp",t).put("open",o).put("high",h).put("low",l).put("close",c);
    }
}
