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
        // Keep the exact existing 10-second decision boundary. All analysis below
        // uses already-closed real candles; no extra candle wait is introduced.
        List<Candle> cs=new ArrayList<>(candles);
        if(cs.size()<MIN_CANDLES)
            return snapshot("WAIT","Collecting genuine closed-candle history");
        int consecutive=1;
        for(int i=cs.size()-1;i>0&&consecutive<MIN_CANDLES;i--) {
            if(cs.get(i).time-cs.get(i-1).time!=FRAME_SECONDS) break;
            consecutive++;
        }
        if(consecutive<MIN_CANDLES)
            return snapshot("WAIT","Need consecutive real 10-second candles");

        List<Double> values=new ArrayList<>();
        for(Candle c:cs) values.add(c.close);
        int n=cs.size();
        Candle last=cs.get(n-1);
        double atr=averageTrueRange(cs,14);
        if(atr<=0) return snapshot("WAIT","Insufficient price movement data");

        double fast=ema(values,9), slow=ema(values,21);
        double rsi=rsiWilder(values,14);
        double momentum=values.get(n-1)-values.get(Math.max(0,n-4));
        double[] dmi=calculateDmi(cs,10);
        double plusDi=dmi[0], minusDi=dmi[1], adx=dmi[2];
        double macd=macdHistogram(values);
        int supertrend=supertrendDirection(cs,7,2.0);

        // Market behaviour features: swing structure, multi-speed slope,
        // candle pressure, pullback/continuation and range location.
        int structure=swingStructure(cs);
        double slopeFast=normalizedSlope(values,atr,3);
        double slopeSlow=normalizedSlope(values,atr,8);
        double pressure=candlePressure(cs,8);
        double persistence=directionPersistence(cs,8);
        int trend=dominantTrend(fast,slow,slopeFast,slopeSlow,structure,supertrend);
        int pullback=pullbackContinuation(cs,trend,atr);
        // Multi-scale price-shape reading: small/large U and V turns, stair
        // progression through alternating candle colours, and liquidity sweeps.
        double shapeBias=multiScaleShapeBias(cs,atr);
        double stairBias=stairStepBias(cs,7);
        double turnBias=turningPointBias(cs,atr);
        double alternatingBias=alternatingProgressBias(cs,6,atr);
        int liquiditySweep=liquiditySweepDirection(cs,12,atr);
        int failedBreak=failedBreakoutDirection(cs,12);
        double closePos=last.range()>0?(last.close-last.low)/last.range():0.5;
        double bodyRatio=last.range()>0?last.bodyAbs()/last.range():0;
        double upperWick=last.high-Math.max(last.open,last.close);
        double lowerWick=Math.min(last.open,last.close)-last.low;

        int lookback=Math.min(24,n-1);
        double resistance=Double.NEGATIVE_INFINITY, support=Double.POSITIVE_INFINITY;
        double avgRange=0;
        for(int i=n-lookback-1;i<n-1;i++) {
            Candle c=cs.get(i);
            resistance=Math.max(resistance,c.high);
            support=Math.min(support,c.low);
            avgRange+=c.range();
        }
        avgRange/=Math.max(1,lookback);
        boolean nearResistance=last.close>=resistance-atr*0.22;
        boolean nearSupport=last.close<=support+atr*0.22;
        boolean bullBreak=last.close>resistance && closePos>=0.58;
        boolean bearBreak=last.close<support && closePos<=0.42;
        boolean bullReject=nearSupport && lowerWick>Math.max(last.bodyAbs(),atr*0.18)
                && closePos>=0.58;
        boolean bearReject=nearResistance && upperWick>Math.max(last.bodyAbs(),atr*0.18)
                && closePos<=0.42;

        // Continuous directional score: evidence accumulates rather than
        // requiring every indicator to agree before a signal can be emitted.
        double bias=0;
        bias+=clamp((fast-slow)/atr*5.0,-9,9);
        bias+=clamp(slopeFast*8.0,-8,8);
        bias+=clamp(slopeSlow*7.0,-9,9);
        bias+=clamp(pressure*12.0,-12,12);
        bias+=clamp(persistence*7.0,-7,7);
        bias+=clamp((plusDi-minusDi)/18.0,-6,6)*(adx>=8?1.0:0.55);
        bias+=clamp((rsi-50.0)/4.0,-6,6);
        bias+=macd>0?3:macd<0?-3:0;
        bias+=supertrend*4.0;
        bias+=structure*8.0;
        bias+=pullback*8.0;
        // These are additive pattern votes, not extra confirmation gates:
        // they do not delay or change the existing 10-second signal boundary.
        bias+=clamp(shapeBias*5.0,-9,9);
        bias+=clamp(stairBias*6.0,-6,6);
        bias+=clamp(turnBias*6.0,-8,8);
        bias+=clamp(alternatingBias*4.0,-5,5);
        bias+=liquiditySweep*7.0;
        bias+=failedBreak*7.0;
        if(bodyRatio>=0.45) bias+=last.body()>0?4:last.body()<0?-4:0;
        if(closePos>=0.72) bias+=3;
        else if(closePos<=0.28) bias-=3;
        if(bullBreak) bias+=10;
        if(bearBreak) bias-=10;
        if(bullReject) bias+=8;
        if(bearReject) bias-=8;

        // A mature trend should not be reversed merely because one candle
        // pulls back. Counter-trend wick signals are discounted in strong ADX.
        if(adx>=18 && trend!=0) {
            if(trend>0 && bias<0) bias*=0.72;
            if(trend<0 && bias>0) bias*=0.72;
        }

        String regime;
        if(adx>=20 && Math.abs(slopeSlow)>=0.12) regime="TRENDING";
        else if(adx<13 && Math.abs(slopeSlow)<0.10) regime="RANGE";
        else if(Math.abs(pressure)<0.16 && Math.abs(persistence)<0.18) regime="CHOPPY";
        else regime="TRANSITION";

        // In ranges, use location and rejection behaviour. In choppy markets,
        // use the freshest pressure/slope rather than freezing on WAIT.
        if("RANGE".equals(regime)) {
            if(nearSupport || (closePos<0.24 && lowerWick>upperWick)) bias+=7;
            if(nearResistance || (closePos>0.76 && upperWick>lowerWick)) bias-=7;
        }
        if("CHOPPY".equals(regime)) bias+=clamp(pressure*6.0,-5,5);

        // Deterministic tie-break from the latest movement means the engine
        // still chooses the dominant side when evidence is mixed. WAIT is
        // reserved for missing/invalid history, not ordinary market movement.
        if(Math.abs(bias)<1.25) {
            bias+=clamp(pressure*5.0,-4,4);
            bias+=clamp(slopeFast*3.0,-2,2);
            if(Math.abs(bias)<0.5) bias+=last.close>=last.open?0.75:-0.75;
        }
        String sig=bias>=0?"CALL":"PUT";
        int score=(int)Math.round(clamp(50.0+Math.abs(bias)*1.15,50,94));
        String pattern;
        if(turnBias>0.35) pattern="bullish turning-point / bounce";
        else if(turnBias< -0.35) pattern="bearish turning-point / rejection";
        if(bullBreak) pattern="bullish breakout";
        else if(bearBreak) pattern="bearish breakout";
        else if(pullback>0) pattern="bullish pullback continuation";
        else if(pullback<0) pattern="bearish pullback continuation";
        else if(bullReject) pattern="support rejection";
        else if(bearReject) pattern="resistance rejection";
        else if(structure>0) pattern="higher-high / higher-low structure";
        else if(structure<0) pattern="lower-high / lower-low structure";
        else if(pressure>0.18) pattern="buying pressure";
        else if(pressure< -0.18) pattern="selling pressure";
        else pattern="short-term directional pressure";
        String why=sig+": "+regime+" • "+pattern+" • "+(bias>=0?"upward":"downward")+" bias";
        return new Result(asset,sig,why,last.close,fast,slow,rsi,momentum,score,totalClosed);
    }

    private double clamp(double value,double low,double high) {
        return Math.max(low,Math.min(high,value));
    }

    /**
     * Reads U/V-shaped turns at several scales. A trough with meaningful
     * descent then recovery votes CALL; a crest votes PUT. Small and broad
     * formations are both considered, with ATR normalization to adapt to
     * each asset's volatility.
     */
    private double multiScaleShapeBias(List<Candle> cs,double atr) {
        if(cs.size()<7||atr<=0) return 0;
        // From micro turns through broad formations. All windows are evaluated
        // from already-closed candles; this adds no wait to the 10-second cycle.
        int[] windows={5,9,15,21,30,45,60,90};
        double total=0,weightTotal=0;
        for(int w:windows) {
            if(cs.size()<w) continue;
            int from=cs.size()-w;
            int half=Math.max(2,w/3);
            double leftMean=0,rightMean=0;
            for(int i=0;i<half;i++) {
                leftMean+=cs.get(from+i).close;
                rightMean+=cs.get(cs.size()-half+i).close;
            }
            leftMean/=half;
            rightMean/=half;
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            int minAt=-1,maxAt=-1;
            for(int i=0;i<w;i++) {
                Candle c=cs.get(from+i);
                if(c.low<min){min=c.low;minAt=i;}
                if(c.high>max){max=c.high;maxAt=i;}
            }
            double score=0;
            // U: a meaningful trough between two higher shoulders, with
            // recovery confirmed by the average of the right-side closes.
            if(minAt>=2&&minAt<=w-3) {
                double leftDrop=leftMean-min;
                double rightRise=rightMean-min;
                double recovery=rightMean-leftMean;
                double center=1.0-Math.abs((minAt/(double)(w-1))-0.5)*1.25;
                if(leftDrop>atr*0.20&&rightRise>atr*0.20&&recovery>atr*0.08) {
                    double strength=Math.min(leftDrop,rightRise)/atr;
                    score+=Math.min(2.0,strength)*Math.max(0.2,center);
                }
            }
            // Inverted U / V: a crest between two lower shoulders.
            if(maxAt>=2&&maxAt<=w-3) {
                double leftRise=max-leftMean;
                double rightDrop=max-rightMean;
                double rollover=leftMean-rightMean;
                double center=1.0-Math.abs((maxAt/(double)(w-1))-0.5)*1.25;
                if(leftRise>atr*0.20&&rightDrop>atr*0.20&&rollover>atr*0.08) {
                    double strength=Math.min(leftRise,rightDrop)/atr;
                    score-=Math.min(2.0,strength)*Math.max(0.2,center);
                }
            }
            // Mixed red/green candles are not a veto: broad net progress
            // confirms which side is controlling the complete formation.
            double net=cs.get(cs.size()-1).close-cs.get(from).open;
            double netVote=clamp(net/(atr*Math.sqrt(w/5.0)),-0.65,0.65);
            score=score*0.75+netVote*0.25;
            double weight=w<=9?0.65:w<=21?0.9:w<=45?1.15:1.35;
            total+=score*weight;
            weightTotal+=weight;
        }
        return weightTotal>0?clamp(total/weightTotal,-1.8,1.8):0;
    }

    /** Measures repeated higher-high/higher-low or lower-high/lower-low steps. */
    private double stairStepBias(List<Candle> cs,int steps) {
        if(cs.size()<steps+1) return 0;
        // A stair trend is a sequence of advances separated by modest
        // counter-moves; it is not required that every candle be the same colour.
        int n=cs.size();
        int[] windows={12,24,45,75};
        double total=0,weightTotal=0;
        for(int w:windows) {
            if(n<w) continue;
            int from=n-w;
            List<Double> highs=new ArrayList<>(),lows=new ArrayList<>();
            // Confirmed local pivots: one candle on each side. This avoids
            // treating a still-forming swing as a completed step.
            for(int i=from+1;i<n-1;i++) {
                Candle p=cs.get(i),a=cs.get(i-1),b=cs.get(i+1);
                if(p.high>=a.high&&p.high>b.high) highs.add(p.high);
                if(p.low<=a.low&&p.low<b.low) lows.add(p.low);
            }
            double swingVote=0;
            if(highs.size()>=3&&lows.size()>=3) {
                int h=highs.size(),l=lows.size();
                int upH=0,downH=0,upL=0,downL=0;
                for(int i=Math.max(1,h-3);i<h;i++) {
                    if(highs.get(i)>highs.get(i-1)) upH++;
                    else if(highs.get(i)<highs.get(i-1)) downH++;
                }
                for(int i=Math.max(1,l-3);i<l;i++) {
                    if(lows.get(i)>lows.get(i-1)) upL++;
                    else if(lows.get(i)<lows.get(i-1)) downL++;
                }
                swingVote=(upH+upL-downH-downL)/6.0;
            }
            double first=cs.get(from).close,last=cs.get(n-1).close;
            double progress=last-first;
            double path=0;
            for(int i=from+1;i<n;i++) path+=Math.abs(cs.get(i).close-cs.get(i-1).close);
            double efficiency=path>0?Math.abs(progress)/path:0;
            double netVote=Math.signum(progress)*efficiency;
            // Higher/lower swing structure carries more weight than candle colour;
            // net progress confirms that the staircase is actually advancing.
            double score=0.7*swingVote+0.3*netVote;
            double weight=w==12?0.6:w==24?0.85:w==45?1.0:1.15;
            total+=score*weight;
            weightTotal+=weight;
        }
        return weightTotal>0?clamp(total/weightTotal,-1,1):0;
    }

    /**
     * Scores where a U/V or stair-step move is turning: defended swing lows,
     * rejected swing highs, pullback holds, failed continuation and reclaim/
     * breakdown of nearby micro structure. It uses closed candles only.
     */
    private double turningPointBias(List<Candle> cs,double atr) {
        if(cs.size()<8||atr<=0) return 0;
        int n=cs.size();
        Candle last=cs.get(n-1),prev=cs.get(n-2);
        double total=0,weights=0;
        int[] windows={8,13,21,34,55,89};
        for(int w:windows) {
            if(n<w+1) continue;
            int from=n-w-1;
            double hi=Double.NEGATIVE_INFINITY,lo=Double.POSITIVE_INFINITY;
            int hiAt=-1,loAt=-1;
            for(int i=from;i<n-1;i++) {
                Candle c=cs.get(i);
                if(c.high>hi){hi=c.high;hiAt=i;}
                if(c.low<lo){lo=c.low;loAt=i;}
            }
            double range=Math.max(atr,hi-lo);
            double closeLocation=(last.close-lo)/Math.max(1e-12,hi-lo);
            double lowerWick=Math.min(last.open,last.close)-last.low;
            double upperWick=last.high-Math.max(last.open,last.close);
            double vote=0;
            // U/V floor: after a low, price must lift away from it and show
            // demand (close location, lower wick, green body or reclaim).
            double bounce=last.close-lo;
            if(loAt>=from+1 && bounce>atr*0.12) {
                double depth=(hi-lo)>0?(last.close-lo)/(hi-lo):0;
                if(depth>0.18) vote+=Math.min(1.0,bounce/atr*0.35);
                if(lowerWick>last.bodyAbs()*0.8 && closeLocation>0.55) vote+=0.35;
                if(last.close>prev.high) vote+=0.35;
            }
            // Crest: mirror image for supply/rejection and bearish rollover.
            double drop=hi-last.close;
            if(hiAt>=from+1 && drop>atr*0.12) {
                double depth=(hi-last.close)/(hi-lo+1e-12);
                if(depth>0.18) vote-=Math.min(1.0,drop/atr*0.35);
                if(upperWick>last.bodyAbs()*0.8 && closeLocation<0.45) vote-=0.35;
                if(last.close<prev.low) vote-=0.35;
            }
            // Staircase pullback: a retracement that holds the prior swing
            // area and resumes is continuation; a decisive loss of that area
            // is an early warning of a step failure/reversal.
            double recentMid=(hi+lo)*0.5;
            if(last.close>prev.close && last.close>recentMid && last.low>lo+atr*0.08)
                vote+=0.18;
            if(last.close<prev.close && last.close<recentMid && last.high<hi-atr*0.08)
                vote-=0.18;
            double weight=w<=13?0.55:w<=34?0.85:1.0;
            total+=clamp(vote,-1.8,1.8)*weight;
            weights+=weight;
        }
        return weights>0?clamp(total/weights,-1.5,1.5):0;
    }

    /**
     * Alternating green/red candles can still trend. Vote from net progress
     * and close-to-close movement, not the last candle colour alone.
     */
    private double alternatingProgressBias(List<Candle> cs,int period,double atr) {
        if(cs.size()<period||atr<=0) return 0;
        int from=cs.size()-period,changes=0,previous=0;
        for(int i=from;i<cs.size();i++) {
            Candle c=cs.get(i);
            int dir=c.close>c.open?1:c.close<c.open?-1:0;
            if(dir!=0&&previous!=0&&dir!=previous) changes++;
            if(dir!=0) previous=dir;
        }
        if(changes<2) return 0;
        double net=cs.get(cs.size()-1).close-cs.get(from).open;
        return clamp(net/atr,-1.5,1.5);
    }

    /** Detects a liquidity sweep: price pierces a prior extreme then closes back inside. */
    private int liquiditySweepDirection(List<Candle> cs,int lookback,double atr) {
        if(cs.size()<lookback+1||atr<=0) return 0;
        int n=cs.size();
        double priorLow=Double.POSITIVE_INFINITY,priorHigh=Double.NEGATIVE_INFINITY;
        for(int i=n-lookback-1;i<n-1;i++) {
            priorLow=Math.min(priorLow,cs.get(i).low);
            priorHigh=Math.max(priorHigh,cs.get(i).high);
        }
        Candle last=cs.get(n-1);
        boolean sweepLow=last.low<priorLow-atr*0.03&&last.close>priorLow;
        boolean sweepHigh=last.high>priorHigh+atr*0.03&&last.close<priorHigh;
        if(sweepLow&&!sweepHigh) return 1;
        if(sweepHigh&&!sweepLow) return -1;
        return 0;
    }

    /** A failed breakout is a trap vote against the side that briefly broke out. */
    private int failedBreakoutDirection(List<Candle> cs,int lookback) {
        if(cs.size()<lookback+1) return 0;
        int n=cs.size();
        double priorLow=Double.POSITIVE_INFINITY,priorHigh=Double.NEGATIVE_INFINITY;
        for(int i=n-lookback-1;i<n-1;i++) {
            priorLow=Math.min(priorLow,cs.get(i).low);
            priorHigh=Math.max(priorHigh,cs.get(i).high);
        }
        Candle last=cs.get(n-1);
        boolean bullTrap=last.high>priorHigh&&last.close<priorHigh;
        boolean bearTrap=last.low<priorLow&&last.close>priorLow;
        if(bullTrap&&!bearTrap) return -1;
        if(bearTrap&&!bullTrap) return 1;
        return 0;
    }

    /** Detects the last two confirmed swing highs and lows (2 candles either side). */
    private int swingStructure(List<Candle> cs) {
        int n=cs.size(), from=Math.max(2,n-48);
        List<Double> highs=new ArrayList<>(), lows=new ArrayList<>();
        for(int i=from;i<n-2;i++) {
            Candle c=cs.get(i);
            boolean ph=true,pl=true;
            for(int j=i-2;j<=i+2;j++) if(j!=i) {
                if(cs.get(j).high>=c.high) ph=false;
                if(cs.get(j).low<=c.low) pl=false;
            }
            if(ph) highs.add(c.high);
            if(pl) lows.add(c.low);
        }
        if(highs.size()<2||lows.size()<2) return 0;
        double h1=highs.get(highs.size()-2),h2=highs.get(highs.size()-1);
        double l1=lows.get(lows.size()-2),l2=lows.get(lows.size()-1);
        if(h2>h1&&l2>l1) return 1;
        if(h2<h1&&l2<l1) return -1;
        return 0;
    }

    /** Signed candle-body pressure, weighted toward the newest candles. */
    private double candlePressure(List<Candle> cs,int period) {
        int from=Math.max(0,cs.size()-period);
        double signed=0,total=0,weight=1;
        for(int i=from;i<cs.size();i++) {
            Candle c=cs.get(i);
            double r=Math.max(c.range(),1e-12);
            signed+=(c.body()/r)*weight;
            total+=weight;
            weight*=1.18;
        }
        return total>0?signed/total:0;
    }

    private double directionPersistence(List<Candle> cs,int period) {
        int from=Math.max(0,cs.size()-period);
        double up=0,down=0,weight=1;
        for(int i=from;i<cs.size();i++) {
            Candle c=cs.get(i);
            if(c.close>c.open) up+=weight;
            else if(c.close<c.open) down+=weight;
            weight*=1.12;
        }
        double sum=up+down;
        return sum>0?(up-down)/sum:0;
    }

    private int dominantTrend(double fast,double slow,double slopeFast,double slopeSlow,
                              int structure,int supertrend) {
        double v=0;
        if(fast>slow) v+=1.0; else if(fast<slow) v-=1.0;
        v+=clamp(slopeFast*3,-1,1);
        v+=clamp(slopeSlow*2,-1,1);
        v+=structure*1.2;
        v+=supertrend*0.5;
        return v>0.65?1:v< -0.65?-1:0;
    }

    /** Finds a brief counter-trend pullback followed by renewed trend pressure. */
    private int pullbackContinuation(List<Candle> cs,int trend,double atr) {
        if(trend==0||cs.size()<6||atr<=0) return 0;
        int n=cs.size(), counter=0;
        for(int i=n-4;i<n-1;i++) {
            Candle c=cs.get(i);
            if((trend>0&&c.close<c.open)||(trend<0&&c.close>c.open)) counter++;
        }
        Candle last=cs.get(n-1);
        double body=last.body();
        boolean renewed=trend>0?body>0&&last.close>cs.get(n-2).close
                :body<0&&last.close<cs.get(n-2).close;
        return counter>=1&&renewed&&last.bodyAbs()>=atr*0.12?trend:0;
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
