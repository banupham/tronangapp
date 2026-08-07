package vn.banupham.tronangapp.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.runtime.AgentRuntime

class MainActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var runtimeStatus: TextView
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

    private fun buildContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(40, 56, 40, 40)

        addView(TextView(context).apply {
            text = "Trợ năng App"
            textSize = 24f
        }, matchWrap())

        addView(TextView(context).apply {
            text = "Đọc cây Accessibility của app đang mở. Không khóa package. Toàn bộ hành động click/AUTO đang tắt."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }, matchWrap())

        permissionStatus = TextView(context).apply { textSize = 18f }
        addView(permissionStatus, matchWrap())

        addView(Button(context).apply {
            text = "Mở cài đặt Trợ năng"
            setOnClickListener { openAccessibilitySettings() }
        }, matchWrap())

        addView(TextView(context).apply {
            text = "CMD:\nadb shell content query --uri content://vn.banupham.tronangapp.commands/status\n\nadb shell content query --uri content://vn.banupham.tronangapp.commands/nodes\n\nadb shell content call --uri content://vn.banupham.tronangapp.commands --method swipe --arg up"
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

        val status = AgentRuntime.status
        runtimeStatus.text = buildString {
            append("Service: ")
            append(if (GenericAccessibilityService.instance != null) "đã kết nối" else "chưa kết nối")
            append("\nPackage hiện tại: ")
            append(status.packageName ?: "—")
            append("\nNodes: ")
            append(status.nodeCount)
            append("\nGeneration: ")
            append(status.generation)
            append("\nEvent cuối: ")
            append(status.lastEvent ?: "—")
            append("\nClick actions: TẮT")
        }
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
}
