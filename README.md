# Trợ năng App

Android Accessibility agent tổng quát, không khóa cứng package, có cây UI/index RAM, WebSocket thường trực, workflow event-driven và tìm ảnh theo ROI ngay trên điện thoại.

## Bản 0.5.0

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
LEFT
RIGHT
BACK
HOME
RECENTS
SLEEP:seconds
WAIT_IMG:image_name
CLICK_IMG:image_name
LOOP:count
END_LOOP
IF:text|label
IF_NOT:text|label
LABEL:name
GOTO:name
BREAK
CONTINUE
```

Ý nghĩa:

```text
CLICK:text          tìm text/contentDescription và click node/parent clickable
WAIT:text           chờ text/contentDescription xuất hiện, không sleep cố định
UP                  vuốt lên
DOWN                vuốt xuống
LEFT                vuốt sang trái
RIGHT               vuốt sang phải
SWIPE:x1,y1,x2,y2,350
                    vuốt từ điểm A(x1,y1) tới B(x2,y2) trong 350 ms
BACK                Android GLOBAL_ACTION_BACK
HOME                Android GLOBAL_ACTION_HOME
RECENTS             Android GLOBAL_ACTION_RECENTS
SLEEP:0.5           nghỉ 0.5 giây; hỗ trợ số thập phân
WAIT_IMG:name       chờ ảnh xuất hiện trong ROI rồi chạy bước tiếp theo
CLICK_IMG:name      chờ ảnh xuất hiện trong ROI, click ngay tâm ảnh rồi chạy tiếp
LOOP:5              bắt đầu khối lặp 5 lần
END_LOOP            kết thúc khối lặp
IF:text|label       nhảy tới label nếu text đang sẵn sàng trong Accessibility RAM index
IF_NOT:text|label   nhảy tới label nếu text chưa sẵn sàng
LABEL:name          khai báo điểm nhảy
GOTO:name           nhảy trực tiếp tới label
BREAK               thoát vòng lặp gần nhất
CONTINUE            chuyển sang lượt tiếp theo của vòng lặp gần nhất
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

Ví dụ lặp và rẽ nhánh:

```text
LOOP:10;IF:Nhận thưởng|claim;DOWN;CONTINUE;LABEL:claim;CLICK:Nhận thưởng;BREAK;END_LOOP;HOME
```

Workflow được compile label và cặp loop một lần trước khi chạy. App giới hạn 100.000 bước thực thi và tự nhường main queue sau mỗi 256 bước logic liên tục để tránh vòng lặp sai làm khóa UI.

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

Cách nhanh nhất trên giao diện Windows:

1. Mở tab `Màn hình` và bắt đầu xem máy cần tạo mẫu.
2. Nhập tên mẫu, ngưỡng và biên `ROI ±px`, rồi bấm `Tạo ảnh mẫu`.
3. Kéo chuột khoanh sát đối tượng ngay trên khung hình của đúng điện thoại.
4. Khi trạng thái báo đã tạo mẫu, dùng `WAIT_IMG:tên_mẫu` hoặc `CLICK_IMG:tên_mẫu`.

Mẫu được lấy trực tiếp từ frame gốc trên điện thoại, không lấy từ ảnh JPEG thu nhỏ đang hiển thị. `ROI ±px` giới hạn vùng tìm quanh vị trí mẫu để tăng tốc; đặt `0` nếu vật thể không di chuyển, hoặc tăng biên nếu vị trí có thể thay đổi.

Mẫu được lưu riêng trên từng điện thoại và tự nạp lại khi app, dịch vụ trợ năng hoặc thiết bị khởi động lại. Cài đè APK bằng cùng khóa ký vẫn giữ mẫu; gỡ app hoặc xóa dữ liệu ứng dụng sẽ xóa mẫu.

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

Sau mỗi `CLICK_IMG`, điện thoại gửi thêm log `IMAGE CLICK TIMING` gồm `find` (thời gian tìm ảnh), `match_to_dispatch` (từ phát hiện đến gửi gesture), `gesture` (Android thực thi gesture), `match_to_click` và `total`, tất cả tính bằng mili-giây trên cùng đồng hồ của điện thoại.

Các JSON socket khác:

```json
{"cmd":"image_list"}
{"cmd":"nodes","limit":200,"offset":0,"filter":""}
{"cmd":"capture_status"}
{"cmd":"image_remove","name":"nut_claim"}
{"cmd":"image_find","name":"nut_claim","click":false}
{"cmd":"image_find","name":"nut_claim","click":true}
{"cmd":"run","script":"WAIT_IMG:nut_claim;CLICK_IMG:nut_claim;BACK"}
```

## Tool server để test

Cài Python package:

```cmd
pip install websockets Pillow
```

Chạy:

```cmd
python tools\ws_server.py
```

Lệnh trên mở ứng dụng desktop có ba tab:

- `Điều khiển`: nút UP/DOWN/LEFT/RIGHT/BACK/HOME/RECENTS, gửi workflow và JSON thô;
- `Màn hình`: xem luồng JPEG tùy chọn cho từng điện thoại; mặc định 4 FPS, rộng 360 px,
  chỉ giữ frame mới nhất để không làm nghẽn lệnh điều khiển. Ô `Hiển thị %` thay đổi tỷ lệ
  ảnh trên PC mà không tăng băng thông; mỗi điện thoại tự vừa khung theo độ phân giải và tỷ lệ
  màn hình riêng. Click trực tiếp trong ảnh sẽ gửi `TAP:x,y` đúng điện thoại;
- `Nodes`: đọc, lọc và phân trang Accessibility nodes;
- `Log / độ trễ`: hiển thị ACK, `phone_queue`, `phone_execute` và tree scan.

Trên Windows cũng có thể chạy bằng cách nhấp đúp:

```text
tools\run_ws_gui.bat
```

Giữ chế độ console cũ bằng:

```cmd
python tools\ws_server.py --cli
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
/nodes 200 0
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
- kiểm tra frame giữ lại ngay khi image watch được bật, không polling định kỳ;
- chỉ quét ROI đã cấu hình;
- lấy lưới mẫu 8x8 từ ảnh template;
- coarse scan stride 2 px, sau đó refine vùng tốt nhất ở stride 1 px;
- không ghi screenshot ra PNG/JPEG và không gửi screenshot qua mạng.

Đây là matcher nhẹ ưu tiên độ trễ. Nó phù hợp với icon/nút có kích thước và màu sắc tương đối ổn định. Nếu vật thể thay đổi scale, xoay, hiệu ứng mạnh hoặc màu sắc lớn thì cần matcher nâng cao hơn.

## Kiểm tra hồi quy độ trễ

Mọi thay đổi trên đường điều khiển realtime cần so sánh log ACK trước và sau trên cùng thiết bị, mạng và workflow:

- `SEND -> RECEIVED`: độ trễ mạng/WebSocket;
- `RECEIVED -> STARTED` (`phone_queue`): hàng đợi main thread;
- `STARTED -> COMPLETED` (`phone_execute`): thời gian thực thi workflow;
- `last_tree_scan`: thời gian dựng lại Accessibility tree.

Không gộp số đo mạng với số đo trên điện thoại. Với image workflow, nên chạy lặp lại cùng một target để kiểm tra cả lần quét ROI đầu tiên và fast path từ vị trí match gần nhất.

## ACTIVE / PAUSED

Thông báo foreground của app có công tắc `PAUSE` / `RESUME`:

- `ACTIVE`: nhận workflow, cập nhật Accessibility tree và xử lý frame tìm ảnh.
- `PAUSED`: giữ quyền trợ năng và socket, nhưng hủy workflow/image watch, bỏ qua Accessibility events và tháo surface screen capture để tránh quét nền.
- PAUSED chỉ tồn tại trong tiến trình hiện tại; nếu Android khởi tạo lại tiến trình, app trở về ACTIVE để tránh bị khóa điều khiển ngoài ý muốn.

Socket JSON cũng hỗ trợ `automation_status`, `automation_pause`, `automation_resume`. Qua ADB có thể dùng:

```text
adb shell content call --uri content://vn.banupham.tronangapp.commands --method automation_pause
adb shell content call --uri content://vn.banupham.tronangapp.commands --method automation_resume
```

## Thư viện workflow offline và package/profile

App Android có thể lưu nhiều workflow theo tên, chạy lại hoặc xóa từng mục mà không cần kết nối socket. Mỗi workflow có thể gắn với một ứng dụng đích bằng `package_name` và `profile_serial`; khi chạy, app mở đúng launcher activity trong profile đó trước rồi mới thực thi chuỗi lệnh.

Các package/profile mà app có thể truy cập được đọc bằng Android `LauncherApps`. Nếu cùng package tồn tại ở profile cá nhân và profile công việc, danh sách hiển thị hai mục riêng với profile serial khác nhau.

Socket JSON hỗ trợ:

```json
{"cmd":"workflow_save","name":"Nhan thuong","script":"WAIT:Nhận thưởng;CLICK:Nhận thưởng","package_name":"com.example.app","profile_serial":0}
{"cmd":"workflow_list"}
{"cmd":"workflow_run_saved","id":"pc-1","name":"Nhan thuong"}
{"cmd":"workflow_remove","name":"Nhan thuong"}
{"cmd":"app_profile_list"}
{"cmd":"app_open","package_name":"com.example.app","profile_serial":0}
```

Có thể vừa lưu vừa chạy bằng `{"cmd":"run","save_as":"Tên workflow","script":"...","package_name":"...","profile_serial":0}`. Lệnh workflow trực tiếp cũng hỗ trợ `OPEN_APP:com.example.app|0`; bỏ `|profile_serial` để dùng profile hiện tại.

Trong `tools/ws_gui.py`, tab **Thư viện workflow** hỗ trợ lấy danh sách app/profile từ
điện thoại, lưu hoặc ghi đè nhiều workflow, nạp để sửa, chạy và xoá từng mục. Các thao
tác thư viện yêu cầu chọn đúng một điện thoại vì dữ liệu được lưu riêng trên từng máy.

Điều kiện ảnh kiểm tra một frame hiện tại và không chờ vô hạn:

```text
IF_NOT_IMG:nhan|BO_QUA_NHAN;CLICK_IMG:nhan;SLEEP_RANDOM:2,5;CLICK_IMG:ok;LABEL:BO_QUA_NHAN
```

`IF_IMG:tên_mẫu|NHÃN` nhảy khi thấy ảnh; `IF_NOT_IMG:tên_mẫu|NHÃN` nhảy khi không
thấy ảnh. `SLEEP_RANDOM:min,max` nghỉ ngẫu nhiên trong khoảng giây, từ 0 đến 3600.

### Kế hoạch chạy nhiều workflow và đặt lịch

GUI Windows có tab **Lịch tự động**. Mỗi kế hoạch chứa danh sách workflow đã lưu theo
thứ tự và số lượt, ví dụ `Điểm danh|1`, `Xem video|60`. Lịch hỗ trợ:

- `manual`: chỉ chạy khi bấm **Chạy ngay**;
- `once`: chạy một lần theo `YYYY-MM-DD HH:MM`;
- `daily`: chạy hằng ngày theo `HH:MM`.

Kế hoạch và lịch được lưu trên điện thoại, tự lên lịch lại sau khi máy khởi động. Android
có thể dịch thời điểm báo thức một chút khi máy đang tiết kiệm pin. Socket JSON tương ứng:

```json
{"cmd":"automation_plan_save","name":"Buổi sáng","items":[{"workflow":"Điểm danh","repeat":1},{"workflow":"Xem video","repeat":60}],"schedule_type":"daily","hour":8,"minute":30,"enabled":true}
{"cmd":"automation_plan_list"}
{"cmd":"automation_plan_run","name":"Buổi sáng"}
{"cmd":"automation_plan_enable","name":"Buổi sáng","enabled":false}
{"cmd":"automation_plan_remove","name":"Buổi sáng"}
```

## Build

```bash
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions cũng build APK debug và upload artifact.
