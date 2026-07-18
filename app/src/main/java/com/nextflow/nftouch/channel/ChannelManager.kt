package com.nextflow.nftouch.channel

import com.nextflow.nftouch.utils.XLog

enum class Channel(val displayName: String) {
    CLOUD("Cloud"),
}

object ChannelManager {

    private const val TAG = "ChannelManager"

    private var cloudHandler: ChannelHandler? = null
    private var messageListener: OnMessageReceivedListener? = null

    interface OnMessageReceivedListener {
        fun onMessageReceived(channel: Channel, message: String, messageID: String)
    }

    @JvmStatic
    fun registerCloudHandler(handler: ChannelHandler) {
        cloudHandler = handler
        XLog.i(TAG, "Cloud handler registered")
    }

    @JvmStatic
    fun setOnMessageReceivedListener(listener: OnMessageReceivedListener?) {
        this.messageListener = listener
    }

    @JvmStatic
    fun sendMessage(channel: Channel, content: String, messageID: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) {
            XLog.w(TAG, "sendMessage: skipping empty message")
            return
        }
        XLog.d(TAG, "sendMessage: ${trimmedContent.take(120)}")
        cloudHandler?.sendMessage(trimmedContent, messageID)
    }

    @JvmStatic
    fun sendImage(channel: Channel, imageBytes: ByteArray, messageID: String) {
        cloudHandler?.sendImage(imageBytes, messageID)
    }

    @JvmStatic
    fun sendFile(channel: Channel, file: java.io.File, messageID: String) {
        cloudHandler?.sendFile(file, messageID)
    }

    @JvmStatic
    fun flushMessages(channel: Channel) {
        cloudHandler?.flushMessages()
    }

    @JvmStatic
    fun dispatchMessage(channel: Channel, message: String, messageID: String) {
        messageListener?.onMessageReceived(channel, message, messageID)
    }
}
