package com.remote.pccontroller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class GetClipboardActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasProcessed = false
    private val fallbackRunnable = Runnable {
        if (!hasProcessed && !isFinishing) {
            fetchAndSetClipboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        // Invisible dummy view so WindowManager creates a surface and grants window focus
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
            fetchAndSetClipboard()
        }
    }

    private fun fetchAndSetClipboard() {
        if (hasProcessed) return
        hasProcessed = true
        mainHandler.removeCallbacks(fallbackRunnable)

        val prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("auth_token", null)
        if (token.isNullOrEmpty()) {
            Toast.makeText(applicationContext, "🔒 Please open the app and pair with PC first", Toast.LENGTH_LONG).show()
            finish()
            overridePendingTransition(0, 0)
            return
        }

        var ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
        val port = prefs.getString("pc_port", "5050") ?: "5050"

        Toast.makeText(applicationContext, "⏳ Fetching PC clipboard...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                var text = getClipboardText(ip, port, token)
                if (text == null) {
                    // Try UDP auto-discovery fallback
                    val discoveredIp = discoverPcIp()
                    if (discoveredIp != null) {
                        ip = discoveredIp
                        prefs.edit().putString("pc_ip", discoveredIp).apply()
                        text = getClipboardText(discoveredIp, port, token)
                    }
                }

                mainHandler.post {
                    if (text != null) {
                        if (text.isBlank()) {
                            Toast.makeText(applicationContext, "ℹ️ PC clipboard is empty", Toast.LENGTH_SHORT).show()
                        } else {
                            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("PC Clipboard", text)
                            clipboardManager?.setPrimaryClip(clip)

                            val preview = if (text.length > 35) text.take(35).replace("\n", " ") + "..." else text.replace("\n", " ")
                            Toast.makeText(applicationContext, "📥 Copied from PC: \"$preview\"", Toast.LENGTH_LONG).show()
                        }
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

    private fun getClipboardText(ip: String, port: String, token: String?): String? {
        return try {
            val builder = Request.Builder()
                .url("http://$ip:$port/api/clipboard")
                .get()
            if (!token.isNullOrEmpty()) {
                builder.header("X-Auth-Token", token)
            }
            val response = client.newCall(builder.build()).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                json.optString("text", "")
            } else {
                null
            }
        } catch (e: IOException) {
            null
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
