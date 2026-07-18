package com.nextflow.nftouch.channel

interface ChannelHandler {
    val channel: Channel
    fun isConnected(): Boolean
    fun init()
    fun disconnect()
    fun reinitFromStorage()
    fun sendMessage(content: String, messageID: String)
    fun sendImage(imageBytes: ByteArray, messageID: String)
    fun sendFile(file: java.io.File, messageID: String)
    fun sendMessageToUser(userId: String, content: String) {}
    fun flushMessages() {}
    fun restoreRoutingContext(targetUserId: String) {}
    fun getLastSenderId(): String? = null
}
