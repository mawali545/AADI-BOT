from __future__ import annotations

import asyncio
import json
import logging
import re
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
        self._pending_binary_header: list | None = None
        self._pending_binary_count = 0
        self._pending_binary_attachments: list[bytes] = []

    async def _send_text(self, text: str):
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        await self.ws.send(text, flags=CurlWsFlag.TEXT)

    async def _recv_text(self) -> str:
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        payload, flags = await self.ws.recv()
        if flags & CurlWsFlag.BINARY:
            await self._handle_binary(bytes(payload))
            return ""
        return payload.decode("utf-8", "replace") if isinstance(payload, bytes) else str(payload)

    async def send_event(self, name: str, payload: object | None = None):
        if not self.ws:
            raise RuntimeError("WebSocket is not connected")
        packet = [name] if payload is None else [name, payload]
        await self._send_text("42" + json.dumps(packet, separators=(",", ":")))

    async def connect(self):
        if AsyncSession is None or CurlWsFlag is None:
            raise RuntimeError("Direct Quotex WebSocket mode needs curl_cffi. Install it from requirements.txt; Android/Termux compatibility still needs on-device verification.")
        if not SSID:
            raise RuntimeError("AADI_QUOTEX_SSID is missing; provide a live Quotex session.")
        if "EIO=3" not in WSS_URL or "ws2." not in WSS_URL:
            raise RuntimeError("Configured endpoint is not the expected live Quotex EIO=3 ws2 endpoint.")

        headers = {
            "Origin": WS_ORIGIN,
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "Accept-Language": "en-US,en;q=0.9",
        }
        self._session = AsyncSession(impersonate="chrome", timeout=15)
        self.ws = await self._session.ws_connect(WSS_URL, headers=headers)
        handshake = await self._recv_text()
        self.last_message_at = time.monotonic()
        if not str(handshake).startswith("0"):
            raise RuntimeError(f"Unexpected Engine.IO handshake: {handshake!r}")

        await self._send_text("40")
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            try:
                packet = await asyncio.wait_for(
                    self._recv_text(),
                    timeout=max(0.1, deadline - time.monotonic()),
                )
            except asyncio.TimeoutError as exc:
                raise TimeoutError("Timed out waiting for Quotex Socket.IO namespace acknowledgement.") from exc
            self.last_message_at = time.monotonic()
            if packet == "2":
                await self._send_text("3")
                continue
            if packet == "40" or packet.startswith("40{"):
                break
            if packet == "41":
                raise ConnectionError("Quotex rejected/disconnected the Socket.IO namespace")
            if packet.startswith("42") or packet.startswith("45"):
                await self._handle_text(packet)
        else:
            raise TimeoutError("Timed out waiting for Quotex Socket.IO namespace acknowledgement.")

        await self.send_event("authorization", {"session": SSID, "isDemo": 0, "tournamentId": 0})

    async def subscribe(self):
        await self.send_event("tick")
        await self.send_event("indicator/list")
        await self.send_event("drawing/load")
        await self.send_event("pending/list")
        await self.send_event("instruments/update", {"asset": ASSET, "period": TIMEFRAME})
        await self.send_event("depth/follow", ASSET)
        await self.send_event("chart_notification/get")
        await self.send_event("history/load", {
            "asset": ASSET, "index": 0, "time": int(time.time()),
            "offset": max(HISTORY_CANDLES, 300), "period": TIMEFRAME,
        })

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

        # Socket.IO binary event packet: 45<n>- followed by JSON with
        # placeholders, then exactly n binary attachment frames.
        match = re.match(r"^45(\d+)-", raw)
        if raw.startswith("45") and not match:
            self._reset_binary_state()
            log.warning("Malformed Socket.IO binary event header")
            return
        if match:
            try:
                header = json.loads(raw[match.end():])
                if not isinstance(header, list) or not header:
                    raise ValueError("binary event header is not a non-empty array")
                count = int(match.group(1))
                if count < 1 or count > 32:
                    raise ValueError("unsupported binary attachment count")
                self._pending_binary_header = header
                self._pending_binary_count = count
                self._pending_binary_attachments = []
            except (TypeError, ValueError):
                self._reset_binary_state()
                log.warning("Invalid Socket.IO binary event header")
            return

        if raw.startswith("42"):
            try:
                data = json.loads(raw[2:])
            except (TypeError, ValueError):
                return
            if isinstance(data, list) and data:
                await self._dispatch(str(data[0]), data[1] if len(data) > 1 else {})

    def _reset_binary_state(self):
        self._pending_binary_header = None
        self._pending_binary_count = 0
        self._pending_binary_attachments = []

    @staticmethod
    def _replace_placeholders(value: object, attachments: list[bytes]) -> object:
        if isinstance(value, dict):
            if value.get("_placeholder") is True:
                index = value.get("num")
                if not isinstance(index, int) or index < 0 or index >= len(attachments):
                    raise ValueError("invalid Socket.IO binary placeholder index")
                return attachments[index]
            return {key: QuotexSocket._replace_placeholders(item, attachments) for key, item in value.items()}
        if isinstance(value, list):
            return [QuotexSocket._replace_placeholders(item, attachments) for item in value]
        return value

    async def _handle_binary(self, raw: bytes):
        header = self._pending_binary_header
        if header is not None:
            self._pending_binary_attachments.append(raw)
            if len(self._pending_binary_attachments) < self._pending_binary_count:
                return
            attachments = self._pending_binary_attachments
            self._reset_binary_state()
            try:
                event = str(header[0])
                body = header[1] if len(header) > 1 else {}
                payload = self._replace_placeholders(body, attachments)
            except (TypeError, ValueError) as exc:
                log.warning("Could not reassemble Socket.IO binary event: %s", exc)
                return
            await self._dispatch_binary_payload(event, payload)
            return

        # Some server variants send standalone binary payloads without a
        # Socket.IO attachment header. Decode only recognized formats.
        data = raw[1:] if raw.startswith(bytes([4])) else raw
        try:
            payload = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return
        if isinstance(payload, dict) and ("candles" in payload or "history" in payload):
            await self._dispatch("history/list/v2", payload)
        elif isinstance(payload, list):
            await self._dispatch("quotes/stream", payload)

    async def _dispatch_binary_payload(self, event: str, payload: object):
        if isinstance(payload, bytes):
            data = payload[1:] if payload.startswith(bytes([4])) else payload
            try:
                decoded = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                decoded = None
            if decoded is not None:
                payload = decoded
            elif event in {"history/load", "history/list/v2"} and len(data) >= 36:
                # Candidate historical format: little-endian int32 timestamp
                # followed by four float64 OHLC values per 36-byte record.
                # It is used only for history events, never assumed to be ticks.
                import struct
                usable = len(data) - (len(data) % 36)
                candles = []
                for offset in range(0, usable, 36):
                    try:
                        ts, op, hi, lo, cl = struct.unpack("<idddd", data[offset:offset + 36])
                    except struct.error:
                        break
                    if not all(map(lambda x: x == x and abs(x) != float("inf"), (op, hi, lo, cl))):
                        continue
                    if not (lo <= min(op, cl) <= max(op, cl) <= hi):
                        continue
                    candles.append({"time": int(ts), "open": op, "high": hi, "low": lo, "close": cl})
                if candles:
                    payload = {"candles": candles}
                else:
                    log.info("Unrecognized binary history payload: %d bytes", len(data))
                    return
            else:
                log.info("Unrecognized binary event payload: event=%s bytes=%d", event, len(data))
                return
        await self._dispatch(event, payload)

    async def run(self):
        backoff = 2
        while True:
            try:
                self.connected = False
                self.authenticated = False
                self._reset_binary_state()
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
                self._reset_binary_state()
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
