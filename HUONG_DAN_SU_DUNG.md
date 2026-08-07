# HƯỚNG DẪN SỬ DỤNG TRONANGAPP

Tài liệu áp dụng cho nhánh `main`, bản app `0.4.1`.

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

## 6. ACK và đo độ trễ

Mỗi workflow gửi từ `tools/ws_server.py` có một ID, ví dụ `pc-1`.

Ví dụ:

```text
[pc-1] SEND       UP
[pc-1] RECEIVED   +     8.0 ms  (PC send -> phone ACK round-trip)
[pc-1] STARTED    +    10.0 ms  phone_queue=1.0 ms  last_tree_scan=12.0 ms  tree_age=210.0 ms
[pc-1] COMPLETED  +   365.0 ms  phone_execute=354.0 ms
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

`phone_queue` được tính bằng đồng hồ monotonic trên điện thoại:

```text
STARTED.phone_ms - RECEIVED.phone_ms
```

Do đó không cần đồng bộ giờ giữa PC và điện thoại.

Cách đọc kết quả:

```text
SEND -> RECEIVED cao
=> nghi mạng / Wi-Fi / WebSocket

phone_queue cao
=> nghi Android main thread / tree đang bận

phone_execute cao
=> thao tác/gesture/workflow mất thời gian
```

`UP` và `DOWN` hiện dùng gesture khoảng `350 ms`, nên `phone_execute` quanh `350-380 ms` là bình thường.

Server 0.4.1 tự đánh dấu:

```text
[NETWORK/SOCKET SPIKE]
```

nếu `SEND -> RECEIVED` vượt khoảng `120 ms`, và:

```text
[MAIN/TREE QUEUE]
```

nếu `phone_queue` vượt khoảng `30 ms`.

## 7. Tối ưu TREE từ bản 0.4.1

Bản cũ có thể rebuild full Accessibility tree quá sớm trong lúc UI đang scroll/animate.

Bản `0.4.1` dùng:

```text
Accessibility event
        |
        +--> FAST PATH: kiểm tra event.source ngay cho WAIT
        |
        +--> full tree được DEBOUNCE
             đợi event lắng xuống rồi mới rebuild
```

Thông số hiện tại:

```text
debounce tree      = 120 ms
command grace      = 150 ms
max tree stale     = 500 ms
```

Nếu event tiếp tục dồn dập, tree bị đẩy lùi nhưng tối đa khoảng 500 ms sẽ refresh một lần để RAM index không quá cũ.

Lệnh socket realtime được đưa lên đầu main queue bằng `postAtFrontOfQueue`, nên `UP`, `DOWN`, `BACK`, `HOME`, `RECENTS` không phải đứng sau một snapshot tree chỉ đang chờ trong queue.

Workflow:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục
```

vẫn có fast path từ `event.source`, nên không phải chờ full-tree rebuild để phản ứng.

## 8. Tối ưu mạng từ bản 0.4.1

Khi WebSocket đang connected, app giữ Wi-Fi ở chế độ ưu tiên độ trễ phù hợp với phiên bản Android và giảm heartbeat WebSocket từ 20 giây xuống còn 5 giây.

Mục tiêu là giảm các spike kiểu:

```text
SEND -> RECEIVED = 300-500 ms
```

trong khi các lần bình thường chỉ khoảng vài đến vài chục ms.

Wi-Fi performance lock chỉ được giữ khi socket connected và được thả khi disconnect/failure. Đổi lại thiết bị có thể tốn pin hơn trong lúc kết nối realtime.

## 9. CLICK và WAIT theo Accessibility

`CLICK:text` và `WAIT:text` không dùng ảnh.

Xem tree hiện tại:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

App bỏ qua hoa/thường và khoảng trắng khi so khớp.

Nếu target không tồn tại trong Accessibility tree thì dùng `CLICK_IMG`.

## 10. Tìm ảnh qua socket

Ảnh mẫu phải được nạp trước. Tên như `nut_claim` chỉ là tên khóa do người dùng đặt, không phải node trong Accessibility tree.

Ví dụ:

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

## 11. Chờ hoặc click ảnh

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

Nếu mục tiêu là phản ứng nhanh, ưu tiên `CLICK_IMG:name` thay vì `WAIT_IMG:name;CLICK_IMG:name`.

Xem ảnh đã nạp:

```text
/images
```

Xem trạng thái capture:

```text
/capture
```

Ảnh mẫu hiện chỉ giữ trong RAM; nếu process app bị kill/restart thì gửi `/img ...` lại.

## 12. Ví dụ workflow hoàn chỉnh

Nạp ảnh:

```text
/img nut_claim "C:\anh\claim.png" 700 1400 1050 1800 0.90
```

Sau đó:

```text
WAIT:Nhận thưởng;CLICK:Nhận thưởng;SLEEP:0.15;CLICK_IMG:nut_claim;BACK;HOME
```

## 13. ADB kiểm tra nhanh

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

## 14. Lỗi thường gặp

`image_target_not_registered`: chưa gửi `/img ...` cho target đó.

`screen_capture_not_running`: mở app và bật `Bật chụp màn hình / tìm ảnh`.

Socket không kết nối: kiểm tra IP bằng `ipconfig`, Windows Firewall và khả năng PC/điện thoại truy cập nhau.

`CLICK:text` không tìm thấy: kiểm tra `/nodes`; nếu target không có trong Accessibility tree thì dùng `CLICK_IMG`.

Tìm ảnh sai hoặc không ra: crop ảnh mẫu sát target, thu nhỏ ROI và điều chỉnh threshold.

## 15. Bảo mật

`ws://` chỉ nên dùng để test trong LAN.

Không expose WebSocket chưa xác thực trực tiếp ra Internet. Khi triển khai Internet nên dùng `wss://` và bổ sung token/xác thực thiết bị.

## 16. Quy trình test nghẽn sau nâng cấp

Cập nhật code PC:

```cmd
git pull origin main
python tools\ws_server.py
```

Cài APK `0.4.1`, bật Accessibility rồi gửi từng lệnh, chờ hoàn thành trước khi gửi lệnh tiếp:

```text
UP
UP
UP
UP
UP
```

Gửi lại log gồm:

```text
RECEIVED
STARTED + phone_queue + last_tree_scan + tree_age
COMPLETED + phone_execute
```

Từ đó có thể tách rõ:

```text
mạng / Wi-Fi / socket
main thread / tree
gesture Android
```
