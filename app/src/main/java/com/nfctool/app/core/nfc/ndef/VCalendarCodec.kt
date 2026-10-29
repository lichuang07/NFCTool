package com.nfctool.app.core.nfc.ndef

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.nfc.NdefRecord

/**
 * vCalendar 日历事件编解码器。
 *
 * MIME 类型 `text/vcalendar`，payload 是明文的 iCalendar 文本：
 *
 * ```
 * BEGIN:VCALENDAR
 * VERSION:2.0
 * BEGIN:VEVENT
 * SUMMARY:周会
 * DTSTART:20261026T100000
 * DTEND:20261026T110000
 * LOCATION:三号会议室
 * DESCRIPTION:讨论 NFC 方案
 * END:VEVENT
 * END:VCALENDAR
 * ```
 */
object VCalendarCodec {

    private const val MIME_VCALENDAR = "text/vcalendar"
    private const val MIME_VCALENDAR_LEGACY = "text/x-vcalendar"

    private val MIME_ALIASES = setOf(MIME_VCALENDAR, MIME_VCALENDAR_LEGACY)

    data class Event(
        val summary: String?,
        val description: String?,
        val location: String?,
        val start: String?,
        val end: String?,
        val organizer: String?,
    )

    fun isVCalendar(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_MIME_MEDIA) return false
        val type = String(record.type, Charsets.UTF_8).lowercase()
        return type in MIME_ALIASES
    }

    /**
     * 解析 vCalendar，失败返回 null。
     */
    fun parse(payload: ByteArray): Event? {
        val text = String(payload, Charsets.UTF_8)
        if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) return null

        var summary: String? = null
        var description: String? = null
        var location: String? = null
        var start: String? = null
        var end: String? = null
        var organizer: String? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val property = line.substringBefore(':').substringBefore(';').uppercase()
            val value = line.substringAfter(':', "")
            when (property) {
                "SUMMARY" -> if (summary == null) summary = value
                "DESCRIPTION" -> if (description == null) description = value
                "LOCATION" -> if (location == null) location = value
                "DTSTART" -> if (start == null) start = value
                "DTEND" -> if (end == null) end = value
                "ORGANIZER" -> if (organizer == null) organizer = value
            }
        }

        if (summary == null && start == null && end == null) return null
        return Event(summary, description, location, start, end, organizer)
    }

    /**
     * 生成 vCalendar 文本。
     *
     * ⚠️ RFC 5545 要求行尾为 **CRLF**（`\r\n`），早期实现用 [StringBuilder.appendLine]
     * 输出裸 LF，部分日历应用（尤其 iOS）会整份解析失败。
     */
    fun build(
        summary: String,
        start: String,
        end: String,
        location: String? = null,
        description: String? = null,
    ): String {
        require(summary.isNotBlank()) { AppStrings.get(R.string.err_vcal_summary_empty) }
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//NFC Toolbox//NFCTool//CN\r\n")
        sb.append("BEGIN:VEVENT\r\n")
        sb.append("SUMMARY:$summary\r\n")
        if (start.isNotBlank()) sb.append("DTSTART:$start\r\n")
        if (end.isNotBlank()) sb.append("DTEND:$end\r\n")
        location?.takeIf { it.isNotBlank() }?.let { sb.append("LOCATION:$it\r\n") }
        description?.takeIf { it.isNotBlank() }?.let { sb.append("DESCRIPTION:$it\r\n") }
        sb.append("END:VEVENT\r\n")
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }
}
