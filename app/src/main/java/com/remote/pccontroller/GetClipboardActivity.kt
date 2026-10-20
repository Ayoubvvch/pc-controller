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

class GetClipboardActivity : AppCompatActivity() {

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
        var ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
        val port = prefs.getString("pc_port", "5050") ?: "5050"

        Toast.makeText(applicationContext, "⏳ Fetching PC clipboard...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                var text = ClipboardSyncHelper.getClipboardText(ip, port)
                if (text == null) {
                    val discoveredIp = ClipboardSyncHelper.discoverPcIp()
                    if (discoveredIp != null) {
                        ip = discoveredIp
                        prefs.edit().putString("pc_ip", discoveredIp).apply()
                        text = ClipboardSyncHelper.getClipboardText(discoveredIp, port)
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
}
