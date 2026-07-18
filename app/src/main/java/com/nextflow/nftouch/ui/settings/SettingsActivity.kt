package com.nextflow.nftouch.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nextflow.nftouch.R
import com.nextflow.nftouch.appViewModel
import com.nextflow.nftouch.base.BaseActivity
import com.nextflow.nftouch.device.CloudHub
import com.nextflow.nftouch.server.ConfigServerManager
import com.nextflow.nftouch.utils.KVUtils
import com.nextflow.nftouch.widget.AlertDialog
import com.nextflow.nftouch.widget.CommonToolbar
import com.nextflow.nftouch.widget.InputDialog
import com.nextflow.nftouch.widget.MenuGroup
import com.nextflow.nftouch.widget.MenuItem
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private val viewModel by lazy { ViewModelProvider(this)[SettingsViewModel::class.java] }
    private val menuItems = mutableMapOf<String, MenuItem>()

    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        initToolbar()
        initMenuGroups()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun initMenuGroups() {
        val channelGroup = findViewById<MenuGroup>(R.id.channelGroup)
        channelGroup.setTitle(getString(R.string.settings_group_channel))

        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_lan_config,
            title = getString(R.string.menu_lan_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LAN_CONFIG) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.CLOUD.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.menu_cloud_bind),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.CLOUD) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.CLOUD.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        val modelGroup = findViewById<MenuGroup>(R.id.modelGroup)
        modelGroup.setTitle(getString(R.string.settings_group_model))

        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settingItems.collect { items ->
                        val greenColor = getColor(R.color.colorSuccessPrimary)
                        val defaultColor = getColor(R.color.colorTextPrimary)
                        val secondaryColor = getColor(R.color.colorTextSecondary)

                        val llmItem = menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]
                        val cloudItem = menuItems[SettingsViewModel.MenuAction.CLOUD.name]

                        if (KVUtils.hasLlmConfig()) {
                            val provider = KVUtils.getLlmProvider().ifEmpty { getString(R.string.llm_config_custom) }
                            llmItem?.setTitle(provider)
                            llmItem?.setSubtitle(KVUtils.getLlmModelName())
                            llmItem?.setTitleColor(greenColor)
                            llmItem?.setSubtitleColor(greenColor)
                            llmItem?.setTrailingText("")
                        } else {
                            llmItem?.setTitle(getString(R.string.menu_llm_config))
                            llmItem?.setSubtitle(getString(R.string.menu_llm_config_subtitle))
                            llmItem?.setTitleColor(defaultColor)
                            llmItem?.setSubtitleColor(secondaryColor)
                            llmItem?.setTrailingText(getString(R.string.common_unconfigured))
                        }

                        if (KVUtils.isCloudBound()) {
                            cloudItem?.setTitle(KVUtils.getCloudServerUrl())
                            cloudItem?.setSubtitle(KVUtils.getCloudDeviceId())
                            cloudItem?.setTitleColor(greenColor)
                            cloudItem?.setSubtitleColor(greenColor)
                            cloudItem?.setTrailingText("")
                        } else {
                            cloudItem?.setTitle(getString(R.string.menu_cloud_bind))
                            cloudItem?.setSubtitle(getString(R.string.menu_cloud_bind_subtitle))
                            cloudItem?.setTitleColor(defaultColor)
                            cloudItem?.setSubtitleColor(secondaryColor)
                            cloudItem?.setTrailingText(getString(R.string.common_unbound))
                        }

                        items.forEach { (key, value) ->
                            if (key != SettingsViewModel.MenuAction.LLM_CONFIG.name && key != SettingsViewModel.MenuAction.CLOUD.name) {
                                when (value) {
                                    is SettingsViewModel.SettingValue.Text -> menuItems[key]?.setTrailingText(value.text)
                                    is SettingsViewModel.SettingValue.Switch -> {}
                                }
                            }
                        }
                    }
                }

                launch {
                    ConfigServerManager.configChanged.collect {
                        viewModel.refresh()
                        appViewModel.initAgent()
                        appViewModel.afterInit()
                    }
                }

                launch {
                    viewModel.menuClickEvent.collect { action ->
                        when (action) {
                            SettingsViewModel.MenuAction.LAN_CONFIG -> {
                                val result = viewModel.toggleConfigServer(this@SettingsActivity)
                                if (result == getString(R.string.lan_config_no_wifi)) {
                                    Toast.makeText(this@SettingsActivity, R.string.lan_config_no_wifi, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SettingsViewModel.MenuAction.CLOUD -> {
                                if (KVUtils.isCloudBound()) {
                                    showUnbindDialog(getString(R.string.menu_cloud_bind)) {
                                        performCloudUnbind()
                                    }
                                } else {
                                    showCloudConfigDialog()
                                }
                            }
                            SettingsViewModel.MenuAction.LLM_CONFIG -> {
                                llmConfigLauncher.launch(Intent(this@SettingsActivity, LlmConfigActivity::class.java))
                            }
                            null -> {}
                            else -> {}
                        }
                        viewModel.clearMenuClickEvent()
                    }
                }
            }
        }
    }

    private fun showUnbindDialog(channelName: String, onUnbind: () -> Unit) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.unbind_title),
            message = getString(R.string.unbind_message, channelName, channelName),
            actionTitle = getString(R.string.unbind_action),
            onAction = onUnbind
        )
    }

    private fun performCloudUnbind() {
        Thread {
            CloudHub.requestServerUnbind()
            KVUtils.setCloudBound(false)
            CloudHub.disconnect()
            runOnUiThread {
                viewModel.refresh()
                Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
            }
        }.start()
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
                        if (warning.isNotEmpty()) showDuplicateWarning(serverUrl, deviceId, code, warning)
                        else showPairingCodeAndTokenInput(serverUrl, deviceId, code)
                    } else {
                        Toast.makeText(this@SettingsActivity, getString(R.string.cloud_config_code_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadingDialog.dismiss()
                    Toast.makeText(this@SettingsActivity, getString(R.string.cloud_config_connect_failed, e.message), Toast.LENGTH_SHORT).show()
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
            onAction = { showPairingCodeAndTokenInput(serverUrl, deviceId, code) },
            onCancel = { showDeviceIdInput(serverUrl) }
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
                    Toast.makeText(this@SettingsActivity, getString(R.string.cloud_config_code_copied), Toast.LENGTH_SHORT).show()
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
                viewModel.refresh()
                CloudHub.reconnect(com.nextflow.nftouch.device.RemoteExecutor(appViewModel.taskOrchestrator))
                Toast.makeText(this, getString(R.string.cloud_config_saved), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
