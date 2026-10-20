package com.remote.pccontroller

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

object ClipboardSyncHelper {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun postClipboard(ip: String, port: String, text: String): Boolean {
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
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: IOException) {
            false
        }
    }

    fun getClipboardText(ip: String, port: String): String? {
        return try {
            val request = Request.Builder()
                .url("http://$ip:$port/api/clipboard")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                response.close()
                val json = JSONObject(body)
                json.optString("text", "")
            } else {
                response.close()
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    fun discoverPcIp(): String? {
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
