package com.nextflow.nftouch.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.Manifest
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.nextflow.nftouch.service.ForegroundService
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.TextView
import com.nextflow.nftouch.R
import com.nextflow.nftouch.BuildConfig
import com.nextflow.nftouch.appViewModel
import com.nextflow.nftouch.base.BaseActivity
import com.nextflow.nftouch.service.ClawAccessibilityService
import com.nextflow.nftouch.ui.guide.GuideActivity
import com.nextflow.nftouch.ui.settings.LlmConfigActivity
import com.nextflow.nftouch.device.CloudHub
import com.nextflow.nftouch.device.ConnectionStateCallback
import com.nextflow.nftouch.utils.KVUtils
import com.nextflow.nftouch.widget.CommonToolbar
import com.nextflow.nftouch.widget.PermissionCardView
import com.nextflow.nftouch.widget.KButton
import com.nextflow.nftouch.widget.MenuGroup
import com.nextflow.nftouch.widget.MenuItem
import com.nextflow.nftouch.widget.AlertDialog
import com.nextflow.nftouch.widget.InputDialog
import androidx.core.net.toUri

/**
 * Home screen — permission management + model config + cloud bind
 */
class HomeActivity : BaseActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    private lateinit var cardAccessibility: PermissionCardView
    private lateinit var cardNotification: PermissionCardView
    private lateinit var cardSystemWindow: PermissionCardView
    private lateinit var cardBattery: PermissionCardView
    private lateinit var cardStorage: PermissionCardView
    private lateinit var btnCancelTask: KButton

    private lateinit var homeModelGroup: MenuGroup
    private lateinit var homeCloudGroup: MenuGroup

    private var llmMenuItem: MenuItem? = null
    private var cloudMenuItem: MenuItem? = null
    private lateinit var toolbar: CommonToolbar

    // Previous permission snapshot, used to detect changes for immediate reporting
    private var prevAccessibility = false
    private var prevNotification = false
    private var prevOverlay = false
    private var prevBatteryOpt = false
    private var prevStorage = false
    private var prevScreenOn = true

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            updateAllPermissionStatus()
            updateConfigStatus()
            handler.postDelayed(this, 1000)
        }
    }

    // Activity Result API - storage permission request (Android 6~10)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.home_enable_storage, Toast.LENGTH_SHORT).show()
        }
        updateStorageStatus()
    }

    // Activity Result API - notification permission request
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startNotificationService()
        } else {
            Toast.makeText(this, R.string.home_need_notification_permission, Toast.LENGTH_SHORT).show()
        }
    }

    // Activity Result API - refresh after returning from LLM config page
    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        updateConfigStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        initMenuGroups()
        showGuideIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateAllPermissionStatus()
        updateConfigStatus()
        startStatusCheck()
        registerConnectionCallback()
    }

    override fun onPause() {
        super.onPause()
        stopStatusCheck()
        CloudHub.connectionStateCallback = null
    }

    private fun registerConnectionCallback() {
        // Don't register callback and hide signal icon if cloud is not configured
        if (!KVUtils.hasCloudConfig()) {
            toolbar.hideAction()
            return
        }
        CloudHub.connectionStateCallback = object : ConnectionStateCallback {
            override fun onStateChanged(state: String, latencyMs: Long) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // Cloud config may have been cleared, re-check
                    if (!KVUtils.hasCloudConfig()) {
                        toolbar.hideAction()
                        return@runOnUiThread
                    }
                    renderConnectionState(state, latencyMs)
                }
            }
        }
        // Display current connection state
        renderConnectionState(CloudHub.currentConnectionState(), CloudHub.currentLatencyMs())
    }

    private fun renderConnectionState(state: String, latencyMs: Long) {
        when (state) {
            "connected" -> {
                val (icon, color) = when {
                    latencyMs in 1..50    -> R.drawable.ic_signal_cellular_3 to getColor(R.color.colorOnline)
                    latencyMs in 51..200  -> R.drawable.ic_signal_cellular_2 to 0xFFFBBF24.toInt()
                    latencyMs > 200       -> R.drawable.ic_signal_cellular_1 to getColor(R.color.colorOffline)
                    else                  -> R.drawable.ic_signal_cellular_3 to getColor(R.color.colorOnline)
                }
                toolbar.setSignalIcon(icon, color)
            }
            "connecting", "socket_open" -> {
                toolbar.setSignalIcon(R.drawable.ic_signal_cellular_0, getColor(R.color.colorTextSecondary))
            }
            else -> {
                toolbar.setSignalIcon(R.drawable.ic_signal_cellular_0, getColor(R.color.colorOffline))
            }
        }
    }

    private fun showGuideIfNeeded() {
        if (!KVUtils.isGuideShown()) {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        // Show privacy notice on first launch (after guide, or standalone)
        showPrivacyNoticeIfNeeded()
    }

    /**
     * Show privacy notice on first launch explaining data sent to AI.
     */
    private fun showPrivacyNoticeIfNeeded() {
        if (KVUtils.getBoolean("privacy_notice_shown", false)) return
        KVUtils.putBoolean("privacy_notice_shown", true)
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.privacy_notice_title),
            message = getString(R.string.privacy_notice_message),
            actionTitle = getString(R.string.privacy_notice_agree)
        )
    }

    private fun initViews() {
        // Toolbar
        toolbar = findViewById(R.id.toolbar)
        toolbar.apply {
            setTitleCentered(true)
            setTitle(getString(R.string.app_name))
        }

        // Version number
        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"

        // Cards
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardNotification = findViewById(R.id.cardNotification)
        cardSystemWindow = findViewById(R.id.cardSystemWindow)
        cardBattery = findViewById(R.id.cardBattery)
        cardStorage = findViewById(R.id.cardStorage)

        // Cancel task button
        btnCancelTask = findViewById(R.id.btnCancelTask)
        btnCancelTask.setOnClickListener {
            if (appViewModel.isTaskRunning()) {
                appViewModel.cancelCurrentTask()
                Toast.makeText(this, R.string.home_cancel_task_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.home_no_task_running, Toast.LENGTH_SHORT).show()
            }
            updateCancelTaskVisibility()
        }

        // Click cards to request permissions
        cardAccessibility.setOnClickListener { requestAccessibilityPermission() }
        cardNotification.setOnClickListener { requestNotificationPermission() }
        cardSystemWindow.setOnClickListener { requestSystemWindowPermission() }
        cardBattery.setOnClickListener { requestBatteryPermission() }
        cardStorage.setOnClickListener { requestStoragePermission() }
    }

    private fun initMenuGroups() {
        // Model config group
        homeModelGroup = findViewById(R.id.homeModelGroup)
        homeModelGroup.setTitle(getString(R.string.home_group_model))

        llmMenuItem = homeModelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            subtitle = getString(R.string.menu_llm_config_subtitle),
            onClick = {
                llmConfigLauncher.launch(Intent(this, LlmConfigActivity::class.java))
            },
            showDivider = false
        )
        llmMenuItem?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // Cloud bind group
        homeCloudGroup = findViewById(R.id.homeCloudGroup)
        homeCloudGroup.setTitle(getString(R.string.home_group_cloud))

        cloudMenuItem = homeCloudGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.menu_cloud_bind),
            subtitle = getString(R.string.menu_cloud_bind_subtitle),
            onClick = {
                if (KVUtils.isCloudBound()) {
                    showCloudUnbindDialog()
                } else {
                    showCloudConfigDialog()
                }
            },
            showDivider = false
        )
        cloudMenuItem?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
    }

    private fun updateConfigStatus() {
        val greenColor = getColor(R.color.colorSuccessPrimary)
        val defaultColor = getColor(R.color.colorTextPrimary)

        // Update LLM config display: when configured, show Provider (top) + Model (bottom) in green
        if (KVUtils.hasLlmConfig()) {
            val provider = KVUtils.getLlmProvider().ifEmpty { getString(R.string.llm_config_custom) }
            val model = KVUtils.getLlmModelName()
            llmMenuItem?.setTitle(provider)
            llmMenuItem?.setSubtitle(model)
            llmMenuItem?.setTitleColor(greenColor)
            llmMenuItem?.setSubtitleColor(greenColor)
            llmMenuItem?.setTrailingText("")
        } else {
            llmMenuItem?.setTitle(getString(R.string.menu_llm_config))
            llmMenuItem?.setSubtitle(getString(R.string.menu_llm_config_subtitle))
            llmMenuItem?.setTitleColor(defaultColor)
            llmMenuItem?.setSubtitleColor(getColor(R.color.colorTextSecondary))
            llmMenuItem?.setTrailingText(getString(R.string.common_unconfigured))
        }

        // Update cloud bind display: when bound, show WS URL (top) + Device ID (bottom) in green
        if (KVUtils.isCloudBound()) {
            val wsUrl = KVUtils.getCloudServerUrl()
            val deviceId = KVUtils.getCloudDeviceId()
            cloudMenuItem?.setTitle(wsUrl)
            cloudMenuItem?.setSubtitle(deviceId)
            cloudMenuItem?.setTitleColor(greenColor)
            cloudMenuItem?.setSubtitleColor(greenColor)
            cloudMenuItem?.setTrailingText("")
        } else {
            cloudMenuItem?.setTitle(getString(R.string.menu_cloud_bind))
            cloudMenuItem?.setSubtitle(getString(R.string.menu_cloud_bind_subtitle))
            cloudMenuItem?.setTitleColor(defaultColor)
            cloudMenuItem?.setSubtitleColor(getColor(R.color.colorTextSecondary))
            cloudMenuItem?.setTrailingText(getString(R.string.common_unbound))
        }
    }

    private fun updateAllPermissionStatus() {
        updateAccessibilityStatus()
        updateNotificationStatus()
        updateSystemWindowStatus()
        updateBatteryStatus()
        updateStorageStatus()
        updateCancelTaskVisibility()
        notifyPermissionChangeIfNeeded()
    }

    private fun notifyPermissionChangeIfNeeded() {
        val curAccessibility = ClawAccessibilityService.isRunning()
        val curNotification = ForegroundService.isRunning()
        val curOverlay = Settings.canDrawOverlays(this)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val curBatteryOpt = powerManager.isIgnoringBatteryOptimizations(packageName)
        val curStorage = isStoragePermissionGranted()
        val curScreenOn = powerManager.isInteractive

        val changed = curAccessibility != prevAccessibility ||
                curNotification != prevNotification ||
                curOverlay != prevOverlay ||
                curBatteryOpt != prevBatteryOpt ||
                curStorage != prevStorage ||
                curScreenOn != prevScreenOn

        prevAccessibility = curAccessibility
        prevNotification = curNotification
        prevOverlay = curOverlay
        prevBatteryOpt = curBatteryOpt
        prevStorage = curStorage
        prevScreenOn = curScreenOn

        if (changed) {
            CloudHub.getInstance()?.onPermissionChanged()
        }
    }

    private fun updateCancelTaskVisibility() {
        btnCancelTask.visibility = if (appViewModel.isTaskRunning()) View.VISIBLE else View.GONE
    }

    private fun updateAccessibilityStatus() {
        cardAccessibility.setPermissionEnabled(ClawAccessibilityService.isRunning())
    }

    private fun updateNotificationStatus() {
        cardNotification.setPermissionEnabled(ForegroundService.isRunning())
    }

    private fun updateSystemWindowStatus() {
        val enabled = Settings.canDrawOverlays(this)
        cardSystemWindow.setPermissionEnabled(enabled)
        if (enabled) {
            appViewModel.showFloatingCircle()
        }
    }

    private fun updateBatteryStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        cardBattery.setPermissionEnabled(powerManager.isIgnoringBatteryOptimizations(packageName))
    }

    private fun updateStorageStatus() {
        cardStorage.setPermissionEnabled(isStoragePermissionGranted())
    }

    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAllPermissionsGranted(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return ClawAccessibilityService.isRunning() &&
                Settings.canDrawOverlays(this) &&
                powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    // ==================== Permission Requests ====================

    private fun requestAccessibilityPermission() {
        if (!ClawAccessibilityService.isRunning()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.home_accessibility_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startNotificationService()
    }

    private fun startNotificationService() {
        val started = ForegroundService.start(this)
        if (started) {
            cardNotification.setPermissionEnabled(true)
            Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.home_need_notification_permission, Toast.LENGTH_SHORT).show()
            updateNotificationStatus()
        }
    }

    private fun requestSystemWindowPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStoragePermission() {
        if (isStoragePermissionGranted()) {
            Toast.makeText(this, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
            Toast.makeText(this, R.string.home_enable_storage, Toast.LENGTH_LONG).show()
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun requestBatteryPermission() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStatusCheck() {
        stopStatusCheck();
        handler.postDelayed(checkRunnable, 1000)
    }

    private fun stopStatusCheck() {
        handler.removeCallbacks(checkRunnable)
    }

    // ==================== 云控配置弹窗 ====================

    private fun performCloudUnbind() {
        Thread {
            CloudHub.requestServerUnbind()
            KVUtils.setCloudBound(false)
            CloudHub.disconnect()
            runOnUiThread {
                updateConfigStatus()
                Toast.makeText(this, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun showCloudUnbindDialog() {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.unbind_title),
            message = getString(R.string.unbind_message, getString(R.string.menu_cloud_bind), getString(R.string.menu_cloud_bind)),
            actionTitle = getString(R.string.unbind_action),
            onAction = {
                performCloudUnbind()
            }
        )
    }

    private fun showCloudConfigDialog() {
        InputDialog.show(
            context = this,
            title = getString(R.string.cloud_config_title),
            presetText = KVUtils.getCloudServerUrl(),
            hint = getString(R.string.cloud_config_hint),
            confirmText = getString(R.string.cloud_config_next),
            onComplete = { serverUrl ->
                if (serverUrl.isBlank()) {
                    Toast.makeText(this, getString(R.string.cloud_config_server_required), Toast.LENGTH_SHORT).show()
                    return@show
                }
                showDeviceIdInput(serverUrl)
            }
        )
    }

    private fun showDeviceIdInput(serverUrl: String) {
        InputDialog.show(
            context = this,
            title = getString(R.string.cloud_config_device_id),
            presetText = KVUtils.getCloudDeviceId(),
            hint = getString(R.string.cloud_config_device_id_hint),
            confirmText = getString(R.string.cloud_config_get_code),
            onComplete = { deviceId ->
                if (deviceId.isBlank()) {
                    Toast.makeText(this, getString(R.string.cloud_config_device_id_required), Toast.LENGTH_SHORT).show()
                    return@show
                }
                requestPairingCode(serverUrl, deviceId)
            }
        )
    }

    private fun requestPairingCode(serverUrl: String, deviceId: String) {
        val loadingDialog = com.nextflow.nftouch.widget.LoadingDialog.show(this, getString(R.string.cloud_config_loading))
        Thread {
            try {
                val httpUrl = serverUrl.replace("ws://", "http://").replace("wss://", "https://")
                val url = java.net.URL("$httpUrl/api/pairing-code")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val body = org.json.JSONObject().apply {
                    put("device_id", deviceId)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val response = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(response)
                val code = json.optString("code", "")
                val warning = json.optString("warning", "")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadingDialog.dismiss()
                    if (code.isNotEmpty()) {
                        if (warning.isNotEmpty()) {
                            showDuplicateWarning(serverUrl, deviceId, code, warning)
                        } else {
                            showPairingCodeAndTokenInput(serverUrl, deviceId, code)
                        }
                    } else {
                        Toast.makeText(this@HomeActivity, getString(R.string.cloud_config_code_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadingDialog.dismiss()
                    Toast.makeText(this@HomeActivity, getString(R.string.cloud_config_connect_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showDuplicateWarning(serverUrl: String, deviceId: String, code: String, warning: String) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.cloud_config_duplicate_title),
            message = "$warning\n\n" + getString(R.string.cloud_config_duplicate_message, deviceId, code),
            actionTitle = getString(R.string.cloud_config_continue),
            cancelTitle = getString(R.string.cloud_config_change),
            onAction = {
                showPairingCodeAndTokenInput(serverUrl, deviceId, code)
            },
            onCancel = {
                showDeviceIdInput(serverUrl)
            }
        )
    }

    private fun showPairingCodeAndTokenInput(serverUrl: String, deviceId: String, code: String) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.cloud_config_code_title, code),
            message = getString(R.string.cloud_config_code_message),
            actionTitle = getString(R.string.cloud_config_input_token),
            cancelTitle = getString(R.string.cloud_config_copy),
            isDismissible = false,
            onAction = {
                showTokenInputDialog(serverUrl, deviceId)
            },
            onCancel = {
                try {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                    Toast.makeText(this@HomeActivity, getString(R.string.cloud_config_code_copied), Toast.LENGTH_SHORT).show()
                    // 重新弹出对话框，避免复制后关闭
                    showPairingCodeAndTokenInput(serverUrl, deviceId, code)
                } catch (_: Exception) {}
            }
        )
    }

    private fun showTokenInputDialog(serverUrl: String, deviceId: String) {
        InputDialog.show(
            context = this,
            title = getString(R.string.cloud_config_bind_token),
            presetText = KVUtils.getCloudToken(),
            hint = getString(R.string.cloud_config_bind_token_hint),
            confirmText = getString(R.string.cloud_config_save),
            cancelText = getString(R.string.common_cancel),
            dismissOnConfirm = false,
            cancelOnTouchOutside = false,
            onDialogComplete = { dialog, token ->
                if (token.isBlank()) {
                    Toast.makeText(this, getString(R.string.cloud_config_token_required), Toast.LENGTH_SHORT).show()
                    return@show
                }
                validateAndSaveCloudToken(dialog, serverUrl, deviceId, token)
            }
        )
    }

    private fun validateAndSaveCloudToken(dialog: InputDialog, serverUrl: String, deviceId: String, token: String) {
        val loadingDialog = com.nextflow.nftouch.widget.LoadingDialog.show(this, getString(R.string.cloud_config_validating_token))
        Thread {
            val result = CloudHub.validateServerToken(serverUrl, deviceId, token)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadingDialog.dismiss()
                if (!result.isValid) {
                    val msg = if (result.reason == "invalid device credentials") {
                        getString(R.string.cloud_config_token_invalid)
                    } else {
                        getString(R.string.cloud_config_token_validate_failed, result.reason ?: "unknown")
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                dialog.dismiss()
                KVUtils.setCloudServerUrl(serverUrl)
                KVUtils.setCloudDeviceId(deviceId)
                KVUtils.setCloudToken(token)
                KVUtils.setCloudBound(true)
                updateConfigStatus()
                CloudHub.reconnect(com.nextflow.nftouch.device.RemoteExecutor(appViewModel.taskOrchestrator))
                Toast.makeText(this, getString(R.string.cloud_config_saved), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
