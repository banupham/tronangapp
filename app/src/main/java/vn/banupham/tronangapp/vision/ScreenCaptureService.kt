package vn.banupham.tronangapp.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var latestImage: Image? = null

    /** Last startWatch generation already checked against latestImage. */
    private var lastProbedWatchGeneration: Long = -1L

    private var width: Int = 0
    private var height: Int = 0
    private var densityDpi: Int = 0

    private val cachedFrameProbe = object : Runnable {
        override fun run() {
            val handler = captureHandler ?: return
            try {
                val generation = ImageTargetRuntime.activeWatchGeneration()

                if (generation == 0L) {
                    // No active workflow image wait. Reset so the next watch is
                    // eligible even if it happens to use the same image name.
                    lastProbedWatchGeneration = -1L
                } else if (generation != lastProbedWatchGeneration) {
                    // A unique generation is assigned on every startWatch call.
                    // This fixes the old name-based race where repeated /find
                    // nut_claim calls could be mistaken for the same watch and
                    // then wait seconds for a new MediaProjection frame.
                    lastProbedWatchGeneration = generation
                    latestImage?.let { image ->
                        runCatching {
                            ImageTargetRuntime.processFrame(image, width, height)
                        }
                    }
                }
            } finally {
                // Never let one bad/stale frame permanently kill the probe loop.
                if (captureHandler === handler) {
                    handler.postDelayed(this, CACHED_FRAME_PROBE_MS)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
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

        captureThread = HandlerThread("tronangapp-screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

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

        // Probe the retained frame for every new image-watch generation. This is
        // independent of the target name, so repeated /find calls are reliable.
        captureHandler?.post(cachedFrameProbe)
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
        captureWidth = 0
        captureHeight = 0
        captureDensityDpi = 0

        captureHandler?.removeCallbacks(cachedFrameProbe)
        lastProbedWatchGeneration = -1L
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
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Trợ năng App")
            .setContentText("Đang đọc khung hình để tìm ảnh mục tiêu")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "vn.banupham.tronangapp.STOP_CAPTURE"

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

        private const val CACHED_FRAME_PROBE_MS = 8L
        private const val CHANNEL_ID = "tronangapp_capture"
        private const val NOTIFICATION_ID = 1201
    }
}
