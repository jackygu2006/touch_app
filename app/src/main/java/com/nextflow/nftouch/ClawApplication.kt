package com.nextflow.nftouch

import com.nextflow.nftouch.agent.DefaultAgentService
import com.nextflow.nftouch.base.BaseApp
import com.nextflow.nftouch.channel.ChannelManager
import com.nextflow.nftouch.device.CloudHub
import com.nextflow.nftouch.device.RemoteExecutor
import com.nextflow.nftouch.service.ForegroundService
import com.nextflow.nftouch.tool.ToolRegistry
import com.nextflow.nftouch.utils.KVUtils
import com.nextflow.nftouch.utils.XLog
import com.blankj.utilcode.util.NetworkUtils

/**
 * Application 入口
 */

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        XLog.setDEBUG(BuildConfig.DEBUG)
        registerNetworkCallback()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]
        KVUtils.init(this)
        ToolRegistry.getInstance().registerAllTools(ToolRegistry.DeviceType.MOBILE)
        XLog.e(TAG, "ClawApplication initialized, tools registered: ${ToolRegistry.getInstance().getAllTools().size}")

        // 网络日志输出到文件（调试时设为 true）
        DefaultAgentService.FILE_LOGGING_ENABLED = BuildConfig.DEBUG
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // 轻量初始化（主线程）
        appViewModelInstance.initCommon()
        if (!ForegroundService.isRunning()) {
            val started = ForegroundService.start(this)
            if (!started) {
                XLog.e(TAG, "ForegroundService start failed: notification permission not granted")
            }
        }

        Thread({
            if (KVUtils.hasLlmConfig()) {
                appViewModelInstance.initAgent()
                appViewModelInstance.afterInit()
            }
            // 云控连接独立于 LLM 配置，有云控配置就启动
            if (KVUtils.hasCloudConfig()) {
                val remoteExecutor = RemoteExecutor(appViewModelInstance.taskOrchestrator)
                CloudHub.connectIfConfigured(remoteExecutor)
            }
        }, "app-async-init").start()
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    /**
     * 监听网络恢复，自动重新初始化通道。
     * 解决开机自启动时无网络导致通道初始化失败的问题，以及运行中断网恢复后通道重连。
     */
    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        XLog.i(TAG, "Network restored (${networkType?.name})")
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "网络断开")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }

}
