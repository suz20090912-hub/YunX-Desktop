package com.yunx.app.data.announcement

/**
 * 应用内公告（Linux 版内置公告）。
 *
 * Linux 版不再连接上游安卓版 / Windows 版的远程公告服务：
 * 上游服务只发布上游自己的公告，且断网 / 被代理劫持时公告页只会报连接超时。
 * 这里改为**随包内置**的 Linux 版公告，列表 / 详情 / 启动弹窗 / 未读角标全部离线可用，
 * 对外接口（[fetchPage] / [fetchDetail] / [Page] / [Result] / [Announcement]）保持原签名，
 * ViewModel 与 UI 无需改动。
 *
 * 更新公告 = 改本文件的 [BUILTIN] 列表并发新版；已读记录仍由 [AnnouncementReadStore] 本地维护，
 * 新 id 会自动算未读并触发启动弹窗。
 */
object AnnouncementApi {

    /** 列表分页大小（保持原接口口径；内置公告一次取满，不存在翻页） */
    const val PAGE_SIZE = 100

    /** 发布者（服务端 `publisher` 对象的本地等价物） */
    data class Publisher(val name: String, val avatarUrl: String?)

    /**
     * 公告对象（字段与原远程接口一致）。
     * [content] 只有详情接口返回；内置公告不带封面与图集。
     */
    data class Announcement(
        val id: String,
        val title: String,
        val summary: String,
        val content: String?,
        val coverImage: String?,
        val images: List<String>,
        val publishAt: String?,
        val author: String,
        val viewCount: Long,
        val publisher: Publisher,
        val isPinned: Boolean,
        val pinnedAt: String?,
        val pinExpireAt: String?,
        val sortOrder: Int,
        val createdAt: String,
        val updatedAt: String,
    ) {
        /** 生效时间（毫秒）：`publishAt` 为空表示「立即发布」，退回 createdAt；两者都解析不出来才是 0 */
        val effectiveMillis: Long get() = parseIsoMillis(publishAt) ?: parseIsoMillis(createdAt) ?: 0L
    }

    /** 分页结果（内置公告只有一页，[hasMore] 恒为 false） */
    data class Page(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val totalPages: Int,
        val hasMore: Boolean,
        val list: List<Announcement>,
    )

    /** 请求结果：失败带上可直接展示的原因 */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 获取公告列表（不含正文）；[page] 从 1 开始，内置公告只有第 1 页 */
    suspend fun fetchPage(page: Int = 1, pageSize: Int = PAGE_SIZE): Result<Page> {
        val list = if (page <= 1) BUILTIN.map { it.copy(content = null) } else emptyList()
        return Result.Success(
            Page(
                total = BUILTIN.size,
                page = page.coerceAtLeast(1),
                pageSize = pageSize,
                totalPages = 1,
                hasMore = false,
                list = list,
            )
        )
    }

    /** 获取公告详情（含正文）；不存在时返回失败，文案与原接口语义一致 */
    suspend fun fetchDetail(id: String): Result<Announcement> {
        val item = BUILTIN.firstOrNull { it.id == id.trim() }
            ?: return Result.Failure("公告不存在或已下架")
        return Result.Success(item)
    }

    /** 内置 Linux 版公告：置顶的发布说明 + 一条使用须知 */
    private val BUILTIN: List<Announcement> = listOf(
        Announcement(
            id = "linux-v1.3.4-release",
            title = "云析 YunX-Linux v1.3.4 发布",
            summary = "Linux 原生版正式发布：Ubuntu 22.04 / 24.04 实测可安装，粘贴分享链接即可高速下载。",
            content = """
                ## 云析 YunX-Linux v1.3.4

                本版本为 **Linux 原生版**，由上游 Windows 桌面版 YunX-Desktop 移植而来，
                已在 **Ubuntu 22.04.5 LTS x86_64** 实机完成安装与运行验收，并兼容 Ubuntu 24.04。

                ### 安装

                从 GitHub Releases 下载 `.deb` 后执行：

                ```bash
                sudo apt install ./yunx-desktop_1.3.4-1_amd64.deb
                ```

                启动：应用菜单「云析」，或终端执行 `yunx-desktop`。

                ### 本版要点

                - 仓库、窗口标题、打包元信息统一为 **YunX-Linux**，不再与 Windows 桌面版混淆。
                - 修复 Ubuntu 22.04 上因 `t64` 依赖名导致无法安装的问题。
                - 公告改为随包内置的 Linux 版公告，离线可用。

                ### 链接

                - Linux 版仓库：https://github.com/suz20090912-hub/YunX-Linux
                - 问题反馈：https://github.com/suz20090912-hub/YunX-Linux/issues
                - 上游 Windows 桌面版：https://github.com/tidain/YunX-Desktop
            """.trimIndent(),
            coverImage = null,
            images = emptyList(),
            publishAt = "2026-11-24T08:00:00+08:00",
            author = "YunX-Linux",
            viewCount = 0,
            publisher = Publisher(name = "YunX-Linux", avatarUrl = null),
            isPinned = true,
            pinnedAt = "2026-11-24T08:00:00+08:00",
            pinExpireAt = null,
            sortOrder = 0,
            createdAt = "2026-11-24T08:00:00+08:00",
            updatedAt = "2026-11-24T08:00:00+08:00",
        ),
        Announcement(
            id = "linux-usage-notes",
            title = "Linux 版使用须知",
            summary = "预览依赖 ffmpeg、通知走 notify-send、代理取自 GNOME / 环境变量，数据目录 ~/.yunx-desktop。",
            content = """
                ## Linux 版使用须知

                - **在线预览**：依赖系统 `ffmpeg` / `ffplay`。最小化安装若缺组件，执行
                  `sudo apt install ffmpeg libnotify-bin wmctrl xdotool` 补齐。
                - **下载通知**：走 `notify-send`（GNOME / KDE 通知中心），不再使用 Windows 通知中心进度条。
                - **网络代理**：取自 GNOME 系统设置或 `http_proxy` / `https_proxy` 环境变量，
                  不再读取 Windows 系统代理。
                - **数据库加密**：Linux 无 DPAPI，加密等级为「宽松 / 普通」两档。
                - **数据目录**：`~/.yunx-desktop`；下载目录默认 `~/下载`，可在设置中修改。
                - **更新检查**：只查询 Linux 版仓库的 Releases，不会误装 Windows 包。

                遇到问题的反馈入口：https://github.com/suz20090912-hub/YunX-Linux/issues
            """.trimIndent(),
            coverImage = null,
            images = emptyList(),
            publishAt = "2026-11-24T08:00:00+08:00",
            author = "YunX-Linux",
            viewCount = 0,
            publisher = Publisher(name = "YunX-Linux", avatarUrl = null),
            isPinned = false,
            pinnedAt = null,
            pinExpireAt = null,
            sortOrder = 1,
            createdAt = "2026-11-24T08:00:00+08:00",
            updatedAt = "2026-11-24T08:00:00+08:00",
        ),
    )
}