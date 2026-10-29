package com.nfctool.app.core.nfc.ndef

import android.nfc.NdefRecord

/**
 * vCard 电子名片解析器。
 *
 * vCard 走的是 MIME 类型 `text/vcard`（旧标签常见 `text/x-vcard`），
 * payload 是明文的 vCard 3.0 / 2.1 文本，结构为若干行：
 *
 * ```
 * BEGIN:VCARD
 * VERSION:3.0
 * N:姓;名;中间名;前缀;后缀
 * FN:完整姓名
 * TEL;TYPE=CELL:+8613800000000
 * EMAIL:someone@example.com
 * ORG:某公司
 * END:VCARD
 * ```
 *
 * 解析侧要兼容各家实现的差异（字段顺序、参数、字符集编码变化很多），
 * 因此解析时按「属性名」匹配而非按行号；写入侧则生成规范的 vCard 3.0。
 */
object VCardCodec {

    private const val MIME_VCARD = "text/vcard"
    private const val MIME_VCARD_LEGACY = "text/x-vcard"

    /** 常见的"以 vCard 开头"的 MIME 别名 */
    private val MIME_ALIASES = setOf(MIME_VCARD, MIME_VCARD_LEGACY)

    data class VCard(
        val fullName: String?,
        val name: String?,
        val organization: String?,
        val title: String?,
        val phones: List<String>,
        val emails: List<String>,
        val urls: List<String>,
        val address: String?,
    )

    fun isVCard(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_MIME_MEDIA) return false
        val type = String(record.type, Charsets.UTF_8).lowercase()
        return type in MIME_ALIASES
    }

    /**
     * 解析 vCard 文本，失败返回 null。
     */
    fun parse(payload: ByteArray): VCard? {
        val text = String(payload, Charsets.UTF_8)
        if (!text.contains("BEGIN:VCARD", ignoreCase = true)) return null

        var fullName: String? = null
        var name: String? = null
        var org: String? = null
        var title: String? = null
        var address: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val urls = mutableListOf<String>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 行结构：[group.]NAME[;param=value...]:value
            // 先按第一个未转义的冒号切开，左边是属性（含参数），右边是值
            val colon = indexOfUnescaped(line, ':') ?: continue
            val left = line.substring(0, colon)
            val value = line.substring(colon + 1)

            // 属性名是左侧第一段（去掉 group. 前缀）；参数以 ';' 分隔，忽略
            val property = left.substringBefore(';')
                .substringAfterLast('.')
                .trim()
                .uppercase()
            if (property.isEmpty()) continue

            when (property) {
                "FN" -> if (fullName == null) fullName = unescape(value)
                "N" -> if (name == null) {
                    // N:姓;名;中间名;前缀;后缀 → 取"名 姓"
                    val parts = splitEscaped(value, ';').map { it.trim() }
                    val family = parts.getOrNull(0).orEmpty()
                    val given = parts.getOrNull(1).orEmpty()
                    val joined = listOf(given, family).filter { it.isNotEmpty() }
                        .joinToString(" ")
                    if (joined.isNotEmpty()) name = joined
                }
                "ORG" -> if (org == null) org = unescape(value)
                "TITLE" -> if (title == null) title = unescape(value)
                "TEL" -> phones += unescape(value)
                "EMAIL" -> emails += unescape(value)
                "URL" -> urls += unescape(value)
                "ADR" -> if (address == null) {
                    // ADR:;;街道;城市;省;邮编;国家
                    val parts = splitEscaped(value, ';').map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.isNotEmpty()) address = parts.joinToString(" ")
                }
            }
        }

        if (fullName == null && name == null && phones.isEmpty() && emails.isEmpty()) {
            return null
        }
        return VCard(
            fullName = fullName,
            name = name,
            organization = org,
            title = title,
            phones = phones,
            emails = emails,
            urls = urls,
            address = address,
        )
    }

    /**
     * 找到第一个未转义的分隔符位置；找不到返回 null。
     */
    private fun indexOfUnescaped(line: String, delimiter: Char): Int? {
        var escaped = false
        for (i in line.indices) {
            val ch = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == delimiter) return i
        }
        return null
    }

    /**
     * 还原 vCard 的反斜杠转义（`\;` `\,` `\n` `\\`）。
     */
    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder()
        var escaped = false
        for (ch in value) {
            if (escaped) {
                sb.append(if (ch == 'n' || ch == 'N') '\n' else ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else {
                sb.append(ch)
            }
        }
        if (escaped) sb.append('\\')
        return sb.toString()
    }

    /**
     * 按未转义的分号切分，兼容 vCard 里的 `\;` 转义。
     */
    private fun splitEscaped(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var escaped = false
        for (ch in line) {
            when {
                escaped -> {
                    sb.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == delimiter -> {
                    out += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        out += sb.toString()
        return out
    }

    /**
     * 生成 vCard 3.0 文本。
     *
     * 字段顺序遵循 RFC 6350：N → FN → ORG → TITLE → TEL → EMAIL → URL → ADR → END。
     * 换行统一用 CRLF，符合 vCard 规范。
     */
    fun build(
        fullName: String,
        phone: String? = null,
        email: String? = null,
        organization: String? = null,
        title: String? = null,
        url: String? = null,
        address: String? = null,
    ): String {
        require(fullName.isNotBlank()) { "姓名不能为空" }
        val name = fullName.trim().replace(';', ' ').replace('\n', ' ')
        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\r\n")
        sb.append("VERSION:3.0\r\n")
        sb.append("N:$name\r\n")
        sb.append("FN:$name\r\n")
        organization?.takeIf { it.isNotBlank() }
            ?.let { sb.append("ORG:").append(it.trim().replace(';', ' ')).append("\r\n") }
        title?.takeIf { it.isNotBlank() }
            ?.let { sb.append("TITLE:").append(it.trim().replace(';', ' ')).append("\r\n") }
        phone?.takeIf { it.isNotBlank() }
            ?.let { sb.append("TEL;TYPE=CELL:").append(it.trim()).append("\r\n") }
        email?.takeIf { it.isNotBlank() }
            ?.let { sb.append("EMAIL:").append(it.trim()).append("\r\n") }
        url?.takeIf { it.isNotBlank() }
            ?.let { sb.append("URL:").append(it.trim()).append("\r\n") }
        address?.takeIf { it.isNotBlank() }
            ?.let { sb.append("ADR;TYPE=HOME:").append(it.trim().replace(';', ' ')).append("\r\n") }
        sb.append("END:VCARD\r\n")
        return sb.toString()
    }
}
