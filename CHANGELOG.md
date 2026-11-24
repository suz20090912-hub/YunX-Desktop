# 更新日志

本文件只记录 Linux 版相对上游 Windows 桌面版的版本变化；完整移植细节见
[Linux 移植记录](docs/LINUX-PORT-FIXES.md)。

## v1.3.4-linux.5（2026-11-24，待发布）

### 变更

- 恢复上游远程公告并**保持原样不做修改**；Linux 版内置公告（发布说明 + 使用须知）改为**叠加**在远程公告列表最前，详情命中内置 id 时离线返回。
- 取代 `.4` 的"仅内置公告"方案：上游公告与 Linux 版公告共存。
## v1.3.4-linux.4（2026-11-24，被 .5 取代）

### 变更

- 应用内公告改为**随包内置的 Linux 版公告**（发布说明 + 使用须知），不再连接上游远程公告服务；列表 / 详情 / 启动弹窗 / 未读角标离线可用，不受代理 / 断网影响。
## v1.3.4-linux.3（2026-11-24，已发布）

### 修复

- GitHub Actions 的 Linux 构建与 Release 改为 Ubuntu 22.04，修复 24.04 产物中的 `libasound2t64` / `libpng16-16t64` 依赖在 Ubuntu 22.04 上无法安装的问题。
- `patchDebDepends` 归一化 t64 包名，使同一份 deb 兼容 Ubuntu 22.04 / 24.04。
- Linux 构建文档的验证环境统一为 Ubuntu 22.04.5 LTS；同步验收记录中的 deb SHA-256。
- 应用日志与调试快照标题统一为 `YunX-Linux`。

## v1.3.4-linux.2（2026-11-24，已发布）

### 变更

- 项目与仓库更名为 **YunX-Linux**，明确标注 Linux 版，避免被误认为 Windows 桌面版。
- 应用窗口标题、托盘提示、打包元信息与 Linux 可执行文件统一改为 `云析 YunX-Linux` / `YunX-Linux`。
- 关于页、首次引导页、支持开发页的 Linux 仓库地址同步为新仓库 `suz20090912-hub/YunX-Linux`。
- 更新检查改为查询新仓库的 Releases，只匹配 Linux 安装包。
- `docs/images/` 下旧 Windows 截图全部替换为 Linux 实机截图；README 增加设置页、网盘页截图。

## v1.3.4-linux.1（2026-11-24，已发布）

首个 Linux 桌面移植版本，基线为上游 `v1.3.4`。

### 新增

- Linux `.deb` 安装包与 Gradle Wrapper 构建链路。
- Linux 宿主适配层：POSIX 路径长度与文件权限、X11 单实例窗口激活、`notify-send` 通知、`systemd-inhibit` 防休眠、GNOME / 环境变量系统代理。
- Gopeed 内核按平台选择固定版本；B 站 DASH 合轨优先系统 `ffmpeg`，缺失时回退内置 Java。
- Linux 版关于页、首次引导页移植说明；支持开发页新增 Linux 仓库入口与“不设赞助渠道”声明。
- `docs/BUILD-LINUX.md` 构建文档、`docs/LINUX-PORT-FIXES.md` 移植记录。
- GitHub Actions：`linux-build.yml` 编译并产出 deb artifact；`release-linux.yml` 按 `v*` tag 自动创建 Release 并上传 deb。
- deb 的 `DEBIAN/control` 自动补充 GTK / NSS / GBM / X11 / JavaFX 运行依赖与 GStreamer / ffmpeg 推荐依赖。

### 修复

- 长中文文件名在 Linux 下触发 `ENAMETOOLONG`。
- 数据目录 / 数据库 / 凭证密钥权限过宽。
- 更新弹窗错误匹配 Windows 安装包。
- 无合成器 X11 下隐藏 CEF 窗口抛异常；陈旧 CEF `SingletonLock` 导致启动失败。
- Gopeed 停止顺序导致的孤儿进程与僵尸任务。
- 托盘不可用、二次启动静默退出、关闭后无法恢复窗口。
- 数据库加密等级、通知、代理、托盘等设置页 Windows 专有文案。
- 内置 JavaFX 对部分编码报 `Could not create player!`；自动回退 FFplay 兼容模式。
- Linux 下 ffplay 无法嵌入应用窗口，恢复「自动 / FFplay / 内置 JavaFX」三选项并给出独立窗口提示。

### 文档

- 新增 Linux 版赞助说明与 Linux 仓库地址。
- 关于页、首次引导页、README 的仓库入口改为 Linux 版仓库，同时保留上游 Windows 桌面版地址。

## v1.3.4（上游 Windows 桌面版，2026-11-13）

Linux 移植的基线版本，功能与上游 Windows 桌面版保持一致。上游变更见
[tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop) 的 Releases。
