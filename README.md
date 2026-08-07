# Trợ năng App

Android Accessibility agent tổng quát, không khóa cứng package, có cây UI/index RAM, WebSocket thường trực, workflow event-driven và tìm ảnh theo ROI ngay trên điện thoại.

## Bản 0.3.0

- Android 10+ (`minSdk 29`).
- Không giới hạn `android:packageNames`.
- Cây Accessibility được cập nhật khi có event và tạo index text/description trong RAM.
- `CLICK` ưu tiên index RAM để giảm độ trễ, sau đó mới fallback quét root nếu node index đã stale.
- `android:notificationTimeout=0` để giảm batching event.
- WebSocket client thường trực, tự ping và reconnect.
- Workflow hỗ trợ text/node, thao tác hệ thống, nghỉ theo giây và image matching.
- Image matching chạy local trên điện thoại bằng MediaProjection + ImageReader; server chỉ gửi ảnh mẫu + ROI, không cần gửi từng frame qua mạng.
- Ảnh mẫu được giữ trong RAM và mất khi process app bị khởi động lại.
- Lệnh legacy `auto` vẫn tắt; workflow chỉ chạy khi được gửi rõ ràng.

## Lệnh workflow

Mỗi bước cách nhau bằng `;` hoặc xuống dòng.

```text
CLICK:text
WAIT:text
UP
DOWN
BACK
HOME
RECENTS
SLEEP:seconds
WAIT_IMG:image_name
CLICK_IMG:image_name
```

Ý nghĩa:

```text
CLICK:text          tìm text/contentDescription và click node/parent clickable
WAIT:text           chờ text/contentDescription xuất hiện, không sleep cố định
UP                  vuốt lên
DOWN                vuốt xuống
BACK                Android GLOBAL_ACTION_BACK
HOME                Android GLOBAL_ACTION_HOME
RECENTS             Android GLOBAL_ACTION_RECENTS
SLEEP:0.5           nghỉ 0.5 giây; hỗ trợ số thập phân
WAIT_IMG:name       chờ ảnh xuất hiện trong ROI rồi chạy bước tiếp theo
CLICK_IMG:name      chờ ảnh xuất hiện trong ROI, click ngay tâm ảnh rồi chạy tiếp
```

`SLEEP` có alias `REST`, `NGHI`, `NGHỈ`. `WAIT` có alias `CHO`, `CHỜ`.

Ví dụ:

```text
WAIT:Hồ sơ;CLICK:Hồ sơ;SLEEP:0.2;DOWN;BACK;HOME
```

Ví dụ dùng ảnh:

```text
WAIT:Thanh toán;CLICK:Thanh toán;CLICK_IMG:nut_xac_nhan;SLEEP:0.2;BACK
```

`WAIT` và `WAIT_IMG` không có timeout mặc định. `WAIT` chạy tiếp nhờ Accessibility event; `WAIT_IMG` chạy tiếp nhờ frame mới của MediaProjection.

## Bật tìm ảnh

Tìm ảnh cần một phiên MediaProjection. Android bắt buộc người dùng cấp quyền chụp màn hình cho từng phiên capture trên Android mới.

1. Mở app `Trợ năng App`.
2. Bấm `Bật chụp màn hình / tìm ảnh`.
3. Chấp nhận hộp thoại chia sẻ/chụp màn hình của Android.
4. Khi app hiển thị `Chụp màn hình đang chạy`, `WAIT_IMG` và `CLICK_IMG` mới hoạt động.

Capture chạy trong foreground service loại `mediaProjection`. Nếu capture bị Android/người dùng dừng, workflow image sẽ trả lỗi `screen_capture_not_running`.

## WebSocket thường trực

Cấu hình URL một lần bằng ADB trong lúc phát triển:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method socket_connect --arg "ws://192.168.1.100:8765"
```

App lưu URL và AccessibilityService sẽ chủ động reconnect.

Sau đó server có thể gửi trực tiếp text workflow:

```text
BACK
HOME
RECENTS
SLEEP:1.5
WAIT:Продолжить;CLICK:Продолжить;DOWN
```

Hoặc image workflow:

```text
WAIT_IMG:nut_claim
CLICK_IMG:nut_claim
```

## Gửi ảnh mẫu qua socket

Socket nhận JSON `image_put`:

```json
{
  "cmd": "image_put",
  "name": "nut_claim",
  "png_base64": "iVBORw0KGgoAAA...",
  "roi": {
    "left": 700,
    "top": 1400,
    "right": 1050,
    "bottom": 1800
  },
  "threshold": 0.90
}
```

Trong đó:

- `png_base64`: PNG/JPEG encode base64; cũng chấp nhận data URL.
- `roi`: tọa độ pixel vùng cần tìm. Nếu `right/bottom` không hợp lệ thì matcher dùng đến mép màn hình.
- `threshold`: độ giống từ `0.50` đến `0.999`; mặc định `0.90`.
- Ảnh mẫu nên crop sát vật thể và đúng scale hiển thị trên điện thoại.

App trả:

```json
{"type":"image_put","success":true,"name":"nut_claim","width":120,"height":48,"threshold":0.9}
```

Khi match được ảnh, app gửi ngược:

```json
{
  "type": "image_match",
  "name": "nut_claim",
  "score": 0.94,
  "left": 810,
  "top": 1510,
  "right": 930,
  "bottom": 1558,
  "x": 870,
  "y": 1534
}
```

`CLICK_IMG:nut_claim` dùng ngay `x,y` vừa match để `dispatchGesture()`; không gửi tọa độ qua server rồi quay lại điện thoại.

Các JSON socket khác:

```json
{"cmd":"image_list"}
{"cmd":"capture_status"}
{"cmd":"image_remove","name":"nut_claim"}
{"cmd":"image_find","name":"nut_claim","click":false}
{"cmd":"image_find","name":"nut_claim","click":true}
{"cmd":"run","script":"WAIT_IMG:nut_claim;CLICK_IMG:nut_claim;BACK"}
```

## Tool server để test

Cài Python package:

```cmd
pip install websockets
```

Chạy:

```cmd
python tools\ws_server.py
```

Trong console server có thể gửi workflow bình thường, hoặc upload ảnh mẫu bằng:

```text
/img nut_claim C:\temp\claim.png 700 1400 1050 1800 0.90
```

Sau khi app trả `image_put success=true`, thử:

```text
/clickimg nut_claim
```

Hoặc:

```text
WAIT_IMG:nut_claim;CLICK_IMG:nut_claim;BACK
```

Các lệnh helper:

```text
/images
/capture
/find nut_claim
/clickimg nut_claim
/stop
```

## ADB test

Xem trạng thái:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/status
```

Các trường đáng chú ý:

```text
workflow_state
workflow_command
workflow_target
socket_state
socket_url
capture_running
image_targets
image_watch
```

Lệnh trực tiếp:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method back
adb shell content call --uri content://vn.banupham.tronangapp.commands --method home
adb shell content call --uri content://vn.banupham.tronangapp.commands --method recents
adb shell content call --uri content://vn.banupham.tronangapp.commands --method sleep --arg 1.5
```

Workflow:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow --arg "BACK;SLEEP:0.5;HOME"
```

Dừng workflow:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow_stop
```

Đọc tree:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

## Image matcher

Matcher hiện tại tối ưu cho trường hợp đã biết ROI nhỏ:

- chỉ xử lý frame khi workflow đang `WAIT_IMG`/`CLICK_IMG`;
- chỉ quét ROI đã cấu hình;
- lấy lưới mẫu 8x8 từ ảnh template;
- coarse scan stride 2 px, sau đó refine vùng tốt nhất ở stride 1 px;
- không ghi screenshot ra PNG/JPEG và không gửi screenshot qua mạng.

Đây là matcher nhẹ ưu tiên độ trễ. Nó phù hợp với icon/nút có kích thước và màu sắc tương đối ổn định. Nếu vật thể thay đổi scale, xoay, hiệu ứng mạnh hoặc màu sắc lớn thì cần matcher nâng cao hơn.

## Build

```bash
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions cũng build APK debug và upload artifact.
