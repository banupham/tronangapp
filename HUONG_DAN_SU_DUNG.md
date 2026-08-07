# HƯỚNG DẪN SỬ DỤNG TRONANGAPP

Tài liệu áp dụng cho nhánh `main`, bản app `0.4.0`.

`tronangapp` dùng Accessibility + WebSocket để chạy workflow trên Android. App hỗ trợ thao tác theo text trong Accessibility tree, thao tác hệ thống, nghỉ theo thời gian và tìm/click ảnh trong một ROI đã biết trước.

## 1. Lệnh workflow

```text
CLICK:text
WAIT:text
UP
DOWN
BACK
HOME
RECENTS
SLEEP:giây
WAIT_IMG:tên_ảnh
CLICK_IMG:tên_ảnh
```

Ý nghĩa:

- `CLICK:text`: tìm `text` hoặc `contentDescription` rồi click node/parent clickable.
- `WAIT:text`: chờ target xuất hiện trong Accessibility.
- `UP`, `DOWN`: vuốt lên/xuống.
- `BACK`, `HOME`, `RECENTS`: thao tác hệ thống.
- `SLEEP:1`: nghỉ 1 giây; có thể dùng số thập phân như `SLEEP:0.15`.
- `WAIT_IMG:name`: chờ ảnh mẫu xuất hiện.
- `CLICK_IMG:name`: chờ ảnh mẫu xuất hiện rồi click ngay vào tâm ảnh match được.

Ghép nhiều bước bằng dấu `;`:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục;SLEEP:0.15;DOWN;BACK
```

## 2. Chuẩn bị Android

Sau khi cài APK:

1. Mở `Trợ năng App`.
2. Bấm `Mở cài đặt Trợ năng` và bật Accessibility Service.
3. Nếu dùng `WAIT_IMG` hoặc `CLICK_IMG`, quay lại app và bấm `Bật chụp màn hình / tìm ảnh`.
4. Chấp nhận hộp thoại MediaProjection của Android.

Khi tìm ảnh sẵn sàng, app hiển thị:

```text
✓ Chụp màn hình đang chạy
```

Nếu hiển thị:

```text
⚠ Chụp màn hình chưa chạy (WAIT_IMG/CLICK_IMG chưa dùng được)
```

thì phải bật capture trước.

## 3. Chạy WebSocket server trên PC

Repo có sẵn:

```text
tools/ws_server.py
```

Cài thư viện:

```cmd
python -m pip install websockets
```

Chạy:

```cmd
python tools\ws_server.py
```

Server mặc định:

```text
ws://0.0.0.0:8765
```

Lấy IP Windows:

```cmd
ipconfig
```

Ví dụ PC có IP `192.168.1.100`, URL điện thoại cần kết nối là:

```text
ws://192.168.1.100:8765
```

## 4. Kết nối app tới socket

Trong giai đoạn test, dùng ADB một lần:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method socket_connect --arg "ws://192.168.1.100:8765"
```

App lưu URL và tự reconnect khi AccessibilityService chạy lại.

Ngắt socket và xóa URL đã lưu:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method socket_disconnect
```

## 5. Gửi lệnh qua socket

Sau khi `ws_server.py` chạy và điện thoại đã kết nối, gõ trực tiếp:

```text
UP
```

hoặc:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục;SLEEP:0.2;DOWN
```

Dừng workflow:

```text
/stop
```

Ping socket:

```text
/ping
```

## 6. ACK và đo độ trễ từ bản 0.4.0

Mỗi workflow gửi từ `tools/ws_server.py` được gắn một ID, ví dụ:

```text
pc-1
```

Ví dụ gửi:

```text
UP
```

console có thể hiển thị:

```text
[pc-1] SEND       UP
[pc-1] RECEIVED   +     5.8 ms  (PC send -> phone ACK round-trip)
[pc-1] STARTED    +     7.2 ms  phone_queue=1.0 ms
[pc-1] COMPLETED  +   360.5 ms  phone_execute=353.0 ms
```

Ý nghĩa:

```text
SEND
  PC bắt đầu gửi lệnh

RECEIVED
  callback WebSocket trên điện thoại đã nhận lệnh

STARTED
  Android main thread bắt đầu chạy workflow

COMPLETED
  workflow đã hoàn thành
```

`phone_queue` được tính hoàn toàn bằng đồng hồ monotonic trên điện thoại:

```text
STARTED.phone_ms - RECEIVED.phone_ms
```

Do đó không cần đồng bộ giờ giữa PC và điện thoại.

Cách đọc kết quả:

```text
SEND -> RECEIVED cao
=> nghi mạng / Wi-Fi / WebSocket

RECEIVED -> STARTED (phone_queue) cao
=> nghi Android main thread bị nghẽn

STARTED -> COMPLETED cao
=> thao tác/gesture/workflow đang mất thời gian
```

Lưu ý: `UP` và `DOWN` hiện dùng gesture khoảng `350 ms`, nên `phone_execute` quanh mức này là bình thường.

## 7. Tối ưu Accessibility tree từ bản 0.4.0

Bản cũ rebuild toàn bộ Accessibility tree sau gần như mỗi event. Khi app mục tiêu phát nhiều event, việc này có thể làm main thread bận và khiến lệnh socket chờ.

Bản 0.4.0 đổi thành:

```text
Accessibility event
        |
        +--> FAST PATH: kiểm tra event.source ngay cho WAIT
        |
        +--> full-tree snapshot được gom event (coalesce)
             và rebuild sau một khoảng ngắn
```

Full tree hiện được coalesce khoảng `40 ms`, thay vì cố rebuild cho mọi event liên tiếp.

Đặc biệt workflow:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục
```

nếu event source chứa đúng target, app có thể kiểm tra và click trực tiếp từ subtree nhỏ trước khi full-tree snapshot chạy.

Các lệnh không cần tree như:

```text
UP
DOWN
BACK
HOME
RECENTS
```

không phụ thuộc nội dung tree để quyết định thao tác.

## 8. CLICK và WAIT theo Accessibility

`CLICK:text` và `WAIT:text` không dùng ảnh.

Xem tree hiện tại:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

App bỏ qua hoa/thường và khoảng trắng khi so khớp. Ví dụ:

```text
Новое сообщение
новоесообщение
Новое    сообщение
```

được chuẩn hóa để so khớp tương đương.

Nếu target không tồn tại trong Accessibility tree, dùng tìm ảnh.

## 9. Tìm ảnh qua socket

Ảnh mẫu phải được nạp trước. Tên như `nut_claim` chỉ là tên khóa do người dùng đặt, không phải node trong Accessibility tree.

Ví dụ có file:

```text
C:\anh\claim.png
```

Nạp vào điện thoại:

```text
/img nut_claim "C:\anh\claim.png" 700 1400 1050 1800 0.90
```

Cú pháp:

```text
/img NAME FILE LEFT TOP RIGHT BOTTOM [THRESHOLD]
```

Trong đó:

```text
NAME       = tên target
FILE       = ảnh PNG/JPEG trên PC
LEFT/TOP/RIGHT/BOTTOM = ROI màn hình cần tìm
THRESHOLD  = ngưỡng giống, ví dụ 0.90
```

Ảnh được gửi một lần qua socket, Android giải mã và giữ target trong RAM.

## 10. Chờ hoặc click ảnh

Chỉ chờ:

```text
WAIT_IMG:nut_claim
```

shortcut:

```text
/find nut_claim
```

Chờ rồi click ngay:

```text
CLICK_IMG:nut_claim
```

shortcut:

```text
/clickimg nut_claim
```

Nếu mục tiêu là phản ứng nhanh, ưu tiên:

```text
CLICK_IMG:nut_claim
```

thay vì:

```text
WAIT_IMG:nut_claim;CLICK_IMG:nut_claim
```

vì `CLICK_IMG` giữ luôn tọa độ vừa match và click ngay.

Xem ảnh đã nạp:

```text
/images
```

Xem trạng thái capture:

```text
/capture
```

Ảnh mẫu hiện chỉ giữ trong RAM; nếu process app bị kill/restart thì gửi `/img ...` lại.

## 11. Ví dụ workflow hoàn chỉnh

Nạp ảnh:

```text
/img nut_claim "C:\anh\claim.png" 700 1400 1050 1800 0.90
```

Sau đó chạy:

```text
WAIT:Nhận thưởng;CLICK:Nhận thưởng;SLEEP:0.15;CLICK_IMG:nut_claim;BACK;HOME
```

Ví dụ chỉ dùng text:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục;SLEEP:0.1;DOWN
```

Ví dụ chỉ dùng ảnh:

```text
CLICK_IMG:nut_claim;SLEEP:0.1;CLICK_IMG:nut_ok
```

## 12. ADB kiểm tra nhanh

Status:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/status
```

Nodes:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

Workflow:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow --arg "WAIT:Tiếp tục;CLICK:Tiếp tục;BACK"
```

Back/Home/Recents:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method back
adb shell content call --uri content://vn.banupham.tronangapp.commands --method home
adb shell content call --uri content://vn.banupham.tronangapp.commands --method recents
```

Sleep:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method sleep --arg "0.5"
```

## 13. Lỗi thường gặp

### `image_target_not_registered`

Chưa gửi `/img ...` cho target đó.

### `screen_capture_not_running`

Mở app và bật `Bật chụp màn hình / tìm ảnh`.

### Socket không kết nối

Kiểm tra IP bằng `ipconfig`, Windows Firewall và khả năng PC/điện thoại truy cập nhau.

### `CLICK:text` không tìm thấy

Kiểm tra `/nodes`; nếu target không có trong Accessibility tree thì dùng `CLICK_IMG`.

### Tìm ảnh sai hoặc không ra

Crop ảnh mẫu sát target, thu nhỏ ROI và điều chỉnh threshold. Ví dụ thử `0.92`, `0.95` nếu match nhầm; hoặc giảm từ `0.90` xuống `0.85` nếu ảnh thật có biến đổi nhỏ.

## 14. Bảo mật

`ws://` chỉ nên dùng để test trong LAN.

Không expose WebSocket chưa xác thực trực tiếp ra Internet. Khi triển khai Internet nên dùng `wss://` và bổ sung token/xác thực thiết bị.

## 15. Quy trình test độ nghẽn khuyến nghị

Chạy server mới:

```cmd
python tools\ws_server.py
```

Sau đó gửi liên tiếp từng lệnh, chờ mỗi lệnh hoàn thành:

```text
UP
DOWN
UP
DOWN
```

Đọc `RECEIVED`, `phone_queue` và `phone_execute` để xác định:

```text
mạng chậm
hay
Android main thread/tree chậm
hay
gesture tự nó mất thời gian
```

Đây là cách chuẩn để chẩn đoán từ bản `0.4.0`, thay vì chỉ nhìn thời điểm màn hình đã vuốt hay chưa.
