# HomeMovie / 家庭电影院

[English](#english) · [中文](#中文)

> 一个面向个人媒体收藏的 Android 影视库：扫描本地视频与 STRM、刮削元数据、播放、字幕与 115 网盘工作流。

当前版本：`2.0.4`

## 中文

### 功能概览

- 本地影视库：通过 Android Storage Access Framework 选择目录，扫描视频、`.strm`、NFO、海报和背景图。
- 元数据刮削：支持多个来源、失败恢复、任务日志、持久化任务与可配置并发。
- 统一任务管线：单视频与网盘文件夹入口共用同一导入/刮削队列；默认 2 路并发，最高 4 路。
- 115 网盘：二维码登录、文件夹浏览、排序、STRM 生成、直链缓存与播放。
- 播放器：AndroidX Media3 / ExoPlayer 播放本地文件、STRM 和网盘视频，支持播放进度、已观看记录和 VR 辅助模式。
- 字幕：本地外挂字幕、在线字幕搜索、编码兼容处理、字幕与进度条样式预览及自定义。
- 浏览体验：搜索、收藏、演员/标签/类别筛选、详情页、最近播放、使用时长统计。
- 主题：支持深色与浅色主题，页面、筛选结果和任务日志均随主题切换。

### 快速开始

1. 使用 Android Studio 打开项目根目录并完成 Gradle Sync。
2. 运行 `app` 模块，首次进入后在设置中选择影视库目录。
3. 扫描本地视频，或在网盘页面登录 115 后添加视频/文件夹。
4. 在“刮削任务”中查看、暂停、继续或清理任务；并发数可在设置中调整。

### 构建

```powershell
# 编译 Kotlin
.\gradlew.bat :app:compileDebugKotlin

# 打包 Debug APK
.\gradlew.bat :app:assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/
```

### 技术栈

- Kotlin、Jetpack Compose、Material 3
- Room、Coroutines、Flow、ViewModel
- AndroidX Media3 / ExoPlayer、OkHttp、Coil
- Android Storage Access Framework、115 网盘 STRM 工作流

### 隐私与安全

请勿提交 Cookie、API Key、签名证书、ASR 模型或 APK。仓库的 `.gitignore` 已排除常见敏感文件与构建产物；115 登录信息仅应保存在设备本地。

### 开发说明

本项目由维护者主导开发，并在需求梳理、代码实现、测试与文档工作中获得 **OpenAI Codex 协助**。Codex 是协作开发工具，不替代人工的设计决策、代码审查与发布验证。

---

## English

### Overview

HomeMovie is a personal Android media-library app for local video files and STRM-based cloud playback. It combines library scanning, metadata scraping, playback, subtitles, and a 115 cloud-drive workflow in one app.

### Highlights

- Scan local videos, `.strm` files, NFO metadata, posters, and fanart through Android's Storage Access Framework.
- Scrape metadata from multiple providers with persistent jobs, logs, recovery, and configurable concurrency.
- Use one shared import-and-scrape pipeline for individual cloud videos and folder imports (2 workers by default, up to 4).
- Browse 115 cloud storage, log in by QR code, generate STRM files, cache direct links, and play cloud media.
- Play local and cloud media with AndroidX Media3 / ExoPlayer, watch-history tracking, progress restore, and VR helper modes.
- Search and load external subtitles, handle common subtitle encodings, and customize subtitle and progress-bar appearance with previews.
- Browse by search, favorites, actors, tags, genres, recent playback, and usage statistics.
- Switch between dark and light themes across library, settings, filter results, and scrape logs.

### Getting started

1. Open this repository in Android Studio and finish Gradle Sync.
2. Run the `app` module and choose a library directory in Settings.
3. Scan local media, or sign in to 115 from the Cloud page and add videos or folders.
4. Use the unified **Scrape Tasks** page to inspect, pause, resume, or clear jobs. Configure concurrency in Settings.

### Build

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Debug APKs are generated under `app/build/outputs/apk/debug/`.

### Tech stack

Kotlin · Jetpack Compose · Material 3 · Room · Coroutines / Flow · ViewModel · AndroidX Media3 / ExoPlayer · OkHttp · Coil

### Security

Do not commit cookies, API keys, signing keys, ASR models, or APKs. The provided `.gitignore` excludes common sensitive files and build artifacts. Keep cloud-login data on the local device only.

### Development note

This project is maintainer-led and was developed with assistance from **OpenAI Codex** for requirements exploration, implementation, testing, and documentation. Codex is a collaborative development tool; human design decisions, review, and release validation remain essential.
