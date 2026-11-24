# Linux 版验收记录

日期：2026-11-24
环境：Ubuntu 22.04.5 LTS x86_64（无头验收，Xvfb 1440x900 + Openbox）
安装包：`yunx-desktop_1.3.4-1_amd64.deb`（Release `v1.3.4-linux.7`，230,122,118 字节；首次验收为 `v1.3.4-linux.3`，230,120,998 字节）
SHA-256：`41a34a6ece544d44bd41f1b9f4fe4dfa066a95b006db1519dbdab87952206e94`（.3 为 `48a945e7dd51b2b0a614a873620d0a5ea831c9b9189e9efb84da7412f650276f`）

## 安装

```bash
sudo dpkg -i desktop/build/compose/binaries/main/deb/yunx-desktop_1.3.4-1_amd64.deb
```

结果：

- `dpkg -s yunx-desktop`：`Status: install ok installed`、`Version: 1.3.4-1`。
- `/usr/bin/yunx-desktop -> ../../opt/yunx-desktop/bin/YunX-Linux` 已创建。
- 无桌面菜单环境会打印 `xdg-desktop-menu: No writable system menu directory found.`，
  `postinst` 已用 `|| true` 兜底，不再让 apt 安装失败。

`DEBIAN/control` 的关键字段：

```text
Depends: libasound2, libbrotli1, libbsd0, libc6, libexpat1, libfontconfig1, libfreetype6, libgl1, libglvnd0, libglx0, libmd0,
 libpng16-16, libuuid1, libx11-6, libxau6, libxcb1, libxdmcp6, libxext6, libxi6, libxrender1, libxtst6, xdg-utils, zlib1g,
 libgtk-3-0 | libgtk-3-0t64, libnss3, libgbm1, libxss1,
 libx11-xcb1, libxcomposite1, libxdamage1, libxrandr2,
 libatk-bridge2.0-0 | libatk-bridge2.0-0t64,
 libcups2 | libcups2t64, libdrm2
Recommends: ffmpeg, gstreamer1.0-plugins-base, gstreamer1.0-plugins-good,
 gstreamer1.0-libav, libnotify-bin, wmctrl, xdotool
```

## 启动

```bash
DISPLAY=:100 dbus-run-session -- /opt/yunx-desktop/bin/YunX-Linux
```

启动日志关键行：

```text
I/YunX-Render: 渲染后端：SOFTWARE
I/YunX-DB: database opened: /home/user/.yunx-desktop/yunx.db
I/YunX-JCEF: JCEF initialized (attempt 1)
```

结论：主窗口、设置页、支持开发页、关于页均可正常进入；截图见
[`screenshots/linux-main.png`](screenshots/linux-main.png)、
[`screenshots/linux-support.png`](screenshots/linux-support.png)、
[`screenshots/linux-about.png`](screenshots/linux-about.png)。

无头环境会出现 Chromium 的 D-Bus / GPU 初始化日志；这是 Xvfb 没有系统总线与 GPU 的正常降级，
不影响主窗口与 JCEF 初始化。

## 文案与入口检查

- 支持开发页显示：「本项目由于完全移植于 desktop 版，故不设置赞助渠道。如有想要赞助的想法，请赞助下面两位上游作者。」
- 支持开发页提供「Linux 版仓库」按钮，指向 `https://github.com/suz20090912-hub/YunX-Linux`。
- 关于页的「Linux 版源码仓库」卡同时显示 Linux 仓库与上游 `tidain/YunX-Desktop`。
- 设置页「支持开发」副标题为「Linux 版不设赞助渠道，可赞助上游作者」。
- Linux 更新检查查询 `suz20090912-hub/YunX-Linux` 的 Releases，只匹配 Linux 资产。
- `/usr/bin/yunx-desktop` 可直接启动应用。

## 一键安装更新复核（v1.3.4-linux.7，2026-11-24）

- 下载 Release .7 的 deb，`sha256sum` 与发布页 digest 一致（`41a34a6e…6e94`），`sudo dpkg -i` 成功。
- `DISPLAY=:100` 启动后 `xdotool search --name 云析 getwindowname` 返回「云析 YunX-Linux」，主窗口 1090x730 正常。
- 包内 `lib/app/desktop-*.jar` 含 `com/yunx/app/util/LinuxPackageInstaller.class`（一键安装入口已随包发布）。
- 系统存在 `/usr/bin/pkexec`；图形密码框依赖桌面会话的 polkit agent（GNOME/KDE 自带），
  无头验收环境无 agent，故一键安装的授权弹窗只能在真实桌面（如 Hyper-V 里的 Ubuntu 桌面）验证。
- 真实桌面点验通过（2026-11-24，用户 Hyper-V Ubuntu 24.04 GNOME）：.7 → .8 经下载页「安装更新」
  + pkexec 密码框完成升级；重启后关于页显示 `1.3.4-linux.8`，不再误报同版本更新。

## 待办

- 真实 GitHub Actions 的 tag Release 已实际触发验证（v1.3.4-linux.1 ~ .7 均产出 deb 资产）。
- 桌面菜单注册依赖真实桌面会话；当前无头环境已验证不会阻塞安装。
