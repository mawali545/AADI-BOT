from __future__ import annotations

import asyncio
import json
import logging
import ssl
import time
from collections.abc import Awaitable, Callable
from typing import Any

import websockets

from config import ASSET, HISTORY_CANDLES, SSID, TIMEFRAME, WS_ORIGIN, WSS_URL

log = logging.getLogger("aadi.quotex")


def _json_from_bytes(raw: bytes) -> Any | None:
    """Decode JSON attachments without treating arbitrary binary as text data."""
    candidates = [raw]
    if raw.startswith(b"\x04"):
        candidates.insert(0, raw[1:])
    for candidate in candidates:
        try:
            return json.loads(candidate.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            continue
    return None


def _replace_binary_placeholders(value: Any, attachments: list[Any]) -> Any:
    """Replace Socket.IO binary placeholder objects with decoded attachments."""
    if isinstance(value, dict):
        if value.get("_placeholder") is True:
            index = value.get("num")
            if isinstance(index, int) and 0 <= index < len(attachments):
                return attachments[index]
            return value
        return {key: _replace_binary_placeholders(item, attachments) for key, item in value.items()}
    if isinstance(value, list):
        return [_replace_binary_placeholders(item, attachments) for item in value]
    return value


class QuotexSocket:
    """Real Quotex Socket.IO/Engine.IO market-data connection only."""

    def __init__(self, on_event: Callable[[str, object], Awaitable[None]]):
        self.on_event = on_event
        self.ws = None
        self.connected = False
        self.authenticated = False
        self.last_message_at = 0.0
        self._pending_binary_event: str | None = None
        self._pending_binary_payload: Any = None
        self._pending_binary_count = 0
        self._pending_binary_attachments: list[Any] = []

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

        headers = {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/142.0.0.0 Safari/537.36"
            ),
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "Accept-Language": "en-US,en;q=0.9",
        }
        ssl_context = ssl.create_default_context()
        self.ws = await websockets.connect(
            WSS_URL,
            additional_headers=headers,
            origin=WS_ORIGIN,
            ping_interval=None,
            max_size=16 * 1024 * 1024,
            ssl=ssl_context,
        )

        handshake = await self.ws.recv()
        self.last_message_at = time.monotonic()
        if isinstance(handshake, bytes):
            handshake = handshake.decode("utf-8", "replace")
        if not str(handshake).startswith("0"):
            raise RuntimeError(f"Unexpected Engine.IO handshake: {handshake!r}")

        await self.ws.send("40")
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            try:
                packet = await asyncio.wait_for(
                    self.ws.recv(),
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
                await self.ws.send("3")
                continue
            if packet == "40" or packet.startswith("40{"):
                break
            if packet == "41":
                raise ConnectionError("Quotex rejected/disconnected the Socket.IO namespace")
            if packet.startswith(("42", "45")):
                await self._handle_text(packet)
        else:
            raise TimeoutError("Timed out waiting for Quotex Socket.IO namespace acknowledgement.")

        await self.send_event(
            "authorization",
            {"session": SSID, "isDemo": 0, "tournamentId": 0},
        )

    async def subscribe(self):
        await self.send_event("tick")
        await self.send_event("indicator/list")
        await self.send_event("drawing/load")
        await self.send_event("pending/list")
        await self.send_event("instruments/update", {"asset": ASSET, "period": TIMEFRAME})
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

    def _clear_binary_state(self):
        self._pending_binary_event = None
        self._pending_binary_payload = None
        self._pending_binary_count = 0
        self._pending_binary_attachments = []

    async def _handle_text(self, raw: str):
        if raw == "2":
            if self.ws:
                await self.ws.send("3")
            return
        if raw in {"3", "40"}:
            return
        if raw == "41":
            raise ConnectionError("Quotex Socket.IO server disconnected")

        # Socket.IO binary-event header: 45<count>-<JSON packet>.
        if raw.startswith("45") and "-" in raw:
            try:
                prefix, encoded = raw.split("-", 1)
                count = int(prefix[2:])
                data = json.loads(encoded)
                if count < 1 or not isinstance(data, list) or not data:
                    raise ValueError("unexpected binary event header shape")
                self._pending_binary_event = str(data[0])
                self._pending_binary_payload = data[1] if len(data) > 1 else {}
                self._pending_binary_count = count
                self._pending_binary_attachments = []
                log.info("Socket.IO binary event header received: event=%s attachments=%d",
                          self._pending_binary_event, count)
            except (TypeError, ValueError):
                self._clear_binary_state()
                log.warning("Invalid Socket.IO binary event header")
            return

        if raw.startswith("42"):
            try:
                data = json.loads(raw[2:])
            except (TypeError, ValueError):
                log.debug("Ignored malformed Socket.IO event packet")
                return
            if isinstance(data, list) and data:
                await self._dispatch(str(data[0]), data[1] if len(data) > 1 else {})

    async def _handle_binary(self, raw: bytes):
        # If a binary event header arrived first, collect its declared attachments.
        if self._pending_binary_event is not None:
            decoded = _json_from_bytes(raw)
            self._pending_binary_attachments.append(decoded if decoded is not None else raw)
            if len(self._pending_binary_attachments) < self._pending_binary_count:
                return
            event = self._pending_binary_event
            payload = _replace_binary_placeholders(
                self._pending_binary_payload, self._pending_binary_attachments
            )
            self._clear_binary_state()
            if any(isinstance(item, bytes) for item in self._pending_binary_attachments):
                log.warning(
                    "Received non-JSON binary attachment for event=%s (%d bytes); "
                    "kept raw bytes instead of fabricating decoded market data",
                    event, len(raw),
                )
            await self._dispatch(event, payload)
            return

        # Some server events arrive as raw JSON in a binary WebSocket frame.
        payload = _json_from_bytes(raw)
        if payload is None:
            log.warning("Unclassified binary frame: %d bytes; prefix=%s",
                      len(raw), raw[:8].hex())
            return
        if isinstance(payload, dict):
            if any(key in payload for key in ("candles", "history", "data")):
                await self._dispatch("history/list/v2", payload)
        elif isinstance(payload, list):
            await self._dispatch("quotes/stream", payload)

    async def run(self):
        backoff = 2
        while True:
            try:
                self.connected = False
                self.authenticated = False
                self._clear_binary_state()
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
                self._clear_binary_state()
                log.warning("Live Quotex connection lost: %s", exc)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30)
