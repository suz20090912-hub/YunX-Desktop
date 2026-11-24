# Linux 移植版问题修复记录

基线：官方 `v1.3.4`（`version.txt` = `1.3.4`，最后提交 `2026-11-13 chore(1.3.4): 版本号提升到 1.3.4`）。
本记录对应《YunX-常见问题清单-终稿.md》里「在 Linux 上会复现 / 新引入」的问题，按由易到难分三批修复。

验证方式：`./gradlew :desktop:compileKotlin`（仓库无 `gradlew` 时用 Gradle 8.13 发行版 + JDK 17）。
全部改动均通过编译；启动链路已在 Xvfb 下回归，范围见文末「未验证项」。

---

## 第一批：平台适配与安全底线

| 问题 | 原实现 | 修复 |
| --- | --- | --- |
| 长中文标题触发 `ENAMETOOLONG` | `DownloadPathPolicy.sanitizeName` 按 120 **字符**截断；Linux 单层文件名上限 255 **字节**，中文 3 字节/字，约 78 字即失败 | Windows 保持 120 字符；POSIX 改为按 UTF-8 字节截断到 200 字节，保留扩展名、emoji 代理对整体保留（`DownloadPathPolicy.kt`） |
| 数据目录/数据库权限过宽 | `~/.yunx-desktop` 755、`yunx.db` 644；`DbKeyStore` 用世界可读的 `/etc/machine-id` 派生「普通」档密钥，同机其他用户可解密凭证 | 新增 `UnixPermissions`（`PosixFileAttributeView` 探测，不支持则跳过）：目录 700，db/wal/shm 与 `credential.key` 600；`AppContext.init()` 启动时收紧（`UnixPermissions.kt`、`AppContext.kt`、`DbKeyStore.kt`） |
| 「较为严格（DPAPI）」在 Linux 可选但必失败 | `DbEncryptionLevel.isSupported = this != STRICTEST`，UI 文案写「绑定当前 Windows 用户」 | `STRICT` 仅 Windows 可用；`isMachineBound` 同步修正；切换失败提示区分 SQLCipher / DPAPI；设置页在 Linux 显示「仅 Windows 可用，当前系统无 DPAPI」（`DbKeyStore.kt`、`DbEncryptionManager.kt`、`StorageDirectoryScreen.kt`） |
| 更新弹窗给 Linux 用户下 win64 包 | `UpdateDialog` 无脑取 `.exe` / `.zip` | 非 Windows 只匹配 `.deb/.appimage/.rpm/.tar.gz/.tar.xz/.dmg`；无兼容资产时强制更新降级为可选，并显示平台化空态文案（`UpdateDialog.kt`） |
| 无合成器 X11 下隐藏 CEF 窗口抛 `UnsupportedOperationException` | `HeadlessWebView` 直接 `host.opacity = 0.0f` | 失败时兜底移到屏幕外 `(-32000, -32000)` 并记日志；关闭时释放隐藏宿主窗口（`HeadlessWebView.kt`） |

## 第二批：下载链路健壮性

| 问题 | 原实现 | 修复 |
| --- | --- | --- |
| 内核版本漂移 / 资产前缀不匹配 | 走 `releases/latest` + `gopeed-web-` 前缀，Linux 资产命名不同 | 固定 `v1.9.3`（`releases/tags/v1.9.3`），资产按平台 token 选择，错误文案带 tag 与期望资产名（`GopeedKernelProvisioner.kt`） |
| `stop()` 先删 pid 再杀进程，孤儿进程占 bolt 锁，下次启动 20s 超时 | 先删 `engine.pid` 再 `destroy` | 先取 pid → 有 Process 用 `destroyProcess`，无 Process 用 `killPidIfOurs(pid)`；仅在此之后删 pid 文件；`killStaleProcess` 复用同一套（`GopeedEngine.kt`） |
| 僵尸任务：`view == null` 只打日志，界面永久「下载中」 | 1000ms 轮询里 `continue` | 按任务累计连续 miss 次数，≥10 次（约 10s）标记失败、写错误与诊断日志（`ENGINE_UNREACHABLE`）、清理引擎任务映射；查到状态时计数清零（`DownloadManager.kt`） |
| Linux 不读系统代理，GitHub API 每次 403 | `SystemProxy` 仅读 Windows 注册表 | Linux 依次读 `https_proxy/https_proxy/http_proxy/all_proxy` 环境变量，再读 GNOME `gsettings org.gnome.system.proxy`（manual 模式的 http/https host+port）；SOCKS 与 PAC 明确记日志后直连（`SystemProxy.kt`） |
| Linux 下载时仍会休眠 | `WindowsKeepAwake` 空操作 | Linux 用 `systemd-inhibit --what=idle:sleep --mode=block sleep infinity` 子进程，`release()` 销毁；无 `systemd-inhibit` 时降级为空操作并记日志（`WindowsKeepAwake.kt`） |
| 引擎页文案承诺「最新内核」与固定版本不符 | —— | 改为「从官方下载已验证内核」（`DownloadEngineScreen.kt`） |

## 第三批：桌面集成

| 问题 | 原实现 | 修复 |
| --- | --- | --- |
| 非 Windows 托盘右键无菜单 | `Win32PopupMenu.show` 在非 Windows 直接 `return 0` | 右键回调在 Linux 上直接还原主窗口；托盘图标不再设置空 `PopupMenu`（Linux 设空菜单会弹空白菜单且不回调）（`Main.kt`、`TrayManager.kt`） |
| GNOME 无托盘时「关闭=最小化到托盘」导致窗口无法恢复 | `CLOSE_BEHAVIOR_TRAY` 无条件隐藏 | 托盘未安装时：有活动任务先弹关闭确认，无任务直接退出（`MainScreen.kt`） |
| Linux 无通知 | `WindowsToastNotifier` 非 Windows 全部 no-op；`TrayManager.showNotification` 无图标直接 return | 新增 `DesktopNotifier`（`notify-send`）；托盘气泡不可用时回退；全部下载完成时在 Linux 发完成通知（`DesktopNotifier.kt`、`TrayManager.kt`、`WindowsToastNotifier.kt`） |
| 二次启动静默退出 | `SingleInstance.focusExistingWindow` 非 Windows 直接 return | 非 Windows 至少发桌面通知「云析已在运行」，并写日志（`SingleInstance.kt`） |
| 「下载完成后关机」Linux 恒失败 | `DesktopActions.shutdownComputer` 非 Windows 返回 false | Linux 依次尝试 `systemctl poweroff` → `shutdown -h now` → `poweroff`，按退出码判定；失败提示改为「请检查系统电源管理权限」（`DesktopActions.kt`、`DownloadViewModel.kt`） |
| 「设置主窗口位置」依赖 Windows C# 遮罩进程 | Linux 上 `PositionPicker.pick` 必然返回 null，按钮无反应 | Linux/macOS 改为「保存当前窗口位置」：把窗口拖到目标位置后一键写入自定义坐标（`ThemeScreen.kt`） |
| 一键导入浏览器 Cookie 在 Linux 恒空 | 只查 `LOCALAPPDATA/APPDATA` + DPAPI，路径用 `\\` | Linux/macOS 路径 + 各 Profile 扫描（`Default/Profile */Network/Cookies`，兼容旧 `Cookies`）；Linux Chromium v10 用 keyring 密码（`secret-tool lookup application <app>`，缺省 `peanuts`）做 PBKDF2 + AES-128-CBC 解密，v20 跳过；Firefox 明文读取；顺带修正 Windows 只查 `User Data/Network/Cookies`、漏掉 `Default` 的问题（`BrowserCookieImporter.kt`） |
| CEF profile 被陈旧 `SingletonLock` 锁死（启动回归新发现） | Chromium 用 `cache/jcef/SingletonLock` 软链（`<hostname>-<pid>`）判断占用；换主机名或上次进程被强杀后留下断链，此后每次启动 `ContentMainRun failed with exit code 21`，网页空白 | `JcefHolder.cleanStaleSingletonLocks()`：启动时检查软链（注意断链 `File.exists()` 返回 false，必须用 `isSymbolicLink`），pid 已死或不是 CEF 进程则删除 `SingletonLock/SingletonCookie/SingletonSocket`；仍被存活 CEF 占用则保留（`JcefHolder.kt`） |

## 第三批补充：引擎启动语义与 Linux 提示（2026-11-23）

| 问题 | 原实现 | 修复 |
| --- | --- | --- |
| 重启 YunX 后 Gopeed 显示「使用中」却「已导入，未启动」，像必须手动点一次 | 只有新建/继续引擎任务、同步未完成任务时才按需 `GopeedEngine.start`；`DownloadEngineScreen` 进页只 `syncInstalledState` | 应用启动时若 `downloadEngine == gopeed` 且内核已导入，后台拉起引擎；失败只记日志，下载时仍会重试（`Main.kt`） |
| 未启动时按钮仍写「重启引擎」 | 固定文案 | 按 `GopeedEngine.State` 显示「启动引擎」/「重启引擎」，成功提示同步区分（`DownloadEngineScreen.kt`） |
| Linux 上 FFmpeg 区块仍提供「下载 FFmpeg 精简版」 | 精简包只有 Windows 构建，Linux 点击后必然失败 | Linux/macOS 隐藏下载按钮，改为提示 `sudo apt install ffmpeg`；未检测到时的状态文案同步改为系统包安装指引（`DownloadEngineScreen.kt`） |
| Linux 二次启动只发通知、不激活已有窗口 | `focusExistingWindow` 非 Windows 直接返回，仅调用 `DesktopNotifier.show` | 新增 `LinuxWindowFocus`：X11 按标题递归找窗口，`XRaiseWindow` + `XSetInputFocus` 激活（XWayland 可用）；找不到时回退 `wmctrl -a` / `xdotool ... windowactivate`，全失败才退通知（`LinuxWindowFocus.kt`、`SingleInstance.kt`） |

## 第四批：设置页去 Windows 化与登录链路审计（2026-11-24）

| 问题 | 原实现 | 修复 |
| --- | --- | --- |
| 「数据库加密等级」在 Linux 上仍展示 Windows DPAPI 档 | 只把 `STRICT` 置灰，副标题仍写「密钥用 Windows DPAPI 保护 —— 仅 Windows 可用，当前系统无 DPAPI」 | Linux/macOS 直接隐藏不可用的 `STRICT`/`STRICTEST` 档；若当前配置恰好是从 Windows 迁来的 `STRICT`，保留一行只读提示「当前系统不支持此档；Linux 请选择普通或宽松」。切档错误文案改为「仅在 Windows 上可用」而不是「当前系统不可用」（`StorageDirectoryScreen.kt`、`DbEncryptionManager.kt`） |
| 网络代理弹窗写死「系统代理取自 Windows 系统设置」 | 所有平台同一句 | Windows 保持原说明；Linux 改为「系统代理读取 GNOME 网络设置或 http_proxy / https_proxy 环境变量，仅在启动或切换设置时读取一次」（`SettingsScreen.kt`） |
| 设置页「通知中心下载进度」是 Windows 专有交互 | 标题与副标题都写「Windows 通知中心进度条 + 下载速度」，Linux 上实际只有完成通知 | Windows 分支保留进度条开关；Linux 改为只读行「下载完成通知 / 全部任务完成时发送桌面通知；Linux 通知不显示进度条」（`SettingsScreen.kt`、`WindowsToastNotifier.kt`） |
| GNOME 无托盘时设置页仍提供「最小化到系统托盘」 | 关闭行为选项与关闭确认弹窗无条件列出托盘选项，选中后窗口隐藏且无法恢复 | 设置页在 `TrayManager.isSupported == false` 时隐藏托盘选项；关闭确认弹窗再以 `isInstalled` 兜底（图标未安装也不显示），有下载任务时只保留「仍然退出（停止下载）」，默认选中同步改为退出（`SettingsScreen.kt`、`CloseConfirmDialog.kt`） |
| Linux 无系统代理时提示「当前平台不支持」 | `SystemProxy.inspect()` 非 Windows 且未读到环境变量/GNOME 代理时返回 `Unavailable` | 改为返回 `Direct`，UI 显示「未检测到系统代理，按直连处理」；`Unavailable` 只表示读取失败（`SystemProxy.kt`、`SettingsScreen.kt`） |
| 关于页写「Windows 桌面端」 | 固定文案 | 改为 Linux 版说明：版本行、移植卡片、Linux 实际功能描述、桌面版源码仓库卡片（`AboutScreen.kt`） |
| Cookie 导入失败提示只提 Chrome/Edge/360 | Linux 上 Chrome/Chromium 新版 v20 Cookie 无法解密时提示不准确 | Linux 分支提示「Chrome/Chromium 127+ 的 v20 加密 Cookie 无法读取，可尝试 Firefox，或直接在内嵌浏览器登录」（`JcefLoginPane.kt`） |
| 机器指纹读取失败日志写「MachineGuid」 | Linux 实际读 `/etc/machine-id`，报错仍写 Windows 注册表项 | 统一改为「机器唯一标识」，避免 Linux 日志误导（`DbKeyStore.kt`） |
| Linux 上 `SystemTray.isSupported()` 为 true、但实际无 XEmbed 托盘时崩溃 | `TrayManager.install()` 只查 `isSupported` 就 `add()`；AWT 的 `XTrayIconPeer` 在 EDT 异步创建，桌面没有 tray owner 时抛 `UnsupportedOperationException`，`trayIcon` 仍被置为非空，关闭窗口会隐藏后无法唤回 | `add()` 失败立即清状态；延迟 2 秒核对 `SystemTray.trayIcons`，未挂上则回退 `notify-send`；全局未捕获异常处理器识别「system tray is not supported」并只记 warning，不再当应用崩溃（`TrayManager.kt`、`Main.kt`） |

### 登录 / 解析链路审计结论

- **代码层已适配**：网盘与视频的解析本身走 HTTP/API，不依赖 Windows；登录页 `JcefLoginPane` 使用 JCEF，Linux 已完成 CEF 初始化、陈旧 `SingletonLock` 清理、offscreen 容器适配；凭证落库走 `FileCredentialCipher`，Linux 的 `NORMAL` 档用 `/etc/machine-id` 派生机器密钥。
- **Cookie 自动导入**：Linux 已扫描 Chrome/Chromium/Edge/Brave/Vivaldi/Opera 与 Firefox（含 Snap/Flatpak 路径）；Chromium v10 用 `secret-tool` keyring 密码 + `peanuts` 回退做 AES-128-CBC 解密；v20 应用绑定加密仍跳过。**推荐优先用内嵌浏览器登录**，Cookie 导入作为备选。
- **已运行态验证（2026-11-24，用户 Hyper-V Ubuntu 24.04）**：网盘链路已在虚拟机跑通「扫码登录 → 文件列表 → 取直链 → 下载」。用户反馈扫描二维码登录正常、直链下载正常。
- **视频链路已运行态验证（2026-11-24 日志导出）**：B 站内嵌浏览器登录成功，Cookie 从 7 个增长到 34 个，包含 `SESSDATA`、`bili_jct`、`DedeUserID`、`DedeUserID__ckMd5`、`sid`；抖音分享链接解析成功，`origin=https://v26-web.douyinvod.com`，任务 id=32 于 17:49:27 入队，17:49:35 `引擎任务完成`，文件落到 `/home/suz/下载/一句话证明你看过这本书…#小说 #原著党 #经典小说 #开封城墙 #城墙景区.mp4`，无解析/下载错误。
- **B 站 DASH 合轨已运行态验证（18:09–18:10 日志）**：任务 id=33 解析出 `dashAudio=true` 的音视频双轨，分别下载完成后调用系统 `/usr/bin/ffmpeg`；ffmpeg 因输出文件 `dash_merged_33` 没有扩展名报 `Unable to choose an output format`，自动回退内置 Java `DashMuxer`，合轨成功，`savedPath=/home/suz/下载/搁游戏里都过于离奇的一幕竟是真实事件？GTA6真不养闲人!_1080P.mp4 size=53375135`。这说明「B 站取流 → 双轨下载 → 合轨落盘」全链路已通；ffmpeg 输出扩展名问题已在本次构建中修正（临时产物改为 `dash_merged_$id.mp4`），待下一轮运行态确认 ffmpeg 直出路径。
- **视频仍需补测**：其他视频平台未逐一实测；合轨失败时回退内置 Java 的路径已实测成功，ffmpeg 直出路径已补上扩展名，待下一轮实测确认。
- **Windows UA 不是平台依赖**：`BaiduConstants`、`QuarkConstants` 等保留 `Windows NT 10.0` UA 与 `sec-ch-ua-platform: "Windows"` 是网盘服务端兼容策略，不是 Linux 未适配；不要改成 Linux UA，否则部分平台会拒绝取链。

### Linux 播放器形态调整（2026-11-24）

- Linux/macOS 没有实现 ffplay 的窗口嵌入（原来的 `SetParent` 只适用于 Win32），ffplay 会另开独立窗口，进度/音量/亮度都不在那个窗口里。Linux **自动模式默认优先内置 JavaFX**：`VideoPreviewOverlay` 把 `MediaView` 通过 `JFXPanel` 嵌在应用窗口内，进度条、音量滑条、亮度滑条都在同一窗口内。
- 音量走 `MediaPlayer.volume`，亮度走 `MediaView` 的 `ColorAdjust.brightness`（UI 0–2，1 为原始亮度）。
- Linux 的预览引擎设置保留三个选项：自动（JavaFX 优先）、FFplay、内置 JavaFX。显式选 FFplay 时给出提醒：「Linux 会另开独立窗口，无法嵌进应用；进度/音量/亮度仍在应用底部控制条」。不再把 FFplay 锁死。
- **实测 jfxmedia 兼容性有限**：18:41 打开某条视频时报 `com.sun.media.jfxmedia.MediaException: Could not create player!`，随后内置 JavaFX 无法起播；已加自动兜底：JavaFX 初始化失败时切到 FFplay 兼容模式，并在预览顶部显示「已切换 FFplay 兼容模式（独立窗口）」提示。
- 代价：内置 JavaFX 仍需要先把 MP4 缓存到本地（`Mp4PreviewSession`）再起播，不如 ffplay 直读直链快；换来的是单窗口和完整控件。

### Linux 文案与版本说明（2026-11-24）

- 关于页、首次引导页统一改为 Linux 桌面端口径，明确标注「完全移植自 YunX-Desktop 桌面版（Windows），仅在技术层面实现 Linux 兼容运行，界面/功能一致，不额外增加 Linux 专属功能」。
- 关于页新增「Linux 版说明」卡片：预览默认窗口内播放、编码不支持时切 FFplay 兼容模式（独立窗口）、数据库仅宽松/普通、无托盘时关闭回退、通知走 notify-send、代理读 GNOME/环境变量、合轨优先系统 ffmpeg、数据目录与日志反馈。
- 功能特性里的「在线预览 / 引擎管理」改成 Linux 实际行为；仓库卡改为「Linux 版源码仓库」，链接到 `suz20090912-hub/YunX-Linux`，并注明上游为 Windows 桌面版。
- 引导页欢迎页、功能特性页、协议页都补了同一段移植说明，避免用户误以为 Linux 版有额外功能。

### Linux 仓库地址与赞助页说明（2026-11-24）

- 关于页、首次引导页的仓库入口改为 Linux 版仓库 `https://github.com/suz20090912-hub/YunX-Linux`，同时保留上游 Windows 桌面版地址。
- 支持开发页新增 Linux 声明：本项目由于完全移植于 desktop 版，故不设置赞助渠道；如有想要赞助的想法，请赞助安卓版作者（CYQawa）与桌面版作者（tidain）。
- 支持开发页新增「Linux 版仓库」入口；设置页的「支持开发」副标题在 Linux 上改为「Linux 版不设赞助渠道，可赞助上游作者」。
- README 新增「赞助说明」与 Linux 版仓库地址；`docs/BUILD-LINUX.md` 补齐 Linux 构建与依赖说明；新增 GitHub Actions Linux 构建工作流。
- 更新检测在 Linux 上改为查询 Linux 版仓库的 Releases，只接受 `.deb` / AppImage 等 Linux 资产，避免误下载 Windows 安装包。

## 启动回归结果（Xvfb，2026-11-23）

- 首启日志：`渲染后端：SOFTWARE` → `database opened` → `清理失效的 CEF 单例锁` → `JCEF initialized (attempt 1)`，无 `HeadlessException`、无 `ContentMainRun failed with exit code 21`。
- 权限实测：`~/.yunx-desktop` 700、`yunx.db` 600。
- 运行环境注意：只用 `openjdk-17-jdk-headless` 会缺 `libawt_xawt.so`，Compose 桌面启动即 `HeadlessException`；需要安装 `openjdk-17-jre`（或桌面版 JDK）。

## 安装包与架构

- `jfxClassifier` 之前对 arm64 也返回 `linux`，会打进 x86_64 JavaFX 原生库。已改为 `linux-aarch64` / `mac-aarch64`（`desktop/build.gradle.kts`）。
- 仍未处理（属打包流水线，不在应用源码内）：
  - `setup` 安装脚本尚未提供；当前以 `.deb` + `apt install ./xxx.deb` 为准。
  - 截图来自 Xvfb 无头环境，未使用带桌面特效/缩放的实机窗口；README 已收录主界面、支持开发页、关于页三张实际运行截图（`docs/screenshots/`）。

## deb 运行依赖自动补丁（2026-11-24）

- `packageDeb` 结束后自动执行 `patchDebDepends`：
  - 用 `dpkg-deb -R` 解包 deb，读取 `DEBIAN/control`；
  - 向 `Depends` 追加 `libgtk-3-0 | libgtk-3-0t64`、`libnss3`、`libgbm1`、`libxss1`、`libx11-xcb1`、`libxcomposite1`、`libxdamage1`、`libxrandr2`、`libatk-bridge2.0-0 | libatk-bridge2.0-0t64`、`libcups2 | libcups2t64`、`libdrm2`；
  - 向 `Recommends` 追加 `ffmpeg`、GStreamer base/good/libav、`libnotify-bin`、`wmctrl`、`xdotool`；
  - 修正 `DEBIAN/control` 中不允许的空行，避免 `dpkg-deb` 报 `several package info entries found`；
  - 给 `postinst` / `prerm` 的 `xdg-desktop-menu` 加 `|| true`，在无桌面会话 / 无 system menu 的环境下不让 apt 安装失败；
  - 用 `dpkg-deb --build --root-owner-group` 原地重打。
- 验收命令：`dpkg-deb -I desktop/build/compose/binaries/main/deb/*.deb | grep -E 'Depends|Recommends'`。
- 实测（Ubuntu 22.04 + Xvfb）：`sudo dpkg -i` 可安装成功；应用启动、JCEF 初始化、数据库打开正常。无 D-Bus / 无 GPU 的环境会有 Chromium 的 bus/GPU 日志，不影响主窗口。

## 按 tag 自动 Release（2026-11-24）

- `.github/workflows/release-linux.yml`：推送 `v*` tag 时，在 Ubuntu 22.04 + JDK 17 上构建 deb；`patchDebDepends` 同时把 t64 包名归一化为带备选的依赖，用 `gh release create` 创建 Release，并 `gh release upload --clobber` 上传 deb。
- 触发示例：`git tag v1.3.4-linux.1 && git push origin v1.3.4-linux.1`。
- 版本说明见 `CHANGELOG.md`。
  - Deb 未随带 `jcef-natives.tar.gz`，首次运行从 classpath 解压约 325 MB。功能可用，但首启慢；若要优化，需要仿照 Windows `JcefNativesPacker` 在 Linux 打 deb 后重打包。
  - 清单里「`/opt/yunx/data` 预设不可写」在当前源码中未找到对应写入点（下载目录走 `DesktopActions.defaultDownloadDir`，数据目录走 `~/.yunx-desktop`），按不适用处理；如后续安装脚本引入该路径，应以每用户目录为准。

## 未验证项（需要运行态回归）

- 真实下载 / 解析 / 登录链路：网盘已验证；视频已验证 B 站登录、抖音解析/下载、B 站 DASH 双轨下载 + 内置合轨；其他视频平台仍待补测。
- gopeed 1.9.3 Go 二进制自身的「删任务/暂停 panic 竞态」无法在 Kotlin 侧修复；当前只做到固定版本 + 进程生命周期兜底，遇到 panic 会由僵尸任务收敛逻辑在 10s 内标记失败。
- 托盘：Linux 上 AWT `SystemTray` 依赖 XEmbed tray owner，`isSupported` 为 true 也可能在异步挂载时失败；当前已做「失败即清状态 + `notify-send` 回退」，不保证所有桌面都能显示托盘图标。
- 更新弹窗依赖 Linux 发布资产命名符合 `.deb/.appimage/.rpm/.tar.gz/.tar.xz` 约定；若发布页命名不同需同步调整匹配规则。
- `notify-send` / `secret-tool` / `systemd-inhibit` 缺失时均静默降级，仅写日志。

## 复现与自检命令

```bash
# 编译
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
/home/user/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle :desktop:compileKotlin --console=plain

# 权限（应为 700 / 600）
stat -c '%a %n' ~/.yunx-desktop ~/.yunx-desktop/yunx.db ~/.yunx-desktop/credential.key

# 代理
env | grep -i proxy
gsettings get org.gnome.system.proxy mode

# gopeed 内核探活
curl -s http://127.0.0.1:18899/api/v1/info
```

## 公告本地化（2026-11-24，v1.3.4-linux.4）

- 上游公告服务（`yunx.cyqawa.os.kg`）只发布上游自己的公告，且明文 HTTP 在代理 fake-ip / 断网环境下必然连接超时。
- Linux 版 `AnnouncementApi` 改为内置公告源：`fetchPage` / `fetchDetail` 直接返回随包内置的 Linux 版公告（置顶发布说明 + 使用须知），对外签名不变，ViewModel / UI / 已读记录（`AnnouncementReadStore`）零改动。
- 更新公告的方式改为修改内置列表并发新版；新公告 id 自动算未读并触发启动弹窗。