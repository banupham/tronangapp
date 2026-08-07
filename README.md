# Trợ năng App

Bản sao tối giản từ ý tưởng của `shopee-accessibility-agent`, dùng để **đọc cây Accessibility của ứng dụng đang mở mà không khóa cứng package Shopee**.

## Trạng thái hiện tại

- Android 10+ (`minSdk 29`).
- Không giới hạn `android:packageNames`: có thể quan sát app đang ở foreground nếu Android cung cấp cây Accessibility.
- Đọc trạng thái và danh sách node của app đang mở.
- Giữ thao tác thủ công qua CMD: `swipe up/down` và `click_text`.
- **Không có AUTO**, không có logic tự tìm mục tiêu, tự click hoặc tự thu thập.
- `click_text` chỉ chạy khi có lệnh ADB/CMD gọi vào và thao tác trên app đang ở foreground.
- `click_text` so khớp cả `text` và `contentDescription`, ưu tiên khớp chính xác rồi mới khớp chứa chuỗi.
- Khi so khớp, app bỏ qua chữ hoa/thường và toàn bộ khoảng trắng: space, nhiều space, xuống dòng, tab, NBSP và zero-width space.
- Giữ cổng lệnh CMD/ADB bằng `ContentProvider`.

## Cổng CMD

Authority mới (để cài song song với app Shopee Agent):

```text
vn.banupham.tronangapp.commands
```

### Xem trạng thái

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/status
```

Các trường đáng chú ý:

```text
package
nodes
service_connected
click_actions_enabled=true
auto_actions_enabled=false
```

### Đọc node đang nhìn thấy

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/nodes
```

### Vuốt

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method swipe --arg up
adb shell content call --uri content://vn.banupham.tronangapp.commands --method swipe --arg down
```

### Click theo text

Ví dụ:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method click_text --arg "Lưu"
```

Hoặc:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method click_text --arg "новое сообщение"
```

Nếu node có description như:

```text
Поступило новое сообщение от пользователя joi76 07.08.2026 18:08:06
```

thì `--arg "новое сообщение"` vẫn khớp và click được nếu node hoặc parent của nó hỗ trợ `ACTION_CLICK`.

Khoảng trắng bên trong chuỗi được bỏ qua khi so khớp, nên các dạng sau được coi tương đương:

```text
Новое сообщение
Новое    сообщение
Новое\nсообщение
Новое сообщение
```

Lưu ý: ở Windows CMD, nếu tham số có khoảng trắng thì **vẫn phải đặt toàn bộ `--arg` trong dấu ngoặc kép** để CMD truyền nó thành một đối số duy nhất.

`click_text` không kiểm tra package Shopee. Nó duyệt cây Accessibility của cửa sổ đang active, tìm trong cả `text` và `contentDescription`, rồi click node hoặc parent clickable gần nhất.

### AUTO

AUTO đã tắt trong bản này. Lệnh:

```cmd
adb shell content call --uri content://vn.banupham.tronangapp.commands --method auto --arg on
```

sẽ không bật hành động tự động và trả lỗi `auto_actions_disabled`.

## Build

```bash
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions cũng build APK debug và upload artifact.
