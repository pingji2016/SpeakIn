package com.speakin.modelservice;

import com.speakin.modelservice.IModelServiceCallback;

/**
 * 模型服务接口。
 *
 * 运行在独立进程 :model 中，与主进程通过 Binder 通信。
 * 所有耗时的模型推理都在此进程中执行，崩溃不影响主进程 UI。
 */
interface IModelService {
    // ─── LLM (文字润色) ─────────────────────────────────

    /** 加载 GGUF 模型，返回是否成功 */
    boolean loadLlmModel(String modelPath);

    /** 执行 LLM 推理，返回生成文本 */
    String complete(String prompt);

    /** LLM 是否已加载 */
    boolean isLlmLoaded();

    // ─── ASR (语音转文字) ────────────────────────────────

    /** 加载 whisper .pte 模型目录，返回是否成功 */
    boolean loadAsrModel(String asrDir);

    /** 异步转写音频文件，结果通过 callback 返回 */
    void transcribe(String audioPath, IModelServiceCallback callback);

    /** ASR 是否已加载 */
    boolean isAsrLoaded();

    // ─── 生命周期 ────────────────────────────────────────

    /** 释放所有模型资源 */
    void release();
}
