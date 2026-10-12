from __future__ import annotations

import logging
import time
from typing import Callable

log = logging.getLogger("aadi.otcharts")


def run_feed(
    api_key: str,
    asset: str,
    timeframe: int,
    history_limit: int,
    on_history: Callable[[list[dict[str, float | int]]], None],
    on_tick: Callable[[str, float, float], None],
    on_status: Callable[[bool, str], None],
) -> None:
    """Read-only Quotex-book feed via OTCharts; never places trades.

    This is an independent third-party data service, not an official Quotex API.
    Callers must verify that its quotes match the instrument in their own app.
    """
    if not api_key:
        raise RuntimeError("AADI_OTCHARTS_API_KEY is missing; create an OTCharts API key first.")

    from otcharts import Client

    client = Client(api_key=api_key)
    bars = client.candles("quotex", asset, tf=timeframe, limit=max(1, min(history_limit, 5000)))
    on_history([
        {"time": int(bar.time), "open": float(bar.open), "high": float(bar.high),
         "low": float(bar.low), "close": float(bar.close)}
        for bar in bars
    ])
    on_status(True, "OTCharts read-only feed connected; verify quotes against Quotex app")

    for tick in client.stream("quotex", asset):
        if tick.symbol and tick.symbol != asset:
            continue
        on_tick(str(tick.symbol or asset), float(tick.time), float(tick.price))
        on_status(True, "OTCharts read-only feed connected; verify quotes against Quotex app")
