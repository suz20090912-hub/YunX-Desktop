package com.yunx.app.util

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Linux 单实例：把已有实例的主窗口激活到前台。
 *
 * 为什么不用 AWT：AWT 只能操作当前 JVM 的窗口；第二个实例和第一个实例是两个进程。
 * 这里优先走 X11（XWayland 下同样适用），按窗口标题找到目标后 Raise + SetInputFocus，
 * 不依赖外部命令；找不到再尝试 wmctrl / xdotool。
 * 纯 Wayland 原生窗口对 X11 不可见，此时返回 false，由调用方退化为桌面通知。
 */
object LinuxWindowFocus {

    private const val TAG = "YunX-WindowFocus"
    private const val MAX_DEPTH = 8
    private const val MAX_WINDOWS = 4000
    private const val REVERT_TO_PARENT = 2
    private const val CURRENT_TIME = 0L

    /** JNA 的 X11 映射没有暴露这两个函数；Native.load 直接取 Xlib 符号，避免再引依赖 */
    private interface XLib : Library {
        fun XRaiseWindow(display: Pointer, window: Long): Int
        fun XSetInputFocus(display: Pointer, window: Long, revertTo: Int, time: Long): Int

        companion object {
            val INSTANCE: XLib = Native.load("X11", XLib::class.java)
        }
    }

    fun activate(title: String): Boolean {
        if (activateViaX11(title)) return true
        if (runExternal(listOf("wmctrl", "-a", title))) return true
        if (runExternal(listOf("xdotool", "search", "--name", title, "windowactivate", "--sync"))) return true
        return false
    }

    private fun activateViaX11(title: String): Boolean = runCatching {
        val x11 = X11.INSTANCE
        val display = x11.XOpenDisplay(null) ?: return false
        try {
            val root = x11.XDefaultRootWindow(display)
            val counter = intArrayOf(0)
            val target = findWindow(x11, display, root, title, 0, counter) ?: return false
            // 先 map 再 raise：最小化 / 被遮挡的窗口也能回来
            runCatching { x11.XMapRaised(display, target) }
            XLib.INSTANCE.XRaiseWindow(display.pointer, target.toLong())
            XLib.INSTANCE.XSetInputFocus(display.pointer, target.toLong(), REVERT_TO_PARENT, CURRENT_TIME)
            x11.XFlush(display)
            true
        } finally {
            runCatching { x11.XCloseDisplay(display) }
        }
    }.getOrElse {
        Log.d(TAG, "X11 激活失败：${it.message}")
        false
    }

    private fun findWindow(
        x11: X11,
        display: X11.Display,
        window: X11.Window,
        title: String,
        depth: Int,
        counter: IntArray
    ): X11.Window? {
        if (depth > MAX_DEPTH || counter[0] >= MAX_WINDOWS) return null
        counter[0]++
        if (windowTitle(x11, display, window) == title) return window

        val rootReturn = X11.WindowByReference()
        val parentReturn = X11.WindowByReference()
        val children = PointerByReference()
        val childCount = IntByReference()
        if (x11.XQueryTree(display, window, rootReturn, parentReturn, children, childCount) == 0) {
            return null
        }
        val ptr = children.value ?: return null
        try {
            for (i in 0 until childCount.value) {
                val child = X11.Window(ptr.getNativeLong(i.toLong() * NativeLong.SIZE.toLong()).toLong())
                findWindow(x11, display, child, title, depth + 1, counter)?.let { return it }
            }
        } finally {
            runCatching { x11.XFree(ptr) }
        }
        return null
    }

    private fun windowTitle(x11: X11, display: X11.Display, window: X11.Window): String? {
        // 优先 _NET_WM_NAME（UTF-8，中文标题不会乱码）。
        // 注意 JNA 把 boolean true 映射成 0xff，XInternAtom 的 only_if_exists 会报 BadValue，
        // 所以这里必须传 false（不存在就创建，语义等价）。
        val nameAtom = x11.XInternAtom(display, "_NET_WM_NAME", false)
        val utf8Atom = x11.XInternAtom(display, "UTF8_STRING", false)
        if (nameAtom.toLong() != 0L) {
            val actualType = X11.AtomByReference()
            val actualFormat = IntByReference()
            val items = NativeLongByReference()
            val bytesAfter = NativeLongByReference()
            val data = PointerByReference()
            val rc = x11.XGetWindowProperty(
                display, window, nameAtom,
                NativeLong(0), NativeLong(1024), false, utf8Atom,
                actualType, actualFormat, items, bytesAfter, data
            )
            val ptr = data.value
            if (rc == 0 && ptr != null && items.value.toInt() > 0) {
                return try {
                    String(ptr.getByteArray(0, items.value.toInt()), Charsets.UTF_8)
                } finally {
                    runCatching { x11.XFree(ptr) }
                }
            }
            if (ptr != null) runCatching { x11.XFree(ptr) }
        }

        // 退回 WM_NAME（老窗口 / 某些 Java 窗口只写这个）
        val name = PointerByReference()
        if (x11.XFetchName(display, window, name) != 0 && name.value != null) {
            return try {
                name.value.getString(0)
            } finally {
                runCatching { x11.XFree(name.value) }
            }
        }
        return null
    }

    private fun runExternal(command: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        val ok = process.exitValue() == 0
        if (!ok) {
            val detail = process.inputStream.bufferedReader().use { it.readText() }.trim().take(200)
            Log.d(TAG, "${command.first()} 激活失败：$detail")
        }
        ok
    }.getOrElse {
        Log.d(TAG, "${command.first()} 不可用：${it.message}")
        false
    }
}
