package com.dr.candletracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Independent descriptive scan of broker-supplied OHLC history and live QX ticks. */
public final class MarketScanner {
    private static final int MAX_CANDLES = 1800, MAX_TICKS = 900, ANALYSIS_CANDLES = 36, ANALYSIS_TICKS = 90;
    private final Deque<Bar> candles = new ArrayDeque<>();
    private final Deque<Quote> ticks = new ArrayDeque<>();
    private String asset = "WAITING", direction = "UNKNOWN", pattern = "COLLECTING", pace = "UNKNOWN";
    private String structure = "INSUFFICIENT DATA", detail = "Waiting for genuine QX chart data";
    private long lastTimestamp, updates, historyLoads, lastDurationNs, totalDurationNs, maxDurationNs;
    private long candlesReceived, ticksReceived, rejectedRows, gaps;
    private double lastPrice = Double.NaN, strength;
    private int lastCandlesScanned, lastTicksScanned, fullHistorySwingChanges;\n    private long fullHistoryScans;\n    private String fullHistoryDirection = "UNKNOWN";\n    private double fullHistoryHigh = Double.NaN, fullHistoryLow = Double.NaN;

    public synchronized void acceptHistory(String symbol, JSONArray rows) {
        if (symbol == null || !symbol.matches("[A-Za-z0-9_./-]{3,32}") || rows == null) return;
        long started = System.nanoTime();
        List<Bar> parsed = new ArrayList<>();
        for (int i=0;i<rows.length();i++) {
            try {
                JSONObject o=rows.optJSONObject(i); JSONArray a=rows.optJSONArray(i);
                double t,op,cl,hi,lo;
                if(o!=null){t=num(o,"time","timestamp","t");op=num(o,"open","o");cl=num(o,"close","c");hi=num(o,"high","h");lo=num(o,"low","l");}
                else if(a!=null&&a.length()>=5){t=a.getDouble(0);op=a.getDouble(1);cl=a.getDouble(2);hi=a.getDouble(3);lo=a.getDouble(4);}
                else { rejectedRows++; continue; }
                long stamp=(long)t;if(stamp>0&&stamp<100000000000L)stamp*=1000L;
                if(stamp<=0||!Double.isFinite(op)||!Double.isFinite(cl)||!Double.isFinite(hi)||!Double.isFinite(lo)||
                   op<=0||cl<=0||lo<=0||hi<Math.max(op,cl)||lo>Math.min(op,cl)){rejectedRows++;continue;}
                parsed.add(new Bar(stamp,op,hi,lo,cl));
            } catch(Exception ignored){rejectedRows++;}
        }
        if(!symbol.equals(asset)){candles.clear();ticks.clear();lastTimestamp=0;}
        asset=symbol;
        // Broker history is ordered and deduplicated by timestamp; only actual supplied rows are retained.
        java.util.TreeMap<Long,Bar> unique=new java.util.TreeMap<>();
        for(Bar b:parsed)unique.put(b.time,b);
        candles.clear();
        for(Bar b:unique.values()){candles.addLast(b);if(candles.size()>MAX_CANDLES)candles.removeFirst();}
        candlesReceived=candles.size();historyLoads++;
        analyze(started);
    }

    /** One complete pass over every genuine candle supplied by QX; never repeated on each tick. */
    private void scanFullHistory() {
        fullHistoryScans++;
        fullHistorySwingChanges=0;
        fullHistoryHigh=Double.NEGATIVE_INFINITY;
        fullHistoryLow=Double.POSITIVE_INFINITY;
        if(candles.isEmpty()){fullHistoryDirection="UNKNOWN";return;}
        double first=candles.peekFirst().close,last=candles.peekLast().close;
        int previous=0;
        for(Bar bar:candles){
            fullHistoryHigh=Math.max(fullHistoryHigh,bar.high);
            fullHistoryLow=Math.min(fullHistoryLow,bar.low);
            int d=sgn(bar.close-bar.open);
            if(d!=0&&previous!=0&&d!=previous)fullHistorySwingChanges++;
            if(d!=0)previous=d;
        }
        fullHistoryDirection=last>first?"UP":(last<first?"DOWN":"SIDEWAYS");
    }

    public synchronized void acceptTick(String symbol,double price,long timestamp) {
        if(symbol==null||!symbol.matches("[A-Za-z0-9_./-]{3,32}")||!Double.isFinite(price)||price<=0||timestamp<=0)return;
        if(!symbol.equals(asset)){asset=symbol;candles.clear();ticks.clear();lastTimestamp=0;candlesReceived=0;}
        if(timestamp<=lastTimestamp)return;
        long started=System.nanoTime();
        if(lastTimestamp>0&&timestamp-lastTimestamp>5000L)gaps++;
        lastTimestamp=timestamp;lastPrice=price;ticks.addLast(new Quote(price,timestamp));
        while(ticks.size()>MAX_TICKS)ticks.removeFirst();ticksReceived++;
        analyze(started);
    }

    private void analyze(long started) {
        List<Bar> b=new ArrayList<>(candles);
        List<Quote> q=new ArrayList<>(ticks);
        int n=Math.min(ANALYSIS_CANDLES,b.size()),m=Math.min(ANALYSIS_TICKS,q.size());
        lastCandlesScanned=n;lastTicksScanned=m;updates++;
        if(n<3&&m<5){direction="UNKNOWN";pattern="COLLECTING";pace="UNKNOWN";structure="INSUFFICIENT DATA";strength=0;detail="Need more real QX candles/ticks";}
        else {
            double first=Double.NaN,last=Double.NaN,path=0,rangeSum=0;
            int turns=0,prior=0;double high=Double.NEGATIVE_INFINITY,low=Double.POSITIVE_INFINITY;
            int from=Math.max(0,b.size()-n);
            for(int i=from;i<b.size();i++){
                Bar x=b.get(i);if(!Double.isFinite(first))first=x.close;last=x.close;
                high=Math.max(high,x.high);low=Math.min(low,x.low);rangeSum+=Math.max(x.high-x.low,Math.abs(x.close-x.open));
                int d=sgn(x.close-x.open);if(d!=0&&prior!=0&&d!=prior)turns++;if(d!=0)prior=d;
                if(i>from)path+=Math.abs(x.close-b.get(i-1).close);
            }
            double displacement=Double.isFinite(first)&&Double.isFinite(last)?last-first:0;
            double efficiency=path>0?Math.abs(displacement)/path:0;
            double avgRange=n>0?rangeSum/n:0;
            double net=0,tickPath=0;int tickTurns=0,td=0;
            int tq=Math.max(0,q.size()-m);
            for(int i=tq;i<q.size();i++){
                Quote x=q.get(i);if(i==tq)net=x.price;else{double delta=x.price-q.get(i-1).price;tickPath+=Math.abs(delta);int d=sgn(delta);if(d!=0&&td!=0&&d!=td)tickTurns++;if(d!=0)td=d;}
            }
            if(m>1)net=q.get(q.size()-1).price-q.get(tq).price;else net=0;
            double tickEfficiency=tickPath>0?Math.abs(net)/tickPath:0;
            double score=Math.max(efficiency,tickEfficiency);
            strength=Math.max(0,Math.min(100,score*100.0));
            if(Math.abs(displacement)>avgRange*0.55)direction=displacement>0?"UP":"DOWN";
            else if(Math.abs(net)>avgRange*0.12)direction=net>0?"UP":"DOWN";
            else direction="SIDEWAYS";
            boolean vShape=false,uShape=false;
            if(n>=5){
                int mid=from+n/2;double early=0,late=0;int ec=0,lc=0;
                for(int i=from;i<mid;i++){early+=b.get(i).close;ec++;}
                for(int i=mid;i<b.size();i++){late+=b.get(i).close;lc++;}
                double start=b.get(from).close,center=b.get(Math.min(b.size()-1,mid)).close,end=b.get(b.size()-1).close;
                double scale=Math.max(avgRange,1e-12);
                vShape=(center<start-scale*.45&&end>center+scale*.45&&end>=start-scale*.25);
                uShape=(center<start-scale*.25&&end>center+scale*.25&&Math.abs(end-start)<scale*2.5);
                if(!vShape){vShape=(center>start+scale*.45&&end<center-scale*.45&&end<=start+scale*.25);}
                if(!uShape){uShape=(center>start+scale*.25&&end<center-scale*.25&&Math.abs(end-start)<scale*2.5);}
            }
            if(vShape)pattern="V-SHAPE / REVERSAL";
            else if(uShape)pattern="U-SHAPE / REVERSAL";
            else if(score>=0.68&&turns+tickTurns<=Math.max(2,n/5+m/20))pattern="STRAIGHT / TRENDING";
            else if(score<0.28&&(turns>=Math.max(2,n/4)||tickTurns>=Math.max(3,m/8)))pattern="CHOPPY / SIDEWAYS";
            else if(score>=0.42)pattern="PULLBACK / TREND";
            else pattern="MIXED / RANGE";
            if(n>=4){
                int half=n/2;double h1=Double.NEGATIVE_INFINITY,l1=Double.POSITIVE_INFINITY,h2=Double.NEGATIVE_INFINITY,l2=Double.POSITIVE_INFINITY;
                for(int i=from;i<b.size();i++){Bar x=b.get(i);if(i<from+half){h1=Math.max(h1,x.high);l1=Math.min(l1,x.low);}else{h2=Math.max(h2,x.high);l2=Math.min(l2,x.low);}}
                if(h2>h1&&l2>l1)structure="HIGHER HIGH + HIGHER LOW";
                else if(h2<h1&&l2<l1)structure="LOWER HIGH + LOWER LOW";
                else structure="RANGE / STRUCTURE MIXED";
            } else structure="LIVE TICK FLOW ONLY";
            if(m>=2){
                long span=q.get(q.size()-1).time-q.get(tq).time;
                double rate=span>0?(m-1)*1000.0/span:0;
                double normalized=avgRange>0?tickPath/avgRange:0;
                pace=rate>=4||normalized>=1.5?"FAST":(rate>=1||normalized>=0.45?"NORMAL":"SLOW");
            }else pace="WAITING FOR TICKS";
            detail=pattern+" · "+direction+" · "+structure;
        }
        lastDurationNs=Math.max(0,System.nanoTime()-started);totalDurationNs+=lastDurationNs;maxDurationNs=Math.max(maxDurationNs,lastDurationNs);
    }

    private static int sgn(double v){return v>0?1:(v<0?-1:0);}
    private static double num(JSONObject o,String...keys){for(String k:keys){Object v=o.opt(k);if(v instanceof Number)return ((Number)v).doubleValue();if(v instanceof String)try{return Double.parseDouble((String)v);}catch(Exception ignored){}}return Double.NaN;}

    public synchronized JSONObject snapshot(){
        JSONObject o=new JSONObject();
        try{
            o.put("asset",asset);o.put("direction",direction);o.put("pattern",pattern);o.put("pace",pace);o.put("structure",structure);o.put("detail",detail);
            o.put("strength",Math.round(strength));o.put("candlesAvailable",candles.size());o.put("ticksAvailable",ticks.size());
            o.put("candlesScanned",candles.size());o.put("recentCandlesAnalyzed",lastCandlesScanned);o.put("ticksScanned",lastTicksScanned);o.put("updates",updates);o.put("historyLoads",historyLoads);\n            o.put("fullHistoryScans",fullHistoryScans);o.put("fullChartDirection",fullHistoryDirection);o.put("fullChartHigh",Double.isFinite(fullHistoryHigh)?fullHistoryHigh:JSONObject.NULL);o.put("fullChartLow",Double.isFinite(fullHistoryLow)?fullHistoryLow:JSONObject.NULL);o.put("fullChartSwingChanges",fullHistorySwingChanges);
            o.put("rejectedRows",rejectedRows);o.put("tickGaps",gaps);o.put("lastScanUs",lastDurationNs/1000.0);
            o.put("avgScanUs",updates>0?(totalDurationNs/(double)updates)/1000.0:0);o.put("maxScanUs",maxDurationNs/1000.0);
            o.put("lastTimestamp",lastTimestamp);o.put("lastPrice",Double.isFinite(lastPrice)?lastPrice:JSONObject.NULL);
        }catch(JSONException ignored){}
        return o;
    }
    private static final class Bar{final long time;final double open,high,low,close;Bar(long t,double o,double h,double l,double c){time=t;open=o;high=h;low=l;close=c;}}
    private static final class Quote{final double price;final long time;Quote(double p,long t){price=p;time=t;}}
}
