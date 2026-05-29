# HomeMovie

HomeMovie 是一个基于 Kotlin、Jetpack Compose、Room 和 AndroidX Media3 的本地影片管理 App。它面向本地媒体库、NFO 元数据、海报墙浏览和 115 云盘 STRM 工作流，目标是在 Android 设备上提供一个可离线管理、可播放、可补充刮削信息的私人影片库。

## 主要功能

- 本地媒体库扫描：通过 Android Storage Access Framework 选择媒体库根目录，并递归扫描常见视频文件。
- NFO 元数据解析：支持读取同目录下的 `视频文件名.nfo`、`movie.nfo`、`tvshow.nfo` 等 Kodi、Jellyfin、Emby 常见格式。
- 海报和背景图匹配：自动查找同目录下的 `poster`、`fanart`、`thumb`、`movie-poster`、`movie-fanart`、`视频文件名-poster`、`视频文件名-fanart` 等图片。
- Room 本地数据库：保存影片、播放进度、收藏、观看状态、云盘 STRM 记录、直链缓存等信息。
- Compose UI：提供首页海报墙、搜索、收藏、筛选结果、影片详情、设置、刮削日志和云盘浏览等界面。
- Media3 播放：基于 AndroidX Media3 / ExoPlayer 播放本地 SAF URI、STRM 解析结果和直链视频。
- 115 云盘辅助：包含 115 Cookie 保存、云盘目录浏览、STRM 生成/刮削、直链解析和缓存逻辑。
- 刮削能力：包含官方站点、DMM、DMM2、MissAV 等来源的元数据抓取入口，并记录刮削日志。
- VR 播放辅助：包含 VR 模式、控制模式和球面播放器视图相关代码。
- 字幕/语音相关能力：集成 sherpa-onnx JNI 和识别器封装，用于离线语音识别/字幕相关实验能力。

## 技术栈

- Kotlin
- Jetpack Compose
- AndroidX Lifecycle / ViewModel
- Room
- Kotlin Coroutines / Flow
- AndroidX Media3 / ExoPlayer
- OkHttp
- org.json
- sherpa-onnx / onnxruntime 原生库
- Gradle Kotlin DSL

## 项目结构

```text
.
├── app/
│   ├── src/main/java/com/example/localmovielibrary/
│   │   ├── asr/              # sherpa-onnx 语音识别封装
│   │   ├── cloud115/         # 115 云盘 API、Cookie、加密和文件模型
│   │   ├── data/
│   │   │   ├── local/        # Room 数据库、DAO、实体、类型转换
│   │   │   └── repository/   # ViewModel 面向的数据仓库
│   │   ├── playback/         # 播放请求、STRM/M3U 解析、直链解析、VR 设置
│   │   ├── scanner/          # 媒体库扫描、NFO 解析、元数据模型
│   │   ├── scraper/          # 元数据刮削器和刮削日志
│   │   ├── translate/        # 百度翻译客户端
│   │   ├── ui/               # Compose 页面和 ViewModel
│   │   └── util/             # 番号解析、影片变体等工具
│   ├── src/main/jniLibs/     # sherpa-onnx / onnxruntime 原生库
│   └── build.gradle.kts
├── gradle/                   # Gradle Wrapper
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 隐私和敏感数据

仓库不应包含任何个人 Cookie、密钥、证书、模型文件或本地环境配置。当前 `.gitignore` 已排除以下内容：

- `local.properties`
- `app/src/main/assets/115-cookies.txt`
- `app/src/main/assets/sherpa-onnx-sense-voice-ja/`
- `*.onnx`
- `*.keystore`、`*.jks`、`*.p12`、`*.pem`、`*.key`
- `*.env`
- `*.apk`
- `.gradle/`、`.idea/`、`.kotlin/`、`.codex_tmp/`
- `build/`、`app/build/`

百度翻译的 App ID 和 Secret Key 不再提供硬编码默认值。需要使用翻译功能时，请在 App 设置页中手动填写自己的凭据。

115 Cookie 属于私密数据，只应保存在本机或 App 私有存储中，不应提交到 GitHub。

## 构建方式

1. 使用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步完成。
3. 确认本机已安装 Android SDK，并由 `local.properties` 指向正确的 SDK 路径。
4. 运行 `:app` 安装到设备或模拟器。

也可以在命令行执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

当前项目已在本地通过 `:app:compileDebugKotlin` 编译检查。

## 本地模型和 Cookie 文件

如果需要使用离线语音识别能力，需要自行准备 sherpa-onnx 相关模型文件，并放入本地未跟踪目录：

```text
app/src/main/assets/sherpa-onnx-sense-voice-ja/
```

如果需要使用 115 云盘功能，可以在 App 内设置 Cookie，或在本地调试时自行准备：

```text
app/src/main/assets/115-cookies.txt
```

这些文件已经被 `.gitignore` 排除，不会随代码仓库上传。

## 开发说明

- 新增数据表或实体时，需要同步更新 Room 数据库版本和迁移策略。
- 新增刮削源时，优先实现 `MovieScraper` 接口，并在注册表中接入。
- 涉及网络请求的功能应避免硬编码账号、Cookie、Token、API Key 或个人服务地址。
- 涉及大型二进制文件时，优先放入本地忽略目录，或使用 Git LFS / Release 附件单独分发。
- 提交前建议执行敏感文件检查，确认没有误提交 `cookie`、`secret`、`token`、`*.onnx`、`local.properties` 等内容。

## 状态

这是一个持续开发中的个人媒体库项目，当前以本地管理、播放、刮削和 115 云盘辅助流程为主。部分能力仍偏实验性质，实际使用前建议结合自己的媒体库结构和网络环境进行测试。
