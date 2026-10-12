#!/usr/bin/env python3
"""Check whether a public Quotex Socket.IO session emits market ticks.

This probe uses no credentials, cookies, account actions, or trades. It checks a public namespace and whether recognizable market-data events arrive; an ACK alone is not live data.
"""
from __future__ import annotations

import asyncio
import json
import os

from curl_cffi import CurlWsFlag
from curl_cffi.requests import AsyncSession

HOST = os.getenv("AADI_PROBE_HOST", "ws2.quotex.com")
ORIGIN = os.getenv("AADI_WS_ORIGIN", "https://quotex.com")
ASSET = os.getenv("AADI_ASSET", "EURUSD_otc")
TIMEFRAME = int(os.getenv("AADI_TIMEFRAME", "60"))


async def main() -> int:
    url = f"wss://{HOST}/socket.io/?EIO=3&transport=websocket"
    headers = {
        "Origin": ORIGIN,
        "Referer": ORIGIN + "/",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
        "Accept-Language": "en-US,en;q=0.9",
    }

    async with AsyncSession(impersonate="chrome", timeout=12) as session:
        try:
            async with session.ws_connect(url, headers=headers) as ws:
                first, _flags = await asyncio.wait_for(ws.recv(), timeout=10)
                first = first.decode("utf-8", "replace") if isinstance(first, bytes) else str(first)
                print(f"first_frame={first[:200]!r}")
                if not first.startswith("0"):
                    print("NO_ENGINE_IO_HANDSHAKE")
                    return 1

                await ws.send("40", flags=CurlWsFlag.TEXT)
                ack, _flags = await asyncio.wait_for(ws.recv(), timeout=8)
                ack = ack.decode("utf-8", "replace") if isinstance(ack, bytes) else str(ack)
                print(f"namespace_reply={ack[:200]!r}")
                if not (ack == "40" or ack.startswith("40{")):
                    print("NO_PUBLIC_NAMESPACE")
                    return 1

                # Public, unauthenticated subscription attempts only. If the
                # server requires authorization, this probe stops without trying
                # to evade that requirement.
                await ws.send("42" + json.dumps(["tick"], separators=(",", ":")), flags=CurlWsFlag.TEXT)
                await ws.send(
                    "42" + json.dumps(
                        ["instruments/update", {"asset": ASSET, "period": TIMEFRAME}],
                        separators=(",", ":"),
                    ), flags=CurlWsFlag.TEXT,
                )

                deadline = asyncio.get_running_loop().time() + 15
                while asyncio.get_running_loop().time() < deadline:
                    remaining = deadline - asyncio.get_running_loop().time()
                    try:
                        raw, flags = await asyncio.wait_for(ws.recv(), timeout=remaining)
                    except asyncio.TimeoutError:
                        break
                    if flags & CurlWsFlag.BINARY:
                        print(f"BINARY_FRAME: {len(raw)} bytes; not yet decoded as a verified tick")
                        continue
                    message = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else str(raw)
                    print(f"frame={message[:500]!r}")
                    if message.startswith("42"):
                        try:
                            packet = json.loads(message[2:])
                        except (TypeError, ValueError):
                            continue
                        event = str(packet[0]) if isinstance(packet, list) and packet else ""
                        payload = packet[1] if isinstance(packet, list) and len(packet) > 1 else None
                        if event in {"quotes/stream", "tick", "candle-generated", "candle"} and payload:
                            print("LIVE_DATA_CANDIDATE: received market-data event; inspect payload before production use")
                            return 0
                print("NO_TICK: public namespace opened but no recognizable live tick arrived without authorization")
                return 2
        except Exception as exc:
            print(f"PUBLIC_STREAM_FAILED: {type(exc).__name__}: {str(exc)[:250]}")
            return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
