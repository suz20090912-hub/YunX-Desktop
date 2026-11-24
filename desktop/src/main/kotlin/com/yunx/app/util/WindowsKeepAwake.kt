package com.yunx.app.util

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载期间阻止系统睡眠。
 *
 * - Windows：`kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)`；
 * - Linux：拉起一个 `systemd-inhibit --what=idle:sleep --mode=block sleep infinity` 子进程，
 *   子进程存活期间 systemd 会阻止空闲休眠；引用计数归零时销毁它；
 * - macOS / 其它：空操作。
 *
 * 为什么要做 Linux 版本：之前整体降级为空操作，下载几十 GB 的整盘链接时机器按空闲策略睡眠，
 * 下载会被挂断（表现为进度卡死、重试多次后失败），而设置里「下载时阻止睡眠」开关又是默认开启的。
 */
object WindowsKeepAwake {
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private const val TAG = "YunX-KeepAwake"
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    private val isLinux: Boolean
        get() = System.getProperty("os.name", "").lowercase().contains("linux")

    /** systemd-inhibit 的可执行路径；没有 systemd 的发行版为 null（自动降级为空操作） */
    private val systemdInhibit: File? by lazy {
        if (!isLinux) null
        else listOf("/usr/bin/systemd-inhibit", "/bin/systemd-inhibit")
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /** 非 Windows 时为 null（懒加载，避免类初始化即 Native.load） */
    private val kernel32: Kernel32? by lazy {
        if (!WindowsTitleBar.isWindows) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }
            .onFailure { Log.w(TAG, "kernel32 加载失败: ${it.message}") }
            .getOrNull()
    }

    /** 所有 SetThreadExecutionState 调用固定在此线程（状态线程本地，跨线程会失调） */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yunx-keep-awake") }
    private val refs = AtomicInteger(0)

    /** Linux 侧持有的 inhibit 子进程（引用归零时销毁） */
    private var inhibitProcess: Process? = null

    val isSupported: Boolean get() = kernel32 != null || systemdInhibit != null

    fun acquire() {
        if (kernel32 != null) {
            if (refs.getAndIncrement() == 0) {
                executor.execute {
                    runCatching { kernel32?.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED) }
                }
            }
            return
        }
        if (!isLinux || systemdInhibit == null) return
        val inhibitor = systemdInhibit ?: return
        if (refs.getAndIncrement() > 0) return
        synchronized(this) {
            if (inhibitProcess?.isAlive == true) return
            inhibitProcess = runCatching {
                ProcessBuilder(
                    inhibitor.absolutePath,
                    "--what=idle:sleep",
                    "--who=YunX-Desktop",
                    "--why=正在下载文件",
                    "--mode=block",
                    "sleep", "infinity"
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.onFailure {
                refs.set(0)
                Log.w(TAG, "systemd-inhibit 启动失败（下载期间可能仍会休眠）：${it.message}")
            }.getOrNull()
        }
    }

    fun release() {
        if (kernel32 != null) {
            if (refs.decrementAndGet() <= 0) {
                refs.set(0)
                executor.execute {
                    runCatching { kernel32?.SetThreadExecutionState(ES_CONTINUOUS) }
                }
            }
            return
        }
        if (!isLinux) return
        if (refs.decrementAndGet() > 0) return
        refs.set(0)
        synchronized(this) {
            val p = inhibitProcess
            inhibitProcess = null
            runCatching {
                // systemd-inhibit 会 fork 出 `sleep infinity`；只杀父进程会留下孤儿 sleep 继续持有锁
                p?.descendants()?.use { kids -> kids.forEach { runCatching { it.destroy() } } }
                p?.destroy()
            }
        }
    }
}
