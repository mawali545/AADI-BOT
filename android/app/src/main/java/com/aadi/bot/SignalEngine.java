package com.aadi.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONArray;

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
    // Warm up from 15 genuine live 10-second candles only.
    private static final int MIN_CANDLES=15;
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

    // Only genuine live candles received after app startup are used for signals.
    private final Deque<Candle> liveCandles = new ArrayDeque<>();
    private String asset;
    private double lastPrice;
    private int totalClosed=0;

    private long latestFeedTime=-1;
    private Result latestHistoryResult=null;
    // The forming candle is built only from current live exchange ticks.
    // Older context remains sourced from Market-QX history packets.
    private long liveBucket=-1;
    private Candle liveCandle=null;
    private int liveTickCount=0;
    // A forecast is prepared near candle close, then issued once at the next
    // 10-second boundary and held unchanged throughout that candle.
    private Result pendingNextCandleResult=null;
    private long previewedBucket=-1;

    /**
     * Update the live forming 10-second candle from genuine current exchange ticks.
     * Historical candles are never used as the current quote.
     */
    public synchronized Result onTick(String symbol, double price, double timestamp) {
        if (symbol == null || symbol.trim().isEmpty() || !Double.isFinite(price) || price <= 0)
            return currentResult();
        if (timestamp > 10000000000.0) timestamp /= 1000.0;
        long ts=(long)timestamp;
        if (ts <= 0) return currentResult();
        if (asset == null || !asset.equals(symbol)) {
            asset=symbol;
            liveCandles.clear();
            totalClosed=0;
            latestHistoryResult=null;
            latestFeedTime=-1;
            liveBucket=-1;
            liveCandle=null;
            liveTickCount=0;
            pendingNextCandleResult=null;
            previewedBucket=-1;
        }
        // Ignore out-of-order exchange ticks so an old packet cannot roll
        // the current price or forming candle backwards.
        if (latestFeedTime>0 && ts<latestFeedTime) return currentResult();
        latestFeedTime=ts;
        lastPrice=price;
        long bucket=(ts/FRAME_SECONDS)*FRAME_SECONDS;
        if(liveCandle==null || bucket>liveBucket) {
            if(liveCandle!=null) {
                appendClosedLiveCandle(liveCandle);
                // If no tick arrived in the final second, use the now-completed
                // genuine candle as a fallback forecast before starting the next.
                if(pendingNextCandleResult==null) {
                    pendingNextCandleResult=calculateWithLiveCandle();
                }
            }
            // Publish exactly one decision for this new candle. It remains
            // fixed until this candle ends; no CALL/PUT flicker inside a bar.
            if(pendingNextCandleResult!=null) {
                Result issued=pendingNextCandleResult;
                latestHistoryResult=new Result(asset,issued.signal,issued.reason,price,
                        issued.emaFast,issued.emaSlow,issued.rsi,issued.momentum,
                        issued.score,issued.closedCandles);
            } else {
                latestHistoryResult=snapshot("WAIT","WAIT: no reliable next-candle forecast");
            }
            pendingNextCandleResult=null;
            previewedBucket=-1;
            liveBucket=bucket;
            liveCandle=new Candle(bucket,price,price,price,price);
            liveTickCount=1;
        } else if(bucket==liveBucket) {
            liveCandle=new Candle(liveBucket,liveCandle.open,
                    Math.max(liveCandle.high,price),Math.min(liveCandle.low,price),price);
            liveTickCount++;
        }

        // Learn from the current candle continuously, but only commit one
        // forecast near its close. That forecast is for the NEXT 10-second bar.
        if(liveTickCount>=2 && (ts%FRAME_SECONDS)>=FRAME_SECONDS-1
                && previewedBucket!=liveBucket) {
            pendingNextCandleResult=calculateWithLiveCandle();
            previewedBucket=liveBucket;
        }
        return currentResult();
    }

    private void appendClosedLiveCandle(Candle completed) {
        if(!liveCandles.isEmpty() && liveCandles.peekLast().time==completed.time) {
            liveCandles.removeLast();
        } else if(!liveCandles.isEmpty() && liveCandles.peekLast().time>completed.time) {
            return;
        }
        liveCandles.addLast(completed);
        while(liveCandles.size()>800) liveCandles.removeFirst();
        totalClosed=liveCandles.size();
    }

    private Result calculateWithLiveCandle() {
        // Strict live-only window: imported chart history never enters the forecast.
        List<Candle> window=new ArrayList<>(liveCandles);
        if(liveCandle!=null) {
            if(!window.isEmpty() && window.get(window.size()-1).time==liveCandle.time)
                window.set(window.size()-1,liveCandle);
            else window.add(liveCandle);
        }
        return calculateFromCandles(window);
    }

    /** Historical chart packets are intentionally ignored by this live-only engine. */
    public synchronized int importHistoricalCandles(String symbol, JSONArray rows) {
        return 0;
    }

    /** Measures chop using only the most recent live candles in this calculation. */
    private double liveChopScore(List<Candle> cs) {
        int count=Math.min(12,cs.size());
        if(count<6) return 0;
        int from=cs.size()-count, changes=0, previous=0;
        double travel=0;
        Candle first=cs.get(from), last=cs.get(cs.size()-1), prior=null;
        for(int i=from;i<cs.size();i++) {
            Candle c=cs.get(i);
            int direction=c.close>c.open?1:c.close<c.open?-1:0;
            if(direction!=0) {
                if(previous!=0 && direction!=previous) changes++;
                previous=direction;
            }
            if(prior!=null) travel+=Math.abs(c.close-prior.close);
            prior=c;
        }
        if(travel<=0) return 0;
        double efficiency=Math.abs(last.close-first.open)/travel;
        double alternation=changes/(double)Math.max(1,count-1);
        return clamp(alternation*(1.0-clamp(efficiency,0,1)),0,1);
    }

    public synchronized Result currentResult() {
        return latestHistoryResult!=null ? latestHistoryResult
                : snapshot("WAIT","Waiting for 15 genuine live QX candles");
    }

    private Result calculateFromCandles(List<Candle> cs) {
        if (cs.size() < MIN_CANDLES)
            return snapshot("WAIT", "Collecting genuine chart history for next-candle forecast");

        final int n=cs.size();
        final Candle last=cs.get(n-1), prev=cs.get(n-2);
        final double atr=averageTrueRange(cs,14);
        if(atr<=0) return snapshot("WAIT","Insufficient genuine price movement");

        List<Double> closes=new ArrayList<>();
        for(Candle c:cs) closes.add(c.close);
        final double fastMove=last.close-cs.get(n-4).close;
        final double slowMove=last.close-cs.get(n-9).close;
        final double pressure=candlePressure(cs,5);
        final int structure=swingStructure(cs);

        // Separate the broad regime from the immediate candle behaviour.
        // Long history is context, not a command to blindly follow its trend.
        // The aggregate already contains all disjoint horizons; do not add
        // its component history scores again.
        double chopScore=liveChopScore(cs);
        double longBias=clamp(0.55*structure
                +0.45*clamp(slowMove/(atr*2.5),-1,1),-1,1);
        longBias=clamp(longBias*(1.0-0.35*chopScore),-1,1);

        // Activate the indicator implementations in the final decision.
        // Each vote is normalized by ATR so it scales across OTC instruments.
        double slopeFast=normalizedSlope(closes,atr,5);
        double slopeSlow=normalizedSlope(closes,atr,12);
        double macdVote=clamp(macdHistogram(closes)/(atr*0.08),-1,1);
        double[] dmi=calculateDmi(cs,14);
        double dmiVote=clamp((dmi[0]-dmi[1])/35.0,-1,1)
                *clamp(dmi[2]/35.0,0,1);
        int supertrend=supertrendDirection(cs,10,2.5);
        int microStructure=marketStructure(cs);
        int dominant=dominantTrend(ema(closes,9),ema(closes,21),
                slopeFast,slopeSlow,structure,supertrend);

        // Additional EMA stack: all five periods are calculated from the
        // expanding genuine-live window. EMA100/200 mature as live candles accrue.
        double e9=ema(closes,9), e20=ema(closes,20), e50=ema(closes,50);
        double e100=ema(closes,100), e200=ema(closes,200);
        double emaStackVote=emaStackBias(e9,e20,e50,e100,e200,atr,n);
        // Fast RSI7 and standard RSI14; 30/50/70 are context levels, not
        // automatic reversal commands. Momentum and level agreement are blended.
        double rsi7=rsiWilder(closes,7), rsi14=rsiWilder(closes,14);
        double rsiVote=rsiMomentumBias(closes,rsi7,rsi14);

        // Preserve the existing indicator and market-behaviour evidence.
        // The new EMA/RSI pair gets a limited share instead of overriding it.
        double indicatorBias=clamp(
                0.16*macdVote
                +0.12*clamp(slopeFast*3,-1,1)
                +0.10*clamp(slopeSlow*2,-1,1)
                +0.15*dmiVote
                +0.10*supertrend
                +0.07*microStructure
                +0.12*dominant
                +0.12*emaStackVote
                +0.06*rsiVote,-1,1);
        // Indicators refine the broad regime; live price-action reversal
        // evidence below can still overrule them when genuine rejection forms.
        longBias=clamp(0.82*longBias+0.18*indicatorBias,-1,1);

        // Recent displacement and pressure are only part of the forecast.
        // A positive pressure reading cannot overrule rejection/exhaustion.
        double fastBias=clamp(fastMove/(atr*1.8),-1,1);
        double flowBias=shortTermFlowBias(cs,atr);
        double stairBias=stairStepBias(cs,3);
        double alternatingBias=alternatingProgressBias(cs,Math.min(12,n),atr);
        double recentBias=clamp(0.30*fastBias+0.20*pressure
                +0.35*clamp(flowBias/1.5,-1,1)
                +0.15*stairBias,-1,1);
        // Alternating red/green candles can still make directional progress.
        recentBias=clamp(recentBias+0.10*clamp(alternatingBias/1.5,-1,1),-1,1);

        // These reversal readers were previously disconnected from the final
        // decision. They now actively test whether the current pressure is
        // being absorbed, rejected, exhausted, or trapped.
        double pattern=candlestickPatternBias(cs,atr);
        double exhaustion=exhaustionReversalBias(cs,atr);
        double turning=turningPointBias(cs,atr);
        double multiScale=multiScaleShapeBias(cs,atr);
        int sweep=liquiditySweepDirection(cs,12,atr);
        int failedBreak=failedBreakoutDirection(cs,12);
        int trend=longBias>0.16?1:longBias< -0.16?-1:0;
        int pullback=pullbackContinuation(cs,trend,atr);

        // Flow already contributes to recentBias; do not count it twice.
        // Sweep and failed-breakout are overlapping descriptions of one trap.
        int trapVote=sweep!=0?sweep:failedBreak;
        double reversalBias=clamp(
                0.28*clamp(pattern/1.5,-1,1)
                +0.30*clamp(exhaustion/1.5,-1,1)
                +0.18*clamp(turning/1.5,-1,1)
                +0.16*clamp(multiScale/1.8,-1,1)
                +0.08*trapVote,-1,1);

        // A shallow counter-candle that holds the swing is a pullback, not a
        // trend reversal. But a failed breakout, sweep or strong rejection
        // can forecast the opposite-colour candle even while trend/pressure
        // still point the other way.
        if(pullback!=0) recentBias=clamp(recentBias+0.16*pullback,-1,1);

        double forecast=0.34*longBias+0.29*recentBias+0.37*reversalBias;

        double candleRange=Math.max(last.range(),1e-12);
        double closePos=(last.close-last.low)/candleRange;
        double upper=last.high-Math.max(last.open,last.close);
        double lower=Math.min(last.open,last.close)-last.low;
        boolean bearishAbsorption=(trend>=0)
                && ((sweep<0||failedBreak<0)
                    || (flowBias< -0.72&&exhaustion< -0.22)
                    || (pattern< -0.55&&exhaustion< -0.35)
                    || (upper>Math.max(last.bodyAbs()*1.15,atr*0.16)&&closePos<0.42
                        && (exhaustion< -0.25||flowBias< -0.55)));
        boolean bullishAbsorption=(trend<=0)
                && ((sweep>0||failedBreak>0)
                    || (flowBias>0.72&&exhaustion>0.22)
                    || (pattern>0.55&&exhaustion>0.35)
                    || (lower>Math.max(last.bodyAbs()*1.15,atr*0.16)&&closePos>0.58
                        && (exhaustion>0.25||flowBias>0.55)));

        // Conflicting absorption means WAIT pressure, not a forced tie-break.
        boolean absorptionConflict=bearishAbsorption&&bullishAbsorption;
        if(absorptionConflict) forecast*=0.35;
        else if(bearishAbsorption) forecast=clamp(forecast-0.12,-1,1);
        else if(bullishAbsorption) forecast=clamp(forecast+0.12,-1,1);

        String regime;
        if(absorptionConflict) regime="CONFLICTING ABSORPTION";
        else if(bearishAbsorption) regime="BEARISH ABSORPTION / REJECTION";
        else if(bullishAbsorption) regime="BULLISH ABSORPTION / REJECTION";
        else if(longBias>0.28&&reversalBias> -0.15) regime="UPTREND WITH PULLBACK CHECK";
        else if(longBias< -0.28&&reversalBias<0.15) regime="DOWNTREND WITH BOUNCE CHECK";
        else if(Math.abs(longBias)<0.18&&chopScore>0.35) regime="MIXED / CHOP";
        else regime="TRANSITION / MIXED";

        // A choppy recent regime requires slightly stronger agreement, without
        // imposing a fixed candle-count delay.
        double entryThreshold=0.32+0.10*chopScore;
        // Require agreement from at least two of the three broad, recent and
        // reversal layers. A strong opposing layer blocks the trade entirely.
        int bullishVotes=(longBias>0.12?1:0)+(recentBias>0.12?1:0)+(reversalBias>0.12?1:0);
        int bearishVotes=(longBias< -0.12?1:0)+(recentBias< -0.12?1:0)+(reversalBias< -0.12?1:0);
        // No absorption event can manufacture a directional signal by itself.
        if(forecast>entryThreshold && bullishVotes>=2 && bearishVotes==0) {
            int score=(int)Math.round(clamp(54+Math.abs(forecast)*34,54,88));
            String why="CALL forecast • "+regime+" • trend, live flow and reversal checks agree";
            return new Result(asset,"CALL",why,last.close,ema(closes,9),ema(closes,21),
                    rsiWilder(closes,14),fastMove,score,totalClosed);
        }
        if(forecast< -entryThreshold && bearishVotes>=2 && bullishVotes==0) {
            int score=(int)Math.round(clamp(54+Math.abs(forecast)*34,54,88));
            String why="PUT forecast • "+regime+" • trend, live flow and reversal checks agree";
            return new Result(asset,"PUT",why,last.close,ema(closes,9),ema(closes,21),
                    rsiWilder(closes,14),fastMove,score,totalClosed);
        }
        String why="WAIT • "+regime+" • pressure and candle-reversal evidence conflict";
        return new Result(asset,"WAIT",why,last.close,ema(closes,9),ema(closes,21),
                rsiWilder(closes,14),fastMove,0,totalClosed);
    }

    /**
     * Context-aware candlestick pattern engine. Long wicks are interpreted as
     * rejection only near a meaningful swing/extension; body colour alone is
     * deliberately weak. Multi-candle engulfing and inside-bar context add
     * evidence without becoming a hard delay/confirmation gate.
     */
    private double candlestickPatternBias(List<Candle> cs,double atr) {
        int n=cs.size();
        if(n<4||atr<=0) return 0;
        Candle a=cs.get(n-4), b=cs.get(n-3), p=cs.get(n-2), c=cs.get(n-1);
        double range=Math.max(c.range(),1e-12);
        double body=Math.max(c.bodyAbs(),atr*0.015);
        double upper=c.high-Math.max(c.open,c.close);
        double lower=Math.min(c.open,c.close)-c.low;
        double upperRatio=upper/range, lowerRatio=lower/range;
        double bodyRatio=c.bodyAbs()/range;
        double closePos=(c.close-c.low)/range;
        double priorMove=c.close-cs.get(Math.max(0,n-5)).open;
        double vote=0;

        boolean hammer=lower>=Math.max(body*1.8,atr*0.16)
                && closePos>=0.62 && upper<=Math.max(body*0.9,atr*0.10);
        if(hammer && (priorMove< -atr*0.25 || c.low<=Math.min(p.low,b.low)+atr*0.12))
            vote+=0.78;

        boolean star=upper>=Math.max(body*1.8,atr*0.16)
                && closePos<=0.38 && lower<=Math.max(body*0.9,atr*0.10);
        if(star && (priorMove>atr*0.25 || c.high>=Math.max(p.high,b.high)-atr*0.12))
            vote-=0.78;

        if(lowerRatio>0.55 && closePos>0.68 && c.low<=Math.min(p.low,b.low)+atr*0.18)
            vote+=0.48;
        if(upperRatio>0.55 && closePos<0.32 && c.high>=Math.max(p.high,b.high)-atr*0.18)
            vote-=0.48;

        boolean bullEngulf=c.close>c.open && p.close<p.open
                && c.open<=p.close && c.close>=p.open
                && c.bodyAbs()>p.bodyAbs()*1.05;
        boolean bearEngulf=c.close<c.open && p.close>p.open
                && c.open>=p.close && c.close<=p.open
                && c.bodyAbs()>p.bodyAbs()*1.05;
        if(bullEngulf) vote+=0.62;
        if(bearEngulf) vote-=0.62;

        double firstMid=(a.open+a.close)*0.5;
        boolean morning=a.close<a.open && p.bodyAbs()<a.bodyAbs()*0.55
                && c.close>c.open && c.close>firstMid;
        boolean evening=a.close>a.open && p.bodyAbs()<a.bodyAbs()*0.55
                && c.close<c.open && c.close<firstMid;
        if(morning) vote+=0.48;
        if(evening) vote-=0.48;

        // Doji/spinning top is indecision, not an automatic reversal.
        boolean indecision=bodyRatio<0.18 && upper>atr*0.08 && lower>atr*0.08;
        if(indecision) vote*=0.35;

        boolean fullBody=bodyRatio>0.82 && upper<range*0.10 && lower<range*0.10;
        if(fullBody && Math.abs(priorMove)<atr*1.0)
            vote+=c.body()>0?0.18:c.body()<0?-0.18:0;

        boolean inside=p.high<b.high && p.low>b.low;
        if(inside && c.close>b.high) vote+=0.24;
        if(inside && c.close<b.low) vote-=0.24;
        return clamp(vote,-1.5,1.5);
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
     * Reads the last 1-3 candle flow against the preceding micro swing.
     * Distinguishes a shallow pullback from a genuine short-term control shift,
     * and penalizes chasing an exhausted burst into a wick/rejection.
     *
     * Positive = buyers gaining control; negative = sellers gaining control.
     * No extra candle is awaited: this is evaluated with the normal close scan.
     */
    private double shortTermFlowBias(List<Candle> cs,double atr) {
        int n=cs.size();
        if(n<7||atr<=0) return 0;
        Candle last=cs.get(n-1),prev=cs.get(n-2);
        double vote=0;

        // Read the prior price path, not a fixed count of same-colour bars.
        // The lookback is a rolling context window; candle colours are never
        // used as a 2/3/4-candle trigger.
        int window=Math.min(12,n-2);
        int from=n-1-window;
        double net=prev.close-cs.get(from).open;
        double travel=0;
        for(int i=from+1;i<n-1;i++)
            travel+=Math.abs(cs.get(i).close-cs.get(i-1).close);
        double efficiency=travel>0?Math.abs(net)/travel:0;
        int direction=net>atr*0.20?1:net< -atr*0.20?-1:0;

        if(direction!=0) {
            int split=from+Math.max(2,window/2);
            double early=0,late=0;
            for(int i=from+1;i<=split&&i<n-1;i++)
                early+=cs.get(i).close-cs.get(i-1).close;
            for(int i=Math.max(split+1,from+1);i<n-1;i++)
                late+=cs.get(i).close-cs.get(i-1).close;
            double earlyRate=early/Math.max(1,split-from);
            double lateRate=late/Math.max(1,(n-2)-Math.max(split,from));
            // Loss of directional displacement is an early warning, not an
            // automatic reversal. It becomes meaningful with rejection or
            // a developing break in nearby structure.
            boolean decelerating=earlyRate*direction>atr*0.012
                    && lateRate*direction<earlyRate*direction*0.58;
            if(decelerating) vote-=0.24*direction*clamp(efficiency+0.25,0.35,1.0);

            double priorHigh=Double.NEGATIVE_INFINITY,priorLow=Double.POSITIVE_INFINITY;
            for(int i=from;i<n-1;i++) {
                priorHigh=Math.max(priorHigh,cs.get(i).high);
                priorLow=Math.min(priorLow,cs.get(i).low);
            }
            double range=Math.max(last.range(),1e-12);
            double closePos=(last.close-last.low)/range;
            double upper=last.high-Math.max(last.open,last.close);
            double lower=Math.min(last.open,last.close)-last.low;
            boolean rejectedHigh=direction>0
                    && last.high>=priorHigh-atr*0.025
                    && upper>Math.max(last.bodyAbs()*0.85,atr*0.12)
                    && closePos<0.48;
            boolean rejectedLow=direction<0
                    && last.low<=priorLow+atr*0.025
                    && lower>Math.max(last.bodyAbs()*0.85,atr*0.12)
                    && closePos>0.52;
            if(rejectedHigh) vote-=0.62;
            if(rejectedLow) vote+=0.62;

            // A live close through the nearby micro swing is stronger than
            // a colour change: it shows that the opposing side took control.
            boolean brokeDown=direction>0
                    && last.close<Math.min(prev.low,cs.get(n-3).low);
            boolean brokeUp=direction<0
                    && last.close>Math.max(prev.high,cs.get(n-3).high);
            if(brokeDown) vote-=0.72;
            if(brokeUp) vote+=0.72;

            // A fresh extreme that cannot hold outside the old range is a
            // failed extension / liquidity trap.
            boolean bullTrap=direction>0&&last.high>priorHigh&&last.close<priorHigh;
            boolean bearTrap=direction<0&&last.low<priorLow&&last.close>priorLow;
            if(bullTrap) vote-=0.58;
            if(bearTrap) vote+=0.58;
        }

        // A single opposite-colour bar is not enough by itself. Its body,
        // displacement and close location must show genuine opposing control.
        double range=Math.max(last.range(),1e-12);
        double closePos=(last.close-last.low)/range;
        if(last.close<last.open && last.bodyAbs()>atr*0.16
                && last.close<prev.close && closePos<0.38) vote-=0.28;
        if(last.close>last.open && last.bodyAbs()>atr*0.16
                && last.close>prev.close && closePos>0.62) vote+=0.28;

        return clamp(vote,-1.5,1.5);
    }

    /**
     * Adaptive candle-by-candle exhaustion/reversal reading.
     * It never requires a fixed number of same-colour candles. It evaluates
     * the rolling live window by price displacement, changing pressure,
     * candle anatomy, rejection and local structure failure.
     */
    private double exhaustionReversalBias(List<Candle> cs,double atr) {
        int n=cs.size();
        if(n<7||atr<=0) return 0;

        int window=Math.min(15,n-1);
        int from=n-window-1;
        int split=from+Math.max(2,window/2);
        double earlyPressure=0,earlyWeight=0,latePressure=0,lateWeight=0;
        double netPath=cs.get(n-2).close-cs.get(from).open;
        double totalTravel=0;
        for(int i=from;i<n-1;i++) {
            Candle x=cs.get(i);
            double range=Math.max(x.range(),1e-12);
            double p=clamp(x.body()/range,-1,1);
            double w=1.0+(i-from)/(double)Math.max(1,window);
            if(i<split) { earlyPressure+=p*w; earlyWeight+=w; }
            else { latePressure+=p*w; lateWeight+=w; }
            if(i>from) totalTravel+=Math.abs(x.close-cs.get(i-1).close);
        }
        earlyPressure=earlyWeight>0?earlyPressure/earlyWeight:0;
        latePressure=lateWeight>0?latePressure/lateWeight:0;
        double fading=latePressure-earlyPressure;
        double direction=netPath>0?1:netPath<0?-1:0;
        double displacement=Math.abs(netPath)/atr;
        if(direction==0||displacement<0.35) return 0;

        Candle last=cs.get(n-1),prev=cs.get(n-2);
        double range=Math.max(last.range(),1e-12);
        double closePos=(last.close-last.low)/range;
        double upper=last.high-Math.max(last.open,last.close);
        double lower=Math.min(last.open,last.close)-last.low;
        double body=last.body();
        boolean counterBody=body*direction<0;
        boolean counterClose=(last.close-prev.close)*direction<0;
        boolean pressureFading=fading*direction< -0.10;
        boolean strongFade=fading*direction< -0.28;
        boolean rejectedExtreme=direction>0
                ? upper>Math.max(last.bodyAbs()*0.85,atr*0.14)&&closePos<0.55
                : lower>Math.max(last.bodyAbs()*0.85,atr*0.14)&&closePos>0.45;

        // A close through the immediately preceding candle's opposite edge
        // is a fresh structure clue; a wick-only colour change is not enough.
        boolean structureBreak=direction>0
                ? last.close<prev.low || (last.close<prev.close&&last.low<prev.low)
                : last.close>prev.high || (last.close>prev.close&&last.high>prev.high);

        // Detect failed extension from the rolling window's extreme.
        double priorExtreme=direction>0?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        for(int i=from;i<n-1;i++) {
            Candle x=cs.get(i);
            priorExtreme=direction>0?Math.max(priorExtreme,x.high):Math.min(priorExtreme,x.low);
        }
        boolean failedExtension=direction>0
                ? last.high>priorExtreme&&last.close<priorExtreme
                : last.low<priorExtreme&&last.close>priorExtreme;

        // A directional run is meaningful only if price actually travelled;
        // choppy alternating candles with little net displacement are ignored.
        double efficiency=totalTravel>0?Math.abs(netPath)/totalTravel:0;
        double vote=0;
        if(pressureFading) vote-=0.28*direction;
        if(strongFade) vote-=0.22*direction;
        if(counterBody) vote-=0.22*direction;
        if(counterClose) vote-=0.30*direction;
        if(rejectedExtreme) vote-=0.42*direction;
        if(structureBreak) vote-=0.48*direction;
        if(failedExtension) vote-=0.38*direction;

        // Scale evidence by the strength of the preceding move, but cap it so
        // this module warns against chasing rather than forcing a reversal.
        double moveFactor=clamp((displacement-0.30)/1.20,0.35,1.0);
        double efficiencyFactor=efficiency>0.22?1.0:0.70;
        vote*=moveFactor*efficiencyFactor;
        if(structureBreak&& (rejectedExtreme||counterClose)) vote*=1.18;
        return clamp(vote,-1.35,1.35);
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
            // Count a floor/crest only if the extreme is recent and price
            // confirms a reclaim/rejection. Being inside an old range alone
            // must never manufacture a reversal vote.
            double bounce=last.close-lo;
            boolean recentLow=loAt>=n-6;
            boolean bullishReclaim=last.close>prev.close||last.close>prev.high;
            if(recentLow && bounce>atr*0.25 && closeLocation>0.58 && bullishReclaim) {
                vote+=Math.min(0.95,bounce/atr*0.28);
                if(lowerWick>Math.max(last.bodyAbs()*0.8,atr*0.10)) vote+=0.20;
                if(last.close>prev.high) vote+=0.25;
            }
            double drop=hi-last.close;
            boolean recentHigh=hiAt>=n-6;
            boolean bearishReject=last.close<prev.close||last.close<prev.low;
            if(recentHigh && drop>atr*0.25 && closeLocation<0.42 && bearishReject) {
                vote-=Math.min(0.95,drop/atr*0.28);
                if(upperWick>Math.max(last.bodyAbs()*0.8,atr*0.10)) vote-=0.20;
                if(last.close<prev.low) vote-=0.25;
            }
            // Tiny continuation credit requires a real higher-low/lower-high
            // step, not merely closing above/below the range midpoint.
            if(last.close>prev.close && last.low>prev.low && last.close>lo+atr*0.20)
                vote+=0.08;
            if(last.close<prev.close && last.high<prev.high && last.close<hi-atr*0.20)
                vote-=0.08;
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

    /** EMA stack agreement across 9/20/50/100/200 periods, ATR-normalized. */
    private double emaStackBias(double e9,double e20,double e50,double e100,
                                double e200,double atr,int samples) {
        if(atr<=0) return 0;
        double[] values={e9,e20,e50,e100,e200};
        // Require enough genuine live samples before long EMA pairs can vote.
        int[] warmup={60,150,300,600};
        double[] weightsByPair={1.0,0.9,0.7,0.5};
        double score=0, weights=0;
        for(int i=0;i<4;i++) {
            if(samples<warmup[i]) continue;
            double separation=(values[i]-values[i+1])/atr;
            score+=clamp(separation/0.35,-1,1)*weightsByPair[i];
            weights+=weightsByPair[i];
        }
        return weights>0?score/weights:0;
    }

    /** RSI7/RSI14 momentum with 30/50/70 context; extremes alone do not reverse trend. */
    private double rsiMomentumBias(List<Double> closes,double rsi7,double rsi14) {
        if(closes.size()<9) return 0;
        double prior7=rsiWilder(closes.subList(0,closes.size()-1),7);
        double fast=clamp((rsi7-50.0)/25.0,-1,1);
        double standard=clamp((rsi14-50.0)/30.0,-1,1);
        double acceleration=clamp((rsi7-prior7)/12.0,-1,1);
        double vote=0.50*fast+0.30*standard+0.20*acceleration;
        if(rsi7>=70&&acceleration<0) vote-=0.12;
        if(rsi7<=30&&acceleration>0) vote+=0.12;
        return clamp(vote,-1,1);
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
            Double.NaN,0,liveCandles.size());
    }
}
