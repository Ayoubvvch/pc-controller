package com.remote.pccontroller

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class ClipboardActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SEND_CLIP = "com.remote.pccontroller.ACTION_SEND_CLIP"
        const val ACTION_GET_CLIP = "com.remote.pccontroller.ACTION_GET_CLIP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        // Collapse notification shade
        try {
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (ignored: Exception) {}

        when (action) {
            ACTION_SEND_CLIP -> handleSendClipboard(context)
            ACTION_GET_CLIP -> handleGetClipboard(context)
        }
    }

    private fun handleSendClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val hasClip = try {
            clipboard?.hasPrimaryClip() == true
        } catch (e: Exception) {
            false
        }

        var clip: ClipData? = null
        try {
            clip = clipboard?.primaryClip
        } catch (e: SecurityException) {
            // Android security restriction on background clipboard read
        }

        // If background read was restricted, fallback to transparent activity
        if (clip == null && hasClip) {
            val fallbackIntent = Intent(context, SendClipboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(fallbackIntent)
            return
        }

        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(context, "⚠️ Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        if (text.isBlank()) {
            Toast.makeText(context, "⚠️ Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = context.getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        var ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
        val port = prefs.getString("pc_port", "5050") ?: "5050"

        val preview = if (text.length > 30) text.take(30) + "..." else text
        Toast.makeText(context, "🚀 Sending to PC: \"$preview\"", Toast.LENGTH_SHORT).show()

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

                Handler(Looper.getMainLooper()).post {
                    if (success) {
                        Toast.makeText(context, "📋 Copied to PC clipboard!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "❌ Failed to connect to PC ($ip:$port)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleGetClipboard(context: Context) {
        val prefs = context.getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)
        var ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
        val port = prefs.getString("pc_port", "5050") ?: "5050"

        Toast.makeText(context, "⏳ Fetching PC clipboard...", Toast.LENGTH_SHORT).show()

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

                Handler(Looper.getMainLooper()).post {
                    if (text != null) {
                        if (text.isBlank()) {
                            Toast.makeText(context, "ℹ️ PC clipboard is empty", Toast.LENGTH_SHORT).show()
                        } else {
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            try {
                                val newClip = ClipData.newPlainText("PC Clipboard", text)
                                clipboardManager?.setPrimaryClip(newClip)

                                val preview = if (text.length > 35) text.take(35).replace("\n", " ") + "..." else text.replace("\n", " ")
                                Toast.makeText(context, "📥 Copied from PC: \"$preview\"", Toast.LENGTH_LONG).show()
                            } catch (e: SecurityException) {
                                // Fallback to transparent activity if background write is restricted
                                val fallbackIntent = Intent(context, GetClipboardActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                context.startActivity(fallbackIntent)
                            }
                        }
                    } else {
                        Toast.makeText(context, "❌ Failed to connect to PC ($ip:$port)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
