package com.nextflow.nftouch.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nextflow.nftouch.ClawApplication
import com.nextflow.nftouch.service.ClawAccessibilityService
import com.nextflow.nftouch.service.ForegroundService
import com.nextflow.nftouch.utils.KVUtils
import com.nextflow.nftouch.utils.XLog
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit


interface AuthCallback {
    fun onSuccess()
    fun onFailure(reason: String)
}

data class DeviceTokenValidationResult(
    val isValid: Boolean,
    val reason: String? = null
)

/**
 * 回调：连接状态变更，供 UI 层实时显示网络连接情况。
 */
interface ConnectionStateCallback {
    /** state: "connecting" | "socket_open" | "connected" | "disconnected" */
    fun onStateChanged(state: String, latencyMs: Long)
}

/**
 * Cloud WebSocket client: connect → auth → heartbeat → receive commands → reconnect.
 *
 * MVP: heartbeat 30s, exponential backoff reconnect, no resend, no token rotation.
 */
class CloudHub(
    private val remoteExecutor: RemoteExecutor
) {
    companion object {
        private const val TAG = "CloudHub"

        private const val HEARTBEAT_INTERVAL_SEC = 30L
        private const val MAX_RECONNECT_DELAY_SEC = 60L

        @Volatile
        private var instance: CloudHub? = null

        @Volatile
        var authCallback: AuthCallback? = null

        @Volatile
        var connectionStateCallback: ConnectionStateCallback? = null

        fun getInstance(): CloudHub? = instance

        fun currentConnectionState(): String = instance?.connectionState ?: "disconnected"

        fun currentLatencyMs(): Long = instance?.latencyMs ?: 0L

        fun isConfigured(): Boolean = KVUtils.hasCloudConfig()

        fun connectIfConfigured(remoteExecutor: RemoteExecutor) {
            // App 重新打开时强制重连，解决旧实例 WebSocket 假活问题
            instance?.let {
                it.close()
                instance = null
            }
            if (!isConfigured()) return
            instance = CloudHub(remoteExecutor)
            instance!!.connect()
        }

        fun disconnect() {
            instance?.close()
            instance = null
        }

        fun requestServerUnbind(): Boolean {
            if (!KVUtils.hasCloudConfig()) {
                return false
            }
            return requestServerUnbind(KVUtils.getCloudServerUrl(), KVUtils.getCloudDeviceId(), KVUtils.getCloudToken())
        }

        private fun requestServerUnbind(serverUrl: String, deviceId: String, token: String): Boolean {
            return postDeviceCredential(serverUrl, "/api/device/unbind", deviceId, token).first
        }

        fun validateServerToken(serverUrl: String, deviceId: String, token: String): DeviceTokenValidationResult {
            val (ok, error) = postDeviceCredential(serverUrl, "/api/device/validate-token", deviceId, token)
            return DeviceTokenValidationResult(ok, error)
        }

        private fun normalizeHttpServerUrl(serverUrl: String): String {
            return serverUrl
                .trim()
                .replace("ws://", "http://")
                .replace("wss://", "https://")
        }

        private fun postDeviceCredential(
            serverUrl: String,
            path: String,
            deviceId: String,
            token: String
        ): Pair<Boolean, String?> {
            var conn: HttpURLConnection? = null
            return try {
                val url = URL(normalizeHttpServerUrl(serverUrl).trimEnd('/') + path)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    put("token", token)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val responseText = try {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    stream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) {
                    null
                }
                if (code !in 200..299) {
                    XLog.w(TAG, "Device credential request failed: path=$path code=$code body=$responseText")
                }
                val message = responseText?.let {
                    runCatching { JSONObject(it).opt("error")?.toString()?.takeIf { msg -> msg.isNotBlank() } }.getOrNull()
                }
                Pair(code in 200..299, message)
            } catch (e: Exception) {
                XLog.e(TAG, "postDeviceCredential failed: path=$path msg=${e.message}", e)
                Pair(false, e.message)
            } finally {
                conn?.disconnect()
            }
        }

        /**
         * 断开旧连接并用最新配置重新连接。
         * 在用户修改云控配置后调用。
         */
        fun reconnect(remoteExecutor: RemoteExecutor) {
            disconnect()
            connectIfConfigured(remoteExecutor)
        }

        /**
         * 向云控中控台发送状态消息（AI任务进度、错误等）
         */
        fun sendStatus(message: String, taskId: Long = instance?.currentTaskId ?: 0) {
            instance?.sendText(JSONObject().apply {
                put("type", "task_status")
                put("text", message)
                if (taskId > 0) put("task_id", taskId)
            }.toString(), "task_status", reconnectOnFailure = true)
        }
    }

    private var webSocket: WebSocket? = null
    private var screenStreamer: ScreenStreamer? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private val reconnectThread = android.os.HandlerThread("cloudhub-reconnect").apply { start() }
    private val handler = Handler(reconnectThread.looper)
    private var lastMessageTime = System.currentTimeMillis()
    private var pingSentTime = 0L
    private var latencyMs = 0L
    @Volatile private var connectionState = "disconnected"
    @Volatile private var lastPermsJson: String = ""
    @Volatile var currentTaskId: Long = 0

    // 自适应分辨率：根据 RTT 平滑值切换五档宽度
    private var smoothedRtt = 0.0
    private var rttTierCounter = 0
    private var rttTierIndex = ScreenStreamer.WIDTH_TIERS.lastIndex  // 初始 720

    // 帧发送追踪（方案A）：检测 RTT 低但带宽不足的场景
    private var smoothedSendDuration = 0.0   // 平滑帧发送耗时，ms（指数平滑 0.7:0.3）
    private var smoothedSendQueue = 0.0      // 平滑发送队列深度，bytes

    /** Register as CLOUD channel handler so AI task callbacks can be sent back via WebSocket */
    val channelHandler = object : com.nextflow.nftouch.channel.ChannelHandler {
        override val channel = com.nextflow.nftouch.channel.Channel.CLOUD
        override fun isConnected() = webSocket != null
        override fun init() {}
        override fun disconnect() {}
        override fun reinitFromStorage() {}
        override fun sendMessage(content: String, messageID: String) {
            sendStatus(content)
        }
        override fun sendImage(imageBytes: ByteArray, messageID: String) {}
        override fun sendFile(file: java.io.File, messageID: String) {}
    }

    private val serverUrl: String get() = KVUtils.getCloudServerUrl()
    private val deviceId: String get() = KVUtils.getCloudDeviceId()
    private val token: String get() = KVUtils.getCloudToken()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)  // 弱网：30s 写入超时
        .build()

    private fun isCurrentSocket(candidate: WebSocket): Boolean = webSocket === candidate

    fun connect() {
        // Clear any pending callbacks from previous connection (prevents racing ping/auth)
        handler.removeCallbacksAndMessages(null)
        val wsUrl = serverUrl.trimEnd('/') + "/ws/device"
        val request = Request.Builder().url(wsUrl).build()

        XLog.i(TAG, "Connecting to $wsUrl")
        updateConnectionState("connecting", 0)
        val currentSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentSocket(webSocket)) {
                    webSocket.close(1000, "stale_socket")
                    return
                }
                XLog.i(TAG, "WebSocket connected")
                reconnectAttempts = 0
                lastMessageTime = System.currentTimeMillis()
                updateConnectionState("socket_open", 0)
                sendAuth()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrentSocket(webSocket)) return
                handleTextMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Not used
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrentSocket(webSocket)) return
                XLog.e(TAG, "WebSocket failure: ${t.message}")
                updateConnectionState("disconnected", 0)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentSocket(webSocket)) return
                XLog.i(TAG, "WebSocket closed: $code $reason")
                updateConnectionState("disconnected", 0)
                scheduleReconnect()
            }
        })
        webSocket = currentSocket
    }

    private fun updateConnectionState(state: String, latencyMs: Long = this.latencyMs) {
        connectionState = state
        connectionStateCallback?.onStateChanged(state, latencyMs)
    }

    private fun sendAuth() {
        try {
            val info = DeviceInfo.collect(ClawApplication.instance)
            val msg = JSONObject().apply {
                put("type", "auth")
                put("token", token)
                put("device_id", deviceId)
                put("info", JSONObject(info))
            }
            if (sendText(msg.toString(), "auth", reconnectOnFailure = true)) {
                XLog.d(TAG, "Auth sent: $deviceId")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "sendAuth failed: ${e.message}", e)
        }
    }

    private fun handleCriticalSendFailure(label: String) {
        if (!shouldReconnect) return
        val current = webSocket ?: return
        XLog.w(TAG, "Critical send failed: $label, forcing reconnect")
        webSocket = null
        updateConnectionState("disconnected", 0)
        current.close(1000, "send_failed:$label")
        scheduleReconnect(2000L)
    }

    private fun sendBinary(bytes: ByteArray, label: String = "frame", reconnectOnFailure: Boolean = false): Boolean {
        val current = webSocket
        if (current == null) {
            XLog.w(TAG, "sendBinary skipped: socket unavailable for $label")
            return false
        }
        val ok = current.send(ByteString.of(*bytes))
        if (!ok) {
            XLog.w(TAG, "sendBinary failed: label=$label size=${bytes.size} queue=${current.queueSize()}")
            if (reconnectOnFailure) {
                handleCriticalSendFailure(label)
            }
        }
        return ok
    }

    private fun sendText(text: String, label: String = "text", reconnectOnFailure: Boolean = false): Boolean {
        val current = webSocket
        if (current == null) {
            XLog.w(TAG, "sendText skipped: socket unavailable for $label")
            return false
        }
        val ok = current.send(text)
        if (!ok) {
            XLog.w(TAG, "sendText failed: label=$label size=${text.length} queue=${current.queueSize()}")
            if (reconnectOnFailure) {
                handleCriticalSendFailure(label)
            }
        }
        return ok
    }

    private fun handleTextMessage(currentSocket: WebSocket, text: String) {
        lastMessageTime = System.currentTimeMillis()
        try {
            val json = JSONObject(text)
            val messageType = json.optString("type", "")
            XLog.i(TAG, "Received message: type=$messageType")
            
            when (messageType) {
                "auth_ok" -> {
                    XLog.i(TAG, "Auth OK, starting screen stream")
                    // Clear any lingering timers from a previous connection before starting new ones
                    handler.removeCallbacksAndMessages(null)
                    com.nextflow.nftouch.channel.ChannelManager.registerCloudHandler(channelHandler)
                    startScreenStream()
                    updateConnectionState("connected", latencyMs)
                    authCallback?.onSuccess()
                    authCallback = null
                    sendImmediateStatus()
                    startHeartbeat()
                    startRttPing()
                }
                "auth_fail" -> {
                    XLog.e(TAG, "Auth failed: ${json.optString("reason", "unknown")}")
                    // Don't call close(), keep shouldReconnect=true for auto-reconnect after token fix
                    screenStreamer?.stop()
                    if (isCurrentSocket(currentSocket)) {
                        currentSocket.close(1000, "auth_fail")
                        webSocket = null
                    }
                    updateConnectionState("disconnected", 0)
                    authCallback?.onFailure(json.optString("reason", "unknown"))
                    authCallback = null
                    scheduleReconnect(15000L)
                }
                "ping" -> {
                    sendText("""{"type":"pong"}""", "pong")
                    // 服务器 ping 到达，说明连接正常，刷新回调
                    updateConnectionState("connected", latencyMs)
                }
                "device_pong" -> {
                    val rttMs = System.currentTimeMillis() - json.optLong("text", 0)
                    if (rttMs in 1..60000) {
                        latencyMs = rttMs
                        updateConnectionState("connected", rttMs)
                        adjustResolutionByRtt(rttMs)
                    }
                }
                "cmd_tap" -> {
                    val result = remoteExecutor.executeWithResult("cmd_tap", mapOf(
                        "x" to json.optInt("x"), "y" to json.optInt("y"),
                        "duration" to json.optInt("duration", 100)
                    ))
                }
                "cmd_swipe" -> {
                    val result = remoteExecutor.executeWithResult("cmd_swipe", mapOf(
                        "x1" to json.optInt("x1"), "y1" to json.optInt("y1"),
                        "x2" to json.optInt("x2"), "y2" to json.optInt("y2"),
                        "duration" to json.optInt("duration", 300)
                    ))
                }
                "cmd_input" -> {
                    XLog.i(TAG, "Received cmd_input command: text='${json.optString("text", "")}'")
                    val result = remoteExecutor.executeWithResult("cmd_input", mapOf(
                        "text" to json.optString("text", "")
                    ))
                    XLog.i(TAG, "cmd_input result: $result")
                }
                "cmd_task" -> {
                    currentTaskId = json.optLong("task_id", 0)
                    val result = remoteExecutor.executeWithResult("cmd_task", mapOf(
                        "prompt" to json.optString("prompt", "")
                    ))
                    if (result != null) {
                        sendStatus(result)
                    }
                }
                "cmd_cancel_task" -> {
                    ClawApplication.appViewModelInstance.cancelCurrentTask()
                    currentTaskId = 0
                }
                "cmd_key" -> {
                    val result = remoteExecutor.executeWithResult("cmd_key", mapOf(
                        "key" to json.optString("key", "")
                    ))
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun startScreenStream() {
        screenStreamer?.stop()
        screenStreamer = ScreenStreamer { frameData ->
            val t0 = System.currentTimeMillis()
            sendBinary(frameData, "frame")
            val sendDuration = System.currentTimeMillis() - t0
            val queueSize = webSocket?.queueSize() ?: 0L
            reportFrameSend(sendDuration, queueSize, frameData.size)
        }
        screenStreamer?.start()
    }

    /**
     * Track frame send stats (Plan A): measure how long sendBinary blocks and
     * how much data is queued in OkHttp's write buffer.
     *
     * When RTT is low but sendDuration/queueSize are high, the network has
     * bandwidth bottleneck — we penalize the effective RTT accordingly.
     */
    private fun reportFrameSend(sendDurationMs: Long, queueSize: Long, frameSize: Int) {
        // Exponential moving average (same formula as smoothedRtt)
        smoothedSendDuration = smoothedSendDuration * 0.7 + sendDurationMs * 0.3
        smoothedSendQueue = smoothedSendQueue * 0.7 + queueSize * 0.3

        // Log when congestion is detected (avoid spam on healthy connections)
        if (smoothedSendDuration > 150 || smoothedSendQueue > 50_000) {
            XLog.w(TAG, "Frame send congestion: dur=${smoothedSendDuration.toLong()}ms, " +
                "queue=${(smoothedSendQueue / 1024).toLong()}KB, frame=${frameSize / 1024}KB")
        }
    }
    private fun startHeartbeat() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (webSocket == null) return
                try {
                    // Watchdog: force reconnect if no message received in 60 seconds
                    if (System.currentTimeMillis() - lastMessageTime > 60_000) {
                        XLog.w(TAG, "Watchdog timeout, forcing reconnect")
                        webSocket?.close(1000, "watchdog")
                        webSocket = null
                        updateConnectionState("disconnected", 0)
                        scheduleReconnect(15000L)
                        return
                    }
                    val battery = (DeviceInfo.collect(ClawApplication.instance)["battery"] as? Int) ?: 0
                    val perms = getPermissions()
                    val screenOn = isScreenOn()
                    val status = JSONObject().apply {
                        put("type", "status")
                        put("info", JSONObject().apply {
                            put("battery", battery)
                            put("screen_on", screenOn)
                            put("permissions", perms)
                        })
                    }
                    sendText(status.toString(), "heartbeat_status", reconnectOnFailure = true)
                } catch (e: Throwable) {
                    XLog.e(TAG, "Heartbeat error", e)
                }
                handler.postDelayed(this, HEARTBEAT_INTERVAL_SEC * 1000)
            }
        }, HEARTBEAT_INTERVAL_SEC * 1000)
    }

    /** Send a status immediately after auth, so dashboard sees permissions without waiting for heartbeat */
    private fun sendImmediateStatus() {
        try {
            val battery = (DeviceInfo.collect(ClawApplication.instance)["battery"] as? Int) ?: 0
            val perms = getPermissions()
            val screenOn = isScreenOn()
            val status = JSONObject().apply {
                put("type", "status")
                put("info", JSONObject().apply {
                    put("battery", battery)
                    put("screen_on", screenOn)
                    put("permissions", perms)
                })
            }
            sendText(status.toString(), "immediate_status", reconnectOnFailure = true)
        } catch (e: Throwable) {
            XLog.e(TAG, "Immediate status error", e)
        }
    }

    /** Send device_ping every 5s to measure RTT */
    private fun startRttPing() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (webSocket == null) return
                try {
                    val ts = System.currentTimeMillis()
                    val msg = JSONObject().apply {
                        put("type", "device_ping")
                        put("text", ts.toString())
                    }
                    sendText(msg.toString(), "device_ping", reconnectOnFailure = true)
                } catch (e: Throwable) {
                    XLog.e(TAG, "RTT ping error", e)
                }
                handler.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    private fun scheduleReconnect(delayMs: Long = 1000L) {
        screenStreamer?.stop()
        if (!shouldReconnect) {
            XLog.w(TAG, "Reconnect disabled, not reconnecting")
            return
        }
        handler.removeCallbacksAndMessages(null)

        // Always start with 1s delay on each fresh disconnect
        val delaySec = delayMs / 1000L
        XLog.i(TAG, "Reconnecting in ${delaySec}s")

        handler.postDelayed({
            if (shouldReconnect) connect()
        }, delaySec * 1000)
    }

    fun close() {
        shouldReconnect = false
        screenStreamer?.stop()
        screenStreamer = null
        handler.removeCallbacksAndMessages(null)
        reconnectThread.quitSafely()
        webSocket?.close(1000, "client shutdown")
        webSocket = null
        updateConnectionState("disconnected", 0)
        XLog.i(TAG, "CloudHub closed")
    }

    private fun getPermissions(): JSONObject {
        val ctx = ClawApplication.instance
        return JSONObject().apply {
            put("accessibility", ClawAccessibilityService.isRunning())
            put("notification", ForegroundService.isRunning())
            put("overlay", Settings.canDrawOverlays(ctx))
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            put("battery_opt", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            put("storage", isStoragePermissionGranted(ctx))
        }
    }

    private fun isScreenOn(): Boolean {
        val pm = ClawApplication.instance.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive()
    }

    private fun isStoragePermissionGranted(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * External call: immediately report status on permission change,
     * avoiding 30-second heartbeat interval.
     */
    fun onPermissionChanged() {
        handler.post {
            if (webSocket == null) return@post
            val perms = getPermissions()
            val permsStr = perms.toString()
            if (permsStr == lastPermsJson) return@post  // No actual change
            lastPermsJson = permsStr
            val battery = (DeviceInfo.collect(ClawApplication.instance)["battery"] as? Int) ?: 0
            val screenOn = isScreenOn()
            val status = JSONObject().apply {
                put("type", "status")
                put("info", JSONObject().apply {
                    put("battery", battery)
                    put("screen_on", screenOn)
                    put("permissions", perms)
                })
            }
            sendText(status.toString(), "permission_status", reconnectOnFailure = true)
        }
    }

    /**
     * Compute an effective RTT by combining the measured ping RTT with
     * frame-send congestion signals (send duration + write queue depth).
     *
     * This is the core of Plan A: when RTT is low but frames can't get out
     * (high sendDuration / large queue), we penalize the effective RTT so
     * the resolution adapts to bandwidth bottlenecks, not just latency.
     */
    private fun computeEffectiveRtt(): Long {
        val rttComponent = smoothedRtt

        // Convert smoothedSendDuration to equivalent RTT penalty
        // send < 100ms: negligible, 100-300ms: mild, 300-500ms: moderate, >500ms: severe
        val sendPenalty = when {
            smoothedSendDuration < 100 -> 0.0
            smoothedSendDuration < 300 -> (smoothedSendDuration - 100) * 0.5   // 0-100ms penalty
            smoothedSendDuration < 500 -> 100.0 + (smoothedSendDuration - 300) * 0.8  // 100-260ms
            else -> 260.0 + (smoothedSendDuration - 500) * 1.0  // 260ms+
        }

        // Convert smoothedSendQueue to equivalent RTT penalty
        // queue < 30KB: negligible, 30-150KB: mild, 150-500KB: moderate, >500KB: severe
        val queuePenalty = when {
            smoothedSendQueue < 30_000 -> 0.0
            smoothedSendQueue < 150_000 -> (smoothedSendQueue - 30_000) / 120_000 * 80.0  // 0-80ms
            smoothedSendQueue < 500_000 -> 80.0 + (smoothedSendQueue - 150_000) / 350_000 * 120.0  // 80-200ms
            else -> 200.0 + (smoothedSendQueue - 500_000) / 500_000 * 100.0  // 200-300ms
        }

        // Take the larger of the two penalties (whichever is more congested)
        val totalPenalty = maxOf(sendPenalty, queuePenalty)
        return (rttComponent + totalPenalty).toLong()
    }

    /**
     * Adaptively adjust stream resolution based on RTT (five tiers: 240/360/480/540/720).
     * Uses weighted smoothing + hysteresis counting to prevent jitter from frequent switching.
     *
     * Extended with Plan A: effectiveRtt blends ping RTT with frame-send congestion signals.
     */
    private fun adjustResolutionByRtt(rttMs: Long) {
        // Weighted smoothing: 0.7 old value + 0.3 new value
        smoothedRtt = smoothedRtt * 0.7 + rttMs * 0.3

        // Determine target tier based on effective RTT (RTT + send congestion penalty)
        val effectiveRtt = computeEffectiveRtt()
        val thresholds = longArrayOf(80, 150, 300, 600)
        val targetIndex = thresholds.indexOfFirst { effectiveRtt < it }.let { if (it == -1) 0 else 4 - it }

        if (targetIndex == rttTierIndex) {
            rttTierCounter = 0
            return
        }
        rttTierCounter++
        // Hysteresis: switch only after 3 consecutive readings in the new tier
        if (rttTierCounter >= 3) {
            val oldWidth = ScreenStreamer.WIDTH_TIERS[rttTierIndex]
            rttTierIndex = targetIndex
            rttTierCounter = 0
            val newWidth = ScreenStreamer.WIDTH_TIERS[rttTierIndex]
            XLog.i(TAG, "Resolution adjusted: ${oldWidth}px → ${newWidth}px " +
                "(effectiveRtt=${effectiveRtt}ms, pingRtt=${smoothedRtt.toLong()}ms, " +
                "sendDur=${smoothedSendDuration.toLong()}ms, queue=${(smoothedSendQueue / 1024).toLong()}KB)")
            screenStreamer?.currentMaxWidth = newWidth
        }
    }
}
