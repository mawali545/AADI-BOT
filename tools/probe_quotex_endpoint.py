"""Network-only Quotex Socket.IO endpoint probe; never sends account credentials."""
from __future__ import annotations

import asyncio
import json
import os
import ssl

import websockets
from websockets.exceptions import WebSocketException

DEFAULT_PATH = "/socket.io/?EIO=3&transport=websocket"
DEFAULT_URL = "wss://ws2.qxbroker.com" + DEFAULT_PATH
ORIGIN = os.getenv("AADI_WS_ORIGIN", "https://qxbroker.com")


def candidate_urls() -> list[str]:
    override = os.getenv("AADI_WSS_URL")
    if override:
        return [override]
    # Both hosts appear in community-maintained Quotex client implementations.
    return [
        "wss://ws2.qxbroker.com" + DEFAULT_PATH,
        "wss://ws.qxbroker.com" + DEFAULT_PATH,
    ]


async def probe(url: str) -> bool:
    print(f"Trying: {url}", flush=True)
    try:
        async with websockets.connect(
            url,
            additional_headers={
                "Origin": ORIGIN,
                "User-Agent": "Mozilla/5.0 AADI-Bot-Endpoint-Probe",
                "Cache-Control": "no-cache",
                "Pragma": "no-cache",
            },
            ssl=ssl.create_default_context(),
            ping_interval=None,
            open_timeout=8,
            close_timeout=2,
            max_size=1024 * 1024,
        ) as ws:
            first = await asyncio.wait_for(ws.recv(), timeout=8)
            if isinstance(first, bytes):
                first = first.decode("utf-8", "replace")
            if not str(first).startswith("0"):
                print(f"Connected, but unexpected Engine.IO response: {first!r}", flush=True)
                return False
            try:
                details = json.loads(str(first)[1:])
                print(f"Engine.IO handshake: OK (sid present: {bool(details.get('sid'))})", flush=True)
            except (ValueError, AttributeError):
                print("Engine.IO handshake: OK (handshake body was not JSON)", flush=True)

            await ws.send("40")
            deadline = asyncio.get_running_loop().time() + 8
            while asyncio.get_running_loop().time() < deadline:
                try:
                    message = await asyncio.wait_for(ws.recv(), timeout=4)
                except TimeoutError:
                    continue
                if isinstance(message, bytes):
                    continue
                message = str(message)
                if message == "2":
                    await ws.send("3")
                elif message == "40" or message.startswith("40{"):
                    print("Socket.IO namespace acknowledgement: OK", flush=True)
                    print("Endpoint reachable. Authentication and market ticks are NOT tested.", flush=True)
                    return True
                elif message == "41":
                    print("Transport connected, but server rejected Socket.IO namespace.", flush=True)
                    return False
            print("Transport connected, but no namespace acknowledgement arrived.", flush=True)
            return False
    except (OSError, TimeoutError, WebSocketException, ssl.SSLError) as exc:
        print(f"Connection failed: {type(exc).__name__}: {exc}", flush=True)
        return False


async def main() -> None:
    print("Quotex endpoint probe (no SSID, no account authorization, no trades)", flush=True)
    for url in candidate_urls():
        if await probe(url):
            return
    raise SystemExit("No tested Quotex endpoint completed the Socket.IO handshake.")


if __name__ == "__main__":
    asyncio.run(main())
