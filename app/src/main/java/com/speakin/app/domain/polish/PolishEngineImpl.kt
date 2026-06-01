package com.speakin.app.domain.polish

import com.speakin.app.domain.llm.LocalLlmEngine
import com.speakin.app.domain.llm.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PolishEngineImpl @Inject constructor(
    private val modelManager: ModelManager,
    private val engine: LocalLlmEngine
) : PolishEngine {

    override fun polish(text: String, callback: PolishEngine.Callback) {
        if (text.isBlank()) {
            callback.onResult(text)
            return
        }

        GlobalScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (!engine.isLoaded) {
                        callback.onResult(text)
                        return@withContext
                    }

                    val prompt = buildPolishPrompt(text)
                    val result = engine.complete(prompt)

                    if (result.isBlank()) {
                        callback.onResult(text)
                    } else {
                        callback.onResult(result)
                    }
                } catch (e: Exception) {
                    callback.onResult(text)
                }
            }
        }
    }

    override fun isModelLoaded(): Boolean = engine.isLoaded

    override fun release() {
        modelManager.release()
    }

    private fun buildPolishPrompt(text: String): String {
        return "请润色以下文字，修正标点和语病，使其更通顺自然。直接输出润色结果，不要添加任何额外说明。\n\n$text"
    }
}
