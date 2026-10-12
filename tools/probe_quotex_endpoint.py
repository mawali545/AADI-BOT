"""Probe Quotex Socket.IO transport without credentials or trades."""
from __future__ import annotations

import asyncio
import json
import os
import ssl
import urllib.parse
import urllib.request

import websockets
from websockets.exceptions import WebSocketException

PATH = "/socket.io/?EIO=3&transport=websocket"
HOSTS = ("ws2.qxbroker.com", "ws.qxbroker.com")
ORIGIN = os.getenv("AADI_WS_ORIGIN", "https://qxbroker.com")


def resolve_via_doh(host: str) -> list[str]:
    """Use public DNS-over-HTTPS if the device/runner's normal DNS fails."""
    answers: list[str] = []
    for resolver in ("https://cloudflare-dns.com/dns-query", "https://dns.google/resolve"):
        try:
            url = resolver + "?" + urllib.parse.urlencode({"name": host, "type": "A"})
            req = urllib.request.Request(url, headers={"accept": "application/dns-json", "user-agent": "AADI-Bot-probe"})
            with urllib.request.urlopen(req, timeout=5) as response:
                payload = json.loads(response.read().decode("utf-8"))
            for item in payload.get("Answer", []):
                ip = item.get("data", "")
                if item.get("type") == 1 and ip and ip not in answers:
                    answers.append(ip)
            if answers:
                break
        except Exception as exc:
            print(f"DNS-over-HTTPS lookup via {resolver.split('/')[2]} failed: {type(exc).__name__}", flush=True)
    return answers


async def probe(host: str) -> bool:
    url = "wss://" + host + PATH
    print(f"Trying official-host candidate: {host}", flush=True)
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
            return await inspect_socket(ws)
    except (OSError, TimeoutError, WebSocketException, ssl.SSLError) as exc:
        print(f"Normal DNS/connection failed: {type(exc).__name__}: {exc}", flush=True)

    ips = await asyncio.to_thread(resolve_via_doh, host)
    if not ips:
        print(f"No A records returned by public DNS-over-HTTPS for {host}.", flush=True)
        return False
    for ip in ips:
        print(f"Retrying {host} via DNS-over-HTTPS IP {ip} (TLS hostname remains {host})", flush=True)
        try:
            async with websockets.connect(
                url,
                host=ip,
                port=443,
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
                if await inspect_socket(ws):
                    return True
        except (OSError, TimeoutError, WebSocketException, ssl.SSLError) as exc:
            print(f"IP connection failed: {type(exc).__name__}: {exc}", flush=True)
    return False


async def inspect_socket(ws) -> bool:
    first = await asyncio.wait_for(ws.recv(), timeout=8)
    if isinstance(first, bytes):
        first = first.decode("utf-8", "replace")
    if not str(first).startswith("0"):
        print(f"TCP/TLS/WebSocket opened but unexpected Engine.IO response: {first!r}", flush=True)
        return False
    try:
        details = json.loads(str(first)[1:])
        print(f"Engine.IO handshake: OK (sid present: {bool(details.get('sid'))})", flush=True)
    except (ValueError, AttributeError):
        print("Engine.IO handshake: OK (handshake body not JSON)", flush=True)
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
            print("Transport connection verified; authentication and market ticks are NOT tested.", flush=True)
            return True
        elif message == "41":
            print("WebSocket transport opened, but Socket.IO namespace was rejected.", flush=True)
            return False
    print("WebSocket transport opened, but no namespace acknowledgement arrived.", flush=True)
    return False


async def main() -> None:
    print("Quotex endpoint probe (no SSID, no login, no trades)", flush=True)
    for host in (os.getenv("AADI_WS_HOST", "").strip(), *HOSTS):
        if host and await probe(host):
            return
    raise SystemExit("No candidate completed the Socket.IO handshake.")


if __name__ == "__main__":
    asyncio.run(main())
