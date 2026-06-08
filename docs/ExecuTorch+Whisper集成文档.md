# SpeakIn ExecuTorch + Whisper 集成文档

## 概述

SpeakIn 使用 **ExecuTorch**（PyTorch 的移动端推理框架）运行 **OpenAI Whisper** 模型，实现**离线语音转文字（ASR）**功能。

### 核心架构

```
┌──────────────────────────────────────────────────────┐
│                    PC端（导出阶段）                     │
│                                                      │
│  PyTorch Whisper模型 ──→ torch.export ──→ .pte文件     │
│  (torchaudio)          (ExecuTorch)                   │
│                                                      │
│  导出产物: whisper_encoder.pte + whisper_decoder.pte   │
│           whisper_config.json + tokenizer.json        │
└──────────────────────────────────────────────────────┘
                          │
                          ▼ ADB push
┌──────────────────────────────────────────────────────┐
│                   Android端（推理阶段）                  │
│                                                      │
│  PCM音频 ──→ MelSpectrogram(Kotlin) ──→ Encoder(.pte) │
│                                              │        │
│                                              ▼        │
│                                   Decoder(.pte) ←─────│
│                                   (自回归 argmax)      │
│                                              │        │
│                                              ▼        │
│                                      Tokenizer         │
│                                      (BPE解码)         │
│                                              │        │
│                                              ▼        │
│                                          文本结果       │
└──────────────────────────────────────────────────────┘
```

---

## 1. 为什么选择 ExecuTorch + Whisper

| 对比项 | ExecuTorch + Whisper | whisper.cpp | 在线 ASR API |
|--------|---------------------|-------------|-------------|
| **离线运行** | ✅ | ✅ | ❌ |
| **编译复杂度** | 低（无 native 编译） | 高（需 NDK 交叉编译） | - |
| **APK 体积影响** | 仅 AAR 依赖（~2MB） | .so 文件（~35MB） | 无 |
| **模型部署** | ADB 推送 .pte 文件 | ADB 推送 GGUF 文件 | 无 |
| **推理速度** | 中等（XNNPACK 加速） | 快（C++ 优化） | 取决于网络 |
| **控制力** | 中等 | 高 | 低 |
| **适用场景** | 快速集成，不想碰 C++ | 极致性能优化 | 永远有网 |

SpeakIn 选择 ExecuTorch 的核心原因：**零 native 编译，纯 Kotlin 配套代码，快速集成**。

---

## 2. PC 端：模型导出

### 2.1 环境准备

```bash
# 推荐虚拟环境
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# 安装依赖
pip install torch torchaudio executorch
```

### 2.2 导出脚本

[scripts/export_whisper_pte.py](file:///d:/github/SpeakIn/scripts/export_whisper_pte.py)

核心流程：

```python
# 1. 加载 whisper（torchaudio 内置）
whisper = torchaudio.models.whisper_builder("small")

# 2. 封装 encoder 和 decoder 为独立 Module
encoder_wrapper = WhisperEncoderWrapper(whisper.encoder)
decoder_wrapper = WhisperDecoderWrapper(whisper.decoder)

# 3. torch.export → to_edge → to_executorch → .pte
exported = torch.export.export(encoder_wrapper, (mel_example,))
edge = exir.to_edge(exported)
edge.to_executorch().write_to_file("whisper_encoder.pte")
```

### 2.3 运行导出

```bash
cd scripts/

# 导出 whisper-small（默认）
python export_whisper_pte.py

# 指定输出目录和模型大小
python export_whisper_pte.py --model small --output-dir ./exported_whisper

# 同时导出 tokenizer（需要 transformers）
python export_whisper_pte.py --model small --export-tokenizer
```

### 2.4 导出产物说明

| 文件 | 用途 | Android 端加载 |
|------|------|---------------|
| `whisper_encoder.pte` | 音频 encoder（mel → hidden states） | `Module.load(encoderPath)` |
| `whisper_decoder.pte` | 文本 decoder（tokens + hidden → logits） | `Module.load(decoderPath)` |
| `whisper_config.json` | 模型参数（mel 维度、采样率、token ID 等） | `WhisperConfig.fromFile()` |
| `tokenizer.json` | BPE tokenizer 词表 | `WhisperTokenizer(tokenizerFile)` |

---

## 3. Android 端：代码架构

### 3.1 文件清单

| 文件 | 职责 | 代码行数 |
|------|------|---------|
| [ExecuTorchWhisperEngine.kt](file:///d:/github/SpeakIn/app/src/main/java/com/speakin/app/domain/asr/ExecuTorchWhisperEngine.kt) | 核心引擎 — 加载 .pte + 推理全流程 | ~253 |
| [MelSpectrogram.kt](file:///d:/github/SpeakIn/app/src/main/java/com/speakin/app/domain/asr/MelSpectrogram.kt) | 纯 Kotlin mel 频谱计算（含 FFT） | ~220 |
| [WhisperTokenizer.kt](file:///d:/github/SpeakIn/app/src/main/java/com/speakin/app/domain/asr/WhisperTokenizer.kt) | BPE token 解码器 | ~100 |
| [AsrEngineImpl.kt](file:///d:/github/SpeakIn/app/src/main/java/com/speakin/app/domain/asr/AsrEngineImpl.kt) | ASR 接口实现 — 封装 WAV 读取 + 调用引擎 | ~205 |
| [AsrModelManager.kt](file:///d:/github/SpeakIn/app/src/main/java/com/speakin/app/domain/model/AsrModelManager.kt) | 模型文件管理 | ~110 |

### 3.2 推理流水线（ExecuTorchWhisperEngine）

```
                                         ┌─────────────────┐
                                         │   PCM FloatArray │
                                         │   (16kHz mono)   │
                                         └────────┬────────┘
                                                  ▼
                                         ┌─────────────────┐
                                         │  MelSpectrogram  │
                                         │   compute()      │
                                         │   normalize()    │
                                         │   flatten()      │
                                         └────────┬────────┘
                                                  ▼
                                         ┌─────────────────┐
                                         │  encoderModule   │
                                         │  forward(mel)    │
                                         │  → Tensor        │
                                         │  (encoder output)│
                                         └────────┬────────┘
                                                  ▼
                                         ┌─────────────────┐
                                         │  decoderModule   │
                                         │  自回归逐 token  │
                                         │  argmax 采样     │
                                         └────────┬────────┘
                                                  ▼
                                         ┌─────────────────┐
                                         │  tokenizer       │
                                         │  decode(IDs)     │
                                         │  → String        │
                                         └────────┬────────┘
                                                  ▼
                                         ┌─────────────────┐
                                         │   文本结果        │
                                         └─────────────────┘
```

### 3.3 关键代码片段

**加载模型**：
```kotlin
val encoderModule = Module.load(encoderFile.absolutePath)
val decoderModule = Module.load(decoderFile.absolutePath)
```

**Encoder 推理**：
```kotlin
val melTensor = Tensor.fromBlob(melFlat, longArrayOf(1, 80, nFrames))
val results = encoderModule.forward(EValue.from(melTensor))
val encoderOutput = results[0].toTensor()
```

**Decoder 自回归**：
```kotlin
val tokenArray = tokens.toIntArray()
val tokenTensor = Tensor.fromBlob(tokenArray, longArrayOf(1, tokenArray.size))
val results = decoder.forward(
    EValue.from(tokenTensor),
    EValue.from(encoderOutputTensor)
)
val logits = results[0].toTensor().dataAsFloatArray
val nextToken = argmax(logits)  // 或 temperature 采样
```

### 3.4 Mel 频谱计算（纯 Kotlin）

无需任何 native 依赖，在 Kotlin 侧完成：

- **分帧加窗**：Hanning 窗，帧长 400，步长 160
- **FFT**：纯 Kotlin radix-2 Cooley-Tukey 实现
- **Mel 滤波器组**：80 个三角滤波器，覆盖 0-8000Hz
- **Log 变换**：`log10(power)` 加小量避免零值
- **归一化**：全局均值归零、方差归一

---

## 4. 依赖配置

### 4.1 Gradle 依赖

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("org.pytorch:executorch-android:1.0.0")
}
```

### 4.2 Maven 仓库

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

ExecuTorch v1.0.0 已发布到 Maven Central，无需额外仓库。

### 4.3 AndroidManifest 权限

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

录音权限在录音时动态申请即可。

---

## 5. 模型部署

### 5.1 PC 导出 + ADB 推送（推荐）

```bash
# 1. PC 导出
cd scripts/
python export_whisper_pte.py --model small --output-dir ./exported_whisper

# 2. ADB 推送
adb shell mkdir -p /data/data/com.speakin.app/files/whisper/
adb push ./exported_whisper/ /data/data/com.speakin.app/files/whisper/
```

### 5.2 验证部署

```bash
adb shell ls -lh /data/data/com.speakin.app/files/whisper/
```

应看到 4 个文件：
```
whisper_encoder.pte    (~90 MB)
whisper_decoder.pte    (~140 MB)
whisper_config.json    (~1 KB)
tokenizer.json         (~5 MB)
```

### 5.3 检查加载日志

```bash
adb logcat -s ExecuTorchWhisper,AsrEngineImpl,AsrModelManager
```

正常输出：
```
ExecuTorchWhisper: Encoder loaded: .../whisper_encoder.pte
ExecuTorchWhisper: Decoder loaded: .../whisper_decoder.pte
ExecuTorchWhisper: Mel spectrogram: 80 bands x 94 frames
AsrEngineImpl: Whisper engine loaded successfully
```

---

## 6. 音频格式说明

### 6.1 录音格式

从 v1.0.0 起，[AudioRecorder](file:///d:/github/SpeakIn/app/src/main/java/com/speakin/app/domain/audio/AudioRecorder.kt) 使用 **AudioRecord** 录制：

| 参数 | 值 |
|------|-----|
| 采样率 | 16 kHz |
| 位深度 | 16-bit |
| 声道 | 单声道 |
| 格式 | WAV |
| 文件大小 | ~32 KB/秒 |

### 6.2 WAV 解析

[AsrEngineImpl](file:///d:/github/SpeakIn/app/src/main/java/com/speakin/app/domain/asr/AsrEngineImpl.kt) 中的 `readAudioFile()` 方法：

1. 解析 WAV header（44 字节）：声道数、采样率、位深度
2. 读取 PCM data 为 `ShortArray`
3. 归一化为 `FloatArray`（[-1.0, 1.0]）
4. 立体声→单声道（取平均）
5. 重采样到 16kHz（线性插值）

### 6.3 与旧版本的兼容性

旧版本使用 `MediaRecorder` 录制 AAC/MP4，新版本不兼容。升级后旧录音文件无法被 ASR 引擎处理（降级为文件名模拟转写）。

---

## 7. ExecuTorch Android API 参考

### 7.1 Module

```kotlin
// 加载 .pte 模型文件
val module = Module.load("/path/to/model.pte")

// 执行推理（forward 方法）
val results: Array<EValue> = module.forward(
    EValue.from(inputTensor),
    EValue.from(extraInput)
)

// 获取模型元数据
val metadata = module.getMethodMetadata("forward")
val backends = metadata.backends  // ["XnnpackBackend"]

// 释放资源
module.destroy()
```

### 7.2 Tensor

```kotlin
// 从 float 数组创建
val tensor = Tensor.fromBlob(
    floatArrayOf(1f, 2f, 3f, ...),  // 数据
    longArrayOf(1, 80, 1500)         // shape
)

// 读取结果
val data: FloatArray = tensor.dataAsFloatArray
```

### 7.3 EValue

```kotlin
// 从 Tensor 创建
val ev = EValue.from(tensor)

// 从标量创建
val evDouble = EValue.from(3.14)
val evInt = EValue.from(42)
val evString = EValue.from("hello")

// 转回 Tensor
val tensor = ev.toTensor()
```

---

## 8. 模型大小与性能

### 8.1 Whisper 模型对比

| 模型 | 参数量 | .pte 总大小 | 适用场景 | 推荐 RAM |
|------|--------|------------|---------|---------|
| whisper-tiny | 39M | ~75 MB | 极简场景 | 2GB+ |
| whisper-base | 74M | ~140 MB | 基础转写 | 3GB+ |
| **whisper-small** | **244M** | **~235 MB** | **默认推荐** | **4GB+** |
| whisper-medium | 769M | ~700 MB | 高精度 | 6GB+ |

### 8.2 推理时间估算（whisper-small, 10秒音频）

| 设备 | 推理时间（约） |
|------|--------------|
| 高通骁龙 8 Gen 3 | 2-4 秒 |
| 骁龙 8 Gen 1 / 天玑 9000 | 3-6 秒 |
| 骁龙 778G / 天玑 1200 | 5-10 秒 |
| 中低端机 | 10-20 秒 |

> 推理时间主要取决于编解码器，XNNPACK 后端会自动利用 NEON/SIMD 加速。

---

## 9. 已知限制与改进方向

### 9.1 当前限制

| 限制 | 说明 |
|------|------|
| **纯 CPU 推理** | ExecuTorch XNNPACK 仅使用 CPU，不支持 GPU/NPU |
| **Greedy 解码** | 当前使用 argmax，不支持 beam search |
| **中文专有优化** | 未针对中文进行语言模型融合或热词增强 |
| **最大 30 秒** | whisper 原生支持最长 30 秒音频 |

### 9.2 改进方向

1. **Vulkan 后端**：使用 ExecuTorch Vulkan 后端利用 GPU 加速
2. **Beam Search**：替换简单的 argmax 解码
3. **VAD 分段**：接入 Voice Activity Detection 处理长音频
4. **语言模型融合**：结合本地 LLM 做 CTC 纠错
5. **量化优化**：用更小的 `tiny` 或 `base` 模型提升速度

---

## 10. 常见问题

### Q: 为什么选择 Encoder/Decoder 分两个 .pte 文件，而不是合并？

Whisper 的 decoder 在自回归推理时需要**逐 token 调用**，合并成一个 .pte 无法在中间步骤修改 token 输入。分两个文件让 Android 端代码在每次 decoder 调用后检查是否需要停止生成。

### Q: 如何更换 whisper 模型？

```bash
# 导出其他大小
python export_whisper_pte.py --model tiny --output-dir ./whisper_tiny
python export_whisper_pte.py --model base --output-dir ./whisper_base
python export_whisper_pte.py --model medium --output-dir ./whisper_medium
```

无需修改 Kotlin 代码，`whisper_config.json` 中的参数会自动适配。

### Q: 推理速度太慢怎么办？

1. 换更小的模型：`tiny`（75MB）或 `base`（140MB）
2. 减少输入长度：当前音频越长，mel 帧越多，encoder 越慢
3. 使用 Vulkan 后端（实验性）：替换 ExecuTorch AAR 为 Vulkan 版本

### Q: 什么情况下会降级为文件名模拟转写？

当 `/data/data/com.speakin.app/files/whisper/` 目录不存在或缺少 `.pte` 文件时，`AsrEngineImpl` 会自动降级。日志中会出现：

```
AsrEngineImpl: Model not loaded, trying to load...
AsrEngineImpl: Falling back to filename transcription
```

### Q: ExecuTorch 的 IValue/EValue 命名差异？

ExecuTorch v1.0.0 将 `IValue` 重命名为 `EValue`（E 代表 ExecuTorch）。如果使用更早版本（v0.x），需使用 `IValue` 类。
