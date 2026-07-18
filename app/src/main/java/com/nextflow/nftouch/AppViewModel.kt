package com.nextflow.nftouch

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import com.nextflow.nftouch.ClawApplication.Companion.appViewModelInstance
import com.nextflow.nftouch.agent.AgentConfig
import com.nextflow.nftouch.channel.Channel
import com.nextflow.nftouch.channel.ChannelManager
import com.nextflow.nftouch.service.ForegroundService
import com.nextflow.nftouch.floating.FloatingCircleManager
import com.nextflow.nftouch.server.ConfigServerManager
import com.nextflow.nftouch.service.KeepAliveJobService
import com.nextflow.nftouch.ui.home.HomeActivity
import com.nextflow.nftouch.utils.KVUtils
import com.nextflow.nftouch.utils.XLog

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { /* 刷新 */ }
    )


    val inProgressTaskMessageId: String get() = taskOrchestrator.inProgressTaskMessageId
    val inProgressTaskChannel: Channel? get() = taskOrchestrator.inProgressTaskChannel

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig {
        var baseUrl = KVUtils.getLlmBaseUrl().trim()
        if (baseUrl.isEmpty()) baseUrl = "https://api.openai.com/v1"
        return AgentConfig.Builder()
            .apiKey(KVUtils.getLlmApiKey())
            .baseUrl(baseUrl)
            .modelName(KVUtils.getLlmModelName())
            .temperature(0.1)
            .maxIterations(100)
            .build()
    }

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        acquireScreenWakeLock()
        ForegroundService.start(ClawApplication.instance)
        KeepAliveJobService.schedule(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        if (android.provider.Settings.canDrawOverlays(ClawApplication.instance)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                appViewModelInstance.showFloatingCircle()
            }
        }
    }


    /**
     * Acquire screen wake lock to prevent accessibility service from failing after screen off
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "NFTouch::ScreenWakeLock"
        ).apply {
            acquire()
        }
        XLog.i(TAG, "Screen wake lock acquired")
    }

    /**
     * Release wake lock
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "Screen wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Show floating circle overlay
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * Bring app to foreground
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, HomeActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    fun isTaskRunning(): Boolean = taskOrchestrator.isTaskRunning()

    fun cancelCurrentTask() = taskOrchestrator.cancelCurrentTask()

    fun startNewTask(channel: Channel, task: String, messageID: String) =
        taskOrchestrator.startNewTask(channel, task, messageID)

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "截图文件不存在: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "发送截图失败", e)
        }
    }
}
