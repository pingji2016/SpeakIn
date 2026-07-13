# Google Play Store Listing Assets

此目录用于管理 Google Play 商店上架所需的素材文件。

> ⚠️ 这些文件**不是**放在 Android 项目里的，而是在 **Google Play Console → 商店设置 → 商品详情** 页面上传的。放这里只是为了 Git 版本管理。

---

## 📱 截图 (Screenshots)

**要求**：
- 最少 2 张，建议 6-8 张
- JPEG 或 24 位 PNG（无 Alpha 通道）
- **手机截图**: 最小 320px，最大 3840px，推荐 ≥1080px 宽
- 截图的长边不能是短边的 2 倍以上（横竖屏分开传）

**建议拍摄内容**：
1. 笔记列表页（展示空状态或有笔记的主页）
2. 录音中页面（展示录音按钮/状态）
3. 转写完成页面（展示语音+转写文字）
4. 笔记详情页（多 Segment 展示）
5. 模型下载/设置页面
6. 深色模式效果（加分项）

```
screenshots/phone/
  ├── 01-note-list.png
  ├── 02-recording.png
  ├── 03-transcription.png
  ├── 04-note-detail.png
  ├── 05-settings.png
  └── 06-dark-mode.png
```

---

## 🎨 Feature Graphic (商店横幅)

**要求**：
- 1024 × 500 px
- JPEG 或 24 位 PNG（无 Alpha 通道）

**放置位置**: `feature-graphic.png`

> 这是 Google Play 商品详情页顶部的横幅图，最重要的一张图。

---

## 🖼️ 应用图标

**要求**：
- 512 × 512 px
- 32 位 PNG（带 Alpha 通道）
- 不超过 1MB

**放置位置**: `app-icon-512.png`

> 注意：Android 项目里的 `mipmap/ic_launcher` 是自适应图标（foreground + background 分层），Play Store 的这个 512px 图标是独立上传的旧版图标，两者可以不同。

---

## 📐 尺寸速查表

| 素材 | 尺寸 | 格式 | 必填 |
|------|------|------|------|
| 手机截图 | min 320px, max 3840px | JPEG / PNG 24bit | ✅ 最少 2 张 |
| Feature Graphic | 1024 × 500 px | JPEG / PNG 24bit | ✅ |
| 应用图标 | 512 × 512 px | PNG 32bit (带 Alpha) | ✅ |
| 视频封面 | 1280 × 720 px | JPEG / PNG 24bit | ❌ |
| 7 寸平板截图 | min 320px, max 3840px | JPEG / PNG 24bit | ❌ |
| 10 寸平板截图 | min 320px, max 3840px | JPEG / PNG 24bit | ❌ |

---

## 🔗 上传入口

Google Play Console → 选择应用 → **商店设置** (Store settings) → **商品详情** (Store listing) → **图片** (Graphics)
