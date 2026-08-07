# Trợ năng App

Bản sao tối giản từ ý tưởng của `shopee-accessibility-agent`, dùng để **đọc cây Accessibility của ứng dụng đang mở mà không khóa cứng package Shopee**.

## Trạng thái hiện tại

- Android 10+ (`minSdk 29`).
- Không giới hạn `android:packageNames`: có thể quan sát app đang ở foreground nếu Android cung cấp cây Accessibility.
- Đọc trạng thái và danh sách node của app đang mở.
- Giữ thao tác thủ công qua CMD: `swipe up/down` và `click_text`.
- **Không có AUTO**, không có logic tự tìm mục tiêu, tự click hoặc tự thu thập.
- `click_text` chỉ chạy khi có lệnh ADB/CMD gọi vào và thao tác trên app đang ở foreground.
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
adb shell content call --uri content://vn.banupham.tronangapp.commands --method click_text --arg "Tiếp tục"
```

`click_text` không kiểm tra package Shopee. Nó tìm text/contentDescription trong cửa sổ Accessibility đang active, rồi click node hoặc parent clickable gần nhất.

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
