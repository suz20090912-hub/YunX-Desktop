package com.yunx.app.util

import java.awt.Image
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘管理器。
 *
 * 右键菜单用 JNA 调 Win32 TrackPopupMenu（CreatePopupMenu + AppendMenuW），
 * 中文正常、暗色跟随系统。不用 Swing、不自绘窗口。
 */
object TrayManager {

    private const val TAG = "YunX-Tray"

    private var trayIcon: TrayIcon? = null

    /**
     * Linux 上 `SystemTray.isSupported()` 可能为 true，但真正 `add` 时桌面没有 XEmbed
     * tray owner，AWT 会在 EDT 的 XTrayIconPeer 里抛 UnsupportedOperationException。
     * 一旦确认失败，就永久回退到 notify-send，避免反复尝试 / 反复弹崩溃日志。
     */
    @Volatile
    private var trayFailed = false

    /** 是否已安装托盘图标 */
    val isInstalled: Boolean
        get() = trayIcon != null && !trayFailed

    /** 当前桌面环境是否提供系统托盘（GNOME 默认不带，返回 false） */
    val isSupported: Boolean
        get() = !trayFailed && runCatching { SystemTray.isSupported() }.getOrDefault(false)

    /** 是否 Windows：只有 Windows 需要空 PopupMenu 才能收到右键回调 */
    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * 安装托盘图标。
     * @param image 图标
     * @param onLeftClick 左键单击回调
     * @param onRightClick 右键单击回调（屏幕坐标 x, y）
     */
    fun install(
        image: BufferedImage,
        onLeftClick: () -> Unit,
        onRightClick: (Int, Int) -> Unit
    ) {
        if (!isSupported) return
        if (trayIcon != null) uninstall()

        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Linux").apply {
            isImageAutoSize = true
            // 空 PopupMenu：Windows 必须设才能收到右键事件；Linux 设空菜单会弹出空白菜单，反而不弹回调
            if (isWindows) popupMenu = PopupMenu()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onLeftClick()
                }
                override fun mousePressed(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON3) {
                        e.consume()
                        onRightClick(e.xOnScreen, e.yOnScreen)
                    }
                }
            })
        }
        val added = runCatching {
            SystemTray.getSystemTray().add(icon)
            true
        }.getOrElse {
            Log.w(TAG, "托盘安装失败，回退桌面通知：${it.message}")
            false
        }
        if (!added) {
            onTrayUnavailable()
            return
        }
        trayIcon = icon
        verifyInstalled(icon)
    }

    /**
     * AWT 的 XTrayIconPeer 是在 EDT 异步创建的；`add()` 返回不代表图标真的挂上了。
     * 延迟检查一次 trayIcons，没挂上就清掉状态，避免「isInstalled=true 但窗口隐藏后无法唤回」。
     */
    private fun verifyInstalled(icon: TrayIcon) {
        Thread {
            try {
                Thread.sleep(2_000)
                val icons = runCatching { SystemTray.getSystemTray().trayIcons }.getOrNull()
                if (icons == null || icons.none { it === icon }) {
                    onTrayUnavailable()
                }
            } catch (_: Throwable) {
                onTrayUnavailable()
            }
        }.apply {
            isDaemon = true
            name = "yunx-tray-verify"
            start()
        }
    }

    /** 由 AWT 未捕获异常处理器调用：托盘不可用不是应用级崩溃，直接回退桌面通知 */
    fun onTrayUnavailable() {
        if (!trayFailed) {
            trayFailed = true
            Log.w(TAG, "系统托盘不可用，已回退桌面通知")
        }
        trayIcon = null
    }

    /** 移除托盘图标 */
    fun uninstall() {
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
            trayIcon = null
        }
    }

    /**
     * 显示系统通知。
     *
     * 走 AWT 托盘图标的原生气泡（Windows 10/11 会归入通知中心）。
     * 注意：托盘图标必须处于可见状态，否则 Windows 不会显示。
     * 这里不能用 Compose 的 TrayState —— 托盘图标已改为手写 AWT TrayIcon，没有 TrayState 实例。
     */
    fun showNotification(title: String, message: String) {
        val icon = trayIcon
        if (icon == null || trayFailed) {
            // 没有托盘图标（例如 GNOME 无托盘）时走 notify-send，避免通知彻底丢失
            DesktopNotifier.show(title, message)
            return
        }
        runCatching { icon.displayMessage(title, message, TrayIcon.MessageType.NONE) }
            .onFailure { Log.w("YunX-Tray", "showNotification failed: ${it.message}") }
    }

    /** 从 classpath 加载 icon.png */
    fun loadTrayIcon(): BufferedImage? = runCatching {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        ImageIO.read(stream)
    }.getOrNull()
}
