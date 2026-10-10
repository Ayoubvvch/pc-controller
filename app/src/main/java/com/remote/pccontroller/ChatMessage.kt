package com.remote.pccontroller

data class ChatMessage(
    val message: String,
    val isUser: Boolean,
    val time: String,
    val isSuccess: Boolean = true,
    val imageUrl: String? = null,
    val attachmentName: String? = null,
    val isVolumeControl: Boolean = false,
    var volumeLevel: Int = 50
)
