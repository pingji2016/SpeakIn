# llama.cpp 集成与交叉编译指南

> 记录 SpeakIn 项目中集成 llama.cpp 的完整步骤

---

## 环境信息

| 项目 | 值 |
|------|------|
| 宿主机 | Windows 11 |
| WSL | Ubuntu 24.04 LTS |
| NDK | r27c (Linux 版，在 WSL 内) |
| llama.cpp 源码 | master (git clone 或 tar.gz 下载) |
| Android ABI | arm64-v8a |
| 目标 API | Android 8.0+ (API 26) |

---

## 一、WSL 环境准备

WSL 中从零搭建交叉编译环境。

### 1.1 安装基础工具（root）

```bash
wsl -u root bash -c "apt update && apt install -y cmake build-essential curl unzip"
```

### 1.2 下载 Linux 版 Android NDK（在 WSL 内）

Windows 本地的 NDK 是 `windows-x86_64` 版本的，WSL 无法直接调用（WSL 里会去找 `linux-x86_64` 的预编译工具链）。所以需要在 WSL 内单独下载 Linux 版 NDK：

```bash
# 在 WSL 中执行
cd ~
curl -L -o ndk.zip https://dl.google.com/android/repository/android-ndk-r27c-linux.zip
unzip ndk.zip
# 解压后得到 ~/android-ndk-r27c/
```

> **为什么不能直接用 Windows NDK？**
>
> Windows NDK 路径为
> `C:\Users\<user>\AppData\Local\Android\Sdk\ndk\26.x.x\`
> 其中 `toolchains/llvm/prebuilt/` 下是 `windows-x86_64/` 目录，里面的 `clang.exe` 是 Windows PE 格式。
> WSL Linux 环境无法执行 Windows exe，因此需要在 WSL 内安装 Linux 版 NDK。
>
> 验证工具链是否可用：
> ```bash
> ls ~/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
> # 应返回文件路径，表示 NDK 正确
> ```

### 1.3 获取 llama.cpp 源码

Git clone 如果遇到网络超时，改用 tar.gz 下载：

```bash
# 方案 A：git clone（需要稳定的 GitHub 连接）
cd ~
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git

# 方案 B（推荐，避免网络断连）：curl 下载压缩包
curl -L -o llama.cpp.tar.gz https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.tar.gz
tar xzf llama.cpp.tar.gz
mv llama.cpp-master llama.cpp
```

---

## 二、交叉编译 llama.cpp

### 2.1 CMake 配置

```bash
export NDK="$HOME/android-ndk-r27c"
cd ~/llama.cpp
mkdir -p build-android

cmake \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=26 \
  -DCMAKE_C_FLAGS="-march=armv8.2a" \
  -DCMAKE_CXX_FLAGS="-march=armv8.2a" \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_CURL=OFF \
  -B build-android \
  -S .
```

| 参数 | 说明 |
|------|------|
| `CMAKE_TOOLCHAIN_FILE` | NDK 的交叉编译工具链文件 |
| `ANDROID_ABI=arm64-v8a` | 64 位 ARM 架构（主流 Android 手机） |
| `ANDROID_PLATFORM=26` | 最低 API 级别（Android 8.0） |
| `-march=armv8.2a` | 启用 ARM v8.2-A 指令集优化 |
| `GGML_OPENMP=OFF` | 关闭 OpenMP（NDK 不支持） |
| `GGML_LLAMAFILE=OFF` | 关闭 llamafile（Android 不支持） |
| `BUILD_SHARED_LIBS=ON` | 编译为 .so 动态库 |
| `LLAMA_CURL=OFF` | 关闭 curl 依赖 |

### 2.2 编译

```bash
cmake --build build-android --config Release -j$(nproc)
```

`-j$(nproc)` 使用所有 CPU 核心并行编译。耗时约 5-15 分钟。

### 2.3 编译产物

```bash
cmake --install build-android --prefix output --config Release
```

产物位于 `output/lib/`：

| 文件 | 大小 | 说明 |
|------|------|------|
| `libllama.so` | ~35MB | **核心推理引擎** — 模型加载、分词、推理 |
| `libggml.so` | ~611KB | GGML 张量计算库（公共 API） |
| `libggml-base.so` | ~6.3MB | GGML 基础算子实现 |
| `libggml-cpu.so` | ~4.0MB | CPU 后端（NEON 优化） |

### 2.4 注意事项

- **server/tools 编译错误可忽略**：tools/server 中的 `server-tools.cpp` 使用了 `posix_spawn`，NDK 环境不支持。但这不影响核心 `.so` 库的生成，因为我们是 `BUILD_SHARED_LIBS=ON`，只编译 llama 核心库。
- **新版 API 变更**：llama.cpp master 分支的 API 已更新（2025年），旧函数如 `llama_load_model_from_file`、`llama_n_vocab` 已被标记废弃，改用 `llama_model_load_from_file`、`llama_vocab_n_tokens` 等。编写 JNI 桥接层时需使用最新 API。

---

## 三、项目集成

### 3.1 目录结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt          # Gradle CMake 构建脚本
│   ├── llama_bridge.cpp        # JNI 桥接层实现
│   └── include/                # llama.cpp 头文件
│       ├── llama.h
│       ├── ggml.h
│       ├── ggml-cpu.h
│       ├── ggml-alloc.h
│       ├── ggml-backend.h
│       └── gguf.h
│
├── jniLibs/
│   └── arm64-v8a/              # 预编译 .so 库
│       ├── libllama.so         (~35MB)
│       ├── libggml.so          (~611KB)
│       ├── libggml-base.so     (~6.3MB)
│       └── libggml-cpu.so      (~4.0MB)
│
└── java/com/speakin/app/domain/llm/
    └── LocalLlmEngine.kt       # Kotlin JNI 封装
```

### 3.2 CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.22)
project("speakin_llama")

set(LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 导入预编译的 .so 库
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libggml.so)

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libggml-base.so)

add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libggml-cpu.so)

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libllama.so)

# 编译 JNI 桥接层，链接上述 .so
add_library(speakin_llama SHARED llama_bridge.cpp)

target_include_directories(speakin_llama PRIVATE ${CMAKE_SOURCE_DIR}/include)
target_link_libraries(speakin_llama llama ggml ggml-base ggml-cpu log)
```

原理：`speakin_llama` 是编译出来的 `libspeakin_llama.so`，运行时动态链接 `libllama.so`、`libggml*.so`。

### 3.3 JNI 桥接层（llama_bridge.cpp）

核心功能：

| JNI 函数 | 用途 |
|----------|------|
| `nativeInit(modelPath)` | 初始化 `llama_backend`、加载 GGUF 模型、创建推理上下文 |
| `nativeComplete(prompt)` | 分词 → 贪婪解码 → 生成文本 → 返回结果 |
| `nativeRelease()` | 释放模型和上下文 |

关键实现点：

```cpp
// 使用新版 API（llama.cpp master 2025）
g_model = llama_model_load_from_file(path, params);     // 加载模型
g_vocab = llama_model_get_vocab(g_model);               // 获取词表
g_ctx = llama_init_from_model(g_model, ctx_params);     // 创建上下文

// 推理循环
llama_tokenize(g_vocab, prompt, ...);
llama_decode(g_ctx, batch);
llama_vocab_is_eog(g_vocab, id);                        // 判断是否结束
llama_token_to_piece(g_vocab, id, buf, ...);            // token → 文本
```

### 3.4 Kotlin 封装（LocalLlmEngine.kt）

```kotlin
class LocalLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        System.loadLibrary("speakin_llama")
    }

    fun loadModel(modelFile: File): Boolean {
        _isLoaded = nativeInit(modelFile.absolutePath)
        return _isLoaded
    }

    fun complete(prompt: String): String {
        return nativeComplete(prompt) ?: ""
    }

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeComplete(prompt: String): String?
    private external fun nativeRelease()
}
```

### 3.5 Gradle 配置

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")  // 只编译 arm64
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"
}
```

### 3.6 构建流程

```
Gradle 构建时：
  1. CMake 读取 CMakeLists.txt
  2. 用 NDK 交叉编译器编译 llama_bridge.cpp → libspeakin_llama.so
  3. libspeakin_llama.so 在运行时通过 dlopen 链接 libllama.so / libggml*.so
  4. jniLibs/ 中的 libllama.so / libggml*.so 被直接打包进 APK

APK 安装后：
  System.loadLibrary("speakin_llama")
    → 自动加载 libspeakin_llama.so
    → 自动解决依赖：libllama.so → libggml.so → libggml-base.so → libggml-cpu.so
```

---

## 四、模型文件

### 下载

```bash
# Qwen3-0.6B Q4_K_M 量化版 (~400MB)
wget https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
```

- 模型放入 `app/src/main/assets/`（APK 内置）或首次启动从网络下载
- 运行时通过 `LocalLlmEngine.loadModel(file)` 加载

### 调用示例

```kotlin
// 润色文字
val prompt = "请润色以下文字：%s".format(text)
val result = llmEngine.complete(prompt)
```

---

## 五、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `llama.h: fatal error: 'ggml-cpu.h' file not found` | 缺少 GGML 头文件 | 从 llama.cpp 源码 `/ggml/include/` 复制所有 `ggml-*.h` 和 `gguf.h` |
| `libllama.so: needed by libspeakin_llama.so, missing` | 缺少 armeabi-v7a 的 .so | 在 `build.gradle.kts` 中设置 `abiFilters += listOf("arm64-v8a")` |
| `posix_spawn` 相关编译错误 | tools/server 不兼容 NDK | 忽略，不影响核心 .so |
| WSL 下 NDK clang 找不到 | NDK 是 Windows 版 | 安装 Linux 版 NDK |
