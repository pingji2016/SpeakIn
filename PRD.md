# SpeakIn — 产品需求文档 (PRD)

## 1. 产品概述

### 1.1 产品定位
SpeakIn 是一款**离线优先的语音笔记助手**。用户通过语音录制笔记，借助本地 AI 模型自动转写为文字，并支持多段语音结构化管理。核心价值在于**完全离线可用**，同时可选配在线模型增强能力。

### 1.2 目标用户
- 经常需要快速记录想法、会议纪要、课堂笔记的文字工作者
- 对数据隐私敏感，希望语音数据不出设备的用户
- 需要语音转文字辅助输入的人群

### 1.3 核心原则
| 原则 | 说明 |
|------|------|
| **离线优先** | 核心功能（录音、转写、播放）不依赖网络 |
| **本地隐私** | 所有录音数据和转写结果默认存储在本地 |
| **模块解耦** | 模型层、数据层、UI 层分离，在线能力作为可插拔模块 |
| **渐进增强** | 先做离线核心链路，再叠加在线能力 |

---

## 2. 核心概念

### 2.1 Note（笔记）
- 一个 Note 是整个对话/录音的单元
- 每个 Note 包含元数据（标题、创建时间、更新时间）
- Note 持久化到本地数据库（Room）

### 2.2 Segment（段落）
- 一个 Note 包含多个 Segment
- 每个 Segment 是一段独立的录音 + 对应的文字内容
- 类似文章的段落结构，语音分段展示

### 2.3 模型角色
| 模型 | 用途 | 运行方式 |
|------|------|---------|
| **ASR 模型** | 语音转文字（离线） | 本地运行 |
| **润色模型** | 转写文字润色/校正（离线） | 本地运行（轻量级） |
| **在线 LLM** | 深度润色、摘要、改写等 | 在线调用（可选配置） |
| **在线 TTS** | 文字转语音、声音克隆等 | 在线调用（可选配置） |

---

## 3. 功能需求

### 3.1 录音与播放

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-01 | 录音 | P0 | 支持录制音频，以 Segment 为单位，录制完成后自动停止 |
| F-02 | 暂停/恢复录音 | P1 | 录音过程中可暂停和恢复，生成独立 Segment |
| F-03 | 分段录音 | P0 | 一次 Note 录制过程中可多次分段，每段生成独立 Segment |
| F-04 | 语音回放 | P0 | 每个 Segment 支持点击播放/暂停录音 |
| F-05 | 波形可视化 | P2 | 录音/播放时显示简单波形 |
| F-06 | 录音删除 | P1 | 支持删除单个 Segment 或整个 Note |

### 3.2 语音转文字（ASR）

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-07 | 离线转写 | P0 | 录音完成后自动调用本地 ASR 模型转写为文字 |
| F-08 | 转写进度展示 | P1 | 转写过程中展示 loading 状态 |
| F-09 | 转写结果编辑 | P1 | 用户可手动编辑/修正转写结果 |
| F-10 | 语言自动检测 | P2 | 自动检测语音语言（中/英） |

### 3.3 文字润色

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-11 | 本地润色 | P0 | 转写完成后自动用本地轻量模型进行基础润色（标点修复、口语词过滤等） |
| F-12 | 在线深度润色 | P1 | 配置在线模型后，提供"深度润色"按钮，调用 LLM 优化文字表达 |
| F-13 | 润色对比 | P2 | 显示润色前后对比，支持接受/拒绝润色结果 |

### 3.4 笔记管理

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-14 | 笔记列表 | P0 | 以时间倒序展示所有 Note，显示标题、时间、Segment 数 |
| F-15 | 笔记详情 | P0 | 展示 Note 内所有 Segment 的语音+文字 |
| F-16 | 笔记搜索 | P2 | 搜索笔记标题和转写文字内容 |
| F-17 | 笔记导出 | P2 | 导出为纯文本/Markdown/音频文件格式 |
| F-18 | 笔记删除 | P0 | 支持删除 Note |

### 3.5 模型配置

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-19 | 离线模型下载/管理 | P0 | 首次使用时下载 ASR 和润色模型，支持查看模型状态 |
| F-20 | 在线模型配置 | P1 | 支持配置 API Endpoint、API Key、模型名称 |
| F-21 | 在线能力开关 | P1 | 可独立开关"深度润色"、"TTS"等在线能力 |

### 3.6 对外能力暴露

通过 **Google App Actions + Android Shortcuts + Deep Links + Content Provider**，将 SpeakIn 的核心能力对外暴露，让用户可以从 Google Assistant、桌面快捷方式、外部 App 等入口快速使用 SpeakIn。

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-22 | App Shortcuts | P1 | 桌面长按图标显示快捷操作：新建笔记、继续上次录音、最近笔记 |
| F-23 | Deep Links | P1 | 支持 URL Scheme 唤起：`speakin://create`（新建）、`speakin://open/{id}`（打开笔记） |
| F-24 | Google App Actions | P2 | 集成 Google Assistant，通过语音指令（BII）唤起，如"Hey Google, 用 SpeakIn 记笔记" |
| F-25 | Share Sheet 接收 | P1 | 系统分享入口：从浏览器/记事本等 App 分享文字到 SpeakIn，自动创建笔记 |
| F-26 | Content Provider | P2 | 对外暴露笔记数据，支持其他 App 通过 ContentResolver 查询笔记列表和内容 |

> **实现原理**：利用 Android 的 `shortcuts.xml` 定义静态快捷方式和 App Actions 的 Built-in Intents（BII），如 `CREATE_THING`、`GET_THING`、`OPEN_APP_FEATURE`。通过 Deep Links 配置 URL Scheme 实现外部唤起。通过 Content Provider 实现跨应用数据共享。

### 3.7 分析与埋点

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-27 | 核心事件埋点 | P2 | 预留 Firebase Analytics 埋点：录音完成、转写完成、润色使用、导出、Shortcuts 唤起等核心事件 |
| F-28 | 崩溃监控 | P2 | 集成 Firebase Crashlytics，收集应用崩溃日志 |

> **数据隐私说明**：所有埋点仅在用户同意隐私政策后启用，录音内容和转写文字**不会**上传到分析平台，仅收集匿名化的事件名称和使用频率。

### 3.8 变现

| ID | 功能 | 优先级 | 描述 |
|----|------|--------|------|
| F-29 | Google AdMob 广告 | P3 | 集成 AdMob，在笔记列表底部或详情页展示 Banner 广告 |
| F-30 | 激励视频广告 | P3 | 可选的激励视频广告：观看广告解锁在线深度润色次数等 |

---

## 4. 技术架构

### 4.1 整体架构分层

```
┌─────────────────────────────────────────┐
│              UI Layer (Compose)          │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐  │
│  │NoteList  │ │NoteDetail│ │Settings  │  │
│  │  Screen  │ │  Screen  │ │  Screen  │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘  │
│       │            │             │        │
│  ┌────┴────────────┴─────────────┴────┐  │
│  │        ViewModel Layer             │  │
│  └────┬────────────┬─────────────┬────┘  │
├───────┼────────────┼─────────────┼───────┤
│  ┌────┴────────────┴─────────────┴────┐  │
│  │         Domain Layer               │  │
│  │  (Use Cases / Repository Impl)     │  │
│  └────┬────────────┬─────────────┬────┘  │
├───────┼────────────┼─────────────┼───────┤
│  ┌────┴────────────┴─────────────┴────┐  │
│  │         Data Layer                 │  │
│  │  ┌────────┐ ┌────────┐ ┌────────┐  │  │
│  │  │ RoomDB │ │ FileIO │ │ Model  │  │  │
│  │  │ (Meta) │ │(Audio) │ │Infra   │  │  │
│  │  └────────┘ └────────┘ └────────┘  │  │
│  └─────────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

### 4.2 关键依赖预选

| 模块 | 技术选型 | 说明 |
|------|---------|------|
| UI 框架 | Jetpack Compose + Material3 | 已有基础设施 |
| 本地数据库 | Room | Android 官方持久化方案 |
| 音频录制 | MediaRecorder / AudioRecord | Android 原生 API |
| 音频播放 | MediaPlayer / ExoPlayer | 支持分段播放 |
| 本地 ASR 模型 | whisper.cpp / sherpa-onnx | 离线语音识别 |
| 本地润色模型 | ONNX Runtime / MediaPipe | 轻量级文本模型 |
| 本地模型部署 | ML Kit / 自建 Inference Engine | 端侧推理 |
| 在线 API | OkHttp + Retrofit | 调用 LLM / TTS API |
| DI 框架 | Hilt | 依赖注入 |
| 导航 | Navigation Compose | 页面路由 |
| 协程 | Kotlin Coroutines + Flow | 异步/响应式 |
| **对外暴露** | **shortcuts.xml + Deep Links + Content Provider** | **Android App Actions / Shortcuts 集成** |
| **分析** | **Firebase Analytics + Crashlytics** | **事件埋点 + 崩溃监控** |
| **变现** | **Google AdMob** | **Banner / 激励视频广告** |

### 4.3 数据模型设计（初步）

```kotlin
// Note 笔记
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val segmentCount: Int = 0
)

// Segment 段落
@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Segment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val audioFilePath: String,       // 录音文件路径
    val durationMs: Long,            // 录音时长
    val rawText: String = "",        // ASR 原始转写文字
    val polishedText: String = "",   // 润色后文字
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int               // 段落排序
)
```

---

## 5. 模型选型建议

### 5.1 本地 ASR 模型选项

| 模型 | 大小 | 语言 | 说明 |
|------|------|------|------|
| **whisper-tiny** | ~150MB | 多语言 | 速度快，体积小，适合离线场景 |
| **whisper-base** | ~300MB | 多语言 | 准确率更高，速度可接受 |
| **sherpa-onnx** (SenseVoice) | ~30-100MB | 中/英 | 体积更小，中文效果优秀 |

**推荐**: 初期使用 whisper.cpp 集成 whisper-tiny，后续可提供模型切换能力。

### 5.2 本地 LLM 润色模型选项

| 模型 | 参数量 | GGUF 文件大小 | 支持中文 | RAM 需求 | 说明 |
|------|--------|--------------|---------|---------|------|
| **Braindler** (Ollama) | ~90M | **72MB** (Q2_K) / **88MB** (Q4) | ✅ | 512MB+ | ⭐ 最小可用，专为移动端设计 |
| **SmolLM2-135M** | 135M | **~100MB** (Q4) | ❌ (英文) | 512MB+ | HuggingFace 出品，适合纯英文 |
| **Gemma 3 270M** (Google) | 270M | **125MB** (INT4) / **241MB** (Q8) | ✅ | 256MB+ | ⭐ 轻量最佳，分类/润色/翻译全能 |
| **TinyLLaMA 1.1B** | 1.1B | **~650MB** (Q4) | ✅ | 2GB+ | 经典小模型 |
| **Phi-2** (Microsoft) | 1.3B | **~800MB** (Q4) | ⚠️ 英文为主 | 2GB+ | 推理能力强 |
| **Qwen3-0.6B** (阿里) | 0.6B | **400MB** (Q4_K_M) / **639MB** (Q8) | ✅✅ | **2GB+** | ⭐ 中文最佳，指令遵循好 |
| **Qwen3-1.7B** (阿里) | 1.7B | **~1GB** (Q4) | ✅✅ | 4GB+ | 效果更好，中高端机首选 |

**推荐优先级**：

1. 入门测试：**Gemma 3 270M** (~125MB，INT4) — 文件最小，功能全面，跑通整个链路
2. 中文优先：**Qwen3-0.6B** (~400MB，Q4_K_M) — 中文润色效果最好
3. 极致小体积（配置低手机）：**Braindler** (~72MB) 或 **Gemma 3 270M** (~125MB)

> **量化等级说明**：Q2_K 约压缩到原始 2bit；Q4_K_M 约 4bit（推荐，平衡体积和质量）；Q8_0 约 8bit（质量最高但体积大）。

### 5.3 在线模型

- **LLM 润色**: 兼容 OpenAI API 格式（如 DeepSeek、通义千问、GPT 等）
- **在线 TTS**: 兼容 OpenAI TTS API 或火山引擎 TTS

---

## 6. 迭代计划

### 迭代一（MVP）：离线录音 + 转写核心链路

**目标**: 完成一个可用的闭环——录音 → 转写 → 展示 → 回放

**功能范围**:
- [ ] F-01 录音（基本录制）
- [ ] F-04 语音回放
- [ ] F-07 离线转写（集成 whisper.cpp）
- [ ] F-08 转写进度展示
- [ ] F-11 本地基础润色
- [ ] F-14 笔记列表
- [ ] F-15 笔记详情（展示语音+文字）
- [ ] F-18 笔记删除
- [ ] F-19 离线模型下载/管理
- [ ] 数据库设计 + Note/Segment 持久化
- [ ] F-27 埋点基础设施（预留 Analytics + Crashlytics SDK 集成）

**交付标准**:
1. 用户可创建 Note，录制一段语音
2. 录音完成后自动转写为文字（本地模型）
3. 转写结果展示在语音下方
4. 点击语音可回放
5. 所有数据持久化，重启应用后数据不丢失
6. Firebase SDK 集成到位，核心事件埋点可用

---

### 迭代二：体验完善 + 对外能力暴露 + 在线能力接入

**目标**: 提升产品完整度，对外暴露核心能力，接入在线模型配置

**功能范围**:
- [ ] F-02 暂停/恢复录音
- [ ] F-03 分段录音（一个 Note 多段语音）
- [ ] F-06 删除单个 Segment
- [ ] F-09 转写结果手动编辑
- [ ] F-12 在线深度润色（可配置）
- [ ] F-13 润色前后对比
- [ ] F-20 在线模型配置页面
- [ ] F-21 在线能力开关
- [ ] F-22 App Shortcuts（桌面快捷方式）
- [ ] F-23 Deep Links（URL Scheme 唤起）
- [ ] F-25 Share Sheet 接收分享
- [ ] F-17 笔记导出（文本 + 音频导出）
- [ ] UI/UX 优化（动画、空状态、错误处理）

**交付标准**:
1. 支持一个 Note 内录制多段语音
2. 可配置在线 LLM 并调用深度润色
3. 润色结果可对比、接受/拒绝
4. 桌面长按图标可快捷新建笔记
5. 支持 URL Scheme 唤起和系统分享导入
6. 笔记可导出为文本和音频文件
7. 整体交互流畅，有完善的加载和错误状态

---

### 迭代三（可选）：高阶功能 + 商业化

**目标**: 搜索、App Actions、广告、TTS、波形

**功能范围**:
- [ ] F-05 波形可视化
- [ ] F-10 语言检测
- [ ] F-16 笔记搜索
- [ ] F-24 Google App Actions（Google Assistant 集成）
- [ ] F-26 Content Provider 对外数据共享
- [ ] 在线 TTS 语音合成能力
- [ ] F-29 AdMob Banner 广告
- [ ] F-30 激励视频广告
- [ ] 应用设置页面完善（主题、存储管理）
- [ ] 性能优化、包体积优化

**交付标准**:
1. 完整的笔记管理能力（搜索、导出）
2. Google Assistant 语音唤起可用
3. 其他 App 可通过 Content Provider 读取笔记
4. 波形可视化提升录音体验
5. 在线 TTS 可用
6. AdMob 广告接入，应用达到可发布状态

---

## 7. 非功能需求

| 需求 | 说明 |
|------|------|
| 性能 | 录音转写延迟不超过 30s（30s 语音） |
| 包体积 | 离线模式 APK 不超过 200MB（含模型） |
| 隐私 | 所有数据默认本地存储，不上传；埋点仅收集匿名事件 |
| 耗电 | 录音 + 推理场景优化，不显著增加耗电 |
| 适配 | 支持 Android 8.0+ (API 26+)，适配深色模式 |
| 分析合规 | 首次启动需弹窗获取隐私同意，同意后启用 Firebase |
| 广告合规 | AdMob 遵循 Google 广告政策，不干扰核心录音体验 |
