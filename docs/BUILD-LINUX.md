# Linux 构建与打包

> 应用说明见 [README](../README.md)。本文只讲 Linux 版如何从源码编译、运行和打 `.deb`。

## 环境要求

已在 **Ubuntu 24.04 x86_64** 实机验证；其他 glibc 发行版可参考，未逐一验证。

| 依赖 | 说明 |
| --- | --- |
| JDK 17 | 必须是完整 JRE，不能只装 `openjdk-17-jdk-headless`；否则缺 `libawt_xawt.so`，Compose 启动报 `HeadlessException` |
| Gradle | 仓库已带 Gradle Wrapper（8.13），无需另装 |
| `ffmpeg` / `ffplay` | 可选；HLS 预览、DASH 合轨、FFplay 兼容模式使用 |
| `fakeroot` | `packageDeb` 打 Debian 包需要 |
| GTK / NSS / GBM | JCEF 与窗口渲染需要 `libgtk-3-0`、`libnss3`、`libgbm1` |
| GStreamer | JavaFX 媒体播放需要基础插件；缺失时部分编码回退 FFplay |

```bash
sudo apt update
sudo apt install openjdk-17-jre ffmpeg fakeroot \
  libgtk-3-0 libnss3 libgbm1 \
  gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
  gstreamer1.0-libav \
  libnotify-bin wmctrl xdotool
```

确认 Java：

```bash
java -version
# 应显示 17.x
```

如果系统有多个 JDK，构建前指定：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
```

## 编译

```bash
git clone https://github.com/suz20090912-hub/YunX-Linux.git
cd YunX-Linux
```

在仓库根目录执行：

```bash
chmod +x gradlew
./gradlew :desktop:compileKotlin --console=plain
```

内存紧张时：

```bash
GRADLE_OPTS='-Xmx1g -Dorg.gradle.daemon=false' \
  ./gradlew :desktop:compileKotlin --console=plain
```

## 开发运行

```bash
./gradlew :desktop:run --console=plain
```

Linux 下不会编译 Windows 托盘配色用的 `darkmode.dll`，构建脚本会自动跳过。

## 打 Debian 包

```bash
./gradlew :desktop:packageDeb --console=plain
```

产物：

```text
desktop/build/compose/binaries/main/deb/yunx-desktop_<版本>-1_amd64.deb
```

版本号来自根目录 `version.txt` 第一行；`Beta=1` 只影响应用内预览标记，不改变 deb 文件名。

安装测试：

```bash
sudo apt install ./desktop/build/compose/binaries/main/deb/yunx-desktop_*.deb
```

或：

```bash
sudo dpkg -i desktop/build/compose/binaries/main/deb/yunx-desktop_*.deb
sudo apt -f install
```

## 构建产物说明

| 路径 | 内容 |
| --- | --- |
| `desktop/build/compose/binaries/main/deb/` | `.deb` 安装包 |
| `desktop/build/compose/binaries/main/app/` | jpackage 展开的 app image |
| `desktop/build/` | 编译中间产物，**不要提交到 Git** |

`.deb` 体积较大（约 218 MB），主要来自 JCEF / Chromium 原生库与运行环境。发布时把 `.deb` 放到 GitHub Releases，不要提交进仓库。

## 仓库内必须保留的本地依赖

`desktop/libs/` 下三个 jar 随仓库分发，缺一个都可能导致构建失败：

| 文件 | 作用 |
| --- | --- |
| `material-color-utilities-1.0.0.jar` | 主题种子色；该库无公开 Maven 坐标 |
| `material-icons-extended-trimmed-1.7.3.jar` | 项目实际引用的 Material 图标裁剪包 |
| `sqlite-jdbc-3.47.1.0-win.jar` | Windows 构建用的 SQLite JDBC；Linux 构建改用 Maven 的 `org.xerial:sqlite-jdbc:3.47.1.0` |

## 常见问题

### `HeadlessException` / 缺 `libawt_xawt.so`

只装了 `openjdk-17-jdk-headless`。补装完整 JRE：

```bash
sudo apt install openjdk-17-jre
```

### `./gradlew: Permission denied`

```bash
chmod +x gradlew
```

### 首次启动很慢 / 内嵌浏览器空白

JCEF 首次运行会把原生库解压到缓存目录（约 325 MB），首次启动慢属于正常现象。若上次异常退出后一直空白，删除陈旧的 CEF 单例锁后重启；应用启动时也会自动清理失效锁。

### JavaFX 报 `Could not create player!`

部分容器/编码 jfxmedia 不支持。应用会自动切 FFplay 兼容模式；也可以手动安装系统 `ffmpeg`：

```bash
sudo apt install ffmpeg
```

### `packageDeb` 报缺少 `fakeroot`

```bash
sudo apt install fakeroot
```

### 托盘图标不显示

GNOME 等桌面没有 XEmbed tray owner 时，AWT 托盘可能挂不上。应用会回退到 `notify-send` 通知；设置页会自动隐藏托盘相关选项。

## 发布建议

1. 在 Linux 构建机执行 `./gradlew :desktop:packageDeb`。
2. 记录 `sha256sum`，随 `.deb` 一起放到 GitHub Releases。
3. Release 资产命名保持 `yunx-desktop_<版本>-1_amd64.deb`，应用内更新检查按 Linux 资产后缀匹配。
4. 不要提交 `desktop/build/`、`.gradle/`、`.kotlin/`、`*.deb` 或本机 `~/.yunx-desktop` 数据。

`patchDebDepends` 还会在 deb 中创建 `/usr/bin/yunx-desktop -> ../../opt/yunx-desktop/bin/YunX-Linux`，安装后可直接执行 `yunx-desktop`。

### 运行依赖自动补丁

`packageDeb` 末尾会自动执行 `patchDebDepends`：解包生成的 deb，向 `DEBIAN/control` 的
`Depends` 追加 GTK / NSS / GBM / X11 / ATK / cups / drm 等 JCEF 运行库，向 `Recommends`
追加 ffmpeg、GStreamer 插件、notify-send、wmctrl、xdotool。这样用户直接：

```bash
sudo apt install ./yunx-desktop_<版本>-1_amd64.deb
```

即可自动补齐主要运行依赖。可用下面的命令核对：

```bash
dpkg-deb -I desktop/build/compose/binaries/main/deb/*.deb | grep -E 'Depends|Recommends'
```

### 按 tag 自动发布

仓库包含 `.github/workflows/release-linux.yml`。推送形如 `v1.3.4-linux.1` 的 tag 后，CI 会：

1. 编译并执行 `packageDeb`；
2. 自动创建 GitHub Release；
3. 把 deb 上传为 Release 附件。

```bash
git tag v1.3.4-linux.1
git push origin v1.3.4-linux.1
```

Release 说明可用 `CHANGELOG.md` 对应段落，或让 GitHub 自动生成。
