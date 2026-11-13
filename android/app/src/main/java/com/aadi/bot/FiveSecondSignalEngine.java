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
    private String asset="";
    private Pending pending;
    private long lastIssued=0;
    private int settled=0,wins=0;
    private String lastOutcome="No 5s result settled yet";
    private Result latest=new Result("WAIT","Waiting for 5-second live confirmation","5-SECOND ENGINE: warming up");

    public synchronized void reset(String newAsset){
        asset=newAsset==null?"":newAsset;ticks.clear();pending=null;lastIssued=0;settled=0;wins=0;
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
        while(!ticks.isEmpty()&&timeMs-ticks.peekFirst().t>12000L)ticks.removeFirst();
        if(pending!=null){
            if(timeMs-pending.lastSeenAt>1500L) pending.feedContinuous=false;
            pending.lastSeenAt=timeMs;
            if(("CALL".equals(pending.side)&&price<=pending.price)
                    ||("PUT".equals(pending.side)&&price>=pending.price)) pending.held=false;
        }

        if(pending!=null&&timeMs>=pending.at+5000L){
            long expiryAt=pending.at+5000L;
            Tick expiryQuote=null;
            long bestGap=Long.MAX_VALUE;
            for(Tick t:ticks){
                long gap=Math.abs(t.t-expiryAt);
                if(gap<=1000L&&gap<bestGap){bestGap=gap;expiryQuote=t;}
            }
            if(expiryQuote!=null&&pending.feedContinuous){
                double expiryPrice=expiryQuote.p;
                boolean finalSide="CALL".equals(pending.side)?expiryPrice>pending.price:expiryPrice<pending.price;
                boolean win=pending.held&&finalSide;
                JSONObject settledEvent=new JSONObject();
                try {
                    settledEvent.put("type","SETTLED");settledEvent.put("asset",asset);
                    settledEvent.put("signal",pending.side);settledEvent.put("entryTimeMs",pending.at);
                    settledEvent.put("entryPrice",pending.price);settledEvent.put("expiryTimeMs",expiryAt);
                    settledEvent.put("expiryPrice",expiryPrice);settledEvent.put("heldAboveBelowEntry",pending.held);
                    settledEvent.put("feedContinuous",pending.feedContinuous);
                    settledEvent.put("baseSignal",pending.baseSignal);settledEvent.put("dailyBias",pending.dailyBias);
                    settledEvent.put("dailySummary",pending.dailySummary);settledEvent.put("baseAnalysis",pending.baseAnalysis);
                    settledEvent.put("microMove3s",pending.move);settledEvent.put("microMove1s",pending.recent);
                    settledEvent.put("tickCountAtEntry",pending.tickCount);
                    settledEvent.put("outcome",expiryPrice==pending.price?"FLAT":win?"WIN":"LOSS");
                } catch(Exception ignored) {}
                audit.put(settledEvent);
                if(expiryPrice==pending.price)lastOutcome="5s FLAT • not counted";
                else {settled++;if(win)wins++;lastOutcome="5s "+(win?"WIN":"LOSS")+" • "+pending.side+(pending.held?"":" • crossed entry during hold")+" • "+String.format(Locale.US,"%.6f → %.6f",pending.price,expiryPrice);}
                pending=null;
            } else if(expiryQuote!=null&&!pending.feedContinuous){
                lastOutcome="5s UNVERIFIED • live tick stream had a gap over 1.5s";
                pending=null;
            } else if(timeMs>expiryAt+1500L){
                lastOutcome="5s UNVERIFIED • no quote within 1s of expiry";
                pending=null;
            }
        }
        if(pending!=null){
            latest=new Result(pending.side,"5-second trade active • expiry check in progress",
                    baseAnalysis+"\n"+dailySummary+"\n5s ENTRY: "+String.format(Locale.US,"%.6f",pending.price)+" | expiry in "+Math.max(0,5000-(timeMs-pending.at))+"ms\n"+lastOutcome,
                    audit.toString());
            return latest;
        }

        Tick old=null,near=null;
        for(Tick t:ticks){
            if(timeMs-t.t>=2200L&&timeMs-t.t<=3800L)old=t;
            if(timeMs-t.t>=700L&&timeMs-t.t<=1700L)near=t;
        }
        int side="CALL".equals(baseSignal)?1:"PUT".equals(baseSignal)?-1:0;
        boolean dailyReady=dailyBias!=0;
        boolean microReady=old!=null&&near!=null&&ticks.size()>=4;
        double move=old==null?0:price-old.p;
        double recent=near==null?0:price-near.p;
        boolean microAgree=side>0?move>0&&recent>0:side<0&&move<0&&recent<0;
        boolean eligible=side!=0&&dailyReady&&dailyBias==side&&microReady&&microAgree
                &&timeMs-lastIssued>=5000L;
        if(eligible){
            pending=new Pending(side>0?"CALL":"PUT",timeMs,price,baseSignal,dailyBias,dailySummary,baseAnalysis,move,recent,ticks.size());
            lastIssued=timeMs;
            JSONObject issuedEvent=new JSONObject();
            try {
                issuedEvent.put("type","ISSUED");issuedEvent.put("asset",asset);
                issuedEvent.put("signal",pending.side);issuedEvent.put("entryTimeMs",timeMs);
                issuedEvent.put("entryPrice",price);issuedEvent.put("baseSignal",baseSignal);
                issuedEvent.put("dailyBias",dailyBias);issuedEvent.put("dailySummary",dailySummary);
                issuedEvent.put("baseAnalysis",baseAnalysis);issuedEvent.put("microMove3s",move);
                issuedEvent.put("microMove1s",recent);issuedEvent.put("microAgree",microAgree);
                issuedEvent.put("tickCountAtEntry",ticks.size());issuedEvent.put("expirySeconds",5);
            } catch(Exception ignored) {}
            audit.put(issuedEvent);
            latest=new Result(pending.side,"5-second signal • base trend, daily context and live tick momentum aligned",
                    baseAnalysis+"\n"+dailySummary+"\n5s MOMENTUM: "+String.format(Locale.US,"net %+.6f / last-second %+.6f",move,recent)
                    +"\n5s ENTRY: "+String.format(Locale.US,"%.6f",price)+" | expiry 5 seconds\n"+lastOutcome,
                    audit.toString());
        } else {
            String why=side==0?"Base trend is WAIT":!dailyReady?"Previous-day reference incomplete or daily direction mixed"
                    :dailyBias!=side?"Daily context conflicts with base trend"
                    :!microReady?"Collecting 3-second tick window":"Short-term ticks do not confirm direction";
            latest=new Result("WAIT","WAIT • "+why,
                    baseAnalysis+"\n"+dailySummary+"\n5s CHECK: "+why+"\n"+lastOutcome
                    +" | settled "+settled+" | wins "+wins
                    +(settled>=5?String.format(Locale.US," | observed %.1f%%",100.0*wins/settled):""),audit.toString());
        }
        return latest;
    }
}
