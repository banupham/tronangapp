import asyncio
import base64
import itertools
import json
import shlex
import time
from pathlib import Path

import websockets

HOST = "0.0.0.0"
PORT = 8765
NETWORK_WARN_MS = 120.0
PHONE_QUEUE_WARN_MS = 30.0
clients = set()
command_ids = itertools.count(1)
pending = {}


def now_ms(start_perf):
    return (time.perf_counter() - start_perf) * 1000.0


def new_command_id():
    return f"pc-{next(command_ids)}"


def register_pending(request_id, description):
    pending[request_id] = {
        "sent_perf": time.perf_counter(),
        "description": description,
        "received_phone_ms": None,
        "started_phone_ms": None,
    }
    print(f"[{request_id}] SEND       {description}")


def print_ack(obj):
    request_id = str(obj.get("id", "?"))
    state = str(obj.get("state", "?"))
    phone_ms = obj.get("phone_ms")
    item = pending.get(request_id)

    if item is None:
        print(f"[{request_id}] {state.upper():<10} phone_ms={phone_ms}")
        return

    elapsed = now_ms(item["sent_perf"])
    extra = ""

    if state == "received":
        item["received_phone_ms"] = phone_ms
        extra = "  (PC send -> phone ACK round-trip)"
        if elapsed >= NETWORK_WARN_MS:
            extra += "  [NETWORK/SOCKET SPIKE]"

    elif state == "started":
        item["started_phone_ms"] = phone_ms
        received_phone_ms = item.get("received_phone_ms")
        if isinstance(phone_ms, (int, float)) and isinstance(received_phone_ms, (int, float)):
            queue_ms = phone_ms - received_phone_ms
            extra = f"  phone_queue={queue_ms:.1f} ms"
            if queue_ms >= PHONE_QUEUE_WARN_MS:
                extra += "  [MAIN/TREE QUEUE]"

        tree_ms = obj.get("last_tree_scan_ms")
        tree_age = obj.get("tree_scan_age_ms")
        if isinstance(tree_ms, (int, float)):
            extra += f"  last_tree_scan={tree_ms:.1f} ms"
        if isinstance(tree_age, (int, float)):
            extra += f"  tree_age={tree_age:.1f} ms"

    elif state in {"completed", "failed", "stopped", "cancelled"}:
        started_phone_ms = item.get("started_phone_ms")
        if isinstance(phone_ms, (int, float)) and isinstance(started_phone_ms, (int, float)):
            extra = f"  phone_execute={phone_ms - started_phone_ms:.1f} ms"
        error = obj.get("error")
        if error:
            extra += f"  error={error}"

    print(f"[{request_id}] {state.upper():<10} +{elapsed:8.1f} ms{extra}")

    if state in {"completed", "failed", "stopped", "cancelled"}:
        pending.pop(request_id, None)


def handle_phone_message(message):
    try:
        obj = json.loads(message)
    except Exception:
        print("PHONE:", message)
        return

    if obj.get("type") == "ack":
        print_ack(obj)
        return

    if obj.get("type") == "workflow":
        request_id = obj.get("request_id")
        print(
            "PHONE workflow:",
            f"id={request_id}",
            f"state={obj.get('state')}",
            f"step={obj.get('step')}/{obj.get('total')}",
            f"command={obj.get('command')}",
            f"target={obj.get('target')}",
            f"error={obj.get('error')}",
        )
        return

    print("PHONE:", message)


async def handler(ws):
    clients.add(ws)
    print(f"[+] phone connected ({len(clients)} client)")
    try:
        async for message in ws:
            handle_phone_message(message)
    finally:
        clients.discard(ws)
        print(f"[-] phone disconnected ({len(clients)} client)")


async def broadcast(message: str):
    if not clients:
        print("[!] no phone connected")
        return 0

    sent = 0
    dead = []
    for ws in list(clients):
        try:
            await ws.send(message)
            sent += 1
        except Exception:
            dead.append(ws)

    for ws in dead:
        clients.discard(ws)
    return sent


async def send_workflow(script: str):
    request_id = new_command_id()
    register_pending(request_id, script)
    message = json.dumps(
        {
            "cmd": "run",
            "id": request_id,
            "script": script,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    if await broadcast(message) == 0:
        pending.pop(request_id, None)


async def send_stop():
    request_id = new_command_id()
    register_pending(request_id, "STOP")
    message = json.dumps(
        {
            "cmd": "stop",
            "id": request_id,
        },
        separators=(",", ":"),
    )
    if await broadcast(message) == 0:
        pending.pop(request_id, None)


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


def tap_workflow(parts):
    if len(parts) != 3:
        raise ValueError("usage: /tap X Y")
    _, raw_x, raw_y = parts
    x = int(raw_x)
    y = int(raw_y)
    if x < 0 or y < 0:
        raise ValueError("X and Y must be non-negative")
    return f"TAP:{x},{y}"


def split_console_line(line: str):
    # posix=False preserves Windows backslashes such as C:\\temp\\claim.png.
    parts = shlex.split(line, posix=False)
    return [part.strip('"').strip("'") for part in parts]


async def console():
    print("Commands:")
    print("  raw workflow, e.g. UP or WAIT:OK;CLICK:OK;TAP:504,1513;BACK;SLEEP:0.2;HOME")
    print("  /tap X Y          -> TAP:X,Y")
    print("  /img NAME FILE LEFT TOP RIGHT BOTTOM [THRESHOLD]")
    print("  /find NAME        -> WAIT_IMG:NAME")
    print("  /clickimg NAME    -> CLICK_IMG:NAME")
    print("  /images           -> list image targets")
    print("  /capture          -> capture status")
    print("  /ping             -> socket ping/pong test")
    print("  /stop             -> stop workflow")
    print()
    print("Latency ACKs:")
    print("  RECEIVED  = phone WebSocket callback received the command")
    print("  STARTED   = Android main thread started processing the workflow")
    print("  COMPLETED = workflow finished")
    print("  phone_queue shows RECEIVED -> STARTED time inside the phone")
    print("  last_tree_scan shows the duration of the most recent full-tree rebuild")
    print()

    while True:
        line = await asyncio.to_thread(input, "> ")
        line = line.strip()
        if not line:
            continue

        try:
            if line.startswith("/img "):
                await broadcast(image_put_command(split_console_line(line)))
            elif line.startswith("/tap "):
                await send_workflow(tap_workflow(split_console_line(line)))
            elif line.startswith("/find "):
                name = line[len("/find "):].strip()
                await send_workflow(f"WAIT_IMG:{name}")
            elif line.startswith("/clickimg "):
                name = line[len("/clickimg "):].strip()
                await send_workflow(f"CLICK_IMG:{name}")
            elif line == "/images":
                await broadcast(json.dumps({"cmd": "image_list"}))
            elif line == "/capture":
                await broadcast(json.dumps({"cmd": "capture_status"}))
            elif line == "/ping":
                await broadcast("PING")
            elif line == "/stop":
                await send_stop()
            else:
                await send_workflow(line)
        except Exception as error:
            print("ERROR:", error)


async def main():
    async with websockets.serve(handler, HOST, PORT, max_size=32 * 1024 * 1024):
        print(f"WebSocket server: ws://{HOST}:{PORT}")
        await console()


if __name__ == "__main__":
    asyncio.run(main())
