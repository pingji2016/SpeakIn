package com.speakin.app.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户自定义模型下载地址的持久化存储。
 *
 * 允许用户在 About 页面中配置自定义的模型下载 URL，
 * 优先级高于代码中硬编码的默认地址。
 */
@Singleton
class ModelConfigRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── ASR (Whisper) 模型 URL ───

    /**
     * 获取用户自定义的 ASR 模型下载基础 URL 列表。
     * 一行一个 URL，空字符串或空白行会被过滤。
     */
    fun getAsrBaseUrls(): List<String> {
        val raw = prefs.getString(KEY_ASR_URLS, null) ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 设置 ASR 模型下载基础 URL。
     * @param urls 每行一个 URL
     */
    fun setAsrBaseUrls(urls: String) {
        prefs.edit().putString(KEY_ASR_URLS, urls).apply()
    }

    /**
     * 获取有效的 ASR URL 列表：用户自定义 > 内置默认。
     */
    fun getEffectiveAsrUrls(): List<String> {
        val custom = getAsrBaseUrls()
        return custom.ifEmpty { DEFAULT_ASR_URLS }
    }

    // ─── LLM 模型 URL ───

    /**
     * 获取用户自定义的 LLM 模型下载 URL 列表。
     * 一行一个 URL。
     */
    fun getLlmUrls(): List<String> {
        val raw = prefs.getString(KEY_LLM_URLS, null) ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 设置 LLM 模型下载 URL。
     * @param urls 每行一个 URL
     */
    fun setLlmUrls(urls: String) {
        prefs.edit().putString(KEY_LLM_URLS, urls).apply()
    }

    /**
     * 获取有效的 LLM URL 列表：用户自定义 > 内置默认。
     */
    fun getEffectiveLlmUrls(): List<String> {
        val custom = getLlmUrls()
        return custom.ifEmpty { DEFAULT_LLM_URLS }
    }

    // ─── 重置 ───

    /**
     * 清除所有自定义配置，恢复使用内置默认地址。
     */
    fun resetAll() {
        prefs.edit()
            .remove(KEY_ASR_URLS)
            .remove(KEY_LLM_URLS)
            .apply()
    }

    /**
     * 检查是否有任何自定义配置。
     */
    fun hasCustomConfig(): Boolean {
        return getAsrBaseUrls().isNotEmpty() || getLlmUrls().isNotEmpty()
    }

    companion object {
        private const val PREFS_NAME = "speakin_model_config"
        private const val KEY_ASR_URLS = "asr_base_urls"
        private const val KEY_LLM_URLS = "llm_model_urls"

        /** 内置默认 ASR 下载地址 */
        val DEFAULT_ASR_URLS = listOf(
            "https://cdn.speakin.app/models/whisper-tiny/v1",
            "https://hf-mirror.com/SpeakIn/whisper-tiny/resolve/main"
        )

        /** 内置默认 LLM 下载地址 */
        val DEFAULT_LLM_URLS = listOf(
            "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
        )
    }
}
