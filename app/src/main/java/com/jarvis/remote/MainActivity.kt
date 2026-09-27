package com.jarvis.remote

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.jarvis.remote.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity(), JarvisConnectionService.ConnectionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: PairingRepository
    private val http = OkHttpClient()
    private val jsonMedia = "application/json".toMediaType()

    private var service: JarvisConnectionService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as JarvisConnectionService.LocalBinder).getService()
            service?.listener = this@MainActivity
            bound = true
            onConnectionStateChanged(service?.isConnected ?: false)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = PairingRepository(this)
        NotificationHelper.ensureChannels(this)

        requestNotificationPermissionIfNeeded()

        binding.scanQrButton.setOnClickListener { startQrScan() }
        binding.manualEntryButton.setOnClickListener {
            binding.manualEntryLayout.visibility =
                if (binding.manualEntryLayout.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        binding.manualPairButton.setOnClickListener {
            val url = binding.manualUrlInput.text.toString().trim()
            val code = binding.manualCodeInput.text.toString().trim().uppercase()
            if (url.isEmpty() || code.isEmpty()) {
                Toast.makeText(this, "Enter both the URL and the code", Toast.LENGTH_SHORT).show()
            } else {
                attemptPair(url, code)
            }
        }
        binding.sendButton.setOnClickListener { sendCurrentMessage() }
        binding.signOutButton.setOnClickListener { signOut() }

        if (repo.isPaired) {
            showChatScreen()
        } else {
            showPairingScreen()
        }
    }

    override fun onStart() {
        super.onStart()
        if (repo.isPaired) {
            val intent = Intent(this, JarvisConnectionService::class.java)
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.listener = null
            unbindService(serviceConnection)
            bound = false
        }
    }

    // ---------------------------------------------------------------- //
    // Permissions
    // ---------------------------------------------------------------- //

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF_PERMISSION)
        }
    }

    // ---------------------------------------------------------------- //
    // QR scanning — IntentIntegrator defaults to the back/environment-
    // facing camera automatically; no manual camera selection needed.
    // ---------------------------------------------------------------- //

    private fun startQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
            return
        }
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE_TYPES)
            .setPrompt("Point the camera at the JARVIS pairing QR")
            .setBeepEnabled(false)
            .initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startQrScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        try {
            val payload = JSONObject(result.contents)
            val url = payload.getString("url")
            val code = payload.getString("code")
            attemptPair(url, code)
        } catch (_: Exception) {
            binding.pairStatusText.text = "That QR code isn't a JARVIS pairing code"
        }
    }

    // ---------------------------------------------------------------- //
    // Pairing
    // ---------------------------------------------------------------- //

    private fun attemptPair(url: String, code: String) {
        binding.pairStatusText.text = "Pairing…"
        promptDeviceNameIfNeeded { deviceName ->
            val body = JSONObject().apply {
                put("pair_code", code)
                put("device_id", repo.deviceId)
                put("device_name", deviceName)
            }.toString().toRequestBody(jsonMedia)

            val request = Request.Builder().url("$url/pair").post(body).build()
            http.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { binding.pairStatusText.text = "Pairing failed: ${e.message}" }
                }
                override fun onResponse(call: Call, response: Response) {
                    val text = response.body?.string() ?: "{}"
                    val json = JSONObject(text)
                    runOnUiThread {
                        if (response.isSuccessful && json.optString("status") == "ok") {
                            repo.completePairing(url, json.getString("shared_secret"), json.getInt("step_seconds"))
                            binding.pairStatusText.text = "Paired!"
                            showChatScreen()
                            onStart() // start + bind the service now that we're paired
                        } else {
                            binding.pairStatusText.text = json.optString("error", "pairing failed")
                        }
                    }
                }
            })
        }
    }

    private fun promptDeviceNameIfNeeded(onReady: (String) -> Unit) {
        val existing = repo.deviceName
        if (!existing.isNullOrBlank()) {
            onReady(existing)
            return
        }
        val input = EditText(this).apply { hint = "e.g. My Phone" }
        AlertDialog.Builder(this)
            .setTitle("What should JARVIS call this device?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().ifBlank { Build.MODEL ?: "phone" }
                repo.deviceName = name
                onReady(name)
            }
            .show()
    }

    // ---------------------------------------------------------------- //
    // Chat
    // ---------------------------------------------------------------- //

    private fun showPairingScreen() {
        binding.pairingLayout.visibility = android.view.View.VISIBLE
        binding.chatLayout.visibility = android.view.View.GONE
    }

    private fun showChatScreen() {
        binding.pairingLayout.visibility = android.view.View.GONE
        binding.chatLayout.visibility = android.view.View.VISIBLE
    }

    private fun sendCurrentMessage() {
        val text = binding.messageInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.messageInput.setText("")
        addBubble(text, isUser = true)
        service?.sendMessage(text) ?: Toast.makeText(this, "Not connected yet", Toast.LENGTH_SHORT).show()
    }

    override fun onReply(text: String) {
        runOnUiThread { addBubble(text, isUser = false) }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        runOnUiThread {
            binding.connectionDot.setBackgroundResource(if (connected) R.drawable.dot_connected else R.drawable.dot_disconnected)
            binding.connectionStatusText.text = if (connected) "JARVIS — connected" else "JARVIS — reconnecting…"
        }
    }

    private fun addBubble(text: String, isUser: Boolean) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(28, 20, 28, 20)
            setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_jarvis)
        }
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            }
            gravity = if (isUser) Gravity.END else Gravity.START
            addView(bubble)
        }
        binding.messageContainer.addView(row)
        binding.chatScroll.post { binding.chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- //
    // Sign out
    // ---------------------------------------------------------------- //

    private fun signOut() {
        val url = repo.serverUrl
        if (url != null) {
            val request = Request.Builder()
                .url("$url/revoke_self")
                .post(ByteArray(0).toRequestBody(null))
                .header("X-Jarvis-Device", repo.deviceId)
                .header("X-Jarvis-Proof", repo.totpProof())
                .build()
            http.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { /* best-effort */ }
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
        stopService(Intent(this, JarvisConnectionService::class.java))
        repo.clearPairing()
        binding.messageContainer.removeAllViews()
        showPairingScreen()
    }

    companion object {
        private const val REQ_CAMERA_PERMISSION = 100
        private const val REQ_NOTIF_PERMISSION = 101
    }
}
