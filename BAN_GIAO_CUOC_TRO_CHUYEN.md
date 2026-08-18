# Bàn giao dự án Trợ năng App

Tài liệu này tổng hợp bối cảnh, mục tiêu và trạng thái dự án sau toàn bộ chuỗi trao đổi. Khi mở cuộc trò chuyện mới, yêu cầu Codex đọc file này trước rồi chỉ kiểm tra các commit mới hơn mốc cuối, không rà soát dự án lại từ đầu.

## 1. Thông tin nền

- Repo: https://github.com/banupham/tronangapp
- Thư mục Windows: C:\Users\duong\Documents\GitHub\tronangapp
- Nhánh: main
- Phiên bản hiện tại: 0.26.0, versionCode 30
- Package Android: vn.banupham.tronangapp
- Android tối thiểu: API 29
- WebSocket thường dùng cổng 8770
- IP người dùng đã chỉ định: 100.95.138.104; hotspot từng dùng dải 192.168.43.x
- Device ID đã thấy: ba792cd94dfedd30
- APK dùng khóa ký cố định trong GitHub Actions. Không tạo khóa mới và không đổi chữ ký.
- SHA-256 chứng thư được CI kiểm tra: 8a71cb90446db1157d4112dd6c13a8ef710b4ba40b470937befea9543429ea6b

## 2. Mục tiêu xuyên suốt

1. App trợ năng Android điều khiển qua WebSocket và chạy workflow offline.
2. Tốc độ phản hồi là ưu tiên cao nhất; tính năng mới không được làm chậm workflow cũ.
3. Không quét Accessibility tree liên tục khi không cần.
4. Điều khiển nhiều điện thoại, chọn/bỏ chọn trên GUI Windows.
5. Đọc nodes, xem màn hình, click trên frame, tạo/xóa ảnh mẫu và chọn vùng tìm.
6. Lưu mọi workflow; chạy tuần tự; đặt lịch một lần hoặc hằng ngày.
7. Hỗ trợ package và Work Profile/profile serial.
8. Giữ chữ ký APK để cập nhật không phải gỡ app.
9. Có timeout, phục hồi lỗi, checkpoint và chịu được service/process bị tái tạo.
10. WebSocket ổn định khi điện thoại phát hotspot cho PC.

## 3. Kiến trúc quan trọng

Android:

- GenericAccessibilityService.kt: node index, workflow, socket command, kế hoạch và checkpoint.
- WorkflowEngine.kt: parser và state machine workflow.
- RemoteSocketClient.kt: OkHttp WebSocket, ID thiết bị, reconnect, heartbeat.
- ImageTargetRuntime.kt: ảnh mẫu và nhận diện ROI local.
- ScreenCaptureService.kt: MediaProjection, stream và chụp frame.
- SavedWorkflowStore.kt: thư viện workflow offline.
- AutomationPlanStore.kt và AutomationPlanScheduler.kt: kế hoạch/lịch.
- AppProfileLauncher.kt: package và profile serial.

Windows:

- tools/ws_gui.py: GUI Tkinter và WebSocket server.
- tools/ws_server.py: server CLI/entry point GUI.

Socket hiện có ba lớp tách biệt:

- Thread OkHttp nhận/gửi.
- HandlerThread tronangapp-socket-control quản lý reconnect/backoff.
- GUI server dùng asyncio trên thread riêng, không chạy trên Tkinter UI thread.

## 4. Những chức năng đã hoàn thành

### Hiệu năng

- Tree cập nhật theo Accessibility event, debounce, index text/description trong RAM.
- CLICK ưu tiên index, fallback node cha clickable, sau cùng gesture tại tâm node.
- Lệnh realtime được đưa lên đầu main queue.
- Image matching chạy local trên điện thoại.
- Frame stream dùng đường transient và giới hạn queue, không chặn lệnh.
- Pause tự động hủy workflow/image watch và tháo capture surface.

Số đo từng ghi nhận:

- ACK socket khoảng 7–60 ms.
- BACK, HOME, WAIT thường khoảng 70–125 ms.
- Vuốt khoảng 360–440 ms do thời gian gesture.
- CLICK_IMG từng hoàn tất khoảng 125 ms; detect_to_complete khoảng 21 ms.

### Điều khiển và nodes

- UP, DOWN, LEFT, RIGHT, BACK, HOME, RECENTS.
- TAP và SWIPE có tọa độ/thời lượng.
- TAP_PERCENT và SWIPE_PERCENT dùng chung nhiều độ phân giải.
- Nodes có phân trang, lọc, text, description, view ID, class, bounds, enabled, clickable.
- CLICK xử lý trường hợp text ở node con nhưng node cha mới clickable.

### Màn hình và ảnh

- Xem nhiều điện thoại có độ phân giải khác nhau, scale đúng tỉ lệ.
- Click trực tiếp trên frame và ánh xạ về tọa độ nguồn.
- Cửa sổ phóng to để lấy mẫu.
- Kéo tạo ảnh mẫu; chọn vùng tìm riêng hoặc ROI cộng biên.
- Danh sách ảnh mẫu, chọn nhiều và xóa.
- Threshold riêng từng mẫu.
- Matching có verification samples và điểm màu đặc trưng để giảm nhầm ảnh cùng nền.

### Workflow đang hỗ trợ

    CLICK:text
    WAIT:text
    WAIT_TIMEOUT:text|seconds
    CLICK_TIME
    IF_TIME:LABEL
    IF_NOT_TIME:LABEL
    WAIT_TIME
    WAIT_TIME_RANDOM:min,max
    WAIT_IMG:name
    WAIT_IMG_TIMEOUT:name|seconds
    WAIT_ANY_IMG:name1,name2|seconds|TIMEOUT_LABEL
    CLICK_IMG:name
    CLICK_IMG_TIMEOUT:name|seconds
    OPEN_APP:package
    OPEN_APP:package|profileSerial
    SLEEP:seconds
    SLEEP_RANDOM:min,max
    TAP:x,y
    TAP_PERCENT:xPercent,yPercent
    SWIPE:x1,y1,x2,y2,durationMs
    SWIPE_PERCENT:x1%,y1%,x2%,y2%,durationMs
    UP;DOWN;LEFT;RIGHT;BACK;HOME;RECENTS
    LABEL:name
    GOTO:name
    IF:text|LABEL
    IF_NOT:text|LABEL
    IF_IMG:image|LABEL
    IF_NOT_IMG:image|LABEL
    LOOP:count
    END_LOOP
    BREAK
    CONTINUE
    SET:NAME=value
    INC:NAME,amount
    IF_VAR:NAME>=value|LABEL
    ON_ERROR:LABEL
    ON_ERROR:OFF
    CHECKPOINT:SAVE
    CHECKPOINT:CLEAR
    REQUIRE_DEVICE:UNLOCKED,CAPTURE,NETWORK,PORTRAIT|LABEL

WAIT_ANY_IMG lưu kết quả vào LAST_IMAGE; lỗi gần nhất ở LAST_ERROR.

REQUIRE_DEVICE hỗ trợ UNLOCKED, CAPTURE, NETWORK, PORTRAIT, LANDSCAPE.

### Thư viện, Work Profile và lịch

- Android lưu nhiều workflow theo tên; chạy/sửa/xóa offline.
- Workflow gắn package và profile serial.
- GUI đồng bộ thư viện workflow qua socket.
- Plan chạy nhiều workflow theo thứ tự và số lượt.
- Lịch manual, once, daily; hiển thị ở Android và GUI.

### Checkpoint và plan nhiều script

- CHECKPOINT:SAVE lưu script, request ID và bước an toàn kế tiếp.
- Service tái tạo sẽ phục hồi checkpoint.
- Workflow terminal sẽ xóa checkpoint.
- Không đặt checkpoint ngay sau thao tác không được phép lặp lại.
- Trạng thái plan được lưu bền: tên, script hiện tại, request ID, số bước và danh sách script còn lại.
- Sau process/service restart, script hiện tại được phục hồi và script sau vẫn chạy.
- Log chuyển bước:

    PLAN state=step_started step=1 remaining=1
    PLAN state=step_started step=2 remaining=0

Nếu script 1 failed, stopped hoặc cancelled thì plan dừng. Chỉ completed mới chạy script 2.

## 5. WebSocket và lỗi reconnect đã sửa

Log xác định nguyên nhân:

    failure:SocketTimeoutException:
    sent ping but didn't receive pong within 5000ms
    (after 9 successful ping/pongs)

Heartbeat 5 giây đồng thời là pong timeout 5 giây, quá gắt cho hotspot. Bản 0.26.0 đã:

- Đổi Android ping/pong timeout thành 30 giây.
- Tắt ping chủ động phía GUI/CLI server để không ping kép.
- Server close_timeout 5 giây.
- Reconnect chạy HandlerThread riêng.
- Giữ CPU wake lock và Wi-Fi lock khi connected.
- Device ID ổn định; socket mới cùng ID thay socket cũ.
- READY sau reconnect gửi disconnect_reason và downtime_ms.

Khi chẩn đoán, lấy đủ DISCONNECTED, CONNECTED và READY. Phân loại:

- sent ping but didn't receive pong: heartbeat timeout.
- closed:4001:replaced_by_reconnect: socket mới cùng ID thay socket cũ.
- Connection reset/Broken pipe: đường TCP hoặc hotspot reset.
- Không có READY mới: service/server không còn chạy.

## 6. Workflow PineDrama

Package:

    OPEN_APP:com.ss.android.ttmd.video|0

Ảnh mẫu đã dùng: diemdanh, nhan, ok, batdau, dattruoc, baoluu, xem, thanhcong.

Text: phần thưởng, bạn đã nhận.

Lưu ý:

- App đích hiện không đọc được đồng hồ qua Accessibility. Không dùng IF_TIME, WAIT_TIME hoặc WAIT_TIME_RANDOM cho PineDrama.
- Khi không có xem, giữ SLEEP_RANDOM:540,660.
- Đổi WAIT:bạn đã nhận thành WAIT_TIMEOUT:bạn đã nhận|35.
- Dùng CLICK_IMG_TIMEOUT:name|8 sau khi đã vào nhánh.
- TAP:360,1402 trên 720x1600 tương ứng TAP_PERCENT:50,87.625.
- Nếu ok/baoluu đôi khi không xuất hiện, dùng IF_NOT_IMG để biến thành tùy chọn.

## 7. Chế độ máy bay

Accessibility không thể trực tiếp bật/tắt Airplane Mode trên Android hiện đại.

- Accessibility chỉ có thể mở Quick Settings và click tile.
- Điều khiển trực tiếp không click cần Shizuku/ADB, root, app hệ thống hoặc quyền ROM/Device Owner.
- Chưa triển khai AIRPLANE:ON/OFF.
- Nếu bật máy bay làm mất Wi-Fi, socket sẽ ngắt; lệnh tắt phải chạy offline hoặc vẫn giữ Wi-Fi.

## 8. Việc còn thiếu

1. TRY/CATCH/FINALLY đầy đủ; hiện chỉ có ON_ERROR.
2. CLICK_IMG_VERIFY xác minh màn hình đích và retry.
3. Timeout trần riêng cho WAIT_TIME.
4. WAIT_ANY hỗn hợp text + ảnh + countdown.
5. Nhiều ảnh mẫu chung một logical target.
6. Ảnh phủ định và ổn định hai frame.
7. ENSURE_SCREEN/watchdog màn hình đứng, ANR hoặc sai foreground app.
8. Trình dựng workflow dạng khối; GUI hiện mới có danh mục/autocomplete.
9. Device profile gom package, profile, ảnh, ROI, độ phân giải và workflow.
10. Xử lý tổng quát popup, bàn phím, Work Profile khóa, đổi giờ và tiết kiệm pin.

## 9. Commit/mốc build

    e52eb1b Relax hotspot WebSocket heartbeat
    09c4d11 Stabilize socket and persist plan progress
    e8f601d Add workflow timeouts checkpoints and device guards
    ea4733b Add resilient workflow control primitives
    fdeede9 Add dynamic countdown waits and image search ROI
    28d1226 Add inline workflow autocomplete
    3c5a840 Add workflow command suggestions
    738afe3 Add countdown workflow conditions
    6898da5 Keep schedule action buttons visible
    4b416f4 Add enlarged screen sampling window
    c1ee135 Share quick controls across GUI tabs
    18c96ac Add image target deletion UI
    42c67fd Improve image matching for similar backgrounds
    e41fe2a Keep stable device IDs across reconnects
    f706280 Fallback to gesture for rejected text clicks

Build gần nhất thành công:

- Run 32167288467
- https://github.com/banupham/tronangapp/actions/runs/32167288467

## 10. Quy trình tiếp tục

1. Đọc file/hàm liên quan trước khi sửa; giữ thay đổi nhỏ.
2. Không tự đổi dependency, kiến trúc hoặc khóa ký.
3. Kiểm tra:

    python -m py_compile tools/ws_gui.py tools/ws_server.py
    git diff --check

4. Không commit artifact local:

    dist-v019/
    screen-v019-app.png
    screen-v019-plan.png
    screen-v019.png
    window-v019.xml

5. Khi được yêu cầu, commit/push main và theo dõi GitHub Actions đến khi build, verify signature, upload APK đều thành công.
6. Không nói đã test thiết bị nếu mới chỉ build CI.

## 11. Prompt cho cuộc trò chuyện mới

Sao chép:

> Hãy đọc toàn bộ BAN_GIAO_CUOC_TRO_CHUYEN.md, kiểm tra git status và các commit mới hơn e52eb1b. Tiếp tục từ trạng thái hiện tại, không rà soát dự án từ đầu. Giữ ưu tiên tốc độ phản hồi, tương thích ngược, chữ ký APK cố định và không commit artifact local.

Nếu HEAD vẫn là e52eb1b và file này chưa lỗi thời, dùng trực tiếp trạng thái bàn giao.
