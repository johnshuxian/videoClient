# 影窗

Windows / macOS 桌面、Android 手机和平板，以及独立原生 Android 电视点播客户端。电影、电视剧和综艺推荐来自豆瓣，番剧榜单来自 Bangumi。初次安装不内置点播源，需要在「播放设置」中粘贴 VOD 订阅地址并导入，例如[这份 vod.json 源清单](https://raw.githubusercontent.com/johnshuxian/IPTVM3U/refs/heads/master/vod.json)。

可用下面的命令把 TVBox 多线路订阅中的直连 JSON 点播接口检测后转换成影窗的 `sources` 格式。第二个参数是输出文件，第三个参数可选，用作搜索测试关键词；命令还会生成同名的 `.report.json` 检测明细。

```bash
npm run probe:tvbox -- "https://gh-proxy.com/https://raw.githubusercontent.com/noimank/tvbox/main/tvboxmuti.json" "vod-tvbox-sources.json" "长安"
```

输出只包含搜索、详情和直连播放地址检查通过的 JSON 接口。TVBox 的 JAR / JS 规则站点及 XML 源不会被转换；检测不等于实际播放成功。若要在影窗中通过订阅地址导入，需将生成的 JSON 文件放到可访问的 HTTP(S) 地址。

## 功能

- 电影、剧集、番剧和综艺推荐、分类筛选、跨源搜索、选集切换与播放失败自动换线
- 番剧可按 TV、OVA、剧场版、WEB 或科幻、治愈、恋爱等题材筛选；形式榜支持高分和日期排序，题材榜支持高分和热度排序
- 搜索时按点播源逐步展示已找到的结果；每次搜索都实时请求点播源
- HLS / MP4 播放、播放进度记录（支持逐条删除）、剧集自动连播
- 投屏成功后停止本机播放，电视端进度同步到播放记录；支持播放状态事件的 DLNA 设备可自动续投下一集
- HLS 清单中的显式广告标记、整组广告地址、短片头及两长段之间短中插过滤
- 同一局域网内搜索 DLNA / UPnP 视频设备并投屏
- 内置图片代理，失效海报显示占位图；浅色毛玻璃和深色模式可切换
- 点播源支持一键更新、全部启用和批量测试。每批测活并发数可设置，默认 8；测活展示进度、搜索延迟及结果数，可随时停止；失败源自动取消勾选
- 播放器下方展示影片海报、年份、地区、来源、主演和剧情简介
- 默认浅色模式；源清单、播放偏好、设备和播放记录保存在 `settings.db`（SQLite），普通设置、播放记录和详情缓存分别使用 `settings`、`play_history`、`detail_cache` 表

Windows、macOS、Android 手机和平板及 Android TV 可点击设置页右上角版本号检查更新。“精确搜索”下的“线路选择”默认使用 `gh-proxy`，也可切换为 GitHub 直连；选择保存在本机，仅影响更新检查和安装包下载。发现新版本后显示“取消 / 同意”，同意后下载对应平台安装包、显示进度并校验大小和 SHA-256，校验通过才打开安装程序。Windows 启动安装向导后退出当前应用；macOS 打开 DMG 后需把应用拖入“应用程序”。Android 会校验包名、版本与当前安装版的签名，首次更新需要允许影窗安装未知应用，授权返回后继续打开系统安装界面。鸿蒙端不提供该更新入口。

更新信息与安装包直接使用当前公开仓库的 GitHub Releases，无需新增服务端或额外的版本描述文件。后续继续递增 `package.json` 版本并发布对应标签即可；尚未内置该功能的旧版本需要先手动安装一次。各端详情页和电视选集面板只显示集数，播放线路在失败时由程序自动切换。

## 开发

需要 Node.js 22 或更新版本，推荐使用与 CI 一致的 Node.js 22。首次拉取代码或换电脑后，先安装项目依赖，再运行开发或打包命令。

```bash
npm ci
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

调试签名 APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`，可安装到 Android 手机、平板。安卓端在原生 SQLite 中保存设置和播放记录，支持跟随设备旋转、手机端顶部分类与底部四入口、本机播放和 DLNA 投屏。手机与电视需连接同一局域网，电视需支持并开启 DLNA 视频接收。桌面版数据库不会自动同步到安卓端，首次使用需重新导入点播源。

在 Windows 上双击 `build-install-android.cmd`，可一键构建网页资源、同步 Android 工程、编译 release 签名 APK 并覆盖安装到唯一已连接的设备。脚本默认使用项目根目录下的 `android-sdk/`（已加入 Git 忽略），也支持 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`；连接多台设备时运行 `./build-install-android.cmd -DeviceId '设备编号'`，其他 SDK 位置可加 `-AndroidSdk 'D:\Android\Sdk'`。需要仓库根目录的 `videoclient-release.jks` 和 `signing-password.txt`，或使用 `ANDROID_KEYSTORE_PATH` 与 `ANDROID_KEYSTORE_PASSWORD`。脚本会核对 release 证书指纹，安装包位于 `android/app/build/outputs/apk/release/app-release.apk`；覆盖安装不会清除应用数据。

搜索框会在各端分别保存最近 10 条搜索，可点选重搜、逐条删除或清空；记录保存在各设备本地，不跨设备同步。
搜索提交时会去掉首尾空格、把连续空格合并为一个，并用整理后的完整内容查询点播源。各端设置页的“精确搜索”默认关闭；开启后，如果搜索内容包含空格，只取第一段作为结果筛选关键词，例如“牧神记 年番3”会提交完整内容搜索，但结果仅显示片名包含“牧神记”的影片。关闭后恢复显示源返回的全部结果。

### 原生 Android 电视端

`android-tv/` 是独立的 Kotlin / Compose 电视应用，不依赖手机端 WebView，最低支持 Android 8.0（API 26），包名为 `com.shuxian.videoclient.tv`，可与手机端同时安装。电视首页顶部为搜索框和设置，下面是电影、电视、综艺、自定义和播放记录菜单。电影和电视保留豆瓣推荐分类标签，综艺沿用豆瓣综艺推荐；自定义菜单保留点播源返回的父分类和子分类。

电视端使用系统默认的 dp / sp，海报列表固定每行 6 张，不提供列数设置。

首次使用在设置中填写 `vod.json` 订阅地址并导入。成人源默认不启用，但可在设置列表中手动启用并选为自定义源；自定义菜单默认使用第一个已启用的源，也可以在设置中选择其他源。搜索只查询已启用的非成人源。设置中不提供并发数输入，搜索内部最多同时请求 6 个源，并按源地址和影片 ID 去重。

电视列表的搜索、菜单、筛选和六列海报在同一个懒加载网格中整体滚动。海报保留卡片缩放、首次加载淡入、占位扫光和 loading 动画；布局提前准备前方约两排、保留后方约一排，图片另行预取前方三排及后方一排。可见海报优先加载，屏幕外请求最多占两个下载名额；图片按卡片像素尺寸解码并使用 32MB 内存缓存，缓存命中首帧直接显示，屏幕外卡片不运行扫光。距末尾约四排时提前请求下一页，失败时保留已有内容并重试原页，重复页停止继续加载。

主菜单使用 24sp 字号，筛选和普通按钮使用 20sp；聚焦时显示浅色实心底、深色文字及轻微缩放，当前选项用橙色标记，主操作使用橙色实心按钮。搜索框始终保留图标、底色和边界。详情、播放器、设置、续播与确认弹窗沿用一致的焦点状态，删除操作单独标记风险，确认删除默认聚焦“取消”。详情和设置页也整体滚动，导入期间按钮保留焦点并显示进度。

遥控器左右不跨行，上下按列移动；末行不足六张时向下落到最后一张，首行向上返回当前筛选，再向下回到原卡片。顶部滚出屏幕后，先滚回并等待布局完成再恢复焦点。返回键可回到列表顶部并聚焦当前筛选，浏览和搜索分别保存滚动位置，从详情返回时恢复原卡片焦点。从设置或首页搜索返回时恢复原入口；取消清空记录返回“删除全部”，记录的删除按钮上下移动及删除后保持在同一操作列。输入法关闭后可直接用方向键离开输入框。

列表下移时用 80ms 滚动过渡补足目标卡片超出视口的距离；目标卡片进入布局后立即聚焦，无需等过渡结束。连续方向键更新同一个待移动目标，长按连发最多每 100ms 接受一次，松开重新按下立即响应。卡片的手动定位与系统焦点滚动不会叠加。

详情页支持选择集数，使用 Media3 原生播放器播放 HLS / MP4；遥控器左右键每次快退或快进 20 秒，播放器内可选集和播放下一集，当前线路失败时会尝试同集的其他线路。播放记录保存在电视本机，记录卡片明确显示“继续播放”，详情页有同源记录时主按钮也显示“继续播放”并提示上次位置；其它源记录弹窗使用“恢复播放记录”主按钮。续播先读取本机缓存的线路和集数，再在后台更新详情。播放记录显示“第几集 · 源名称”，省略线路名称。播放器底栏按“下一集、倍速、选集”顺序排列，采用浅底深色焦点和深色渐变遮罩，进度条聚焦时突出滑块；关闭倍速或选集面板返回原操作按钮，退出播放器则滚动、聚焦到当前播放集，后台详情更新不会重复抢走焦点。电视端设置与播放记录不与手机或桌面同步。

播放器底栏的倍速、下一集、选集等提示跟随实际聚焦按钮显示，并避开进度条和屏幕边缘。每次从底栏或遥控器菜单键打开选集面板，都会定位当前播放集；窗口获得焦点、当前集完成布局后才解除恢复保护，当前集保留播放图标和选中状态。

电视端播放点播 HLS 时会过滤显式广告标记，并按整组地址特征、短片头和两长段之间的短中插识别广告；直播 HLS、字节范围清单和 MP4 保持原有播放方式。

需要 JDK 21、Android SDK Platform 36 和 Build Tools 35.0.0，并配置 release 签名。在 Windows 上运行 `npm run dist:android-tv`，签名 APK 输出到 `android-tv/app/build/outputs/apk/release/app-release.apk`。发布标签的 GitHub Actions 也会生成 `videoClient-vX.Y.Z-android-tv.apk`。

### HarmonyOS 7 适配工程

`harmony/` 是面向 HarmonyOS 7（API 26.0.0）的原生 HAP 工程。安装 DevEco Studio 26.0.0 Release 和对应 SDK 后，先在仓库根目录运行：

```bash
npm run sync:harmony
```

然后在 DevEco Studio 中打开 `harmony/`，同步工程。在 **File → Project Structure → Project → Signing Configs** 为连接的真机配置自动调试签名，再构建并运行 `entry`。每次修改 React 页面或更新版本号后，重新运行同步命令。网页资源随 HAP 打包，无需运行外部服务器。本地生成的 `harmony/build-profile.json5` 包含签名密码，已被 Git 忽略；不要强制添加到仓库。

在 Windows 上安装 PowerShell 7 并完成一次签名配置后，可连接并解锁手机，双击仓库根目录的 `build-install-harmony.cmd` 一键同步网页资源、编译签名 HAP 并覆盖安装。脚本会自动使用本机 `E:\mysoft\DevEco Studio` 或 `DEVECO_HOME` 指向的 DevEco Studio；其他安装位置可在 PowerShell 7 中运行 `./scripts/build-install-harmony.ps1 -DevEcoHome 'D:\DevEco Studio'`。连接多台设备时另加 `-DeviceId '设备编号'`，设备编号可通过 `hdc list targets -v` 查看。签名 HAP 保存在 `harmony/entry/build/default/outputs/default/entry-default-signed.hap`；脚本不会清除手机应用数据。

鸿蒙工程已接入首页、点播源请求、搜索、本地设置、图片和视频的本机代理，以及 DLNA 搜索、投屏与进度查询。进入播放页后在详情页内自动播放，手动点击全屏时仅播放器横向铺满屏幕并隐藏系统栏，页面方向保持不变；浅色和深色模式同步设置状态栏、导航栏颜色。鸿蒙端设置、播放记录和详情缓存保存在本机 `settings.db`，旧的 ArkWeb 设置与播放记录会在首次启动时迁入。点播 HLS 清单会过滤显式广告标记，并按整组地址特征、短片头和两长段之间的短中插识别广告；直播和字节范围清单保留原样。

API 26 工程已通过 DevEco Studio 26.0.0 编译与调试签名；真机已验证海报和视频经代理加载。DLNA 仍需在同一局域网的电视上做投屏验收。

安装包输出到 `release/`。字体文件随安装包提供，不需要启动时连接 Google Fonts。macOS 默认使用无需苹果开发者账号或证书的 ad-hoc 签名，未经过 Apple 公证，首次启动仍可能需要手动放行。首页数据和点播资源都依赖相应的第三方服务；个别线路失效时，可以在搜索结果中换源。接收设备需支持对应的投屏协议，并能自行访问视频源地址。
Windows 安装包使用安装向导，可在安装时选择目录。
发布新版本时，先更新 `package.json` 的版本号，再推送对应的 `vX.Y.Z` 标签。GitHub Actions 会构建 Windows x64 安装包、Apple Silicon（ARM64）macOS DMG、Android 手机 APK、原生 Android 电视 APK 和 HarmonyOS 签名 HAP，并发布到 GitHub Releases。文件名分别为 `videoClient-vX.Y.Z-windows-x64.exe`、`videoClient-vX.Y.Z-macos-arm64.dmg`、`videoClient-vX.Y.Z-android.apk`、`videoClient-vX.Y.Z-android-tv.apk` 和 `videoClient-vX.Y.Z-harmony.hap`，也可在运行记录的 Artifacts 中下载。Windows CI 产物未签名；macOS CI 会对 App 和 Helper 进行 ad-hoc 签名，并在上传前校验签名，无需配置 macOS 签名 Secrets。若要让下载的 Mac 应用通过默认 Gatekeeper 检查，仍需改用 Developer ID 签名并完成 Apple 公证。

GitHub 仓库的 Actions Secrets 需要配置 `ANDROID_KEYSTORE_BASE64`（同一份 JKS 密钥库的 Base64 内容）和 `ANDROID_KEYSTORE_PASSWORD`。工作流只在构建签名版 APK 时读取它们，并核对证书 SHA-256 指纹 `7F:98:3E:F3:2A:1B:69:A8:4E:D1:94:79:78:13:E1:88:C4:66:DD:BC:CE:E1:A7:43:89:0C:6F:36:B5:58:FE:D1`；密钥缺失或签名不一致时直接失败，避免再次发布不同签名的 APK。请在仓库外备份原始 JKS 和密码，后续版本必须继续使用同一密钥。本地 `npm run dist:android` 仍生成调试签名 APK；旧版 GitHub 调试签名 APK 无法用新正式签名直接覆盖安装，需要先卸载一次。

### GitHub 上的鸿蒙构建设置

鸿蒙任务使用带 `harmony` 标签的 Windows x64 自托管 Runner。到仓库 **Settings → Actions → Runners → New self-hosted runner**，按 GitHub 给出的 Windows 命令安装并启动 Runner，添加 `harmony` 标签。Runner 需要 PowerShell 7、DevEco Studio 26 和 HarmonyOS API 26 SDK；工作流默认从 `E:\mysoft\DevEco Studio` 调用工具。若 Runner 装在其他机器，请先修改工作流中的 `DEVECO_HOME`。Runner 必须保持在线，鸿蒙任务才能开始。

本机鸿蒙签名材料保存在仓库根目录的 `harmony-signing/`（已加入 Git 忽略）。在这台电脑的 PowerShell 7 中运行以下命令，脚本会把 `.p12`、配套的 `.cer` 和 `.p7b`、DevEco 密码加密所需的 `material` 目录，以及本机签名配置打包，并将 Base64 内容复制到剪贴板：

```powershell
.\scripts\export-harmony-signing.ps1 -StoreFile '.\harmony-signing\signing.p12'
```

到仓库 **Settings → Secrets and variables → Actions → New repository secret**，添加 `HARMONY_SIGNING_BUNDLE_BASE64`，粘贴剪贴板内容。请勿将 `harmony-signing/`、Base64 或本机 `harmony/build-profile.json5` 提交到仓库。以后换构建机时，仍需使用同一套签名材料；可在新机器重新导出，并更新这一项 Secret。

CI 会校验 `.cer` 的 SHA-256 为 `50599d171bf509a40dfa214b5bcd366b2b6ff9c762aa67781a600b8b79c4c5c6`，不一致就停止构建，避免发布无法覆盖当前安装版的 HAP。当前使用的自动调试签名配置通常只允许已登记的设备安装；若要面向所有设备分发，需先在华为开发者平台申请正式发布签名，这会涉及签名迁移。本工作流也支持在 Actions 页面手动运行，手动运行只上传构建产物，不创建 GitHub Release。

### macOS ad-hoc 签名与首次打开

在 Mac 上运行 `npm run dist:mac` 即可生成带 ad-hoc 签名的 DMG。配置保留 Hardened Runtime，并为主进程和 Helper 声明 JIT 与加载 Electron 框架所需的权限。ad-hoc 签名不提供苹果认可的开发者身份，也不能用于 Apple 公证。

从本项目的 GitHub Releases 下载 DMG，把「影窗.app」拖入「应用程序」。首次打开若提示无法验证开发者或 Apple 无法检查恶意软件，可在确认下载来源后到「系统设置 → 隐私与安全性」中点击「仍要打开」。如果提示应用「已损坏」，可打开终端执行：

```bash
sudo xattr -dr com.apple.quarantine "/Applications/影窗.app"
open "/Applications/影窗.app"
```

这只移除该应用的下载隔离标记，不会给应用添加 Apple 签名。重新安装新版本后可能需要再次执行。

Windows 安装版的 `settings.db` 位于实际安装目录（例如 `E:\mysoft\videoClient\settings.db`），该目录需要允许当前用户写入。安装新版本时，安装器会先备份旧数据库，再恢复到新安装目录；开发版和 macOS 仍使用系统用户数据目录。
旧版 `localStorage` 和 `settings.json` 不会迁移到 SQLite，首次使用新版需要重新设置。
DLNA 投屏把视频源地址直接发给接收设备，由接收设备自行取流。需要代理补充请求头、依赖广告过滤或电视无法直接访问的视频源可能无法播放。播放器上绘制的弹幕不会自动随视频一起投出。
macOS 首次搜索投屏设备时，请允许「影窗」访问本地网络；若之前拒绝，可在「系统设置 → 隐私与安全性 → 本地网络」中重新开启，然后在投屏窗口重新搜索。
