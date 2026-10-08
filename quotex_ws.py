from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable

import websockets

from config import ASSET, IS_DEMO, SSID, TIMEFRAME, WSS_URL

log = logging.getLogger("aadi.quotex")


class QuotexSocket:
    def __init__(self, on_message: Callable[[str, dict], Awaitable[None]]):
        self.on_message = on_message
        self.ws = None
        self.connected = False

    async def send_event(self, name: str, payload: dict | None = None):
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        body = [name, payload or {}]
        await self.ws.send("42" + json.dumps(body, separators=(",", ":")))

    async def connect(self):
        if not SSID:
            raise RuntimeError(
                "AADI_QUOTEX_SSID is missing. Set the live Quotex session locally; never commit it."
            )

        self.ws = await websockets.connect(
            WSS_URL,
            ping_interval=None,
            max_size=8 * 1024 * 1024,
            origin="https://quotex.io",
        )
        self.connected = True

        handshake = await self.ws.recv()
        if isinstance(handshake, bytes):
            handshake = handshake.decode()

        if not str(handshake).startswith("0"):
            raise RuntimeError(f"Unexpected Socket.IO handshake: {handshake!r}")

        await self.ws.send("40")

        # Quotex Socket.IO authorization used by current community protocol implementations.
        await self.send_event(
            "authorization",
            {"session": SSID, "isDemo": 1 if IS_DEMO else 0, "tournamentId": 0},
        )

        await self.send_event(
            "subscribe_candles",
            {"asset": ASSET, "timeframe": TIMEFRAME},
        )
        await self.send_event(
            "subscribe_quotes",
            {"asset": ASSET},
        )

    async def run(self):
        backoff = 2
        while True:
            try:
                await self.connect()
                backoff = 2
                assert self.ws is not None

                async for raw in self.ws:
                    if raw == "2":
                        await self.ws.send("3")
                        continue
                    if raw == "3":
                        continue
                    if not isinstance(raw, str) or not raw.startswith("42"):
                        continue

                    try:
                        event, payload = json.loads(raw[2:])
                    except Exception:
                        log.exception("Could not parse Socket.IO event")
                        continue

                    await self.on_message(str(event), payload if isinstance(payload, dict) else {})
            except asyncio.CancelledError:
                raise
            except Exception:
                self.connected = False
                log.exception("Quotex WebSocket disconnected; retrying")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30)
