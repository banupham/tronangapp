import asyncio
import base64
import json
import shlex
from pathlib import Path

import websockets

HOST = "0.0.0.0"
PORT = 8765
clients = set()


async def handler(ws):
    clients.add(ws)
    print(f"[+] phone connected ({len(clients)} client)")
    try:
        async for message in ws:
            print("PHONE:", message)
    finally:
        clients.discard(ws)
        print(f"[-] phone disconnected ({len(clients)} client)")


async def broadcast(message: str):
    if not clients:
        print("[!] no phone connected")
        return
    dead = []
    for ws in list(clients):
        try:
            await ws.send(message)
        except Exception:
            dead.append(ws)
    for ws in dead:
        clients.discard(ws)


def image_put_command(parts):
    if len(parts) not in (7, 8):
        raise ValueError(
            "usage: /img NAME FILE LEFT TOP RIGHT BOTTOM [THRESHOLD]"
        )

    _, name, file_name, left, top, right, bottom, *rest = parts
    threshold = float(rest[0]) if rest else 0.90
    raw = Path(file_name).read_bytes()
    encoded = base64.b64encode(raw).decode("ascii")
    return json.dumps(
        {
            "cmd": "image_put",
            "name": name,
            "png_base64": encoded,
            "roi": {
                "left": int(left),
                "top": int(top),
                "right": int(right),
                "bottom": int(bottom),
            },
            "threshold": threshold,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


def split_console_line(line: str):
    # posix=False preserves Windows backslashes such as C:\\temp\\claim.png.
    parts = shlex.split(line, posix=False)
    return [part.strip('"').strip("'") for part in parts]


async def console():
    print("Commands:")
    print("  raw workflow, e.g. WAIT:OK;CLICK:OK;BACK;SLEEP:0.2;HOME")
    print("  /img NAME FILE LEFT TOP RIGHT BOTTOM [THRESHOLD]")
    print("  /find NAME        -> WAIT_IMG:NAME")
    print("  /clickimg NAME    -> CLICK_IMG:NAME")
    print("  /images           -> list image targets")
    print("  /capture          -> capture status")
    print("  /stop             -> stop workflow")
    print()

    while True:
        line = await asyncio.to_thread(input, "> ")
        line = line.strip()
        if not line:
            continue

        try:
            if line.startswith("/img "):
                message = image_put_command(split_console_line(line))
            elif line.startswith("/find "):
                name = line[len("/find "):].strip()
                message = f"WAIT_IMG:{name}"
            elif line.startswith("/clickimg "):
                name = line[len("/clickimg "):].strip()
                message = f"CLICK_IMG:{name}"
            elif line == "/images":
                message = json.dumps({"cmd": "image_list"})
            elif line == "/capture":
                message = json.dumps({"cmd": "capture_status"})
            elif line == "/stop":
                message = "STOP"
            else:
                message = line

            await broadcast(message)
        except Exception as error:
            print("ERROR:", error)


async def main():
    async with websockets.serve(handler, HOST, PORT, max_size=32 * 1024 * 1024):
        print(f"WebSocket server: ws://{HOST}:{PORT}")
        await console()


if __name__ == "__main__":
    asyncio.run(main())
