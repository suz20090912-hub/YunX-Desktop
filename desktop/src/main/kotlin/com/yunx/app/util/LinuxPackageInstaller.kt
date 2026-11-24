package com.yunx.app.util

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Linux 一键安装 `.deb`（更新包 / 本地安装包）。
 *
 * 走 `pkexec /usr/bin/apt-get install -y <abs path>`：polkit 会弹**系统图形密码框**授权，
 * 用户输一次密码即完成覆盖升级，全程不需要开终端；与 Windows 安装程序弹 UAC 是同一思路。
 * 无图形 polkit agent 的环境（纯无头）会授权失败，调用方把原因展示给用户即可。
 *
 * 阻塞调用：请在 IO 线程执行（见下载页「安装更新」按钮）。
 */
object LinuxPackageInstaller {

    /** 安装结果：ok + 可直接展示给用户的中文文案 */
    data class Outcome(val ok: Boolean, val message: String)

    fun installDeb(file: File): Outcome {
        if (!file.isFile) return Outcome(false, "安装包不存在：${file.name}")
        return try {
            val proc = ProcessBuilder("pkexec", "/usr/bin/apt-get", "install", "-y", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, TimeUnit.MINUTES)) {
                proc.destroyForcibly()
                return Outcome(false, "安装超时（10 分钟），请改用终端 sudo apt install 安装")
            }
            when (val code = proc.exitValue()) {
                0 -> Outcome(true, "安装完成，重启云析即可使用新版本")
                // pkexec 约定：126 = 用户取消授权对话框；127 = 授权失败（密码错 / 无权限）
                126 -> Outcome(false, "已取消授权，未安装")
                127 -> Outcome(false, "授权失败（密码错误或无管理员权限），未安装")
                else -> Outcome(false, "安装失败（exit $code）：${output.trim().takeLast(300)}")
            }
        } catch (e: Exception) {
            Outcome(false, "无法启动安装器（pkexec 缺失或无图形授权环境）：${e.message}")
        }
    }
}