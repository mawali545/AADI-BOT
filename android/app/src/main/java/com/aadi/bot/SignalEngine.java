package com.aadi.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Conservative 10-second candle scanner. It prioritizes avoiding weak entries;
 * it does not claim or guarantee predictive accuracy.
 */
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
    private static final int MIN_CANDLES=30;
    private static final int MIN_SCORE=4;
    private static final long FRAME_SECONDS=10;

    private static final class Candle {
        final double open, high, low, close;
        Candle(double o,double h,double l,double c) {
            open=o; high=h; low=l; close=c;
        }
        double range() { return Math.max(0, high-low); }
        double body() { return close-open; }
    }

    private final Deque<Candle> candles = new ArrayDeque<>();
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
            asset=symbol; candles.clear(); candleTime=-1; totalClosed=0;
        }
        lastPrice=price;
        long bucket=(ts/FRAME_SECONDS)*FRAME_SECONDS;
        if (candleTime < 0) {
            startCandle(bucket, price);
            return snapshot("WAIT", "Collecting real 10-second candles");
        }
        if (bucket < candleTime) return snapshot("WAIT", "Out-of-order tick ignored");
        if (bucket > candleTime) {
            candles.addLast(new Candle(open,high,low,close));
            while (candles.size()>500) candles.removeFirst();
            totalClosed++;
            startCandle(bucket, price);
            return calculate();
        }
        high=Math.max(high,price);
        low=Math.min(low,price);
        close=price;
        return calculate();
    }

    private void startCandle(long time,double p) {
        candleTime=time; open=p; high=p; low=p; close=p;
    }

    private Result calculate() {
        List<Candle> cs=new ArrayList<>(candles);
        if (cs.size()<MIN_CANDLES)
            return snapshot("WAIT","Need 30 closed real 10-second candles");
        List<Double> values=new ArrayList<>();
        for(Candle c:cs) values.add(c.close);

        double fast=ema(values,FAST);
        double slow=ema(values,SLOW);
        double rsi=rsi(values,RSI_PERIOD);
        double momentum=values.get(values.size()-1)-values.get(values.size()-1-MOMENTUM);
        double oldSlow=ema(values.subList(0,values.size()-5),SLOW);
        double slope=slow-oldSlow;

        int up=0, down=0;
        for(int i=cs.size()-4;i<cs.size();i++) {
            double body=cs.get(i).body();
            if(body>0) up++;
            else if(body<0) down++;
        }
        Candle last=cs.get(cs.size()-1);

        double avgRange=0;
        for(int i=cs.size()-14;i<cs.size();i++) avgRange+=cs.get(i).range();
        avgRange/=14.0;
        boolean spike=avgRange>0 && last.range()>avgRange*2.3;
        boolean flat=avgRange<=0 || Math.abs(momentum)<avgRange*0.35;
        boolean cleanBody=last.range()>0 && Math.abs(last.body())>=last.range()*0.25;

        int score=0;
        boolean trendUp=fast>slow && slope>0;
        boolean trendDown=fast<slow && slope<0;
        if(trendUp) score++; else if(trendDown) score--;
        if(rsi>=52 && rsi<=68) score++;
        else if(rsi<=48 && rsi>=32) score--;
        if(momentum>0) score++; else if(momentum<0) score--;
        if(up>=3) score++; else if(down>=3) score--;

        boolean lastUp=last.body()>0;
        boolean lastDown=last.body()<0;
        if(lastUp) score++; else if(lastDown) score--;

        String sig="WAIT";
        String why="Filters not aligned";
        if(spike) why="WAIT: abnormal volatility spike";
        else if(flat) why="WAIT: insufficient movement / sideways market";
        else if(!cleanBody) why="WAIT: weak or indecisive last candle";
        else if(score>=MIN_SCORE && trendUp && up>=3 && lastUp && rsi>=52 && rsi<=68) {
            sig="CALL"; why="Confirmed bullish trend + momentum + candle structure";
        } else if(score<=-MIN_SCORE && trendDown && down>=3 && lastDown && rsi<=48 && rsi>=32) {
            sig="PUT"; why="Confirmed bearish trend + momentum + candle structure";
        }
        return new Result(asset,sig,why,lastPrice,fast,slow,rsi,momentum,score,totalClosed);
    }

    private double ema(List<Double> v,int period) {
        if(v.size()<period) return v.get(v.size()-1);
        double e=0;
        for(int i=0;i<period;i++) e+=v.get(i);
        e/=period;
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
