package com.yunx.app.data.download

import java.io.File

/** Pure path validation shared by Android storage backends and unit tests. */
internal object DownloadPathPolicy {

    data class SafePath(val fileName: String, val relativeDirectory: String)

    fun sanitize(relativePath: String, fallbackName: String): SafePath? {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith('/')) return null

        val rawParts = normalized.split('/')
        if (rawParts.any { it == "." || it == ".." }) return null

        val parts = rawParts.filter { it.isNotBlank() }
        // ★ 以 '/' 结尾 = 只给了目录、没给文件名（批量下载拼出 "文件夹A/" 就是这种形态）。
        //   此时最后一段是**目录名**，不能当文件名用：否则文件会平铺到下载目录根、还顶着文件夹的名字，
        //   表现为「整个文件夹下载后只有一个文件进了文件夹，其余散落在下载目录」。
        //   名字用 fallback，目录照旧保留。
        val nameMissing = normalized.endsWith('/')
        val rawName = if (nameMissing) "" else parts.lastOrNull().orEmpty()
        val safeName = sanitizeName(rawName).ifBlank { fallbackName }
        val dirParts = if (nameMissing) parts else parts.dropLast(1)
        val safeDirectory = dirParts.joinToString("/") { sanitizeName(it) }
        return SafePath(safeName, safeDirectory)
    }

    fun isContained(base: File, candidate: File): Boolean {
        val basePath = base.canonicalFile.path.trimEnd(File.separatorChar)
        val candidatePath = candidate.canonicalFile.path
        return candidatePath != basePath && candidatePath.startsWith(basePath + File.separator)
    }

    /** Windows 保持原有 120 字符行为（NTFS 上限 255 字符，120 足够）。 */
    private const val MAX_NAME_CHARS_WINDOWS = 120

    /**
     * linux-port：Linux/Unix 的 ext4/xfs/btrfs 单层文件名上限是 255 **字节**，不是字符。
     * 中文 UTF-8 是 3 字节/字，120 个中文字符 = 360 字节，必然 ENAMETOOLONG。
     * 这里取 200 字节，给「标题 + 清晰度 + 序号 + 扩展名」留出足够余量。
     */
    private const val MAX_NAME_BYTES_POSIX = 200

    private val isWindows: Boolean
        get() = System.getProperty("os.name", "").lowercase().contains("win")

    fun sanitizeName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
        return if (isWindows) {
            if (cleaned.length > MAX_NAME_CHARS_WINDOWS) {
                val ext = cleaned.substringAfterLast('.', "").take(10)
                val base = cleaned.substringBeforeLast('.').take(100)
                if (ext.isNotBlank() && ext != cleaned) "$base.$ext" else cleaned.take(MAX_NAME_CHARS_WINDOWS)
            } else {
                cleaned
            }
        } else {
            truncateUtf8(cleaned, MAX_NAME_BYTES_POSIX)
        }
    }

    /** 按 UTF-8 字节截断，保留扩展名；emoji 代理对整体保留，不截出半个字符。 */
    private fun truncateUtf8(name: String, maxBytes: Int): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val ext = name.substringAfterLast('.', "")
        val useExt = ext.isNotBlank() && ext != name && ext.length <= 10
        val suffix = if (useExt) ".$ext" else ""
        val budget = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
        if (budget <= 0) return name.take(1)
        val base = if (useExt) name.substringBeforeLast('.') else name
        return truncateToBytes(base, budget) + suffix
    }

    private fun truncateToBytes(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var bytes = 0
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val step = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) 2 else 1
            val piece = text.substring(i, i + step)
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + pieceBytes > maxBytes) break
            sb.append(piece)
            bytes += pieceBytes
            i += step
        }
        return sb.toString()
    }
}
