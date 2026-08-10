import asyncio
import base64
import io
import itertools
import json
import queue
import threading
import time
import tkinter as tk
from concurrent.futures import ThreadPoolExecutor
from tkinter import messagebox, ttk

import websockets
from PIL import Image, ImageTk


MAX_EVENTS_PER_TICK = 100
NODE_ROWS_PER_TICK = 50
MAX_LOG_LINES = 5_000


class WebSocketBackend:
    def __init__(self, events):
        self.events = events
        self.loop = None
        self.thread = None
        self.stop_event = None
        self.clients = {}
        self.client_ids = itertools.count(1)

    def start(self, host, port):
        if self.thread and self.thread.is_alive():
            return
        self.thread = threading.Thread(
            target=self._thread_main,
            args=(host, port),
            name="tronangapp-ws",
            daemon=True,
        )
        self.thread.start()

    def _thread_main(self, host, port):
        try:
            asyncio.run(self._run(host, port))
        except Exception as error:
            self.events.put(("server_error", str(error)))

    async def _run(self, host, port):
        self.loop = asyncio.get_running_loop()
        self.stop_event = asyncio.Event()
        async with websockets.serve(
            self._handler,
            host,
            port,
            max_size=32 * 1024 * 1024,
        ):
            self.events.put(("server_started", host, port))
            await self.stop_event.wait()
        self.clients.clear()
        self.events.put(("server_stopped",))

    async def _handler(self, ws):
        client_id = f"phone-{next(self.client_ids)}"
        remote = ws.remote_address
        remote_label = f"{remote[0]}:{remote[1]}" if remote else client_id
        self.clients[client_id] = ws
        self.events.put(("client_connected", client_id, remote_label))
        try:
            async for message in ws:
                try:
                    payload = json.loads(message)
                except Exception:
                    payload = message
                self.events.put(("message", client_id, payload))
        finally:
            self.clients.pop(client_id, None)
            self.events.put(("client_disconnected", client_id))

    async def _broadcast(self, message, target_ids):
        if not self.clients:
            self.events.put(("send_error", "Chưa có điện thoại kết nối"))
            return 0
        targets = list(self.clients) if target_ids is None else list(target_ids)
        if not targets:
            self.events.put(("send_error", "Chưa chọn điện thoại"))
            return 0
        sent = 0
        for client_id in targets:
            ws = self.clients.get(client_id)
            if ws is None:
                continue
            try:
                await ws.send(message)
                sent += 1
            except Exception:
                self.clients.pop(client_id, None)
                self.events.put(("client_disconnected", client_id))
        return sent

    def send(self, message, target_ids=None):
        if not self.loop or not self.loop.is_running():
            self.events.put(("send_error", "WebSocket server chưa chạy"))
            return
        asyncio.run_coroutine_threadsafe(
            self._broadcast(message, tuple(target_ids) if target_ids is not None else None),
            self.loop,
        )

    def stop(self):
        if self.loop and self.stop_event:
            self.loop.call_soon_threadsafe(self.stop_event.set)


class TronangControlApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Trợ năng App - WebSocket Control")
        self.root.geometry("1180x760")
        self.root.minsize(900, 600)

        self.events = queue.Queue()
        self.backend = WebSocketBackend(self.events)
        self.command_ids = itertools.count(1)
        self.pending = {}
        self.device_vars = {}
        self.device_labels = {}
        self.device_widgets = {}
        self.log_buffer = []
        self.pending_node_rows = []
        self.node_insert_scheduled = False
        self.node_response_count = 0
        self.frame_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="screen-decode")
        self.frame_lock = threading.Lock()
        self.pending_frames = {}
        self.frame_decode_busy = set()
        self.screen_labels = {}
        self.screen_images = {}
        self.screen_source_images = {}
        self.screen_geometry = {}
        self.screen_render_job = None
        self.closing = False

        self.host_var = tk.StringVar(value="0.0.0.0")
        self.port_var = tk.StringVar(value="8770")
        self.server_status_var = tk.StringVar(value="Đã dừng")
        self.client_status_var = tk.StringVar(value="0 điện thoại")
        self.node_limit_var = tk.StringVar(value="200")
        self.node_offset_var = tk.StringVar(value="0")
        self.node_filter_var = tk.StringVar()
        self.node_summary_var = tk.StringVar(value="Chưa đọc nodes")
        self.device_summary_var = tk.StringVar(value="Chưa có thiết bị")
        self.swipe_start_x_var = tk.StringVar(value="540")
        self.swipe_start_y_var = tk.StringVar(value="1500")
        self.swipe_end_x_var = tk.StringVar(value="540")
        self.swipe_end_y_var = tk.StringVar(value="500")
        self.swipe_duration_var = tk.StringVar(value="350")
        self.stream_fps_var = tk.StringVar(value="4")
        self.stream_width_var = tk.StringVar(value="360")
        self.stream_quality_var = tk.StringVar(value="55")
        self.display_width_var = tk.StringVar(value="100")
        self.stream_status_var = tk.StringVar(value="Chế độ xem đang tắt")

        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.root.after(50, self._poll_events)
        self.root.after(100, self._flush_logs)
        self.root.after(100, self.start_server)

    def _build_ui(self):
        top = ttk.Frame(self.root, padding=8)
        top.pack(fill=tk.X)
        ttk.Label(top, text="Host").pack(side=tk.LEFT)
        ttk.Entry(top, textvariable=self.host_var, width=16).pack(side=tk.LEFT, padx=(5, 10))
        ttk.Label(top, text="Port").pack(side=tk.LEFT)
        ttk.Entry(top, textvariable=self.port_var, width=8).pack(side=tk.LEFT, padx=(5, 10))
        ttk.Button(top, text="Khởi động", command=self.start_server).pack(side=tk.LEFT)
        ttk.Button(top, text="Dừng", command=self.stop_server).pack(side=tk.LEFT, padx=6)
        ttk.Button(top, text="Thoát ứng dụng", command=self._on_close).pack(side=tk.LEFT)
        ttk.Separator(top, orient=tk.VERTICAL).pack(side=tk.LEFT, fill=tk.Y, padx=10)
        ttk.Label(top, textvariable=self.server_status_var).pack(side=tk.LEFT)
        ttk.Label(top, text=" • ").pack(side=tk.LEFT)
        ttk.Label(top, textvariable=self.client_status_var).pack(side=tk.LEFT)

        devices = ttk.LabelFrame(self.root, text="Điện thoại nhận lệnh", padding=6)
        devices.pack(fill=tk.X, padx=8, pady=(0, 6))
        controls = ttk.Frame(devices)
        controls.pack(side=tk.LEFT)
        ttk.Button(controls, text="Chọn tất cả", command=lambda: self.select_all_devices(True)).pack(side=tk.LEFT)
        ttk.Button(controls, text="Bỏ chọn tất cả", command=lambda: self.select_all_devices(False)).pack(side=tk.LEFT, padx=4)
        self.device_checks = ttk.Frame(devices)
        self.device_checks.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(10, 0))

        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=8, pady=(0, 8))

        control = ttk.Frame(self.notebook, padding=10)
        nodes = ttk.Frame(self.notebook, padding=8)
        logs = ttk.Frame(self.notebook, padding=8)
        screens = ttk.Frame(self.notebook, padding=8)
        self.notebook.add(control, text="Điều khiển")
        self.notebook.add(screens, text="Màn hình")
        self.notebook.add(nodes, text="Nodes")
        self.notebook.add(logs, text="Log / độ trễ")
        self._build_control_tab(control)
        self._build_screens_tab(screens)
        self._build_nodes_tab(nodes)
        self._build_log_tab(logs)

    def _build_control_tab(self, parent):
        ttk.Label(parent, textvariable=self.device_summary_var).pack(anchor=tk.W, pady=(0, 8))

        quick = ttk.LabelFrame(parent, text="Điều khiển nhanh", padding=8)
        quick.pack(fill=tk.X)
        for label, command in (
            ("↑ UP", "UP"),
            ("↓ DOWN", "DOWN"),
            ("← LEFT", "LEFT"),
            ("→ RIGHT", "RIGHT"),
            ("BACK", "BACK"),
            ("HOME", "HOME"),
            ("RECENTS", "RECENTS"),
        ):
            ttk.Button(
                quick,
                text=label,
                command=lambda value=command: self.send_workflow(value),
            ).pack(side=tk.LEFT, padx=3)

        custom_swipe = ttk.LabelFrame(parent, text="Vuốt từ điểm A đến B", padding=8)
        custom_swipe.pack(fill=tk.X, pady=(8, 0))
        fields = (
            ("A.x", self.swipe_start_x_var),
            ("A.y", self.swipe_start_y_var),
            ("B.x", self.swipe_end_x_var),
            ("B.y", self.swipe_end_y_var),
            ("Thời gian (ms)", self.swipe_duration_var),
        )
        for label, variable in fields:
            ttk.Label(custom_swipe, text=label).pack(side=tk.LEFT, padx=(4, 2))
            ttk.Entry(custom_swipe, textvariable=variable, width=7).pack(side=tk.LEFT)
        ttk.Button(custom_swipe, text="Vuốt ngay", command=self.send_custom_swipe).pack(
            side=tk.LEFT, padx=(10, 4)
        )
        ttk.Button(custom_swipe, text="Thêm vào workflow", command=self.append_custom_swipe).pack(
            side=tk.LEFT
        )

        actions = ttk.Frame(parent)
        actions.pack(fill=tk.X, pady=8)
        ttk.Button(actions, text="STOP workflow", command=self.send_stop).pack(side=tk.LEFT)
        ttk.Button(
            actions,
            text="PING",
            command=lambda: self.backend.send("PING", self._selected_clients()),
        ).pack(side=tk.LEFT, padx=5)
        ttk.Button(actions, text="Capture status", command=self.request_capture).pack(side=tk.LEFT, padx=5)
        ttk.Button(actions, text="Image targets", command=self.request_images).pack(side=tk.LEFT, padx=5)

        workflow_frame = ttk.LabelFrame(parent, text="Workflow", padding=8)
        workflow_frame.pack(fill=tk.BOTH, expand=True)
        self.workflow_text = tk.Text(workflow_frame, height=12, wrap=tk.WORD, undo=True)
        self.workflow_text.pack(fill=tk.BOTH, expand=True)
        self.workflow_text.insert("1.0", "WAIT:Trợ năng App;SLEEP:0")

        workflow_buttons = ttk.Frame(workflow_frame)
        workflow_buttons.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(workflow_buttons, text="Gửi workflow", command=self.send_workflow_text).pack(side=tk.LEFT)
        ttk.Button(workflow_buttons, text="Xóa", command=lambda: self.workflow_text.delete("1.0", tk.END)).pack(side=tk.LEFT, padx=5)
        ttk.Button(
            workflow_buttons,
            text="Mẫu LOOP/IF",
            command=self.insert_loop_example,
        ).pack(side=tk.LEFT, padx=5)

        raw_frame = ttk.LabelFrame(parent, text="Gửi JSON/text thô", padding=8)
        raw_frame.pack(fill=tk.X, pady=(8, 0))
        self.raw_entry = ttk.Entry(raw_frame)
        self.raw_entry.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.raw_entry.bind("<Return>", lambda _event: self.send_raw())
        ttk.Button(raw_frame, text="Gửi", command=self.send_raw).pack(side=tk.LEFT, padx=(6, 0))

    def _build_nodes_tab(self, parent):
        filters = ttk.Frame(parent)
        filters.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(filters, text="Limit").pack(side=tk.LEFT)
        ttk.Entry(filters, textvariable=self.node_limit_var, width=7).pack(side=tk.LEFT, padx=(4, 8))
        ttk.Label(filters, text="Offset").pack(side=tk.LEFT)
        ttk.Entry(filters, textvariable=self.node_offset_var, width=8).pack(side=tk.LEFT, padx=(4, 8))
        ttk.Label(filters, text="Lọc").pack(side=tk.LEFT)
        filter_entry = ttk.Entry(filters, textvariable=self.node_filter_var, width=28)
        filter_entry.pack(side=tk.LEFT, padx=(4, 8))
        filter_entry.bind("<Return>", lambda _event: self.request_nodes())
        ttk.Button(filters, text="Đọc nodes", command=self.request_nodes).pack(side=tk.LEFT)
        ttk.Button(filters, text="Trang trước", command=lambda: self.change_node_page(-1)).pack(side=tk.LEFT, padx=4)
        ttk.Button(filters, text="Trang sau", command=lambda: self.change_node_page(1)).pack(side=tk.LEFT)
        ttk.Label(filters, textvariable=self.node_summary_var).pack(side=tk.RIGHT)

        columns = (
            "device", "key", "text", "description", "view_id", "class", "bounds", "enabled", "clickable"
        )
        tree_frame = ttk.Frame(parent)
        tree_frame.pack(fill=tk.BOTH, expand=True)
        self.node_tree = ttk.Treeview(tree_frame, columns=columns, show="headings")
        widths = {
            "device": 120,
            "key": 80,
            "text": 180,
            "description": 210,
            "view_id": 180,
            "class": 180,
            "bounds": 150,
            "enabled": 70,
            "clickable": 70,
        }
        for column in columns:
            self.node_tree.heading(column, text=column)
            self.node_tree.column(column, width=widths[column], minwidth=55, stretch=True)
        y_scroll = ttk.Scrollbar(tree_frame, orient=tk.VERTICAL, command=self.node_tree.yview)
        x_scroll = ttk.Scrollbar(tree_frame, orient=tk.HORIZONTAL, command=self.node_tree.xview)
        self.node_tree.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        self.node_tree.grid(row=0, column=0, sticky="nsew")
        y_scroll.grid(row=0, column=1, sticky="ns")
        x_scroll.grid(row=1, column=0, sticky="ew")
        tree_frame.rowconfigure(0, weight=1)
        tree_frame.columnconfigure(0, weight=1)

    def _build_screens_tab(self, parent):
        toolbar = ttk.Frame(parent)
        toolbar.pack(fill=tk.X, pady=(0, 8))
        for label, variable, width in (
            ("FPS", self.stream_fps_var, 4),
            ("Rộng", self.stream_width_var, 6),
            ("JPEG %", self.stream_quality_var, 5),
            ("Hiển thị %", self.display_width_var, 4),
        ):
            ttk.Label(toolbar, text=label).pack(side=tk.LEFT, padx=(4, 2))
            ttk.Entry(toolbar, textvariable=variable, width=width).pack(side=tk.LEFT)
        ttk.Button(toolbar, text="Bắt đầu xem máy đã chọn", command=self.start_screen_stream).pack(
            side=tk.LEFT, padx=(10, 4)
        )
        ttk.Button(toolbar, text="Dừng xem máy đã chọn", command=self.stop_screen_stream).pack(
            side=tk.LEFT
        )
        ttk.Button(toolbar, text="Đổi kích thước", command=self.apply_display_size).pack(
            side=tk.LEFT, padx=4
        )
        ttk.Label(toolbar, textvariable=self.stream_status_var).pack(side=tk.RIGHT)

        self.screen_grid = ttk.Frame(parent)
        self.screen_grid.pack(fill=tk.BOTH, expand=True)
        self.screen_grid.columnconfigure(0, weight=1)
        self.screen_grid.columnconfigure(1, weight=1)
        self.screen_grid.rowconfigure(0, weight=1)
        self.screen_grid.bind("<Configure>", lambda _event: self._schedule_screen_render())

    def _build_log_tab(self, parent):
        toolbar = ttk.Frame(parent)
        toolbar.pack(fill=tk.X, pady=(0, 6))
        ttk.Button(toolbar, text="Xóa log", command=self.clear_log).pack(side=tk.LEFT)
        self.log_text = tk.Text(parent, wrap=tk.NONE, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True)

    def start_server(self):
        try:
            port = int(self.port_var.get())
            if port < 1 or port > 65535:
                raise ValueError
        except ValueError:
            messagebox.showerror("Port không hợp lệ", "Port phải nằm trong khoảng 1-65535")
            return
        if self.backend.thread and self.backend.thread.is_alive():
            return
        self.backend = WebSocketBackend(self.events)
        self.server_status_var.set("Đang khởi động...")
        self.backend.start(self.host_var.get().strip() or "0.0.0.0", port)

    def stop_server(self):
        self.backend.stop()

    def send_workflow_text(self):
        script = self.workflow_text.get("1.0", tk.END).strip()
        if script:
            self.send_workflow(script)

    def _custom_swipe_command(self):
        try:
            values = [
                int(self.swipe_start_x_var.get()),
                int(self.swipe_start_y_var.get()),
                int(self.swipe_end_x_var.get()),
                int(self.swipe_end_y_var.get()),
                int(self.swipe_duration_var.get()),
            ]
        except ValueError:
            messagebox.showerror("Vuốt không hợp lệ", "Tọa độ và thời gian phải là số nguyên")
            return None
        if any(value < 0 for value in values[:4]):
            messagebox.showerror("Vuốt không hợp lệ", "Tọa độ không được âm")
            return None
        if values[:2] == values[2:4]:
            messagebox.showerror("Vuốt không hợp lệ", "Điểm A và B phải khác nhau")
            return None
        if not 50 <= values[4] <= 60_000:
            messagebox.showerror("Vuốt không hợp lệ", "Thời gian phải từ 50 đến 60000 ms")
            return None
        return "SWIPE:" + ",".join(str(value) for value in values)

    def send_custom_swipe(self):
        command = self._custom_swipe_command()
        if command:
            self.send_workflow(command)

    def append_custom_swipe(self):
        command = self._custom_swipe_command()
        if not command:
            return
        current = self.workflow_text.get("1.0", tk.END).strip()
        self.workflow_text.delete("1.0", tk.END)
        self.workflow_text.insert("1.0", f"{current};{command}" if current else command)

    def send_workflow(self, script, targets=None):
        targets = self._selected_clients() if targets is None else list(targets)
        if not targets:
            self._log("SEND ERROR: Chưa chọn điện thoại")
            return
        request_id = f"pc-{next(self.command_ids)}"
        sent_at = time.perf_counter()
        for client_id in targets:
            self.pending[(request_id, client_id)] = {
                "sent": sent_at,
                "received_phone_ms": None,
                "started_phone_ms": None,
            }
        payload = json.dumps(
            {"cmd": "run", "id": request_id, "script": script},
            ensure_ascii=False,
            separators=(",", ":"),
        )
        self._log(f"[{request_id}] SEND       {script}")
        self.backend.send(payload, targets)

    def send_stop(self):
        targets = self._selected_clients()
        if not targets:
            self._log("SEND ERROR: Chưa chọn điện thoại")
            return
        request_id = f"pc-{next(self.command_ids)}"
        sent_at = time.perf_counter()
        for client_id in targets:
            self.pending[(request_id, client_id)] = {"sent": sent_at}
        self._log(f"[{request_id}] SEND       STOP")
        self.backend.send(json.dumps({"cmd": "stop", "id": request_id}), targets)

    def send_raw(self):
        value = self.raw_entry.get().strip()
        if value:
            self.backend.send(value, self._selected_clients())
            self._log(f"PC RAW: {value}")

    def request_nodes(self):
        try:
            limit = max(1, min(2000, int(self.node_limit_var.get())))
            offset = max(0, int(self.node_offset_var.get()))
        except ValueError:
            messagebox.showerror("Giá trị không hợp lệ", "Limit và offset phải là số nguyên")
            return
        self.node_limit_var.set(str(limit))
        self.node_offset_var.set(str(offset))
        targets = self._selected_clients()
        self.node_tree.delete(*self.node_tree.get_children())
        self.pending_node_rows.clear()
        self.node_response_count = 0
        self.backend.send(json.dumps({
            "cmd": "nodes",
            "limit": limit,
            "offset": offset,
            "filter": self.node_filter_var.get().strip(),
        }, ensure_ascii=False), targets)

    def change_node_page(self, direction):
        try:
            limit = max(1, int(self.node_limit_var.get()))
            offset = max(0, int(self.node_offset_var.get()))
        except ValueError:
            return
        self.node_offset_var.set(str(max(0, offset + direction * limit)))
        self.request_nodes()

    def request_capture(self):
        self.backend.send(json.dumps({"cmd": "capture_status"}), self._selected_clients())

    def request_images(self):
        self.backend.send(json.dumps({"cmd": "image_list"}), self._selected_clients())

    def start_screen_stream(self):
        try:
            fps = max(1, min(12, int(self.stream_fps_var.get())))
            width = max(240, min(720, int(self.stream_width_var.get())))
            quality = max(35, min(80, int(self.stream_quality_var.get())))
        except ValueError:
            messagebox.showerror("Cấu hình không hợp lệ", "FPS, chiều rộng và JPEG phải là số nguyên")
            return
        targets = self._selected_clients()
        if not targets:
            self._log("SCREEN ERROR: Chưa chọn điện thoại")
            return
        self.stream_fps_var.set(str(fps))
        self.stream_width_var.set(str(width))
        self.stream_quality_var.set(str(quality))
        self.backend.send(json.dumps({
            "cmd": "screen_stream_start",
            "fps": fps,
            "width": width,
            "quality": quality,
        }), targets)
        self.stream_status_var.set(f"Đang yêu cầu {fps} FPS • {width}px • JPEG {quality}%")

    def stop_screen_stream(self):
        targets = self._selected_clients()
        self.backend.send(json.dumps({"cmd": "screen_stream_stop"}), targets)

    def _selected_clients(self):
        return [client_id for client_id, value in self.device_vars.items() if value.get()]

    def select_all_devices(self, selected):
        for value in self.device_vars.values():
            value.set(selected)

    def _add_device(self, client_id, label):
        if client_id in self.device_vars:
            return
        value = tk.BooleanVar(value=True)
        widget = ttk.Checkbutton(
            self.device_checks,
            text=f"{client_id} • {label}",
            variable=value,
        )
        widget.pack(side=tk.LEFT, padx=4)
        self.device_vars[client_id] = value
        self.device_labels[client_id] = label
        self.device_widgets[client_id] = widget
        self.client_status_var.set(f"{len(self.device_vars)} điện thoại")

    def _remove_device(self, client_id):
        widget = self.device_widgets.pop(client_id, None)
        if widget:
            widget.destroy()
        self.device_vars.pop(client_id, None)
        self.device_labels.pop(client_id, None)
        screen = self.screen_labels.pop(client_id, None)
        if screen:
            screen.master.destroy()
        self.screen_images.pop(client_id, None)
        self.screen_source_images.pop(client_id, None)
        self.screen_geometry.pop(client_id, None)
        with self.frame_lock:
            self.pending_frames.pop(client_id, None)
        self._schedule_screen_render()
        for key in [key for key in self.pending if key[1] == client_id]:
            self.pending.pop(key, None)
        self.client_status_var.set(f"{len(self.device_vars)} điện thoại")

    def _clear_devices(self):
        for client_id in list(self.device_widgets):
            self._remove_device(client_id)

    def insert_loop_example(self):
        example = (
            "LOOP:10;IF:Nhận thưởng|CLAIM;DOWN;CONTINUE;"
            "LABEL:CLAIM;CLICK:Nhận thưởng;BREAK;END_LOOP;HOME"
        )
        self.workflow_text.delete("1.0", tk.END)
        self.workflow_text.insert("1.0", example)

    def _poll_events(self):
        if self.closing:
            return
        processed = 0
        try:
            while processed < MAX_EVENTS_PER_TICK:
                event = self.events.get_nowait()
                self._handle_event(event)
                processed += 1
        except queue.Empty:
            pass
        self.root.after(10 if not self.events.empty() else 50, self._poll_events)

    def _handle_event(self, event):
        kind = event[0]
        if kind == "server_started":
            self.server_status_var.set(f"Đang nghe ws://{event[1]}:{event[2]}")
            self._log(self.server_status_var.get())
        elif kind == "server_stopped":
            self.server_status_var.set("Đã dừng")
            self._clear_devices()
            self._log("WebSocket server đã dừng")
        elif kind == "server_error":
            self.server_status_var.set("Lỗi server")
            self._log(f"SERVER ERROR: {event[1]}")
            messagebox.showerror("WebSocket server", event[1])
        elif kind == "client_connected":
            self._add_device(event[1], event[2])
            self._log(f"CONNECTED {event[1]} • {event[2]}")
        elif kind == "client_disconnected":
            label = self.device_labels.get(event[1], event[1])
            self._remove_device(event[1])
            self._log(f"DISCONNECTED {event[1]} • {label}")
        elif kind == "send_error":
            self._log(f"SEND ERROR: {event[1]}")
        elif kind == "message":
            self._handle_phone_message(event[1], event[2])
        elif kind == "decoded_frame":
            self._show_screen_frame(event[1], event[2], event[3], event[4])
        elif kind == "frame_error":
            self._log(f"[{event[1]}] FRAME ERROR: {event[2]}")

    def _handle_phone_message(self, client_id, payload):
        label = self.device_labels.get(client_id, client_id)
        if not isinstance(payload, dict):
            self._log(f"[{client_id} • {label}] PHONE: {payload}")
            return
        obj = payload
        message_type = obj.get("type")
        if message_type == "ack":
            self._handle_ack(client_id, obj)
        elif message_type == "nodes":
            self._show_nodes(client_id, obj)
        elif message_type == "screen_frame":
            self._queue_screen_frame(client_id, obj)
        elif message_type == "screen_stream":
            self.stream_status_var.set(f"{client_id}: {obj.get('state')}")
            self._log(f"[{client_id}] SCREEN {obj.get('state')}")
        elif message_type == "ready":
            self.device_summary_var.set(f"Thiết bị sẵn sàng: {len(self.device_vars)}")
            self._log(f"[{client_id} • {label}] READY: {json.dumps(obj, ensure_ascii=False)}")
        elif message_type == "workflow":
            self._log(
                f"[{client_id} • {label}] WORKFLOW "
                f"id={obj.get('request_id')} state={obj.get('state')} "
                f"step={obj.get('step')}/{obj.get('total')} "
                f"command={obj.get('command')} target={obj.get('target')} "
                f"error={obj.get('error')}"
            )
        else:
            self._log(f"[{client_id} • {label}] PHONE: {json.dumps(obj, ensure_ascii=False)}")

    def _queue_screen_frame(self, client_id, obj):
        encoded = obj.get("jpeg")
        if not isinstance(encoded, str):
            return
        with self.frame_lock:
            self.pending_frames[client_id] = (encoded, obj)
            if client_id in self.frame_decode_busy:
                return
            self.frame_decode_busy.add(client_id)
        self.frame_executor.submit(self._decode_screen_frames, client_id)

    def _decode_screen_frames(self, client_id):
        while True:
            with self.frame_lock:
                item = self.pending_frames.pop(client_id, None)
                if item is None:
                    self.frame_decode_busy.discard(client_id)
                    return
                encoded, metadata = item
            try:
                image = Image.open(io.BytesIO(base64.b64decode(encoded))).convert("RGB")
                image.load()
                self.events.put((
                    "decoded_frame",
                    client_id,
                    image,
                    int(metadata.get("source_width") or image.width),
                    int(metadata.get("source_height") or image.height),
                ))
            except Exception as error:
                self.events.put(("frame_error", client_id, str(error)))

    def _show_screen_frame(self, client_id, image, source_width, source_height):
        widget = self.screen_labels.get(client_id)
        if widget is None:
            frame = ttk.LabelFrame(
                self.screen_grid,
                text=f"{client_id} • {self.device_labels.get(client_id, client_id)}",
                padding=4,
            )
            position = len(self.screen_labels)
            row = position // 2
            self.screen_grid.rowconfigure(row, weight=1)
            frame.grid(row=row, column=position % 2, padx=4, pady=4, sticky="nsew")
            widget = ttk.Label(frame, anchor=tk.CENTER)
            widget.pack(fill=tk.BOTH, expand=True)
            widget.bind("<Button-1>", lambda event, device=client_id: self._click_screen(device, event))
            widget.configure(cursor="hand2")
            self.screen_labels[client_id] = widget
        self.screen_source_images[client_id] = image
        self.screen_geometry[client_id] = (source_width, source_height, image.width, image.height)
        self._render_screen_image(client_id)
        self._schedule_screen_render()

    def _display_percent(self):
        try:
            value = int(self.display_width_var.get())
        except ValueError:
            raise ValueError("Tỷ lệ hiển thị phải là số nguyên")
        if not 25 <= value <= 100:
            raise ValueError("Tỷ lệ hiển thị phải từ 25 đến 100%")
        return value

    def apply_display_size(self):
        try:
            percent = self._display_percent()
        except ValueError as error:
            messagebox.showerror("Kích thước không hợp lệ", str(error))
            return
        self.display_width_var.set(str(percent))
        for client_id in list(self.screen_source_images):
            self._render_screen_image(client_id)

    def _schedule_screen_render(self):
        if self.screen_render_job is not None:
            self.root.after_cancel(self.screen_render_job)
        self.screen_render_job = self.root.after(80, self._render_all_screens)

    def _render_all_screens(self):
        self.screen_render_job = None
        for client_id in list(self.screen_source_images):
            self._render_screen_image(client_id)

    def _render_screen_image(self, client_id):
        image = self.screen_source_images.get(client_id)
        widget = self.screen_labels.get(client_id)
        if image is None or widget is None:
            return
        try:
            percent = self._display_percent() / 100.0
        except ValueError:
            percent = 1.0
        device_count = max(1, len(self.screen_labels))
        columns = min(2, device_count)
        rows = (device_count + columns - 1) // columns
        available_width = max(100, self.screen_grid.winfo_width() // columns - 20)
        available_height = max(100, self.screen_grid.winfo_height() // rows - 28)
        width, height = self._fit_screen_size(
            image.width,
            image.height,
            available_width,
            available_height,
            percent,
        )
        rendered = image if (width, height) == image.size else image.resize(
            (width, height), Image.Resampling.BILINEAR
        )
        photo = ImageTk.PhotoImage(rendered)
        self.screen_images[client_id] = photo
        widget.configure(image=photo)
        source_width, source_height, _, _ = self.screen_geometry[client_id]
        self.screen_geometry[client_id] = (source_width, source_height, width, height)

    @staticmethod
    def _fit_screen_size(image_width, image_height, available_width, available_height, percent):
        scale = min(
            available_width * percent / image_width,
            available_height * percent / image_height,
        )
        return (
            max(1, round(image_width * scale)),
            max(1, round(image_height * scale)),
        )

    def _click_screen(self, client_id, event):
        geometry = self.screen_geometry.get(client_id)
        widget = self.screen_labels.get(client_id)
        if geometry is None or widget is None:
            return
        source_width, source_height, rendered_width, rendered_height = geometry
        left = max(0, (widget.winfo_width() - rendered_width) // 2)
        top = max(0, (widget.winfo_height() - rendered_height) // 2)
        image_x = event.x - left
        image_y = event.y - top
        if not (0 <= image_x < rendered_width and 0 <= image_y < rendered_height):
            return
        target_x = min(source_width - 1, round(image_x * source_width / rendered_width))
        target_y = min(source_height - 1, round(image_y * source_height / rendered_height))
        self._log(f"[{client_id}] FRAME CLICK ({image_x},{image_y}) -> TAP:{target_x},{target_y}")
        self.send_workflow(f"TAP:{target_x},{target_y}", [client_id])

    def _handle_ack(self, client_id, obj):
        request_id = str(obj.get("id", "?"))
        state = str(obj.get("state", "?"))
        item = self.pending.get((request_id, client_id))
        prefix = f"[{client_id} {request_id}]"
        if not item:
            self._log(f"{prefix} {state.upper()} phone_ms={obj.get('phone_ms')}")
            return
        elapsed = (time.perf_counter() - item["sent"]) * 1000.0
        phone_ms = obj.get("phone_ms")
        details = ""
        if state == "received":
            item["received_phone_ms"] = phone_ms
        elif state == "started":
            item["started_phone_ms"] = phone_ms
            received = item.get("received_phone_ms")
            if isinstance(phone_ms, (int, float)) and isinstance(received, (int, float)):
                details += f" phone_queue={phone_ms - received:.1f}ms"
            details += f" tree_scan={obj.get('last_tree_scan_ms')}ms"
        elif state in {"completed", "failed", "stopped", "cancelled"}:
            started = item.get("started_phone_ms")
            if isinstance(phone_ms, (int, float)) and isinstance(started, (int, float)):
                details += f" phone_execute={phone_ms - started:.1f}ms"
            if obj.get("error"):
                details += f" error={obj.get('error')}"
            self.pending.pop((request_id, client_id), None)
        self._log(f"{prefix} {state.upper():<10} +{elapsed:8.1f}ms{details}")

    def _show_nodes(self, client_id, obj):
        label = self.device_labels.get(client_id, client_id)
        for node in obj.get("nodes", []):
            bounds = (
                f"{node.get('left')},{node.get('top')},"
                f"{node.get('right')},{node.get('bottom')}"
            )
            self.pending_node_rows.append((
                f"{client_id} • {label}",
                node.get("key"),
                node.get("text"),
                node.get("description"),
                node.get("view_id"),
                node.get("class"),
                bounds,
                node.get("enabled"),
                node.get("clickable"),
            ))
        self.node_response_count += 1
        if not self.node_insert_scheduled:
            self.node_insert_scheduled = True
            self.root.after_idle(self._drain_node_rows)
        self.node_summary_var.set(
            f"{self.node_response_count} thiết bị trả lời • "
            f"{obj.get('returned', 0)}/{obj.get('total', 0)} nodes gần nhất"
        )
        self.device_summary_var.set(f"{client_id}: {obj.get('package')}")
        self.notebook.select(2)
        self._log(
            f"[{client_id} • {label}] NODES package={obj.get('package')} returned={obj.get('returned')}/"
            f"{obj.get('total')} offset={obj.get('offset')} has_more={obj.get('has_more')}"
        )

    def _drain_node_rows(self):
        batch = self.pending_node_rows[:NODE_ROWS_PER_TICK]
        del self.pending_node_rows[:NODE_ROWS_PER_TICK]
        for values in batch:
            self.node_tree.insert("", tk.END, values=values)
        if self.pending_node_rows:
            self.root.after(10, self._drain_node_rows)
        else:
            self.node_insert_scheduled = False

    def _log(self, value):
        timestamp = time.strftime("%H:%M:%S")
        self.log_buffer.append(f"{timestamp} {value}\n")

    def _flush_logs(self):
        if self.closing:
            return
        if self.log_buffer:
            payload = "".join(self.log_buffer)
            self.log_buffer.clear()
            self.log_text.configure(state=tk.NORMAL)
            self.log_text.insert(tk.END, payload)
            line_count = int(self.log_text.index("end-1c").split(".")[0])
            if line_count > MAX_LOG_LINES:
                self.log_text.delete("1.0", f"{line_count - MAX_LOG_LINES}.0")
            self.log_text.see(tk.END)
            self.log_text.configure(state=tk.DISABLED)
        self.root.after(100, self._flush_logs)

    def clear_log(self):
        self.log_buffer.clear()
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.delete("1.0", tk.END)
        self.log_text.configure(state=tk.DISABLED)

    def _on_close(self):
        if self.closing:
            return
        self.closing = True
        if self.screen_render_job is not None:
            self.root.after_cancel(self.screen_render_job)
            self.screen_render_job = None
        with self.frame_lock:
            self.pending_frames.clear()
        self.backend.stop()
        self.frame_executor.shutdown(wait=False, cancel_futures=True)
        self.root.quit()
        self.root.destroy()


def main():
    root = tk.Tk()
    TronangControlApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
