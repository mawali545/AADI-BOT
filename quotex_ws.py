from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Awaitable, Callable

try:
    from curl_cffi import CurlWsFlag
    from curl_cffi.requests import AsyncSession
except ImportError:  # Optional legacy direct-WebSocket mode.
    CurlWsFlag = None
    AsyncSession = None

from config import ASSET, HISTORY_CANDLES, SSID, TIMEFRAME, WS_ORIGIN, WSS_URL

log = logging.getLogger("aadi.quotex")


class QuotexSocket:
    """Real Quotex Socket.IO/Engine.IO market-data connection only."""

    def __init__(self, on_event: Callable[[str, object], Awaitable[None]]):
        self.on_event = on_event
        self.ws = None
        self._session = None
        self.connected = False
        self.authenticated = False
        self.last_message_at = 0.0
        self._pending_binary_event: str | None = None

    async def _send_text(self, text: str):
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        await self.ws.send(text, flags=CurlWsFlag.TEXT)

    async def _recv_text(self) -> str:
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        payload, flags = await self.ws.recv()
        if flags & CurlWsFlag.BINARY:
            await self._handle_binary(payload)
            return ""
        return payload.decode("utf-8", "replace") if isinstance(payload, bytes) else str(payload)

    async def send_event(self, name: str, payload: object | None = None):
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        packet = [name] if payload is None else [name, payload]
        await self._send_text("42" + json.dumps(packet, separators=(",", ":")))

    async def connect(self):
        if AsyncSession is None or CurlWsFlag is None:
            raise RuntimeError("Direct Quotex WebSocket mode needs curl_cffi; install requirements-quotex-ws.txt or use AADI_FEED_PROVIDER=otcharts.")
        if not SSID:
            raise RuntimeError("AADI_QUOTEX_SSID is missing; provide a live Quotex SSID.")
        if "EIO=3" not in WSS_URL or "ws2." not in WSS_URL:
            raise RuntimeError("Configured endpoint is not the expected live Quotex EIO=3 ws2 endpoint.")

        headers = {
            "Origin": WS_ORIGIN,
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "Accept-Language": "en-US,en;q=0.9",
        }

        # Use curl_cffi's documented WebSocket API without TLS fingerprint
        # impersonation. Certificate verification stays enabled.
        self._session = AsyncSession(impersonate="chrome", timeout=15)
        self.ws = await self._session.ws_connect(WSS_URL, headers=headers)
        handshake = await self._recv_text()
        self.last_message_at = time.monotonic()

        if isinstance(handshake, bytes):
            handshake = handshake.decode("utf-8", "replace")
        if not str(handshake).startswith("0"):
            raise RuntimeError(f"Unexpected Engine.IO handshake: {handshake!r}")

        # Engine.IO opened; now wait for the Socket.IO namespace ACK before
        # sending any application event. Community clients commonly require
        # this separate "40" acknowledgement from the server.
        await self._send_text("40")
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            try:
                packet = await asyncio.wait_for(
                    self._recv_text(),
                    timeout=max(0.1, deadline - time.monotonic()),
                )
            except asyncio.TimeoutError as exc:
                raise TimeoutError(
                    "Timed out waiting for Quotex Socket.IO namespace acknowledgement."
                ) from exc
            self.last_message_at = time.monotonic()
            if isinstance(packet, bytes):
                await self._handle_binary(packet)
                continue
            packet = str(packet)
            if packet == "2":
                await self._send_text("3")
                continue
            if packet == "40" or packet.startswith("40{"):
                break
            if packet == "41":
                raise ConnectionError("Quotex rejected/disconnected the Socket.IO namespace")
            if packet.startswith("42"):
                await self._handle_text(packet)
        else:
            raise TimeoutError(
                "Timed out waiting for Quotex Socket.IO namespace acknowledgement."
            )

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
        await self.send_event(
            "history/load",
            {
                "asset": ASSET,
                "index": 0,
                "time": int(time.time()),
                "offset": max(HISTORY_CANDLES, 300),
                "period": TIMEFRAME,
            },
        )

    async def _dispatch(self, event: str, payload: object):
        if event == "s_authorization":
            self.authenticated = True
            await self.subscribe()
        await self.on_event(event, payload)

    async def _handle_text(self, raw: str):
        if raw == "2":
            if self.ws:
                await self._send_text("3")
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
        event = self._pending_binary_event
        self._pending_binary_event = None
        data = raw[1:] if raw.startswith(bytes([4])) else raw
        try:
            payload = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            # Historical candle replies can use fixed-width little-endian
            # records: int32 timestamp + four float64 OHLC values (36 bytes).
            if event in {"history/load", "history/list/v2"} and len(data) >= 36:
                import struct
                usable = len(data) - (len(data) % 36)
                candles = []
                for offset in range(0, usable, 36):
                    try:
                        ts, op, hi, lo, cl = struct.unpack("<idddd", data[offset:offset + 36])
                    except struct.error:
                        break
                    candles.append({"time": int(ts), "open": op, "high": hi, "low": lo, "close": cl})
                if candles:
                    await self._dispatch(event, {"candles": candles})
            return

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

                while True:
                    raw = await self._recv_text()
                    self.last_message_at = time.monotonic()
                    if raw:
                        await self._handle_text(raw)

            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.connected = False
                self.authenticated = False
                if self.ws is not None:
                    try:
                        await self.ws.close()
                    except Exception:
                        pass
                log.warning("Live Quotex connection lost: %s", exc)
                if self._session is not None:
                    try:
                        await self._session.close()
                    except Exception:
                        pass
                    self._session = None
                self.ws = None
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30)
