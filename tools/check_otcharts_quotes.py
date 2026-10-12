#!/usr/bin/env python3
"""Fetch one recent Quotex candle for manual comparison; never opens a live stream."""
from __future__ import annotations

import os
import sys


def main() -> int:
    key = os.getenv("AADI_OTCHARTS_API_KEY", "")
    if not key:
        print("MISSING_KEY: set AADI_OTCHARTS_API_KEY locally in Termux.")
        return 2

    asset = os.getenv("AADI_ASSET", "EURUSD_otc")
    timeframe = int(os.getenv("AADI_TIMEFRAME", "60"))
    from otcharts import Client

    try:
        client = Client(api_key=key)
        symbols = {item.symbol for item in client.symbols("quotex")}
        if asset not in symbols:
            print(f"SYMBOL_NOT_LISTED: {asset}; use a symbol returned by OTCharts for venue=quotex.")
            return 3
        bars = client.candles("quotex", asset, tf=timeframe, limit=2)
        if not bars:
            print("NO_CANDLES: service returned no candles for this symbol/timeframe.")
            return 4
        bar = bars[-1]
        print("SOURCE=OTCHARTS (independent, unofficial; not verified by Quotex)")
        print(f"SYMBOL={asset}")
        print(f"TIMEFRAME_SECONDS={timeframe}")
        print(f"LATEST_BAR_OPEN_UNIX={bar.time}")
        print(f"OPEN={bar.open}")
        print(f"HIGH={bar.high}")
        print(f"LOW={bar.low}")
        print(f"CLOSE={bar.close}")
        print("Compare this candle's timestamp and OHLC with the same Quotex app chart before using this feed.")
        return 0
    except Exception as exc:
        print(f"OTCHARTS_CHECK_FAILED: {type(exc).__name__}: {str(exc)[:300]}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
