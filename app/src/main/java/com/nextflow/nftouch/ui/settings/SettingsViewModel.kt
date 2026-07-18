package com.nextflow.nftouch.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.nextflow.nftouch.ClawApplication
import com.nextflow.nftouch.R
import com.nextflow.nftouch.server.ConfigServerManager
import com.nextflow.nftouch.utils.KVUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel : ViewModel() {

    private val _settingItems = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
    val settingItems: StateFlow<Map<String, SettingValue>> = _settingItems

    private val _menuClickEvent = MutableStateFlow<MenuAction?>(null)
    val menuClickEvent: StateFlow<MenuAction?> = _menuClickEvent

    init { refresh() }

    fun refresh() {
        val map = mapOf(
            MenuAction.LLM_CONFIG.name to SettingValue.Text(if (KVUtils.hasLlmConfig()) KVUtils.getLlmModelName() else ClawApplication.instance.getString(R.string.common_unconfigured)),
            MenuAction.LAN_CONFIG.name to SettingValue.Text(getLanConfigTrailingText()),
            MenuAction.CLOUD.name to SettingValue.Text(if (KVUtils.isCloudBound()) ClawApplication.instance.getString(R.string.common_bound) + " · " + KVUtils.getCloudDeviceId() else ClawApplication.instance.getString(R.string.common_unbound))
        )
        _settingItems.value = map
    }

    fun updateSettingValue(key: String, value: SettingValue) {
        _settingItems.value = _settingItems.value.toMutableMap().apply { put(key, value) }
    }

    fun updateTrailingText(key: String, text: String) {
        updateSettingValue(key, SettingValue.Text(text))
    }

    fun onMenuItemClick(action: MenuAction) {
        _menuClickEvent.value = action
    }

    fun clearMenuClickEvent() {
        _menuClickEvent.value = null
    }

    fun toggleConfigServer(context: Context): String {
        return if (ConfigServerManager.isRunning()) {
            ConfigServerManager.stop()
            KVUtils.setConfigServerEnabled(false)
            val text = getLanConfigTrailingText()
            updateTrailingText(MenuAction.LAN_CONFIG.name, text)
            text
        } else {
            val started = ConfigServerManager.start(context)
            if (started) {
                KVUtils.setConfigServerEnabled(true)
                val text = getLanConfigTrailingText()
                updateTrailingText(MenuAction.LAN_CONFIG.name, text)
                text
            } else {
                ClawApplication.instance.getString(R.string.lan_config_no_wifi)
            }
        }
    }

    private fun getLanConfigTrailingText(): String {
        return if (ConfigServerManager.isRunning()) {
            ConfigServerManager.getAddress() ?: ClawApplication.instance.getString(R.string.lan_config_stopped)
        } else {
            ClawApplication.instance.getString(R.string.lan_config_stopped)
        }
    }

    sealed class SettingValue {
        data class Text(val text: String) : SettingValue()
        data class Switch(val isOn: Boolean) : SettingValue()
    }

    enum class MenuAction {
        LAN_CONFIG, CLOUD, LLM_CONFIG
    }
}
