import asyncio
import base64
import io
import itertools
import json
import queue
import threading
import time
import tkinter as tk
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor
from tkinter import messagebox, ttk

import websockets
from PIL import Image, ImageTk


MAX_EVENTS_PER_TICK = 100
NODE_ROWS_PER_TICK = 50
MAX_LOG_LINES = 5_000

COMMAND_GUIDE = (
    ("CLICK", "CLICK:Văn bản", "Bấm node theo text hoặc mô tả."),
    ("CLICK_TIME", "CLICK_TIME", "Bấm mô tả dạng đồng hồ đếm ngược mm:ss."),
    ("IF_TIME", "IF_TIME:TEN_NHAN", "Nếu có mô tả đồng hồ mm:ss thì nhảy tới LABEL."),
    ("IF_NOT_TIME", "IF_NOT_TIME:TEN_NHAN", "Nếu không còn mô tả đồng hồ mm:ss thì nhảy tới LABEL."),
    ("CLICK_DESC_REGEX", "CLICK_DESC_REGEX:^Mở.*", "Bấm node có mô tả khớp biểu thức chính quy."),
    ("CLICK_CLASS_DESC_REGEX", "CLICK_CLASS_DESC_REGEX:android.widget.Button|^Mở.*", "Lọc theo class và regex mô tả."),
    ("TAP", "TAP:540,1200", "Bấm trực tiếp tại toạ độ x,y."),
    ("SWIPE", "SWIPE:540,1500,540,500,350", "Vuốt từ A đến B trong thời gian mili giây."),
    ("WAIT", "WAIT:Văn bản", "Chờ node có text hoặc mô tả xuất hiện."),
    ("WAIT_IMG", "WAIT_IMG:tên_mẫu", "Chờ ảnh mẫu xuất hiện trên màn hình."),
    ("CLICK_IMG", "CLICK_IMG:tên_mẫu", "Tìm và bấm vào ảnh mẫu."),
    ("OPEN_APP", "OPEN_APP:com.example.app", "Mở package trong profile cá nhân."),
    ("OPEN_APP profile", "OPEN_APP:com.example.app|16", "Mở package theo profile serial."),
    ("SLEEP", "SLEEP:1.5", "Tạm chờ số giây, tối đa 3600 giây."),
    ("SLEEP_RANDOM", "SLEEP_RANDOM:2,5", "Nghỉ ngẫu nhiên từ 2 đến 5 giây."),
    ("UP", "UP", "Vuốt lên theo cấu hình mặc định."),
    ("DOWN", "DOWN", "Vuốt xuống theo cấu hình mặc định."),
    ("LEFT", "LEFT", "Vuốt sang trái."),
    ("RIGHT", "RIGHT", "Vuốt sang phải."),
    ("BACK", "BACK", "Thực hiện nút Quay lại."),
    ("HOME", "HOME", "Trở về màn hình chính."),
    ("RECENTS", "RECENTS", "Mở màn hình đa nhiệm."),
    ("LABEL", "LABEL:TEN_NHAN", "Đánh dấu vị trí để GOTO hoặc IF nhảy tới."),
    ("GOTO", "GOTO:TEN_NHAN", "Nhảy tới LABEL tương ứng."),
    ("IF", "IF:Văn bản|TEN_NHAN", "Nếu thấy mục tiêu thì nhảy tới LABEL."),
    ("IF_NOT", "IF_NOT:Văn bản|TEN_NHAN", "Nếu không thấy mục tiêu thì nhảy tới LABEL."),
    ("IF_IMG", "IF_IMG:tên_mẫu|TEN_NHAN", "Nếu thấy ảnh mẫu thì nhảy tới LABEL."),
    ("IF_NOT_IMG", "IF_NOT_IMG:tên_mẫu|TEN_NHAN", "Nếu không thấy ảnh mẫu thì nhảy tới LABEL."),
    ("LOOP", "LOOP:10", "Bắt đầu vòng lặp với số lần chỉ định."),
    ("END_LOOP", "END_LOOP", "Kết thúc và quay lại đầu vòng lặp."),
    ("BREAK", "BREAK", "Thoát khỏi vòng lặp hiện tại."),
    ("CONTINUE", "CONTINUE", "Chuyển sang lượt lặp tiếp theo."),
)


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
        headers = getattr(ws, "request_headers", None)
        if headers is None:
            headers = getattr(getattr(ws, "request", None), "headers", None)
        raw_device_id = headers.get("X-Tronang-Device-Id") if headers else None
        safe_device_id = "".join(
            char for char in (raw_device_id or "").lower()
            if char.isalnum() or char in "-_"
        )[:32]
        client_id = (
            f"phone-{safe_device_id}"
            if safe_device_id
            else f"phone-{next(self.client_ids)}"
        )
        remote = ws.remote_address
        remote_label = f"{remote[0]}:{remote[1]}" if remote else client_id
        previous = self.clients.get(client_id)
        self.clients[client_id] = ws
        if previous is not None and previous is not ws:
            await previous.close(4001, "replaced_by_reconnect")
        self.events.put(("client_connected", client_id, remote_label))
        try:
            async for message in ws:
                try:
                    payload = json.loads(message)
                except Exception:
                    payload = message
                self.events.put(("message", client_id, payload))
        finally:
            if self.clients.get(client_id) is ws:
                self.clients.pop(client_id, None)
                self.events.put((
                    "client_disconnected",
                    client_id,
                    getattr(ws, "close_code", None),
                    getattr(ws, "close_reason", None),
                ))

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
        self.active_image_requests = {}
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
        self.zoom_windows = {}
        self.zoom_canvases = {}
        self.zoom_images = {}
        self.zoom_geometry = {}
        self.zoom_sample_drag = None
        self.screen_render_job = None
        self.closing = False
        self.sample_mode = False
        self.sample_drag = None
        self.image_target_rows = {}
        self.saved_workflow_name_var = tk.StringVar()
        self.app_profile_var = tk.StringVar(value="Không tự mở ứng dụng")
        self.app_profile_targets = {}
        self.saved_workflow_rows = {}
        self.command_guide_filter_var = tk.StringVar()
        self.command_suggestion_var = tk.StringVar()
        self.plan_name_var = tk.StringVar()
        self.plan_schedule_type_var = tk.StringVar(value="daily")
        self.plan_schedule_value_var = tk.StringVar(value="08:00")
        self.plan_enabled_var = tk.BooleanVar(value=True)
        self.automation_plan_rows = {}

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
        self.sample_name_var = tk.StringVar(value="MAU_1")
        self.sample_threshold_var = tk.StringVar(value="0.90")
        self.sample_margin_var = tk.StringVar(value="120")
        self.sample_button_var = tk.StringVar(value="Tạo ảnh mẫu")
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

        self._build_quick_controls(self.root)

        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=8, pady=(0, 8))

        control = ttk.Frame(self.notebook, padding=10)
        nodes = ttk.Frame(self.notebook, padding=8)
        logs = ttk.Frame(self.notebook, padding=8)
        screens = ttk.Frame(self.notebook, padding=8)
        library = ttk.Frame(self.notebook, padding=8)
        guide = ttk.Frame(self.notebook, padding=8)
        planner = ttk.Frame(self.notebook, padding=8)
        self.nodes_tab = nodes
        self.logs_tab = logs
        self.notebook.add(control, text="Điều khiển")
        self.notebook.add(library, text="Thư viện workflow")
        self.notebook.add(guide, text="Hướng dẫn lệnh")
        self.notebook.add(planner, text="Lịch tự động")
        self.notebook.add(screens, text="Màn hình")
        self.notebook.add(nodes, text="Nodes")
        self.notebook.add(logs, text="Log / độ trễ")
        self._build_control_tab(control)
        self._build_library_tab(library)
        self._build_command_guide_tab(guide)
        self._build_automation_plan_tab(planner)
        self._build_screens_tab(screens)
        self._build_nodes_tab(nodes)
        self._build_log_tab(logs)
        self.notebook.bind("<<NotebookTabChanged>>", self._toggle_quick_controls)

    def _build_quick_controls(self, parent):
        self.quick_controls = ttk.LabelFrame(parent, text="Điều khiển nhanh", padding=6)
        self.quick_controls.pack(fill=tk.X, padx=8, pady=(0, 6))
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
                self.quick_controls,
                text=label,
                command=lambda value=command: self.send_workflow(value),
            ).pack(side=tk.LEFT, padx=3)

    def _toggle_quick_controls(self, _event=None):
        if self.notebook.select() == str(self.logs_tab):
            self.quick_controls.pack_forget()
        elif not self.quick_controls.winfo_manager():
            self.quick_controls.pack(
                fill=tk.X,
                padx=8,
                pady=(0, 6),
                before=self.notebook,
            )

    def _build_control_tab(self, parent):
        ttk.Label(parent, textvariable=self.device_summary_var).pack(anchor=tk.W, pady=(0, 8))

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
        self.workflow_suggestion_list = tk.Listbox(
            workflow_frame,
            height=7,
            activestyle="dotbox",
            exportselection=False,
        )
        self.workflow_suggestion_start = None
        self.workflow_text.bind("<KeyRelease>", self._show_workflow_autocomplete, add="+")
        self.workflow_text.bind("<Down>", self._autocomplete_move_down, add="+")
        self.workflow_text.bind("<Up>", self._autocomplete_move_up, add="+")
        self.workflow_text.bind("<Return>", self._autocomplete_accept, add="+")
        self.workflow_text.bind("<Tab>", self._autocomplete_accept, add="+")
        self.workflow_text.bind("<Escape>", self._autocomplete_hide, add="+")
        self.workflow_suggestion_list.bind("<Double-1>", self._autocomplete_accept)
        self.workflow_suggestion_list.bind("<ButtonRelease-1>", self._autocomplete_accept)

        workflow_buttons = ttk.Frame(workflow_frame)
        workflow_buttons.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(workflow_buttons, text="Gửi workflow", command=self.send_workflow_text).pack(side=tk.LEFT)
        ttk.Button(workflow_buttons, text="Xóa", command=lambda: self.workflow_text.delete("1.0", tk.END)).pack(side=tk.LEFT, padx=5)
        ttk.Button(
            workflow_buttons,
            text="Mẫu LOOP/IF",
            command=self.insert_loop_example,
        ).pack(side=tk.LEFT, padx=5)
        ttk.Label(workflow_buttons, text="Gợi ý lệnh").pack(side=tk.LEFT, padx=(14, 4))
        self.command_suggestion_combo = ttk.Combobox(
            workflow_buttons,
            textvariable=self.command_suggestion_var,
            values=tuple(item[1] for item in COMMAND_GUIDE),
            width=34,
        )
        self.command_suggestion_combo.pack(side=tk.LEFT)
        self.command_suggestion_combo.bind("<KeyRelease>", self._filter_command_suggestions)
        self.command_suggestion_combo.bind("<Return>", lambda _event: self._insert_command_suggestion())
        ttk.Button(
            workflow_buttons,
            text="Chèn",
            command=self._insert_command_suggestion,
        ).pack(side=tk.LEFT, padx=(4, 0))

        raw_frame = ttk.LabelFrame(parent, text="Gửi JSON/text thô", padding=8)
        raw_frame.pack(fill=tk.X, pady=(8, 0))
        self.raw_entry = ttk.Entry(raw_frame)
        self.raw_entry.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.raw_entry.bind("<Return>", lambda _event: self.send_raw())
        ttk.Button(raw_frame, text="Gửi", command=self.send_raw).pack(side=tk.LEFT, padx=(6, 0))

    def _build_library_tab(self, parent):
        ttk.Label(
            parent,
            text="Dữ liệu được lưu trên điện thoại. Hãy chọn đúng 1 điện thoại để thao tác.",
        ).pack(anchor=tk.W, pady=(0, 8))

        editor = ttk.LabelFrame(parent, text="Workflow", padding=8)
        editor.pack(fill=tk.X)
        ttk.Label(editor, text="Tên").grid(row=0, column=0, sticky="w")
        ttk.Entry(editor, textvariable=self.saved_workflow_name_var, width=32).grid(
            row=0, column=1, sticky="ew", padx=(6, 12)
        )
        ttk.Label(editor, text="Ứng dụng / profile").grid(row=0, column=2, sticky="w")
        self.app_profile_combo = ttk.Combobox(
            editor, textvariable=self.app_profile_var, state="readonly", width=52
        )
        self.app_profile_combo.grid(row=0, column=3, sticky="ew", padx=(6, 0))
        self.app_profile_combo["values"] = ("Không tự mở ứng dụng",)
        editor.columnconfigure(1, weight=1)
        editor.columnconfigure(3, weight=2)

        buttons = ttk.Frame(editor)
        buttons.grid(row=1, column=0, columnspan=4, sticky="w", pady=(8, 0))
        ttk.Button(buttons, text="Lấy app/profile", command=self.request_app_profiles).pack(side=tk.LEFT)
        ttk.Button(buttons, text="Làm mới thư viện", command=self.request_saved_workflows).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Lưu / ghi đè", command=self.save_workflow_to_phone).pack(side=tk.LEFT)

        columns = ("name", "script", "package", "profile")
        tree_frame = ttk.Frame(parent)
        tree_frame.pack(fill=tk.BOTH, expand=True, pady=8)
        self.saved_workflow_tree = ttk.Treeview(tree_frame, columns=columns, show="headings")
        for column, title, width in (
            ("name", "Tên", 180), ("script", "Chuỗi hành động", 520),
            ("package", "Package", 240), ("profile", "Profile serial", 100),
        ):
            self.saved_workflow_tree.heading(column, text=title)
            self.saved_workflow_tree.column(column, width=width, minwidth=70)
        scroll = ttk.Scrollbar(tree_frame, orient=tk.VERTICAL, command=self.saved_workflow_tree.yview)
        self.saved_workflow_tree.configure(yscrollcommand=scroll.set)
        self.saved_workflow_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.saved_workflow_tree.bind("<Double-1>", lambda _event: self.load_saved_workflow())

        row_actions = ttk.Frame(parent)
        row_actions.pack(fill=tk.X)
        ttk.Button(row_actions, text="Nạp để sửa", command=self.load_saved_workflow).pack(side=tk.LEFT)
        ttk.Button(row_actions, text="Chạy trên điện thoại", command=self.run_saved_workflow).pack(side=tk.LEFT, padx=5)
        ttk.Button(row_actions, text="Xoá khỏi điện thoại", command=self.remove_saved_workflow).pack(side=tk.LEFT)

    def _build_command_guide_tab(self, parent):
        toolbar = ttk.Frame(parent)
        toolbar.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(toolbar, text="Tìm lệnh").pack(side=tk.LEFT)
        search = ttk.Entry(toolbar, textvariable=self.command_guide_filter_var, width=36)
        search.pack(side=tk.LEFT, padx=6)
        search.bind("<KeyRelease>", lambda _event: self._refresh_command_guide())
        ttk.Button(toolbar, text="Xoá lọc", command=self._clear_command_guide_filter).pack(side=tk.LEFT)
        ttk.Label(
            toolbar,
            text="Nhấp đúp một dòng để chèn cú pháp vào workflow.",
        ).pack(side=tk.RIGHT)

        columns = ("command", "syntax", "description")
        tree_frame = ttk.Frame(parent)
        tree_frame.pack(fill=tk.BOTH, expand=True)
        self.command_guide_tree = ttk.Treeview(tree_frame, columns=columns, show="headings")
        for column, title, width in (
            ("command", "Lệnh", 170),
            ("syntax", "Cú pháp / ví dụ", 390),
            ("description", "Công dụng", 560),
        ):
            self.command_guide_tree.heading(column, text=title)
            self.command_guide_tree.column(column, width=width, minwidth=100)
        y_scroll = ttk.Scrollbar(tree_frame, orient=tk.VERTICAL, command=self.command_guide_tree.yview)
        self.command_guide_tree.configure(yscrollcommand=y_scroll.set)
        self.command_guide_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        y_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.command_guide_tree.bind("<Double-1>", lambda _event: self._insert_guide_command())

        actions = ttk.Frame(parent)
        actions.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(actions, text="Chèn vào workflow", command=self._insert_guide_command).pack(side=tk.LEFT)
        ttk.Button(actions, text="Sao chép cú pháp", command=self._copy_guide_command).pack(side=tk.LEFT, padx=5)
        ttk.Button(actions, text="Mở tab Điều khiển", command=lambda: self.notebook.select(0)).pack(side=tk.LEFT)
        self._refresh_command_guide()

    def _refresh_command_guide(self):
        if not hasattr(self, "command_guide_tree"):
            return
        query = self.command_guide_filter_var.get().strip().casefold()
        for item in self.command_guide_tree.get_children():
            self.command_guide_tree.delete(item)
        for command, syntax, description in COMMAND_GUIDE:
            searchable = f"{command} {syntax} {description}".casefold()
            if not query or query in searchable:
                self.command_guide_tree.insert("", tk.END, values=(command, syntax, description))

    def _clear_command_guide_filter(self):
        self.command_guide_filter_var.set("")
        self._refresh_command_guide()

    def _filter_command_suggestions(self, _event=None):
        query = self.command_suggestion_var.get().strip().casefold()
        values = tuple(
            syntax
            for command, syntax, description in COMMAND_GUIDE
            if not query or query in f"{command} {syntax} {description}".casefold()
        )
        self.command_suggestion_combo["values"] = values

    def _show_workflow_autocomplete(self, event=None):
        if event is not None and event.keysym in {"Up", "Down", "Return", "Tab", "Escape"}:
            return
        before_cursor = self.workflow_text.get("1.0", "insert")
        separator = max(before_cursor.rfind(";"), before_cursor.rfind("\n"))
        raw_token = before_cursor[separator + 1:]
        query = raw_token.strip()
        if not query or ":" in query or any(char.isspace() for char in query):
            self._autocomplete_hide()
            return
        normalized = query.casefold()
        matches = [
            syntax
            for command, syntax, _description in COMMAND_GUIDE
            if command.casefold().startswith(normalized) or syntax.casefold().startswith(normalized)
        ]
        if not matches:
            self._autocomplete_hide()
            return

        self.workflow_suggestion_list.delete(0, tk.END)
        for syntax in matches[:12]:
            self.workflow_suggestion_list.insert(tk.END, syntax)
        self.workflow_suggestion_list.selection_set(0)
        self.workflow_suggestion_list.activate(0)
        self.workflow_suggestion_start = self.workflow_text.index(
            f"insert - {len(raw_token)} chars"
        )
        bbox = self.workflow_text.bbox("insert")
        if bbox is None:
            self._autocomplete_hide()
            return
        x, y, _width, height = bbox
        popup_width = min(430, max(240, self.workflow_text.winfo_width() - x - 8))
        self.workflow_suggestion_list.place(
            x=x,
            y=y + height,
            width=popup_width,
        )
        self.workflow_suggestion_list.lift()

    def _autocomplete_visible(self):
        return bool(self.workflow_suggestion_list.winfo_manager())

    def _autocomplete_move_down(self, _event=None):
        if not self._autocomplete_visible():
            return None
        current = self.workflow_suggestion_list.curselection()
        index = min((current[0] if current else -1) + 1, self.workflow_suggestion_list.size() - 1)
        self.workflow_suggestion_list.selection_clear(0, tk.END)
        self.workflow_suggestion_list.selection_set(index)
        self.workflow_suggestion_list.activate(index)
        self.workflow_suggestion_list.see(index)
        return "break"

    def _autocomplete_move_up(self, _event=None):
        if not self._autocomplete_visible():
            return None
        current = self.workflow_suggestion_list.curselection()
        index = max((current[0] if current else 1) - 1, 0)
        self.workflow_suggestion_list.selection_clear(0, tk.END)
        self.workflow_suggestion_list.selection_set(index)
        self.workflow_suggestion_list.activate(index)
        self.workflow_suggestion_list.see(index)
        return "break"

    def _autocomplete_accept(self, _event=None):
        if not self._autocomplete_visible() or self.workflow_suggestion_start is None:
            return None
        selected = self.workflow_suggestion_list.curselection()
        if not selected:
            return "break"
        syntax = self.workflow_suggestion_list.get(selected[0])
        self.workflow_text.delete(self.workflow_suggestion_start, "insert")
        self.workflow_text.insert(self.workflow_suggestion_start, syntax)
        self.workflow_text.mark_set("insert", f"{self.workflow_suggestion_start} + {len(syntax)} chars")
        self._autocomplete_hide()
        self.workflow_text.focus_set()
        return "break"

    def _autocomplete_hide(self, _event=None):
        self.workflow_suggestion_list.place_forget()
        self.workflow_suggestion_start = None
        return "break" if _event is not None else None

    def _insert_command_suggestion(self):
        syntax = self.command_suggestion_var.get().strip()
        if not syntax:
            messagebox.showinfo("Gợi ý lệnh", "Hãy chọn hoặc nhập cú pháp cần chèn")
            return
        current = self.workflow_text.get("1.0", tk.END).strip()
        self.workflow_text.delete("1.0", tk.END)
        self.workflow_text.insert("1.0", f"{current};{syntax}" if current else syntax)
        self.workflow_text.see(tk.END)

    def _selected_guide_syntax(self):
        selected = self.command_guide_tree.selection()
        if not selected:
            messagebox.showwarning("Chọn lệnh", "Hãy chọn một lệnh trong danh sách hướng dẫn.")
            return None
        return self.command_guide_tree.item(selected[0], "values")[1]

    def _insert_guide_command(self):
        syntax = self._selected_guide_syntax()
        if not syntax:
            return
        current = self.workflow_text.get("1.0", tk.END).strip()
        self.workflow_text.delete("1.0", tk.END)
        self.workflow_text.insert("1.0", f"{current};{syntax}" if current else syntax)

    def _copy_guide_command(self):
        syntax = self._selected_guide_syntax()
        if not syntax:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(syntax)
        self.root.update_idletasks()

    def _build_automation_plan_tab(self, parent):
        ttk.Label(
            parent,
            text="Mỗi dòng: Tên workflow|số lượt. Các dòng được chạy tuần tự từ trên xuống.",
        ).pack(anchor=tk.W, pady=(0, 8))

        editor = ttk.LabelFrame(parent, text="Kế hoạch", padding=8)
        editor.pack(fill=tk.X)
        ttk.Label(editor, text="Tên kế hoạch").grid(row=0, column=0, sticky="w")
        ttk.Entry(editor, textvariable=self.plan_name_var, width=28).grid(row=0, column=1, sticky="ew", padx=6)
        ttk.Label(editor, text="Kiểu lịch").grid(row=0, column=2, sticky="w")
        schedule_combo = ttk.Combobox(
            editor,
            textvariable=self.plan_schedule_type_var,
            values=("manual", "once", "daily"),
            state="readonly",
            width=10,
        )
        schedule_combo.grid(row=0, column=3, padx=6)
        ttk.Label(editor, text="Thời gian").grid(row=0, column=4, sticky="w")
        ttk.Entry(editor, textvariable=self.plan_schedule_value_var, width=18).grid(row=0, column=5, padx=6)
        ttk.Checkbutton(editor, text="Đang bật", variable=self.plan_enabled_var).grid(row=0, column=6)
        editor.columnconfigure(1, weight=1)

        ttk.Label(
            editor,
            text="once: YYYY-MM-DD HH:MM  •  daily: HH:MM  •  manual: không dùng thời gian",
        ).grid(row=1, column=0, columnspan=7, sticky="w", pady=(6, 4))
        self.plan_items_text = tk.Text(editor, height=4, wrap=tk.NONE, undo=True)
        self.plan_items_text.grid(row=2, column=0, columnspan=7, sticky="ew")

        buttons = ttk.Frame(editor)
        buttons.grid(row=3, column=0, columnspan=7, sticky="w", pady=(8, 0))
        ttk.Button(buttons, text="Thêm workflow đang chọn", command=self.add_selected_workflow_to_plan).pack(side=tk.LEFT)
        ttk.Button(buttons, text="Lưu / ghi đè kế hoạch", command=self.save_automation_plan).pack(side=tk.LEFT, padx=5)
        ttk.Button(buttons, text="Làm mới", command=self.request_automation_plans).pack(side=tk.LEFT)

        actions = ttk.Frame(parent)
        actions.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(actions, text="Nạp để sửa", command=self.load_automation_plan).pack(side=tk.LEFT)
        ttk.Button(actions, text="Chạy ngay", command=self.run_automation_plan).pack(side=tk.LEFT, padx=5)
        ttk.Button(actions, text="Bật / tắt", command=self.toggle_automation_plan).pack(side=tk.LEFT)
        ttk.Button(actions, text="Xoá kế hoạch", command=self.remove_automation_plan).pack(side=tk.LEFT, padx=5)

        columns = ("name", "items", "schedule", "enabled")
        tree_frame = ttk.Frame(parent)
        tree_frame.pack(fill=tk.BOTH, expand=True, pady=8)
        self.automation_plan_tree = ttk.Treeview(tree_frame, columns=columns, show="headings")
        for column, title, width in (
            ("name", "Tên", 180), ("items", "Workflow × lượt", 520),
            ("schedule", "Lịch", 250), ("enabled", "Bật", 70),
        ):
            self.automation_plan_tree.heading(column, text=title)
            self.automation_plan_tree.column(column, width=width, minwidth=70)
        scroll = ttk.Scrollbar(tree_frame, orient=tk.VERTICAL, command=self.automation_plan_tree.yview)
        self.automation_plan_tree.configure(yscrollcommand=scroll.set)
        self.automation_plan_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.automation_plan_tree.bind("<Double-1>", lambda _event: self.load_automation_plan())

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
        ttk.Button(toolbar, text="Phóng to lấy mẫu", command=self.open_sample_zoom).pack(
            side=tk.LEFT, padx=4
        )
        ttk.Label(toolbar, textvariable=self.stream_status_var).pack(side=tk.RIGHT)

        samplebar = ttk.LabelFrame(parent, text="Tạo mẫu tìm ảnh từ frame gốc", padding=5)
        samplebar.pack(fill=tk.X, pady=(0, 6))
        for label, variable, width in (
            ("Tên", self.sample_name_var, 16),
            ("Ngưỡng", self.sample_threshold_var, 6),
            ("ROI ±px", self.sample_margin_var, 7),
        ):
            ttk.Label(samplebar, text=label).pack(side=tk.LEFT, padx=(4, 2))
            ttk.Entry(samplebar, textvariable=variable, width=width).pack(side=tk.LEFT)
        ttk.Button(
            samplebar,
            textvariable=self.sample_button_var,
            command=self.toggle_sample_mode,
        ).pack(side=tk.LEFT, padx=8)
        ttk.Label(
            samplebar,
            text="Bật rồi kéo chuột khoanh đối tượng trên một màn hình",
        ).pack(side=tk.LEFT)

        target_box = ttk.LabelFrame(parent, text="Ảnh mẫu đã lưu", padding=5)
        target_box.pack(fill=tk.X, pady=(0, 6))
        target_actions = ttk.Frame(target_box)
        target_actions.pack(side=tk.LEFT, fill=tk.Y, padx=(0, 6))
        ttk.Button(target_actions, text="Tải danh sách", command=self.request_images).pack(fill=tk.X)
        ttk.Button(
            target_actions,
            text="Chọn tất cả",
            command=lambda: self.select_all_image_targets(True),
        ).pack(fill=tk.X, pady=(3, 0))
        ttk.Button(
            target_actions,
            text="Bỏ chọn",
            command=lambda: self.select_all_image_targets(False),
        ).pack(fill=tk.X, pady=(3, 0))
        ttk.Button(target_actions, text="Xóa mục đã tích", command=self.remove_selected_images).pack(
            fill=tk.X, pady=(3, 0)
        )
        self.image_target_tree = ttk.Treeview(
            target_box,
            columns=("selected", "device", "name"),
            show="headings",
            height=4,
        )
        self.image_target_tree.heading("selected", text="Xóa")
        self.image_target_tree.heading("device", text="Thiết bị")
        self.image_target_tree.heading("name", text="Tên ảnh mẫu")
        self.image_target_tree.column("selected", width=48, minwidth=48, stretch=False, anchor=tk.CENTER)
        self.image_target_tree.column("device", width=260, minwidth=140, stretch=True)
        self.image_target_tree.column("name", width=220, minwidth=120, stretch=True)
        self.image_target_tree.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.image_target_tree.bind("<Button-1>", self.toggle_image_target)

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

    def _one_selected_client(self):
        targets = self._selected_clients()
        if len(targets) != 1:
            messagebox.showwarning(
                "Chọn điện thoại",
                "Chức năng thư viện yêu cầu chọn đúng 1 điện thoại.",
            )
            return None
        return targets[0]

    def _send_library_command(self, payload):
        client_id = self._one_selected_client()
        if client_id is None:
            return None
        self.backend.send(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            [client_id],
        )
        return client_id

    def request_app_profiles(self):
        self._send_library_command({"cmd": "app_profile_list"})

    def request_saved_workflows(self):
        self._send_library_command({"cmd": "workflow_list"})

    def save_workflow_to_phone(self):
        name = self.saved_workflow_name_var.get().strip()
        script = self.workflow_text.get("1.0", tk.END).strip()
        if not name or not script:
            messagebox.showwarning("Thiếu dữ liệu", "Cần nhập tên và chuỗi workflow.")
            return
        payload = {"cmd": "workflow_save", "name": name, "script": script}
        target = self.app_profile_targets.get(self.app_profile_var.get())
        if target:
            payload["package_name"] = target["package_name"]
            payload["profile_serial"] = target["profile_serial"]
        self._send_library_command(payload)

    def _selected_saved_workflow(self):
        selected = self.saved_workflow_tree.selection()
        if not selected:
            messagebox.showwarning("Chọn workflow", "Hãy chọn một workflow trong danh sách.")
            return None
        return self.saved_workflow_rows.get(selected[0])

    def load_saved_workflow(self):
        workflow = self._selected_saved_workflow()
        if not workflow:
            return
        self.saved_workflow_name_var.set(workflow.get("name", ""))
        self.workflow_text.delete("1.0", tk.END)
        self.workflow_text.insert("1.0", workflow.get("script", ""))
        package_name = workflow.get("package_name")
        profile_serial = workflow.get("profile_serial")
        display = "Không tự mở ứng dụng"
        for label, target in self.app_profile_targets.items():
            if target.get("package_name") == package_name and target.get("profile_serial") == profile_serial:
                display = label
                break
        self.app_profile_var.set(display)

    def run_saved_workflow(self):
        workflow = self._selected_saved_workflow()
        if not workflow:
            return
        request_id = f"pc-{next(self.command_ids)}"
        self._send_library_command(
            {"cmd": "workflow_run_saved", "id": request_id, "name": workflow["name"]}
        )

    def remove_saved_workflow(self):
        workflow = self._selected_saved_workflow()
        if not workflow:
            return
        if not messagebox.askyesno("Xoá workflow", f"Xoá '{workflow['name']}' khỏi điện thoại?"):
            return
        self._send_library_command({"cmd": "workflow_remove", "name": workflow["name"]})

    def add_selected_workflow_to_plan(self):
        workflow = self._selected_saved_workflow()
        if not workflow:
            return
        current = self.plan_items_text.get("1.0", tk.END).strip()
        line = f"{workflow['name']}|1"
        self.plan_items_text.delete("1.0", tk.END)
        self.plan_items_text.insert("1.0", f"{current}\n{line}" if current else line)

    def request_automation_plans(self):
        self._send_library_command({"cmd": "automation_plan_list"})

    def _parse_plan_items(self):
        items = []
        for raw_line in self.plan_items_text.get("1.0", tk.END).splitlines():
            line = raw_line.strip()
            if not line:
                continue
            name, separator, repeat_text = line.rpartition("|")
            if not separator or not name.strip():
                raise ValueError(f"Dòng không hợp lệ: {line}")
            repetitions = int(repeat_text.strip())
            if not 1 <= repetitions <= 100:
                raise ValueError("Số lượt mỗi workflow phải từ 1 đến 100")
            items.append({"workflow": name.strip(), "repeat": repetitions})
        if not items:
            raise ValueError("Kế hoạch phải có ít nhất một workflow")
        if sum(item["repeat"] for item in items) > 1000:
            raise ValueError("Tổng số lượt không được vượt quá 1000")
        return items

    def save_automation_plan(self):
        try:
            items = self._parse_plan_items()
            payload = {
                "cmd": "automation_plan_save",
                "name": self.plan_name_var.get().strip(),
                "items": items,
                "schedule_type": self.plan_schedule_type_var.get(),
                "enabled": self.plan_enabled_var.get(),
            }
            if not payload["name"]:
                raise ValueError("Cần nhập tên kế hoạch")
            schedule_value = self.plan_schedule_value_var.get().strip()
            if payload["schedule_type"] == "once":
                moment = datetime.strptime(schedule_value, "%Y-%m-%d %H:%M")
                if moment.timestamp() <= time.time():
                    raise ValueError("Thời gian chạy một lần phải ở tương lai")
                payload["run_at_ms"] = int(moment.timestamp() * 1000)
            elif payload["schedule_type"] == "daily":
                moment = datetime.strptime(schedule_value, "%H:%M")
                payload["hour"] = moment.hour
                payload["minute"] = moment.minute
        except (ValueError, TypeError) as error:
            messagebox.showerror("Kế hoạch không hợp lệ", str(error))
            return
        self._send_library_command(payload)

    def _selected_automation_plan(self):
        selected = self.automation_plan_tree.selection()
        if not selected:
            messagebox.showwarning("Chọn kế hoạch", "Hãy chọn một kế hoạch trong danh sách.")
            return None
        return self.automation_plan_rows.get(selected[0])

    def load_automation_plan(self):
        plan = self._selected_automation_plan()
        if not plan:
            return
        self.plan_name_var.set(plan.get("name", ""))
        self.plan_schedule_type_var.set(plan.get("schedule_type", "manual"))
        self.plan_enabled_var.set(bool(plan.get("enabled", True)))
        if plan.get("schedule_type") == "once" and plan.get("run_at_ms"):
            value = datetime.fromtimestamp(plan["run_at_ms"] / 1000).strftime("%Y-%m-%d %H:%M")
        elif plan.get("schedule_type") == "daily":
            value = f"{int(plan.get('hour', 0)):02d}:{int(plan.get('minute', 0)):02d}"
        else:
            value = ""
        self.plan_schedule_value_var.set(value)
        lines = [f"{item['workflow']}|{item['repeat']}" for item in plan.get("items", [])]
        self.plan_items_text.delete("1.0", tk.END)
        self.plan_items_text.insert("1.0", "\n".join(lines))

    def run_automation_plan(self):
        plan = self._selected_automation_plan()
        if plan:
            self._send_library_command({"cmd": "automation_plan_run", "name": plan["name"]})

    def toggle_automation_plan(self):
        plan = self._selected_automation_plan()
        if plan:
            self._send_library_command({
                "cmd": "automation_plan_enable",
                "name": plan["name"],
                "enabled": not bool(plan.get("enabled", True)),
            })

    def remove_automation_plan(self):
        plan = self._selected_automation_plan()
        if not plan:
            return
        if messagebox.askyesno("Xoá kế hoạch", f"Xoá kế hoạch '{plan['name']}'?"):
            self._send_library_command({"cmd": "automation_plan_remove", "name": plan["name"]})

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
        targets = self._selected_clients()
        self.image_target_rows.clear()
        self.image_target_tree.delete(*self.image_target_tree.get_children())
        self.backend.send(json.dumps({"cmd": "image_list"}), targets)

    def toggle_image_target(self, event):
        if self.image_target_tree.identify_column(event.x) != "#1":
            return
        item = self.image_target_tree.identify_row(event.y)
        if not item:
            return
        values = self.image_target_tree.item(item, "values")
        selected = values[0] != "☑"
        self.image_target_tree.set(item, "selected", "☑" if selected else "☐")
        for key, row in self.image_target_rows.items():
            if row["item"] == item:
                row["selected"] = selected
                break

    def select_all_image_targets(self, selected):
        for row in self.image_target_rows.values():
            row["selected"] = selected
            self.image_target_tree.set(row["item"], "selected", "☑" if selected else "☐")

    def remove_selected_images(self):
        selected = [key for key, row in self.image_target_rows.items() if row["selected"]]
        if not selected:
            messagebox.showinfo("Xóa ảnh mẫu", "Chưa tích ảnh mẫu cần xóa")
            return
        if not messagebox.askyesno("Xóa ảnh mẫu", f"Xóa {len(selected)} ảnh mẫu đã tích?"):
            return
        for client_id, name in selected:
            self.backend.send(
                json.dumps({"cmd": "image_remove", "name": name}, ensure_ascii=False),
                [client_id],
            )

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
            self.device_labels[client_id] = label
            widget = self.device_widgets.get(client_id)
            if widget is not None:
                widget.configure(text=f"{client_id} • {label}")
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
        for key in [key for key in self.image_target_rows if key[0] == client_id]:
            row = self.image_target_rows.pop(key)
            self.image_target_tree.delete(row["item"])
        screen = self.screen_labels.pop(client_id, None)
        if screen:
            screen.master.destroy()
        self.screen_images.pop(client_id, None)
        self.screen_source_images.pop(client_id, None)
        self.screen_geometry.pop(client_id, None)
        self._close_sample_zoom(client_id)
        with self.frame_lock:
            self.pending_frames.pop(client_id, None)
        self._schedule_screen_render()
        for key in [key for key in self.pending if key[1] == client_id]:
            self.pending.pop(key, None)
        self.active_image_requests.pop(client_id, None)
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
            details = ""
            if len(event) > 2 and event[2] is not None:
                details += f" code={event[2]}"
            if len(event) > 3 and event[3]:
                details += f" reason={event[3]}"
            self._log(f"DISCONNECTED {event[1]} • {label}{details}")
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
        elif message_type == "image_put":
            if obj.get("success"):
                self.stream_status_var.set(
                    f"{client_id}: đã tạo mẫu {obj.get('name')} "
                    f"({obj.get('width')}x{obj.get('height')})"
                )
            self._log(f"[{client_id}] IMAGE PUT: {json.dumps(obj, ensure_ascii=False)}")
        elif message_type == "image_list":
            self._show_image_targets(client_id, obj.get("targets", []))
            self._log(
                f"[{client_id}] IMAGE LIST: {len(obj.get('targets', []))} mẫu "
                f"capture_running={obj.get('capture_running')}"
            )
        elif message_type == "image_remove":
            name = str(obj.get("name", ""))
            if obj.get("success"):
                row = self.image_target_rows.pop((client_id, name), None)
                if row is not None:
                    self.image_target_tree.delete(row["item"])
            self._log(f"[{client_id}] IMAGE REMOVE: {json.dumps(obj, ensure_ascii=False)}")
        elif message_type == "image_click_timing":
            active = self.active_image_requests.get(client_id)
            if active and active.get("request_id") == str(obj.get("request_id")):
                active["reported"] = True
            self._log(
                f"[{client_id} {obj.get('request_id')}] IMAGE CLICK TIMING "
                f"name={obj.get('name')} find={obj.get('find_ms')}ms "
                f"match_to_dispatch={obj.get('match_to_dispatch_ms')}ms "
                f"gesture={obj.get('gesture_ms')}ms "
                f"match_to_click={obj.get('match_to_click_ms')}ms "
                f"total={obj.get('total_ms')}ms success={obj.get('success')}"
            )
        elif message_type == "automation_mode":
            state = str(obj.get("state", "unknown")).upper()
            self.device_summary_var.set(f"{client_id}: automation {state}")
            self._log(f"[{client_id}] AUTOMATION {state}")
        elif message_type == "app_profile_list":
            self._show_app_profiles(client_id, obj.get("apps", []))
        elif message_type == "workflow_list":
            self._show_saved_workflows(client_id, obj.get("workflows", []))
        elif message_type in ("workflow_save", "workflow_remove"):
            self._log(f"[{client_id}] {message_type.upper()}: {json.dumps(obj, ensure_ascii=False)}")
            if obj.get("success"):
                self.backend.send(json.dumps({"cmd": "workflow_list"}), [client_id])
        elif message_type == "automation_plan_list":
            self._show_automation_plans(client_id, obj.get("plans", []))
        elif message_type in (
            "automation_plan_save", "automation_plan_remove", "automation_plan_enable",
        ):
            self._log(f"[{client_id}] {message_type.upper()}: {json.dumps(obj, ensure_ascii=False)}")
            if obj.get("success"):
                self.backend.send(json.dumps({"cmd": "automation_plan_list"}), [client_id])
        elif message_type in ("automation_plan_run", "automation_plan"):
            self._log(f"[{client_id}] PLAN: {json.dumps(obj, ensure_ascii=False)}")
        elif message_type == "image_match":
            active = self.active_image_requests.get(client_id)
            timestamp_ms = obj.get("timestamp_ms")
            if active and isinstance(timestamp_ms, (int, float)):
                active["matched_phone_ms"] = timestamp_ms
            request_id = active.get("request_id") if active else "?"
            self._log(
                f"[{client_id} {request_id}] IMAGE MATCH name={obj.get('name')} "
                f"score={obj.get('score')} phone_ms={timestamp_ms} "
                f"point={obj.get('x')},{obj.get('y')}"
            )
        elif message_type == "ready":
            self.device_summary_var.set(f"Thiết bị sẵn sàng: {len(self.device_vars)}")
            self._log(f"[{client_id} • {label}] READY: {json.dumps(obj, ensure_ascii=False)}")
        elif message_type == "workflow":
            request_id = obj.get("request_id")
            if obj.get("command") == "CLICK_IMG" and request_id is not None:
                request_id = str(request_id)
                active = self.active_image_requests.get(client_id)
                if not active or active.get("request_id") != request_id:
                    self.active_image_requests[client_id] = {
                        "request_id": request_id,
                        "name": obj.get("target"),
                        "matched_phone_ms": None,
                        "reported": False,
                    }
            self._log(
                f"[{client_id} • {label}] WORKFLOW "
                f"id={obj.get('request_id')} state={obj.get('state')} "
                f"step={obj.get('step')}/{obj.get('total')} "
                f"command={obj.get('command')} target={obj.get('target')} "
                f"error={obj.get('error')}"
            )
        else:
            self._log(f"[{client_id} • {label}] PHONE: {json.dumps(obj, ensure_ascii=False)}")

    def _show_image_targets(self, client_id, targets):
        label = self.device_labels.get(client_id, client_id)
        for key in [key for key in self.image_target_rows if key[0] == client_id]:
            row = self.image_target_rows.pop(key)
            self.image_target_tree.delete(row["item"])
        for name in sorted({str(value).strip() for value in targets if str(value).strip()}):
            item = self.image_target_tree.insert(
                "",
                tk.END,
                values=("☐", f"{client_id} • {label}", name),
            )
            self.image_target_rows[(client_id, name)] = {
                "item": item,
                "selected": False,
            }

    def _show_app_profiles(self, client_id, apps):
        targets = {}
        for app in apps if isinstance(apps, list) else []:
            if not isinstance(app, dict):
                continue
            display = (
                f"{app.get('label') or app.get('package_name')} — "
                f"{app.get('profile_label')} [{app.get('profile_serial')}] — "
                f"{app.get('package_name')}"
            )
            targets[display] = app
        self.app_profile_targets = targets
        values = ["Không tự mở ứng dụng", *sorted(targets, key=str.casefold)]
        self.app_profile_combo["values"] = values
        if self.app_profile_var.get() not in values:
            self.app_profile_var.set(values[0])
        profiles = {(app.get("profile_label"), app.get("profile_serial")) for app in targets.values()}
        self._log(f"[{client_id}] APP/PROFILE: {len(targets)} app, {len(profiles)} profile")

    def _show_saved_workflows(self, client_id, workflows):
        for item in self.saved_workflow_tree.get_children():
            self.saved_workflow_tree.delete(item)
        self.saved_workflow_rows.clear()
        for workflow in workflows if isinstance(workflows, list) else []:
            if not isinstance(workflow, dict):
                continue
            item = self.saved_workflow_tree.insert(
                "",
                tk.END,
                values=(
                    workflow.get("name", ""),
                    workflow.get("script", ""),
                    workflow.get("package_name") or "",
                    "" if workflow.get("profile_serial") is None else workflow.get("profile_serial"),
                ),
            )
            self.saved_workflow_rows[item] = workflow
        self._log(f"[{client_id}] WORKFLOW LIBRARY: {len(self.saved_workflow_rows)} mục")

    def _show_automation_plans(self, client_id, plans):
        for item in self.automation_plan_tree.get_children():
            self.automation_plan_tree.delete(item)
        self.automation_plan_rows.clear()
        for plan in plans if isinstance(plans, list) else []:
            if not isinstance(plan, dict):
                continue
            items = ", ".join(
                f"{entry.get('workflow')} ×{entry.get('repeat')}" for entry in plan.get("items", [])
            )
            schedule_type = plan.get("schedule_type")
            if schedule_type == "once" and plan.get("run_at_ms"):
                schedule = "Một lần " + datetime.fromtimestamp(
                    plan["run_at_ms"] / 1000
                ).strftime("%Y-%m-%d %H:%M")
            elif schedule_type == "daily":
                schedule = f"Hằng ngày {int(plan.get('hour', 0)):02d}:{int(plan.get('minute', 0)):02d}"
            else:
                schedule = "Chạy thủ công"
            row = self.automation_plan_tree.insert(
                "", tk.END,
                values=(plan.get("name", ""), items, schedule, "Có" if plan.get("enabled") else "Không"),
            )
            self.automation_plan_rows[row] = plan
        self._log(f"[{client_id}] AUTOMATION PLANS: {len(self.automation_plan_rows)} kế hoạch")

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
            widget = tk.Canvas(frame, background="#202020", highlightthickness=0)
            widget.pack(fill=tk.BOTH, expand=True)
            widget.bind(
                "<Button-1>",
                lambda event, device=client_id: self._screen_pointer_down(device, event),
            )
            widget.bind(
                "<B1-Motion>",
                lambda event, device=client_id: self._screen_pointer_drag(device, event),
            )
            widget.bind(
                "<ButtonRelease-1>",
                lambda event, device=client_id: self._screen_pointer_up(device, event),
            )
            widget.configure(cursor="hand2")
            self.screen_labels[client_id] = widget
        self.screen_source_images[client_id] = image
        self.screen_geometry[client_id] = (source_width, source_height, image.width, image.height)
        self._render_screen_image(client_id)
        self._render_zoom_image(client_id)
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
        widget.delete("frame_image")
        widget.create_image(
            widget.winfo_width() // 2,
            widget.winfo_height() // 2,
            image=photo,
            anchor=tk.CENTER,
            tags="frame_image",
        )
        widget.tag_lower("frame_image")
        source_width, source_height, _, _ = self.screen_geometry[client_id]
        self.screen_geometry[client_id] = (source_width, source_height, width, height)

    def open_sample_zoom(self):
        targets = [client_id for client_id in self._selected_clients() if client_id in self.screen_source_images]
        if len(targets) != 1:
            messagebox.showinfo(
                "Phóng to lấy mẫu",
                "Hãy chọn đúng 1 điện thoại đang hiển thị màn hình.",
            )
            return
        client_id = targets[0]
        existing = self.zoom_windows.get(client_id)
        if existing is not None and existing.winfo_exists():
            existing.lift()
            existing.focus_force()
            return

        window = tk.Toplevel(self.root)
        window.title(f"Phóng to lấy mẫu • {client_id}")
        width = min(700, max(480, self.root.winfo_screenwidth() - 120))
        height = min(900, max(620, self.root.winfo_screenheight() - 100))
        window.geometry(f"{width}x{height}")
        ttk.Label(
            window,
            text="Bật ‘Tạo ảnh mẫu’ ở cửa sổ chính rồi kéo khoanh trực tiếp trên ảnh này.",
            padding=6,
        ).pack(fill=tk.X)
        canvas = tk.Canvas(window, background="#202020", highlightthickness=0)
        canvas.pack(fill=tk.BOTH, expand=True)
        canvas.configure(cursor="crosshair" if self.sample_mode else "hand2")
        canvas.bind("<Configure>", lambda _event, device=client_id: self._render_zoom_image(device))
        canvas.bind("<Button-1>", lambda event, device=client_id: self._zoom_pointer_down(device, event))
        canvas.bind("<B1-Motion>", lambda event, device=client_id: self._zoom_pointer_drag(device, event))
        canvas.bind(
            "<ButtonRelease-1>",
            lambda event, device=client_id: self._zoom_pointer_up(device, event),
        )
        window.protocol("WM_DELETE_WINDOW", lambda device=client_id: self._close_sample_zoom(device))
        self.zoom_windows[client_id] = window
        self.zoom_canvases[client_id] = canvas
        window.update_idletasks()
        self._render_zoom_image(client_id)

    def _close_sample_zoom(self, client_id):
        window = self.zoom_windows.pop(client_id, None)
        self.zoom_canvases.pop(client_id, None)
        self.zoom_images.pop(client_id, None)
        self.zoom_geometry.pop(client_id, None)
        if self.zoom_sample_drag and self.zoom_sample_drag[0] == client_id:
            self.zoom_sample_drag = None
        if window is not None and window.winfo_exists():
            window.destroy()

    def _render_zoom_image(self, client_id):
        image = self.screen_source_images.get(client_id)
        canvas = self.zoom_canvases.get(client_id)
        source_geometry = self.screen_geometry.get(client_id)
        if image is None or canvas is None or source_geometry is None or not canvas.winfo_exists():
            return
        available_width = max(100, canvas.winfo_width() - 12)
        available_height = max(100, canvas.winfo_height() - 12)
        width, height = self._fit_screen_size(
            image.width,
            image.height,
            available_width,
            available_height,
            1.0,
        )
        rendered = image if (width, height) == image.size else image.resize(
            (width, height), Image.Resampling.BILINEAR
        )
        photo = ImageTk.PhotoImage(rendered)
        self.zoom_images[client_id] = photo
        canvas.delete("frame_image")
        canvas.create_image(
            canvas.winfo_width() // 2,
            canvas.winfo_height() // 2,
            image=photo,
            anchor=tk.CENTER,
            tags="frame_image",
        )
        canvas.tag_lower("frame_image")
        source_width, source_height, _, _ = source_geometry
        self.zoom_geometry[client_id] = (source_width, source_height, width, height)

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
        mapped = self._screen_point_to_source(client_id, event.x, event.y)
        if mapped is None:
            return
        target_x, target_y, image_x, image_y = mapped
        self._log(f"[{client_id}] FRAME CLICK ({image_x},{image_y}) -> TAP:{target_x},{target_y}")
        self.send_workflow(f"TAP:{target_x},{target_y}", [client_id])

    def _screen_point_to_source(self, client_id, x, y):
        geometry = self.screen_geometry.get(client_id)
        widget = self.screen_labels.get(client_id)
        return self._point_to_source(widget, geometry, x, y)

    @staticmethod
    def _point_to_source(widget, geometry, x, y):
        if geometry is None or widget is None:
            return None
        source_width, source_height, rendered_width, rendered_height = geometry
        left = max(0, (widget.winfo_width() - rendered_width) // 2)
        top = max(0, (widget.winfo_height() - rendered_height) // 2)
        image_x = x - left
        image_y = y - top
        if not (0 <= image_x < rendered_width and 0 <= image_y < rendered_height):
            return None
        target_x = min(source_width - 1, round(image_x * source_width / rendered_width))
        target_y = min(source_height - 1, round(image_y * source_height / rendered_height))
        return target_x, target_y, image_x, image_y

    def _zoom_point_to_source(self, client_id, x, y):
        return self._point_to_source(
            self.zoom_canvases.get(client_id),
            self.zoom_geometry.get(client_id),
            x,
            y,
        )

    def _zoom_pointer_down(self, client_id, event):
        mapped = self._zoom_point_to_source(client_id, event.x, event.y)
        if mapped is None:
            return
        if not self.sample_mode:
            target_x, target_y, image_x, image_y = mapped
            self._log(f"[{client_id}] ZOOM CLICK ({image_x},{image_y}) -> TAP:{target_x},{target_y}")
            self.send_workflow(f"TAP:{target_x},{target_y}", [client_id])
            return
        self.zoom_sample_drag = (client_id, event.x, event.y)

    def _zoom_pointer_drag(self, client_id, event):
        if not self.sample_mode or not self.zoom_sample_drag or self.zoom_sample_drag[0] != client_id:
            return
        canvas = self.zoom_canvases.get(client_id)
        if canvas is None:
            return
        _, start_x, start_y = self.zoom_sample_drag
        canvas.delete("sample_selection")
        canvas.create_rectangle(
            start_x,
            start_y,
            event.x,
            event.y,
            outline="#ff4040",
            width=2,
            tags="sample_selection",
        )

    def _zoom_pointer_up(self, client_id, event):
        if not self.sample_mode or not self.zoom_sample_drag or self.zoom_sample_drag[0] != client_id:
            return
        _, start_x, start_y = self.zoom_sample_drag
        self.zoom_sample_drag = None
        first = self._zoom_point_to_source(client_id, start_x, start_y)
        second = self._zoom_point_to_source(client_id, event.x, event.y)
        if first is None or second is None:
            return
        self._submit_image_sample(client_id, first, second)

    def toggle_sample_mode(self):
        if not self.sample_mode:
            name = self.sample_name_var.get().strip()
            try:
                threshold = float(self.sample_threshold_var.get())
                margin = int(self.sample_margin_var.get())
            except ValueError:
                messagebox.showerror("Tạo ảnh mẫu", "Ngưỡng và ROI phải là số")
                return
            if not name:
                messagebox.showerror("Tạo ảnh mẫu", "Tên mẫu không được để trống")
                return
            if not 0.50 <= threshold <= 0.999 or not 0 <= margin <= 5000:
                messagebox.showerror("Tạo ảnh mẫu", "Ngưỡng 0.50–0.999; ROI 0–5000 px")
                return
        self.sample_mode = not self.sample_mode
        self.sample_drag = None
        self.sample_button_var.set("Hủy tạo mẫu" if self.sample_mode else "Tạo ảnh mẫu")
        self.stream_status_var.set(
            "Kéo khoanh vùng ảnh mẫu" if self.sample_mode else "Đã hủy tạo ảnh mẫu"
        )
        for widget in self.screen_labels.values():
            widget.delete("sample_selection")
            widget.configure(cursor="crosshair" if self.sample_mode else "hand2")
        for widget in self.zoom_canvases.values():
            widget.delete("sample_selection")
            widget.configure(cursor="crosshair" if self.sample_mode else "hand2")

    def _screen_pointer_down(self, client_id, event):
        if not self.sample_mode:
            self._click_screen(client_id, event)
            return
        if self._screen_point_to_source(client_id, event.x, event.y) is None:
            return
        self.sample_drag = (client_id, event.x, event.y)

    def _screen_pointer_drag(self, client_id, event):
        if not self.sample_mode or not self.sample_drag or self.sample_drag[0] != client_id:
            return
        widget = self.screen_labels[client_id]
        _, start_x, start_y = self.sample_drag
        widget.delete("sample_selection")
        widget.create_rectangle(
            start_x,
            start_y,
            event.x,
            event.y,
            outline="#ff4040",
            width=2,
            tags="sample_selection",
        )

    def _screen_pointer_up(self, client_id, event):
        if not self.sample_mode or not self.sample_drag or self.sample_drag[0] != client_id:
            return
        _, start_x, start_y = self.sample_drag
        self.sample_drag = None
        first = self._screen_point_to_source(client_id, start_x, start_y)
        second = self._screen_point_to_source(client_id, event.x, event.y)
        if first is None or second is None:
            return
        self._submit_image_sample(client_id, first, second)

    def _submit_image_sample(self, client_id, first, second):
        left, right = sorted((first[0], second[0]))
        top, bottom = sorted((first[1], second[1]))
        right += 1
        bottom += 1
        if right - left < 4 or bottom - top < 4:
            messagebox.showerror("Tạo ảnh mẫu", "Vùng chọn quá nhỏ")
            return
        source_width, source_height, _, _ = self.screen_geometry[client_id]
        margin = int(self.sample_margin_var.get())
        payload = {
            "cmd": "image_capture_put",
            "name": self.sample_name_var.get().strip(),
            "threshold": float(self.sample_threshold_var.get()),
            "template": {"left": left, "top": top, "right": right, "bottom": bottom},
            "roi": {
                "left": max(0, left - margin),
                "top": max(0, top - margin),
                "right": min(source_width, right + margin),
                "bottom": min(source_height, bottom + margin),
            },
        }
        self.backend.send(json.dumps(payload, ensure_ascii=False), [client_id])
        self._log(
            f"[{client_id}] CREATE IMAGE {payload['name']} template={left},{top},{right},{bottom} "
            f"roi=±{margin}px threshold={payload['threshold']}"
        )
        self.sample_mode = False
        self.sample_button_var.set("Tạo ảnh mẫu")
        self.stream_status_var.set(f"{client_id}: đang tạo mẫu {payload['name']}…")
        for widget in self.screen_labels.values():
            widget.delete("sample_selection")
            widget.configure(cursor="hand2")
        for widget in self.zoom_canvases.values():
            widget.delete("sample_selection")
            widget.configure(cursor="hand2")

    def _handle_ack(self, client_id, obj):
        request_id = str(obj.get("id", "?"))
        state = str(obj.get("state", "?"))
        phone_ms = obj.get("phone_ms")
        active = self.active_image_requests.get(client_id)
        timing_details = ""
        if (
            state in {"completed", "failed", "stopped", "cancelled"}
            and active
            and active.get("request_id") == request_id
        ):
            matched_phone_ms = active.get("matched_phone_ms")
            if (
                state == "completed"
                and not active.get("reported")
                and isinstance(phone_ms, (int, float))
                and isinstance(matched_phone_ms, (int, float))
            ):
                timing_details = (
                    f" detect_to_complete={phone_ms - matched_phone_ms:.1f}ms"
                )
            self.active_image_requests.pop(client_id, None)
        item = self.pending.get((request_id, client_id))
        prefix = f"[{client_id} {request_id}]"
        if not item:
            self._log(f"{prefix} {state.upper()} phone_ms={phone_ms}{timing_details}")
            return
        elapsed = (time.perf_counter() - item["sent"]) * 1000.0
        details = timing_details
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
        self.notebook.select(self.nodes_tab)
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
