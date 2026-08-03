# SpeakIn 🎙️

> 离线优先的语音笔记助手 — 语音录制 → AI 转写 → 智能润色，全程本地运行，保护你的数据隐私。

<div align="center">

[![Android](https://img.shields.io/badge/Platform-Android-34A853?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen?style=flat)](https://android-arsenal.com/api?level=26)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat)](LICENSE)

</div>

---

## ✨ 特性

| 特性 | 说明 |
|------|------|
| 🎤 **高质量录音** | 以 Segment 为单位的语音录制，支持分段录音、暂停/恢复 |
| 📝 **离线 AI 转写** | 基于 ExecuTorch + Whisper 的本地语音识别，无需联网 |
| ✨ **智能润色** | 本地 LLM（Qwen3）自动修复标点、过滤口语词、优化表达 |
| 🔒 **隐私优先** | 所有录音数据和转写结果默认存储在本地，不出设备 |
| 📱 **现代 UI** | Jetpack Compose + Material3 构建，支持深色模式 |
| 🔗 **外部集成** | App Shortcuts、Deep Links、Share Sheet 接收 |
| 🌐 **可选在线增强** | 支持配置在线 LLM（OpenAI 兼容 API）进行深度润色 |

## 🏗️ 技术架构

```
┌─────────────────────────────────────────┐
│           UI Layer (Jetpack Compose)     │
│   NoteList  │  NoteDetail  │  Settings   │
├─────────────────────────────────────────┤
│          ViewModel Layer                 │
├─────────────────────────────────────────┤
│          Domain Layer                    │
│   (Use Cases / Repository Impl)         │
├─────────────────────────────────────────┤
│          Data Layer                      │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐ │
│  │ Room DB │ │  File I/O│ │ Model    │ │
│  │ (Notes) │ │  (Audio) │ │ Infra    │ │
│  └─────────┘ └──────────┘ └──────────┘ │
└─────────────────────────────────────────┘
```

### 技术栈

| 模块 | 技术选型 |
|------|---------|
| UI 框架 | Jetpack Compose + Material3 |
| 导航 | Navigation Compose |
| 本地数据库 | Room |
| 依赖注入 | Hilt |
| 异步处理 | Kotlin Coroutines + Flow |
| 音频录制 | Android MediaRecorder |
| 音频播放 | ExoPlayer |
| **ASR 模型** | **ExecuTorch + Whisper (tiny)** — 离线语音识别 |
| **润色模型** | **llama.cpp + Qwen3-0.6B (GGUF)** — 本地文本润色 |
| 在线 API | OkHttp + Retrofit（可选配置） |
| 图片加载 | Coil |
| 构建系统 | Gradle KTS + Version Catalogs |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 15 (API 36) |

## 📦 项目结构

```
SpeakIn/
├── app/                          # 主应用模块
│   └── src/main/
│       ├── cpp/                  # C++ 原生代码 (CMake)
│       ├── java/com/speakin/app/
│       │   ├── data/             # 数据层 (Room, Repository)
│       │   ├── di/               # Hilt 依赖注入模块
│       │   ├── domain/
│       │   │   ├── asr/          # ASR 引擎 (ExecuTorch Whisper)
│       │   │   ├── audio/        # 录音/播放 (MediaRecorder, ExoPlayer)
│       │   │   ├── llm/          # 本地 LLM 引擎 (llama.cpp)
│       │   │   ├── polish/       # 文本润色引擎
│       │   │   └── model/        # 模型管理
│       │   └── ui/               # UI 层
│       │       ├── navigation/   # 导航图
│       │       ├── notelist/     # 笔记列表页面
│       │       ├── notedetail/   # 笔记详情页面
│       │       ├── recording/    # 录音控制条
│       │       ├── settings/     # 设置页面
│       │       ├── modeldownload/# 模型下载页面
│       │       └── theme/        # Material3 主题
├── model-service/                # 模型推理服务模块 (AIDL)
│   └── src/main/java/
│       └── com/speakin/modelservice/
│           ├── ExecuTorchWhisperEngine.kt   # Whisper 推理引擎
│           ├── LocalLlmEngine.kt            # 本地 LLM 推理
│           ├── WhisperTokenizer.kt          # Whisper 分词器
│           └── ModelService.kt              # AIDL 模型服务
├── speakin_assets/               # Play Asset Delivery 模块
├── docs/                         # 技术文档
│   ├── ExecuTorch+Whisper集成文档.md
│   ├── llama.cpp集成与交叉编译指南.md
│   └── 模型离线部署指南.md
├── scripts/                      # 辅助脚本
│   ├── export_whisper_cpu.py     # Whisper 模型导出 (pre_enc + decoder)
│   └── export_whisper_pte.py     # Whisper 模型导出 (encoder + decoder)
├── whisper_models/               # Whisper 模型存放目录 (gitignored)
├── PRD.md                        # 产品需求文档
└── build.gradle.kts              # 根构建脚本
```

## 🚀 快速开始

### 环境要求

- Android Studio Ladybug (2024.2) 或更新版本
- JDK 17+
- Android SDK 36
- NDK 27.0.12077973
- CMake 3.22.1+

### 克隆项目

```bash
git clone https://github.com/SpeakIn/SpeakIn.git
cd SpeakIn
```

### 下载 AI 模型

模型通过 HuggingFace 托管，Gradle Task 一键下载：

```bash
# Windows
.\gradlew downloadAllModels      # 下载全部模型 (Whisper ASR + Qwen3 LLM)

# 或单独下载
.\gradlew downloadWhisperModel   # 下载 Whisper ASR 模型 (~231 MB)
.\gradlew downloadLlmModel       # 下载 Qwen3 润色模型 (~400 MB)
```

下载的模型文件位于（gitignored，不提交到仓库）：
- `whisper_models/whisper_pre_enc.pte` — Whisper pre-encoder
- `whisper_models/whisper_decoder.pte` — Whisper decoder
- `whisper_models/tokenizer.json` — Whisper 分词器
- `llm_models/qwen3-0.6b-q4_k_m.gguf` — Qwen3 润色模型

### 构建与运行

1. 用 Android Studio 打开项目
2. 等待 Gradle Sync 完成
3. 运行 `.\gradlew downloadAllModels` 下载模型
4. 连接 Android 设备或启动模拟器 (API 26+)
5. 运行 `app` 模块

Whisper 模型会在构建时自动打包进 APK，App 首次运行时自动从 APK 解压到内部存储，无需手动推送或联网下载。

> **注意：** 由于项目使用了 NDK 和 C++ 代码，首次构建可能需要较长时间。

### 部署 LLM 模型到设备（手动方式）

Whisper ASR 模型已内置在 APK 中，安装即用。以下手动推送方式仅适用于：

- **LLM 润色模型**：体积较大（~400MB），未打包进 APK，需单独推送或通过 App 内下载
- **更新模型**：在已安装的 APK 上单独更新模型文件，无需重新安装

```bash
# 创建模型目录
adb shell mkdir -p /data/data/com.speakin.app/files/whisper/
adb shell mkdir -p /data/data/com.speakin.app/files/models/

# 推送 Whisper 模型文件
adb push whisper_models/whisper_pre_enc.pte /data/data/com.speakin.app/files/whisper/
adb push whisper_models/whisper_decoder.pte /data/data/com.speakin.app/files/whisper/
adb push whisper_models/tokenizer.json /data/data/com.speakin.app/files/whisper/

# 推送 LLM 模型文件
adb push llm_models/qwen3-0.6b-q4_k_m.gguf /data/data/com.speakin.app/files/models/
```

> 💡 LLM 模型也可通过 App 内 **设置页面 → 下载润色模型** 从网络下载，无需 adb 推送。
>
> LLM 模型未部署时，语音转写仍可正常使用，仅文本润色功能会安静跳过（返回原文）。

## 📱 功能展示

### 当前已实现

- ✅ 语音录制与回放 (MediaRecorder + ExoPlayer)
- ✅ 离线语音转文字 (ExecuTorch + Whisper tiny) — 模型内置 APK，开箱即用
- ✅ 本地 AI 润色 (llama.cpp + Qwen3-0.6B) — 需额外下载 LLM 模型
- ✅ 笔记列表与详情 (Room + Compose)
- ✅ 分段录音管理
- ✅ 模型下载与管理
- ✅ 设置页面（在线 API 配置、能力开关）
- ✅ Deep Links 支持 (`speakin://`)
- ✅ App Shortcuts (桌面快捷方式)
- ✅ Share Sheet 接收分享文字

### 路线图

3. 部署脚本

已写入 scripts/deploy.sh，用法如下：

# 一键部署：推送模型 + 构建 + 安装 APK
./scripts/deploy.sh

# 仅构建并安装 APK
./scripts/deploy.sh --apk-only

# 仅推送模型文件
./scripts/deploy.sh --models-only

# 顺便推送测试音频
./scripts/deploy.sh --m4a /path/to/audio.m4a


更多功能规划请参考 [PRD.md](PRD.md)，包括：

- 🔲 波形可视化
- 🔲 笔记搜索
- 🔲 笔记导出 (文本/Markdown/音频)
- 🔲 Google App Actions（Google Assistant 集成）
- 🔲 Content Provider 跨应用数据共享
- 🔲 在线 TTS 语音合成
- 🔲 Firebase Analytics & Crashlytics
- 🔲 Google AdMob 集成

## 📖 相关文档

| 文档 | 说明 |
|------|------|
| [PRD.md](PRD.md) | 产品需求文档 |
| [docs/ExecuTorch+Whisper集成文档.md](docs/ExecuTorch+Whisper集成文档.md) | ExecuTorch Whisper 集成指南 |
| [docs/llama.cpp集成与交叉编译指南.md](docs/llama.cpp集成与交叉编译指南.md) | llama.cpp 编译与集成说明 |
| [docs/模型离线部署指南.md](docs/模型离线部署指南.md) | 离线模型部署教程 |

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 [MIT License](LICENSE)。

---

<div align="center">
  <sub>Built with ❤️ for privacy-first voice note taking</sub>
</div>
