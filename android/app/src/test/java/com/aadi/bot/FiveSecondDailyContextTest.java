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
        rows.put(candle(today-86400L,1.1000,1.1010,1.0990,1.1005));
        rows.put(candle(today-10L,1.1005,1.1020,1.1000,1.1015));
        rows.put(candle(today,1.1015,1.1020,1.1010,1.1018));
        tracker.importHistory("EURUSD_otc",rows);
        assertTrue(tracker.isReady("EURUSD_otc",now));
        assertEquals(1,tracker.bias("EURUSD_otc",1.1020,now));
        assertTrue(tracker.summary("EURUSD_otc",1.1020,now).contains("YESTERDAY O/C"));
    }

    @Test public void fiveSecondSignalRequiresTrendDailyAndTickAgreement() {
        FiveSecondSignalEngine e=new FiveSecondSignalEngine();
        long t=System.currentTimeMillis();
        e.onTick("EURUSD_otc",1.10000,t,"CALL",1,"daily aligned");
        e.onTick("EURUSD_otc",1.10001,t+1000,"CALL",1,"daily aligned");
        e.onTick("EURUSD_otc",1.10002,t+2000,"CALL",1,"daily aligned");
        FiveSecondSignalEngine.Result r=e.onTick("EURUSD_otc",1.10003,t+3000,"CALL",1,"daily aligned");
        assertEquals("CALL",r.signal);
        FiveSecondSignalEngine.Result settled=e.onTick("EURUSD_otc",1.10005,t+8000,"CALL",1,"daily aligned");
        assertTrue(settled.analysis.contains("5s WIN"));
    }

    @Test public void dailyConflictBlocksFiveSecondCall() {
        FiveSecondSignalEngine e=new FiveSecondSignalEngine();
        long t=System.currentTimeMillis();
        e.onTick("EURUSD_otc",1.1,t,"CALL",-1,"daily bearish");
        e.onTick("EURUSD_otc",1.1001,t+1000,"CALL",-1,"daily bearish");
        e.onTick("EURUSD_otc",1.1002,t+2000,"CALL",-1,"daily bearish");
        FiveSecondSignalEngine.Result r=e.onTick("EURUSD_otc",1.1003,t+3000,"CALL",-1,"daily bearish");
        assertEquals("WAIT",r.signal);
        assertTrue(r.reason.contains("Daily context conflicts"));
    }

    private JSONObject candle(long t,double o,double h,double l,double c) throws Exception {
        return new JSONObject().put("timestamp",t).put("open",o).put("high",h).put("low",l).put("close",c);
    }
}
