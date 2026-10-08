from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Awaitable, Callable

import websockets

from config import ASSET, HISTORY_CANDLES, SSID, TIMEFRAME, WS_ORIGIN, WSS_URL

log = logging.getLogger("aadi.quotex")


class QuotexSocket:
    """Real Quotex Socket.IO/Engine.IO market-data connection only."""

    def __init__(self, on_event: Callable[[str, object], Awaitable[None]]):
        self.on_event = on_event
        self.ws = None
        self.connected = False
        self.authenticated = False
        self.last_message_at = 0.0
        self._pending_binary_event: str | None = None

    async def send_event(self, name: str, payload: object | None = None):
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        packet = [name] if payload is None else [name, payload]
        await self.ws.send("42" + json.dumps(packet, separators=(",", ":")))

    async def connect(self):
        if not SSID:
            raise RuntimeError("AADI_QUOTEX_SSID is missing; provide a live Quotex SSID.")
        if "EIO=3" not in WSS_URL or "ws2." not in WSS_URL:
            raise RuntimeError("Configured endpoint is not the expected live Quotex EIO=3 ws2 endpoint.")

        self.ws = await websockets.connect(
            WSS_URL,
            ping_interval=None,
            max_size=16 * 1024 * 1024,
            origin=WS_ORIGIN,
        )
        handshake = await self.ws.recv()
        self.last_message_at = time.monotonic()

        if isinstance(handshake, bytes):
            handshake = handshake.decode("utf-8", "replace")
        if not str(handshake).startswith("0"):
            raise RuntimeError(f"Unexpected Engine.IO handshake: {handshake!r}")

        await self.ws.send("40")
        await self.send_event(
            "authorization",
            {"session": SSID, "isDemo": 0, "tournamentId": 0},
        )

    async def subscribe(self):
        await self.send_event("tick")
        await self.send_event("indicator/list")
        await self.send_event("drawing/load")
        await self.send_event("pending/list")
        await self.send_event(
            "instruments/update",
            {"asset": ASSET, "period": TIMEFRAME},
        )
        await self.send_event("depth/follow", ASSET)
        await self.send_event("chart_notification/get")
        await self.send_event("history/load", {
            "asset": ASSET,
            "index": 0,
            "time": int(time.time()),
            "offset": max(HISTORY_CANDLES, 300),
            "period": TIMEFRAME,
        })

    async def _dispatch(self, event: str, payload: object):
        if event == "s_authorization":
            self.authenticated = True
            await self.subscribe()
        await self.on_event(event, payload)

    async def _handle_text(self, raw: str):
        if raw == "2":
            if self.ws:
                await self.ws.send("3")
            return
        if raw in {"3", "40"}:
            return
        if raw == "41":
            raise ConnectionError("Quotex Socket.IO server disconnected")

        if raw.startswith("451-"):
            try:
                data = json.loads(raw[4:])
                if isinstance(data, list) and data:
                    self._pending_binary_event = str(data[0])
            except (TypeError, ValueError):
                log.warning("Invalid Socket.IO binary event header")
            return

        if raw.startswith("42"):
            try:
                data = json.loads(raw[2:])
            except (TypeError, ValueError):
                return
            if isinstance(data, list) and data:
                await self._dispatch(
                    str(data[0]),
                    data[1] if len(data) > 1 else {},
                )

    async def _handle_binary(self, raw: bytes):
        text = raw.decode("utf-8", "replace")
        if text.startswith("\x04"):
            text = text[1:]
        try:
            payload = json.loads(text)
        except (TypeError, ValueError):
            return

        event = self._pending_binary_event
        self._pending_binary_event = None

        if event:
            await self._dispatch(event, payload)
        elif isinstance(payload, dict):
            if "candles" in payload or "history" in payload:
                await self._dispatch("history/list/v2", payload)
        elif isinstance(payload, list):
            await self._dispatch("quotes/stream", payload)

    async def run(self):
        backoff = 2
        while True:
            try:
                self.connected = False
                self.authenticated = False
                await self.connect()
                self.connected = True
                backoff = 2

                async for raw in self.ws:
                    self.last_message_at = time.monotonic()
                    if isinstance(raw, bytes):
                        await self._handle_binary(raw)
                    else:
                        await self._handle_text(raw)

            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.connected = False
                self.authenticated = False
                log.warning("Live Quotex connection lost: %s", exc)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30)
