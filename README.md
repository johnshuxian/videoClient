# 影窗

Windows / macOS 桌面与 Android 手机、平板点播客户端。电影、电视剧和综艺推荐来自豆瓣，番剧榜单来自 Bangumi。初次安装不内置点播源，需要在「播放设置」中粘贴 VOD 订阅地址并导入，例如[这份 vod.json 源清单](https://raw.githubusercontent.com/johnshuxian/IPTVM3U/refs/heads/master/vod.json)。

## 功能

- 电影、剧集、番剧和综艺推荐、分类筛选、跨源搜索、线路和选集切换
- 番剧可按 TV、OVA、剧场版、WEB 或科幻、治愈、恋爱等题材筛选；形式榜支持高分和日期排序，题材榜支持高分和热度排序
- 搜索时按点播源逐步展示已找到的结果；每次搜索都实时请求点播源
- HLS / MP4 播放、播放进度记录（支持逐条删除）、剧集自动连播
- 投屏成功后停止本机播放，电视端进度同步到播放记录；AirPlay 和支持播放状态事件的 DLNA 设备可自动续投下一集
- HLS 清单中的显式广告标记和广告地址过滤
- 同一局域网内搜索 DLNA / UPnP 视频设备并投屏；桌面版另支持 AirPlay，首次连接会提示输入电视上的验证码
- 内置图片代理，失效海报显示占位图；浅色毛玻璃和深色模式可切换
- 点播源支持一键更新、全部启用和批量测试。每批测活并发数可设置，默认 8；测活展示进度、搜索延迟及结果数，可随时停止；失败源自动取消勾选
- 播放器下方展示影片海报、年份、地区、来源、主演和剧情简介
- 默认浅色模式；源清单、播放偏好、设备、AirPlay 配对信息和播放记录保存在 `settings.db`（SQLite）

## 开发

需要 Node.js 22 或更新版本。
构建 AirPlay 助手还需要 Python 3.9 或更新版本和 Git。开发模式首次使用 AirPlay 前运行 `npm run build:airplay`。

```bash
npm install
npm run dev
```

## 构建

```bash
npm run build:web
npm run dist:win  # 在 Windows 上生成 NSIS 安装包
npm run dist:mac  # 在 macOS 上生成 DMG
npm test
```

Android 版需要 JDK 21、Android SDK Platform 36 和 Build Tools 35.0.0。在 Windows 上运行：

```bash
npm run dist:android
```

调试签名 APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`，可安装到 Android 手机和平板。安卓端在原生 SQLite 中保存设置和播放记录，支持跟随设备旋转、手机端顶部分类与底部四入口、本机播放和 DLNA 投屏。手机与电视需连接同一局域网，电视需支持并开启 DLNA 视频接收；AirPlay 仍仅支持桌面版。桌面版数据库不会自动同步到安卓端，首次使用需重新导入点播源。

安装包输出到 `release/`。字体文件随安装包提供，不需要启动时连接 Google Fonts。macOS 发布给其他电脑时，需要按 Apple 的要求签名和公证。首页数据和点播资源都依赖相应的第三方服务；个别线路失效时，可以在搜索结果中换源。接收设备需支持对应的投屏协议，且能访问电脑或手机的局域网地址。
Windows 安装包使用安装向导，可在安装时选择目录。
推送到 `master`、提交 Pull Request 或在 GitHub Actions 中手动运行 `Build desktop installers`，会分别生成 Windows x64 安装包和 Apple Silicon（ARM64）macOS DMG，可在运行记录的 Artifacts 中下载。发布新版本时，先更新 `package.json` 的版本号，再推送对应的 `vX.Y.Z` 标签；两个安装包构建成功后会自动上传到 GitHub Releases。CI 产物未签名；向其他 Mac 分发前仍需配置 Apple 签名和公证。

### macOS 未签名版本首次打开

从本项目的 GitHub Releases 下载 DMG，把「影窗.app」拖入「应用程序」后，如果启动时提示应用「已损坏」，可在确认下载来源后打开终端执行：

```bash
sudo xattr -dr com.apple.quarantine "/Applications/影窗.app"
open "/Applications/影窗.app"
```

这只移除该应用的下载隔离标记，不会给应用添加 Apple 签名。重新安装新版本后可能需要再次执行。

Windows 安装版的 `settings.db` 位于实际安装目录（例如 `E:\mysoft\videoClient\settings.db`），该目录需要允许当前用户写入。安装新版本时，安装器会先备份旧数据库，再恢复到新安装目录；开发版和 macOS 仍使用系统用户数据目录。
旧版 `localStorage` 和 `settings.json` 不会迁移到 SQLite，首次使用新版需要重新设置。
AirPlay 投屏同样要求接收设备能访问电脑的局域网代理。投屏发送的是视频流，播放器上绘制的弹幕不会自动随视频一起投出；Apple TV 的实际兼容性仍需在对应 tvOS 设备上验证。
macOS 首次搜索投屏设备时，请允许「影窗」访问本地网络；若之前拒绝，可在「系统设置 → 隐私与安全性 → 本地网络」中重新开启，然后在投屏窗口重新搜索。
