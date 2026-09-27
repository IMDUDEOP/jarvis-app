package com.jarvis.remote

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The one thing that has to survive the app being backgrounded: the
 * WebSocket to remote_pairing.py's /ws. A foreground service is what lets
 * Android tolerate a long-lived socket at all — without one, the OS kills
 * background network connections within minutes. This is the realistic
 * middle ground without a Firebase/FCM project: JARVIS can reach the phone
 * any time this service's socket is connected, which is "the app has been
 * opened at least once since the last reboot and hasn't been force-stopped"
 * — not true wake-on-push for a fully killed app, but a normal foreground
 * service notification (the required, deliberately silent one — see
 * NotificationHelper.CHANNEL_SERVICE) plus BootReceiver restarting it after
 * every reboot gets close for most real usage.
 */
class JarvisConnectionService : Service() {

    interface ConnectionListener {
        fun onReply(text: String)
        fun onConnectionStateChanged(connected: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): JarvisConnectionService = this@JarvisConnectionService
    }

    private val binder = LocalBinder()
    var listener: ConnectionListener? = null

    private lateinit var repo: PairingRepository
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSockets: no read timeout
        .build()
    private var socket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var stopped = false

    @Volatile
    var isConnected: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        repo = PairingRepository(this)
        NotificationHelper.ensureChannels(this)
        startForeground(NotificationHelper.NOTIF_ID_SERVICE, NotificationHelper.serviceNotification(this, "Connecting…"))
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopped = true
        socket?.close(1000, "service stopping")
        super.onDestroy()
    }

    fun sendMessage(text: String) {
        val ws = socket
        if (ws == null || !isConnected) {
            connect() // try to recover the connection, then the user can retry sending
            return
        }
        ws.send(JSONObject().put("text", text).toString())
    }

    private fun connect() {
        if (stopped || !repo.isPaired) return
        val baseUrl = repo.serverUrl ?: return
        val wsUrl = baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") +
            "/ws?device_id=${repo.deviceId}&proof=${repo.totpProof()}"

        val request = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                mainHandler.post {
                    listener?.onConnectionStateChanged(true)
                    updateForegroundNotification("Connected")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        isConnected = false
        mainHandler.post {
            listener?.onConnectionStateChanged(false)
            updateForegroundNotification("Reconnecting…")
        }
        if (stopped) return
        mainHandler.postDelayed({ connect() }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun handleIncoming(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        when (json.optString("type")) {
            "reply" -> {
                val reply = json.optString("reply", "")
                mainHandler.post {
                    if (listener != null) {
                        listener?.onReply(reply)
                    } else {
                        NotificationHelper.showMessage(this, "JARVIS", reply)
                    }
                }
            }
            "notification" -> {
                val title = json.optString("title", "JARVIS")
                val body = json.optString("body", "")
                mainHandler.post { NotificationHelper.showMessage(this, title, body) }
            }
            "control" -> {
                val action = json.optString("action")
                val params = json.optJSONObject("params") ?: JSONObject()
                mainHandler.post {
                    if (action == "ring") {
                        ring(params.optString("caller_name", "JARVIS"))
                    } else {
                        PhoneControlExecutor.execute(this, action, params)
                        if (action == "battery_status") {
                            sendMessage("(battery status: ${PhoneControlExecutor.batteryPercent(this)}%)")
                        }
                    }
                }
            }
        }
    }

    /** Full-screen "incoming call" alert. Starting an Activity directly from
     * a background Service is restricted on modern Android, so this uses
     * the documented workaround: a high-priority notification with
     * setFullScreenIntent(), which the system launches full-screen when the
     * device is locked/idle (and shows as a normal heads-up notification
     * otherwise, still tappable to open). */
    private fun ring(callerName: String) {
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_CALL)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NotificationHelper.NOTIF_ID_CALL, notif)
    }

    private fun updateForegroundNotification(statusText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(NotificationHelper.NOTIF_ID_SERVICE, NotificationHelper.serviceNotification(this, statusText))
        }
    }

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
