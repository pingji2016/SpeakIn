package com.speakin.modelservice

import org.json.JSONObject
import java.io.File

/**
 * Whisper BPE Tokenizer - 只实现解码方向（IDs → text）。
 */
class WhisperTokenizer(tokenizerFile: File) {

    private val idToToken: MutableMap<Int, String> = mutableMapOf()
    private val byteDecoder: MutableMap<Int, Byte> = mutableMapOf()
    private val specialTokens: Set<Int>
    val size: Int get() = idToToken.size

    companion object {
        private const val EOT_TOKEN = 50257
        private const val SOT_TOKEN = 50258
        private const val SOT_ZH_TOKEN = 50319
        private const val TRANSCRIBE_TOKEN = 50359
        private const val NOTIMESTAMPS_TOKEN = 50363
        private const val NOSPEECH_TOKEN = 50362
    }

    init {
        loadTokenizer(tokenizerFile)
        specialTokens = idToToken.entries
            .filter { (_, t) -> t.startsWith("<|") && t.endsWith("|>") }
            .map { it.key }.toSet()
        byteDecoder.putAll(buildByteDecoder())
    }

    private fun loadTokenizer(file: File) {
        val content = file.readText()
        if (content.contains("\"added_tokens\"")) {
            val root = JSONObject(content)
            val model = root.getJSONObject("model")
            val vocab = model.getJSONObject("vocab")
            for (key in vocab.keys()) idToToken[vocab.getInt(key)] = key
        } else {
            val json = JSONObject(content)
            for (key in json.keys()) idToToken[json.getInt(key)] = key
        }
    }

    fun decode(tokenIds: List<Int>): String {
        val filtered = tokenIds.filter { it !in specialTokens }
        if (filtered.isEmpty()) return ""

        val bytes = mutableListOf<Byte>()
        for (id in filtered) {
            val token = idToToken[id] ?: continue
            for (ch in token) {
                bytes.add(byteDecoder[ch.code] ?: ch.code.toByte())
            }
        }
        return try { String(bytes.toByteArray(), Charsets.UTF_8) }
        catch (e: Exception) { filtered.mapNotNull { idToToken[it] }.joinToString(" ") }
    }

    private fun buildByteDecoder(): Map<Int, Byte> {
        val result = mutableMapOf<Int, Byte>()
        var n = 0
        for (b in 0..255) {
            if ((b in 0x21..0x7E) || b >= 0xA0) result[b] = b.toByte()
            else { result[256 + n] = b.toByte(); n++ }
        }
        return result
    }

    fun isEndOfText(id: Int): Boolean = id == EOT_TOKEN

    fun getSotTokens(): List<Int> = listOf(SOT_TOKEN, SOT_ZH_TOKEN, TRANSCRIBE_TOKEN, NOTIMESTAMPS_TOKEN)
}
