package com.dr.candletracker;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONArray;
public class SignalEngineTest {
 @Test public void waitsUntilThreeRealCandlesAreCompleted(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  e.acceptTick("EURUSD_otc",1.1000,t);e.acceptTick("EURUSD_otc",1.1002,t+1);
  assertEquals("WAIT",e.getSignal());assertEquals(1,e.getCandleCount());
 }
 @Test public void buildsOhlcOnlyFromAcceptedTicks(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  e.acceptTick("EURUSD_otc",1.1000,t);e.acceptTick("EURUSD_otc",1.1005,t+1000);e.acceptTick("EURUSD_otc",1.0995,t+2000);
  assertEquals(1.1000,e.getOpen(),0.0000001);assertEquals(1.1005,e.getHigh(),0.0000001);
  assertEquals(1.0995,e.getLow(),0.0000001);assertEquals(1.0995,e.getClose(),0.0000001);
 }
 @Test public void ignoresOlderTicks(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  e.acceptTick("EURUSD_otc",1.1000,t);e.acceptTick("EURUSD_otc",1.1010,t+1000);e.acceptTick("EURUSD_otc",1.0900,t-1000);
  assertEquals(1.1010,e.getLastPrice(),0.0000001);assertEquals(1.1010,e.getClose(),0.0000001);
 }
 @Test public void rejectsInvalidInputs(){
  SignalEngine e=new SignalEngine();e.acceptTick("EURUSD_otc",-1,1800000000000L);e.acceptTick("EURUSD_otc",1.1,0);e.acceptTick("bad asset",1.1,1800000000000L);
  assertEquals(0,e.getCandleCount());
 }

 @Test public void callIsSettledFromFirstRealTickAtTenSecondExpiry(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  e.acceptTick("EURUSD_otc",1.1000,t,60);e.acceptTick("EURUSD_otc",1.1008,t+1000,60);
  e.acceptTick("EURUSD_otc",1.1008,t+60000,60);e.acceptTick("EURUSD_otc",1.1016,t+61000,60);
  e.acceptTick("EURUSD_otc",1.1016,t+120000,60);e.acceptTick("EURUSD_otc",1.1024,t+121000,60);
  e.acceptTick("EURUSD_otc",1.1024,t+180000,60);
  assertEquals("CALL",e.getSignal());assertEquals(0,e.getWins());assertEquals(0,e.getLosses());
  e.acceptTick("EURUSD_otc",1.1025,t+189999,60);
  assertEquals(0,e.getWins());assertEquals(0,e.getLosses());
  e.acceptTick("EURUSD_otc",1.1026,t+190000,60);
  assertEquals(1,e.getWins());assertEquals(0,e.getLosses());
  e.acceptTick("EURUSD_otc",1.1027,t+191000,60);
  assertEquals(1,e.getWins());
 }
 @Test public void putIsSettledAsLossWhenExpiryPriceRises(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  e.acceptTick("EURUSD_otc",1.1040,t,60);e.acceptTick("EURUSD_otc",1.1032,t+1000,60);
  e.acceptTick("EURUSD_otc",1.1032,t+60000,60);e.acceptTick("EURUSD_otc",1.1024,t+61000,60);
  e.acceptTick("EURUSD_otc",1.1024,t+120000,60);e.acceptTick("EURUSD_otc",1.1016,t+121000,60);
  e.acceptTick("EURUSD_otc",1.1016,t+180000,60);
  assertEquals("PUT",e.getSignal());
  e.acceptTick("EURUSD_otc",1.1018,t+190000,60);
  assertEquals(0,e.getWins());assertEquals(1,e.getLosses());
 }

 @Test public void importsRealHistoryObjectsAndGeneratesProbabilityCall(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  JSONArray rows=new JSONArray("["
   +"{\"time\":"+t+",\"open\":1.1000,\"close\":1.1006,\"high\":1.1007,\"low\":1.0999},"
   +"{\"time\":"+(t+60000)+",\"open\":1.1008,\"close\":1.1014,\"high\":1.1015,\"low\":1.1007},"
   +"{\"time\":"+(t+120000)+",\"open\":1.1016,\"close\":1.1022,\"high\":1.1023,\"low\":1.1015},"
   +"{\"time\":"+(t+180000)+",\"open\":1.1024,\"close\":1.1030,\"high\":1.1031,\"low\":1.1023},"
   +"{\"time\":"+(t+240000)+",\"open\":1.1032,\"close\":1.1038,\"high\":1.1039,\"low\":1.1031},"
   +"{\"time\":"+(t+300000)+",\"open\":1.1040,\"close\":1.1046,\"high\":1.1047,\"low\":1.1039}]");
  e.acceptHistory("EURUSD_otc",60,rows);
  assertEquals(6,e.getCandleCount());
  e.acceptTick("EURUSD_otc",1.1046,t+360000,60);
  assertEquals("CALL",e.getSignal());
  assertTrue(e.getSignalProbability()>50);
  assertTrue(e.getReason().contains("bull"));
 }
 @Test public void choppyAlternatingRealCandlesStayWait(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  JSONArray rows=new JSONArray("["
   +"{\"time\":"+t+",\"open\":1.1000,\"close\":1.10002,\"high\":1.1005,\"low\":1.0995},"
   +"{\"time\":"+(t+60000)+",\"open\":1.10002,\"close\":1.1000,\"high\":1.10052,\"low\":1.0995},"
   +"{\"time\":"+(t+120000)+",\"open\":1.1000,\"close\":1.10002,\"high\":1.1005,\"low\":1.09948},"
   +"{\"time\":"+(t+180000)+",\"open\":1.10002,\"close\":1.1000,\"high\":1.10052,\"low\":1.0995},"
   +"{\"time\":"+(t+240000)+",\"open\":1.1000,\"close\":1.10002,\"high\":1.1005,\"low\":1.09948},"
   +"{\"time\":"+(t+300000)+",\"open\":1.10002,\"close\":1.1000,\"high\":1.10052,\"low\":1.0995}]");
  e.acceptHistory("EURUSD_otc",60,rows);
  e.acceptTick("EURUSD_otc",1.1000,t+360000,60);
  assertEquals("WAIT",e.getSignal());
 }

}
