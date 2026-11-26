package com.dr.candletracker;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;

/** Native signal screen. OTCharts is the sole market-data source; no Quotex login/feed fallback. */
public final class MainActivity extends Activity {
    private final SignalEngine engine = new SignalEngine();
    private final FeedIntegrityMonitor integrity = new FeedIntegrityMonitor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private OTChartsFeedClient feed;
    private EditText keyInput, symbolInput;
    private TextView status, signal, price, stats, history;
    private String activeSymbol = "EURUSD_otc";
    private long ticks = 0, lastTickWall = 0, lastSourceMs = 0;
    private boolean instrumentClosed = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(3, 12, 8));
        getWindow().setNavigationBarColor(Color.rgb(3, 12, 8));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();
    }
    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(20));
        root.setBackgroundColor(Color.rgb(3, 12, 8));
        scroll.addView(root);
        root.addView(label("AADI · OTCHARTS QX SIGNAL", 22, Color.rgb(140,255,180)));
        root.addView(label("Sole feed: OTCharts Quotex · Manual trades only", 13, Color.LTGRAY));
        root.addView(label("OTCharts API key", 13, Color.WHITE));
        keyInput = new EditText(this);
        keyInput.setSingleLine(true); keyInput.setTextColor(Color.WHITE);
        keyInput.setHintTextColor(Color.GRAY); keyInput.setHint("Paste your OTCharts key here");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(keyInput, params());
        root.addView(label("Quotex instrument", 13, Color.WHITE));
        symbolInput = new EditText(this);
        symbolInput.setSingleLine(true); symbolInput.setText("EURUSD_otc");
        symbolInput.setTextColor(Color.WHITE); symbolInput.setHintTextColor(Color.GRAY);
        symbolInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(symbolInput, params());
        Button connect = new Button(this);
        connect.setText("CONNECT OTCHARTS"); root.addView(connect, params());
        connect.setOnClickListener(v -> connectFeed());
        status = label("NOT CONNECTED · Enter API key and instrument", 14, Color.YELLOW);
        root.addView(status);
        signal = label("WAIT", 40, Color.WHITE); signal.setGravity(Gravity.CENTER);
        root.addView(signal, params());
        price = label("Price: --", 18, Color.WHITE); root.addView(price);
        stats = label("Ticks: 0 · Wins: 0 · Losses: 0", 14, Color.LTGRAY); root.addView(stats);
        history = label("Waiting for OTCharts candle history…", 13, Color.LTGRAY); root.addView(history);
        root.addView(label("Free OTCharts keys do not include live streaming. A live-enabled plan is required. The app will not switch to QX or another feed.", 12, Color.rgb(255,210,120)));
        setContentView(scroll);
    }
    private void connectFeed() {
        String key = keyInput.getText().toString().trim();
        String symbol = symbolInput.getText().toString().trim();
        if (key.isEmpty()) { status.setText("Enter your OTCharts API key."); return; }
        if (!symbol.matches("[A-Za-z0-9_#./-]{3,40}")) { status.setText("Invalid instrument id."); return; }
        if (feed != null) feed.stop();
        activeSymbol = symbol; ticks = 0; lastTickWall = 0; lastSourceMs = 0; instrumentClosed = false;
        status.setText("Connecting to OTCharts Quotex feed…");
        signal.setText("WAIT"); price.setText("Price: --");
        history.setText("Loading 5-second candle history…");
        feed = new OTChartsFeedClient(key, symbol, new OTChartsFeedClient.Listener() {
            @Override public void onStatus(String value) { ui.post(() -> status.setText(value)); }
            @Override public void onClosed(boolean closed) {
                ui.post(() -> { instrumentClosed = closed; if (closed) signal.setText("WAIT · CLOSED"); });
            }
            @Override public void onHistory(JSONArray candles, int missingBars) {
                ui.post(() -> {
                    if (missingBars > 0) {
                        history.setText("History has " + missingBars + " missing bars; not used for signal warm-up.");
                        return;
                    }
                    if (integrity.recordHistory(activeSymbol, 5, candles)) {
                        engine.acceptHistory(activeSymbol, 5, candles);
                        history.setText("OTCharts history loaded: " + candles.length() + " candles · 5s");
                    } else history.setText("History validation failed; waiting for clean live ticks.");
                    refresh();
                });
            }
            @Override public void onTick(double value, long seconds) {
                long stamp = seconds * 1000L;
                ui.post(() -> {
                    if (instrumentClosed || !activeSymbol.equals(symbolInput.getText().toString().trim())) return;
                    if (stamp <= lastSourceMs || stamp > System.currentTimeMillis() + 5000L) return;
                    long arrival = android.os.SystemClock.elapsedRealtime();
                    if (!integrity.recordTick(activeSymbol, stamp, arrival, 10)) return;
                    lastSourceMs = stamp; ticks++; lastTickWall = System.currentTimeMillis();
                    engine.acceptTick(activeSymbol, value, stamp, 10, null);
                    refresh();
                });
            }
        });
        feed.start();
    }
    private void refresh() {
        String s = instrumentClosed ? "WAIT · CLOSED" : engine.getSignal();
        signal.setText(s);
        signal.setTextColor("CALL".equals(s) ? Color.rgb(90,255,150) :
            ("PUT".equals(s) ? Color.rgb(255,105,105) : Color.WHITE));
        price.setText("Price: " + (Double.isFinite(engine.getLastPrice()) ?
            String.format(java.util.Locale.US, "%.8f", engine.getLastPrice()) : "--"));
        stats.setText("Ticks: " + ticks + " · Wins: " + engine.getWins() +
            " · Losses: " + engine.getLosses() + " · Age: " +
            (lastTickWall == 0 ? "--" : (System.currentTimeMillis() - lastTickWall) + " ms"));
    }
    private TextView label(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(0, dp(7), 0, dp(7)); return v;
    }
    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8); return p;
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    @Override protected void onDestroy() {
        if (feed != null) feed.stop();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
