package com.remote.pccontroller

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class SendClipboardActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasProcessed = false
    private val fallbackRunnable = Runnable {
        if (!hasProcessed && !isFinishing) {
            processAndSendClipboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        // Set invisible dummy view so WindowManager creates a surface and grants window focus
        setContentView(View(this))

        // Collapse notification shade
        try {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (ignored: Exception) {}

        // Fallback timer in case onWindowFocusChanged is delayed
        mainHandler.postDelayed(fallbackRunnable, 600)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasProcessed) {
            mainHandler.removeCallbacks(fallbackRunnable)
            processAndSendClipboard()
        }
    }

    private fun processAndSendClipboard() {
        if (hasProcessed) return
        hasProcessed = true
        mainHandler.removeCallbacks(fallbackRunnable)

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = clipboardManager?.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(applicationContext, "⚠️ Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(0, 0)
            return
        }

        val text = clipData.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (text.isBlank()) {
            Toast.makeText(applicationContext, "⚠️ Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            finish()
            overridePendingTransition(0, 0)
            return
        }

        val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        var ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
        val port = prefs.getString("pc_port", "5050") ?: "5050"

        val preview = if (text.length > 30) text.take(30) + "..." else text
        Toast.makeText(applicationContext, "🚀 Sending to PC: \"$preview\"", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                var success = postClipboard(ip, port, text)
                if (!success) {
                    // Fast UDP auto-discovery fallback
                    val discoveredIp = discoverPcIp()
                    if (discoveredIp != null) {
                        ip = discoveredIp
                        prefs.edit().putString("pc_ip", discoveredIp).apply()
                        success = postClipboard(discoveredIp, port, text)
                    }
                }

                mainHandler.post {
                    if (success) {
                        Toast.makeText(applicationContext, "📋 Copied to PC clipboard!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "❌ Failed to connect to PC ($ip:$port)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(applicationContext, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()

        finish()
        overridePendingTransition(0, 0)
    }

    private fun postClipboard(ip: String, port: String, text: String): Boolean {
        return try {
            val jsonPayload = JSONObject().apply {
                put("text", text)
            }.toString()
            val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("http://$ip:$port/api/clipboard")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: IOException) {
            false
        }
    }

    private fun discoverPcIp(): String? {
        return try {
            val udpSocket = DatagramSocket()
            udpSocket.broadcast = true
            udpSocket.soTimeout = 1200
            val reqData = "DISCOVER_PC_SERVER".toByteArray()
            val packet = DatagramPacket(reqData, reqData.size, InetAddress.getByName("255.255.255.255"), 5051)
            udpSocket.send(packet)

            val buf = ByteArray(1024)
            val recvPacket = DatagramPacket(buf, buf.size)
            udpSocket.receive(recvPacket)
            val respStr = String(recvPacket.data, 0, recvPacket.length)
            udpSocket.close()
            if (respStr.contains("pc-remote-controller") || respStr.contains("ready") || respStr.contains("status")) {
                recvPacket.address.hostAddress
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
