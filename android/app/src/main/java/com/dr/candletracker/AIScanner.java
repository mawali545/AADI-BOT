package com.dr.candletracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Single adaptive price-action scanner. Inputs are genuine QX OHLC history and
 * validated live quotes only. The evidence score is not a measured win probability.
 */
public final class AIScanner {
    private static final int MAX_BARS=1800, MAX_TICKS=1200, TICK_WINDOW=32, BAR_WINDOW=12;
    private static final long EXPIRY_MS=10_000L;
    private final Deque<Bar> bars=new ArrayDeque<>();
    private final Deque<Quote> tape=new ArrayDeque<>();
    private final Deque<Bar> liveBars=new ArrayDeque<>();
    private String asset="WAITING FOR QX", signal="WAIT", reason="Waiting for genuine QX market data";
    private String pattern="COLLECTING", direction="UNKNOWN";
    private long lastTimestamp, entryTimestamp, expiryTimestamp, fullHistoryScans, historyLoads, scanCount;
    private long lastScanNs,totalScanNs,maxScanNs;
    private double lastPrice=Double.NaN,entryPrice=Double.NaN,evidenceScore;
    private int wins,losses,ties, lastBarsScanned,lastTicksScanned,fullHistorySwingChanges;
    private double historyHigh=Double.NaN,historyLow=Double.NaN;
    private String historyDirection="UNKNOWN",lastResult="NONE";
    private boolean pending=false;

    public synchronized void acceptHistory(String symbol,long periodSeconds,JSONArray rows){
        if(!validAsset(symbol)||rows==null)return;
        long started=System.nanoTime();
        TreeMap<Long,Bar> parsed=new TreeMap<>();
        long duration=Math.max(1L,Math.min(86400L,periodSeconds))*1000L;
        for(int i=0;i<rows.length();i++){
            try{
                JSONObject o=rows.optJSONObject(i);JSONArray a=rows.optJSONArray(i);
                double raw,op,cl,hi,lo;
                if(o!=null){raw=num(o,"time","timestamp","t");op=num(o,"open","o");cl=num(o,"close","c");hi=num(o,"high","h");lo=num(o,"low","l");}
                else if(a!=null&&a.length()>=5){raw=a.getDouble(0);op=a.getDouble(1);cl=a.getDouble(2);hi=a.getDouble(3);lo=a.getDouble(4);}
                else continue;
                long t=(long)raw;if(t>0&&t<100000000000L)t*=1000L;
                if(t<=0||!validOhlc(op,hi,lo,cl))continue;
                long bucket=t/duration*duration;
                parsed.put(bucket,new Bar(bucket,op,hi,lo,cl));
            }catch(Exception ignored){}
        }
        if(!symbol.equals(asset)){resetForAsset(symbol);}
        asset=symbol;bars.clear();liveBars.clear();
        for(Bar b:parsed.values()){bars.addLast(b);if(bars.size()>MAX_BARS)bars.removeFirst();}
        historyLoads++;scanFullHistory();
        long elapsed=Math.max(0,System.nanoTime()-started);lastScanNs=elapsed;totalScanNs+=elapsed;maxScanNs=Math.max(maxScanNs,elapsed);
        if(!pending){reason="Loaded "+bars.size()+" genuine QX candles; waiting for live price flow";signal="WAIT";}
    }

    public synchronized void acceptTick(String symbol,double price,long timestamp,long periodSeconds,Integer brokerDirection){
        if(!validAsset(symbol)||!Double.isFinite(price)||price<=0||timestamp<=0)return;
        if(!symbol.equals(asset))resetForAsset(symbol);
        if(timestamp<=lastTimestamp)return;
        lastTimestamp=timestamp;lastPrice=price;
        acceptLiveOhlc(timestamp,price);
        tape.addLast(new Quote(price,timestamp,brokerDirection));
        while(tape.size()>MAX_TICKS)tape.removeFirst();

        // Expiry is settled only by an actual QX quote at or after entry + 10 seconds.
        if(pending&&timestamp>=expiryTimestamp){
            settle(price);
            pending=false;signal="WAIT";
            reason="10-second trade settled · "+lastResult;
            // Do not issue a second trade on the same quote used to settle the first.
            recordScanTime(0);
            return;
        }
        if(pending){recordScanTime(0);return;}
        long started=System.nanoTime();
        Scan result=analyze(price);
        scanCount++;
        lastBarsScanned=result.barsScanned;lastTicksScanned=result.ticksScanned;
        direction=result.direction;pattern=result.pattern;evidenceScore=result.score;
        if(!"WAIT".equals(result.signal)){
            signal=result.signal;entryPrice=price;entryTimestamp=timestamp;
            expiryTimestamp=timestamp+EXPIRY_MS;pending=true;
            reason=result.reason;
        }else{
            signal="WAIT";entryPrice=Double.NaN;entryTimestamp=0;expiryTimestamp=0;
            reason=result.reason;
        }
        recordScanTime(Math.max(0,System.nanoTime()-started));
    }

    // Build genuine one-second OHLC buckets from received QX ticks when broker history is absent.
    // These are derived only from real quotes; no prices or candles are fabricated.
    private void acceptLiveOhlc(long timestamp,double price){
        long bucket=(timestamp/1000L)*1000L;
        Bar last=liveBars.peekLast();
        if(last!=null&&last.time==bucket){
            liveBars.removeLast();
            liveBars.addLast(new Bar(bucket,last.open,Math.max(last.high,price),Math.min(last.low,price),price));
        }else if(last==null||bucket>last.time){
            liveBars.addLast(new Bar(bucket,price,price,price,price));
            while(liveBars.size()>MAX_BARS)liveBars.removeFirst();
        }
    }

    private void resetForAsset(String symbol){
        asset=symbol;bars.clear();liveBars.clear();tape.clear();lastTimestamp=0;lastPrice=Double.NaN;
        signal="WAIT";pending=false;entryPrice=Double.NaN;entryTimestamp=0;expiryTimestamp=0;
        reason="Selected "+symbol+" · collecting genuine QX data";
        historyHigh=Double.NaN;historyLow=Double.NaN;historyDirection="UNKNOWN";
        lastResult="NONE";
    }

    private void scanFullHistory(){
        fullHistoryScans++;fullHistorySwingChanges=0;
        if(bars.isEmpty()){historyHigh=Double.NaN;historyLow=Double.NaN;historyDirection="UNKNOWN";return;}
        double first=bars.peekFirst().close,last=bars.peekLast().close;
        historyHigh=Double.NEGATIVE_INFINITY;historyLow=Double.POSITIVE_INFINITY;
        int previous=0;
        for(Bar b:bars){
            historyHigh=Math.max(historyHigh,b.high);historyLow=Math.min(historyLow,b.low);
            int d=sign(b.close-b.open);
            if(d!=0&&previous!=0&&d!=previous)fullHistorySwingChanges++;
            if(d!=0)previous=d;
        }
        historyDirection=last>first?"UP":last<first?"DOWN":"SIDEWAYS";
    }

    private Scan analyze(double price){
        List<Bar> recentBars=new ArrayList<>();
        for(Bar b:bars)recentBars.add(b);
        for(Bar b:liveBars)recentBars.add(b);
        int from=Math.max(0,recentBars.size()-BAR_WINDOW);
        int bn=recentBars.size()-from;
        List<Quote> q=new ArrayList<>(tape);
        int qFrom=Math.max(0,q.size()-TICK_WINDOW),qn=q.size()-qFrom;
        if(bn<5||qn<8)return new Scan("WAIT","UNKNOWN","COLLECTING",0,bn,qn,
            "Need 5 genuine history/live-tick OHLC candles and 8 live QX ticks");
        double path=0,net=q.get(q.size()-1).price-q.get(qFrom).price;
        int up=0,down=0,changes=0;
        for(int i=qFrom+1;i<q.size();i++){
            double delta=q.get(i).price-q.get(i-1).price;
            path+=Math.abs(delta);int d=sign(delta);
            if(d>0)up++;else if(d<0)down++;
            if(d!=0)changes++;
        }
        if(path<=0||changes<5)return new Scan("WAIT","UNKNOWN","LOW ACTIVITY",0,bn,qn,
            "Insufficient directional movement in genuine live ticks");
        double momentum=clamp(net/path,-1,1);
        double consistency=(double)Math.max(up,down)/Math.max(1,changes);
        double tickDir=up>=down?1:-1;
        double avgRange=0,barNet=0,bodyBias=0;
        for(int i=from;i<recentBars.size();i++){
            Bar b=recentBars.get(i);avgRange+=Math.max(1e-12,b.high-b.low);
            barNet+=b.close-b.open;
        }
        avgRange/=bn;
        Bar last=recentBars.get(recentBars.size()-1);
        double trend=clamp((last.close-recentBars.get(from).close)/(avgRange*bn),-1,1);
        double body=clamp((last.close-last.open)/Math.max(1e-12,last.high-last.low),-1,1);
        double lowerWick=Math.max(0,Math.min(last.open,last.close)-last.low);
        double upperWick=Math.max(0,last.high-Math.max(last.open,last.close));
        double rejection=clamp((lowerWick-upperWick)/Math.max(1e-12,last.high-last.low),-1,1);

        // Penalize two-way/choppy movement; use broker price-action only.
        double chop=1.0-Math.abs(momentum);
        double signed=(0.48*momentum)+(0.20*trend)+(0.16*tickDir*consistency)+(0.10*body)+(0.06*rejection);
        signed=clamp(signed,-1,1);
        double score=50.0+45.0*Math.abs(signed);
        String dir=signed>0.12?"UP":signed< -0.12?"DOWN":"SIDEWAYS";
        String pat=chop>0.78?"CHOPPY / RANGE":(Math.abs(trend)>0.55?"TREND / CONTINUATION":
            (Math.abs(rejection)>0.45?"WICK REJECTION":"MIXED PRICE ACTION"));
        if(chop>0.82||consistency<0.62||Math.abs(momentum)<0.22)
            return new Scan("WAIT",dir,pat,score,bn,qn,"WAIT · choppy or weak 10-second tick flow");
        if(Math.abs(signed)<0.66)
            return new Scan("WAIT",dir,pat,score,bn,qn,"WAIT · directional evidence below entry threshold");
        String side=signed>0?"CALL":"PUT";
        String why=side+" · adaptive price-action score "+Math.round(score)+"/95 · "+pat+" · "+dir;
        return new Scan(side,dir,pat,score,bn,qn,why);
    }

    private void settle(double expiryPrice){
        double delta=expiryPrice-entryPrice;
        double eps=Math.max(1e-12,Math.abs(entryPrice)*1e-12);
        if(Math.abs(delta)<=eps){ties++;lastResult="TIE";}
        else if(("CALL".equals(signal)&&delta>0)||("PUT".equals(signal)&&delta<0)){wins++;lastResult="WIN";}
        else{losses++;lastResult="LOSS";}
        reason="10s "+lastResult+" · entry "+fmt(entryPrice)+" → expiry "+fmt(expiryPrice);
        entryPrice=Double.NaN;entryTimestamp=0;expiryTimestamp=0;
    }

    private void recordScanTime(long ns){lastScanNs=ns;totalScanNs+=ns;maxScanNs=Math.max(maxScanNs,ns);}
    private static boolean validAsset(String s){return s!=null&&s.matches("[A-Za-z0-9_./-]{3,32}");}
    private static boolean validOhlc(double o,double h,double l,double c){return Double.isFinite(o)&&Double.isFinite(h)&&Double.isFinite(l)&&Double.isFinite(c)&&o>0&&c>0&&l>0&&h>=Math.max(o,c)&&l<=Math.min(o,c);}
    private static double num(JSONObject o,String...keys){for(String k:keys){Object v=o.opt(k);if(v instanceof Number)return ((Number)v).doubleValue();if(v instanceof String)try{return Double.parseDouble((String)v);}catch(Exception ignored){}}return Double.NaN;}
    private static int sign(double x){return x>0?1:x<0?-1:0;}
    private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    private static String fmt(double x){return String.format(java.util.Locale.US,"%.6f",x);}

    public synchronized String getSignal(){return signal;}
    public synchronized String getReason(){return reason;}
    public synchronized int getWins(){return wins;}
    public synchronized int getLosses(){return losses;}
    public synchronized int getTies(){return ties;}
    public synchronized int getSignalCount(){return wins+losses+ties+(pending?1:0);}
    public synchronized double getSignalProbability(){return evidenceScore;}
    public synchronized double getLastPrice(){return lastPrice;}
    public synchronized int getCandleCount(){return bars.size()+liveBars.size();}
    public synchronized int getAssetCandleCount(){return bars.size()+liveBars.size();}
    public synchronized double getOpen(){return !liveBars.isEmpty()?liveBars.peekLast().open:(bars.isEmpty()?Double.NaN:bars.peekLast().open);}
    public synchronized double getHigh(){return !liveBars.isEmpty()?liveBars.peekLast().high:(bars.isEmpty()?Double.NaN:bars.peekLast().high);}
    public synchronized double getLow(){return !liveBars.isEmpty()?liveBars.peekLast().low:(bars.isEmpty()?Double.NaN:bars.peekLast().low);}
    public synchronized double getClose(){return !liveBars.isEmpty()?liveBars.peekLast().close:(bars.isEmpty()?Double.NaN:bars.peekLast().close);}
    public synchronized double getVector(){return evidenceScore/100.0;}
    public synchronized double getHistogram(){return evidenceScore;}
    public synchronized double getDisplacement(){return Double.isFinite(lastPrice)&&Double.isFinite(entryPrice)?lastPrice-entryPrice:0;}
    public synchronized JSONArray getHistogramBars(){return new JSONArray();}
    public synchronized int getDirectionalUpTicks(){return 0;}
    public synchronized int getDirectionalDownTicks(){return 0;}
    public synchronized int getDirectionalNeutralTicks(){return 0;}
    public synchronized int getBrokerDirectionTickCount(){return 0;}

    public synchronized JSONObject snapshot(){
        JSONObject o=new JSONObject();
        try{
            o.put("asset",asset);o.put("signal",signal);o.put("direction",direction);o.put("pattern",pattern);
            o.put("detail",reason);o.put("evidenceScore",Math.round(evidenceScore));
            o.put("entryPrice",Double.isFinite(entryPrice)?entryPrice:JSONObject.NULL);
            o.put("entryTimestamp",entryTimestamp);o.put("expiryTimestamp",expiryTimestamp);
            o.put("remainingMs",pending?Math.max(0,expiryTimestamp-lastTimestamp):0);
            o.put("pending",pending);o.put("expirySeconds",10);o.put("lastResult",lastResult);
            o.put("wins",wins);o.put("losses",losses);o.put("ties",ties);
            o.put("candlesAvailable",bars.size()+liveBars.size());o.put("historyCandlesAvailable",bars.size());o.put("tickDerivedCandlesAvailable",liveBars.size());o.put("ticksAvailable",tape.size());
            o.put("candlesScanned",bars.size()+liveBars.size());o.put("ticksScanned",lastTicksScanned);
            o.put("recentCandlesAnalyzed",lastBarsScanned);o.put("fullHistoryScans",fullHistoryScans);
            o.put("historyLoads",historyLoads);o.put("fullChartDirection",historyDirection);
            o.put("fullChartHigh",Double.isFinite(historyHigh)?historyHigh:JSONObject.NULL);
            o.put("fullChartLow",Double.isFinite(historyLow)?historyLow:JSONObject.NULL);
            o.put("fullChartSwingChanges",fullHistorySwingChanges);
            o.put("lastScanUs",lastScanNs/1000.0);
            o.put("avgScanUs",scanCount>0?(totalScanNs/(double)scanCount)/1000.0:0);
            o.put("maxScanUs",maxScanNs/1000.0);o.put("lastTimestamp",lastTimestamp);
            o.put("lastPrice",Double.isFinite(lastPrice)?lastPrice:JSONObject.NULL);
        }catch(JSONException ignored){}
        return o;
    }

    private static final class Bar{final long time;final double open,high,low,close;Bar(long t,double o,double h,double l,double c){time=t;open=o;high=h;low=l;close=c;}}
    private static final class Quote{final double price;final long time;final Integer brokerDirection;Quote(double p,long t,Integer d){price=p;time=t;brokerDirection=d;}}
    private static final class Scan{final String signal,direction,pattern,reason;final double score;final int barsScanned,ticksScanned;Scan(String s,String d,String p,double sc,int b,int t,String r){signal=s;direction=d;pattern=p;score=sc;barsScanned=b;ticksScanned=t;reason=r;}}
}
