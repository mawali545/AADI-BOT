package com.dr.candletracker;
import org.junit.Test;
import static org.junit.Assert.*;
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
}
