package com.yunx.app.util

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * linux-port：POSIX 权限收紧（Windows 上自动退化为空操作）。
 *
 * 背景：Linux 的默认 umask 是 022，`mkdirs()` 建出来的数据目录是 755、`File.writeBytes()`
 * 写出来的 credential.key / yunx.db 是 644。Windows 下 `%USERPROFILE%` 目录默认带用户 ACL，
 * 其它用户进不来；Linux 上 `/home/<user>` 往往是 755，同机其它用户可以直接读走：
 *
 * - `yunx.db`：任务名 / URL / 保存路径 / 账号备注（明文元数据）；
 * - `credential.key`：包裹后的凭证主密钥 —— 而「普通」档的机器指纹 `/etc/machine-id` 是
 *   全员可读的，等于把钥匙和锁放在同一个公共抽屉里。
 *
 * 因此：数据目录一律 700，密钥与库文件一律 600。用 [PosixFileAttributeView] 探测文件系统是否
 * 支持 POSIX 权限（NTFS / FAT / 部分网络盘返回 null），不支持就静默跳过，不影响原有行为。
 */
object UnixPermissions {

    private const val DIR_PERMS = "rwx------"
    private const val FILE_PERMS = "rw-------"

    fun restrictDir(dir: File) = apply(dir, DIR_PERMS)

    fun restrictFile(file: File) = apply(file, FILE_PERMS)

    /** 库文件与运行时旁边的 -wal / -shm 一并收紧（WAL 里同样是明文数据）。 */
    fun restrictDatabase(dbFile: File) {
        restrictFile(dbFile)
        restrictFile(File(dbFile.absolutePath + "-wal"))
        restrictFile(File(dbFile.absolutePath + "-shm"))
    }

    private fun apply(file: File, perms: String) {
        if (!file.exists()) return
        runCatching {
            val view = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
                ?: return // Windows / FAT / 不支持 POSIX 的文件系统
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
        }.onFailure {
            Log.w("YunX-Perm", "收紧权限失败 ${file.absolutePath}: ${it.message}")
        }
    }
}
