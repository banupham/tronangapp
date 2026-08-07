# Trợ năng App

Bản sao tối giản từ ý tưởng của `shopee-accessibility-agent`, dùng để **đọc cây Accessibility của ứng dụng đang mở mà không khóa cứng package Shopee**.

## Trạng thái hiện tại

- Android 10+ (`minSdk 29`).
- Không giới hạn `android:packageNames`: có thể quan sát app đang ở foreground nếu Android cung cấp cây Accessibility.
- Chỉ đọc trạng thái và danh sách node.
- **Đã tạm bỏ toàn bộ hành động click** và toàn bộ AUTO có thể dẫn tới click.
- Giữ cổng lệnh CMD/ADB bằng `ContentProvider`.
- Giữ lệnh `swipe up/down` để phục vụ test điều hướng; không có lệnh tap/click.

## Cổng CMD

Authority mới (để cài song song với app Shopee Agent):

```text
vn.banupham.tronangapp.commands
```

### Xem trạng thái

```cmd
adb shell content query --uri content://vn.banupham.tronangapp.commands/status
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

Các method click/auto không tồn tại trong bản này.

## Build

```bash
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions cũng build APK debug và upload artifact.
