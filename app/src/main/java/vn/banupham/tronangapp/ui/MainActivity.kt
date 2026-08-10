package vn.banupham.tronangapp.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.runtime.AgentRuntime
import vn.banupham.tronangapp.vision.ImageTargetRuntime
import vn.banupham.tronangapp.vision.ScreenCaptureService

class MainActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var socketHostInput: EditText
    private lateinit var socketPortInput: EditText
    private lateinit var socketControlStatus: TextView
    private lateinit var runtimeStatus: TextView
    private var socketFieldsInitialized = false
    private val handler = Handler(Looper.getMainLooper())

    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply { addView(buildContent()) })
        handleAutoCaptureIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoCaptureIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    @Deprecated("Deprecated in Android API but kept for minSdk 29 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        GenericAccessibilityService.instance?.disarmAutoCaptureConsent()
        if (resultCode != RESULT_OK || data == null) return

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun buildContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(40, 56, 40, 40)

        addView(TextView(context).apply {
            text = "Trợ năng App"
            textSize = 24f
        }, matchWrap())

        addView(TextView(context).apply {
            text = "Accessibility + workflow socket + tìm ảnh ROI. Không khóa package."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }, matchWrap())

        permissionStatus = TextView(context).apply { textSize = 18f }
        addView(permissionStatus, matchWrap())

        addView(Button(context).apply {
            text = "Mở cài đặt Trợ năng"
            setOnClickListener { openAccessibilitySettings() }
        }, matchWrap())

        captureStatus = TextView(context).apply {
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }
        addView(captureStatus, matchWrap())

        addView(Button(context).apply {
            text = "Bật chụp màn hình / tìm ảnh"
            setOnClickListener { requestScreenCapture() }
        }, matchWrap())

        addView(Button(context).apply {
            text = "Tắt chụp màn hình"
            setOnClickListener { stopScreenCapture() }
        }, matchWrap())

        addView(TextView(context).apply {
            text = "Lưu ý: Android bắt buộc hiện hộp thoại cho phép chụp màn hình. Sau khi cho phép, ảnh mục tiêu được tìm local trên điện thoại; ảnh màn hình không cần gửi qua socket."
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }, matchWrap())

        addView(TextView(context).apply {
            text = "Kết nối WebSocket"
            textSize = 18f
            setPadding(0, 20, 0, 8)
        }, matchWrap())

        socketHostInput = EditText(context).apply {
            hint = "IP hoặc hostname, ví dụ 192.168.1.100"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        addView(socketHostInput, matchWrap())

        socketPortInput = EditText(context).apply {
            hint = "Port"
            setText(DEFAULT_SOCKET_PORT.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        addView(socketPortInput, matchWrap())

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Kết nối"
                setOnClickListener { connectSocketFromUi() }
            }, weightedWrap())
            addView(Button(context).apply {
                text = "Ngắt kết nối"
                setOnClickListener { disconnectSocketFromUi() }
            }, weightedWrap())
        }, matchWrap())

        socketControlStatus = TextView(context).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        addView(socketControlStatus, matchWrap())

        addView(TextView(context).apply {
            text = "CMD:\nadb shell content query --uri content://vn.banupham.tronangapp.commands/status\n\nadb shell content query --uri content://vn.banupham.tronangapp.commands/nodes\n\nadb shell content call --uri content://vn.banupham.tronangapp.commands --method workflow --arg \"BACK;SLEEP:0.5;HOME\""
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, 28, 0, 12)
        }, matchWrap())

        runtimeStatus = TextView(context).apply {
            textSize = 16f
            setPadding(0, 20, 0, 0)
        }
        addView(runtimeStatus, matchWrap())
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        permissionStatus.text = if (enabled) "✓ Trợ năng đã bật" else "⚠ Trợ năng chưa bật"
        permissionStatus.setTextColor(if (enabled) Color.rgb(32, 128, 64) else Color.rgb(190, 70, 30))

        val captureRunning = ScreenCaptureService.running
        captureStatus.text = if (captureRunning) {
            "✓ Chụp màn hình đang chạy"
        } else {
            "⚠ Chụp màn hình chưa chạy (WAIT_IMG/CLICK_IMG chưa dùng được)"
        }
        captureStatus.setTextColor(
            if (captureRunning) Color.rgb(32, 128, 64) else Color.rgb(190, 70, 30)
        )

        val status = AgentRuntime.status
        val service = GenericAccessibilityService.instance
        val workflow = service?.workflowStatus()
        val socketUrl = service?.socketUrl()
        if (!socketFieldsInitialized && !socketUrl.isNullOrBlank()) {
            val uri = Uri.parse(socketUrl)
            socketHostInput.setText(uri.host ?: socketUrl.substringAfter("://").substringBefore(':'))
            socketPortInput.setText((if (uri.port > 0) uri.port else DEFAULT_SOCKET_PORT).toString())
            socketFieldsInitialized = true
        }
        socketControlStatus.text = buildString {
            append("Trạng thái: ")
            append(service?.socketState() ?: "service chưa chạy")
            if (!socketUrl.isNullOrBlank()) {
                append("\n")
                append(socketUrl)
            }
        }
        runtimeStatus.text = buildString {
            append("Service: ")
            append(if (service != null) "đã kết nối" else "chưa kết nối")
            append("\nPackage hiện tại: ")
            append(status.packageName ?: "—")
            append("\nNodes: ")
            append(status.nodeCount)
            append("\nGeneration: ")
            append(status.generation)
            append("\nEvent cuối: ")
            append(status.lastEvent ?: "—")
            append("\nSocket: ")
            append(service?.socketState() ?: "disconnected")
            append("\nWorkflow: ")
            append(workflow?.state ?: "idle")
            append("\nImage targets RAM: ")
            append(ImageTargetRuntime.targetCount())
            append("\nImage watch: ")
            append(ImageTargetRuntime.activeWatchName() ?: "—")
        }
    }

    private fun requestScreenCapture() {
        GenericAccessibilityService.instance?.armAutoCaptureConsent()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    private fun handleAutoCaptureIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_CAPTURE, false) != true) return
        intent.removeExtra(EXTRA_AUTO_CAPTURE)
        if (ScreenCaptureService.running) return
        handler.postDelayed({
            if (!isFinishing && !ScreenCaptureService.running) requestScreenCapture()
        }, AUTO_CAPTURE_REQUEST_DELAY_MS)
    }

    private fun stopScreenCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    private fun connectSocketFromUi() {
        val service = GenericAccessibilityService.instance
        if (service == null) {
            socketControlStatus.text = "Hãy bật Trợ năng trước khi kết nối"
            return
        }

        val rawHost = socketHostInput.text.toString().trim()
        val port = socketPortInput.text.toString().toIntOrNull()
        if (rawHost.isBlank() || port == null || port !in 1..65_535) {
            socketControlStatus.text = "IP/hostname hoặc port không hợp lệ"
            return
        }

        val parsed = Uri.parse(if (rawHost.contains("://")) rawHost else "ws://$rawHost")
        val scheme = if (parsed.scheme.equals("wss", ignoreCase = true)) "wss" else "ws"
        val host = parsed.host?.trim().orEmpty()
        if (host.isBlank()) {
            socketControlStatus.text = "IP/hostname không hợp lệ"
            return
        }

        val url = "$scheme://$host:$port"
        val success = service.connectSocket(url)
        socketFieldsInitialized = true
        socketControlStatus.text = if (success) "Đang kết nối: $url" else "Không thể kết nối: $url"
    }

    private fun disconnectSocketFromUi() {
        val service = GenericAccessibilityService.instance
        if (service == null) {
            socketControlStatus.text = "Service chưa chạy"
            return
        }
        service.disconnectSocket(clearSavedUrl = true)
        socketControlStatus.text = "Đã ngắt kết nối"
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, GenericAccessibilityService::class.java)
        val manager = getSystemService(AccessibilityManager::class.java)
        val bound = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo?.serviceInfo?.let { serviceInfo ->
                    ComponentName(serviceInfo.packageName, serviceInfo.name)
                } == expected
            }
        val persisted = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { ComponentName.unflattenFromString(it) == expected }
            ?: false
        return bound || persisted
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weightedWrap() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f
    )

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 7101
        private const val DEFAULT_SOCKET_PORT = 8770
        private const val AUTO_CAPTURE_REQUEST_DELAY_MS = 350L
        const val EXTRA_AUTO_CAPTURE = "auto_capture"
    }
}
