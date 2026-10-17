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
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.os.Handler
import android.os.Looper
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
                downloadFileFromPc(item)
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

    private fun downloadFileFromPc(item: PcFileItem) {
        val baseUrl = getBaseUrl()
        val phoneIp = getPhoneLocalIp() ?: ""

        val ext = item.name.substringAfterLast('.', "").lowercase()
        val isMedia = ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "raw", "dng", "mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "3gp", "m4v", "ts")
        val targetDest = if (isMedia) "/sdcard/Pictures/" else "/sdcard/Download/"

        Toast.makeText(this, "🚀 Starting SSH transfer for ${item.name}...", Toast.LENGTH_SHORT).show()

        val jsonPayload = JSONObject().apply {
            put("path", item.path)
            put("name", item.name)
            put("size", item.sizeStr)
            put("phone_ip", phoneIp)
            put("dest", targetDest)
        }.toString()

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url("$baseUrl/api/files/send_ssh")
            .post(jsonPayload.toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "⚠️ SSH transfer failed, falling back to direct download...", Toast.LENGTH_SHORT).show()
                    downloadFileHttpFallback(item)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var message = "🚀 Starting SSH transfer for ${item.name} to phone in background!"
                var isSuccess = response.isSuccessful
                var resolvedDest = targetDest
                try {
                    val json = JSONObject(body)
                    if (json.optString("status") == "success") {
                        isSuccess = true
                        message = json.optString("message", message)
                        resolvedDest = json.optString("dest", targetDest)
                    } else {
                        isSuccess = false
                    }
                } catch (ignored: Exception) {}

                runOnUiThread {
                    if (isSuccess) {
                        Toast.makeText(this@MainActivity, "🚀 SSH transfer started to $resolvedDest!", Toast.LENGTH_LONG).show()
                        addPcMessage(
                            "🚀 File transfer started via SSH in background!\n" +
                            "📁 File: ${item.name}\n" +
                            "💾 Size: ${item.sizeStr}\n" +
                            "📍 Destination: $resolvedDest\n" +
                            "🛡️ Transferred via SSH in background; you can safely close the app or lock your screen without interruption."
                        )
                    } else {
                        Toast.makeText(this@MainActivity, "⚠️ SSH transfer failed, falling back to direct download...", Toast.LENGTH_SHORT).show()
                        downloadFileHttpFallback(item)
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
        val btnCheckUpdates = view.findViewById<View>(R.id.btnCheckUpdates)
        val tvCheckUpdatesText = view.findViewById<TextView>(R.id.tvCheckUpdatesText)

        etIp.setText(prefs.getString("pc_ip", "192.168.11.110"))
        etPort.setText(prefs.getString("pc_port", "5050"))
        switchKeepBackground?.isChecked = prefs.getBoolean("keep_background", false)

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
                        val currentVersion = "v2.0.7"
                        if (tagName.isNotEmpty() && tagName != currentVersion) {
                            statusView?.text = "🚀 New update available: $tagName"
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("🚀 New Version Available: $tagName")
                                .setMessage("A new version ($tagName) is available on GitHub with the latest improvements!\n\nWould you like to download and install it?")
                                .setPositiveButton("Download & Update") { _, _ ->
                                    val targetUrl = apkUrl ?: "https://github.com/Ayoubvvch/pc-controller/releases/latest"
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                    startActivity(browserIntent)
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
}
