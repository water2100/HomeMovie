# Local Movie Library

一个 Kotlin + Jetpack Compose + Room + Media3 的本地影片管理 App MVP。

## 已实现

- 通过 Storage Access Framework 选择媒体库根目录
- 递归扫描本地视频文件：`mp4`、`mkv`、`avi`、`mov`、`wmv`、`m4v` 等
- 查找同目录 NFO：`视频文件名.nfo`、`movie.nfo`、`tvshow.nfo`
- 解析 Kodi/Jellyfin/Emby 常见 NFO 字段
- 查找同目录图片：`poster`、`fanart`、`thumb`、`movie-poster`、`movie-fanart`、`视频文件名-poster`、`视频文件名-fanart`
- 使用 Room 保存扫描结果
- Compose 海报墙、影片详情页
- 使用 AndroidX Media3 / ExoPlayer 播放 SAF 本地视频 URI

## 结构

- `data/local`: Room 数据库、DAO、实体、类型转换
- `data/repository`: ViewModel 面向的数据入口
- `scanner`: SAF 遍历、NFO 解析、图片匹配
- `ui/home`: 首页海报墙和扫描入口
- `ui/detail`: 影片详情页
- `ui/player`: Media3 播放页

## 构建

用 Android Studio 打开本目录后同步 Gradle，然后运行 `:app`。当前环境没有可用的 `gradle`/`gradlew.bat`，所以本仓库未在当前机器完成命令行编译验证。
