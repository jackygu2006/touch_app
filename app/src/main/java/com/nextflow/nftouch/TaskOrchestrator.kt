package com.nextflow.nftouch

import com.nextflow.nftouch.agent.AgentCallback
import com.nextflow.nftouch.agent.AgentConfig
import com.nextflow.nftouch.agent.AgentService
import com.nextflow.nftouch.agent.AgentServiceFactory
import com.nextflow.nftouch.agent.langchain.http.DeepSeekCacheInterceptor
import com.nextflow.nftouch.channel.Channel
import com.nextflow.nftouch.channel.ChannelManager
import com.nextflow.nftouch.floating.FloatingCircleManager
import com.nextflow.nftouch.service.ClawAccessibilityService
import com.nextflow.nftouch.tool.ToolResult
import com.nextflow.nftouch.utils.XLog

/**
 * Task orchestrator: manages Agent lifecycle, task lock, task execution and callback handling.
 *
 * @param agentConfigProvider callback to lazily get the latest AgentConfig
 * @param onTaskFinished notification after each task ends (success/failure/cancel), used to refresh UI etc.
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    private lateinit var agentService: AgentService

    private val taskLock = Any()
    @Volatile
    var inProgressTaskMessageId: String = ""
        private set
    @Volatile
    var inProgressTaskChannel: Channel? = null
        private set
    private var taskStartTime = 0L

    // ==================== Agent Lifecycle ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== Task Lock ====================

    /**
     * Atomically try to acquire the task lock. If no task is running, mark as occupied and return true; otherwise return false.
     */
    fun tryAcquireTask(messageId: String, channel: Channel): Boolean {
        synchronized(taskLock) {
            if (inProgressTaskMessageId.isNotEmpty()) return false
            inProgressTaskMessageId = messageId
            inProgressTaskChannel = channel
            return true
        }
    }

    /**
     * Release task lock, return (channel, messageId) for caller use.
     */
    private fun releaseTask(): Pair<Channel?, String> {
        synchronized(taskLock) {
            val ch = inProgressTaskChannel
            val id = inProgressTaskMessageId
            inProgressTaskMessageId = ""
            inProgressTaskChannel = null
            return ch to id
        }
    }

    fun isTaskRunning(): Boolean {
        synchronized(taskLock) {
            return inProgressTaskMessageId.isNotEmpty()
        }
    }

    // ==================== Task Execution ====================

    fun cancelCurrentTask() {
        if (!isTaskRunning()) return
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        val (channel, messageId) = releaseTask()
        if (channel != null && messageId.isNotEmpty()) {
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled), messageId)
        }
        FloatingCircleManager.setErrorState()
        onTaskFinished()
        XLog.d(TAG, "Current task cancelled by user")
    }

    fun startNewTask(channel: Channel, task: String, messageID: String) {
        taskStartTime = System.currentTimeMillis()
        DeepSeekCacheInterceptor.reset()
        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        FloatingCircleManager.showTaskNotify(task, channel)

        // Per-round message aggregation buffer: accumulate thinking + toolResult into one message
        val roundBuffer = StringBuilder()

        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        agentService.executeTask(task, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                // Before new round starts, flush accumulated messages from previous round
                flushRoundBuffer()
                FloatingCircleManager.setRunningState(round, channel)
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = ClawApplication.instance
                val status = if (result.isSuccess) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)
                var data = if (result.isSuccess) result.data else result.error
                if (data != null && data.length > 300) {
                    data = data.substring(0, 300) + "...(truncated)"
                }
                if (!result.isSuccess) {
                    XLog.e(TAG, "!!!!!!!!!!Fail: $toolName, $parameters $data")
                }
                XLog.e(TAG, "onToolResult: $toolName, $status $data")
                if (toolId == "finish" && (result.data?.isNotEmpty() ?: false)) {
                    // Send finish result separately, no merge (this is the final reply)
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    // Append to current round buffer
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(
                        app.getString(R.string.channel_msg_tool_execution, toolName + parameters, status)
                    )
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                val elapsed = (System.currentTimeMillis() - taskStartTime) / 1000
                XLog.i(TAG, "onComplete: 轮数=$round, totalTokens=$totalTokens, 用时=${elapsed}s")
                flushRoundBuffer()
                releaseTask()
                ChannelManager.sendMessage(channel, "✅ " + buildStatsMessage(elapsed, totalTokens), messageID)
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                val elapsed = (System.currentTimeMillis() - taskStartTime) / 1000
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens, 用时=${elapsed}s", error)
                flushRoundBuffer()
                releaseTask()
                ChannelManager.sendMessage(channel,
                    ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message) +
                    "\n" + buildStatsMessage(elapsed, totalTokens), messageID)
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                flushRoundBuffer()
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked), messageID)
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(channel, stream.toByteArray(), messageID)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        })
    }

    private fun buildStatsMessage(elapsedSec: Long, totalTokens: Int): String {
        val sb = StringBuilder()
        sb.append("⏱ 用时 ${elapsedSec}s | 🪙 Token: ${formatNumber(totalTokens)}")
        if (DeepSeekCacheInterceptor.totalCacheHitTokens > 0) {
            val total = DeepSeekCacheInterceptor.totalPromptTokens
            val saved = DeepSeekCacheInterceptor.totalCacheHitTokens
            val pct = if (total > 0) saved * 100 / total else 0
            sb.append(" | 💾 Cache: 节省 ${formatNumber(saved.toInt())} (${pct}%)")
        }
        return sb.toString()
    }

    private fun formatNumber(n: Int): String {
        if (n < 1000) return n.toString()
        return "%.1fk".format(n / 1000.0)
    }
}
