package com.aadi.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONArray;

/**
 * Transparent multi-layer next-candle engine.
 * Production inputs are real Market-QX ticks only. No synthetic prices/candles
 * are generated. The accuracy figure is measured from completed predictions,
 * never estimated from indicator strength.
 */
public final class SignalEngine {
    public static final class Result {
        public final String asset, signal, reason, regime;
        public final double price, emaFast, emaSlow, rsi, momentum;
        public final double trendScore, priceActionScore, patternScore, momentumScore;
        public final double accuracyPct;
        public final int score, closedCandles, accuracySamples, accuracyWins;

        Result(String a, String s, String why, String marketRegime, double p,
               double ef, double es, double r, double m,
               double trend, double action, double pattern, double momentumLayer,
               int aligned, int candles, double accuracy, int samples, int wins) {
            asset=a; signal=s; reason=why; regime=marketRegime; price=p;
            emaFast=ef; emaSlow=es; rsi=r; momentum=m;
            trendScore=trend; priceActionScore=action; patternScore=pattern;
            momentumScore=momentumLayer; score=aligned; closedCandles=candles;
            accuracyPct=accuracy; accuracySamples=samples; accuracyWins=wins;
        }
    }

    private static final long CANDLE_SECONDS = 10L;
    private static final int MIN_CANDLES = 50;
    private static final int MAX_CANDLES = 800;
    private static final int ACCURACY_WINDOW = 100;
    private static final int ACCURACY_MIN_SAMPLE = 20;

    private static final class Candle {
        final long time;
        final double open, high, low, close;
        Candle(long t, double o, double h, double l, double c) {
            time=t; open=o; high=h; low=l; close=c;
        }
        double range() { return Math.max(0.0, high-low); }
        double body() { return close-open; }
        double bodyAbs() { return Math.abs(close-open); }
    }

    private static final class Prediction {
        final long targetBucket;
        final String signal;
        Prediction(long bucket, String s) { targetBucket=bucket; signal=s; }
    }

    private final Deque<Candle> candles = new ArrayDeque<>();
    private final Deque<Boolean> outcomes = new ArrayDeque<>();
    private String asset;
    private long latestFeedTime=-1L, liveBucket=-1L;
    private Candle forming;
    private int liveTickCount;
    private double lastPrice=Double.NaN;
    private Result latestResult;
    private Result pendingForecast;
    private Prediction activePrediction;
    private int wins;

    /** Accept only positive, timestamped ticks for the currently selected asset. */
    public synchronized Result onTick(String symbol, double price, double timestamp) {
        if (symbol==null || symbol.trim().isEmpty() || !Double.isFinite(price) || price<=0
                || !Double.isFinite(timestamp) || timestamp<=0) return currentResult();
        if (timestamp>10000000000.0) timestamp/=1000.0;
        long ts=(long)timestamp;
        if (ts<=0) return currentResult();

        if (asset==null || !asset.equals(symbol)) resetForAsset(symbol);
        if (latestFeedTime>0 && ts<latestFeedTime) return currentResult();
        latestFeedTime=ts;
        lastPrice=price;
        long bucket=(ts/CANDLE_SECONDS)*CANDLE_SECONDS;

        if (forming==null) {
            startCandle(bucket, price);
            return currentResult();
        }

        if (bucket>liveBucket) {
            closeCurrentCandle();
            if (pendingForecast==null) pendingForecast=analyse(new ArrayList<>(candles));
            publishForecast(bucket, price);
            startCandle(bucket, price);
            return currentResult();
        }
        if (bucket<liveBucket) return currentResult();

        forming=new Candle(liveBucket, forming.open,
                Math.max(forming.high,price), Math.min(forming.low,price), price);
        liveTickCount++;

        // Refresh the private preview during the final second. It is not shown
        // or counted as a signal until the next candle actually starts.
        if ((ts%CANDLE_SECONDS)>=CANDLE_SECONDS-1 && liveTickCount>=2) {
            pendingForecast=analyse(windowWithForming());
        }
        return currentResult();
    }

    private void resetForAsset(String symbol) {
        asset=symbol;
        candles.clear();
        outcomes.clear();
        wins=0;
        latestFeedTime=-1L;
        liveBucket=-1L;
        forming=null;
        liveTickCount=0;
        lastPrice=Double.NaN;
        latestResult=null;
        pendingForecast=null;
        activePrediction=null;
    }

    private void startCandle(long bucket, double price) {
        liveBucket=bucket;
        forming=new Candle(bucket,price,price,price,price);
        liveTickCount=1;
    }

    private void closeCurrentCandle() {
        if (forming==null) return;
        settlePrediction(forming);
        if (candles.isEmpty() || candles.peekLast().time<forming.time) {
            candles.addLast(forming);
            while (candles.size()>MAX_CANDLES) candles.removeFirst();
        }
    }

    private void settlePrediction(Candle completed) {
        if (activePrediction==null || activePrediction.targetBucket!=completed.time) return;
        // A flat close is scored as a loss: a directional binary outcome did not occur.
        boolean up=completed.close>completed.open;
        boolean down=completed.close<completed.open;
        boolean won=("CALL".equals(activePrediction.signal)&&up)
                || ("PUT".equals(activePrediction.signal)&&down);
        outcomes.addLast(won);
        if (won) wins++;
        while (outcomes.size()>ACCURACY_WINDOW) {
            if (outcomes.removeFirst()) wins--;
        }
        activePrediction=null;
    }

    private void publishForecast(long targetBucket, double price) {
        Result forecast=pendingForecast;
        pendingForecast=null;
        if (forecast==null) {
            latestResult=waiting("Waiting for a complete live-candle analysis",price);
            activePrediction=null;
            return;
        }
        latestResult=copyAtPrice(forecast,price);
        if ("CALL".equals(forecast.signal)||"PUT".equals(forecast.signal)) {
            activePrediction=new Prediction(targetBucket,forecast.signal);
        } else {
            activePrediction=null;
        }
    }

    private List<Candle> windowWithForming() {
        List<Candle> rows=new ArrayList<>(candles);
        if (forming!=null) rows.add(forming);
        return rows;
    }

    /** History packets are not currently wired into the Android feed. Keep this
     * compatibility method fail-closed; unverified history is never injected. */
    public synchronized int importHistoricalCandles(String symbol, JSONArray rows) {
        return 0;
    }

    public synchronized Result currentResult() {
        if (latestResult!=null) return latestResult;
        return waiting(asset==null ? "Waiting for selected Market-QX asset" :
                "Collecting real candles • "+candles.size()+"/"+MIN_CANDLES, lastPrice);
    }

    private Result analyse(List<Candle> cs) {
        int n=cs.size();
        if (n<MIN_CANDLES) {
            return waiting("WARM-UP • "+n+"/"+MIN_CANDLES+" completed real candles",lastPrice);
        }
        double atr=atr(cs,14);
        if (!Double.isFinite(atr)||atr<=1e-12) {
            return waiting("NO TRADE • insufficient real price movement",lastPrice);
        }

        double e9=ema(cs,9), e21=ema(cs,21), e50=ema(cs,50);
        double rsi=rsi(cs,14);
        double momentum=cs.get(n-1).close-cs.get(n-4).close;
        double trend=trendLayer(cs,atr,e9,e21,e50);
        double action=priceActionLayer(cs,atr,trend);
        double pattern=patternLayer(cs,atr);
        double momentumLayer=momentumLayer(cs,atr,rsi);
        double efficiency=efficiencyRatio(cs,20);

        String regime;
        if (Math.abs(trend)>=0.34 && efficiency>=0.24) {
            regime=trend>0 ? "UPTREND" : "DOWNTREND";
        } else if (efficiency<0.16) {
            regime="RANGE / CHOP";
        } else {
            regime="TRANSITION";
        }

        int direction=0, aligned=0;
        double weighted=0.30*trend+0.30*action+0.20*pattern+0.20*momentumLayer;
        int upVotes=(trend>0.18?1:0)+(action>0.18?1:0)
                +(pattern>0.18?1:0)+(momentumLayer>0.18?1:0);
        int downVotes=(trend< -0.18?1:0)+(action< -0.18?1:0)
                +(pattern< -0.18?1:0)+(momentumLayer< -0.18?1:0);

        if ("UPTREND".equals(regime) || "DOWNTREND".equals(regime)) {
            int trendDirection=trend>0?1:-1;
            boolean coreAgreement=sign(action)==trendDirection
                    && (sign(momentumLayer)==trendDirection || sign(pattern)==trendDirection);
            boolean noStrongOpposition=(trendDirection>0 ? downVotes==0 : upVotes==0);
            if (coreAgreement && noStrongOpposition
                    && trendDirection*weighted>=0.36
                    && (trendDirection>0?upVotes:downVotes)>=3) {
                direction=trendDirection;
            }
        } else if ("RANGE / CHOP".equals(regime)) {
            // In a range, only a clear rejection pattern plus price-action
            // confirmation may produce a reversal signal. Trend-following alone
            // is deliberately blocked.
            if (action>=0.42 && pattern>=0.48 && momentumLayer> -0.18
                    && weighted>=0.28) direction=1;
            else if (action<=-0.42 && pattern<=-0.48 && momentumLayer<0.18
                    && weighted<=-0.28) direction=-1;
        }

        String signal=direction>0?"CALL":direction<0?"PUT":"WAIT";
        aligned=direction>0?upVotes:direction<0?downVotes:Math.max(upVotes,downVotes);
        String reason;
        if ("WAIT".equals(signal)) {
            reason="WAIT • "+regime+" • layers not sufficiently aligned";
        } else {
            reason=signal+" • "+regime+" • Trend "+signed(trend)
                    +" | Price action "+signed(action)
                    +" | Pattern "+signed(pattern)
                    +" | Momentum "+signed(momentumLayer)
                    +" | Agreement "+aligned+"/4";
        }

        double accuracy=outcomes.size()>=ACCURACY_MIN_SAMPLE
                ? (100.0*wins/outcomes.size()) : -1.0;
        return new Result(asset,signal,reason,regime,cs.get(n-1).close,e9,e21,rsi,
                momentum,trend,action,pattern,momentumLayer,aligned,n,
                accuracy,outcomes.size(),wins);
    }

    private Result waiting(String why,double price) {
        double accuracy=outcomes.size()>=ACCURACY_MIN_SAMPLE
                ? 100.0*wins/outcomes.size() : -1.0;
        return new Result(asset,"WAIT",why,"WARM-UP",price,Double.NaN,Double.NaN,
                Double.NaN,Double.NaN,0,0,0,0,0,candles.size(),
                accuracy,outcomes.size(),wins);
    }

    private Result copyAtPrice(Result r,double price) {
        double accuracy=outcomes.size()>=ACCURACY_MIN_SAMPLE
                ? 100.0*wins/outcomes.size() : -1.0;
        return new Result(r.asset,r.signal,r.reason,r.regime,price,r.emaFast,r.emaSlow,
                r.rsi,r.momentum,r.trendScore,r.priceActionScore,r.patternScore,
                r.momentumScore,r.score,r.closedCandles,accuracy,
                outcomes.size(),wins);
    }

    private double trendLayer(List<Candle> cs,double atr,double e9,double e21,double e50) {
        int n=cs.size();
        double emaFast=clamp((e9-e21)/(atr*1.4),-1,1);
        double emaSlow=clamp((e21-e50)/(atr*2.2),-1,1);
        double slope=clamp((cs.get(n-1).close-cs.get(n-6).close)/(atr*2.5),-1,1);
        int structure=swingStructure(cs);
        return clamp(0.30*emaFast+0.25*emaSlow+0.25*slope+0.20*structure,-1,1);
    }

    private double priceActionLayer(List<Candle> cs,double atr,double trend) {
        int n=cs.size();
        Candle last=cs.get(n-1), prev=cs.get(n-2);
        double range=Math.max(last.range(),atr*0.02);
        double body=clamp(last.body()/range,-1,1);
        double closeLocation=clamp(((last.close-last.low)/range)*2.0-1.0,-1,1);
        double displacement=clamp((last.close-prev.close)/(atr*0.8),-1,1);
        double breakout=0;
        int from=Math.max(0,n-11);
        double priorHigh=Double.NEGATIVE_INFINITY, priorLow=Double.POSITIVE_INFINITY;
        for(int i=from;i<n-1;i++) {
            priorHigh=Math.max(priorHigh,cs.get(i).high);
            priorLow=Math.min(priorLow,cs.get(i).low);
        }
        if(last.close>priorHigh) breakout=0.85;
        else if(last.close<priorLow) breakout=-0.85;
        else if(last.high>priorHigh && last.close<priorHigh) breakout=-0.55;
        else if(last.low<priorLow && last.close>priorLow) breakout=0.55;

        double pullback=0;
        if(trend>0.22 && last.close<last.open && last.low>prev.low
                && last.close>cs.get(n-5).close) pullback=0.35;
        if(trend< -0.22 && last.close>last.open && last.high<prev.high
                && last.close<cs.get(n-5).close) pullback=-0.35;
        return clamp(0.25*body+0.25*closeLocation+0.35*breakout
                +0.15*clamp(displacement+pullback,-1,1),-1,1);
    }

    private double patternLayer(List<Candle> cs,double atr) {
        int n=cs.size();
        if(n<4) return 0;
        Candle a=cs.get(n-3), p=cs.get(n-2), c=cs.get(n-1);
        double range=Math.max(c.range(),atr*0.02);
        double body=Math.max(c.bodyAbs(),atr*0.015);
        double upper=c.high-Math.max(c.open,c.close);
        double lower=Math.min(c.open,c.close)-c.low;
        double closePos=(c.close-c.low)/range;
        double vote=0;

        boolean bullEngulf=c.close>c.open && p.close<p.open
                && c.open<=p.close && c.close>=p.open
                && c.bodyAbs()>p.bodyAbs()*1.05;
        boolean bearEngulf=c.close<c.open && p.close>p.open
                && c.open>=p.close && c.close<=p.open
                && c.bodyAbs()>p.bodyAbs()*1.05;
        if(bullEngulf) vote+=0.85;
        if(bearEngulf) vote-=0.85;

        boolean hammer=lower>=body*1.8 && closePos>=0.65
                && upper<=Math.max(body*0.8,atr*0.08);
        boolean shootingStar=upper>=body*1.8 && closePos<=0.35
                && lower<=Math.max(body*0.8,atr*0.08);
        double priorMove=c.close-a.open;
        if(hammer && priorMove< -atr*0.25) vote+=0.75;
        if(shootingStar && priorMove>atr*0.25) vote-=0.75;

        boolean morning=a.close<a.open && p.bodyAbs()<a.bodyAbs()*0.55
                && c.close>c.open && c.close>(a.open+a.close)*0.5;
        boolean evening=a.close>a.open && p.bodyAbs()<a.bodyAbs()*0.55
                && c.close<c.open && c.close<(a.open+a.close)*0.5;
        if(morning) vote+=0.55;
        if(evening) vote-=0.55;

        boolean doji=c.bodyAbs()/range<0.12;
        if(doji) vote*=0.25; // indecision is not a reversal by itself

        // Double top/bottom is counted only when the second test rejects
        // the prior swing level and closes back inside the range.
        double tolerance=atr*0.18;
        if(Math.abs(c.high-p.high)<=tolerance && c.close<p.high-tolerance*0.15)
            vote-=0.35;
        if(Math.abs(c.low-p.low)<=tolerance && c.close>p.low+tolerance*0.15)
            vote+=0.35;
        return clamp(vote,-1,1);
    }

    private double momentumLayer(List<Candle> cs,double atr,double rsi) {
        int n=cs.size();
        double rsiVote=clamp((rsi-50.0)/25.0,-1,1);
        double displacement=clamp((cs.get(n-1).close-cs.get(n-6).close)/(atr*2.0),-1,1);
        double macd=ema(cs,12)-ema(cs,26);
        double macdVote=clamp(macd/(atr*1.5),-1,1);
        // RSI extremes are damped; they are context, not automatic reversal calls.
        if(rsi>78 && displacement>0) rsiVote*=0.45;
        if(rsi<22 && displacement<0) rsiVote*=0.45;
        return clamp(0.35*rsiVote+0.35*displacement+0.30*macdVote,-1,1);
    }

    private int swingStructure(List<Candle> cs) {
        int n=cs.size(), from=Math.max(0,n-24);
        List<Double> highs=new ArrayList<>(), lows=new ArrayList<>();
        for(int i=from+1;i<n-1;i++) {
            Candle p=cs.get(i-1), c=cs.get(i), q=cs.get(i+1);
            if(c.high>=p.high && c.high>q.high) highs.add(c.high);
            if(c.low<=p.low && c.low<q.low) lows.add(c.low);
        }
        if(highs.size()<2 || lows.size()<2) {
            double mid=(cs.get(from).high+cs.get(from).low)*0.5;
            return cs.get(n-1).close>mid?1:cs.get(n-1).close<mid?-1:0;
        }
        double h0=highs.get(highs.size()-2), h1=highs.get(highs.size()-1);
        double l0=lows.get(lows.size()-2), l1=lows.get(lows.size()-1);
        if(h1>h0 && l1>l0) return 1;
        if(h1<h0 && l1<l0) return -1;
        return 0;
    }

    private double efficiencyRatio(List<Candle> cs,int period) {
        int n=cs.size(), from=Math.max(0,n-period);
        double travel=0;
        for(int i=from+1;i<n;i++) travel+=Math.abs(cs.get(i).close-cs.get(i-1).close);
        if(travel<=1e-12) return 0;
        return clamp(Math.abs(cs.get(n-1).close-cs.get(from).close)/travel,0,1);
    }

    private double atr(List<Candle> cs,int period) {
        int n=cs.size(), from=Math.max(1,n-period);
        double sum=0; int count=0;
        for(int i=from;i<n;i++) {
            Candle c=cs.get(i), p=cs.get(i-1);
            sum+=Math.max(c.high-c.low,Math.max(Math.abs(c.high-p.close),Math.abs(c.low-p.close)));
            count++;
        }
        return count==0?0:sum/count;
    }

    private double ema(List<Candle> cs,int period) {
        int n=cs.size();
        if(n<period) return cs.get(n-1).close;
        double value=0;
        for(int i=0;i<period;i++) value+=cs.get(i).close;
        value/=period;
        double k=2.0/(period+1.0);
        for(int i=period;i<n;i++) value=cs.get(i).close*k+value*(1-k);
        return value;
    }

    private double rsi(List<Candle> cs,int period) {
        int n=cs.size();
        if(n<period+1) return 50;
        double gain=0,loss=0;
        for(int i=n-period;i<n;i++) {
            double change=cs.get(i).close-cs.get(i-1).close;
            gain+=Math.max(change,0);
            loss+=Math.max(-change,0);
        }
        if(loss<=1e-12) return 100;
        return 100.0-100.0/(1.0+gain/loss);
    }

    private int sign(double v) { return v>0.18?1:v< -0.18?-1:0; }
    private double clamp(double v,double lo,double hi) { return Math.max(lo,Math.min(hi,v)); }
    private String signed(double v) { return String.format(java.util.Locale.US,"%+.2f",v); }
}
