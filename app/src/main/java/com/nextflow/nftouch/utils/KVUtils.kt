package com.nextflow.nftouch.utils

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * MMKV 键值存储工具类
 *
 * 使用方式：
 *   // 在 Application.onCreate 中初始化
 *   KVUtils.init(context)
 *
 *   // 存取数据
 *   KVUtils.putString("key", "value")
 *   val value = KVUtils.getString("key", "default")
 */
object KVUtils {


    // 钉钉配置
    // 飞书配置
    // QQ 机器人配置
    // Discord 机器人配置
    // Telegram 机器人配置
    // 微信 iLink Bot 配置

    private lateinit var mmkv: MMKV

    private const val DEFAULT_INT = 0
    private const val DEFAULT_LONG = 0L
    private const val DEFAULT_BOOL = false
    private const val DEFAULT_FLOAT = 0f
    private const val DEFAULT_DOUBLE = 0.0

    /**
     * 在 Application.onCreate 中调用初始化
     */
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
    }

    // ==================== String ====================
    fun putString(key: String, value: String?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    // ==================== Int ====================
    fun putInt(key: String, value: Int): Boolean {
        return mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = DEFAULT_INT): Int {
        return mmkv.decodeInt(key, defaultValue)
    }

    // ==================== Long ====================
    fun putLong(key: String, value: Long): Boolean {
        return mmkv.encode(key, value)
    }

    fun getLong(key: String, defaultValue: Long = DEFAULT_LONG): Long {
        return mmkv.decodeLong(key, defaultValue)
    }

    // ==================== Boolean ====================
    fun putBoolean(key: String, value: Boolean): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = DEFAULT_BOOL): Boolean {
        return mmkv.decodeBool(key, defaultValue)
    }

    // ==================== Float ====================
    fun putFloat(key: String, value: Float): Boolean {
        return mmkv.encode(key, value)
    }

    fun getFloat(key: String, defaultValue: Float = DEFAULT_FLOAT): Float {
        return mmkv.decodeFloat(key, defaultValue)
    }

    // ==================== Double ====================
    fun putDouble(key: String, value: Double): Boolean {
        return mmkv.encode(key, value)
    }

    fun getDouble(key: String, defaultValue: Double = DEFAULT_DOUBLE): Double {
        return mmkv.decodeDouble(key, defaultValue)
    }

    // ==================== Bytes ====================
    fun putBytes(key: String, value: ByteArray?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBytes(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    // ==================== 常用操作 ====================
    fun contains(key: String): Boolean {
        return mmkv.containsKey(key)
    }

    fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }

    fun remove(vararg keys: String) {
        mmkv.removeValuesForKeys(keys)
    }

    fun clear() {
        mmkv.clearAll()
    }

    fun getAllKeys(): Array<String> {
        return mmkv.allKeys() ?: emptyArray()
    }

    /**
     * 同步写入磁盘（默认是异步的）
     */
    fun sync() {
        mmkv.sync()
    }


    // ==================== 引导页 ====================
    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // ==================== 钉钉配置 ====================

    // ==================== 飞书配置 ====================

    // ==================== QQ 机器人配置 ====================

    // ==================== Discord 机器人配置 ====================

    // ==================== Telegram 机器人配置 ====================

    // ==================== 微信 iLink Bot 配置 ====================

    // ==================== 局域网配置服务 ====================
    private const val KEY_CONFIG_SERVER_ENABLED = "KEY_CONFIG_SERVER_ENABLED"
    fun isConfigServerEnabled(): Boolean = getBoolean(KEY_CONFIG_SERVER_ENABLED, false)
    fun setConfigServerEnabled(enabled: Boolean) = putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled)

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"
    private const val KEY_LLM_PROVIDER = "KEY_LLM_PROVIDER"

    fun getLlmApiKey(): String = getString(KEY_LLM_API_KEY, "")
    fun setLlmApiKey(value: String) = putString(KEY_LLM_API_KEY, value)
    fun getLlmBaseUrl(): String = getString(KEY_LLM_BASE_URL, "")
    fun setLlmBaseUrl(value: String) = putString(KEY_LLM_BASE_URL, value)
    fun getLlmModelName(): String = getString(KEY_LLM_MODEL_NAME, "")
    fun setLlmModelName(value: String) = putString(KEY_LLM_MODEL_NAME, value)
    fun getLlmProvider(): String = getString(KEY_LLM_PROVIDER, "")
    fun setLlmProvider(value: String) = putString(KEY_LLM_PROVIDER, value)

    /** 是否已配置 LLM（API Key 非空即视为已配置） */
    fun hasLlmConfig(): Boolean = getLlmApiKey().isNotEmpty()

    // ==================== 云控配置 ====================
    private const val KEY_CLOUD_SERVER_URL = "KEY_CLOUD_SERVER_URL"
    private const val KEY_CLOUD_DEVICE_ID = "KEY_CLOUD_DEVICE_ID"
    private const val KEY_CLOUD_TOKEN = "KEY_CLOUD_TOKEN"
    private const val KEY_CLOUD_BOUND = "KEY_CLOUD_BOUND"

    fun getCloudServerUrl(): String = getString(KEY_CLOUD_SERVER_URL, "")
    fun setCloudServerUrl(value: String) = putString(KEY_CLOUD_SERVER_URL, value)
    fun getCloudDeviceId(): String = getString(KEY_CLOUD_DEVICE_ID, "")
    fun setCloudDeviceId(value: String) = putString(KEY_CLOUD_DEVICE_ID, value)
    fun getCloudToken(): String = getString(KEY_CLOUD_TOKEN, "")
    fun setCloudToken(value: String) = putString(KEY_CLOUD_TOKEN, value)
    fun isCloudBound(): Boolean = getBoolean(KEY_CLOUD_BOUND, false)
    fun setCloudBound(bound: Boolean) = putBoolean(KEY_CLOUD_BOUND, bound)

    /** 是否已配置云控 */
    fun hasCloudConfig(): Boolean =
        getCloudServerUrl().isNotEmpty() && getCloudDeviceId().isNotEmpty() && getCloudToken().isNotEmpty()
}
