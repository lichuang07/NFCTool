package com.nfctool.app.core.nfc.ndef

import android.nfc.NdefRecord

/**
 * MECARD 名片编解码器。
 *
 * MECARD 是 NEC 定义的紧凑名片格式，被日系手机广泛用于 NFC 传名片，
 * 相比 vCard 更省空间（NTAG213 上更实用）。
 *
 * MIME 类型 `text/x-mecard`，payload 是单行文本：
 *
 * ```
 * MECARD:N:张三;TEL:+8613800000000;EMAIL:someone@example.com;URL:https://example.com;ADR:上海;;
 * ```
 *
 * 字段用 `;` 分隔，以 `;;` 结束。字段名大写，值中的 `,` 表示多值分隔。
 */
object MeCardCodec {

    private const val MIME_MECARD = "text/x-mecard"

    data class MeCard(
        val name: String?,
        val phones: List<String>,
        val emails: List<String>,
        val urls: List<String>,
        val addresses: List<String>,
        val note: String?,
    )

    fun isMeCard(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_MIME_MEDIA) return false
        return String(record.type, Charsets.UTF_8).equals(MIME_MECARD, ignoreCase = true)
    }

    /**
     * 解析 MECARD，失败返回 null。
     */
    fun parse(payload: ByteArray): MeCard? {
        val text = String(payload, Charsets.UTF_8).trim()
        if (!text.startsWith("MECARD:", ignoreCase = true)) return null

        var name: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val addresses = mutableListOf<String>()
        var note: String? = null

        // 去掉前缀后按 ; 切分，MECARD 以 ";;" 结尾会产生一个空尾段
        val body = text.substringAfter(':')
        for (segment in body.split(';')) {
            if (segment.isEmpty()) continue
            val key = segment.substringBefore(':', "").uppercase()
            val value = segment.substringAfter(':', "")
            if (value.isEmpty()) continue
            when (key) {
                "N" -> if (name == null) name = value
                "TEL" -> phones += value.split(',')
                "EMAIL" -> emails += value.split(',')
                "URL" -> urls += value.split(',')
                "ADR" -> addresses += value.split(',')
                "NOTE" -> if (note == null) note = value
            }
        }

        if (name == null && phones.isEmpty() && emails.isEmpty()) return null
        return MeCard(name, phones, emails, urls, addresses, note)
    }

    /**
     * 生成 MECARD 文本。
     *
     * MECARD 的 `N` 字段用逗号分隔姓与名。
     */
    fun build(
        name: String,
        phone: String? = null,
        email: String? = null,
        url: String? = null,
        address: String? = null,
        note: String? = null,
    ): String {
        require(name.isNotBlank()) { "姓名不能为空" }
        val sb = StringBuilder("MECARD:N:")
        sb.append(name.replace(';', ' ').replace(',', ' ').trim())
        phone?.takeIf { it.isNotBlank() }?.let { sb.append(";TEL:").append(it.trim()) }
        email?.takeIf { it.isNotBlank() }?.let { sb.append(";EMAIL:").append(it.trim()) }
        url?.takeIf { it.isNotBlank() }?.let { sb.append(";URL:").append(it.trim()) }
        address?.takeIf { it.isNotBlank() }?.let { sb.append(";ADR:").append(it.trim()) }
        note?.takeIf { it.isNotBlank() }?.let { sb.append(";NOTE:").append(it.trim()) }
        sb.append(";;")
        return sb.toString()
    }
}
