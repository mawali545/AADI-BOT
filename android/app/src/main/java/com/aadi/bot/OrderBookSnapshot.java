package com.aadi.bot;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** A two-sided order-book snapshot is accepted only when both sides contain price AND size levels. */
public final class OrderBookSnapshot {
    public static final class Level {
        public final double price, size;
        Level(double p,double s){price=p;size=s;}
    }
    public final List<Level> bidLevels, askLevels;
    public final double spread, imbalance, bestBid, bestAsk;
    private OrderBookSnapshot(List<Level> b,List<Level> a) {
        bidLevels=b; askLevels=a;
        bestBid=b.get(0).price; bestAsk=a.get(0).price;
        spread=bestAsk-bestBid;
        double bs=0,as=0;
        for(Level l:b)bs+=l.size;
        for(Level l:a)as+=l.size;
        imbalance=(bs+as)>0?(bs-as)/(bs+as):0;
    }
    public static OrderBookSnapshot parse(Object root) {
        JSONArray bids=findSide(root,true,0), asks=findSide(root,false,0);
        List<Level> b=parseLevels(bids), a=parseLevels(asks);
        if(b.isEmpty()||a.isEmpty())return null;
        Collections.sort(b,(x,y)->Double.compare(y.price,x.price));
        Collections.sort(a,Comparator.comparingDouble(x->x.price));
        if(b.get(0).price>=a.get(0).price)return null; // crossed/invalid book
        return new OrderBookSnapshot(trim(b,20),trim(a,20));
    }
    private static List<Level> trim(List<Level> in,int max) {
        return new ArrayList<>(in.subList(0,Math.min(max,in.size())));
    }
    private static JSONArray findSide(Object node,boolean bid,int depth) {
        if(node==null||depth>12)return null;
        if(node instanceof JSONObject) {
            JSONObject o=(JSONObject)node;
            String[] keys=bid?new String[]{"bids","bidLevels","buyLevels","buyOrders"}
                    :new String[]{"asks","askLevels","sellLevels","sellOrders"};
            for(String k:keys) if(o.has(k)&&o.opt(k) instanceof JSONArray)
                return (JSONArray)o.opt(k);
            JSONArray found=null;
            java.util.Iterator<String> it=o.keys();
            while(it.hasNext()&&found==null)found=findSide(o.opt(it.next()),bid,depth+1);
            return found;
        }
        if(node instanceof JSONArray) {
            JSONArray a=(JSONArray)node;
            for(int i=0;i<a.length();i++){JSONArray found=findSide(a.opt(i),bid,depth+1);if(found!=null)return found;}
        }
        return null;
    }
    private static List<Level> parseLevels(JSONArray rows) {
        List<Level> out=new ArrayList<>();
        if(rows==null)return out;
        for(int i=0;i<rows.length()&&out.size()<100;i++) {
            Object row=rows.opt(i); double p=Double.NaN,s=Double.NaN;
            if(row instanceof JSONArray) {
                JSONArray x=(JSONArray)row;
                if(x.length()>=2){p=num(x.opt(0));s=num(x.opt(1));}
            } else if(row instanceof JSONObject) {
                JSONObject x=(JSONObject)row;
                p=first(x,"price","rate","p");
                s=first(x,"size","quantity","amount","volume","qty");
            }
            if(Double.isFinite(p)&&p>0&&Double.isFinite(s)&&s>0)out.add(new Level(p,s));
        }
        return out;
    }
    private static double first(JSONObject o,String...keys) {
        for(String k:keys)if(o.has(k)){double n=num(o.opt(k));if(Double.isFinite(n))return n;}
        return Double.NaN;
    }
    private static double num(Object v) {
        try {double d=v instanceof Number?((Number)v).doubleValue():Double.parseDouble(String.valueOf(v));return d;}
        catch(Exception e){return Double.NaN;}
    }
    /** True only when the latest live quote is bracketed by the captured best bid/ask. */
    public boolean matchesQuote(double quote) {
        return Double.isFinite(quote) && quote > 0 && bestBid <= quote && quote <= bestAsk;
    }

    public String heatmap() {
        StringBuilder s=new StringBuilder();
        double max=0;
        for(Level l:bidLevels)max=Math.max(max,l.size);
        for(Level l:askLevels)max=Math.max(max,l.size);
        s.append("ASK ↑");
        for(int i=0;i<Math.min(5,askLevels.size());i++){Level l=askLevels.get(i);s.append("\nA ").append(String.format(java.util.Locale.US,"%.6f",l.price)).append("  ").append(bar(l.size,max)).append(" ").append(l.size);}
        s.append("\nBID ↓");
        for(int i=0;i<Math.min(5,bidLevels.size());i++){Level l=bidLevels.get(i);s.append("\nB ").append(String.format(java.util.Locale.US,"%.6f",l.price)).append("  ").append(bar(l.size,max)).append(" ").append(l.size);}
        return s.toString();
    }
    private String bar(double v,double max) {
        int n=max>0?(int)Math.round(8*v/max):0;
        StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append("█");return b.toString();
    }
}
