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
        double bodyAbs() { return Math.abs(close-open); }
    }

    private final Deque<Candle> candles = new ArrayDeque<>();
    private String asset;
    private long candleTime=-1;
    private double open, high, low, close, lastPrice;
    private int totalClosed=0;
    private boolean awaitingLiveCandle=false;
    // One prediction is calculated at candle close and held for the entire next candle.
    private Result lockedNextCandleResult=null;

    public synchronized Result onTick(String symbol, double price, double timestamp) {
        if (symbol == null || symbol.trim().isEmpty() || !Double.isFinite(price) || price <= 0)
            return snapshot("WAIT", "Invalid tick ignored");
        if (timestamp > 10000000000.0) timestamp /= 1000.0;
        long ts=(long)timestamp;
        if (ts <= 0) ts=System.currentTimeMillis()/1000L;
        if (asset == null || !asset.equals(symbol)) {
            asset=symbol; candles.clear(); candleTime=-1; totalClosed=0;
            awaitingLiveCandle=false; lockedNextCandleResult=null;
        }
        lastPrice=price;
        long bucket=(ts/FRAME_SECONDS)*FRAME_SECONDS;
        if (awaitingLiveCandle) {
            if (bucket <= candleTime) return snapshot("WAIT", "Historical candles loaded; waiting for live candle close");
            startCandle(bucket, price);
            awaitingLiveCandle=false;
            // The imported history already contains closed candles. Calculate
            // immediately at this first live boundary and lock for this candle.
            lockedNextCandleResult=calculateClosedCandles();
            return lockedNextCandleResult;
        }
        if (candleTime < 0) {
            startCandle(bucket, price);
            lockedNextCandleResult=snapshot("WAIT", "Collecting genuine Market-QX ticks");
            return lockedNextCandleResult;
        }
        if (bucket < candleTime) return lockedNextCandleResult != null
                ? lockedNextCandleResult : snapshot("WAIT", "Out-of-order tick ignored");
        if (bucket > candleTime) {
            // The previous candle is now final. Use only its closed OHLC data
            // to predict the candle that has just started.
            candles.addLast(new Candle(candleTime,open,high,low,close));
            while (candles.size()>18000) candles.removeFirst();
            totalClosed++;
            startCandle(bucket, price);
            lockedNextCandleResult=calculateClosedCandles();
            return lockedNextCandleResult;
        }
        high=Math.max(high,price);
        low=Math.min(low,price);
        close=price;
        // Never recalculate from a forming candle: keep the prediction locked.
        return lockedNextCandleResult != null ? lockedNextCandleResult
                : snapshot("WAIT", "Waiting for candle close");
    }

    /**
     * Imports verified OHLC objects received from Market-QX history responses.
     * Only timestamped 10-second candles are accepted; malformed or other intervals are ignored.
     * Expected fields: timestamp,time, or ts; open/high/low/close (or o/h/l/c).
     */
    public synchronized int importHistoricalCandles(String symbol, JSONArray rows) {
        if (symbol == null || symbol.trim().isEmpty() || rows == null) return 0;
        TreeMap<Long,Candle> incoming = new TreeMap<>();
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
            // Never import the currently forming 10-second candle as closed history.
            long now=System.currentTimeMillis()/1000L;
            if (bucket >= (now/FRAME_SECONDS)*FRAME_SECONDS) continue;
            incoming.put(bucket,new Candle(bucket,o,h,l,c));
        }
        if (incoming.isEmpty()) return 0;
        if (asset!=null && !asset.equals(symbol)) {
            candles.clear(); totalClosed=0; candleTime=-1;
            awaitingLiveCandle=false; lockedNextCandleResult=null;
        }
        asset=symbol;
        TreeMap<Long,Candle> merged=new TreeMap<>();
        for(Candle old:candles) merged.put(old.time,old);
        int added=0;
        for(java.util.Map.Entry<Long,Candle> entry:incoming.entrySet()) {
            if(!merged.containsKey(entry.getKey())) added++;
            merged.put(entry.getKey(),entry.getValue());
        }
        // Retain up to two days of 10-second history for contextual analysis.
        while(merged.size()>18000) merged.pollFirstEntry();
        candles.clear();
        for(Candle candle:merged.values()) candles.addLast(candle);
        totalClosed=candles.size();
        if (awaitingLiveCandle || candleTime<0) {
            candleTime=merged.lastKey();
            lastPrice=merged.lastEntry().getValue().close;
            awaitingLiveCandle=true;
            lockedNextCandleResult=snapshot("WAIT","Historical candles loading; waiting for live candle");
        }
        return added;
    }

    private double number(JSONObject row,String longKey,String shortKey) {
        double v=row.has(longKey)?row.optDouble(longKey,Double.NaN):row.optDouble(shortKey,Double.NaN);
        return v;
    }

    private void startCandle(long time,double p) {
        candleTime=time; open=p; high=p; low=p; close=p;
    }

    private Result calculateClosedCandles() {
        // Evaluate only at the 10-second boundary; never wait for another candle
        // once the minimum closed history is available.
        List<Candle> cs=new ArrayList<>(candles);
        if(cs.size()<MIN_CANDLES)
            return snapshot("WAIT","Building 20 closed-candle history");
        int consecutive=1;
        for(int i=cs.size()-1;i>0&&consecutive<MIN_CANDLES;i--) {
            if(cs.get(i).time-cs.get(i-1).time!=FRAME_SECONDS) break;
            consecutive++;
        }
        if(consecutive<MIN_CANDLES)
            return snapshot("WAIT","Need 20 consecutive closed 10-second candles");

        List<Double> values=new ArrayList<>();
        for(Candle c:cs) values.add(c.close);
        double fast=ema(values,9), slow=ema(values,21);
        double rsi=rsiWilder(values,14);
        double momentum=values.get(values.size()-1)-values.get(values.size()-4);
        double[] dmi=calculateDmi(cs,10);
        double plusDi=dmi[0], minusDi=dmi[1], adx=dmi[2];
        double macd=macdHistogram(values);
        // Supertrend is calculated from the already-closed history, so it adds
        // no extra candle wait and does not move the 10-second signal boundary.
        int supertrend=supertrendDirection(cs,7,2.0);
        Candle last=cs.get(cs.size()-1);
        // Extra direction evidence uses only candles already closed. These
        // calculations run inside the existing 10-second boundary evaluation.
        double atr=averageTrueRange(cs,10);
        double normalizedSlope=normalizedSlope(values,atr,4);
        int structure=marketStructure(cs);
        double bodyRatio=last.range()>0?last.bodyAbs()/last.range():0;

        int lookback=Math.min(12,cs.size()-1);
        double resistance=Double.NEGATIVE_INFINITY, support=Double.POSITIVE_INFINITY;
        double avgRange=0;
        for(int i=cs.size()-lookback-1;i<cs.size()-1;i++) {
            Candle x=cs.get(i);
            resistance=Math.max(resistance,x.high);
            support=Math.min(support,x.low);
            avgRange+=x.range();
        }
        avgRange/=lookback;
        double range=last.range();
        double upperWick=last.high-Math.max(last.open,last.close);
        double lowerWick=Math.min(last.open,last.close)-last.low;
        double closePosition=range>0?(last.close-last.low)/range:0.5;
        boolean spike=avgRange>0&&range>avgRange*2.4;
        boolean flat=avgRange<=0||Math.abs(momentum)<avgRange*0.12;
        boolean nearResistance=avgRange>0&&resistance-last.close<=avgRange*0.30;
        boolean nearSupport=avgRange>0&&last.close-support<=avgRange*0.30;
        boolean bullReject=nearSupport&&lowerWick>=Math.max(last.bodyAbs()*1.25,range*0.35)
                &&closePosition>=0.62;
        boolean bearReject=nearResistance&&upperWick>=Math.max(last.bodyAbs()*1.25,range*0.35)
                &&closePosition<=0.38;
        boolean bullBreak=last.close>resistance&&closePosition>=0.70;
        boolean bearBreak=last.close<support&&closePosition<=0.30;

        int bull=0,bear=0;
        if(fast>slow) bull+=18; else if(fast<slow) bear+=18;
        if(momentum>0) bull+=18; else if(momentum<0) bear+=18;
        if(plusDi>minusDi&&adx>=10) bull+=14; else if(minusDi>plusDi&&adx>=10) bear+=14;
        if(macd>0) bull+=12; else if(macd<0) bear+=12;
        if(supertrend>0) bull+=18; else if(supertrend<0) bear+=18;
        if(rsi>=50&&rsi<=68) bull+=12;
        else if(rsi<=50&&rsi>=32) bear+=12;
        if(closePosition>=0.65) bull+=10; else if(closePosition<=0.35) bear+=10;
        // Volatility-normalized slope prevents tiny price noise from counting
        // as a trend; structure checks higher-high/higher-low or lower-high/lower-low.
        if(normalizedSlope>=0.12) bull+=10;
        else if(normalizedSlope<=-0.12) bear+=10;
        if(structure>0) bull+=8; else if(structure<0) bear+=8;
        if(bodyRatio>=0.55) {
            if(last.body()>0) bull+=6;
            else if(last.body()<0) bear+=6;
        }
        if(adx>=18) {
            if(plusDi>minusDi) bull+=6;
            else if(minusDi>plusDi) bear+=6;
        }
        if(bullBreak) bull+=16;
        if(bearBreak) bear+=16;
        // Rejection setups also need direction evidence; a long wick alone
        // is not enough to call a reversal against an established move.
        if(bullReject) bull+=28;
        if(bearReject) bear+=28;

        String sig="WAIT",why="WAIT: no clean 10-second setup";
        int score=Math.min(100,Math.max(bull,bear));
        if(spike) why="WAIT: abnormal volatility; avoid chasing";
        else if(flat) why="WAIT: sideways/weak momentum";
        else if(bullReject&&bull>=58&&bull>bear+15
                &&(supertrend>0||structure>0)&&normalizedSlope>-0.35) {
            sig="CALL"; why="CALL: support rejection + trend/structure confirmation";
        } else if(bearReject&&bear>=58&&bear>bull+15
                &&(supertrend<0||structure<0)&&normalizedSlope<0.35) {
            sig="PUT"; why="PUT: resistance rejection + trend/structure confirmation";
        } else if(bull>=66&&bull>bear+18&&!nearResistance&&fast>slow&&momentum>0
                &&supertrend>0&&normalizedSlope>=0.08&&structure>0&&adx>=12) {
            sig="CALL"; why="CALL: trend + momentum + market structure aligned";
        } else if(bear>=66&&bear>bull+18&&!nearSupport&&fast<slow&&momentum<0
                &&supertrend<0&&normalizedSlope<=-0.08&&structure<0&&adx>=12) {
            sig="PUT"; why="PUT: trend + momentum + market structure aligned";
        } else if(nearResistance&&bull>bear) why="WAIT: bullish entry blocked at resistance";
        else if(nearSupport&&bear>bull) why="WAIT: bearish entry blocked at support";
        else if(bull>bear) why="WAIT: bullish bias, confirmation not strong enough";
        else if(bear>bull) why="WAIT: bearish bias, confirmation not strong enough";
        return new Result(asset,sig,why,last.close,fast,slow,rsi,momentum,score,totalClosed);
    }

    /** Average true range over the most recent closed candles. */
    private double averageTrueRange(List<Candle> cs,int period) {
        if(cs.size()<2) return 0;
        int start=Math.max(1,cs.size()-period);
        double total=0;
        for(int i=start;i<cs.size();i++) {
            Candle cur=cs.get(i),prev=cs.get(i-1);
            total+=Math.max(cur.high-cur.low,
                    Math.max(Math.abs(cur.high-prev.close),Math.abs(cur.low-prev.close)));
        }
        return total/Math.max(1,cs.size()-start);
    }

    /** Difference of recent vs prior close means, normalized by ATR. */
    private double normalizedSlope(List<Double> values,double atr,int window) {
        if(values.size()<window*2||atr<=0) return 0;
        int n=values.size();
        double recent=0,prior=0;
        for(int i=n-window;i<n;i++) recent+=values.get(i);
        for(int i=n-window*2;i<n-window;i++) prior+=values.get(i);
        return ((recent-prior)/window)/atr;
    }

    /** +1 higher highs/lows, -1 lower highs/lows, 0 mixed/unclear. */
    private int marketStructure(List<Candle> cs) {
        int n=cs.size();
        if(n<3) return 0;
        Candle a=cs.get(n-3),b=cs.get(n-2),c=cs.get(n-1);
        boolean higher=b.high>a.high&&c.high>b.high&&b.low>a.low&&c.low>b.low;
        boolean lower=b.high<a.high&&c.high<b.high&&b.low<a.low&&c.low<b.low;
        return higher?1:lower?-1:0;
    }

    private double macdHistogram(List<Double> values) {
        if(values.isEmpty()) return 0;
        // Build the MACD line from every available closed candle. This keeps
        // the signal-line EMA meaningful even during the 20-candle warm-up.
        List<Double> line=new ArrayList<>();
        for(int i=0;i<values.size();i++) {
            List<Double> part=values.subList(0,i+1);
            line.add(ema(part,12)-ema(part,26));
        }
        double macdNow=line.get(line.size()-1);
        double signal=ema(line,9);
        return macdNow-signal;
    }

    /** Returns +1 for bullish, -1 for bearish, 0 when unavailable. */
    private int supertrendDirection(List<Candle> cs,int period,double multiplier) {
        if(cs.size()<period+2) return 0;
        double finalUpper=0, finalLower=0;
        int trend=1;
        for(int i=1;i<cs.size();i++) {
            Candle cur=cs.get(i), prev=cs.get(i-1);
            double tr=Math.max(cur.high-cur.low,
                    Math.max(Math.abs(cur.high-prev.close),Math.abs(cur.low-prev.close)));
            int from=Math.max(1,i-period+1);
            double atr=0;
            for(int j=from;j<=i;j++) {
                Candle a=cs.get(j), b=cs.get(j-1);
                atr+=Math.max(a.high-a.low,
                        Math.max(Math.abs(a.high-b.close),Math.abs(a.low-b.close)));
            }
            atr/=Math.max(1,i-from+1);
            double mid=(cur.high+cur.low)/2.0;
            double basicUpper=mid+multiplier*atr;
            double basicLower=mid-multiplier*atr;
            if(i==1) {
                finalUpper=basicUpper;
                finalLower=basicLower;
            } else {
                finalUpper=(basicUpper<finalUpper||prev.close>finalUpper)?basicUpper:finalUpper;
                finalLower=(basicLower>finalLower||prev.close<finalLower)?basicLower:finalLower;
            }
            if(trend>0&&cur.close<finalLower) trend=-1;
            else if(trend<0&&cur.close>finalUpper) trend=1;
        }
        return trend;
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
        if(v.isEmpty()) return 0;
        // With fewer samples than the requested period, use their mean as a
        // partial seed instead of incorrectly returning the latest close.
        int seed=Math.min(period,v.size());
        double e=0;
        for(int i=0;i<seed;i++) e+=v.get(i);
        e/=seed;
        double k=2.0/(period+1);
        for(int i=seed;i<v.size();i++) e=v.get(i)*k+e*(1-k);
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
