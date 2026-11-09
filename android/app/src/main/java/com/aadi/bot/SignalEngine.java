package com.aadi.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Conservative real-tick 10-second market scanner.
 * It analyzes locally collected closed candles; it does not promise outcomes.
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
            return snapshot("WAIT", "Collecting genuine Market-QX ticks");
        }
        if (bucket < candleTime) return snapshot("WAIT", "Out-of-order tick ignored");
        if (bucket > candleTime) {
            candles.addLast(new Candle(open,high,low,close));
            while (candles.size()>600) candles.removeFirst();
            totalClosed++;
            startCandle(bucket, price);
            return calculate();
        }
        high=Math.max(high,price);
        low=Math.min(low,price);
        close=price;
        return calculate();
    }

    /** Called by the Analyze button; uses the latest genuine closed candles. */
    public synchronized Result analyzeNow() {
        if (candles.size()<MIN_CANDLES)
            return snapshot("WAIT","Need 30 closed real 10-second candles before analysis");
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
        double fast=ema(values,FAST), slow=ema(values,SLOW);
        double rsi=rsiWilder(values,RSI_PERIOD);
        double momentum=values.get(values.size()-1)-values.get(values.size()-1-MOMENTUM);
        double oldSlow=ema(values.subList(0,values.size()-5),SLOW);
        double slope=slow-oldSlow;
        double macd=ema(values,12)-ema(values,26);
        List<Double> macdSeries=new ArrayList<>();
        for(int i=25;i<values.size();i++)
            macdSeries.add(ema(values.subList(0,i+1),12)-ema(values.subList(0,i+1),26));
        double macdSignal=macdSeries.isEmpty()?0:ema(macdSeries,9);
        double macdHist=macd-macdSignal;

        int up=0, down=0;
        for(int i=cs.size()-4;i<cs.size();i++) {
            double b=cs.get(i).body();
            if(b>0) up++; else if(b<0) down++;
        }
        Candle last=cs.get(cs.size()-1);
        double avgRange=0;
        for(int i=cs.size()-14;i<cs.size();i++) avgRange+=cs.get(i).range();
        avgRange/=14.0;

        double path=0;
        int lookback=10;
        for(int i=cs.size()-lookback;i<cs.size();i++)
            path+=Math.abs(cs.get(i).close-cs.get(i).open);
        double net=Math.abs(cs.get(cs.size()-1).close-cs.get(cs.size()-lookback).open);
        double efficiency=path>0?net/path:0;

        double resistance=Double.NEGATIVE_INFINITY;
        double support=Double.POSITIVE_INFINITY;
        for(int i=Math.max(0,cs.size()-20);i<cs.size();i++) {
            resistance=Math.max(resistance,cs.get(i).high);
            support=Math.min(support,cs.get(i).low);
        }
        double closeNow=last.close;
        boolean nearResistance=avgRange>0 && resistance-closeNow<=avgRange*0.65;
        boolean nearSupport=avgRange>0 && closeNow-support<=avgRange*0.65;
        boolean spike=avgRange>0 && last.range()>avgRange*2.3;
        boolean flat=avgRange<=0 || efficiency<0.20 || Math.abs(momentum)<avgRange*0.35;
        boolean cleanBody=last.range()>0 && Math.abs(last.body())>=last.range()*0.30;

        boolean trendUp=fast>slow && slope>0;
        boolean trendDown=fast<slow && slope<0;
        boolean bullMomentum=momentum>0 && macdHist>0;
        boolean bearMomentum=momentum<0 && macdHist<0;
        boolean bullRsi=rsi>=52 && rsi<=68;
        boolean bearRsi=rsi<=48 && rsi>=32;
        boolean lastUp=last.body()>0;
        boolean lastDown=last.body()<0;

        int score=0;
        if(trendUp) score++; else if(trendDown) score--;
        if(bullMomentum) score++; else if(bearMomentum) score--;
        if(bullRsi) score++; else if(bearRsi) score--;
        if(up>=3) score++; else if(down>=3) score--;
        if(lastUp) score++; else if(lastDown) score--;

        String sig="WAIT";
        String why="WAIT: confirmations are not aligned";
        if(spike) why="WAIT: abnormal volatility spike";
        else if(flat) why="WAIT: choppy or sideways market";
        else if(!cleanBody) why="WAIT: weak candle body";
        else if(trendUp && bullMomentum && bullRsi && up>=3 && lastUp && !nearResistance && score>=4) {
            sig="CALL"; why="Bullish trend, MACD/momentum, RSI and candle structure agree; resistance clear";
        } else if(trendDown && bearMomentum && bearRsi && down>=3 && lastDown && !nearSupport && score<=-4) {
            sig="PUT"; why="Bearish trend, MACD/momentum, RSI and candle structure agree; support clear";
        } else if(nearResistance && trendUp) why="WAIT: price is too close to resistance";
        else if(nearSupport && trendDown) why="WAIT: price is too close to support";

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

    private double rsiWilder(List<Double> v,int period) {
        if(v.size()<=period) return 50;
        double gain=0, loss=0;
        for(int i=1;i<=period;i++) {
            double d=v.get(i)-v.get(i-1);
            if(d>0) gain+=d; else loss-=d;
        }
        double avgGain=gain/period, avgLoss=loss/period;
        for(int i=period+1;i<v.size();i++) {
            double d=v.get(i)-v.get(i-1);
            double g=Math.max(0,d), l=Math.max(0,-d);
            avgGain=((avgGain*(period-1))+g)/period;
            avgLoss=((avgLoss*(period-1))+l)/period;
        }
        if(avgLoss==0) return avgGain==0?50:100;
        double rs=avgGain/avgLoss;
        return 100.0-(100.0/(1.0+rs));
    }

    private Result snapshot(String sig,String why) {
        return new Result(asset,sig,why,lastPrice,Double.NaN,Double.NaN,Double.NaN,
            Double.NaN,0,totalClosed);
    }
}
