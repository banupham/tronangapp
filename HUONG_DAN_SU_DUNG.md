# HƯỚNG DẪN SỬ DỤNG TRONANGAPP

Tài liệu này mô tả cách dùng bản hiện tại của `tronangapp` để điều khiển Android qua Accessibility + WebSocket, chạy workflow, chờ text, click text, vuốt, Back/Home/đa nhiệm, nghỉ theo giây và tìm/click ảnh trong một vùng màn hình đã biết trước.

## 1. Các chức năng hiện có

App hiện hỗ trợ các lệnh workflow:

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

- `CLICK:text`: tìm `text` hoặc `contentDescription` trong Accessibility tree rồi click node hoặc parent clickable gần nhất.
- `WAIT:text`: dừng workflow tại đây cho đến khi text/description mục tiêu xuất hiện và sẵn sàng trong Accessibility tree.
- `UP`: vuốt lên.
- `DOWN`: vuốt xuống.
- `BACK`: nút Back hệ thống.
- `HOME`: về màn hình chính.
- `RECENTS`: mở màn hình đa nhiệm.
- `SLEEP:1`: nghỉ 1 giây.
- `SLEEP:0.25`: nghỉ 0,25 giây.
- `WAIT_IMG:name`: chờ ảnh mẫu `name` xuất hiện trong ROI đã đăng ký.
- `CLICK_IMG:name`: chờ ảnh mẫu `name` xuất hiện rồi click ngay vào tâm ảnh tìm được.

Các bước workflow cách nhau bằng dấu `;` hoặc xuống dòng.

Ví dụ:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục;SLEEP:0.2;DOWN;BACK;HOME
```

## 2. Chuẩn bị trên Android

Sau khi cài APK:

1. Mở app `Trợ năng App`.
2. Bấm `Mở cài đặt Trợ năng`.
3. Bật Accessibility Service cho app.
4. Nếu cần dùng `WAIT_IMG` hoặc `CLICK_IMG`, quay lại app và bấm `Bật chụp màn hình / tìm ảnh`.
5. Chấp nhận hộp thoại cho phép chụp/chia sẻ màn hình của Android.

Khi phần tìm ảnh đã sẵn sàng, app sẽ hiển thị:

```text
✓ Chụp màn hình đang chạy
```

Nếu hiển thị:

```text
⚠ Chụp màn hình chưa chạy (WAIT_IMG/CLICK_IMG chưa dùng được)
```

thì các lệnh tìm ảnh chưa thể hoạt động.

Lưu ý: quyền MediaProjection phải được người dùng chấp nhận trên điện thoại. Không thể bật hoàn toàn âm thầm chỉ bằng socket.

## 3. Chạy WebSocket server trên PC

Trong repo đã có:

```text
tools/ws_server.py
```

Cài thư viện:

```cmd
python -m pip install websockets
```

Chạy server:

```cmd
python tools\ws_server.py
```

Mặc định server lắng nghe:

```text
ws://0.0.0.0:8765
```

Nếu Windows Firewall hỏi quyền cho Python, cho phép trên mạng LAN đang sử dụng.

## 4. Lấy IP máy tính

Trên Windows:

```cmd
ipconfig
```

Ví dụ IP máy tính là:

```text
192.168.1.100
```

thì URL socket sẽ là:

```text
ws://192.168.1.100:8765
```

Điện thoại và PC phải truy cập được tới nhau qua mạng.

## 5. Cho app kết nối WebSocket

Trong giai đoạn test, dùng ADB một lần:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method socket_connect --arg "ws://192.168.1.100:8765"
```

App lưu URL socket. Khi AccessibilityService được chạy lại, app sẽ tự thử kết nối lại URL đã lưu.

Server sẽ thấy thông báo tương tự:

```text
[+] phone connected
PHONE: {"type":"ready","source":"tronangapp"}
```

Xem trạng thái app:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/status
```

Ngắt socket và xóa URL đã lưu:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method socket_disconnect
```

## 6. Gửi lệnh trực tiếp qua socket

Sau khi chạy `tools/ws_server.py`, gõ trực tiếp lệnh tại dấu nhắc `>`.

Ví dụ click text:

```text
CLICK:Tiếp tục
```

Chờ text xuất hiện rồi click:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục
```

Vuốt:

```text
UP
```

```text
DOWN
```

Back:

```text
BACK
```

Home:

```text
HOME
```

Đa nhiệm:

```text
RECENTS
```

Nghỉ 1 giây:

```text
SLEEP:1
```

Nghỉ 150 ms:

```text
SLEEP:0.15
```

Workflow hỗn hợp:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục;SLEEP:0.15;DOWN;BACK
```

Dừng workflow hiện tại:

```text
/stop
```

hoặc server gửi raw:

```text
STOP
```

## 7. CLICK và WAIT dùng Accessibility tree

`CLICK:text` và `WAIT:text` KHÔNG dùng ảnh.

Luồng:

```text
Accessibility event
    -> Accessibility tree
    -> text / contentDescription
    -> tìm node
    -> click
```

App chuẩn hóa chuỗi khi so khớp, bỏ qua hoa/thường và khoảng trắng.

Ví dụ các chuỗi sau có thể được coi tương đương:

```text
Новое сообщение
новоесообщение
Новое    сообщение
```

Có thể xem tree hiện tại bằng ADB:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

Nếu một nút/icon không xuất hiện trong Accessibility tree thì nên dùng tìm ảnh.

## 8. Tìm ảnh: nguyên tắc hoạt động

`WAIT_IMG` và `CLICK_IMG` KHÔNG lấy ảnh từ Accessibility tree.

Phải có một ảnh mẫu thật trước.

Luồng:

```text
Ảnh mẫu trên PC
    -> gửi qua WebSocket
    -> Android giải mã
    -> lưu target trong RAM
    -> MediaProjection lấy frame màn hình
    -> chỉ quét ROI đã khai báo
    -> so sánh với ảnh mẫu
    -> tìm được tọa độ
    -> CLICK_IMG click vào tâm ảnh
```

Tên như:

```text
nut_claim
```

chỉ là tên khóa do người dùng tự đặt cho ảnh mẫu.

## 9. Nạp ảnh mẫu qua socket

Ví dụ có file:

```text
C:\anh\nut_xac_nhan.png
```

Tại console của `ws_server.py`, gửi:

```text
/img nut_claim "C:\anh\nut_xac_nhan.png" 700 1400 1050 1800 0.90
```

Cú pháp:

```text
/img NAME FILE LEFT TOP RIGHT BOTTOM [THRESHOLD]
```

Trong ví dụ trên:

```text
NAME       = nut_claim
FILE       = C:\anh\nut_xac_nhan.png
LEFT       = 700
TOP        = 1400
RIGHT      = 1050
BOTTOM     = 1800
THRESHOLD  = 0.90
```

ROI là vùng màn hình mà ảnh dự kiến xuất hiện.

Dùng ROI càng nhỏ và đúng vị trí thì tìm ảnh càng nhanh.

`threshold = 0.90` nghĩa là yêu cầu mức giống tối thiểu khoảng 90% theo matcher hiện tại.

Sau khi nhận ảnh thành công, app trả JSON tương tự:

```json
{"type":"image_put","success":true,"name":"nut_claim","width":120,"height":48,"threshold":0.9}
```

## 10. Chờ ảnh xuất hiện

Sau khi đã nạp ảnh mẫu:

```text
WAIT_IMG:nut_claim
```

Hoặc dùng shortcut của server:

```text
/find nut_claim
```

Workflow sẽ dừng ở bước này cho đến khi target xuất hiện.

## 11. Chờ ảnh rồi click ngay

Khuyến nghị dùng:

```text
CLICK_IMG:nut_claim
```

Hoặc shortcut:

```text
/clickimg nut_claim
```

`CLICK_IMG` có nghĩa:

```text
chờ ảnh xuất hiện
    -> giữ luôn tọa độ vừa match
    -> click ngay
```

Nếu mục tiêu là phản ứng nhanh thì nên dùng `CLICK_IMG` thay vì:

```text
WAIT_IMG:nut_claim;CLICK_IMG:nut_claim
```

vì chuỗi trên có thể phải dò ảnh lại ở bước sau.

## 12. Xem danh sách ảnh đã nạp

Trong console server:

```text
/images
```

App trả danh sách target đang giữ trong RAM.

Xem trạng thái capture:

```text
/capture
```

## 13. Ảnh mẫu hiện chỉ lưu trong RAM

Các image target hiện tại không được lưu vĩnh viễn.

Nếu process app bị kill/restart thì cần gửi lại:

```text
/img ...
```

ROI và threshold cũng được đăng ký lại cùng ảnh.

## 14. Ví dụ workflow thực tế

Ví dụ 1 - chờ text rồi thao tác:

```text
WAIT:Новое сообщение;CLICK:Новое сообщение;SLEEP:0.15;DOWN
```

Ví dụ 2 - mở thao tác rồi chờ một nút hình ảnh:

```text
CLICK:Nhận thưởng;SLEEP:0.2;CLICK_IMG:nut_claim;BACK
```

Ví dụ 3 - workflow kết hợp text + ảnh + thao tác hệ thống:

```text
WAIT:Tiếp tục;CLICK:Tiếp tục;SLEEP:0.1;DOWN;CLICK_IMG:nut_claim;SLEEP:0.2;HOME
```

Ví dụ 4 - chỉ dùng image target:

```text
CLICK_IMG:nut_claim;SLEEP:0.1;CLICK_IMG:nut_ok
```

Trước đó phải nạp cả hai ảnh:

```text
/img nut_claim "C:\anh\claim.png" 700 1400 1050 1800 0.90
/img nut_ok "C:\anh\ok.png" 500 1200 850 1550 0.92
```

## 15. Phân biệt WAIT và SLEEP

`WAIT:text`:

```text
WAIT:Tiếp tục
```

Không chờ một số giây cố định. Nó chờ Accessibility event cho tới khi mục tiêu xuất hiện.

`SLEEP:seconds`:

```text
SLEEP:0.5
```

Luôn nghỉ đúng khoảng thời gian được yêu cầu rồi chạy bước tiếp theo.

`WAIT_IMG:name`:

```text
WAIT_IMG:nut_claim
```

Không chờ theo thời gian. Nó chờ frame màn hình cho tới khi ảnh được match.

## 16. Kiểm tra nhanh bằng ADB

Chạy workflow:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow --arg "WAIT:Tiếp tục;CLICK:Tiếp tục;BACK"
```

Back:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method back
```

Home:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method home
```

Đa nhiệm:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method recents
```

Nghỉ:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method sleep --arg "0.5"
```

Dừng workflow:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow_stop
```

Xem trạng thái:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/status
```

Xem Accessibility nodes:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

## 17. Các lỗi thường gặp

### `image_target_not_registered`

Chưa nạp ảnh mẫu.

Gửi trước:

```text
/img nut_claim "C:\anh\claim.png" 700 1400 1050 1800 0.90
```

### `screen_capture_not_running`

Chưa bật MediaProjection.

Mở app -> bấm:

```text
Bật chụp màn hình / tìm ảnh
```

rồi chấp nhận hộp thoại Android.

### Socket không kết nối

Kiểm tra:

```cmd
ipconfig
```

Đảm bảo URL dùng đúng IP máy tính:

```text
ws://IP_PC:8765
```

Kiểm tra Windows Firewall và đảm bảo PC/điện thoại truy cập được tới nhau.

### `CLICK:text` không tìm thấy nút

Kiểm tra node tree:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

Nếu target không tồn tại trong tree thì dùng `CLICK_IMG`.

### Tìm ảnh sai

Thử:

- crop ảnh mẫu sát mục tiêu hơn;
- thu nhỏ ROI;
- tăng threshold, ví dụ `0.92`, `0.95`;
- đảm bảo ảnh mẫu cùng kích thước hiển thị với target thực tế.

### Tìm ảnh không ra dù nhìn giống

Thử giảm threshold:

```text
0.90 -> 0.85
```

Không nên hạ quá thấp nếu trong ROI có nhiều hình giống nhau.

## 18. Tốc độ

Với `CLICK:text`, đường ưu tiên là Accessibility tree/index RAM.

Với `CLICK_IMG`, frame màn hình được xử lý local trên Android. Ảnh màn hình không gửi lên PC để nhận diện.

Nếu đã biết trước vùng mục tiêu, nên giới hạn ROI thật nhỏ. Ví dụ target chỉ nằm trong khoảng:

```text
LEFT=700
TOP=1400
RIGHT=1050
BOTTOM=1800
```

thì matcher chỉ quét vùng đó thay vì toàn màn hình.

Mục tiêu thiết kế là:

```text
frame -> ROI -> match -> lấy x,y -> click
```

không lưu screenshot ra file, không gửi frame qua socket, không dùng OCR nếu không cần.

## 19. Bảo mật socket

`ws://` phù hợp để test trong LAN.

Không nên expose server WebSocket không xác thực trực tiếp ra Internet.

Nếu triển khai qua Internet nên bổ sung xác thực thiết bị/token và dùng:

```text
wss://
```

## 20. Tóm tắt quy trình sử dụng

```text
ANDROID
  1. Cài APK
  2. Bật Accessibility
  3. Nếu dùng ảnh -> bật "Chụp màn hình / tìm ảnh"

PC
  4. python -m pip install websockets
  5. python tools\ws_server.py
  6. lấy IP bằng ipconfig

KẾT NỐI
  7. socket_connect -> ws://IP_PC:8765

TEXT
  8. WAIT:text / CLICK:text

IMAGE
  9. /img name file left top right bottom threshold
 10. CLICK_IMG:name

WORKFLOW
 11. ghép các bước bằng dấu ;
```

Ví dụ hoàn chỉnh:

```text
/img nut_claim "C:\anh\claim.png" 700 1400 1050 1800 0.90
```

sau đó:

```text
WAIT:Nhận thưởng;CLICK:Nhận thưởng;SLEEP:0.15;CLICK_IMG:nut_claim;BACK;HOME
```

---

Tài liệu áp dụng cho nhánh `main`, bản app `0.3.0` tại thời điểm tạo hướng dẫn.
