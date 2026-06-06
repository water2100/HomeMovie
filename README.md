# 家庭电影院

家庭电影院是一个基于 Kotlin、Jetpack Compose、Room 和 AndroidX Media3 / ExoPlayer 的 Android 本地影视库 App。项目面向本地影片、NFO 元数据、海报墙、115 网盘 STRM 工作流和离线语音字幕实验能力，目标是在手机上提供一个接近 Emby / Jellyfin 风格的私人影视库体验。

当前版本：`1.0.0`

## 使用说明

### 1. 目录设置
主要用于设置下载的图片和 NFO 信息的位置。手动新建一个影视库专用文件夹即可。  
STRM 位置也设置成这个文件夹，然后保存设置。

### 2. 网盘设置
首先需要扫描二维码登录，选择你想要的登录方式并获取二维码，然后使用 115 App 扫码登录。其他设置不用管。

### 3. 刮削
这里默认即可，后续会具体说明这些刮削方式。  
注意：`DMM` 和 `DMM2` 刮削必须挂日本节点，否则无法刮削。

### 4. 字幕
实时字幕效果不是很好，想用也可以用。需要下载模型，模型下载时建议挂节点，否则会很慢。

### 5. 使用
115 网盘登录以后，可以在网盘界面选择想要添加的视频，会自动使用 `DMM2` 刮削，然后在影片页面就能看到刚刚刮削的影片。  
注意：`DMM2` 适合现在的新片，因为它是从 DMM 中逆向出的接口，有高清图片；老影片可能没有数据。  
也可以在网盘界面点击视频直接播放，支持 VR 视频播放。播放 VR 视频时，点击播放器右上角切换到 `360`，会启用陀螺仪效果，转动手机时画面也会跟着转动。



## 主要功能

- 本地影片库扫描：支持通过 Android Storage Access Framework 选择影片库目录，扫描本地视频和 `.strm` 文件。
- NFO 元数据读取：支持读取影片同目录下的 NFO，解析标题、演员、类型、标签、简介、发行日期、片商等信息。
- 海报与背景图识别：支持 `poster`、`thumb`、`fanart` 等本地图片，并提供图片缓存以改善海报墙滑动体验。
- Emby 风格界面：包含首页、影片页、收藏页、搜索页、网盘页、详情页、筛选结果页、日志页和设置页。
- Media3 播放器：支持本地视频、STRM、115 网盘直链播放，并保留播放进度。
- STRM 解析链路：支持从 STRM 提取 pickcode，缓存 115 真实视频直链，过期后自动重新获取。
- 115 网盘浏览：支持二维码登录、账号 Cookie 管理、文件夹浏览、视频添加入库、直链缓存和网盘播放。
- 刮削能力：支持 DMM、DMM2、Official、MissAV 等来源，默认刮削方式为 DMM2。
- 多版本和分段视频：支持普通版、4K/8K 版本以及分段视频在详情页中作为不同播放源选择。
- 国产 A 目录：支持单独的国产视频数据入口和展示页面。
- VR 播放辅助：支持普通 2D 与多种 VR 模式切换，包括 360、180、SBS、OU 等。
- 实时字幕实验：支持 sherpa-onnx 本地 ASR、百度翻译和 DeepSeek 翻译配置，并可保存生成的字幕文件。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- AndroidX Lifecycle / ViewModel
- Room
- Kotlin Coroutines / Flow
- AndroidX Media3 / ExoPlayer
- OkHttp
- Coil
- sherpa-onnx
- Gradle Kotlin DSL

## 项目结构

```text
app/src/main/java/com/example/localmovielibrary/
├── asr/          # 本地 ASR 与模型管理
├── cloud115/     # 115 网盘登录、Cookie、接口和文件模型
├── data/         # Room、DAO、Entity、Repository
├── playback/     # 播放请求、STRM/M3U/直链解析、VR 设置
├── scanner/      # 影片扫描、NFO 解析、图片识别
├── scraper/      # 元数据刮削器、NFO 写入、日志
├── subtitle/     # 实时字幕保存与读取
├── translate/    # 百度翻译、DeepSeek 翻译和提示词模板
├── ui/           # Compose 页面和 ViewModel
└── util/         # 番号、版本、文本等工具
```

## 构建

使用 Android Studio 打开项目根目录，等待 Gradle Sync 完成后运行 `app` 即可。

命令行编译 Kotlin：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

打包 Debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 本地模型

实时字幕使用的 ASR 模型不随仓库和 APK 分发。请在 App 的设置页面中下载或选择模型。

本地调试时可将模型放在：

```text
app/external-models/
```

该目录已加入 `.gitignore`，不会上传到 GitHub。

## 隐私与敏感数据

以下内容不应提交到仓库：

- 115 Cookie
- API Key / Secret Key
- 本地 SDK 配置
- 签名证书
- ASR 模型文件
- APK 构建产物

`.gitignore` 已排除常见敏感文件和大体积二进制文件，包括：

- `local.properties`
- `app/src/main/assets/115-cookies.txt`
- `app/src/main/assets/sherpa-onnx-sense-voice-ja/`
- `app/external-models/`
- `*.onnx`
- `*.apk`
- `*.keystore` / `*.jks` / `*.pem` / `*.key`

115 网盘账号 Cookie 请通过 App 设置页二维码登录或账号管理功能保存到设备本地。

## 发布

从 `1.0.0` 开始启用正式版本号。

APK 不提交到代码仓库，建议作为 GitHub Release 附件发布。

## 说明

这是一个持续开发中的个人影视库项目。部分功能依赖用户自己的媒体库结构、115 网盘账号、网络环境和刮削来源可访问性。使用前建议先在少量影片上测试扫描、刮削、播放和字幕流程。
