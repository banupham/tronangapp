package vn.banupham.tronangapp.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import vn.banupham.tronangapp.accessibility.GenericAccessibilityService
import vn.banupham.tronangapp.runtime.AutomationMode
import vn.banupham.tronangapp.ui.MainActivity

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var latestImage: Image? = null
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val streamEncoding = AtomicBoolean(false)
    private var lastStreamFrameMs = 0L

    private var width: Int = 0
    private var height: Int = 0
    private var densityDpi: Int = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_AUTOMATION -> {
                applyAutomationMode(true)
                return START_NOT_STICKY
            }
            ACTION_RESUME_AUTOMATION -> {
                applyAutomationMode(false)
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        stopProjection()

        val captureMetrics = resolveCaptureMetrics()
        width = captureMetrics.widthPixels
        height = captureMetrics.heightPixels
        densityDpi = captureMetrics.densityDpi
        captureWidth = width
        captureHeight = height
        captureDensityDpi = densityDpi

        captureThread = HandlerThread(
            "tronangapp-screen-capture",
            Process.THREAD_PRIORITY_DISPLAY
        ).also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        ImageTargetRuntime.onWatchStarted = {
            captureHandler?.post {
                latestImage?.let { image ->
                    runCatching { ImageTargetRuntime.processFrame(image, width, height) }
                }
            }
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, resultData)
        projection = mediaProjection

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                running = false
                stopSelf()
            }
        }, captureHandler)

        // Keep one already acquired image available as the latest stable frame.
        // maxImages=3 leaves enough room for acquireLatestImage() while one image
        // remains held for immediate WAIT_IMG / CLICK_IMG checks.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener

            runCatching { latestImage?.close() }
            latestImage = image

            // If a workflow is already waiting, evaluate this fresh frame now.
            runCatching {
                ImageTargetRuntime.processFrame(image, width, height)
            }
            maybeStreamFrame(image)
        }, captureHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "tronangapp-screen",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
        running = true
        setCapturePaused(AutomationMode.paused)

    }

    private fun applyAutomationMode(paused: Boolean) {
        val accessibility = GenericAccessibilityService.instance
        if (accessibility != null) {
            accessibility.setAutomationPaused(paused)
        } else {
            AutomationMode.setPaused(paused)
            setCapturePaused(paused)
        }
        updateNotification()
    }

    private fun setCapturePaused(paused: Boolean) {
        captureHandler?.post {
            if (paused) {
                configureStream(false)
                ImageTargetRuntime.clearWatch()
                runCatching { latestImage?.close() }
                latestImage = null
                runCatching { virtualDisplay?.surface = null }
            } else {
                val surface = imageReader?.surface
                if (surface != null && surface.isValid) {
                    runCatching { virtualDisplay?.surface = surface }
                }
            }
            updateNotification()
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification()
        )
    }

    private fun maybeStreamFrame(image: Image) {
        val consumer = streamConsumer ?: return
        val now = SystemClock.elapsedRealtime()
        val intervalMs = 1_000L / streamFps.coerceIn(1, 12)
        if (now - lastStreamFrameMs < intervalMs || !streamEncoding.compareAndSet(false, true)) return
        lastStreamFrameMs = now

        val bitmap = runCatching { imageToBitmap(image) }.getOrElse {
            streamEncoding.set(false)
            return
        }
        streamExecutor.execute {
            try {
                val targetWidth = streamWidth.coerceIn(240, width)
                val targetHeight = (bitmap.height * (targetWidth.toFloat() / bitmap.width))
                    .toInt().coerceAtLeast(1)
                val scaled = if (targetWidth == bitmap.width) bitmap else {
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                        .also { bitmap.recycle() }
                }
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, streamQuality.coerceIn(35, 80), output)
                scaled.recycle()
                consumer(
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                    targetWidth,
                    targetHeight,
                    now
                )
            } finally {
                streamEncoding.set(false)
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    /**
     * MediaProjection must use the real logical display bounds, not the
     * compatibility-adjusted Resources.displayMetrics of this app process.
     * This keeps image-match coordinates in the same coordinate system used by
     * Accessibility gestures and `adb shell input tap` when `wm size` has an
     * override (for example 1080x1920 on a 1440x2560 physical panel).
     */
    private fun resolveCaptureMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
            metrics.density = resources.displayMetrics.density
            metrics.scaledDensity = resources.displayMetrics.scaledDensity
            metrics.xdpi = resources.displayMetrics.xdpi
            metrics.ydpi = resources.displayMetrics.ydpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            val fallback = resources.displayMetrics
            metrics.widthPixels = fallback.widthPixels
            metrics.heightPixels = fallback.heightPixels
            metrics.densityDpi = fallback.densityDpi
            metrics.density = fallback.density
            metrics.scaledDensity = fallback.scaledDensity
            metrics.xdpi = fallback.xdpi
            metrics.ydpi = fallback.ydpi
        }

        return metrics
    }

    private fun stopProjection() {
        running = false
        configureStream(false)
        captureWidth = 0
        captureHeight = 0
        captureDensityDpi = 0

        ImageTargetRuntime.onWatchStarted = null
        runCatching { latestImage?.close() }
        latestImage = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null
    }

    override fun onDestroy() {
        stopProjection()
        if (instance === this) instance = null
        streamExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val paused = AutomationMode.paused
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val toggleIntent = PendingIntent.getService(
            this,
            if (paused) 2 else 1,
            Intent(this, ScreenCaptureService::class.java).setAction(
                if (paused) ACTION_RESUME_AUTOMATION else ACTION_PAUSE_AUTOMATION
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(openIntent)
        builder.addAction(
            0,
            if (paused) "RESUME" else "PAUSE",
            toggleIntent
        )
        return builder
            .setContentTitle("Trợ năng App")
            .setContentText("Đang đọc khung hình để tìm ảnh mục tiêu")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentText(
                if (paused) "PAUSED - tree scan and commands are disabled"
                else "ACTIVE - ready for commands"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "vn.banupham.tronangapp.STOP_CAPTURE"
        const val ACTION_PAUSE_AUTOMATION = "vn.banupham.tronangapp.PAUSE_AUTOMATION"
        const val ACTION_RESUME_AUTOMATION = "vn.banupham.tronangapp.RESUME_AUTOMATION"

        fun applyPausedState(paused: Boolean) {
            instance?.setCapturePaused(paused)
        }

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var captureWidth: Int = 0
            private set

        @Volatile
        var captureHeight: Int = 0
            private set

        @Volatile
        var captureDensityDpi: Int = 0
            private set

        @Volatile
        private var streamConsumer: ((String, Int, Int, Long) -> Unit)? = null

        @Volatile
        private var streamFps: Int = 4

        @Volatile
        private var streamWidth: Int = 360

        @Volatile
        private var streamQuality: Int = 55

        fun configureStream(
            enabled: Boolean,
            fps: Int = 4,
            width: Int = 360,
            quality: Int = 55,
            consumer: ((String, Int, Int, Long) -> Unit)? = null
        ) {
            streamFps = fps.coerceIn(1, 12)
            streamWidth = width.coerceIn(240, 720)
            streamQuality = quality.coerceIn(35, 80)
            streamConsumer = if (enabled) consumer else null
        }

        fun registerImageTarget(
            name: String,
            templateLeft: Int,
            templateTop: Int,
            templateRight: Int,
            templateBottom: Int,
            roiLeft: Int,
            roiTop: Int,
            roiRight: Int,
            roiBottom: Int,
            threshold: Double,
            callback: (Result<ImageTargetRuntime.ImageTarget>) -> Unit
        ): Boolean {
            val service = instance ?: return false
            val handler = service.captureHandler ?: return false
            handler.post {
                val image = service.latestImage
                val result = if (image == null) {
                    Result.failure(IllegalStateException("screen_frame_unavailable"))
                } else {
                    ImageTargetRuntime.registerFromFrame(
                        name,
                        image,
                        service.width,
                        service.height,
                        templateLeft,
                        templateTop,
                        templateRight,
                        templateBottom,
                        roiLeft,
                        roiTop,
                        roiRight,
                        roiBottom,
                        threshold
                    )
                }
                callback(result)
            }
            return true
        }

        fun probeImageTarget(
            name: String,
            callback: (Result<ImageTargetRuntime.ImageMatch?>) -> Unit
        ): Boolean {
            val service = instance ?: return false
            val handler = service.captureHandler ?: return false
            handler.post {
                val image = service.latestImage
                callback(
                    if (image == null) {
                        Result.failure(IllegalStateException("screen_frame_unavailable"))
                    } else {
                        ImageTargetRuntime.probeFrame(name, image, service.width, service.height)
                    }
                )
            }
            return true
        }

        @Volatile
        private var instance: ScreenCaptureService? = null

        private const val CHANNEL_ID = "tronangapp_capture"
        private const val NOTIFICATION_ID = 1201
    }
}
