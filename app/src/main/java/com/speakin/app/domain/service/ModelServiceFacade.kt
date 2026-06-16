package com.speakin.app.domain.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.speakin.modelservice.IModelService
import com.speakin.modelservice.IModelServiceCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 模型服务门面 — 封装与远程 :model 进程的 AIDL 通信。
 *
 * 主进程通过此类调用模型推理，即使模型进程崩溃 UI 也不受影响。
 * 崩溃后自动重连（Android 会重启 Service）。
 */
@Singleton
class ModelServiceFacade @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var service: IModelService? = null
    private val bound = AtomicBoolean(false)
    private val connectListeners = CopyOnWriteArrayList<() -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IModelService.Stub.asInterface(binder)
            bound.set(true)
            Log.i(TAG, "Connected to ModelService")
            connectListeners.forEach { it() }
            connectListeners.clear()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "ModelService disconnected (crashed?)")
            bound.set(false)
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "ModelService binding died")
            bound.set(false)
            service = null
        }
    }

    /**
     * 绑定到远程模型服务。
     */
    fun bind() {
        if (bound.get()) return
        val intent = Intent().apply {
            component = ComponentName("com.speakin.app", "com.speakin.modelservice.ModelService")
        }
        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
        }
    }

    /**
     * 解绑服务。
     */
    fun unbind() {
        if (bound.getAndSet(false)) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            service = null
        }
    }

    /**
     * 等待服务连接就绪。
     */
    private suspend fun awaitService(): IModelService? {
        if (bound.get() && service != null) return service

        return suspendCancellableCoroutine { cont ->
            connectListeners.add {
                cont.resume(service)
            }
            bind()
        }
    }

    // ═══════════════════════════════════════════════════════
    // LLM (文字润色)
    // ═══════════════════════════════════════════════════════

    /**
     * 加载 LLM 模型。
     */
    suspend fun loadLlm(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        val svc = awaitService() ?: return@withContext false
        svc.loadLlmModel(modelFile.absolutePath)
    }

    /**
     * 执行 LLM 推理。
     */
    suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        val svc = awaitService() ?: return@withContext ""
        svc.complete(prompt)
    }

    /**
     * LLM 是否已加载。
     */
    suspend fun isLlmLoaded(): Boolean = withContext(Dispatchers.IO) {
        service?.isLlmLoaded ?: false
    }

    // ═══════════════════════════════════════════════════════
    // ASR (语音转文字)
    // ═══════════════════════════════════════════════════════

    /**
     * 加载 ASR 模型。
     */
    suspend fun loadAsr(modelDir: File): Boolean = withContext(Dispatchers.IO) {
        val svc = awaitService() ?: return@withContext false
        svc.loadAsrModel(modelDir.absolutePath)
    }

    /**
     * 异步转写音频文件。
     */
    suspend fun transcribe(audioFile: File, onProgress: ((Float) -> Unit)? = null): Result<String> {
        val svc = awaitService() ?: return Result.failure(Exception("服务未连接"))

        return suspendCancellableCoroutine { cont ->
            val callback = object : IModelServiceCallback.Stub() {
                override fun onResult(text: String) {
                    cont.resume(Result.success(text))
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.resume(Result.failure(Exception(error)))
                }

                override fun onProgress(progress: Float) {
                    onProgress?.invoke(progress)
                }
            }
            svc.transcribe(audioFile.absolutePath, callback)
        }
    }

    /**
     * 释放所有模型资源。
     */
    fun releaseAll() {
        try { service?.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ModelSvcFacade"
    }
}
