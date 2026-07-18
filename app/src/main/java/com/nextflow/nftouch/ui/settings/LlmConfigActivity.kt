package com.nextflow.nftouch.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.nextflow.nftouch.ClawApplication
import com.nextflow.nftouch.R
import com.nextflow.nftouch.base.BaseActivity
import com.nextflow.nftouch.utils.KVUtils
import com.nextflow.nftouch.widget.CommonToolbar
import com.nextflow.nftouch.widget.KButton

/**
 * LLM 配置页：选择 Provider → 自动填 Base URL → 手填 Model + API Key
 */
class LlmConfigActivity : BaseActivity() {

    private val providers = listOf(
        LlmProvider("OpenAI", "https://api.openai.com/v1"),
        LlmProvider("Anthropic", "https://api.anthropic.com"),
        LlmProvider("Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
        LlmProvider("DeepSeek", "https://api.deepseek.com/v1"),
        LlmProvider("Azure OpenAI", ""),
        LlmProvider(ClawApplication.instance.getString(R.string.llm_config_custom), "")
    )

    private var selectedProvider: LlmProvider? = null

    private lateinit var tvProvider: TextView
    private lateinit var etApiKey: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etModelName: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        tvProvider = findViewById(R.id.tvProvider)
        etApiKey = findViewById(R.id.etApiKey)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModelName = findViewById(R.id.etModelName)

        // 恢复已保存的配置
        val savedProvider = KVUtils.getLlmProvider()
        if (savedProvider.isNotEmpty()) {
            selectedProvider = providers.find { it.name == savedProvider }
        }
        if (selectedProvider != null) {
            tvProvider.text = selectedProvider!!.displayName
        }
        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl())
        etModelName.setText(KVUtils.getLlmModelName())

        // 点击 Provider 卡片弹出选择列表
        findViewById<View>(R.id.cardProvider).setOnClickListener {
            showProviderDialog()
        }

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            save()
        }
    }

    private fun showProviderDialog() {
        val names = providers.map { it.name }.toTypedArray()
        val preselected = selectedProvider?.let { providers.indexOf(it) } ?: -1

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.llm_config_select_provider))
            .setSingleChoiceItems(names, preselected) { dialog, which ->
                val provider = providers[which]
                selectProvider(provider)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun selectProvider(provider: LlmProvider) {
        selectedProvider = provider
        tvProvider.text = provider.displayName

        if (provider.name == ClawApplication.instance.getString(R.string.llm_config_custom)) {
            etBaseUrl.setText(KVUtils.getLlmBaseUrl())
            etBaseUrl.hint = getString(R.string.llm_config_base_url_hint)
            etModelName.setText(KVUtils.getLlmModelName())
            etModelName.hint = getString(R.string.llm_config_model_name_hint)
        } else {
            if (provider.defaultBaseUrl.isNotEmpty()) {
                etBaseUrl.setText(provider.defaultBaseUrl)
            }
            etModelName.hint = getString(R.string.llm_config_model_name_hint)
        }
    }

    private fun save() {
        val apiKey = etApiKey.text.toString().trim()
        val baseUrl = etBaseUrl.text.toString().trim()
        val modelName = etModelName.text.toString().trim()

        if (apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
            return
        }

        val provider = selectedProvider
        if (provider == null && baseUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.llm_config_select_provider_or_url), Toast.LENGTH_SHORT).show()
            return
        }

        KVUtils.setLlmApiKey(apiKey)
        KVUtils.setLlmBaseUrl(baseUrl)
        KVUtils.setLlmModelName(modelName)
        KVUtils.setLlmProvider(provider?.name ?: ClawApplication.instance.getString(R.string.llm_config_custom))

        ClawApplication.appViewModelInstance.updateAgentConfig()
        ClawApplication.appViewModelInstance.initAgent()
        ClawApplication.appViewModelInstance.afterInit()
        Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    data class LlmProvider(
        val name: String,
        val defaultBaseUrl: String,
        val displayName: String = name
    )
}
