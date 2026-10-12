"""Probe Quotex-owned/community-listed WebSocket host candidates; no credentials or trades."""
from __future__ import annotations

import asyncio
import json
import os
import ssl
import urllib.parse
import urllib.request

import websockets
from websockets.exceptions import WebSocketException

def socket_path(eio: int) -> str:
    return f"/socket.io/?EIO={eio}&transport=websocket"
HOSTS = ("ws2.quotex.com", "ws.quotex.com", "ws2.qxbroker.com", "ws.qxbroker.com", "qxbroker.com", "quotex.com", "qxbroker.io", "quotex.io")
ORIGINS = ("https://qxbroker.com", "https://quotex.com")
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"


def resolve_via_doh(host: str) -> list[str]:
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
            print(f"DoH lookup via {resolver.split('/')[2]}: {type(exc).__name__}", flush=True)
    return answers


async def inspect_socket(ws) -> bool:
    first = await asyncio.wait_for(ws.recv(), timeout=8)
    if isinstance(first, bytes):
        first = first.decode("utf-8", "replace")
    if not str(first).startswith("0"):
        print(f"Unexpected Engine.IO response: {first!r}", flush=True)
        return False
    try:
        details = json.loads(str(first)[1:])
        print(f"Engine.IO handshake: OK (sid present: {bool(details.get('sid'))})", flush=True)
    except (ValueError, AttributeError):
        print("Engine.IO handshake: OK", flush=True)
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
            print("SUCCESS: Quotex Socket.IO namespace acknowledgement received.", flush=True)
            print("Only transport/namespace checked; authentication and ticks not tested.", flush=True)
            return True
        elif message == "41":
            print("Transport connected; Socket.IO namespace rejected.", flush=True)
            return False
    print("Transport connected, but no namespace acknowledgement.", flush=True)
    return False


async def probe(host: str, origin: str, eio: int) -> bool:
    url = "wss://" + host + socket_path(eio)
    print(f"Trying host={host}, EIO={eio}, origin={origin}", flush=True)
    try:
        async with websockets.connect(
            url,
            additional_headers={"Origin": origin, "User-Agent": USER_AGENT, "Cache-Control": "no-cache", "Pragma": "no-cache"},
            ssl=ssl.create_default_context(),
            ping_interval=None,
            open_timeout=7,
            close_timeout=2,
            max_size=1024 * 1024,
        ) as ws:
            if await inspect_socket(ws):
                return True
    except (OSError, TimeoutError, WebSocketException, ssl.SSLError) as exc:
        print(f"Normal connection failed: {type(exc).__name__}: {exc}", flush=True)

    ips = await asyncio.to_thread(resolve_via_doh, host)
    for ip in ips:
        print(f"Retrying {host} via DoH IP {ip}", flush=True)
        try:
            async with websockets.connect(
                url, host=ip, port=443,
                additional_headers={"Origin": origin, "User-Agent": USER_AGENT, "Cache-Control": "no-cache", "Pragma": "no-cache"},
                ssl=ssl.create_default_context(), ping_interval=None,
                open_timeout=7, close_timeout=2, max_size=1024 * 1024,
            ) as ws:
                if await inspect_socket(ws):
                    return True
        except (OSError, TimeoutError, WebSocketException, ssl.SSLError) as exc:
            print(f"DoH-IP connection failed: {type(exc).__name__}: {exc}", flush=True)
    if not ips:
        print(f"No public A record for {host}.", flush=True)
    return False


async def main() -> None:
    print("Quotex endpoint probe: no SSID, no login, no trade requests.", flush=True)
    custom_host = os.getenv("AADI_WS_HOST", "").strip()
    custom_origin = os.getenv("AADI_WS_ORIGIN", "").strip()
    hosts = ([custom_host] if custom_host else []) + list(HOSTS)
    origins = ([custom_origin] if custom_origin else []) + list(ORIGINS)
    seen: set[tuple[str, str]] = set()
    for host in hosts:
        for origin in origins:
            for eio in (3, 4):
                key = (host, origin, eio)
                if key in seen:
                    continue
                seen.add(key)
                if await probe(host, origin, eio):
                    return
    raise SystemExit("No tested Quotex host completed the Socket.IO handshake.")


if __name__ == "__main__":
    asyncio.run(main())
