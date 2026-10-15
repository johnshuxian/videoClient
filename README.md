# 影窗

Windows / macOS 桌面点播客户端。首页读取豆瓣影视推荐。初次安装不内置点播源，需要在「播放设置」中粘贴 VOD 订阅地址并导入，例如[这份 vod.json 源清单](https://raw.githubusercontent.com/johnshuxian/IPTVM3U/refs/heads/master/vod.json)。

## 功能

- 电影和剧集推荐、分类筛选、跨源搜索、线路和选集切换
- HLS / MP4 播放、播放进度记录、剧集自动连播
- HLS 清单中的显式广告标记和广告地址过滤
- 同一局域网内搜索 DLNA / UPnP 和 AirPlay 视频设备并投屏；AirPlay 首次连接会提示输入电视上的验证码
- 内置图片代理，失效海报显示占位图；浅色毛玻璃和深色模式可切换
- 点播源支持一键更新、全部启用和批量测试。测活展示进度、搜索延迟及结果数，可随时停止；失败源自动取消勾选
- 播放器下方展示影片海报、年份、地区、来源、主演和剧情简介
- 源清单、启用状态和播放偏好保存在本机

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

安装包输出到 `release/`。macOS 发布给其他电脑时，需要按 Apple 的要求签名和公证。首页数据和点播资源都依赖相应的第三方服务；个别线路失效时，可以在搜索结果中换源。接收设备需支持 DLNA 或 AirPlay 视频播放，且能访问电脑的局域网地址。
Windows 安装包使用安装向导，可在安装时选择目录。
AirPlay 投屏同样要求接收设备能访问电脑的局域网代理。投屏发送的是视频流，播放器上绘制的弹幕不会自动随视频一起投出；Apple TV 的实际兼容性仍需在对应 tvOS 设备上验证。
