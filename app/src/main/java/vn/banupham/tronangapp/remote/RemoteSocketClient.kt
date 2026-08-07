package vn.banupham.tronangapp.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
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
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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

        if (persist) {
            preferences.edit().putString(KEY_URL, normalized).apply()
        }
        url = normalized
        shouldReconnect = true
        reconnectDelayMs = 1_000L
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        webSocket?.cancel()
        openSocketLocked()
        return true
    }

    @Synchronized
    fun disconnect(clearSavedUrl: Boolean = false) {
        shouldReconnect = false
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        webSocket?.close(1000, "client_disconnect")
        webSocket = null
        state = "disconnected"
        if (clearSavedUrl) {
            preferences.edit().remove(KEY_URL).apply()
            url = null
        }
    }

    fun send(message: String): Boolean = webSocket?.send(message) == true

    @Synchronized
    private fun openSocketLocked() {
        val target = url ?: return
        state = "connecting"
        val request = Request.Builder().url(target).build()
        webSocket = client.newWebSocket(request, listener)
    }

    @Synchronized
    private fun scheduleReconnectLocked() {
        if (!shouldReconnect || reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed(reconnectRunnable, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@RemoteSocketClient) {
                state = "connected"
                reconnectDelayMs = 1_000L
                reconnectScheduled = false
            }
            webSocket.send("{\"type\":\"ready\",\"source\":\"tronangapp\"}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onCommand(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@RemoteSocketClient) {
                state = "disconnected"
                this@RemoteSocketClient.webSocket = null
                scheduleReconnectLocked()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@RemoteSocketClient) {
                state = "disconnected"
                this@RemoteSocketClient.webSocket = null
                scheduleReconnectLocked()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "remote_socket"
        private const val KEY_URL = "url"
    }
}
