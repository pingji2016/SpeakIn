package com.speakin.app.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户自定义模型配置的持久化存储。
 *
 * 允许用户在 About 页面中配置自定义的模型下载地址和模型类型，
 * 优先级高于代码中硬编码的默认值。
 */
@Singleton
class ModelConfigRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── ASR (Whisper) 模型类型 ───

    /**
     * 获取用户自定义的 ASR 模型类型。
     */
    fun getAsrModelType(): String {
        return prefs.getString(KEY_ASR_MODEL_TYPE, DEFAULT_ASR_MODEL_TYPE) ?: DEFAULT_ASR_MODEL_TYPE
    }

    /**
     * 设置 ASR 模型类型。
     */
    fun setAsrModelType(type: String) {
        prefs.edit().putString(KEY_ASR_MODEL_TYPE, type).apply()
    }

    // ─── ASR (Whisper) 模型 URL ───

    fun getAsrBaseUrls(): List<String> {
        val raw = prefs.getString(KEY_ASR_URLS, null) ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun setAsrBaseUrls(urls: String) {
        prefs.edit().putString(KEY_ASR_URLS, urls).apply()
    }

    fun getEffectiveAsrUrls(): List<String> {
        val custom = getAsrBaseUrls()
        return custom.ifEmpty { DEFAULT_ASR_URLS }
    }

    // ─── LLM 模型类型 ───

    /**
     * 获取用户自定义的 LLM 模型类型（GGUF 文件名）。
     */
    fun getLlmModelType(): String {
        return prefs.getString(KEY_LLM_MODEL_TYPE, DEFAULT_LLM_MODEL_TYPE) ?: DEFAULT_LLM_MODEL_TYPE
    }

    /**
     * 设置 LLM 模型类型（GGUF 文件名）。
     */
    fun setLlmModelType(type: String) {
        prefs.edit().putString(KEY_LLM_MODEL_TYPE, type).apply()
    }

    // ─── LLM 模型 URL ───

    fun getLlmUrls(): List<String> {
        val raw = prefs.getString(KEY_LLM_URLS, null) ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun setLlmUrls(urls: String) {
        prefs.edit().putString(KEY_LLM_URLS, urls).apply()
    }

    fun getEffectiveLlmUrls(): List<String> {
        val custom = getLlmUrls()
        return custom.ifEmpty { DEFAULT_LLM_URLS }
    }

    // ─── 重置 ───

    fun resetAll() {
        prefs.edit()
            .remove(KEY_ASR_URLS)
            .remove(KEY_LLM_URLS)
            .remove(KEY_ASR_MODEL_TYPE)
            .remove(KEY_LLM_MODEL_TYPE)
            .remove(KEY_MODEL_STORAGE_PATH)
            .remove(KEY_MODEL_STORAGE_URI)
            .apply()
    }

    fun hasCustomConfig(): Boolean {
        return getAsrBaseUrls().isNotEmpty() ||
                getLlmUrls().isNotEmpty() ||
                getAsrModelType() != DEFAULT_ASR_MODEL_TYPE ||
                getLlmModelType() != DEFAULT_LLM_MODEL_TYPE ||
                getModelStoragePath() != null
    }

    // ─── 模型存储路径 ───

    /**
     * 获取用户自定义的模型存储根目录路径（文件系统路径）。
     * 返回 null 表示使用默认内部存储。
     */
    fun getModelStoragePath(): String? {
        return prefs.getString(KEY_MODEL_STORAGE_PATH, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 设置自定义模型存储根目录路径。
     * 传入 null 恢复默认。
     */
    fun setModelStoragePath(path: String?) {
        if (path != null) {
            prefs.edit().putString(KEY_MODEL_STORAGE_PATH, path).apply()
        } else {
            prefs.edit().remove(KEY_MODEL_STORAGE_PATH).remove(KEY_MODEL_STORAGE_URI).apply()
        }
    }

    /**
     * 获取持久化的 SAF 目录 URI 字符串。
     */
    fun getModelStorageUri(): String? {
        return prefs.getString(KEY_MODEL_STORAGE_URI, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 持久化 SAF 目录 URI 字符串（用于后续获取访问权限）。
     */
    fun setModelStorageUri(uri: String?) {
        if (uri != null) {
            prefs.edit().putString(KEY_MODEL_STORAGE_URI, uri).apply()
        } else {
            prefs.edit().remove(KEY_MODEL_STORAGE_URI).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "speakin_model_config"
        private const val KEY_ASR_URLS = "asr_base_urls"
        private const val KEY_LLM_URLS = "llm_model_urls"
        private const val KEY_ASR_MODEL_TYPE = "asr_model_type"
        private const val KEY_LLM_MODEL_TYPE = "llm_model_type"
        private const val KEY_MODEL_STORAGE_PATH = "model_storage_path"
        private const val KEY_MODEL_STORAGE_URI = "model_storage_uri"

        /** 默认 ASR 模型类型 */
        const val DEFAULT_ASR_MODEL_TYPE = "whisper-tiny"
        /** 支持的 ASR 模型类型列表 */
        val SUPPORTED_ASR_TYPES = listOf(
            DEFAULT_ASR_MODEL_TYPE,
            "whisper-base",
            "whisper-small"
        )

        /** 默认 LLM 模型类型（GGUF 文件名） */
        const val DEFAULT_LLM_MODEL_TYPE = "qwen3-0.6b-q4_k_m.gguf"
        /** 支持的 LLM 模型类型列表 */
        val SUPPORTED_LLM_TYPES = listOf(
            DEFAULT_LLM_MODEL_TYPE,
            "qwen3-1.5b-q4_k_m.gguf",
            "qwen3-0.6b-q8_0.gguf"
        )

        /** 内置默认 ASR 下载地址（按优先级排列，网络下载时依次尝试） */
        val DEFAULT_ASR_URLS = listOf(
            "https://cdn.speakin.app/models/whisper-tiny/v1",
            "https://hf-mirror.com/SpeakIn/whisper-tiny/resolve/main",
            "https://www.modelscope.cn/min0max/whisper/resolve/master"
        )

        /** 内置默认 LLM 下载地址 */
        val DEFAULT_LLM_URLS = listOf(
            "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
        )
    }
}
