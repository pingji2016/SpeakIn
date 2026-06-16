package com.speakin.modelservice;

/**
 * 模型服务回调接口（跨进程，oneway 表示异步不阻塞）。
 */
oneway interface IModelServiceCallback {
    void onResult(String text);
    void onError(String error);
    void onProgress(float progress);
}
