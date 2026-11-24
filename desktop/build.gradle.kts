import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ===================== 多平台移植（linux-port 分支） =====================
// 宿主平台判定：原生依赖（JavaFX / JCEF / SQLite）按平台挑 classifier，
// 打包目标格式也随之切换。Windows 侧行为保持不变。
val buildOs = System.getProperty("os.name").lowercase()
val buildArch = System.getProperty("os.arch").lowercase()
val isWindowsHost = buildOs.contains("win")
val isMacHost = buildOs.contains("mac")
val isLinuxHost = !isWindowsHost && !isMacHost
val jfxClassifier = when {
    isWindowsHost -> "win"
    isMacHost -> if (buildArch.contains("aarch64")) "mac-aarch64" else "mac"
    buildArch.contains("aarch64") -> "linux-aarch64"
    else -> "linux"
}
// 与 Windows 版同一个 jcef 构建号，换平台只换 artifact 前缀
val JCEF_VERSION = "jcef-1770317+cef-132.3.1+g144febe+chromium-132.0.6834.83"
val jcefNativesCoordinate = when {
    isWindowsHost -> "me.friwi:jcef-natives-windows-amd64:$JCEF_VERSION"
    isMacHost && buildArch.contains("aarch64") -> "me.friwi:jcef-natives-macos-arm64:$JCEF_VERSION"
    isMacHost -> "me.friwi:jcef-natives-macos-x64:$JCEF_VERSION"
    buildArch.contains("aarch64") -> "me.friwi:jcef-natives-linux-arm64:$JCEF_VERSION"
    else -> "me.friwi:jcef-natives-linux-amd64:$JCEF_VERSION"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // ★ 图标：**不要**改回 compose.materialIconsExtended —— 完整包有 36 MB（5000+ 图标），
    //   本项目只用到 92 个。libs/material-icons-extended-trimmed-1.7.3.jar 是从官方
    //   material-icons-extended-desktop-1.7.3.jar 里按实际引用裁出来的（219 KB）。
    //   新增/删除图标后需要重新生成：见 docs/BUILD.md「图标裁剪包」一节。
    //   （material-icons-core 仍由 compose.material3 传递引入，核心图标不受影响）
    implementation(files("libs/material-icons-extended-trimmed-1.7.3.jar"))

    // 多平台版 lifecycle（Compose Desktop 可用 viewModel()）
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // BackHandler 由本项目自带桌面实现（ui/BackHandler.kt，Escape 键）

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Room -> 纯 JDBC SQLite
    // ★ 用裁剪后的本地 jar：官方包 13.6 MB，含六个平台的原生库，本项目只跑 Windows。
    //   只保留 org/sqlite/native/Windows（x86/x86_64/arm/arm64），其余代码与 META-INF 原样保留。
    // Windows 用仓库里裁过的本地 jar（只含 Windows 原生库）；其它平台用官方全量包
    if (isWindowsHost) implementation(files("libs/sqlite-jdbc-3.47.1.0-win.jar"))
    else implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Android 内置 org.json 的 JVM 等价物
    implementation("org.json:json:20240303")
    // 主题种子色（material-color-utilities 无公开仓库坐标；从 material 1.14.0 AAR 解包 classes.jar 本地引入）
    implementation(files("libs/material-color-utilities-1.0.0.jar"))

    // JavaFX MediaView 播放器（M6）：JFXPanel 经 SwingPanel 嵌入 Compose。
    // 版本跟随 jvmToolchain(17)；发布目标只有 Windows，直接用 :win classifier（jar 内含原生库，
    // 首次运行由 JavaFX 自动解到用户目录缓存，jpackage 无需额外处理）
    implementation("org.openjfx:javafx-base:17.0.13:$jfxClassifier")
    implementation("org.openjfx:javafx-graphics:17.0.13:$jfxClassifier")
    implementation("org.openjfx:javafx-media:17.0.13:$jfxClassifier")
    implementation("org.openjfx:javafx-swing:17.0.13:$jfxClassifier")

    // GFM Markdown 渲染（README / 更新说明）。注意：必须停留在 0.33.x ——
    // 0.34.0 起改用 Compose 1.8+ 的 BasicText 签名（含 TextAutoSize），与项目锁定的
    // Compose Multiplatform 1.7.3 二进制不兼容，会在运行时抛 NoSuchMethodError
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")

    // DASH 合轨 / MP4 重封装（纯 Java，无原生库）。
    // 注意：1.9.x 把库拆成了两个 artifact —— isoparser 只有 box 层，
    // MovieCreator / DefaultMp4Builder 在 muxer 里，且 muxer 会传递依赖同版本 isoparser。
    implementation("org.mp4parser:muxer:1.9.56") {
        // muxer 的 POM 把 junit 声明成 compile 依赖，运行期用不到，排除以免进安装包
        exclude(group = "junit", module = "junit")
    }

    // 方案 A：一键导入浏览器 Cookie（DPAPI 解密 Chromium 系浏览器的加密 Cookie）
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // 方案 B：内嵌 Chromium 浏览器（JCEF），jcefmaven 负责原生库装载
    implementation("me.friwi:jcefmaven:132.3.1") {
        // jogl / gluegen 只服务于 JCEF 的离屏渲染（OSR）；本项目用窗口态浏览器
        // （createBrowser + createImmediately），不打进安装包可省约 5.4 MB。
        // ★ 若将来改用 OSR（无窗口渲染），必须去掉这两条排除。
        exclude(group = "me.friwi", module = "jogl-all")
        exclude(group = "me.friwi", module = "gluegen-rt")
    }
    // ★ 原生库只在开发运行（gradle run）时由 jcefmaven 从 classpath 里取；
    //   发布包不随带这个 120MB 的 jar，改由打包脚本用 JcefNativesPacker 重打成
    //   app\jcef-natives.tar.gz（裁剪语言包），首次使用时由 JcefNativesInstaller 解开。
    //   见 portable-package.ps1 [2c/5] 与 ui/jcef/JcefNativesInstaller.kt。
    implementation(jcefNativesCoordinate)

    // tar.gz 读写：CEF 原生库发布包（打包期重打成 gzip，运行期自解包）
    // jcefmaven 已传递引入 commons-compress，这里显式声明版本以保证稳定
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(17)
}

// ---- 版本号单一来源：仓库根目录 version.txt（jpackage / 便携包 / 安装包 / 应用内版本全部由它派生）----
// 格式：第一行版本号（如 1.3.1）；第二行 Beta=1 / Beta=0（1=预览版，0 或缺省=正式版）
val versionLines = rootProject.file("version.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
val appVersion = versionLines.firstOrNull().orEmpty()
require(appVersion.matches(Regex("""\d+(\.\d+)*"""))) { "version.txt 内容非法: '$appVersion'" }
val appIsPreview = versionLines.drop(1).firstOrNull()?.let { line ->
    when (line.substringAfter('=', "").trim()) {
        "1" -> true
        "0" -> false
        // 兼容旧写法（preview / beta）
        else -> line.lowercase() in setOf("preview", "beta")
    }
} ?: false

// 生成 Kotlin 常量供 UpdateChecker 使用，避免应用内再维护一份硬编码
val generateAppVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
    val version = appVersion
    val isPreview = appIsPreview.toString()
    outputs.dir(outDir)
    inputs.property("version", version)
    inputs.property("isPreview", isPreview)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/yunx/app")
        pkgDir.mkdirs()
        pkgDir.resolve("AppVersion.kt").writeText(
            """
            |package com.yunx.app
            |
            |/** 由根目录 version.txt 生成，请勿手改 */
            |internal const val APP_VERSION = "$version"
            |
            |/** 是否为预览版（version.txt 第二行 Beta=1）；预览版在关于页显示 Beta 标记 */
            |internal const val APP_IS_PREVIEW = $isPreview
            |""".trimMargin() + "\n"
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateAppVersion)

// ---- 遮罩选位器 EXE 打进 jar 资源 ----
// picker\PositionPicker.cs 由 run.ps1 / portable-package.ps1 用 csc 编到仓库根 build\ 下，
// 这里把它作为资源塞进 jar 的 yunx\ 前缀；运行期由 util/PositionPicker.kt 释放到 %TEMP% 再执行，
// 因此发布包目录里不再出现这个原生 exe。（文件不存在时 from() 会静默跳过）
tasks.named<ProcessResources>("processResources") {
    val pickerExe = rootProject.layout.projectDirectory.file("build/YunX-PositionPicker.exe")
    from(pickerExe) { into("yunx") }
}

tasks.withType<Test> {
    useJUnit()
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

// 本机工作目录含非 ASCII 字符时，Gradle 测试 worker 的 classpath 传递会乱码（测试类 ClassNotFound），
// `gradle test` 在 E:\桌面 下不可用。这个任务在**当前 JVM 内**直接跑 JUnit（不分叉），
// 作为本机的替代入口：`gradle :desktop:testInProcess`。CI（ASCII 路径）仍走标准 `test`。
val testInProcessClasspath = the<SourceSetContainer>()["test"].runtimeClasspath
tasks.register<JavaExec>("testInProcess") {
    group = "verification"
    description = "在当前 JVM 内运行 JUnit 测试（绕开非 ASCII 路径下测试 worker 的 classpath 问题）"
    dependsOn("compileTestKotlin")
    mainClass.set("org.junit.runner.JUnitCore")
    classpath = testInProcessClasspath
    args = listOf(
        "com.yunx.app.data.network.VideoLinkParserTest",
        "com.yunx.app.data.network.ShareLinkParserTest",
        "com.yunx.app.data.network.StarSeaApiTest",
        "com.yunx.app.data.download.HlsRequestPolicyTest",
        "com.yunx.app.data.download.HttpRangePolicyTest",
        "com.yunx.app.data.download.ElasticAllocatorTest",
        "com.yunx.app.data.download.DownloadPathPolicyTest",
        "com.yunx.app.data.download.DownloadGroupingTest",
        "com.yunx.app.data.download.DownloadListLayoutTest",
        "com.yunx.app.data.download.DirectPartManifestTest",
        "com.yunx.app.data.download.DownloadSaverTest",
        "com.yunx.app.data.download.ChunkDownloaderDirectTest",
        "com.yunx.app.data.download.DownloadCacheMaintenanceTest",
        "com.yunx.app.data.download.DownloadManagerDirectIntegrationTest",
        "com.yunx.app.data.db.SecureAccountDaosTest",
        "com.yunx.app.data.storage.StorageMigratorTest",
        "com.yunx.app.data.security.DbKeyStoreTest",
        "com.yunx.app.ui.login.XunleiVerificationPolicyTest",
        "com.yunx.app.data.network.XunleiWebCredentialTest",
        "com.yunx.app.util.LogRedactorTest",
        "com.yunx.app.ui.viewmodel.BaiduCloudDownloadHeadersTest"
    )
}

// 编译 native/darkmode.cpp → build/native/darkmode.dll（托盘原生菜单暗色桥接层）
val nativeSrcDir = file("native")
val nativeOutDir = layout.buildDirectory.dir("native").get().asFile
val darkModeSrc = File(nativeSrcDir, "darkmode.cpp")
val darkModeOut = File(nativeOutDir, "darkmode.dll")

// 定位 MSVC 的 vcvars64.bat（cl 直接调会找不到 windows.h / CRT 库，必须先 call 它）
fun findMsvcVcvars(): File? {
    val roots = listOf(
        "C:/Program Files/Microsoft Visual Studio/2022",
        "C:/Program Files (x86)/Microsoft Visual Studio/2022",
        "C:/Program Files/Microsoft Visual Studio/2019",
        "C:/Program Files (x86)/Microsoft Visual Studio/2019"
    )
    for (root in roots) {
        val f = File(root)
        if (!f.isDirectory) continue
        val editions = f.listFiles() ?: continue
        for (ed in editions) {
            val bat = File(ed, "VC/Auxiliary/Build/vcvars64.bat")
            if (bat.isFile) return bat
        }
    }
    return null
}

val compileDarkMode: TaskProvider<out Task> = if (isWindowsHost) tasks.register<Exec>("compileDarkMode") {
    val vcvars = findMsvcVcvars()
    inputs.file(darkModeSrc)
    outputs.file(darkModeOut)
    doFirst {
        nativeOutDir.mkdirs()
    }
    if (vcvars != null) {
        commandLine(
            "cmd", "/c",
            "call \"${vcvars.absolutePath}\" >nul && " +
                "cl /nologo /utf-8 /LD \"${darkModeSrc.absolutePath}\" " +
                "/Fe:\"${darkModeOut.absolutePath}\" " +
                "/Fo:\"${nativeOutDir.absolutePath}\\\\\" " +
                "/link /NOENTRY kernel32.lib user32.lib"
        )
    } else {
        // 回退 MinGW
        commandLine(
            "g++", "-shared", "-o", darkModeOut.absolutePath,
            darkModeSrc.absolutePath, "-lkernel32", "-luser32"
        )
    }
} else {
    // Linux / macOS 没有 uxtheme，托盘菜单暗色桥接层整块跳过：
    // Kotlin 侧 DarkMode 会退化为「读不到系统配色 → 按浅色」，不影响启动。
    tasks.register("compileDarkMode") { doLast { println("[linux-port] 非 Windows 宿主，跳过 darkmode.dll") } }
}

// 开发运行（:desktop:run）时把 DLL 目录并入 java.library.path，并保留 JDK bin（jawt 等原生库）
tasks.withType<JavaExec> {
    dependsOn(compileDarkMode)
    val jvmBin = File(System.getProperty("java.home"), "bin").absolutePath
    systemProperty("java.library.path", nativeOutDir.absolutePath + File.pathSeparator + jvmBin)
}

// 收集运行时依赖 jar 路径（供便携版 app-image 打包）
tasks.register("printRuntimeClasspath") {
    doLast {
        val cp = configurations.runtimeClasspath.get().files
            .joinToString(";") { it.absolutePath }
        println("RUNTIME_CP=$cp")
    }
}

// 导出全部运行时依赖 jar 到 build/exportLibs（供便携版打包脚本使用，规避 gradle 缓存路径漂移）
// ★ 排除 jcef 原生包：发布包改用它重打出来的 jcef-natives.tar.gz（见 JcefNativesPacker），
//   开发运行（:desktop:run）仍直接从运行时 classpath 取该 jar，不受影响。
tasks.register<Sync>("exportRuntimeLibs") {
    from(configurations.runtimeClasspath) { exclude("jcef-natives-*.jar") }
    into(layout.projectDirectory.dir("build/exportLibs"))
}

// 单独导出 jcef 原生包，供打包脚本重打成 tar.gz
tasks.register<Sync>("exportJcefNatives") {
    from(configurations.runtimeClasspath) { include("jcef-natives-*.jar") }
    into(layout.projectDirectory.dir("build/jcefNatives"))
}

// ===================== Debian 运行依赖自动补丁 =====================
// jpackage 生成的 control 只包含 jlink 探测到的底层库，不会声明 JCEF/GTK、
// JavaFX/GStreamer 等运行时才加载的依赖。这里在 packageDeb 之后原地重写
// DEBIAN/control，保证 `apt install ./xxx.deb` 能自动补齐运行环境。
// 只在 Linux 宿主执行；Windows 构建不受影响。
val debRuntimeDepends = listOf(
    "libgtk-3-0 | libgtk-3-0t64",
    "libnss3",
    "libgbm1",
    "libxss1",
    "libx11-xcb1",
    "libxcomposite1",
    "libxdamage1",
    "libxrandr2",
    "libatk-bridge2.0-0 | libatk-bridge2.0-0t64",
    "libcups2 | libcups2t64",
    "libdrm2"
)
val debRuntimeRecommends = listOf(
    "ffmpeg",
    "gstreamer1.0-plugins-base",
    "gstreamer1.0-plugins-good",
    "gstreamer1.0-libav",
    "libnotify-bin",
    "wmctrl",
    "xdotool"
)

val patchDebDepends = tasks.register("patchDebDepends") {
    group = "distribution"
    description = "为生成的 deb 补充 GTK / JCEF / JavaFX 运行依赖"
    doLast {
        if (!isLinuxHost) {
            println("[linux-port] 非 Linux 宿主，跳过 deb 依赖补丁")
            return@doLast
        }
        val debDir = layout.buildDirectory.dir("compose/binaries/main/deb").get().asFile
        val deb = debDir.listFiles { file -> file.isFile && file.name.endsWith(".deb") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("未找到 deb 产物: ${debDir.absolutePath}")
        val workDir = layout.buildDirectory.dir("compose/tmp/deb-depends").get().asFile
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        fun runCommand(vararg command: String) {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0) {
                error("命令失败($exit): ${command.joinToString(" ")}\n$output")
            }
            if (output.isNotBlank()) println(output.trim())
        }

        runCommand("dpkg-deb", "-R", deb.absolutePath, workDir.absolutePath)
        val controlFile = File(workDir, "DEBIAN/control")
        val lines = controlFile.readLines().toMutableList()

        fun mergeField(field: String, additions: List<String>) {
            val index = lines.indexOfFirst { it.startsWith("$field:") }
            val existing = if (index >= 0) {
                lines[index].substringAfter(":").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            val merged = (existing + additions).distinct()
            if (index >= 0) {
                lines[index] = "$field: ${merged.joinToString(", ")}"
            } else {
                lines.add("$field: ${merged.joinToString(", ")}")
            }
        }

        mergeField("Depends", debRuntimeDepends)
        mergeField("Recommends", debRuntimeRecommends)
        // Ubuntu 24.04 的 jpackage 会写入 t64 包名；这里归一化为带备选的依赖，
        // 让同一份 deb 在 22.04 / 24.04 都能解析（CI 固定 22.04 构建，兜底仍保留）。
        val normalized = lines
            .map {
                it.replace("libasound2t64", "libasound2 | libasound2t64")
                    .replace("libpng16-16t64", "libpng16-16 | libpng16-16t64")
            }
            .filter { it.isNotBlank() }
        controlFile.writeText(normalized.joinToString("\n") + "\n")

        // 无桌面会话 / 无 system menu 的环境下 xdg-desktop-menu 会返回非 0，
        // 不应因此让整个 apt 安装失败；安装菜单失败时仅跳过菜单注册。
        listOf("postinst", "prerm").forEach { name ->
            val script = File(workDir, "DEBIAN/$name")
            if (script.exists()) {
                script.writeText(
                    script.readText()
                        .replace(Regex("(?m)^(xdg-desktop-menu (?:install|uninstall) .+)$"), "$1 || true")
                )
            }
        }

        // 提供 /usr/bin/yunx-desktop 命令；jpackage 默认只装到 /opt/yunx-desktop/bin。
        val cliDir = File(workDir, "usr/bin")
        cliDir.mkdirs()
        val cliLink = File(cliDir, "yunx-desktop")
        if (cliLink.exists()) cliLink.delete()
        runCommand("ln", "-s", "../../opt/yunx-desktop/bin/YunX-Linux", cliLink.absolutePath)

        runCommand("dpkg-deb", "--build", "--root-owner-group", workDir.absolutePath, deb.absolutePath)
        workDir.deleteRecursively()
        println("[linux-port] 已补充 deb 运行依赖: ${deb.name}")
    }
}

tasks.matching { it.name == "packageDeb" }.configureEach {
    finalizedBy(patchDebDepends)
}

compose.desktop {
    application {
        mainClass = "com.yunx.app.MainKt"
        nativeDistributions {
            targetFormats(
                when {
                    isWindowsHost -> TargetFormat.Exe
                    isMacHost -> TargetFormat.Dmg
                    else -> TargetFormat.Deb
                }
            )
            // sqlite-jdbc 通过反射加载 java.sql.Driver，jlink 自动检测不到，必须显式声明；
            // jdk.httpserver 是视频预览的本地回环代理（com.sun.net.httpserver），
            // jdk.unsupported.desktop 是 JavaFX 与 Swing 互操作（JFXPanel 里的 jdk.swing.interop）——
            // 两者 jdeps 都探测不到，漏了它们安装包里的预览会一律失败（无法预览 / 播放器初始化失败）
            modules("java.sql", "java.naming", "jdk.httpserver", "jdk.unsupported.desktop")
            // 每用户安装（%LOCALAPPDATA%\Programs），无需管理员权限
            if (isWindowsHost) {
                windows {
                    perUserInstall = true
                }
            }
            if (!isWindowsHost && !isMacHost) {
                // jpackage 的 Linux 包名必须是全小写、无空格的 Debian 风格
                linux {
                    packageName = "yunx-desktop"
                    menuGroup = "Utility"
                    shortcut = true
                }
            }
            packageName = "YunX-Linux"
            packageVersion = appVersion
            // 注意：jpackage 参数文件解析不支持非 ASCII 描述（本机报 "Input length = 1"），描述保持纯英文
            description = "YunX-Linux - netdisk share-link parser and high-speed downloader (Linux port of YunX for Android)"
            vendor = "YunX-Linux"
            copyright = "Copyright (C) 2026 tidain"
        }
    }
}
