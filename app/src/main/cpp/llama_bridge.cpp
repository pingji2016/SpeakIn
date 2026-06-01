#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#define TAG "SpeakIn-LLaMA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include "llama.h"

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static const llama_vocab* g_vocab = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_speakin_app_domain_llm_LocalLlmEngine_nativeInit(
    JNIEnv* env, jobject, jstring model_path_str) {

    if (g_model != nullptr) {
        return JNI_TRUE;
    }

    llama_backend_init();

    const char* model_path = env->GetStringUTFChars(model_path_str, nullptr);
    LOGI("Loading model: %s", model_path);

    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(model_path, model_params);

    env->ReleaseStringUTFChars(model_path_str, model_path);

    if (g_model == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;
    ctx_params.n_batch = 512;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_speakin_app_domain_llm_LocalLlmEngine_nativeComplete(
    JNIEnv* env, jobject, jstring prompt_str) {

    if (g_model == nullptr || g_ctx == nullptr || g_vocab == nullptr) {
        return env->NewStringUTF("");
    }

    const char* prompt = env->GetStringUTFChars(prompt_str, nullptr);
    LOGI("Prompt: %s", prompt);

    std::string result;
    const int n_len = 256;

    int n_tokens = llama_tokenize(
        g_vocab, prompt, -1,
        nullptr, 0, true, false
    );

    std::vector<llama_token> tokens(n_tokens);
    n_tokens = llama_tokenize(
        g_vocab, prompt, -1,
        tokens.data(), tokens.size(), true, false
    );

    env->ReleaseStringUTFChars(prompt_str, prompt);

    if (n_tokens <= 0) {
        return env->NewStringUTF("");
    }

    tokens.resize(n_tokens);
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("decode failed");
        return env->NewStringUTF("");
    }

    for (int i = 0; i < n_len; i++) {
        float* logits = llama_get_logits_ith(g_ctx, -1);
        int n_vocab = llama_vocab_n_tokens(g_vocab);

        llama_token id = 0;
        float max_logit = logits[0];
        for (int j = 1; j < n_vocab; j++) {
            if (logits[j] > max_logit) {
                max_logit = logits[j];
                id = j;
            }
        }

        if (llama_vocab_is_eog(g_vocab, id)) {
            break;
        }

        char buf[8];
        int n = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }

        llama_batch next_batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, next_batch) != 0) {
            break;
        }
    }

    LOGI("Result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_speakin_app_domain_llm_LocalLlmEngine_nativeRelease(
    JNIEnv*, jobject) {

    LOGI("Releasing model");
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    llama_backend_free();
    LOGI("Released");
}
