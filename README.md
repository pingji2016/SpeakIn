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
│   └── export_whisper_pte.py     # Whisper 模型导出脚本
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

项目提供了便捷的 Gradle Task 来下载所需模型：

```bash
# Windows
.\gradlew downloadAllModels      # 下载全部模型 (Whisper ASR + Qwen3 LLM)

# 或单独下载
.\gradlew downloadWhisperModel   # 下载 Whisper ASR 模型 (~235 MB)
.\gradlew downloadLlmModel       # 下载 Qwen3 润色模型 (~400 MB)
```

下载的模型文件位于：
- `whisper_models/whisper_tiny_xnnpack_fp32.pte` — Whisper ASR 模型
- `whisper_models/tokenizer.json` — Whisper 分词器
- `llm_models/qwen3-0.6b-q4_k_m.gguf` — Qwen3 润色模型

### 构建与运行

1. 用 Android Studio 打开项目
2. 等待 Gradle Sync 完成
3. 连接 Android 设备或启动模拟器 (API 26+)
4. 运行 `app` 模块

> **注意：** 由于项目使用了 NDK 和 C++ 代码，首次构建可能需要较长时间。
>
> 运行 `.\gradlew downloadAllModels` 后，模型文件会自动复制到 `app/src/main/assets/models/` 并随 APK 一起打包。首次启动时，App 会自动将模型从 APK 解压到内部存储，无需手动 adb push。

## 📱 功能展示

### 当前已实现

- ✅ 语音录制与回放 (MediaRecorder + ExoPlayer)
- ✅ 离线语音转文字 (ExecuTorch + Whisper tiny)
- ✅ 本地 AI 润色 (llama.cpp + Qwen3-0.6B)
- ✅ 笔记列表与详情 (Room + Compose)
- ✅ 分段录音管理
- ✅ 模型下载与管理
- ✅ 设置页面（在线 API 配置、能力开关）
- ✅ Deep Links 支持 (`speakin://`)
- ✅ App Shortcuts (桌面快捷方式)
- ✅ Share Sheet 接收分享文字

### 路线图

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
