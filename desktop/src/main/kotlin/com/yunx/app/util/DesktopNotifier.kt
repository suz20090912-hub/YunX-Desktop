package com.yunx.app.util

import java.util.concurrent.TimeUnit

/**
 * Linux 桌面通知兜底：走 `notify-send`（libnotify / GNOME / KDE 均自带）。
 *
 * Windows 走托盘气泡或 WinRT toast（见 TrayManager / WindowsToastNotifier），
 * 这里只处理 Linux，命令不存在时静默记日志，不影响主流程。
 */
object DesktopNotifier {

    private const val TAG = "YunX-Notify"
    private val osName = System.getProperty("os.name", "").lowercase()
    private val isLinux = osName.contains("linux")

    val isSupported: Boolean
        get() = isLinux && locateNotifySend() != null

    /** 发送一条桌面通知；失败只记日志，绝不抛出 */
    fun show(title: String, message: String) {
        if (!isLinux) return
        val bin = locateNotifySend() ?: run {
            Log.w(TAG, "notify-send 不存在，跳过通知：$title")
            return
        }
        runCatching {
            val p = ProcessBuilder(
                bin, "-a", "云析 YunX-Linux", "-t", "5000", title, message
            ).redirectErrorStream(true).start()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                Log.w(TAG, "notify-send 超时：$title")
                return
            }
            if (p.exitValue() != 0) {
                Log.w(TAG, "notify-send 退出码 ${p.exitValue()}：$title")
            }
        }.onFailure { Log.w(TAG, "notify-send 调用失败：${it.message}") }
    }

    private fun locateNotifySend(): String? =
        listOf("/usr/bin/notify-send", "/bin/notify-send", "/usr/local/bin/notify-send")
            .firstOrNull { java.io.File(it).canExecute() }
}
