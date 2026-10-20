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

class SendClipboardActivity : AppCompatActivity() {

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
                var success = ClipboardSyncHelper.postClipboard(ip, port, text)
                if (!success) {
                    val discoveredIp = ClipboardSyncHelper.discoverPcIp()
                    if (discoveredIp != null) {
                        ip = discoveredIp
                        prefs.edit().putString("pc_ip", discoveredIp).apply()
                        success = ClipboardSyncHelper.postClipboard(discoveredIp, port, text)
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
}
