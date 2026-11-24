package com.dr.candletracker;

import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class AIScannerTest {
    private static final long T=1800000000000L;
    private JSONArray bullishHistory() throws Exception {
        StringBuilder s=new StringBuilder("[");
        double p=1.1000;
        for(int i=0;i<10;i++){
            double o=p,c=p+0.00020,h=c+0.00004,l=o-0.00003;
            if(i>0)s.append(",");
            s.append("{\"time\":").append(T+i*60000L).append(",\"open\":").append(o)
             .append(",\"close\":").append(c).append(",\"high\":").append(h).append(",\"low\":").append(l).append("}");
            p=c;
        }
        return new JSONArray(s.append("]").toString());
    }
    private JSONArray bearishHistory() throws Exception {
        StringBuilder s=new StringBuilder("[");
        double p=1.1030;
        for(int i=0;i<10;i++){
            double o=p,cl=p-0.00020,h=o+0.00003,l=cl-0.00004;
            if(i>0)s.append(",");
            s.append("{\"time\":").append(T+i*60000L).append(",\"open\":").append(o)
             .append(",\"close\":").append(cl).append(",\"high\":").append(h).append(",\"low\":").append(l).append("}");
            p=cl;
        }
        return new JSONArray(s.append("]").toString());
    }
    @Test public void waitsUntilRealHistoryAndEnoughLiveTicksExist() throws Exception {
        AIScanner a=new AIScanner();a.acceptHistory("EURUSD_otc",60,bullishHistory());
        assertEquals("WAIT",a.getSignal());
        a.acceptTick("EURUSD_otc",1.1020,T+600000,60,null);
        assertEquals("WAIT",a.getSignal());
    }
    @Test public void genuineLiveTicksBuildOhlcAndCanProduceSignalWithoutBrokerHistory() {
        AIScanner a=new AIScanner();
        for(int i=0;i<12;i++)a.acceptTick("EURUSD_otc",1.1000+i*0.0001,T+i*1000L,10,1);
        assertTrue(a.snapshot().optInt("tickDerivedCandlesAvailable")>=5);
        assertTrue(a.snapshot().optInt("ticksAvailable")>=8);
        assertTrue("Structure should classify moving flat tick-derived candles",a.snapshot().optString("structure").contains("BULLISH"));
        assertEquals("CALL",a.getSignal());
    }
    @Test public void strongBullishLiveFlowCreatesCallAndLocksEntryPrice() throws Exception {
        AIScanner a=new AIScanner();a.acceptHistory("EURUSD_otc",60,bullishHistory());
        for(int i=0;i<10;i++)a.acceptTick("EURUSD_otc",1.1020+i*0.00005,T+600001+i*100,60,1);
        assertEquals("CALL",a.getSignal());
        double lockedEntry=a.snapshot().optDouble("entryPrice");
        long lockedAt=a.snapshot().optLong("entryTimestamp");
        assertTrue(lockedEntry>=1.1020&&lockedEntry<=1.10245);
        assertTrue(lockedAt>=T+600001&&lockedAt<=T+600901);
        assertEquals(lockedAt+10000L,a.snapshot().optLong("expiryTimestamp"));
    }
    @Test public void tenSecondExpiryUsesFirstActualQuoteAtOrAfterEntryExpiry() throws Exception {
        AIScanner a=new AIScanner();a.acceptHistory("EURUSD_otc",60,bullishHistory());
        for(int i=0;i<10;i++)a.acceptTick("EURUSD_otc",1.1020+i*0.00005,T+600001+i*100,60,1);
        assertEquals("CALL",a.getSignal());
        long expiry=a.snapshot().optLong("expiryTimestamp");
        a.acceptTick("EURUSD_otc",1.1028,expiry-1,60,1);
        assertEquals("CALL",a.getSignal());
        a.acceptTick("EURUSD_otc",1.1029,expiry,60,1);
        assertEquals("WAIT",a.getSignal());
        assertEquals(1,a.getWins());
        assertEquals("WIN",a.snapshot().optString("lastResult"));
    }
    @Test public void putWinsOnlyWhenExpiryPriceIsBelowLockedEntry() throws Exception {
        AIScanner a=new AIScanner();a.acceptHistory("EURUSD_otc",60,bullishHistory());
        // Establish an initial sequence, then a decisive bearish flow on a separate selected asset.
        a.acceptHistory("GBPUSD_otc",60,bearishHistory());
        for(int i=0;i<10;i++)a.acceptTick("GBPUSD_otc",1.1020-i*0.00005,T+600001+i*100,60,-1);
        assertEquals("PUT",a.getSignal());
        double entry=a.snapshot().optDouble("entryPrice");
        long expiry=a.snapshot().optLong("expiryTimestamp");
        a.acceptTick("GBPUSD_otc",entry-0.0001,expiry,60,-1);
        assertEquals(1,a.getWins());
    }
    @Test public void staleDuplicateAndOlderTicksAreIgnored() throws Exception {
        AIScanner a=new AIScanner();a.acceptHistory("EURUSD_otc",60,bullishHistory());
        a.acceptTick("EURUSD_otc",1.1,T+600001,60,1);
        a.acceptTick("EURUSD_otc",1.2,T+600001,60,1);
        a.acceptTick("EURUSD_otc",1.0,T+600000,60,-1);
        assertEquals(1.1,a.snapshot().optDouble("lastPrice"),0.00000001);
    }
}
