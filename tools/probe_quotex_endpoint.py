"""Network-only Quotex Socket.IO endpoint probe; never sends account credentials."""
from __future__ import annotations

import asyncio
import json
import os
import ssl

import websockets

URL = os.getenv(
    "AADI_WSS_URL",
    "wss://ws2.qxbroker.com/socket.io/?EIO=3&transport=websocket",
)
ORIGIN = os.getenv("AADI_WS_ORIGIN", "https://qxbroker.com")


async def main() -> None:
    print("Quotex endpoint probe (no SSID, no account authorization, no trades)")
    print(f"Endpoint: {URL}")
    async with websockets.connect(
        URL,
        additional_headers={
            "Origin": ORIGIN,
            "User-Agent": "Mozilla/5.0 AADI-Bot-Endpoint-Probe",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
        },
        ssl=ssl.create_default_context(),
        ping_interval=None,
        open_timeout=10,
        close_timeout=3,
        max_size=1024 * 1024,
    ) as ws:
        first = await asyncio.wait_for(ws.recv(), timeout=10)
        if isinstance(first, bytes):
            first = first.decode("utf-8", "replace")
        if not str(first).startswith("0"):
            raise RuntimeError(f"Unexpected Engine.IO handshake: {first!r}")
        try:
            details = json.loads(str(first)[1:])
            print(f"Engine.IO handshake: OK (sid present: {bool(details.get('sid'))})")
        except (ValueError, AttributeError):
            print("Engine.IO handshake: OK (handshake body was not JSON)")
        await ws.send("40")
        deadline = asyncio.get_running_loop().time() + 10
        while asyncio.get_running_loop().time() < deadline:
            message = await asyncio.wait_for(ws.recv(), timeout=10)
            if isinstance(message, bytes):
                continue
            message = str(message)
            if message == "2":
                await ws.send("3")
            elif message == "40" or message.startswith("40{"):
                print("Socket.IO namespace acknowledgement: OK")
                print("Endpoint reachable. Authentication and market ticks are NOT tested by this probe.")
                return
            elif message == "41":
                raise RuntimeError("Server rejected the Socket.IO namespace")
        raise TimeoutError("No Socket.IO namespace acknowledgement within 10 seconds")


if __name__ == "__main__":
    asyncio.run(main())
