package com.aadi.bot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Five-second directional confirmation and outcome tracker, driven only by live QX ticks. */
public final class FiveSecondSignalEngine {
    public static final class Result {
        public final String signal,reason,analysis,auditEvents;
        Result(String s,String r,String a){this(s,r,a,"[]");}
        Result(String s,String r,String a,String events){signal=s;reason=r;analysis=a;auditEvents=events==null?"[]":events;}
    }
    private static final class Tick { final long t; final double p; Tick(long t,double p){this.t=t;this.p=p;} }
    private static final class Pending {
        final String side,baseSignal,dailySummary,baseAnalysis;
        final long at; final double price,move,recent; final int dailyBias,tickCount;
        boolean held=true,feedContinuous=true; long lastSeenAt;
        Pending(String s,long t,double p,String bs,int db,String ds,String ba,double m,double r,int tc){
            side=s;at=t;price=p;lastSeenAt=t;baseSignal=bs;dailyBias=db;dailySummary=ds;
            baseAnalysis=ba;move=m;recent=r;tickCount=tc;
        }
    }
    private final Deque<Tick> ticks=new ArrayDeque<>();
    private String lastIssuedSide="";
    private double lastIssuedPrice=Double.NaN;
    private int sameSideStreak=0;
    private String asset="";
    private Pending pending;
    private long lastIssued=0;
    private int settled=0,wins=0;
    private String lastOutcome="No 10s result settled yet";
    private Result latest=new Result("WAIT","Waiting for 10-second live confirmation","10-SECOND ENGINE: warming up");

    public synchronized void reset(String newAsset){
        asset=newAsset==null?"":newAsset;ticks.clear();pending=null;lastIssued=0;settled=0;wins=0;lastIssuedSide="";lastIssuedPrice=Double.NaN;sameSideStreak=0;
        lastOutcome="No 5s result settled yet";
        latest=new Result("WAIT","New asset selected • collecting fresh ticks","5-SECOND ENGINE: waiting for new asset ticks");
    }

    public synchronized Result onTick(String symbol,double price,long timeMs,String baseSignal,
                                      int dailyBias,String dailySummary,String baseAnalysis) {
        if(symbol==null||symbol.isEmpty()||!Double.isFinite(price)||price<=0||timeMs<=0)
            return latest;
        if(!asset.equals(symbol))reset(symbol);
        if(!ticks.isEmpty()&&timeMs<ticks.peekLast().t)return latest;
        JSONArray audit=new JSONArray();
        ticks.addLast(new Tick(timeMs,price));
        while(!ticks.isEmpty()&&timeMs-ticks.peekFirst().t>15000L)ticks.removeFirst();
        if(pending!=null){
            if(timeMs-pending.lastSeenAt>1500L) pending.feedContinuous=false;
            pending.lastSeenAt=timeMs;
            if(("CALL".equals(pending.side)&&price<=pending.price)
                    ||("PUT".equals(pending.side)&&price>=pending.price)) pending.held=false;
        }

        if(pending!=null&&timeMs>=pending.at+10000L){
            long expiryAt=pending.at+10000L;
            Tick expiryQuote=null;
            long bestGap=Long.MAX_VALUE;
            for(Tick t:ticks){
                long gap=Math.abs(t.t-expiryAt);
                if(gap<=1000L&&gap<bestGap){bestGap=gap;expiryQuote=t;}
            }
            if(expiryQuote!=null&&pending.feedContinuous){
                double expiryPrice=expiryQuote.p;
                boolean finalSide="CALL".equals(pending.side)?expiryPrice>pending.price:expiryPrice<pending.price;
                boolean win=finalSide;
                JSONObject settledEvent=new JSONObject();
                try {
                    settledEvent.put("type","SETTLED");settledEvent.put("tradeId",Long.toString(pending.at));settledEvent.put("asset",asset);
                    settledEvent.put("signal",pending.side);settledEvent.put("entryTimeMs",pending.at);
                    settledEvent.put("entryPrice",pending.price);settledEvent.put("expiryTimeMs",expiryAt);
                    settledEvent.put("expiryPrice",expiryPrice);settledEvent.put("heldAboveBelowEntry",pending.held);
                    settledEvent.put("feedContinuous",pending.feedContinuous);
                    settledEvent.put("baseSignal",pending.baseSignal);settledEvent.put("dailyBias",pending.dailyBias);
                    settledEvent.put("dailySummary",pending.dailySummary);settledEvent.put("baseAnalysis",pending.baseAnalysis);
                    settledEvent.put("microMove3s",pending.move);settledEvent.put("microMove1s",pending.recent);
                    settledEvent.put("tickCountAtEntry",pending.tickCount);
                    settledEvent.put("outcome",expiryPrice==pending.price?"FLAT":win?"WIN":"LOSS");
                    settledEvent.put("settledCount",settled+(expiryPrice==pending.price?0:1));
                    settledEvent.put("wins",wins+(! (expiryPrice==pending.price) && win?1:0));
                } catch(Exception ignored) {}
                audit.put(settledEvent);
                if(expiryPrice==pending.price)lastOutcome="10s FLAT • not counted";
                else {settled++;if(win)wins++;lastOutcome="10s "+(win?"WIN":"LOSS")+" • "+pending.side+(pending.held?"":" • crossed entry during hold")+" • "+String.format(Locale.US,"%.6f → %.6f",pending.price,expiryPrice);}
                pending=null;
            } else if(expiryQuote!=null&&!pending.feedContinuous){
                JSONObject unverified=new JSONObject();
                try {
                    unverified.put("type","UNVERIFIED");unverified.put("tradeId",Long.toString(pending.at));unverified.put("asset",asset);
                    unverified.put("signal",pending.side);unverified.put("entryTimeMs",pending.at);
                    unverified.put("entryPrice",pending.price);unverified.put("expiryTimeMs",expiryAt);
                    unverified.put("feedContinuous",false);unverified.put("reason","Live tick stream had a gap over 1.5 seconds");
                    unverified.put("baseSignal",pending.baseSignal);unverified.put("dailyBias",pending.dailyBias);
                    unverified.put("dailySummary",pending.dailySummary);unverified.put("baseAnalysis",pending.baseAnalysis);
                    unverified.put("microMove3s",pending.move);unverified.put("microMove1s",pending.recent);
                } catch(Exception ignored) {}
                audit.put(unverified);
                lastOutcome="10s UNVERIFIED • live tick stream had a gap over 1.5s";
                pending=null;
            } else if(timeMs>expiryAt+1500L){
                JSONObject unverified=new JSONObject();
                try {
                    unverified.put("type","UNVERIFIED");unverified.put("tradeId",Long.toString(pending.at));unverified.put("asset",asset);
                    unverified.put("signal",pending.side);unverified.put("entryTimeMs",pending.at);
                    unverified.put("entryPrice",pending.price);unverified.put("expiryTimeMs",expiryAt);
                    unverified.put("feedContinuous",pending.feedContinuous);unverified.put("reason","No quote within 1 second of 10-second expiry");
                    unverified.put("baseSignal",pending.baseSignal);unverified.put("dailyBias",pending.dailyBias);
                    unverified.put("dailySummary",pending.dailySummary);unverified.put("baseAnalysis",pending.baseAnalysis);
                    unverified.put("microMove3s",pending.move);unverified.put("microMove1s",pending.recent);
                } catch(Exception ignored) {}
                audit.put(unverified);
                lastOutcome="10s UNVERIFIED • no quote within 1s of expiry";
                pending=null;
            }
        }
        if(pending!=null){
            latest=new Result(pending.side,"10-second trade active • 10-second expiry check in progress",
                    baseAnalysis+"\n"+dailySummary+"\n10s ENTRY: "+String.format(Locale.US,"%.6f",pending.price)+" | expiry in "+Math.max(0,10000-(timeMs-pending.at))+"ms\n"+lastOutcome+"\n"+performanceLine(),
                    audit.toString());
            return latest;
        }

        Tick old=null,near=null;
        for(Tick t:ticks){
            if(timeMs-t.t>=1800L&&timeMs-t.t<=3200L)old=t;
            if(timeMs-t.t>=400L&&timeMs-t.t<=1300L)near=t;
        }
        int side="CALL".equals(baseSignal)?1:"PUT".equals(baseSignal)?-1:0;
        // Daily bias is recorded as context, not used as a hard veto: OTC daily
        // references can be mixed/incomplete while short-term live momentum is clear.
        boolean microReady=old!=null&&near!=null&&ticks.size()>=3;
        double move=old==null?0:price-old.p;
        double recent=near==null?0:price-near.p;
        boolean netMicroAgree=side>0?move>0&&recent>0:side<0&&move<0&&recent<0;
        // Inspect the actual tick path, not only its net displacement. Ignore
        // microscopic changes below 2% ATR to avoid counting quote jitter.
        double tickAtr=extractMetric(baseAnalysis,"ATR");
        double minTickMove=Double.isFinite(tickAtr)&&tickAtr>0?tickAtr*0.02:0.0;
        int tickReversals=0,previousTickDirection=0,latestTickDirection=0;
        long latestDirectionalTickAt=0;
        Tick previousTick=null;
        for(Tick currentTick:ticks){
            if(timeMs-currentTick.t>3000L) { previousTick=currentTick; continue; }
            if(previousTick!=null&&timeMs-previousTick.t<=3000L){
                double tickDelta=currentTick.p-previousTick.p;
                if(Math.abs(tickDelta)>minTickMove){
                    int direction=tickDelta>0?1:-1;
                    if(previousTickDirection!=0&&direction!=previousTickDirection)tickReversals++;
                    previousTickDirection=direction;
                    latestTickDirection=direction;
                    latestDirectionalTickAt=currentTick.t;
                }
            }
            previousTick=currentTick;
        }
        boolean latestTickFresh=latestDirectionalTickAt>0&&timeMs-latestDirectionalTickAt<=700L;
        boolean latestTickAgree=side>0?latestTickDirection>0:side<0&&latestTickDirection<0;
        boolean choppyTickFlow=tickReversals>=3;
        boolean microAgree=netMicroAgree&&latestTickFresh&&latestTickAgree&&!choppyTickFlow;
        boolean dailyConflict=(side>0&&dailyBias<0)||(side<0&&dailyBias>0);
        boolean trendEmaConflict=side>0
                ? baseAnalysis!=null&&baseAnalysis.contains("MARKET TREND: DOWNTREND")
                    &&baseAnalysis.contains("EMA stack BEARISH")
                : side<0&&baseAnalysis!=null&&baseAnalysis.contains("MARKET TREND: UPTREND")
                    &&baseAnalysis.contains("EMA stack BULLISH");
        // Avoid chasing an exhausted move when the oscillator is stretched
        // and MACD has already turned against the proposed direction.
        double auditRsi14=extractMetric(baseAnalysis,"RSI14");
        double auditRsi7=extractMetric(baseAnalysis,"RSI7");
        double auditRsi7Delta=extractMetric(baseAnalysis,"RSI7delta");
        boolean macdBullish=baseAnalysis!=null&&baseAnalysis.contains("MACD BULLISH");
        boolean macdBearish=baseAnalysis!=null&&baseAnalysis.contains("MACD BEARISH");
        boolean exhaustionConflict=(side<0&&auditRsi14>0&&auditRsi14<=30.0&&macdBullish)
                ||(side>0&&auditRsi14>=70.0&&macdBearish);
        // Live-market quality gate: do not veto a signal because of the prior-day
        // reference. Instead count independent live trend/momentum evidence opposing it.
        double slope5=extractMetric(baseAnalysis,"slope5");
        double slope12=extractMetric(baseAnalysis,"slope12");
        double supertrend=extractMetric(baseAnalysis,"Supertrend");
        double dominant=extractMetric(baseAnalysis,"dominant");
        double dmiVote=extractMetric(baseAnalysis,"DMIvote");
        double diPlus=extractMetric(baseAnalysis,"DI+");
        double diMinus=extractMetric(baseAnalysis,"DI-");
        int opposingLiveVotes=0;
        if(side>0){
            if(baseAnalysis!=null&&baseAnalysis.contains("MARKET TREND: DOWNTREND"))opposingLiveVotes++;
            if(Double.isFinite(dominant)&&dominant<0)opposingLiveVotes++;
            if(Double.isFinite(slope5)&&slope5<0)opposingLiveVotes++;
            if(Double.isFinite(slope12)&&slope12<0)opposingLiveVotes++;
            if(Double.isFinite(supertrend)&&supertrend<0)opposingLiveVotes++;
            if(macdBearish)opposingLiveVotes++;
            if(Double.isFinite(dmiVote)&&dmiVote< -0.15)opposingLiveVotes++;
            if(Double.isFinite(diPlus)&&Double.isFinite(diMinus)&&diMinus>diPlus)opposingLiveVotes++;
        } else if(side<0){
            if(baseAnalysis!=null&&baseAnalysis.contains("MARKET TREND: UPTREND"))opposingLiveVotes++;
            if(Double.isFinite(dominant)&&dominant>0)opposingLiveVotes++;
            if(Double.isFinite(slope5)&&slope5>0)opposingLiveVotes++;
            if(Double.isFinite(slope12)&&slope12>0)opposingLiveVotes++;
            if(Double.isFinite(supertrend)&&supertrend>0)opposingLiveVotes++;
            if(macdBullish)opposingLiveVotes++;
            if(Double.isFinite(dmiVote)&&dmiVote>0.15)opposingLiveVotes++;
            if(Double.isFinite(diPlus)&&Double.isFinite(diMinus)&&diPlus>diMinus)opposingLiveVotes++;
        }
        // Price-action evidence is evaluated separately from the broad trend votes.
        // This prevents a bullish EMA/DI stack from hiding a short-term rollover.
        double atr=extractMetric(baseAnalysis,"ATR");
        double fastMove=extractMetric(baseAnalysis,"fastMove");
        double pressure=extractMetric(baseAnalysis,"pressure");
        double flow=extractMetric(baseAnalysis,"flow");
        double reversalBias=extractMetric(baseAnalysis,"reversalBias");
        double recentBias=extractMetric(baseAnalysis,"recentBias");
        double supportLevel=extractMetric(baseAnalysis,"support");
        double resistanceLevel=extractMetric(baseAnalysis,"resistance");
        double supportDistanceAtr=Double.isFinite(atr)&&atr>0&&Double.isFinite(supportLevel)
                ?(price-supportLevel)/atr:Double.NaN;
        double resistanceDistanceAtr=Double.isFinite(atr)&&atr>0&&Double.isFinite(resistanceLevel)
                ?(resistanceLevel-price)/atr:Double.NaN;
        double opposingLevelDistanceAtr=side>0?resistanceDistanceAtr:supportDistanceAtr;
        boolean momentumFading=baseAnalysis!=null&&baseAnalysis.contains("momentumFading true");
        boolean confirmedBreakout=side>0
                ?baseAnalysis!=null&&baseAnalysis.contains("BULLISH BREAKOUT")&&Double.isFinite(resistanceLevel)&&price>resistanceLevel
                :side<0&&baseAnalysis!=null&&baseAnalysis.contains("BEARISH BREAKDOWN")
                    &&Double.isFinite(supportLevel)&&price<supportLevel;
        boolean fadingAtLevel=momentumFading
                ||(side>0&&Double.isFinite(reversalBias)&&reversalBias< -0.08)
                ||(side<0&&Double.isFinite(reversalBias)&&reversalBias>0.08);
        EntryQualityGate.Decision entryQuality=EntryQualityGate.evaluate(side,atr,fastMove,
                slope5,slope12,recentBias,reversalBias,flow,pressure,auditRsi7,auditRsi7Delta,
                opposingLevelDistanceAtr,confirmedBreakout,fadingAtLevel);
        int opposingPriceVotes=0;
        if(side>0){
            if(Double.isFinite(flow)&&flow< -0.08)opposingPriceVotes++;
            if(Double.isFinite(pressure)&&pressure< -0.12)opposingPriceVotes++;
            if(Double.isFinite(fastMove)&&Double.isFinite(atr)&&atr>0&&fastMove< -atr*0.10)opposingPriceVotes++;
            if(Double.isFinite(reversalBias)&&reversalBias< -0.12)opposingPriceVotes++;
        } else if(side<0){
            if(Double.isFinite(flow)&&flow>0.08)opposingPriceVotes++;
            if(Double.isFinite(pressure)&&pressure>0.12)opposingPriceVotes++;
            if(Double.isFinite(fastMove)&&Double.isFinite(atr)&&atr>0&&fastMove>atr*0.10)opposingPriceVotes++;
            if(Double.isFinite(reversalBias)&&reversalBias>0.12)opposingPriceVotes++;
        }
        // A very large counter-move cannot be dismissed as a harmless pullback
        // unless the reversal layer itself confirms the proposed entry.
        boolean strongCounterMove=side>0
                ? Double.isFinite(fastMove)&&Double.isFinite(atr)&&atr>0
                    &&fastMove< -atr*0.65&&(!Double.isFinite(reversalBias)||reversalBias<0.18)
                : side<0&&Double.isFinite(fastMove)&&Double.isFinite(atr)&&atr>0
                    &&fastMove>atr*0.65&&(!Double.isFinite(reversalBias)||reversalBias> -0.18);
        boolean liveConflict=opposingLiveVotes>=2
                ||(opposingLiveVotes>=1&&opposingPriceVotes>=1)
                ||opposingPriceVotes>=2||strongCounterMove;
        double[] forecastThreshold=extractForecastThreshold(baseAnalysis);
        boolean weakForecastMargin=forecastThreshold!=null
                &&Math.abs(forecastThreshold[0])-forecastThreshold[1]<forecastThreshold[1]*0.12;
        boolean counterTrendConflict=trendEmaConflict||exhaustionConflict||liveConflict
                ||weakForecastMargin||entryQuality.blocked;
        String proposedSide=side>0?"CALL":side<0?"PUT":"";
        // Prevent repeated entries in the same direction/price zone. A follow-up
        // needs fresh price progress scaled to ATR; later repeats need more progress.
        double repeatProgress=Double.isFinite(lastIssuedPrice)&&Double.isFinite(tickAtr)&&tickAtr>0
                ?(side>0?price-lastIssuedPrice:lastIssuedPrice-price):Double.NaN;
        double requiredProgress=tickAtr>0&&Double.isFinite(tickAtr)
                ?tickAtr*(sameSideStreak<=1?0.10:0.18):Double.POSITIVE_INFINITY;
        boolean repeatedSideQuality=side==0||!proposedSide.equals(lastIssuedSide)
                ||(Double.isFinite(repeatProgress)&&repeatProgress>=requiredProgress
                    &&microAgree&&tickReversals<=1);
        boolean eligible=side!=0&&microReady&&microAgree&&!counterTrendConflict
                &&repeatedSideQuality&&timeMs-lastIssued>=7000L;
        if(eligible){
            pending=new Pending(proposedSide,timeMs,price,baseSignal,dailyBias,dailySummary,baseAnalysis,move,recent,ticks.size());
            if(proposedSide.equals(lastIssuedSide))sameSideStreak++;else sameSideStreak=1;
            lastIssuedSide=proposedSide;lastIssuedPrice=price;
            lastIssued=timeMs;
            JSONObject issuedEvent=new JSONObject();
            try {
                issuedEvent.put("type","ISSUED");issuedEvent.put("tradeId",Long.toString(timeMs));issuedEvent.put("asset",asset);
                issuedEvent.put("signal",pending.side);issuedEvent.put("entryTimeMs",timeMs);
                issuedEvent.put("entryPrice",price);issuedEvent.put("baseSignal",baseSignal);
                issuedEvent.put("dailyBias",dailyBias);issuedEvent.put("dailySummary",dailySummary);
                issuedEvent.put("baseAnalysis",baseAnalysis);issuedEvent.put("microMove3s",move);
                issuedEvent.put("microMove1s",recent);issuedEvent.put("microAgree",microAgree);
                issuedEvent.put("tickReversals3s",tickReversals);
                issuedEvent.put("latestTickDirection",latestTickDirection);
                issuedEvent.put("latestTickAgeMs",latestDirectionalTickAt>0?timeMs-latestDirectionalTickAt:-1);
                issuedEvent.put("tickCountAtEntry",ticks.size());issuedEvent.put("expirySeconds",10);
                issuedEvent.put("entrySupportDistanceAtr",supportDistanceAtr);
                issuedEvent.put("entryResistanceDistanceAtr",resistanceDistanceAtr);
                issuedEvent.put("momentumFading",momentumFading);
                issuedEvent.put("confirmedBreakout",confirmedBreakout);
                issuedEvent.put("sameDirectionStreak",sameSideStreak);
                issuedEvent.put("sameDirectionProgress",repeatProgress);
                issuedEvent.put("settledCount",settled);issuedEvent.put("wins",wins);
                issuedEvent.put("accuracyPct",settled>0?100.0*wins/settled:-1.0);
            } catch(Exception ignored) {}
            audit.put(issuedEvent);
            latest=new Result(pending.side,"10-second signal • base trend and live tick momentum aligned",
                    baseAnalysis+"\n"+dailySummary+"\n5s MOMENTUM: "+String.format(Locale.US,"net %+.6f / last-second %+.6f",move,recent)
                    +"\n10s ENTRY: "+String.format(Locale.US,"%.6f",price)+" | expiry 10 seconds\n"+lastOutcome+"\n"+performanceLine(),
                    audit.toString());
        } else {
            String why=side==0?"Base trend is WAIT"
                    :exhaustionConflict?"Exhaustion caution • RSI extreme with opposing MACD"
                    :weakForecastMargin?"Weak forecast margin • signal too close to minimum threshold"
                    :entryQuality.blocked?"Entry quality blocked • "+entryQuality.reason
                    :liveConflict?"Live momentum/price-action conflict • opposing flow, MACD or counter-move"
                    :counterTrendConflict?"Live trend/EMA conflict • entry blocked"
                    :!microReady?"Collecting short live tick window"
                    :!repeatedSideQuality?"Repeated "+proposedSide+" blocked • same-zone entry needs fresh ATR-scaled price progress"
                    :choppyTickFlow?"Choppy tick flow • direction reversed "+tickReversals+" times in 3s"
                    :!latestTickFresh?"Waiting for a fresh directional tick"
                    :!latestTickAgree?"Latest tick movement reversed against signal"
                    :!netMicroAgree?"Short-term net ticks do not confirm direction":"Short-term ticks do not confirm direction";
            latest=new Result("WAIT","WAIT • "+why,
                    baseAnalysis+"\n"+dailySummary+"\n5s CHECK: "+why+"\n"+lastOutcome
                    +"\n"+performanceLine(),audit.toString());
        }
        return latest;
    }

    private double[] extractForecastThreshold(String analysis) {
        if(analysis==null)return null;
        java.util.regex.Matcher m=java.util.regex.Pattern
                .compile("forecast\\s+([-+]?\\d+(?:\\.\\d+)?)\\s*\\|\\s*threshold\\s+([0-9]+(?:\\.[0-9]+)?)")
                .matcher(analysis);
        if(!m.find())return null;
        try{return new double[]{Double.parseDouble(m.group(1)),Double.parseDouble(m.group(2))};}
        catch(NumberFormatException ignored){return null;}
    }

    private double extractMetric(String analysis,String key) {
        if(analysis==null||key==null)return Double.NaN;
        java.util.regex.Matcher m=java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(key)+"\\s+([+-]?\\d+(?:\\.\\d+)?)")
                .matcher(analysis);
        return m.find()?Double.parseDouble(m.group(1)):Double.NaN;
    }

    private String performanceLine() {
        return settled==0 ? "10S SETTLED ACCURACY: waiting for first verified result"
                : String.format(Locale.US,"10S SETTLED ACCURACY: %.1f%% (%d wins / %d settled)",
                        100.0*wins/settled,wins,settled);
    }
}
