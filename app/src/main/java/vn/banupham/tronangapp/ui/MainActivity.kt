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
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.remote.RemoteSocketClient
import vn.banupham.tronangapp.runtime.AgentRuntime
import vn.banupham.tronangapp.runtime.AutomationMode
import vn.banupham.tronangapp.runtime.AutomationPlan
import vn.banupham.tronangapp.runtime.AutomationPlanItem
import vn.banupham.tronangapp.runtime.AutomationPlanScheduler
import vn.banupham.tronangapp.runtime.AutomationPlanStore
import vn.banupham.tronangapp.runtime.AppProfileLauncher
import vn.banupham.tronangapp.runtime.LaunchableAppTarget
import vn.banupham.tronangapp.runtime.SavedWorkflow
import vn.banupham.tronangapp.runtime.SavedWorkflowStore
import vn.banupham.tronangapp.runtime.WorkflowEngine
import vn.banupham.tronangapp.vision.ImageTargetRuntime
import vn.banupham.tronangapp.vision.ScreenCaptureService

class MainActivity : Activity() {
    private lateinit var permissionStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var socketHostInput: EditText
    private lateinit var socketPortInput: EditText
    private lateinit var socketControlStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var workflowNameInput: EditText
    private lateinit var workflowScriptInput: EditText
    private lateinit var workflowTargetSpinner: Spinner
    private lateinit var workflowLibrary: LinearLayout
    private lateinit var workflowLibraryStatus: TextView
    private lateinit var planNameInput: EditText
    private lateinit var planItemsInput: EditText
    private lateinit var planScheduleSpinner: Spinner
    private lateinit var planTimeInput: EditText
    private lateinit var planEnabledInput: CheckBox
    private lateinit var planLibrary: LinearLayout
    private lateinit var planStatus: TextView
    private var launchTargets: List<LaunchableAppTarget> = emptyList()
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
        ImageTargetRuntime.initialize(this)
        setContentView(ScrollView(this).apply { addView(buildContent()) })
        refreshLaunchTargets()
        refreshWorkflowLibrary()
        refreshAutomationPlanLibrary()
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
                text = "Lưu cấu hình"
                setOnClickListener { saveSocketFromUi() }
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
            text = "Thư viện workflow offline"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }, matchWrap())

        workflowNameInput = EditText(context).apply {
            hint = "Tên workflow"
            setSingleLine(true)
        }
        addView(workflowNameInput, matchWrap())

        workflowScriptInput = EditText(context).apply {
            hint = "Chuỗi lệnh, ví dụ WAIT:Mở;CLICK:Mở"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        addView(workflowScriptInput, matchWrap())

        workflowTargetSpinner = Spinner(context)
        addView(workflowTargetSpinner, matchWrap())

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Lưu / ghi đè"
                setOnClickListener { saveWorkflowFromUi() }
            }, weightedWrap())
            addView(Button(context).apply {
                text = "Làm mới app/profile"
                setOnClickListener { refreshLaunchTargets() }
            }, weightedWrap())
        }, matchWrap())

        workflowLibraryStatus = TextView(context).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        addView(workflowLibraryStatus, matchWrap())

        workflowLibrary = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(workflowLibrary, matchWrap())

        addView(TextView(context).apply {
            text = "Kế hoạch tự động"
            textSize = 18f
            setPadding(0, 28, 0, 8)
        }, matchWrap())

        addView(TextView(context).apply {
            text = "Mỗi dòng: Tên workflow|số lượt. Các dòng chạy tuần tự từ trên xuống."
            textSize = 14f
        }, matchWrap())

        planNameInput = EditText(context).apply {
            hint = "Tên kế hoạch"
            setSingleLine(true)
        }
        addView(planNameInput, matchWrap())

        planItemsInput = EditText(context).apply {
            hint = "Điểm danh|1\nXem video|60"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        addView(planItemsInput, matchWrap())

        planScheduleSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("manual", "once", "daily")
            )
        }
        addView(planScheduleSpinner, matchWrap())

        planTimeInput = EditText(context).apply {
            hint = "once: 2026-08-18 08:30 | daily: 08:30"
            setSingleLine(true)
        }
        addView(planTimeInput, matchWrap())

        planEnabledInput = CheckBox(context).apply {
            text = "Bật lịch"
            isChecked = true
        }
        addView(planEnabledInput, matchWrap())

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Lưu / ghi đè lịch"
                setOnClickListener { saveAutomationPlanFromUi() }
            }, weightedWrap())
            addView(Button(context).apply {
                text = "Làm mới lịch"
                setOnClickListener { refreshAutomationPlanLibrary() }
            }, weightedWrap())
        }, matchWrap())

        planStatus = TextView(context).apply {
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        addView(planStatus, matchWrap())

        planLibrary = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(planLibrary, matchWrap())

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
        val activeSocketUrl = service?.socketUrl()
        val savedSocketUrl = RemoteSocketClient.savedUrl(this)
        val socketUrl = savedSocketUrl ?: activeSocketUrl
        if (!socketFieldsInitialized && !socketUrl.isNullOrBlank()) {
            val uri = Uri.parse(socketUrl)
            socketHostInput.setText(uri.host ?: socketUrl.substringAfter("://").substringBefore(':'))
            socketPortInput.setText((if (uri.port > 0) uri.port else DEFAULT_SOCKET_PORT).toString())
            socketFieldsInitialized = true
        }
        socketControlStatus.text = buildString {
            append("Trạng thái: ")
            append(service?.socketState() ?: "service chưa chạy")
            if (!activeSocketUrl.isNullOrBlank()) {
                append("\n")
                append("Đang dùng: ")
                append(activeSocketUrl)
            }
            if (!savedSocketUrl.isNullOrBlank()) {
                append("\nĐã lưu: ")
                append(savedSocketUrl)
            }
        }
        runtimeStatus.text = buildString {
            append("Automation: ")
            append(if (AutomationMode.paused) "PAUSED" else "ACTIVE")
            append("\n")
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

    private fun refreshLaunchTargets() {
        launchTargets = AppProfileLauncher.listTargets(this)
        val labels = listOf("Không tự mở ứng dụng") + launchTargets.map(LaunchableAppTarget::toString)
        workflowTargetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        workflowLibraryStatus.text = "Đã đọc ${launchTargets.size} package/profile có thể mở"
    }

    private fun saveWorkflowFromUi() {
        val name = workflowNameInput.text.toString().trim()
        val script = workflowScriptInput.text.toString().trim()
        val target = launchTargets.getOrNull(workflowTargetSpinner.selectedItemPosition - 1)
        val workflow = SavedWorkflow(
            name = name,
            script = script,
            packageName = target?.packageName,
            profileSerial = target?.profileSerial
        )
        val validation = runCatching { WorkflowEngine.parse(workflow.compiledScript()) }
        if (name.isBlank() || script.isBlank() || validation.isFailure) {
            workflowLibraryStatus.text = validation.exceptionOrNull()?.message
                ?: "Tên và chuỗi workflow không được để trống"
            return
        }
        SavedWorkflowStore.save(this, workflow)
        workflowLibraryStatus.text = "Đã lưu: $name"
        refreshWorkflowLibrary()
    }

    private fun refreshWorkflowLibrary() {
        workflowLibrary.removeAllViews()
        val workflows = SavedWorkflowStore.list(this)
        if (workflows.isEmpty()) {
            workflowLibrary.addView(TextView(this).apply { text = "Chưa có workflow đã lưu" }, matchWrap())
            return
        }
        workflows.forEach { workflow ->
            workflowLibrary.addView(TextView(this).apply {
                text = buildString {
                    append(workflow.name)
                    append("\n")
                    append(workflow.script)
                    if (!workflow.packageName.isNullOrBlank()) {
                        append("\nPackage: ")
                        append(workflow.packageName)
                        append(" | profile: ")
                        append(workflow.profileSerial ?: "current")
                    }
                }
                setTextIsSelectable(true)
            }, matchWrap())
            workflowLibrary.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    text = "Chạy"
                    setOnClickListener { runSavedWorkflowFromUi(workflow) }
                }, weightedWrap())
                addView(Button(context).apply {
                    text = "Sửa"
                    setOnClickListener { editSavedWorkflow(workflow) }
                }, weightedWrap())
                addView(Button(context).apply {
                    text = "Xóa"
                    setOnClickListener {
                        SavedWorkflowStore.remove(this@MainActivity, workflow.name)
                        workflowLibraryStatus.text = "Đã xóa: ${workflow.name}"
                        refreshWorkflowLibrary()
                    }
                }, weightedWrap())
            }, matchWrap())
        }
    }

    private fun runSavedWorkflowFromUi(workflow: SavedWorkflow) {
        val service = GenericAccessibilityService.instance
        if (service == null) {
            workflowLibraryStatus.text = "Dịch vụ trợ năng chưa chạy"
            return
        }
        val status = service.runWorkflow(workflow.compiledScript(), "offline-${System.currentTimeMillis()}")
        workflowLibraryStatus.text = if (status.state == "failed") {
            "Không thể chạy: ${status.error}"
        } else {
            "Đang chạy: ${workflow.name}"
        }
    }

    private fun editSavedWorkflow(workflow: SavedWorkflow) {
        workflowNameInput.setText(workflow.name)
        workflowScriptInput.setText(workflow.script)
        val targetIndex = launchTargets.indexOfFirst {
            it.packageName == workflow.packageName && it.profileSerial == workflow.profileSerial
        }
        workflowTargetSpinner.setSelection(if (targetIndex >= 0) targetIndex + 1 else 0)
        workflowLibraryStatus.text = "Đang sửa: ${workflow.name}"
    }

    private fun saveAutomationPlanFromUi() {
        val name = planNameInput.text.toString().trim()
        val items = parseAutomationPlanItems() ?: return
        val scheduleType = planScheduleSpinner.selectedItem?.toString().orEmpty()
        val timeText = planTimeInput.text.toString().trim()
        var runAtMillis: Long? = null
        var hour: Int? = null
        var minute: Int? = null
        when (scheduleType) {
            "once" -> {
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
                    isLenient = false
                }
                runAtMillis = runCatching { formatter.parse(timeText)?.time }.getOrNull()
                if (runAtMillis == null || runAtMillis <= System.currentTimeMillis()) {
                    planStatus.text = "Ngày giờ phải có dạng YYYY-MM-DD HH:MM và ở tương lai"
                    return
                }
            }
            "daily" -> {
                val parts = timeText.split(':')
                hour = parts.getOrNull(0)?.toIntOrNull()
                minute = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || hour !in 0..23 || minute !in 0..59) {
                    planStatus.text = "Giờ hằng ngày phải có dạng HH:MM"
                    return
                }
            }
        }
        val plan = AutomationPlan(
            name = name,
            items = items,
            scheduleType = scheduleType,
            runAtMillis = runAtMillis,
            hour = hour,
            minute = minute,
            enabled = planEnabledInput.isChecked
        )
        val workflowsExist = items.all { SavedWorkflowStore.find(this, it.workflowName) != null }
        if (name.isBlank() || !workflowsExist || !AutomationPlanStore.save(this, plan)) {
            planStatus.text = if (!workflowsExist) {
                "Có tên workflow chưa được lưu trong thư viện"
            } else {
                "Tên hoặc nội dung kế hoạch không hợp lệ"
            }
            return
        }
        val scheduled = AutomationPlanScheduler.schedule(this, plan)
        planStatus.text = if (scheduled) "Đã lưu kế hoạch: $name" else "Không thể đặt lịch: $name"
        refreshAutomationPlanLibrary()
    }

    private fun parseAutomationPlanItems(): List<AutomationPlanItem>? {
        val items = ArrayList<AutomationPlanItem>()
        planItemsInput.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty)
            .forEach { line ->
                val separator = line.lastIndexOf('|')
                val workflowName = if (separator > 0) line.substring(0, separator).trim() else ""
                val repetitions = if (separator > 0) line.substring(separator + 1).trim().toIntOrNull() else null
                if (workflowName.isBlank() || repetitions !in 1..100) {
                    planStatus.text = "Dòng không hợp lệ: $line (dùng Tên workflow|1..100)"
                    return null
                }
                items += AutomationPlanItem(workflowName, repetitions!!)
            }
        if (items.isEmpty() || items.sumOf { it.repetitions } > 1_000) {
            planStatus.text = "Cần ít nhất một workflow; tổng số lượt tối đa là 1000"
            return null
        }
        return items
    }

    private fun refreshAutomationPlanLibrary() {
        planLibrary.removeAllViews()
        val plans = AutomationPlanStore.list(this)
        if (plans.isEmpty()) {
            planLibrary.addView(TextView(this).apply { text = "Chưa có kế hoạch tự động" }, matchWrap())
            return
        }
        plans.forEach { plan ->
            planLibrary.addView(TextView(this).apply {
                text = buildString {
                    append(plan.name)
                    append(if (plan.enabled) " • ĐANG BẬT" else " • ĐÃ TẮT")
                    append("\n")
                    append(plan.items.joinToString(" → ") { "${it.workflowName} ×${it.repetitions}" })
                    append("\nLịch: ")
                    append(planScheduleLabel(plan))
                }
                setTextIsSelectable(true)
            }, matchWrap())
            planLibrary.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    text = "Chạy ngay"
                    setOnClickListener {
                        val success = GenericAccessibilityService.instance?.runAutomationPlan(plan.name) == true
                        planStatus.text = if (success) "Đã xếp hàng: ${plan.name}" else "Trợ năng chưa chạy"
                    }
                }, weightedWrap())
                addView(Button(context).apply {
                    text = "Sửa"
                    setOnClickListener { editAutomationPlan(plan) }
                }, weightedWrap())
                addView(Button(context).apply {
                    text = if (plan.enabled) "Tắt" else "Bật"
                    setOnClickListener {
                        val updated = AutomationPlanStore.setEnabled(this@MainActivity, plan.name, !plan.enabled)
                        if (updated != null) AutomationPlanScheduler.schedule(this@MainActivity, updated)
                        refreshAutomationPlanLibrary()
                    }
                }, weightedWrap())
                addView(Button(context).apply {
                    text = "Xóa"
                    setOnClickListener {
                        AutomationPlanScheduler.cancel(this@MainActivity, plan.name)
                        AutomationPlanStore.remove(this@MainActivity, plan.name)
                        refreshAutomationPlanLibrary()
                    }
                }, weightedWrap())
            }, matchWrap())
        }
    }

    private fun editAutomationPlan(plan: AutomationPlan) {
        planNameInput.setText(plan.name)
        planItemsInput.setText(plan.items.joinToString("\n") { "${it.workflowName}|${it.repetitions}" })
        planScheduleSpinner.setSelection(listOf("manual", "once", "daily").indexOf(plan.scheduleType).coerceAtLeast(0))
        planTimeInput.setText(
            when (plan.scheduleType) {
                "once" -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(plan.runAtMillis ?: 0L))
                "daily" -> "%02d:%02d".format(plan.hour ?: 0, plan.minute ?: 0)
                else -> ""
            }
        )
        planEnabledInput.isChecked = plan.enabled
        planStatus.text = "Đang sửa: ${plan.name}"
    }

    private fun planScheduleLabel(plan: AutomationPlan): String = when (plan.scheduleType) {
        "once" -> "một lần " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(plan.runAtMillis ?: 0L))
        "daily" -> "hằng ngày %02d:%02d".format(plan.hour ?: 0, plan.minute ?: 0)
        else -> "thủ công"
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

        val url = socketUrlFromInputs() ?: return
        val success = service.connectSocket(url)
        socketFieldsInitialized = true
        socketControlStatus.text = if (success) "Đang kết nối: $url" else "Không thể kết nối: $url"
    }

    private fun saveSocketFromUi() {
        val url = socketUrlFromInputs() ?: return
        val success = RemoteSocketClient.saveUrl(this, url)
        socketFieldsInitialized = true
        socketControlStatus.text = if (success) {
            "Đã lưu cấu hình: $url"
        } else {
            "Không thể lưu cấu hình: $url"
        }
    }

    private fun socketUrlFromInputs(): String? {
        val rawHost = socketHostInput.text.toString().trim()
        val port = socketPortInput.text.toString().toIntOrNull()
        if (rawHost.isBlank() || port == null || port !in 1..65_535) {
            socketControlStatus.text = "IP/hostname hoặc port không hợp lệ"
            return null
        }

        val parsed = Uri.parse(if (rawHost.contains("://")) rawHost else "ws://$rawHost")
        val scheme = if (parsed.scheme.equals("wss", ignoreCase = true)) "wss" else "ws"
        val host = parsed.host?.trim().orEmpty()
        if (host.isBlank()) {
            socketControlStatus.text = "IP/hostname không hợp lệ"
            return null
        }
        return "$scheme://$host:$port"
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
