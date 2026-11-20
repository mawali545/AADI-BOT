from __future__ import annotations

from collections import deque
from typing import Iterable


def ema(values: Iterable[float], period: int) -> float | None:
    vals = list(values)
    if len(vals) < period:
        return None
    k = 2 / (period + 1)
    value = sum(vals[:period]) / period
    for price in vals[period:]:
        value = price * k + value * (1 - k)
    return value


def rsi(values: Iterable[float], period: int = 14) -> float | None:
    vals = list(values)
    if len(vals) < period + 1:
        return None
    gains = []
    losses = []
    for a, b in zip(vals[-period-1:-1], vals[-period:]):
        change = b - a
        gains.append(max(change, 0.0))
        losses.append(max(-change, 0.0))
    avg_gain = sum(gains) / period
    avg_loss = sum(losses) / period
    if avg_loss == 0:
        return 100.0
    rs = avg_gain / avg_loss
    return 100 - (100 / (1 + rs))


def momentum(values: Iterable[float], lookback: int = 5) -> float | None:
    vals = list(values)
    if len(vals) <= lookback:
        return None
    return vals[-1] - vals[-1-lookback]


def signal(closes: deque[float], fast: int, slow: int, rsi_period: int, minimum: int) -> dict:
    f = ema(closes, fast)
    s = ema(closes, slow)
    r = rsi(closes, rsi_period)
    m = momentum(closes)

    if f is None or s is None or r is None or m is None:
        return {"signal": "WAIT", "score": 0, "ema": None, "rsi": r, "momentum": m}

    score = 0
    if f > s:
        score += 1
    elif f < s:
        score -= 1

    if r >= 55:
        score += 1
    elif r <= 45:
        score -= 1

    if m > 0:
        score += 1
    elif m < 0:
        score -= 1

    if score >= minimum:
        sig = "CALL"
    elif score <= -minimum:
        sig = "PUT"
    else:
        sig = "WAIT"

    return {"signal": sig, "score": score, "ema_fast": f, "ema_slow": s, "rsi": r, "momentum": m}
