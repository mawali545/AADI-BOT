package com.dr.candletracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real-feed diagnostics: rolling OHLC memory, normalized price vector, cycle and latency. */
public final class MarketDiagnostics {
    private static final long BUCKET_MS = 10_000L;
    private static final int MAX_MEMORY = 180, MAX_TAPE = 32, MAX_LOGS = 80;
    private final Deque<Double> tape = new ArrayDeque<>();
    private final List<MemoryCandle> memory = new ArrayList<>();
    private final Deque<String> events = new ArrayDeque<>();
    private long cycles, lastSourceTime, lastArrivalTime;
    private long latencyMs = -1;
    private String node = "WAITING", scan = "WAITING", cipher = "LOCAL BRIDGE";
    private double vectorMagnitude;
    private double[] vector = new double[4];

    public synchronized void onTick(String asset, double price, long sourceTime, long arrivalTime) {
        if (asset == null || !Double.isFinite(price) || price <= 0 || sourceTime <= 0) return;
        cycles++;
        lastSourceTime = sourceTime;
        lastArrivalTime = arrivalTime;
        long rawLatency = arrivalTime - sourceTime;
        latencyMs = rawLatency < 0 ? 0 : Math.min(rawLatency, 120_000L);
        long bucket = sourceTime / BUCKET_MS * BUCKET_MS;
        MemoryCandle current = memory.isEmpty() ? null : memory.get(memory.size()-1);
        if (current == null || current.bucket != bucket || !current.asset.equals(asset)) {
            current = new MemoryCandle(asset, bucket, price);
            memory.add(current);
            if (memory.size() > MAX_MEMORY) memory.remove(0);
        }
        current.update(price);
        tape.addLast(price);
        while (tape.size() > MAX_TAPE) tape.removeFirst();
        calculateVector();
        node = "ARMED";
        scan = "PROCESSING";
        log("LIVE TICK " + asset);
    }

    public synchronized void onHistory(String asset, int candles) {
        log("HISTORY " + asset + " · " + candles + " rows");
    }
    public synchronized void onConnection(String state) {
        node = "CONNECTED".equals(state) ? "ARMED" : "RECONNECTING";
        log("QX WEBSOCKET " + state);
    }
    public synchronized void onSignal(String signal) {
        scan = signal == null ? "WAIT" : signal;
        log("SCAN RESULT " + scan);
    }
    public synchronized void onCipherState(String state) {
        cipher = state == null ? "LOCKED" : state;
        log("BRIDGE " + cipher);
    }
    public synchronized void onRejected(String reason) {
        log("REJECTED " + (reason == null ? "UNKNOWN" : reason));
    }
    private void log(String event) {
        events.addLast(System.currentTimeMillis() + " " + event);
        while (events.size() > MAX_LOGS) events.removeFirst();
    }
    private void calculateVector() {
        if (tape.size() < 4) { vector = new double[4]; vectorMagnitude = 0; return; }
        Double[] p = tape.toArray(new Double[0]);
        double scale = Math.max(Math.abs(p[p.length-1]) * 1e-8, 1e-12);
        double first = (p[p.length-1] - p[0]) / scale;
        double second = (p[p.length-1] - 2*p[p.length-2] + p[p.length-3]) / scale;
        double up = 0, down = 0, travel = 0;
        for (int i=1;i<p.length;i++) {
            double d=p[i]-p[i-1]; travel += Math.abs(d);
            if(d>0) up += d; else down += -d;
        }
        double efficiency = travel == 0 ? 0 : (p[p.length-1]-p[0])/travel;
        double pressure = travel == 0 ? 0 : (up-down)/travel;
        vector = new double[]{clamp(first/Math.max(1,p.length-1)), clamp(second), clamp(efficiency), clamp(pressure)};
        vectorMagnitude = Math.sqrt(vector[0]*vector[0]+vector[1]*vector[1]+vector[2]*vector[2]+vector[3]*vector[3]);
    }
    private static double clamp(double x) { return Math.max(-1, Math.min(1, x)); }

    public synchronized JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("memoryCount", memory.size());
            JSONArray candles = new JSONArray();
            int from = Math.max(0, memory.size()-12);
            for (int i=from;i<memory.size();i++) {
                MemoryCandle c=memory.get(i); JSONObject row=new JSONObject();
                row.put("asset",c.asset); row.put("time",c.bucket); row.put("open",c.open);
                row.put("high",c.high); row.put("low",c.low); row.put("close",c.close);
                candles.put(row);
            }
            o.put("memoryStream",candles);
            JSONArray v = new JSONArray(); for(double x:vector)v.put(x);
            o.put("vector",v); o.put("vectorMagnitude",vectorMagnitude);
            o.put("cycle",cycles); o.put("latencyMs",latencyMs);
            o.put("node",node); o.put("scanState",scan); o.put("cipherState",cipher);
            JSONArray log = new JSONArray(); for(String e:events)log.put(e);
            o.put("eventLog",log);
        } catch(Exception ignored) {}
        return o;
    }
    public synchronized long getCycles() { return cycles; }
    public synchronized int getMemoryCount() { return memory.size(); }
    public synchronized long getLatencyMs() { return latencyMs; }
    public synchronized double getVectorMagnitude() { return vectorMagnitude; }
    public synchronized double getLatestMemoryHigh() { return memory.isEmpty() ? Double.NaN : memory.get(memory.size()-1).high; }

    private static final class MemoryCandle {
        final String asset; final long bucket; final double open; double high,low,close;
        MemoryCandle(String a,long b,double p){asset=a;bucket=b;open=p;high=p;low=p;close=p;}
        void update(double p){high=Math.max(high,p);low=Math.min(low,p);close=p;}
    }
}