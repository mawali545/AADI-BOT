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
  e.acceptHistory("EURUSD_otc",60,new JSONArray("[[1800000000000,1.1000,1.1008,1.1010,1.0998],[1800000060000,1.1008,1.1016,1.1018,1.1006],[1800000120000,1.1016,1.1024,1.1026,1.1014],[1800000180000,1.1024,1.1032,1.1034,1.1022]]"));
  e.acceptTick("EURUSD_otc",1.1033,t+240000,60);
  assertEquals("CALL",e.getSignal());assertEquals(0,e.getWins());assertEquals(0,e.getLosses());
  e.acceptTick("EURUSD_otc",1.1035,t+249999,60);
  assertEquals(0,e.getWins());assertEquals(0,e.getLosses());
  e.acceptTick("EURUSD_otc",1.1036,t+250000,60);
  assertEquals(1,e.getWins());assertEquals(0,e.getLosses());
  e.acceptTick("EURUSD_otc",1.1037,t+251000,60);
  assertEquals(1,e.getWins());
 }
 @Test public void putIsSettledAsLossWhenExpiryPriceRises(){
  SignalEngine e=new SignalEngine();long t=1800000000000L;
  e.acceptHistory("EURUSD_otc",60,new JSONArray("[[1800000000000,1.1040,1.1032,1.1042,1.1030],[1800000060000,1.1032,1.1024,1.1034,1.1022],[1800000120000,1.1024,1.1016,1.1026,1.1014],[1800000180000,1.1016,1.1008,1.1018,1.1006]]"));
  e.acceptTick("EURUSD_otc",1.1007,t+240000,60);
  assertEquals("PUT",e.getSignal());
  e.acceptTick("EURUSD_otc",1.1009,t+250000,60);
  assertEquals(0,e.getWins());assertEquals(1,e.getLosses());
 }

}
