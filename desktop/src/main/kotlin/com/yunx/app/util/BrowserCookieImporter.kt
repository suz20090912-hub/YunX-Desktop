package com.yunx.app.util

import com.sun.jna.platform.win32.Crypt32Util
import com.yunx.app.AppContext
import java.io.File
import java.sql.DriverManager
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 一键从本机浏览器导入网盘登录 Cookie（方案 A）。
 *
 * - Chromium 系（Chrome / Edge / 360 / QQ / 搜狗 / Opera / Brave / Vivaldi / Chromium）：
 *   读取各 Profile 下的 `Network/Cookies`（SQLite）。`encrypted_value` 处理：
 *   Windows：`v10` + DPAPI 载荷 → CryptUnprotectData；
 *   Linux：`v10` + AES-128-CBC（key 由 keyring 密码或 "peanuts" 派生，salt=saltysalt，
 *   IV=16 个空格），`v20`（Chrome 127+ 应用绑定加密）无法解密 → 跳过；
 * - Firefox：cookies.sqlite 中 value 为明文，直接读取。
 */
object BrowserCookieImporter {

    private const val TAG = "YunX-Cookie"

    private val osName = System.getProperty("os.name", "").lowercase()
    private val isWindows = osName.contains("win")
    private val isLinux = osName.contains("linux")
    private val home = File(System.getProperty("user.home") ?: ".")

    enum class Kind { CHROMIUM, FIREFOX }

    data class BrowserInfo(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val profileDirs: List<File>,
        /** Linux keyring 里的应用名（secret-tool lookup application <keyringApp>） */
        val keyringApp: String? = null
    )

    data class FoundCookies(
        val browserName: String,
        val cookie: String,
        val count: Int
    )

    /** 平台 → 需要匹配的 Cookie 域名后缀 */
    fun domainSuffixes(platform: String): List<String> = when (platform) {
        "QUARK" -> listOf(".quark.cn")
        "UC" -> listOf(".uc.cn")
        "BAIDU" -> listOf(".baidu.com")
        "C139" -> listOf("mail.10086.cn", "yun.139.com", ".10086.cn")
        "PAN115" -> listOf(".115.com", ".115cdn.com")
        else -> emptyList()
    }

    /** 发现本机已安装浏览器的 Cookie 库目录（存在 cookies 文件才算） */
    fun discoverBrowsers(): List<BrowserInfo> {
        val out = mutableListOf<BrowserInfo>()
        if (isWindows) discoverWindows(out) else if (isLinux) discoverLinux(out) else discoverMac(out)
        return out
    }

    private fun discoverWindows(out: MutableList<BrowserInfo>) {
        val local = System.getenv("LOCALAPPDATA")?.let { File(it) } ?: return
        val roaming = System.getenv("APPDATA")?.let { File(it) } ?: return

        val chromiumCandidates = listOf(
            Triple("Chrome", "chrome", listOf(File(local, "Google\\Chrome\\User Data"))),
            Triple("Edge", "microsoft-edge", listOf(File(local, "Microsoft\\Edge\\User Data"))),
            Triple("360极速浏览器", null, listOf(File(local, "360Chrome\\Chrome\\User Data"))),
            Triple(
                "360安全浏览器", null,
                listOf(File(roaming, "360se6\\User Data"), File(roaming, "360se\\User Data"), File(local, "360se6\\User Data"))
            ),
            Triple("QQ浏览器", null, listOf(File(local, "Tencent\\QQBrowser\\User Data"))),
            Triple("搜狗浏览器", null, listOf(File(roaming, "SogouExplorer\\User Data"), File(local, "SogouExplorer\\User Data"))),
            Triple("Opera", "opera", listOf(File(roaming, "Opera Software\\Opera Stable"))),
            Triple("Brave", "brave", listOf(File(local, "BraveSoftware\\Brave-Browser\\User Data"))),
            Triple("Vivaldi", "vivaldi", listOf(File(local, "Vivaldi\\User Data")))
        )
        addChromiumBrowsers(out, chromiumCandidates)

        val ffRoots = listOf(File(roaming, "Mozilla\\Firefox\\Profiles"))
        addFirefoxBrowser(out, ffRoots)
    }

    private fun discoverLinux(out: MutableList<BrowserInfo>) {
        val cfg = File(home, ".config")
        val chromiumCandidates = listOf(
            Triple("Chrome", "chrome", listOf(File(cfg, "google-chrome"), File(cfg, "google-chrome-beta"))),
            Triple("Chromium", "chromium", listOf(File(cfg, "chromium"))),
            Triple("Edge", "microsoft-edge", listOf(File(cfg, "microsoft-edge"), File(cfg, "microsoft-edge-beta"))),
            Triple("Brave", "brave", listOf(File(cfg, "BraveSoftware/Brave-Browser"))),
            Triple("Vivaldi", "vivaldi", listOf(File(cfg, "vivaldi"), File(cfg, "vivaldi-snapshot"))),
            Triple("Opera", "opera", listOf(File(cfg, "opera")))
        )
        addChromiumBrowsers(out, chromiumCandidates)

        // Firefox：原生 / Snap / Flatpak 三种安装位置
        addFirefoxBrowser(
            out,
            listOf(
                File(home, ".mozilla/firefox"),
                File(home, "snap/firefox/common/.mozilla/firefox"),
                File(home, ".var/app/org.mozilla.firefox/.mozilla/firefox")
            )
        )
    }

    private fun discoverMac(out: MutableList<BrowserInfo>) {
        val appSupport = File(home, "Library/Application Support")
        addChromiumBrowsers(
            out,
            listOf(
                Triple("Chrome", "chrome", listOf(File(appSupport, "Google/Chrome"))),
                Triple("Edge", "microsoft-edge", listOf(File(appSupport, "Microsoft Edge"))),
                Triple("Brave", "brave", listOf(File(appSupport, "BraveSoftware/Brave-Browser"))),
                Triple("Vivaldi", "vivaldi", listOf(File(appSupport, "Vivaldi")))
            )
        )
        addFirefoxBrowser(out, listOf(File(home, "Library/Application Support/Firefox/Profiles")))
    }

    private fun addChromiumBrowsers(
        out: MutableList<BrowserInfo>,
        candidates: List<Triple<String, String?, List<File>>>
    ) {
        for ((name, keyring, roots) in candidates) {
            val existing = roots.filter { root -> findChromiumCookieFiles(root).isNotEmpty() }
            if (existing.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, existing, keyring)
            }
        }
    }

    private fun addFirefoxBrowser(out: MutableList<BrowserInfo>, roots: List<File>) {
        val profiles = roots.flatMap { root ->
            root.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").isFile }?.toList() ?: emptyList()
        }
        if (profiles.isNotEmpty()) {
            out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
        }
    }

    /**
     * Chromium 的 Cookie 库位于 `User Data/<Profile>/Network/Cookies`（旧版 `Cookies`）；
     * Opera 等把 Profile 直接当根目录。这里统一枚举根目录下的 Default / Profile *。
     */
    private fun findChromiumCookieFiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        // Opera / 部分单 Profile 浏览器：根目录直接就是 Profile
        for (candidate in listOf("Network/Cookies", "Cookies")) {
            val f = File(root, candidate)
            if (f.isFile) out += f
        }
        root.listFiles { f -> f.isDirectory }?.forEach { profile ->
            for (candidate in listOf("Network/Cookies", "Cookies")) {
                val f = File(profile, candidate)
                if (f.isFile) out += f
            }
        }
        return out
    }

    /** 从指定浏览器导入指定平台的 Cookie；未找到返回 null */
    fun importFrom(browser: BrowserInfo, domains: List<String>): FoundCookies? =
        when (browser.kind) {
            Kind.CHROMIUM -> importChromium(browser, domains)
            Kind.FIREFOX -> importFirefox(browser, domains)
        }

    // ---------- Chromium ----------

    private fun importChromium(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (root in browser.profileDirs) {
            for (cookiesFile in findChromiumCookieFiles(root)) {
                val copy = File(AppContext.cacheDir, "browser-cookies-${System.nanoTime()}.db")
                try {
                    cookiesFile.copyTo(copy, overwrite = true)
                    val cookie = extractChromium(copy, domains, browser.keyringApp) ?: continue
                    return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
                } catch (e: Exception) {
                    Log.w(TAG, "读取 ${browser.displayName} Cookie 失败: ${e.message}")
                } finally {
                    runCatching { copy.delete() }
                }
            }
        }
        return null
    }

    private fun extractChromium(dbFile: File, domains: List<String>, keyringApp: String?): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host_key LIKE ?" }
            conn.prepareStatement(
                "SELECT host_key, name, encrypted_value, value FROM cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val enc = runCatching { rs.getBytes(3) }.getOrNull()
                        val plain = runCatching { rs.getString(4) }.getOrNull()
                        val value = decryptChromiumValue(enc, plain, keyringApp) ?: continue
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }

    /**
     * Chromium encrypted_value：
     * - "v10" / "v11" 前缀 → Windows 走 DPAPI；Linux/macOS 走 AES-128-CBC；
     * - "v20" 前缀（应用绑定加密，Chrome 127+）→ 返回 null（无法解密，跳过后整体提示）；
     * - 空值 → 用明文 value 列；
     * - 无前缀（个别旧版）→ Windows 按 DPAPI 尝试，Linux 按明文尝试。
     */
    private fun decryptChromiumValue(bytes: ByteArray?, plainValue: String?, keyringApp: String?): String? {
        if (bytes == null || bytes.isEmpty()) {
            return plainValue?.takeIf { it.isNotEmpty() }
        }
        var offset = 0
        var version = 0
        if (bytes[0] == 'v'.code.toByte() &&
            bytes[1] in '0'.code.toByte()..'9'.code.toByte() &&
            bytes[2] in '0'.code.toByte()..'9'.code.toByte()
        ) {
            version = (bytes[1] - '0'.code.toByte()) * 10 + (bytes[2] - '0'.code.toByte())
            offset = 3
        }
        if (version >= 20) return null // 应用绑定加密，无法解密
        val payload = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
        if (isWindows) {
            return runCatching {
                val plain = Crypt32Util.cryptUnprotectData(payload)
                String(plain, Charsets.UTF_8)
            }.getOrNull()
        }
        if (version == 0) {
            // Linux 下无前缀一般是明文（个别版本）
            return String(payload, Charsets.UTF_8).takeIf { it.isNotEmpty() && !it.startsWith("v1") }
        }
        return linuxAesDecrypt(payload, keyringApp)
    }

    /**
     * Linux Chromium v10 解密：
     * password 优先从 libsecret keyring 取（`secret-tool lookup application <app>`），
     * 取不到时回退 Chromium 内置的 "peanuts"（--password-store=basic）。
     * key = PBKDF2-HMAC-SHA1(password, "saltysalt", 1 次, 128 bit)；AES-128-CBC，IV=16 个空格。
     */
    private fun linuxAesDecrypt(payload: ByteArray, keyringApp: String?): String? {
        val password = keyringPassword(keyringApp) ?: "peanuts"
        return runCatching {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(PBEKeySpec(password.toCharArray(), "saltysalt".toByteArray(Charsets.UTF_8), 1, 128))
                .encoded
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ByteArray(16) { 0x20.toByte() })
            )
            String(cipher.doFinal(payload), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Linux Chromium Cookie 解密失败：${it.message}") }.getOrNull()
    }

    private fun keyringPassword(keyringApp: String?): String? {
        val app = keyringApp ?: return null
        val bin = listOf("/usr/bin/secret-tool", "/bin/secret-tool", "/usr/local/bin/secret-tool")
            .firstOrNull { File(it).canExecute() } ?: return null
        return runCatching {
            val p = ProcessBuilder(bin, "lookup", "application", app)
                .redirectErrorStream(true).start()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) return null
            p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // ---------- Firefox ----------

    private fun importFirefox(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (profile in browser.profileDirs) {
            val dbFile = File(profile, "cookies.sqlite")
            if (!dbFile.exists()) continue
            val copy = File(AppContext.cacheDir, "ff-cookies-${System.nanoTime()}.db")
            try {
                dbFile.copyTo(copy, overwrite = true)
                val cookie = extractFirefox(copy, domains) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 Firefox Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractFirefox(dbFile: File, domains: List<String>): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host LIKE ?" }
            conn.prepareStatement(
                "SELECT host, name, value FROM moz_cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = rs.getString(3)
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }
}
