#!/usr/bin/env python3
"""Optional Chromium-TLS-fingerprint WebSocket handshake probe.

No credentials, session cookies, account actions, or trades are used.
This only tests whether a public Socket.IO handshake is accepted.
"""
from __future__ import annotations

import asyncio
import os
import sys

try:
    from curl_cffi.requests import AsyncSession, CurlWsFlag
except ImportError:
    print("MISSING_DEPENDENCY: install curl_cffi to run this optional probe")
    raise SystemExit(2)

HOSTS = [
    os.getenv("AADI_PROBE_HOST", "ws2.quotex.com"),
    "ws2.qxbroker.com",
]
ORIGINS = ["https://quotex.com", "https://qxbroker.com"]


async def probe(host: str, origin: str) -> bool:
    url = f"wss://{host}/socket.io/?EIO=3&transport=websocket"
    headers = {
        "Origin": origin,
        "Referer": origin + "/",
        "Accept-Language": "en-US,en;q=0.9",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
    }
    async with AsyncSession(impersonate="chrome", timeout=12) as session:
        ws = None
        try:
            ws = await session.ws_connect(url, headers=headers)
            message, _flags = await asyncio.wait_for(ws.recv(), timeout=10)
            text = message.decode("utf-8", "replace") if isinstance(message, bytes) else str(message)
            print(f"{host} origin={origin}: first_frame={text[:180]!r}")
            if not text.startswith("0"):
                return False
            await ws.send("40", flags=CurlWsFlag.TEXT)
            try:
                reply, _flags = await asyncio.wait_for(ws.recv(), timeout=6)
                reply_text = reply.decode("utf-8", "replace") if isinstance(reply, bytes) else str(reply)
                print(f"{host} origin={origin}: namespace_reply={reply_text[:180]!r}")
                return reply_text == "40" or reply_text.startswith("40{")
            except asyncio.TimeoutError:
                print(f"{host} origin={origin}: transport opened; namespace ACK timed out")
                return False
        except Exception as exc:
            print(f"{host} origin={origin}: {type(exc).__name__}: {str(exc)[:220]}")
            return False
        finally:
            if ws is not None:
                try:
                    await ws.close()
                except Exception:
                    pass


async def main() -> int:
    for host in dict.fromkeys(HOSTS):
        for origin in ORIGINS:
            if await probe(host, origin):
                print("HANDSHAKE_OK: public Socket.IO namespace opened; no authentication or ticks tested")
                return 0
    print("NO_HANDSHAKE: alternate TLS client did not open the Socket.IO namespace")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
