from __future__ import annotations

import time
from collections import deque
from typing import Any

from config import (
    ASSET,
    EMA_FAST,
    EMA_SLOW,
    MIN_CONFLUENCE,
    RSI_PERIOD,
    STALE_AFTER,
    TIMEFRAME,
)
from indicators import signal
from quotex_ws import QuotexSocket


class Scanner:
    """Builds candles only from real Quotex history/ticks; never fabricates market data."""

    def __init__(self):
        self.closes: deque[float] = deque(maxlen=1000)
        self.current_candle: dict[str, float | int] | None = None
        self.last_tick_at = 0.0
        self.latest: dict[str, Any] = {
            "asset": ASSET,
            "signal": "WAIT",
            "score": 0,
            "connected": False,
            "authenticated": False,
            "market_status": "DISCONNECTED",
            "price": None,
            "timestamp": None,
            "data_age": None,
            "candle_count": 0,
        }
        self.socket = QuotexSocket(self.handle_event)

    @staticmethod
    def _number(value: Any) -> float | None:
        return float(value) if isinstance(value, (int, float)) else None

    def _set_price(self, price: float, timestamp: float | None = None):
        self.latest["price"] = price
        self.latest["timestamp"] = timestamp or time.time()
        self.last_tick_at = time.monotonic()

    def _append_closed_candle(self, close: float):
        self.closes.append(close)
        if len(self.closes) >= max(EMA_SLOW, RSI_PERIOD + 1) + 1:
            self.latest.update(
                signal(self.closes, EMA_FAST, EMA_SLOW, RSI_PERIOD, MIN_CONFLUENCE)
            )

    def _consume_tick(self, symbol: Any, timestamp: Any, price: Any):
        if symbol is not None and str(symbol) != ASSET:
            return
        p = self._number(price)
        ts = self._number(timestamp)
        if p is None:
            return
        if ts is None:
            ts = time.time()
        if ts > 10_000_000_000:
            ts /= 1000.0

        self._set_price(p, ts)
        bucket = int(ts // TIMEFRAME) * TIMEFRAME

        if self.current_candle is None:
            self.current_candle = {
                "time": bucket, "open": p, "high": p, "low": p, "close": p
            }
            return

        current_time = int(self.current_candle["time"])
        if bucket < current_time:
            return

        if bucket > current_time:
            self._append_closed_candle(float(self.current_candle["close"]))
            self.current_candle = {
                "time": bucket, "open": p, "high": p, "low": p, "close": p
            }
            return

        self.current_candle["high"] = max(float(self.current_candle["high"]), p)
        self.current_candle["low"] = min(float(self.current_candle["low"]), p)
        self.current_candle["close"] = p

    def _load_history(self, payload: Any):
        rows: Any = []
        if isinstance(payload, dict):
            rows = payload.get("candles")
            if rows is None:
                rows = payload.get("history")
            if isinstance(rows, dict):
                rows = rows.get("candles") or rows.get("history") or []
        elif isinstance(payload, list):
            rows = payload

        if not isinstance(rows, list):
            return

        parsed: list[tuple[float, float]] = []
        for row in rows:
            if isinstance(row, (list, tuple)) and len(row) >= 3:
                ts = self._number(row[0])
                close = self._number(row[2])
                if ts is not None and close is not None:
                    parsed.append((ts, close))
            elif isinstance(row, dict):
                ts = self._number(row.get("time") or row.get("timestamp"))
                close = self._number(row.get("close"))
                if ts is not None and close is not None:
                    parsed.append((ts, close))

        for _, close in sorted(parsed, key=lambda x: x[0]):
            self.closes.append(close)

        if self.closes:
            self.latest["candle_count"] = len(self.closes)
            self.latest.update(
                signal(self.closes, EMA_FAST, EMA_SLOW, RSI_PERIOD, MIN_CONFLUENCE)
            )

    async def handle_event(self, event: str, payload: object):
        self.latest["last_event"] = event

        if event in {"history/list/v2", "history/list", "history/load", "candles"}:
            self._load_history(payload)

        if event == "quotes/stream":
            rows = payload if isinstance(payload, list) else []
            for row in rows:
                if isinstance(row, (list, tuple)) and len(row) >= 3:
                    self._consume_tick(row[0], row[1], row[2])
                elif isinstance(row, dict):
                    self._consume_tick(
                        row.get("asset") or row.get("symbol"),
                        row.get("time") or row.get("timestamp"),
                        row.get("price") or row.get("close"),
                    )

        elif event in {"candle-generated", "candle"} and isinstance(payload, dict):
            price = self._number(payload.get("close"))
            ts = self._number(payload.get("time") or payload.get("timestamp"))
            if price is not None:
                self._consume_tick(payload.get("asset") or ASSET, ts, price)

        self.latest["connected"] = self.socket.connected
        self.latest["authenticated"] = self.socket.authenticated
        self.latest["candle_count"] = len(self.closes)

    async def run(self):
        await self.socket.run()

    def snapshot(self):
        out = dict(self.latest)
        age = None if not self.last_tick_at else time.monotonic() - self.last_tick_at
        out["data_age"] = age
        out["candle_count"] = len(self.closes)

        if not self.socket.connected:
            out["market_status"] = "DISCONNECTED"
        elif not self.socket.authenticated:
            out["market_status"] = "AUTHENTICATING"
        elif age is None or age > STALE_AFTER:
            out["market_status"] = "STALE"
        else:
            out["market_status"] = "LIVE"

        return out


scanner = Scanner()
