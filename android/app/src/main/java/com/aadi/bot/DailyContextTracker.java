package com.aadi.bot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashMap;

/** Builds a prior-UTC-day reference from actual Market-QX historical candles. */
public final class DailyContextTracker {
    private static final long DAY=86400L;
    private static final class Candle {
        final long t; final double o,h,l,c;
        Candle(long t,double o,double h,double l,double c){this.t=t;this.o=o;this.h=h;this.l=l;this.c=c;}
    }
    private final Map<String,TreeMap<Long,Candle>> byAsset=new HashMap<>();

    public synchronized void resetAsset(String asset){ if(asset!=null) byAsset.remove(asset); }

    public synchronized int importHistory(String asset, JSONArray rows) {
        if(asset==null||asset.trim().isEmpty()||rows==null)return 0;
        TreeMap<Long,Candle> map=byAsset.computeIfAbsent(asset,k->new TreeMap<>());
        int added=0;
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i); if(r==null)continue;
            long t=(long)r.optDouble("timestamp",r.optDouble("time",r.optDouble("ts",0)));
            if(t>10000000000L)t/=1000L;
            double o=num(r,"open","o"),h=num(r,"high","h"),l=num(r,"low","l"),c=num(r,"close","c");
            if(t<=0||!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)
                    ||o<=0||h<Math.max(o,c)||l>Math.min(o,c)||l<=0||c<=0||h<l)continue;
            long bucket=(t/10L)*10L;
            if(!map.containsKey(bucket))added++;
            map.put(bucket,new Candle(bucket,o,h,l,c));
        }
        while(map.size()>30000)map.pollFirstEntry();
        return added;
    }

    public synchronized boolean isReady(String asset,long nowSeconds) {
        return context(asset,nowSeconds)!=null;
    }

    /** +1/-1 only when previous-day and current-day references are complete and agree. */
    public synchronized int bias(String asset,double price,long nowSeconds) {
        Context c=context(asset,nowSeconds);
        if(c==null||!Double.isFinite(price)||price<=0)return 0;
        int votes=0;
        votes+=sign(c.prevClose-c.prevOpen);
        votes+=sign(c.todayOpen-c.prevClose);
        votes+=sign(price-c.todayOpen);
        return votes>=2?1:votes<=-2?-1:0;
    }

    public synchronized String summary(String asset,double price,long nowSeconds) {
        Context c=context(asset,nowSeconds);
        if(c==null)return "DAILY CONTEXT: WAIT • previous-day open/close + today's open not fully loaded";
        int b=bias(asset,price,nowSeconds);
        String direction=b>0?"BULLISH ALIGNMENT":b<0?"BEARISH ALIGNMENT":"MIXED / NO ALIGNMENT";
        return String.format(Locale.US,
                "YESTERDAY O/C: %.6f / %.6f | TODAY OPEN: %.6f | NOW: %.6f | vs yesterday close: %+.6f | DAILY: %s",
                c.prevOpen,c.prevClose,c.todayOpen,price,price-c.prevClose,direction);
    }

    private static int sign(double x){return x>0?1:x<0?-1:0;}
    private static double num(JSONObject r,String a,String b){
        return r.has(a)?r.optDouble(a,Double.NaN):r.optDouble(b,Double.NaN);
    }
    private static final class Context {
        double prevOpen,prevClose,todayOpen;
    }
    private Context context(String asset,long now) {
        TreeMap<Long,Candle> map=byAsset.get(asset);
        if(map==null||map.isEmpty())return null;
        long offset=java.util.TimeZone.getDefault().getOffset(now*1000L)/1000L;
        long today=Math.floorDiv(now+offset,DAY)*DAY-offset, yesterday=today-DAY;
        Map.Entry<Long,Candle> firstPrev=map.ceilingEntry(yesterday);
        Map.Entry<Long,Candle> lastPrev=map.lowerEntry(today);
        Map.Entry<Long,Candle> firstToday=map.ceilingEntry(today);
        if(firstPrev==null||lastPrev==null||firstToday==null)return null;
        // Require near-full 10-second history coverage of yesterday, not just two endpoints.
        if(firstPrev.getKey()>yesterday+120L||lastPrev.getKey()<today-120L
                ||firstToday.getKey()>today+120L)return null;
        java.util.NavigableMap<Long,Candle> priorDay=map.subMap(yesterday,true,today,false);
        if(priorDay.size()<8000)return null;
        long previous=Long.MIN_VALUE;
        for(Long stamp:priorDay.keySet()){
            if(previous!=Long.MIN_VALUE&&stamp-previous>60L)return null;
            previous=stamp;
        }
        Context c=new Context();
        c.prevOpen=firstPrev.getValue().o;
        c.prevClose=lastPrev.getValue().c;
        c.todayOpen=firstToday.getValue().o;
        return c;
    }
}
