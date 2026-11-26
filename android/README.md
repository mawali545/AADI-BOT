# AADI OTCharts QX Signal — Build 259

## Market data source
- OTCharts is the only market-data source used by the Android signal screen.
- Venue is fixed to `quotex`; the instrument is entered using the exact OTCharts symbol, e.g. `EURUSD_otc`.
- The app loads 5-second candle history once, then holds the OTCharts Server-Sent Events live stream.
- It does not read the Quotex WebSocket, use a Quotex SSID/login, or fall back to another provider.
- The OTCharts API key is entered at runtime and is not embedded in the APK or committed to Git.
- Manual trading only. No orders are placed.

## Plan requirements
OTCharts free keys allow limited candle requests but do not include live streaming. A live-enabled OTCharts plan is required for continuous CALL/PUT signal operation. If the API key, venue, symbol, quota, or plan is rejected, the app displays the reason and does not use another feed.

## Signal engine
The existing native SignalEngine is retained. It consumes OTCharts 5-second history and real-time ticks, and evaluates 10-second decision/settlement slots. Signal quality and timing still require on-device forward testing against the Quotex screen. A successful APK build does not prove profitability or accuracy.

## Build
GitHub Actions validates the Android JavaScript asset, runs JVM unit tests, assembles the debug APK, and uploads the APK and Android source ZIP.
