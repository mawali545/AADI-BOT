package com.dr.candletracker;

import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class MarketScannerTest {
    private static final long T=1800000000000L;

    @Test public void scansBrokerHistoryAndLiveTicksIncrementally() throws Exception {
        JSONArray bars=new JSONArray("["
            +"{\"time\":"+T+",\"open\":1.1000,\"close\":1.1002,\"high\":1.1003,\"low\":1.0999},"
            +"{\"time\":"+(T+60000)+",\"open\":1.1002,\"close\":1.1005,\"high\":1.1006,\"low\":1.1001},"
            +"{\"time\":"+(T+120000)+",\"open\":1.1005,\"close\":1.1008,\"high\":1.1009,\"low\":1.1004},"
            +"{\"time\":"+(T+180000)+",\"open\":1.1008,\"close\":1.1009,\"high\":1.1010,\"low\":1.1007}]");
        MarketScanner scanner=new MarketScanner();
        scanner.acceptHistory("EURUSD_otc",bars);
        scanner.acceptTick("EURUSD_otc",1.1010,T+181000);
        scanner.acceptTick("EURUSD_otc",1.1012,T+182000);
        org.json.JSONObject s=scanner.snapshot();
        assertEquals("EURUSD_otc",s.getString("asset"));
        assertEquals(4,s.getInt("candlesAvailable"));
        assertEquals(2,s.getInt("ticksAvailable"));
        assertEquals(4,s.getInt("candlesScanned"));
        assertEquals(1,s.getLong("fullHistoryScans"));
        assertEquals(4,s.getInt("recentCandlesAnalyzed"));
        assertTrue(s.getInt("ticksScanned")>0);
        assertTrue(s.getLong("updates")>=3);
        assertTrue(s.getDouble("lastScanUs")>=0);
        assertTrue(s.getDouble("avgScanUs")>=0);
    }


    @Test public void scansAllNineHundredTicksAndBuildsTickDerivedBarsWithoutHistory() throws Exception {
        MarketScanner scanner=new MarketScanner();
        for(int i=0;i<900;i++){
            double price=1.1000+((i%24)-12)*0.00001+(i*0.0000001);
            scanner.acceptTick("CADCHF_otc",price,T+i*100L);
        }
        org.json.JSONObject s=scanner.snapshot();
        assertEquals(900,s.getInt("ticksAvailable"));
        assertEquals(900,s.getInt("ticksScanned"));
        assertEquals(0,s.getInt("candlesAvailable"));
        assertTrue(s.getInt("tickBarsAvailable")>0);
        assertTrue(s.getInt("candlesScanned")>0);
        assertEquals(0,s.getLong("historyLoads"));
        assertTrue(s.getString("historyStatus").contains("TICK-DERIVED"));
        assertTrue(s.getDouble("lastScanUs")>=0);
    }

    @Test public void rejectsOlderTicksAndKeepsRealDataOnly() throws Exception {
        MarketScanner scanner=new MarketScanner();
        scanner.acceptTick("EURUSD_otc",1.1,T+2000);
        scanner.acceptTick("EURUSD_otc",1.2,T+1000);
        org.json.JSONObject s=scanner.snapshot();
        assertEquals(1,s.getInt("ticksAvailable"));
        assertEquals(T+2000,s.getLong("lastTimestamp"));
    }

    @Test public void switchesScannerStateWhenSelectedAssetChanges() throws Exception {
        MarketScanner scanner=new MarketScanner();
        scanner.acceptTick("EURUSD_otc",1.1,T+1000);
        scanner.acceptTick("GBPUSD_otc",1.2,T+2000);
        org.json.JSONObject s=scanner.snapshot();
        assertEquals("GBPUSD_otc",s.getString("asset"));
        assertEquals(1,s.getInt("ticksAvailable"));
    }
}
