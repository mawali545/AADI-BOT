package com.aadi.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final int MIN_CANDLES=20;
    private static final long FRAME_SECONDS=10;

    private static final class Candle {
        final long time;
        final double open, high, low, close;
        Candle(long t,double o,double h,double l,double c) {
            time=t; open=o; high=h; low=l; close=c;
        }
        double range() { return Math.max(0, high-low); }
        double body() { return close-open; }
    }

    private final Deque<Candle> candles = new ArrayDeque<>();
    private String asset;
    private long candleTime=-1;
    private double open, high, low, close, lastPrice;
    private int totalClosed=0;
    private boolean awaitingLiveCandle=false;

    public synchronized Result onTick(String symbol, double price, double timestamp) {
        if (symbol == null || symbol.trim().isEmpty() || !Double.isFinite(price) || price <= 0)
            return snapshot("WAIT", "Invalid tick ignored");
        if (timestamp > 10000000000.0) timestamp /= 1000.0;
        long ts=(long)timestamp;
        if (ts <= 0) ts=System.currentTimeMillis()/1000L;
        if (asset == null || !asset.equals(symbol)) {
            asset=symbol; candles.clear(); candleTime=-1; totalClosed=0; awaitingLiveCandle=false;
        }
        lastPrice=price;
        long bucket=(ts/FRAME_SECONDS)*FRAME_SECONDS;
        if (awaitingLiveCandle) {
            if (bucket <= candleTime) return snapshot("WAIT", "Historical candles loaded; waiting for current live candle");
            startCandle(bucket, price);
            awaitingLiveCandle=false;
            return calculate();
        }
        if (candleTime < 0) {
            startCandle(bucket, price);
            return snapshot("WAIT", "Collecting genuine Market-QX ticks");
        }
        if (bucket < candleTime) return snapshot("WAIT", "Out-of-order tick ignored");
        if (bucket > candleTime) {
            candles.addLast(new Candle(candleTime,open,high,low,close));
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

    /**
     * Imports verified OHLC objects received from Market-QX history responses.
     * Only timestamped 10-second candles are accepted; malformed or other intervals are ignored.
     * Expected fields: timestamp,time, or ts; open/high/low/close (or o/h/l/c).
     */
    public synchronized int importHistoricalCandles(String symbol, JSONArray rows) {
        if (symbol == null || symbol.trim().isEmpty() || rows == null) return 0;
        TreeMap<Long,Candle> sorted = new TreeMap<>();
        for (int i=0; i<rows.length(); i++) {
            JSONObject row=rows.optJSONObject(i);
            if (row==null) continue;
            long t=(long)row.optDouble("timestamp",row.optDouble("time",row.optDouble("ts",0)));
            if (t>10000000000L) t/=1000L;
            if (t<=0) continue;
            double o=number(row,"open","o"), h=number(row,"high","h");
            double l=number(row,"low","l"), c=number(row,"close","c");
            if (!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)
                    || o<=0||h<=0||l<=0||c<=0||h<Math.max(o,c)||l>Math.min(o,c)||h<l) continue;
            long bucket=(t/FRAME_SECONDS)*FRAME_SECONDS;
            sorted.put(bucket,new Candle(bucket,o,h,l,c));
        }
        if (sorted.size()<MIN_CANDLES) return 0;
        Long previous=null;
        for(Long t:sorted.keySet()) {
            if(previous!=null && t-previous!=FRAME_SECONDS) return 0;
            previous=t;
        }
        long now=System.currentTimeMillis()/1000L;
        while(!sorted.isEmpty() && sorted.lastKey() >= (now/FRAME_SECONDS)*FRAME_SECONDS)
            sorted.pollLastEntry();
        if(sorted.size()<MIN_CANDLES) return 0;
        if(asset!=null && !asset.equals(symbol)) {
            candles.clear(); totalClosed=0;
        } else if(asset!=null && !candles.isEmpty()) {
            // Keep an existing live history rather than replacing it with a duplicate response.
            return 0;
        }
        asset=symbol;
        candles.clear();
        for(Candle candle:sorted.values()) {
            candles.addLast(candle);
            while(candles.size()>600) candles.removeFirst();
        }
        totalClosed=candles.size();
        candleTime=sorted.lastKey();
        lastPrice=sorted.lastEntry().getValue().close;
        awaitingLiveCandle=true;
        return candles.size();
    }

    private double number(JSONObject row,String longKey,String shortKey) {
        double v=row.has(longKey)?row.optDouble(longKey,Double.NaN):row.optDouble(shortKey,Double.NaN);
        return v;
    }

    private void startCandle(long time,double p) {
        candleTime=time; open=p; high=p; low=p; close=p;
    }

    private Result calculate() {
        List<Candle> cs=new ArrayList<>(candles);
        if(candleTime>=0 && close>0) cs.add(new Candle(candleTime,open,high,low,close));
        if(candles.size()<MIN_CANDLES || cs.size()<MIN_CANDLES+1)
            return snapshot("WAIT","Building real candle history");

        List<Double> values=new ArrayList<>();
        for(Candle c:cs) values.add(c.close);
        double fast=ema(values,9), slow=ema(values,21);
        double rsi=rsiWilder(values,14);
        double momentum=values.get(values.size()-1)-values.get(values.size()-4);
        double[] dmi=calculateDmi(cs,14);
        double plusDi=dmi[0], minusDi=dmi[1], adx=dmi[2];
        double macd=macdHistogram(values);
        Candle last=cs.get(cs.size()-1);
        double avgRange=0;
        for(int i=cs.size()-14;i<cs.size();i++) avgRange+=cs.get(i).range();
        avgRange/=14.0;

        double resistance=Double.NEGATIVE_INFINITY, support=Double.POSITIVE_INFINITY;
        for(int i=Math.max(0,cs.size()-18);i<cs.size()-1;i++) {
            resistance=Math.max(resistance,cs.get(i).high);
            support=Math.min(support,cs.get(i).low);
        }
        boolean nearResistance=avgRange>0 && resistance-last.close<=avgRange*0.35;
        boolean nearSupport=avgRange>0 && last.close-support<=avgRange*0.35;
        boolean spike=avgRange>0 && last.range()>avgRange*2.5;
        boolean flat=avgRange<=0 || Math.abs(momentum)<avgRange*0.18;
        boolean bullishCandle=last.close>last.open && last.range()>0
                && last.body()/last.range()>=0.18;
        boolean bearishCandle=last.close<last.open && last.range()>0
                && -last.body()/last.range()>=0.18;

        int bull=0, bear=0;
        if(fast>slow) bull+=20; else if(fast<slow) bear+=20;
        if(macd>0) bull+=20; else if(macd<0) bear+=20;
        if(rsi>=52 && rsi<=72) bull+=15; else if(rsi<=48 && rsi>=28) bear+=15;
        if(plusDi>minusDi && adx>=12) bull+=15; else if(minusDi>plusDi && adx>=12) bear+=15;
        if(momentum>0) bull+=15; else if(momentum<0) bear+=15;
        if(bullishCandle) bull+=15; else if(bearishCandle) bear+=15;

        int score=Math.max(bull,bear);
        String sig="WAIT", why="WAIT: mixed market direction";
        if(spike) why="WAIT: sudden volatility spike";
        else if(flat) why="WAIT: price is moving sideways";
        else if(bull>=70 && bull>bear+15 && !nearResistance) {
            sig="CALL"; why="CALL: bullish momentum confirmed by multiple factors";
        } else if(bear>=70 && bear>bull+15 && !nearSupport) {
            sig="PUT"; why="PUT: bearish momentum confirmed by multiple factors";
        } else if(nearResistance && bull>=55) why="WAIT: CALL blocked near resistance";
        else if(nearSupport && bear>=55) why="WAIT: PUT blocked near support";
        else if(bull>bear) why="WAIT: bullish setup forming — waiting for stronger entry";
        else if(bear>bull) why="WAIT: bearish setup forming — waiting for stronger entry";

        return new Result(asset,sig,why,lastPrice,fast,slow,rsi,momentum,score,totalClosed);
    }

    private double macdHistogram(List<Double> values) {
        double macdNow=ema(values,12)-ema(values,26);
        List<Double> line=new ArrayList<>();
        for(int i=25;i<values.size();i++) {
            List<Double> part=values.subList(0,i+1);
            line.add(ema(part,12)-ema(part,26));
        }
        double signal=ema(line,9);
        return macdNow-signal;
    }

    private double[] calculateDmi(List<Candle> cs,int period) {
        List<Double> dxValues=new ArrayList<>();
        double lastPlus=0,lastMinus=0;
        for(int end=period;end<cs.size();end++) {
            double trSum=0,plusSum=0,minusSum=0;
            int start=end-period+1;
            for(int i=start;i<=end;i++) {
                Candle cur=cs.get(i), prev=cs.get(i-1);
                double up=cur.high-prev.high, down=prev.low-cur.low;
                double plus=up>down && up>0 ? up : 0;
                double minus=down>up && down>0 ? down : 0;
                double tr=Math.max(cur.high-cur.low,
                        Math.max(Math.abs(cur.high-prev.close),Math.abs(cur.low-prev.close)));
                trSum+=tr; plusSum+=plus; minusSum+=minus;
            }
            if(trSum<=0) continue;
            lastPlus=100.0*plusSum/trSum;
            lastMinus=100.0*minusSum/trSum;
            double sum=lastPlus+lastMinus;
            if(sum>0) dxValues.add(100.0*Math.abs(lastPlus-lastMinus)/sum);
        }
        if(dxValues.isEmpty()) return new double[]{lastPlus,lastMinus,0};
        int from=Math.max(0,dxValues.size()-period);
        double total=0;
        for(int i=from;i<dxValues.size();i++) total+=dxValues.get(i);
        return new double[]{lastPlus,lastMinus,total/(dxValues.size()-from)};
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
