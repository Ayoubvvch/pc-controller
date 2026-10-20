package com.remote.pccontroller

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.PopupMenu
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.core.content.FileProvider
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.os.Handler
import android.os.Looper
import android.widget.Button
import java.util.Locale
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var rvMessages: RecyclerView
    private lateinit var etCommand: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var btnClipboard: ImageButton
    private lateinit var btnBrowseFiles: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var tvConnectionStatus: TextView

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    // File Picker for sending documents/photos to PC
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadFileToPc(uri)
        }
    }

    // Notification permission launcher for Android 13+
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            PcBackgroundService.start(this)
            prefs.edit().putBoolean("keep_background", true).apply()
            Toast.makeText(this, "🔄 Persistent background service started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notification permission is required for the background service", Toast.LENGTH_LONG).show()
        }
    }

    // In-app Update Installation permission launcher (Android 8+)
    private var pendingApkToInstall: File? = null
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                pendingApkToInstall?.let { launchPackageInstaller(it) }
            } else {
                Toast.makeText(this, "Permission not granted to install update", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Hardware volume buttons & HUD
    private var cachedPcVolume: Int = 50
    private val hudHandler = Handler(Looper.getMainLooper())
    private var hideHudRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("pc_remote_prefs", Context.MODE_PRIVATE)

        // Initialize views
        rvMessages = findViewById(R.id.rvMessages)
        etCommand = findViewById(R.id.etCommand)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        btnClipboard = findViewById(R.id.btnClipboard)
        btnBrowseFiles = findViewById(R.id.btnBrowseFiles)
        btnSettings = findViewById(R.id.btnSettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnMore = findViewById(R.id.btnMore)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)

        // Setup RecyclerView
        adapter = ChatAdapter(
            messages = messages,
            client = client,
            onImageClick = { imageUrl -> showFullscreenImage(imageUrl) },
            onImageDownload = { imageUrl -> saveScreenshotToDevice(imageUrl) },
            onImageGallery = { imageUrl -> openImageInGallery(imageUrl) },
            onVolumeChanged = { newVolume -> sendVolumeUpdate(newVolume) },
            onMuteClick = { sendCommand("mute") }
        )
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvMessages.layoutManager = layoutManager
        rvMessages.adapter = adapter

        // Welcome message
        addPcMessage(
            "👋 Welcome to PC Controller!\n" +
            "• 📁 Browse & download files: Tap the folder icon above.\n" +
            "• 📸 Screen capture: Supports both displays or Display 1/2.\n" +
            "• 📎 Send files to PC: Tap the attachment clip next to the text input."
        )

        // Button listeners
        btnSend.setOnClickListener {
            val text = etCommand.text.toString().trim()
            if (text.isNotEmpty()) {
                sendCommand(text)
                etCommand.text.clear()
            }
        }

        btnAttach.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        btnClipboard.setOnClickListener {
            showPasteToPcDialog()
        }

        btnBrowseFiles.setOnClickListener {
            showFileBrowserDialog()
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                btnSend.performClick()
                true
            } else {
                false
            }
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnRefresh.setOnClickListener { checkConnection(autoDiscoverOnFail = true) }
        btnMore.setOnClickListener { v -> showMoreMenu(v) }
        tvConnectionStatus.setOnClickListener { checkConnection(autoDiscoverOnFail = true) }

        // Setup Quick Action Chips
        setupQuickChips()

        // Check connection on start
        checkConnection()

        // Handle incoming share if app was opened via Share Sheet
        handleIncomingShareIntent(intent)

        // Keep persistent background service running if enabled
        if (prefs.getBoolean("keep_background", false)) {
            PcBackgroundService.start(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isHwVolumeEnabled = prefs.getBoolean("hw_volume_control", true)
        if (isHwVolumeEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    adjustPcVolumeDelta(+5)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    adjustPcVolumeDelta(-5)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val isHwVolumeEnabled = prefs.getBoolean("hw_volume_control", true)
        if (isHwVolumeEnabled && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (Intent.ACTION_SEND == action) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }

            if (uri != null) {
                Toast.makeText(this, "🚀 Sending shared file to PC...", Toast.LENGTH_SHORT).show()
                uploadFileToPc(uri)
            } else {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    val trimmed = sharedText.trim()
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        Toast.makeText(this, "🌐 Opening link in PC browser...", Toast.LENGTH_SHORT).show()
                        sendCommand("open $trimmed")
                    } else {
                        sendCommand(trimmed)
                    }
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }

            if (!uris.isNullOrEmpty()) {
                Toast.makeText(this, "🚀 Sending ${uris.size} shared files to PC...", Toast.LENGTH_SHORT).show()
                for (u in uris) {
                    uploadFileToPc(u)
                }
            }
        }
    }

    private fun setupQuickChips() {
        findViewById<TextView>(R.id.chipClipboardHistory)?.setOnClickListener { showClipboardHistoryDialog() }
        findViewById<TextView>(R.id.chipBrowseFiles)?.setOnClickListener { showFileBrowserDialog() }
        findViewById<TextView>(R.id.chipClipboard)?.setOnClickListener { showPasteToPcDialog() }
        findViewById<TextView>(R.id.chipScreenshotAll)?.setOnClickListener { sendCommand("screenshot all") }
        findViewById<TextView>(R.id.chipScreenshot1)?.setOnClickListener { sendCommand("screenshot 1") }
        findViewById<TextView>(R.id.chipScreenshot2)?.setOnClickListener { sendCommand("screenshot 2") }
        findViewById<TextView>(R.id.chipMessi)?.setOnClickListener { sendCommand("messi") }
        findViewById<TextView>(R.id.chipRain)?.setOnClickListener { sendCommand("sleep rain 15min") }
        findViewById<TextView>(R.id.chipRainMin)?.setOnClickListener { sendCommand("sleep rain 15m min") }
        findViewById<TextView>(R.id.chipSoundSlider)?.setOnClickListener { sendCommand("sound") }
        findViewById<TextView>(R.id.chipVol15)?.setOnClickListener { sendCommand("s 15") }
        findViewById<TextView>(R.id.chipVol25)?.setOnClickListener { sendCommand("s 25") }
        findViewById<TextView>(R.id.chipVol40)?.setOnClickListener { sendCommand("s 40") }
        findViewById<TextView>(R.id.chipMute)?.setOnClickListener { sendCommand("mute") }
        findViewById<TextView>(R.id.chipSleep)?.setOnClickListener { sendCommand("sleep") }
        findViewById<TextView>(R.id.chipLock)?.setOnClickListener { sendCommand("lock") }
        findViewById<TextView>(R.id.chipStatus)?.setOnClickListener { sendCommand("status") }
        findViewById<TextView>(R.id.chipCancel)?.setOnClickListener { sendCommand("stop") }
        findViewById<TextView>(R.id.chipRestart)?.setOnClickListener {
            showConfirmationDialog("Restart PC", "Are you sure you want to restart the PC?") {
                sendCommand("restart")
            }
        }
        findViewById<TextView>(R.id.chipShutdown)?.setOnClickListener {
            showConfirmationDialog("Shutdown PC", "Are you sure you want to shut down the PC?") {
                sendCommand("shutdown")
            }
        }
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getBaseUrl(): String {
        val ip = prefs.getString("pc_ip", "192.168.11.110") ?: "192.168.11.110"
        val port = prefs.getString("pc_port", "5050") ?: "5050"
        return "http://$ip:$port"
    }

    private var isDiscovering = false

    private fun discoverPcServer(
        onSuccess: (String) -> Unit,
        onFailed: (() -> Unit)? = null
    ) {
        if (isDiscovering) return
        isDiscovering = true

        runOnUiThread {
            tvConnectionStatus.text = "🟡 Auto-discovering PC in network..."
            tvConnectionStatus.setTextColor(Color.parseColor("#F59E0B"))
        }

        Thread {
            val port = prefs.getString("pc_port", "5050") ?: "5050"
            var foundIp: String? = null

            // 1. Try Fast UDP Broadcast Discovery (~10-50ms)
            try {
                val udpSocket = DatagramSocket()
                udpSocket.broadcast = true
                udpSocket.soTimeout = 1200
                val sendData = "PC_REMOTE_DISCOVER".toByteArray()

                // Broadcast to 255.255.255.255
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddr, 5051)
                udpSocket.send(sendPacket)

                // Also send to local subnet broadcast
                val localSubnetBroadcast = getSubnetBroadcastAddress()
                if (localSubnetBroadcast != null) {
                    val subPacket = DatagramPacket(sendData, sendData.size, localSubnetBroadcast, 5051)
                    udpSocket.send(subPacket)
                }

                val buf = ByteArray(1024)
                val recvPacket = DatagramPacket(buf, buf.size)
                udpSocket.receive(recvPacket)
                val respStr = String(recvPacket.data, 0, recvPacket.length)
                if (respStr.contains("pc-remote-controller") || respStr.contains("ready") || respStr.contains("status")) {
                    foundIp = recvPacket.address.hostAddress
                }
                udpSocket.close()
            } catch (ignored: Exception) {}

            // 2. Fallback: Fast Parallel Subnet Probe (if router filters UDP broadcast)
            if (foundIp == null) {
                foundIp = scanSubnetForServer(port)
            }

            isDiscovering = false

            runOnUiThread {
                if (foundIp != null) {
                    prefs.edit().putString("pc_ip", foundIp).apply()
                    val newBaseUrl = "http://$foundIp:$port"
                    tvConnectionStatus.text = "🟢 Connected ($newBaseUrl)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
                    Toast.makeText(this@MainActivity, "🔍 Found PC: $foundIp", Toast.LENGTH_SHORT).show()
                    onSuccess(foundIp)
                } else {
                    onFailed?.invoke()
                }
            }
        }.start()
    }

    private fun getSubnetBroadcastAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) return broadcast
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun getPhoneLocalIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addrs = networkInterface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun scanSubnetForServer(port: String): String? {
        try {
            val phoneIp = getPhoneLocalIp() ?: return null
            val parts = phoneIp.split(".")
            if (parts.size != 4) return null
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."

            val candidateIps = mutableListOf<String>()
            val currentConfiguredIp = prefs.getString("pc_ip", null)
            if (!currentConfiguredIp.isNullOrEmpty() && currentConfiguredIp.startsWith(prefix)) {
                candidateIps.add(currentConfiguredIp)
            }
            // Add priority range (common DHCP leases)
            for (i in 100..125) {
                val ip = "$prefix$i"
                if (!candidateIps.contains(ip) && ip != phoneIp) candidateIps.add(ip)
            }
            // Add remaining subnet range
            for (i in 1..254) {
                val ip = "$prefix$i"
                if (!candidateIps.contains(ip) && ip != phoneIp) candidateIps.add(ip)
            }

            val found = AtomicReference<String?>(null)
            val executor = Executors.newFixedThreadPool(25)

            for (ip in candidateIps) {
                if (found.get() != null) break
                executor.execute {
                    if (found.get() != null) return@execute
                    if (pingServerCandidate(ip, port)) {
                        found.compareAndSet(null, ip)
                    }
                }
            }

            executor.shutdown()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            executor.shutdownNow()

            return found.get()
        } catch (ignored: Exception) {
            return null
        }
    }

    private fun pingServerCandidate(ip: String, port: String): Boolean {
        try {
            val url = URL("http://$ip:$port/ping")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 400
            conn.readTimeout = 400
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return text.contains("status") || text.contains("success") || text.contains("online") || text.contains("ok")
            }
            conn.disconnect()
        } catch (ignored: Exception) {}
        return false
    }

    private fun checkConnection(autoDiscoverOnFail: Boolean = true) {
        val baseUrl = getBaseUrl()
        tvConnectionStatus.text = "🟡 Checking... ($baseUrl)"
        tvConnectionStatus.setTextColor(Color.parseColor("#F59E0B"))

        val request = Request.Builder()
            .url("$baseUrl/ping")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (autoDiscoverOnFail) {
                        discoverPcServer(
                            onSuccess = {
                                checkConnection(autoDiscoverOnFail = false)
                            },
                            onFailed = {
                                tvConnectionStatus.text = "🔴 Disconnected ($baseUrl)"
                                tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                            }
                        )
                    } else {
                        tvConnectionStatus.text = "🔴 Disconnected ($baseUrl)"
                        tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val successful = response.isSuccessful
                response.close()
                runOnUiThread {
                    if (successful) {
                        tvConnectionStatus.text = "🟢 Connected ($baseUrl)"
                        tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
                        fetchCurrentPcVolume()
                    } else {
                        tvConnectionStatus.text = "🔴 Response Error ($baseUrl)"
                        tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            }
        })
    }

    private fun sendCommand(command: String, isRetry: Boolean = false) {
        if (!isRetry) {
            addUserMessage(command)
        }

        val baseUrl = getBaseUrl()
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val jsonPayload = JSONObject().apply {
            put("command", command)
        }.toString()

        val requestBody = jsonPayload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/command")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (!isRetry) {
                        // Automatically discover PC and retry execution!
                        tvConnectionStatus.text = "🟡 Auto-discovering PC in network..."
                        tvConnectionStatus.setTextColor(Color.parseColor("#F59E0B"))
                        discoverPcServer(
                            onSuccess = {
                                sendCommand(command, isRetry = true)
                            },
                            onFailed = {
                                addPcMessage("❌ Could not connect to PC ($baseUrl)\nNetwork scan completed with no response.\nEnsure server.py is running on your PC and both devices are on the same Wi-Fi network.")
                                tvConnectionStatus.text = "🔴 Disconnected ($baseUrl)"
                                tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                            }
                        )
                    } else {
                        addPcMessage("❌ Could not connect to PC ($baseUrl)\nEnsure the server is running and connected to Wi-Fi.")
                        tvConnectionStatus.text = "🔴 Disconnected ($baseUrl)"
                        tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var reply = body
                var imageUrl: String? = null
                var isVolumeControl = false
                var volumeLevel = 50
                val cmdClean = command.trim().lowercase()
                val isSoundCmd = cmdClean in listOf("sound", "vol", "volume", "صوت", "s", "mute", "كتم") ||
                                cmdClean.startsWith("sound") || cmdClean.startsWith("vol") || cmdClean.startsWith("s ")
                try {
                    val json = JSONObject(body)
                    if (json.has("message")) {
                        reply = json.getString("message")
                    }
                    if (json.has("image_url")) {
                        val path = json.getString("image_url")
                        imageUrl = if (path.startsWith("http")) path else "$baseUrl$path"
                    }
                    if (isSoundCmd || json.optString("type") == "volume" || json.has("volume")) {
                        isVolumeControl = true
                        volumeLevel = json.optInt("volume", 50)
                        cachedPcVolume = volumeLevel
                    }
                } catch (ignored: Exception) {
                    if (isSoundCmd) {
                        isVolumeControl = true
                    }
                }

                runOnUiThread {
                    tvConnectionStatus.text = "🟢 Connected ($baseUrl)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
                    addPcMessage(
                        if (reply.isNotEmpty()) reply else "✅ Command received successfully.",
                        imageUrl = imageUrl,
                        isVolumeControl = isVolumeControl,
                        volumeLevel = volumeLevel
                    )

                    if (isVolumeControl) {
                        showVolumeBottomSheet(volumeLevel)
                    }
                }
            }
        })
    }

    private fun uploadFileToPc(uri: Uri) {
        var fileName = "file_${System.currentTimeMillis()}"
        var fileSize: Long = 0

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val sizeFormatted = formatFileSize(fileSize)
        addUserMessage("📎 Sending: $fileName ($sizeFormatted)")

        val baseUrl = getBaseUrl()
        val encodedName = Base64.encodeToString(fileName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength(): Long = if (fileSize > 0) fileSize else -1L
            override fun writeTo(sink: okio.BufferedSink) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/upload")
            .header("X-Filename-B64", encodedName)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    addPcMessage("❌ Failed to send file to PC: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var reply = body
                try {
                    val json = JSONObject(body)
                    if (json.has("message")) {
                        reply = json.getString("message")
                    }
                } catch (ignored: Exception) {
                }

                runOnUiThread {
                    addPcMessage(if (reply.isNotEmpty()) reply else "✅ File received successfully in PC Downloads!")
                }
            }
        })
    }

    private fun showFullscreenImage(imageUrl: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_image_preview)

        val ivFullImage = dialog.findViewById<ImageView>(R.id.ivFullImage)
        val pbLoading = dialog.findViewById<ProgressBar>(R.id.pbLoading)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnClose)
        val btnOpenGallery = dialog.findViewById<TextView>(R.id.btnOpenGallery)
        val btnDownloadImage = dialog.findViewById<TextView>(R.id.btnDownloadImage)
        val btnShareImage = dialog.findViewById<TextView>(R.id.btnShareImage)

        val zoomListener = ZoomTouchListener(ivFullImage)
        ivFullImage.setOnTouchListener(zoomListener)

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDownloadImage.setOnClickListener { saveScreenshotToDevice(imageUrl) }
        btnOpenGallery.setOnClickListener { openImageInGallery(imageUrl) }
        btnShareImage.setOnClickListener { shareScreenshotImage(imageUrl) }

        pbLoading.visibility = View.VISIBLE

        val cached = ChatAdapter.imageMemoryCache.get(imageUrl)
        if (cached != null) {
            ivFullImage.setImageBitmap(cached)
            pbLoading.visibility = View.GONE
            ivFullImage.post { zoomListener.resetZoom() }
        } else {
            val req = Request.Builder().url(imageUrl).build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { pbLoading.visibility = View.GONE }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        response.close()
                        if (bytes != null) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                ChatAdapter.imageMemoryCache.put(imageUrl, bitmap)
                                runOnUiThread {
                                    pbLoading.visibility = View.GONE
                                    ivFullImage.setImageBitmap(bitmap)
                                    ivFullImage.post { zoomListener.resetZoom() }
                                }
                                return
                            }
                        }
                    }
                    runOnUiThread { pbLoading.visibility = View.GONE }
                }
            })
        }

        dialog.show()
    }

    private fun saveScreenshotToDevice(imageUrl: String, onFinished: ((File?) -> Unit)? = null) {
        Toast.makeText(this, "⏳ Saving screenshot...", Toast.LENGTH_SHORT).show()
        val filename = "Screenshot_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val targetFile = File(downloadsDir, filename)

        val cachedBitmap = ChatAdapter.imageMemoryCache.get(imageUrl)
        if (cachedBitmap != null) {
            try {
                FileOutputStream(targetFile).use { out ->
                    cachedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(targetFile.absolutePath), arrayOf("image/jpeg"), null)
                Toast.makeText(this, "✅ Screenshot saved to Downloads folder!", Toast.LENGTH_LONG).show()
                onFinished?.invoke(targetFile)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val req = Request.Builder().url(imageUrl).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
                    onFinished?.invoke(null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    android.media.MediaScannerConnection.scanFile(this@MainActivity, arrayOf(targetFile.absolutePath), arrayOf("image/jpeg"), null)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "✅ Screenshot saved to Downloads folder successfully!", Toast.LENGTH_LONG).show()
                        onFinished?.invoke(targetFile)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "❌ Failed to save image", Toast.LENGTH_SHORT).show()
                        onFinished?.invoke(null)
                    }
                }
            }
        })
    }

    private fun openImageInGallery(imageUrl: String) {
        val cacheFile = File(cacheDir, "preview_screenshot.jpg")
        val cachedBitmap = ChatAdapter.imageMemoryCache.get(imageUrl)
        if (cachedBitmap != null) {
            try {
                FileOutputStream(cacheFile).use { out ->
                    cachedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                launchGalleryIntent(cacheFile)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        Toast.makeText(this, "⏳ Opening gallery...", Toast.LENGTH_SHORT).show()
        val req = Request.Builder().url(imageUrl).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ Failed to fetch image", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    runOnUiThread { launchGalleryIntent(cacheFile) }
                }
            }
        })
    }

    private fun launchGalleryIntent(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open in Gallery"))
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Could not open gallery app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareScreenshotImage(imageUrl: String) {
        val cacheFile = File(cacheDir, "share_screenshot.jpg")
        val cachedBitmap = ChatAdapter.imageMemoryCache.get(imageUrl)
        if (cachedBitmap != null) {
            try {
                FileOutputStream(cacheFile).use { out ->
                    cachedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                launchShareIntent(cacheFile)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        saveScreenshotToDevice(imageUrl) { file ->
            if (file != null) launchShareIntent(file)
        }
    }

    private fun launchShareIntent(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Screenshot"))
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Could not share image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // PC File Explorer & Downloader
    // ==========================================
    private fun showFileBrowserDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_file_browser)

        val btnBackFolder = dialog.findViewById<ImageButton>(R.id.btnBackFolder)
        val btnCloseBrowser = dialog.findViewById<ImageButton>(R.id.btnCloseBrowser)
        val tvCurrentPath = dialog.findViewById<TextView>(R.id.tvCurrentPath)
        val layoutShortcuts = dialog.findViewById<LinearLayout>(R.id.layoutShortcuts)
        val rvPcFiles = dialog.findViewById<RecyclerView>(R.id.rvPcFiles)
        val pbBrowserLoading = dialog.findViewById<ProgressBar>(R.id.pbBrowserLoading)
        val tvEmptyFiles = dialog.findViewById<TextView>(R.id.tvEmptyFiles)
        val btnDownloadCurrentFolder = dialog.findViewById<ImageButton>(R.id.btnDownloadCurrentFolder)

        rvPcFiles.layoutManager = LinearLayoutManager(this)

        var currentFolder = ""
        var parentFolder: String? = null
        val fileList = mutableListOf<PcFileItem>()
        lateinit var loadFolder: (String?) -> Unit

        val fileAdapter = PcFileAdapter(
            items = fileList,
            onItemClick = { item ->
                if (item.isDirectory) {
                    loadFolder(item.path)
                } else {
                    downloadFileFromPc(item)
                }
            },
            onDownloadClick = { item ->
                if (item.isDirectory) {
                    downloadFolderFromPc(item)
                } else {
                    downloadFileFromPc(item)
                }
            }
        )
        rvPcFiles.adapter = fileAdapter

        loadFolder = { folderPath: String? ->
            pbBrowserLoading.visibility = View.VISIBLE
            tvEmptyFiles.visibility = View.GONE
            val baseUrl = getBaseUrl()
            val encoded = if (!folderPath.isNullOrEmpty()) URLEncoder.encode(folderPath, "UTF-8") else ""
            val url = "$baseUrl/api/browse" + if (encoded.isNotEmpty()) "?path=$encoded" else ""

            val req = Request.Builder().url(url).build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        pbBrowserLoading.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Could not load folder: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        if (json.optString("status") == "success") {
                            currentFolder = json.optString("current_path")
                            parentFolder = if (json.has("parent_path") && !json.isNull("parent_path")) json.optString("parent_path") else null

                            val foldersJson = json.optJSONArray("folders") ?: JSONArray()
                            val filesJson = json.optJSONArray("files") ?: JSONArray()
                            val shortcutsJson = json.optJSONArray("shortcuts") ?: JSONArray()
                            val drivesJson = json.optJSONArray("drives") ?: JSONArray()

                            val newItems = mutableListOf<PcFileItem>()
                            for (i in 0 until foldersJson.length()) {
                                val f = foldersJson.getJSONObject(i)
                                newItems.add(
                                    PcFileItem(
                                        name = f.getString("name"),
                                        path = f.getString("path"),
                                        isDirectory = true
                                    )
                                )
                            }
                            for (i in 0 until filesJson.length()) {
                                val f = filesJson.getJSONObject(i)
                                newItems.add(
                                    PcFileItem(
                                        name = f.getString("name"),
                                        path = f.getString("path"),
                                        isDirectory = false,
                                        sizeStr = f.optString("size_str", ""),
                                        sizeBytes = f.optLong("size", 0),
                                        ext = f.optString("ext", "")
                                    )
                                )
                            }

                            runOnUiThread {
                                pbBrowserLoading.visibility = View.GONE
                                tvCurrentPath.text = currentFolder
                                btnBackFolder.isEnabled = !parentFolder.isNullOrEmpty()
                                btnBackFolder.alpha = if (!parentFolder.isNullOrEmpty()) 1.0f else 0.3f
                                btnDownloadCurrentFolder.visibility = if (!isDriveRoot(currentFolder)) View.VISIBLE else View.GONE

                                fileList.clear()
                                fileList.addAll(newItems)
                                fileAdapter.notifyDataSetChanged()
                                tvEmptyFiles.visibility = if (newItems.isEmpty()) View.VISIBLE else View.GONE

                                // Populate shortcuts and drives
                                layoutShortcuts.removeAllViews()
                                for (i in 0 until shortcutsJson.length()) {
                                    val s = shortcutsJson.getJSONObject(i)
                                    val chip = createShortcutChip(s.getString("name"), s.optString("icon", "📁")) {
                                        loadFolder(s.getString("path"))
                                    }
                                    layoutShortcuts.addView(chip)
                                }
                                for (i in 0 until drivesJson.length()) {
                                    val d = drivesJson.getString(i)
                                    val chip = createShortcutChip(d, "💾") {
                                        loadFolder(d)
                                    }
                                    layoutShortcuts.addView(chip)
                                }
                            }
                            return
                        }
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    runOnUiThread {
                        pbBrowserLoading.visibility = View.GONE
                    }
                }
            })
        }

        btnBackFolder.setOnClickListener {
            if (!parentFolder.isNullOrEmpty()) {
                loadFolder(parentFolder)
            }
        }
        btnDownloadCurrentFolder.setOnClickListener {
            if (!currentFolder.isNullOrEmpty() && !isDriveRoot(currentFolder)) {
                val folderName = currentFolder.substringAfterLast('\\').ifEmpty { currentFolder.substringAfterLast('/') }
                val currentFolderItem = PcFileItem(
                    name = folderName,
                    path = currentFolder,
                    isDirectory = true
                )
                downloadFolderFromPc(currentFolderItem)
            }
        }
        btnCloseBrowser.setOnClickListener { dialog.dismiss() }

        loadFolder(null)
        dialog.show()
    }

    private fun createShortcutChip(title: String, icon: String, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = "$icon $title"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(28, 14, 28, 14)
            isClickable = true
            isFocusable = true
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 16
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
        return tv
    }

    private fun isDriveRoot(path: String?): Boolean {
        if (path.isNullOrBlank()) return true
        val trimmed = path.trim().replace('/', '\\').trimEnd('\\')
        return (trimmed.length <= 2 && trimmed.endsWith(":")) || trimmed == "\\" || trimmed == "/"
    }

    private fun downloadFolderFromPc(item: PcFileItem) {
        if (isDriveRoot(item.path)) {
            Toast.makeText(this, "❌ Transferring an entire drive root is not allowed.", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = getBaseUrl()
        val encoded = URLEncoder.encode(item.path, "UTF-8")
        val url = "$baseUrl/api/files/folder_info?path=$encoded"

        Toast.makeText(this, "⏳ Checking folder size on PC...", Toast.LENGTH_SHORT).show()

        val req = Request.Builder().url(url).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "⚠️ Could not check folder size: ${e.message}", Toast.LENGTH_SHORT).show()
                    showChooseDestinationDialog(item, "Folder", isLarge = false)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var isLarge = false
                var isDrive = false
                var sizeStr = "Folder"
                try {
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        isLarge = json.optBoolean("is_large", false)
                        isDrive = json.optBoolean("is_drive", false)
                        sizeStr = json.optString("size_str", "Folder")
                    } else if (json.optBoolean("is_drive", false)) {
                        isDrive = true
                    }
                } catch (ignored: Exception) {}

                runOnUiThread {
                    if (isDrive) {
                        Toast.makeText(this@MainActivity, "❌ Transferring an entire drive root is not allowed.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    if (isLarge) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("⚠️ Large Folder Warning")
                            .setMessage("This folder size is $sizeStr (exceeds 500 MB).\n\nTransferring large folders over SSH may take some time depending on your Wi-Fi.\n\nA live CMD terminal window will open on your PC to show real-time progress and allow stopping anytime.\n\nDo you want to proceed?")
                            .setPositiveButton("Proceed") { _, _ ->
                                showChooseDestinationDialog(item, sizeStr, isLarge = true)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        showChooseDestinationDialog(item, sizeStr, isLarge = false)
                    }
                }
            }
        })
    }

    private fun showChooseDestinationDialog(item: PcFileItem, sizeStr: String, isLarge: Boolean) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_choose_destination)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val tvFolderTitle = dialog.findViewById<TextView>(R.id.tvFolderTitle)
        val tvFolderSubtitle = dialog.findViewById<TextView>(R.id.tvFolderSubtitle)
        val layoutLargeWarning = dialog.findViewById<LinearLayout>(R.id.layoutLargeWarning)
        val tvLargeWarningText = dialog.findViewById<TextView>(R.id.tvLargeWarningText)
        val etTargetDestPath = dialog.findViewById<EditText>(R.id.etTargetDestPath)
        val btnCancelTransfer = dialog.findViewById<TextView>(R.id.btnCancelTransfer)
        val btnConfirmTransfer = dialog.findViewById<TextView>(R.id.btnConfirmTransfer)

        val chipDestDownload = dialog.findViewById<TextView>(R.id.chipDestDownload)
        val chipDestDocuments = dialog.findViewById<TextView>(R.id.chipDestDocuments)
        val chipDestPictures = dialog.findViewById<TextView>(R.id.chipDestPictures)
        val chipDestMovies = dialog.findViewById<TextView>(R.id.chipDestMovies)
        val chipDestMusic = dialog.findViewById<TextView>(R.id.chipDestMusic)

        tvFolderTitle.text = item.name
        tvFolderSubtitle.text = "Size: $sizeStr • Source: ${item.path}"

        if (isLarge) {
            layoutLargeWarning.visibility = View.VISIBLE
            tvLargeWarningText.text = "Large Folder ($sizeStr): A live CMD window will open on your PC to monitor progress or stop anytime."
        } else {
            layoutLargeWarning.visibility = View.GONE
        }

        fun selectChip(selectedChip: TextView, path: String) {
            val chips = listOf(chipDestDownload, chipDestDocuments, chipDestPictures, chipDestMovies, chipDestMusic)
            for (c in chips) {
                if (c == selectedChip) {
                    c.setTextColor(Color.WHITE)
                    c.setBackgroundResource(R.drawable.bg_input)
                } else {
                    c.setTextColor(resources.getColor(R.color.text_secondary, null))
                    c.setBackgroundResource(R.drawable.bg_chip)
                }
            }
            etTargetDestPath.setText(path)
            etTargetDestPath.setSelection(path.length)
        }

        chipDestDownload.setOnClickListener { selectChip(chipDestDownload, "/sdcard/Download/") }
        chipDestDocuments.setOnClickListener { selectChip(chipDestDocuments, "/sdcard/Documents/") }
        chipDestPictures.setOnClickListener { selectChip(chipDestPictures, "/sdcard/Pictures/") }
        chipDestMovies.setOnClickListener { selectChip(chipDestMovies, "/sdcard/Movies/") }
        chipDestMusic.setOnClickListener { selectChip(chipDestMusic, "/sdcard/Music/") }

        btnCancelTransfer.setOnClickListener { dialog.dismiss() }

        btnConfirmTransfer.setOnClickListener {
            var destPath = etTargetDestPath.text.toString().trim()
            if (destPath.isEmpty()) {
                destPath = "/sdcard/Download/"
            }
            if (!destPath.endsWith("/")) {
                destPath += "/"
            }
            dialog.dismiss()
            startSshTransfer(item, destPath, sizeStr, isFolder = true)
        }

        dialog.show()
    }

    private fun downloadFileFromPc(item: PcFileItem) {
        if (item.isDirectory) {
            downloadFolderFromPc(item)
            return
        }

        val ext = item.name.substringAfterLast('.', "").lowercase()
        val isMedia = ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "raw", "dng", "mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "3gp", "m4v", "ts")
        val targetDest = if (isMedia) "/sdcard/Pictures/" else "/sdcard/Download/"

        startSshTransfer(item, targetDest, item.sizeStr, isFolder = false)
    }

    private fun startSshTransfer(item: PcFileItem, destPath: String, sizeStr: String, isFolder: Boolean) {
        val baseUrl = getBaseUrl()
        val phoneIp = getPhoneLocalIp() ?: ""

        val typeLabel = if (isFolder) "Folder" else "File"
        Toast.makeText(this, "🚀 Launching SSH transfer on PC for ${item.name}...", Toast.LENGTH_SHORT).show()

        val jsonPayload = JSONObject().apply {
            put("path", item.path)
            put("name", item.name)
            put("size", sizeStr)
            put("phone_ip", phoneIp)
            put("dest", destPath)
            put("is_folder", isFolder)
        }.toString()

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url("$baseUrl/api/files/send_ssh")
            .post(jsonPayload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (!isFolder) {
                        Toast.makeText(this@MainActivity, "⚠️ SSH transfer failed, falling back to direct download...", Toast.LENGTH_SHORT).show()
                        downloadFileHttpFallback(item)
                    } else {
                        Toast.makeText(this@MainActivity, "❌ Failed to reach PC server: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var message = "🚀 SSH transfer launched on PC for ${item.name}!"
                var isSuccess = response.isSuccessful
                var resolvedDest = destPath
                try {
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        isSuccess = true
                        message = json.optString("message", message)
                        resolvedDest = json.optString("dest", destPath)
                    } else {
                        isSuccess = false
                        message = json.optString("message", "Transfer failed")
                    }
                } catch (ignored: Exception) {}

                runOnUiThread {
                    if (isSuccess) {
                        Toast.makeText(this@MainActivity, "🖥️ CMD transfer started on PC to $resolvedDest!", Toast.LENGTH_LONG).show()
                        addPcMessage(
                            "🚀 SSH $typeLabel transfer started on PC!\n" +
                            "📁 Item: ${item.name}\n" +
                            "💾 Size: $sizeStr\n" +
                            "📍 Destination on phone: $resolvedDest\n" +
                            "⚡ Overwrite Mode: Matching files at destination are replaced.\n" +
                            "🖥️ A live CMD terminal window has opened on your PC showing real-time progress. You can monitor or cancel it from your PC anytime."
                        )
                    } else {
                        if (!isFolder) {
                            Toast.makeText(this@MainActivity, "⚠️ SSH transfer failed, falling back to direct download...", Toast.LENGTH_SHORT).show()
                            downloadFileHttpFallback(item)
                        } else {
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }

    private fun downloadFileHttpFallback(item: PcFileItem) {
        val baseUrl = getBaseUrl()
        val encoded = URLEncoder.encode(item.path, "UTF-8")
        val url = "$baseUrl/api/download?path=$encoded"
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val isMedia = ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "raw", "dng", "mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "3gp", "m4v", "ts")

        val req = Request.Builder().url(url).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ Failed to download file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val targetDir = if (isMedia) {
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        } else {
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        }
                        if (!targetDir.exists()) targetDir.mkdirs()

                        var targetFile = File(targetDir, item.name)
                        var counter = 1
                        val nameWithoutExt = targetFile.nameWithoutExtension
                        val fileExt = targetFile.extension
                        while (targetFile.exists()) {
                            val newName = if (fileExt.isNotEmpty()) "$nameWithoutExt ($counter).$fileExt" else "$nameWithoutExt ($counter)"
                            targetFile = File(targetDir, newName)
                            counter++
                        }

                        response.body?.byteStream()?.use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        val folderLabel = if (isMedia) "Pictures" else "Download"
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "✅ Saved ${targetFile.name} to $folderLabel folder!", Toast.LENGTH_LONG).show()
                            addPcMessage(
                                "📥 File downloaded from PC successfully!\n" +
                                "📁 File: ${targetFile.name}\n" +
                                "💾 Size: ${item.sizeStr}\n" +
                                "📍 Saved to: $folderLabel/${targetFile.name}"
                            )
                        }
                        return
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ Failed to save file", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes >= 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        return "$bytes B"
    }

    private fun addUserMessage(text: String) {
        val time = timeFormat.format(Date())
        messages.add(ChatMessage(text, isUser = true, time = time))
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    private fun addPcMessage(
        text: String,
        imageUrl: String? = null,
        isVolumeControl: Boolean = false,
        volumeLevel: Int = 50
    ) {
        val time = timeFormat.format(Date())
        messages.add(
            ChatMessage(
                message = text,
                isUser = false,
                time = time,
                imageUrl = imageUrl,
                isVolumeControl = isVolumeControl,
                volumeLevel = volumeLevel
            )
        )
        adapter.notifyItemInserted(messages.size - 1)
        rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    private var lastVolumeSendTime = 0L
    private var pendingVolumeRunnable: Runnable? = null
    private val volumeHandler = Handler(Looper.getMainLooper())

    private fun sendVolumeUpdate(volume: Int) {
        pendingVolumeRunnable?.let { volumeHandler.removeCallbacks(it) }

        val now = System.currentTimeMillis()
        if (now - lastVolumeSendTime > 120) {
            lastVolumeSendTime = now
            executeVolumeRequest(volume)
        } else {
            pendingVolumeRunnable = Runnable {
                lastVolumeSendTime = System.currentTimeMillis()
                executeVolumeRequest(volume)
            }
            volumeHandler.postDelayed(pendingVolumeRunnable!!, 120)
        }
    }

    private fun executeVolumeRequest(volume: Int) {
        val baseUrl = getBaseUrl()
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val jsonPayload = JSONObject().apply {
            put("volume", volume)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/volume")
            .post(jsonPayload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private var activeVolumeBottomSheet: BottomSheetDialog? = null

    private fun showVolumeBottomSheet(initialVolume: Int) {
        if (isFinishing || isDestroyed) return

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(currentFocus?.windowToken ?: window.decorView.windowToken, 0)

        activeVolumeBottomSheet?.let { dialog ->
            if (dialog.isShowing) {
                val sb = dialog.findViewById<SeekBar>(R.id.sbBsVolume)
                val tv = dialog.findViewById<TextView>(R.id.tvBsVolumePercent)
                sb?.progress = initialVolume
                tv?.text = "$initialVolume%"
                return
            }
        }

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_volume_slider, null)
        dialog.setContentView(view)

        val tvPercent = view.findViewById<TextView>(R.id.tvBsVolumePercent)
        val sbVolume = view.findViewById<SeekBar>(R.id.sbBsVolume)
        val btnClose = view.findViewById<TextView>(R.id.btnBsClose)

        val btnMute = view.findViewById<TextView>(R.id.btnBsMute)
        val btnVol15 = view.findViewById<TextView>(R.id.btnBsVol15)
        val btnVol30 = view.findViewById<TextView>(R.id.btnBsVol30)
        val btnVol50 = view.findViewById<TextView>(R.id.btnBsVol50)
        val btnVol75 = view.findViewById<TextView>(R.id.btnBsVol75)
        val btnVol100 = view.findViewById<TextView>(R.id.btnBsVol100)

        tvPercent.text = "$initialVolume%"
        sbVolume.progress = initialVolume

        val updateDialogVol = { vol: Int ->
            tvPercent.text = "$vol%"
            sbVolume.progress = vol
            sendVolumeUpdate(vol)
        }

        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPercent.text = "$progress%"
                    sendVolumeUpdate(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 50
                tvPercent.text = "$progress%"
                sendVolumeUpdate(progress)
            }
        })

        btnMute.setOnClickListener {
            sendCommand("mute")
        }
        btnVol15.setOnClickListener { updateDialogVol(15) }
        btnVol30.setOnClickListener { updateDialogVol(30) }
        btnVol50.setOnClickListener { updateDialogVol(50) }
        btnVol75.setOnClickListener { updateDialogVol(75) }
        btnVol100.setOnClickListener { updateDialogVol(100) }

        btnClose.setOnClickListener { dialog.dismiss() }

        activeVolumeBottomSheet = dialog
        dialog.show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIp = view.findViewById<EditText>(R.id.etIpAddress)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val btnAutoDiscover = view.findViewById<View>(R.id.btnAutoDiscover)
        val tvAutoDiscoverText = view.findViewById<TextView>(R.id.tvAutoDiscoverText)

        val switchKeepBackground = view.findViewById<SwitchCompat>(R.id.switchKeepBackground)
        val switchHwVolume = view.findViewById<SwitchCompat>(R.id.switchHwVolume)
        val btnCheckUpdates = view.findViewById<View>(R.id.btnCheckUpdates)
        val tvCheckUpdatesText = view.findViewById<TextView>(R.id.tvCheckUpdatesText)

        etIp.setText(prefs.getString("pc_ip", "192.168.11.110"))
        etPort.setText(prefs.getString("pc_port", "5050"))
        switchKeepBackground?.isChecked = prefs.getBoolean("keep_background", false)
        switchHwVolume?.isChecked = prefs.getBoolean("hw_volume_control", true)

        btnAutoDiscover?.setOnClickListener {
            tvAutoDiscoverText?.text = "⏳ Searching network..."
            discoverPcServer(
                onSuccess = { foundIp ->
                    etIp.setText(foundIp)
                    tvAutoDiscoverText?.text = "✅ Found PC: $foundIp"
                },
                onFailed = {
                    tvAutoDiscoverText?.text = "❌ PC not found. Ensure server is running."
                }
            )
        }

        btnCheckUpdates?.setOnClickListener {
            checkForUpdates(tvCheckUpdatesText)
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val newIp = etIp.text.toString().trim()
                val newPort = etPort.text.toString().trim()
                val isBgEnabled = switchKeepBackground?.isChecked ?: false
                val isHwVolEnabled = switchHwVolume?.isChecked ?: true

                prefs.edit().putBoolean("hw_volume_control", isHwVolEnabled).apply()

                if (newIp.isNotEmpty()) {
                    prefs.edit()
                        .putString("pc_ip", newIp)
                        .putString("pc_port", if (newPort.isNotEmpty()) newPort else "5050")
                        .apply()
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                    checkConnection()
                }

                // Handle background service toggle
                val wasBgEnabled = prefs.getBoolean("keep_background", false)
                if (isBgEnabled != wasBgEnabled) {
                    if (isBgEnabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            PcBackgroundService.start(this)
                            prefs.edit().putBoolean("keep_background", true).apply()
                            Toast.makeText(this, "🔄 Persistent background service started", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        PcBackgroundService.stop(this)
                        prefs.edit().putBoolean("keep_background", false).apply()
                        Toast.makeText(this, "Background service stopped", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkForUpdates(statusView: TextView?) {
        statusView?.text = "⏳ Checking GitHub Releases..."
        val request = Request.Builder()
            .url("https://api.github.com/repos/Ayoubvvch/pc-controller/releases/latest")
            .header("User-Agent", "PCRemote-Android")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    statusView?.text = "❌ Check failed: ${e.message}"
                    Toast.makeText(this@MainActivity, "Could not check for updates", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "")
                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    runOnUiThread {
                        val currentVersion = "v2.1.3"
                        if (tagName.isNotEmpty() && tagName != currentVersion) {
                            statusView?.text = "🚀 New update available: $tagName"
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("🚀 New Version Available: $tagName")
                                .setMessage("A new version ($tagName) is available on GitHub with the latest improvements!\n\nWould you like to download and install it now?")
                                .setPositiveButton("🚀 Download & Install") { _, _ ->
                                    if (apkUrl != null) {
                                        startInAppUpdateDownload(apkUrl, tagName)
                                    } else {
                                        val targetUrl = "https://github.com/Ayoubvvch/pc-controller/releases/latest"
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                        startActivity(browserIntent)
                                    }
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        } else {
                            statusView?.text = "✅ App is up to date ($currentVersion)"
                            Toast.makeText(this@MainActivity, "You have the latest version ($currentVersion)!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusView?.text = "❌ Error parsing update info"
                    }
                }
            }
        })
    }

    private fun startInAppUpdateDownload(apkUrl: String, tagName: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_download_update, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDownloadTitle)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvDownloadStatus)
        val tvBytes = dialogView.findViewById<TextView>(R.id.tvDownloadBytes)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvDownloadPercent)
        val pb = dialogView.findViewById<ProgressBar>(R.id.pbDownloadProgress)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancelDownload)

        tvTitle.text = "📥 Downloading Update ($tagName)"
        tvStatus.text = "Connecting to GitHub Releases..."

        val downloadDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        downloadDialog.show()

        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "PCRemote-Android")
            .build()
        val downloadCall = client.newCall(request)

        btnCancel.setOnClickListener {
            downloadCall.cancel()
            downloadDialog.dismiss()
            Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
        }

        downloadCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                runOnUiThread {
                    downloadDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        downloadDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Server returned error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                val body = response.body
                if (body == null) {
                    runOnUiThread {
                        downloadDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Empty response body", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val totalLength = body.contentLength()
                val targetDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                val apkFile = File(targetDir, "PCRemote-$tagName.apk")

                try {
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(apkFile)
                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L
                    var lastUpdateMs = 0L

                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        bytesDownloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs > 100 || bytesDownloaded == totalLength) {
                            lastUpdateMs = now
                            val percent = if (totalLength > 0) ((bytesDownloaded * 100) / totalLength).toInt() else 0
                            val downloadedStr = String.format(Locale.US, "%.1f MB", bytesDownloaded / (1024.0 * 1024.0))
                            val totalStr = if (totalLength > 0) String.format(Locale.US, "%.1f MB", totalLength / (1024.0 * 1024.0)) else "Unknown"

                            runOnUiThread {
                                pb.isIndeterminate = totalLength <= 0
                                if (totalLength > 0) {
                                    pb.progress = percent
                                    tvPercent.text = "$percent%"
                                } else {
                                    tvPercent.text = ""
                                }
                                tvStatus.text = "Downloading PCRemote-$tagName.apk..."
                                tvBytes.text = "$downloadedStr / $totalStr"
                            }
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    runOnUiThread {
                        downloadDialog.dismiss()
                        Toast.makeText(this@MainActivity, "✅ Download complete! Starting installer...", Toast.LENGTH_SHORT).show()
                        installDownloadedApk(apkFile)
                    }
                } catch (e: Exception) {
                    if (call.isCanceled()) return
                    runOnUiThread {
                        downloadDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun installDownloadedApk(apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(this, "Downloaded APK file not found", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingApkToInstall = apkFile
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("To install the new update, Android requires permission to allow install from this app.\n\nPlease enable 'Allow from this source' on the next screen.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        installPermissionLauncher.launch(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }

        launchPackageInstaller(apkFile)
    }

    private fun launchPackageInstaller(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not launch package installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun adjustPcVolumeDelta(delta: Int) {
        cachedPcVolume = (cachedPcVolume + delta).coerceIn(0, 100)
        showVolumeHud(cachedPcVolume)
        sendVolumeUpdate(cachedPcVolume)
    }

    private fun showVolumeHud(volume: Int) {
        val hud = findViewById<LinearLayout>(R.id.layoutVolumeHud) ?: return
        val tvText = findViewById<TextView>(R.id.tvVolumeHudText)
        val tvIcon = findViewById<TextView>(R.id.tvVolumeHudIcon)
        val pb = findViewById<ProgressBar>(R.id.pbVolumeHud)

        tvText?.text = "PC Volume: $volume%"
        tvIcon?.text = if (volume == 0) "🔇" else if (volume < 30) "🔉" else "🔊"
        pb?.progress = volume

        hud.alpha = 1f
        hud.visibility = View.VISIBLE

        activeVolumeBottomSheet?.let { dialog ->
            if (dialog.isShowing) {
                val sb = dialog.findViewById<SeekBar>(R.id.sbBsVolume)
                val tv = dialog.findViewById<TextView>(R.id.tvBsVolumePercent)
                sb?.progress = volume
                tv?.text = "$volume%"
            }
        }

        hideHudRunnable?.let { hudHandler.removeCallbacks(it) }
        hideHudRunnable = Runnable {
            hud.animate().alpha(0f).setDuration(250).withEndAction {
                hud.visibility = View.GONE
            }.start()
        }
        hudHandler.postDelayed(hideHudRunnable!!, 1500)
    }

    private fun fetchCurrentPcVolume() {
        val baseUrl = getBaseUrl()
        val request = Request.Builder().url("$baseUrl/api/volume").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    val vol = json.optInt("volume", -1)
                    if (vol >= 0) {
                        cachedPcVolume = vol
                    }
                } catch (ignored: Exception) {}
            }
        })
    }

    private fun showPasteToPcDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_paste_clipboard, null)
        val etText = view.findViewById<EditText>(R.id.etClipboardText)
        val btnPasteFromPhone = view.findViewById<TextView>(R.id.btnPasteFromPhone)
        val btnClear = view.findViewById<TextView>(R.id.btnClearText)
        val tvCharCount = view.findViewById<TextView>(R.id.tvCharCount)

        fun updateCount() {
            val len = etText?.text?.length ?: 0
            tvCharCount?.text = "$len character${if (len == 1) "" else "s"}"
        }

        etText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCount()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) {
                    etText?.setText(text)
                    etText?.setSelection(text.length)
                    updateCount()
                }
            }
        }

        btnPasteFromPhone?.setOnClickListener {
            if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0)?.coerceToText(this)?.toString()
                    if (!text.isNullOrBlank()) {
                        etText?.setText(text)
                        etText?.setSelection(text.length)
                        updateCount()
                        Toast.makeText(this, "📋 Pasted from phone clipboard", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear?.setOnClickListener {
            etText?.setText("")
            updateCount()
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("🚀 Send to PC") { _, _ ->
                val textToSend = etText?.text?.toString() ?: ""
                if (textToSend.isBlank()) {
                    Toast.makeText(this, "Please enter or paste some text first", Toast.LENGTH_SHORT).show()
                } else {
                    sendClipboardToPc(textToSend)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendClipboardToPc(text: String, isRetry: Boolean = false) {
        val preview = if (text.length > 80) text.take(80) + "..." else text
        if (!isRetry) {
            addUserMessage("📋 [Sent to PC Clipboard]:\n$preview")
        }

        val baseUrl = getBaseUrl()
        val jsonPayload = JSONObject().apply {
            put("text", text)
        }.toString()

        val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/clipboard")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (!isRetry) {
                        tvConnectionStatus.text = "🟡 Auto-discovering PC in network..."
                        tvConnectionStatus.setTextColor(Color.parseColor("#F59E0B"))
                        discoverPcServer(
                            onSuccess = {
                                sendClipboardToPc(text, isRetry = true)
                            },
                            onFailed = {
                                addPcMessage("❌ Failed to send text to PC clipboard ($baseUrl)\nCheck your connection and ensure the server is running.")
                                tvConnectionStatus.text = "🔴 Disconnected ($baseUrl)"
                                tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                                Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        addPcMessage("❌ Failed to send text to PC clipboard ($baseUrl)\nEnsure the server is running and connected to Wi-Fi.")
                        tvConnectionStatus.text = "🔴 Disconnected ($baseUrl)"
                        tvConnectionStatus.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var success = response.isSuccessful
                var message = "📋 Text copied to PC clipboard successfully!"
                try {
                    val json = JSONObject(body)
                    if (json.has("message")) {
                        message = json.getString("message")
                    }
                    if (json.optString("status") == "error") {
                        success = false
                    }
                } catch (ignored: Exception) {}

                runOnUiThread {
                    tvConnectionStatus.text = "🟢 Connected ($baseUrl)"
                    tvConnectionStatus.setTextColor(Color.parseColor("#10B981"))
                    if (success) {
                        val fullReply = "$message\n\nPreview: \"$preview\"\n\n💡 Ready! Press Ctrl + V on your PC to paste."
                        addPcMessage(fullReply)
                        Toast.makeText(this@MainActivity, "📋 Copied to PC clipboard!", Toast.LENGTH_SHORT).show()
                    } else {
                        addPcMessage("❌ Could not copy to PC clipboard: $message")
                    }
                }
            }
        })
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "📋 PC Clipboard (Win + V)")
        popup.menu.add(0, 2, 1, "📁 Browse PC Files")
        popup.menu.add(0, 3, 2, "⚙️ Settings & Updates")
        popup.menu.add(0, 4, 3, "🔄 Refresh Connection")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showClipboardHistoryDialog()
                    true
                }
                2 -> {
                    showFileBrowserDialog()
                    true
                }
                3 -> {
                    showSettingsDialog()
                    true
                }
                4 -> {
                    checkConnection(autoDiscoverOnFail = true)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showClipboardHistoryDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_clipboard_history)

        val btnRefreshHistory = dialog.findViewById<ImageButton>(R.id.btnRefreshHistory)
        val btnClearHistory = dialog.findViewById<ImageButton>(R.id.btnClearHistory)
        val btnCloseHistory = dialog.findViewById<ImageButton>(R.id.btnCloseHistory)
        val btnCloseHistoryBottom = dialog.findViewById<TextView>(R.id.btnCloseHistoryBottom)
        val btnQuickPasteToPc = dialog.findViewById<TextView>(R.id.btnQuickPasteToPc)
        val etSearchHistory = dialog.findViewById<EditText>(R.id.etSearchHistory)
        val tvHistoryCount = dialog.findViewById<TextView>(R.id.tvHistoryCount)
        val pbHistoryLoading = dialog.findViewById<ProgressBar>(R.id.pbHistoryLoading)
        val tvEmptyHistory = dialog.findViewById<TextView>(R.id.tvEmptyHistory)
        val rvClipboardHistory = dialog.findViewById<RecyclerView>(R.id.rvClipboardHistory)

        rvClipboardHistory.layoutManager = LinearLayoutManager(this)

        var allItems = listOf<PcClipboardHistoryItem>()

        val adapter = ClipboardHistoryAdapter(
            items = emptyList(),
            onCopyPhone = { item ->
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = android.content.ClipData.newPlainText("PC Clipboard", item.text)
                clipboardManager?.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Copied to phone clipboard!", Toast.LENGTH_SHORT).show()
            },
            onSetPc = { item ->
                sendClipboardToPc(item.text)
                Toast.makeText(this, "💻 Activated on PC clipboard!", Toast.LENGTH_SHORT).show()
            }
        )
        rvClipboardHistory.adapter = adapter

        fun applyFilter(query: String) {
            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) {
                allItems
            } else {
                allItems.filter { it.text.lowercase().contains(q) }
            }
            adapter.updateList(filtered)
            tvHistoryCount.text = "${filtered.size} item${if (filtered.size == 1) "" else "s"}"
            if (filtered.isEmpty()) {
                tvEmptyHistory.visibility = View.VISIBLE
                if (allItems.isEmpty()) {
                    tvEmptyHistory.text = "📋 No PC clipboard history yet.\n\nItems copied on your PC (or sent from this phone) will automatically appear here!"
                } else {
                    tvEmptyHistory.text = "🔍 No clipboard items matched \"$query\""
                }
            } else {
                tvEmptyHistory.visibility = View.GONE
            }
        }

        fun loadHistory() {
            pbHistoryLoading.visibility = View.VISIBLE
            tvEmptyHistory.visibility = View.GONE
            val baseUrl = getBaseUrl()

            val request = Request.Builder()
                .url("$baseUrl/api/clipboard/history")
                .get()
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        pbHistoryLoading.visibility = View.GONE
                        tvEmptyHistory.visibility = View.VISIBLE
                        tvEmptyHistory.text = "❌ Failed to connect to PC.\nMake sure the server is running."
                        Toast.makeText(this@MainActivity, "Failed to load clipboard history", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    val list = mutableListOf<PcClipboardHistoryItem>()
                    try {
                        val json = JSONObject(body)
                        val historyArr = json.optJSONArray("history") ?: JSONArray()
                        for (i in 0 until historyArr.length()) {
                            val obj = historyArr.getJSONObject(i)
                            list.add(
                                PcClipboardHistoryItem(
                                    text = obj.optString("text", ""),
                                    time = obj.optString("time", ""),
                                    timestamp = obj.optDouble("timestamp", 0.0),
                                    length = obj.optInt("len", 0)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    runOnUiThread {
                        pbHistoryLoading.visibility = View.GONE
                        allItems = list
                        applyFilter(etSearchHistory.text?.toString() ?: "")
                    }
                }
            })
        }

        etSearchHistory.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnRefreshHistory.setOnClickListener { loadHistory() }

        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear PC Clipboard History")
                .setMessage("Are you sure you want to delete all saved PC clipboard history?")
                .setPositiveButton("Clear All") { _, _ ->
                    val baseUrl = getBaseUrl()
                    val emptyBody = "".toRequestBody("application/json".toMediaTypeOrNull())
                    val clearReq = Request.Builder()
                        .url("$baseUrl/api/clipboard/clear")
                        .post(emptyBody)
                        .build()
                    client.newCall(clearReq).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Failed to clear history", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onResponse(call: Call, response: Response) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "🗑️ Clipboard history cleared", Toast.LENGTH_SHORT).show()
                                loadHistory()
                            }
                        }
                    })
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnQuickPasteToPc.setOnClickListener {
            showPasteToPcDialog()
        }

        btnCloseHistory.setOnClickListener { dialog.dismiss() }
        btnCloseHistoryBottom.setOnClickListener { dialog.dismiss() }

        dialog.show()
        loadHistory()
    }
}

data class PcClipboardHistoryItem(
    val text: String,
    val time: String,
    val timestamp: Double,
    val length: Int
)

class ClipboardHistoryAdapter(
    private var items: List<PcClipboardHistoryItem>,
    private val onCopyPhone: (PcClipboardHistoryItem) -> Unit,
    private val onSetPc: (PcClipboardHistoryItem) -> Unit
) : RecyclerView.Adapter<ClipboardHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvItemTime)
        val tvLength: TextView = view.findViewById(R.id.tvItemLength)
        val tvContent: TextView = view.findViewById(R.id.tvItemContent)
        val btnCopyPhone: TextView = view.findViewById(R.id.btnItemCopyPhone)
        val btnSetPc: TextView = view.findViewById(R.id.btnItemSetPc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_clipboard_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTime.text = if (item.time.isNotBlank()) "🕒 ${item.time}" else "🕒 Just now"
        val chars = if (item.length > 0) item.length else item.text.length
        holder.tvLength.text = "• $chars chars"
        holder.tvContent.text = item.text

        holder.btnCopyPhone.setOnClickListener { onCopyPhone(item) }
        holder.btnSetPc.setOnClickListener { onSetPc(item) }
        holder.itemView.setOnClickListener { onCopyPhone(item) }
    }

    override fun getItemCount() = items.size

    fun updateList(newItems: List<PcClipboardHistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
