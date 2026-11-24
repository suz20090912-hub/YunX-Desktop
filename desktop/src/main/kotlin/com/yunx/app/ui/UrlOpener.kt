package com.yunx.app.ui

import com.yunx.app.util.DesktopActions

/**
 * 打开外链并给反馈：成功提示"已在默认浏览器打开"（Linux 浏览器可能不自动前台，
 * 用户至少知道点击生效了），失败提示手动复制。所有外链按钮统一走这里，别再裸调 openUrl。
 */
fun openUrlWithFeedback(url: String) {
    if (DesktopActions.openUrl(url)) {
        SnackbarController.show("已在默认浏览器打开")
    } else {
        SnackbarController.show("打开链接失败，请手动复制：$url")
    }
}