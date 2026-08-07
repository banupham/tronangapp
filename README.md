# Trợ năng App

Bản trợ năng Android tổng quát, không khóa cứng package, dùng Accessibility để đọc cây UI, click, vuốt và chạy chuỗi lệnh theo sự kiện.

## Bản 0.2.0

- Android 10+ (`minSdk 29`).
- Không giới hạn `android:packageNames`.
- Cây Accessibility được cập nhật khi có event và tạo index text/description trong RAM.
- `click_text` ưu tiên index RAM để giảm độ trễ, sau đó mới fallback quét root nếu node index đã stale.
- `android:notificationTimeout=0` để giảm batching event.
- Có workflow event-driven với 4 chỉ lệnh lõi: `CLICK`, `UP`, `DOWN`, `WAIT`.
- `WAIT` **không dùng sleep theo giây**. Nó treo workflow tại bước đó và chỉ chạy tiếp khi text/description mục tiêu xuất hiện trong cây Accessibility đang nhìn thấy.
- Có WebSocket client thường trực. URL được lưu lại và AccessibilityService sẽ tự kết nối lại khi service khởi động.
- WebSocket tự ping mỗi 20 giây và reconnect theo backoff 1s -> 2s -> 4s ... tối đa 15s.
- Giữ lại ContentProvider/ADB để test nhanh trong giai đoạn phát triển.
- Lệnh legacy `auto` vẫn tắt; workflow chỉ chạy khi được gửi rõ ràng.

## Cú pháp workflow

Mỗi bước cách nhau bằng `;` hoặc xuống dòng.

```text
WAIT:Новое сообщение
CLICK:Новое сообщение
DOWN
WAIT:Продолжить
CLICK:Продолжить
UP
```

Có thể gửi một dòng:

```text
WAIT:Новое сообщение;CLICK:Новое сообщение;DOWN;WAIT:Продолжить;CLICK:Продолжить
```

Ý nghĩa:

```text
CLICK:text   -> tìm text/contentDescription và click node hoặc parent clickable gần nhất
UP           -> vuốt lên
DOWN         -> vuốt xuống
WAIT:text    -> chờ theo Accessibility event cho tới khi text/contentDescription xuất hiện
```

`WAIT` không có timeout mặc định và không dùng delay cố định. Khi chưa thấy mục tiêu, workflow ở trạng thái `waiting`. Mỗi Accessibility event mới cập nhật index RAM; đúng thời điểm mục tiêu xuất hiện thì workflow tiếp tục ngay.

So khớp bỏ qua chữ hoa/thường và toàn bộ khoảng trắng. Ví dụ:

```text
Новое сообщение
новоесообщение
Новое    сообщение
```

được coi tương đương. Match chính xác được ưu tiên trước match chứa chuỗi.

## Test workflow bằng ADB

Authority:

```text
vn.banupham.tronangapp.commands
```

Ví dụ:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow --arg "WAIT:Новое сообщение;CLICK:Новое сообщение;DOWN"
```

Nếu Windows CMD gây rắc rối với khoảng trắng, có thể bỏ khoảng trắng trong target vì app tự chuẩn hóa:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow --arg "WAIT:Новоесообщение;CLICK:Новоесообщение;DOWN"
```

Dừng workflow:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow_stop
```

Xem trạng thái:

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/status
```

Các trường mới:

```text
workflow_state
workflow_step
workflow_total
workflow_command
workflow_target
workflow_error
socket_state
socket_url
```

## Lệnh đơn

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method click --arg "Новое сообщение"
adb shell content call --uri content://vn.banupham.tronangapp.commands --method up
adb shell content call --uri content://vn.banupham.tronangapp.commands --method down
adb shell content call --uri content://vn.banupham.tronangapp.commands --method wait --arg "Продолжить"
```

`click_text` và `swipe --arg up/down` cũ vẫn hoạt động.

## WebSocket thường trực

Kết nối một lần trong lúc test:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method socket_connect --arg "ws://192.168.1.100:8765/ws"
```

App lưu URL. Sau đó có thể tắt ADB; khi AccessibilityService chạy, app chủ động kết nối ra URL đã lưu.

Server chỉ cần gửi text workflow qua WebSocket, ví dụ:

```text
WAIT:Новое сообщение;CLICK:Новое сообщение;DOWN
```

Hoặc lệnh đơn:

```text
CLICK:Продолжить
UP
DOWN
```

Server gửi:

```text
PING
```

app trả:

```json
{"type":"pong"}
```

Gửi:

```text
STOP
```

để dừng workflow hiện tại.

App cũng gửi trạng thái workflow dạng JSON, ví dụ:

```json
{"type":"workflow","state":"waiting","step":0,"total":3,"command":"WAIT","target":"Новое сообщение","error":null}
```

Ngắt socket và xóa URL đã lưu:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method socket_disconnect
```

Khi dùng qua Internet nên dùng `wss://` thay vì `ws://`.

## Node tree

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

Node hiện có thêm trường `enabled` bên cạnh `clickable`.

## Build

```bash
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions cũng build APK debug và upload artifact.
