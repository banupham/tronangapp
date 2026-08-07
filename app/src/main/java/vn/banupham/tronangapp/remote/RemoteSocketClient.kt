package vn.banupham.tronangapp.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RemoteSocketClient(
    context: Context,
    private val onCommand: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        // A shorter WebSocket heartbeat keeps the TCP/Wi-Fi path warm for an
        // interactive control channel instead of letting it sit idle for 20s.
        .pingInterval(SOCKET_PING_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val wifiLock: WifiManager.WifiLock? = createRealtimeWifiLock()

    @Volatile
    var state: String = "disconnected"
        private set

    @Volatile
    var url: String? = preferences.getString(KEY_URL, null)
        private set

    private var webSocket: WebSocket? = null
    private var shouldReconnect = false
    private var reconnectDelayMs = 1_000L
    private var reconnectScheduled = false
    private val pendingMessages = ArrayDeque<String>()

    private val reconnectRunnable = Runnable {
        synchronized(this) {
            reconnectScheduled = false
            if (shouldReconnect) openSocketLocked()
        }
    }

    @Synchronized
    fun connectSaved(): Boolean {
        val saved = url ?: return false
        return connect(saved, persist = false)
    }

    @Synchronized
    fun connect(requestedUrl: String, persist: Boolean = true): Boolean {
        val normalized = requestedUrl.trim()
        if (!normalized.startsWith("ws://") && !normalized.startsWith("wss://")) {
            state = "invalid_url"
            return false
        }

        if (url != null && url != normalized) {
            pendingMessages.clear()
        }
        if (persist) {
            preferences.edit().putString(KEY_URL, normalized).apply()
        }
        url = normalized
        shouldReconnect = true
        reconnectDelayMs = 1_000L
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false

        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()
        releaseWifiLockLocked()
        openSocketLocked()
        return true
    }

    @Synchronized
    fun disconnect(clearSavedUrl: Boolean = false) {
        shouldReconnect = false
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.close(1000, "client_disconnect")
        state = "disconnected"
        pendingMessages.clear()
        releaseWifiLockLocked()
        if (clearSavedUrl) {
            preferences.edit().remove(KEY_URL).apply()
            url = null
        }
    }

    /**
     * Send immediately when the socket is healthy. If the socket is between
     * connections, keep a bounded queue so ACK/completion messages are not
     * silently lost during a short reconnect window.
     */
    @Synchronized
    fun send(message: String): Boolean {
        val socket = webSocket
        if (state == "connected" && socket != null && socket.send(message)) {
            return true
        }
        enqueuePendingLocked(message)
        return false
    }

    @Synchronized
    private fun openSocketLocked() {
        val target = url ?: return
        state = "connecting"
        val request = Request.Builder().url(target).build()
        webSocket = client.newWebSocket(request, listener)
    }

    private fun enqueuePendingLocked(message: String) {
        while (pendingMessages.size >= MAX_PENDING_MESSAGES) {
            pendingMessages.removeFirst()
        }
        pendingMessages.addLast(message)
    }

    private fun flushPendingLocked(socket: WebSocket) {
        while (pendingMessages.isNotEmpty()) {
            val message = pendingMessages.first()
            if (!socket.send(message)) return
            pendingMessages.removeFirst()
        }
    }

    @Synchronized
    private fun scheduleReconnectLocked() {
        if (!shouldReconnect || reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
    }

    /**
     * Android 10-13: HIGH_PERF keeps Wi-Fi out of power-save mode, which is
     * useful for a low-bandwidth but latency-sensitive socket. Android 14+
     * uses the newer LOW_LATENCY mode.
     */
    @Suppress("DEPRECATION")
    private fun createRealtimeWifiLock(): WifiManager.WifiLock? = runCatching {
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 34) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        manager.createWifiLock(mode, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }.getOrNull()

    private fun acquireWifiLockLocked() {
        val lock = wifiLock ?: return
        if (!lock.isHeld) {
            runCatching { lock.acquire() }
        }
    }

    private fun releaseWifiLockLocked() {
        val lock = wifiLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@RemoteSocketClient) {
                if (this@RemoteSocketClient.webSocket !== webSocket) {
                    webSocket.cancel()
                    return
                }
                state = "connected"
                reconnectDelayMs = 1_000L
                reconnectScheduled = false
                acquireWifiLockLocked()
                webSocket.send("{\"type\":\"ready\",\"source\":\"tronangapp\"}")
                flushPendingLocked(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(this@RemoteSocketClient) {
                if (this@RemoteSocketClient.webSocket !== webSocket) return
            }
            onCommand(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@RemoteSocketClient) {
                if (this@RemoteSocketClient.webSocket !== webSocket) return
                state = "disconnected"
                this@RemoteSocketClient.webSocket = null
                releaseWifiLockLocked()
                scheduleReconnectLocked()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@RemoteSocketClient) {
                if (this@RemoteSocketClient.webSocket !== webSocket) return
                state = "disconnected"
                this@RemoteSocketClient.webSocket = null
                releaseWifiLockLocked()
                scheduleReconnectLocked()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "remote_socket"
        private const val KEY_URL = "url"
        private const val MAX_PENDING_MESSAGES = 200
        private const val SOCKET_PING_SECONDS = 5L
        private const val WIFI_LOCK_TAG = "tronangapp:realtime_socket"
    }
}
