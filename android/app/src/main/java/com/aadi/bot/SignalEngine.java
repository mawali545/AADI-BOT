package com.aadi.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Builds 10-second candles from received real page ticks and computes EMA/RSI/momentum confluence. */
public final class SignalEngine {
    public static final class Result {
        public final String asset, signal, reason;
        public final double price, emaFast, emaSlow, rsi, momentum;
        public final int score, closedCandles;
        Result(String a, String s, String why, double p, double ef, double es,
               double r, double m, int sc, int n) {
            asset=a; signal=s; reason=why; price=p; emaFast=ef; emaSlow=es;
            rsi=r; momentum=m; score=sc; closedCandles=n;
        }
    }

    private static final int FAST=9, SLOW=21, RSI_PERIOD=14, MOMENTUM=5;
    private static final int MIN_CONFLUENCE=3;
    private static final long FRAME_SECONDS=10;
    private final Deque<Double> closes = new ArrayDeque<>();
    private String asset;
    private long candleTime=-1;
    private double open, high, low, close, lastPrice;
    private int totalClosed=0;

    public synchronized Result onTick(String symbol, double price, double timestamp) {
        if (symbol == null || symbol.trim().isEmpty() || !Double.isFinite(price) || price <= 0)
            return snapshot("WAIT", "Invalid tick ignored");
        if (timestamp > 10000000000.0) timestamp /= 1000.0;
        long ts=(long)timestamp;
        if (ts <= 0) ts=System.currentTimeMillis()/1000L;
        if (asset == null || !asset.equals(symbol)) {
            asset=symbol; closes.clear(); candleTime=-1; totalClosed=0;
        }
        lastPrice=price;
        long bucket=(ts/FRAME_SECONDS)*FRAME_SECONDS;
        if (candleTime < 0) {
            startCandle(bucket, price);
            return snapshot("WAIT", "Collecting real candles");
        }
        if (bucket < candleTime) return snapshot("WAIT", "Out-of-order tick ignored");
        if (bucket > candleTime) {
            closes.addLast(close);
            while (closes.size()>500) closes.removeFirst();
            totalClosed++;
            startCandle(bucket, price);
        } else {
            high=Math.max(high,price); low=Math.min(low,price); close=price;
        }
        return calculate();
    }

    private void startCandle(long time,double p) {
        candleTime=time; open=p; high=p; low=p; close=p;
    }

    private Result calculate() {
        List<Double> values=new ArrayList<>(closes);
        if (values.size()<Math.max(SLOW,RSI_PERIOD+1)+1)
            return snapshot("WAIT","Need at least 22 closed real 10-second candles");
        double fast=ema(values,FAST), slow=ema(values,SLOW), rsi=rsi(values,RSI_PERIOD);
        double momentum=values.get(values.size()-1)-values.get(values.size()-1-MOMENTUM);
        int score=0;
        if(fast>slow) score++; else if(fast<slow) score--;
        if(rsi>=55) score++; else if(rsi<=45) score--;
        if(momentum>0) score++; else if(momentum<0) score--;
        String sig=score>=MIN_CONFLUENCE?"CALL":score<=-MIN_CONFLUENCE?"PUT":"WAIT";
        return new Result(asset,sig,"NEXT 10s candle: EMA 9/21 + RSI 14 + 5-candle momentum",
            lastPrice,fast,slow,rsi,momentum,score,totalClosed);
    }

    private double ema(List<Double> v,int period) {
        double e=0; for(int i=0;i<period;i++) e+=v.get(i); e/=period;
        double k=2.0/(period+1);
        for(int i=period;i<v.size();i++) e=v.get(i)*k+e*(1-k);
        return e;
    }

    private double rsi(List<Double> v,int period) {
        double gains=0,losses=0;
        int start=v.size()-period-1;
        for(int i=start+1;i<v.size();i++) {
            double change=v.get(i)-v.get(i-1);
            if(change>0) gains+=change; else losses-=change;
        }
        double avgGain=gains/period, avgLoss=losses/period;
        if(avgLoss==0) return 100.0;
        double rs=avgGain/avgLoss;
        return 100.0-(100.0/(1.0+rs));
    }

    private Result snapshot(String sig,String why) {
        return new Result(asset,sig,why,lastPrice,Double.NaN,Double.NaN,Double.NaN,
            Double.NaN,0,totalClosed);
    }
}
