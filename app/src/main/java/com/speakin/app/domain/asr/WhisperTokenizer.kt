package com.speakin.app.domain.asr

import org.json.JSONObject
import java.io.File

/**
 * Whisper BPE Tokenizer - 仅实现解码方向（IDs → text）。
 *
 * 使用 HuggingFace 格式的 tokenizer.json 或 vocab.json。
 * 参考 OpenAI whisper 的 byte-level BPE tokenizer 实现。
 */
class WhisperTokenizer(tokenizerFile: File) {

    private val idToToken: MutableMap<Int, String> = mutableMapOf()
    private val byteDecoder: MutableMap<Int, Byte> = mutableMapOf()
    private val specialTokens: Set<Int>

    companion object {
        // whisper 特殊 token IDs（multilingual）
        private const val SOT_TOKEN = 50258    // <|startoftranscript|>
        private const val EOT_TOKEN = 50257    // <|endoftext|>
        private const val TRANSCRIBE_TOKEN = 50362
        private const val NOTIMESTAMPS_TOKEN = 50363
        private const val SOT_EN_TOKEN = 50259
        private const val SOT_ZH_TOKEN = 50319
    }

    init {
        loadTokenizer(tokenizerFile)
        specialTokens = buildSpecialTokenSet()
        byteDecoder.putAll(buildByteDecoder())
    }

    private fun loadTokenizer(file: File) {
        val content = file.readText()

        if (content.contains("\"added_tokens\"")) {
            // HuggingFace tokenizer.json 格式
            val root = JSONObject(content)
            val model = root.getJSONObject("model")
            val vocab = model.getJSONObject("vocab")
            for (key in vocab.keys()) {
                val id = vocab.getInt(key)
                idToToken[id] = key
            }
        } else {
            // 简单 vocab.json 格式: {"token_str": id, ...}
            val json = JSONObject(content)
            for (key in json.keys()) {
                val id = json.getInt(key)
                idToToken[id] = key
            }
        }
    }

    private fun buildSpecialTokenSet(): Set<Int> {
        return idToToken.entries
            .filter { (_, token) ->
                token.startsWith("<|") && token.endsWith("|>")
            }
            .map { it.key }
            .toSet()
    }

    /**
     * 将 token IDs 解码为文本（过滤特殊 token）。
     */
    fun decode(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""

        val filtered = tokenIds.filter { it !in specialTokens }
        if (filtered.isEmpty()) return ""

        // 按 byte-level 编码拼接
        val bytes = mutableListOf<Byte>()
        for (id in filtered) {
            val token = idToToken[id] ?: continue
            for (ch in token) {
                val byte = byteDecoder[ch.code]
                if (byte != null) {
                    bytes.add(byte)
                } else {
                    // 普通 ASCII 字符直接转 byte
                    bytes.add(ch.code.toByte())
                }
            }
        }

        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            // fallback: 返回原始 token 拼接
            filtered.mapNotNull { idToToken[it] }.joinToString(" ")
        }
    }

    /**
     * GPT-2 style bytes_to_unicode mapping in reverse.
     * 原始 mapping: byte → unicode char
     * 此方法返回: unicode char code → byte
     */
    private fun buildByteDecoder(): Map<Int, Byte> {
        val result = mutableMapOf<Int, Byte>()
        var n = 0
        for (b in 0..255) {
            if ((b in 0x21..0x7E) || b >= 0xA0) {
                result[b] = b.toByte()
            } else {
                result[256 + n] = b.toByte()
                n++
            }
        }
        return result
    }

    /**
     * 检查 token ID 是否为结束标记。
     */
    fun isEndOfText(id: Int): Boolean {
        return id == EOT_TOKEN
    }

    /**
     * 获取起始 token ID（<|startoftranscript|> + <|zh|> + <|transcribe|> + <|notimestamps|>）。
     */
    fun getSotTokens(languageCode: String = "zh"): List<Int> {
        val langToken = when (languageCode) {
            "zh" -> SOT_ZH_TOKEN
            "en" -> SOT_EN_TOKEN
            else -> SOT_TOKEN
        }
        return listOf(SOT_TOKEN, langToken, TRANSCRIBE_TOKEN, NOTIMESTAMPS_TOKEN)
    }
}
