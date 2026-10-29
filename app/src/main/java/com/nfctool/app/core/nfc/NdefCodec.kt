package com.nfctool.app.core.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NdefRecord as AndroidRecord
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import com.nfctool.app.core.nfc.ndef.NdefDetail
import com.nfctool.app.core.nfc.ndef.NdefKind
import com.nfctool.app.core.nfc.ndef.NdefTypeRegistry
import java.nio.charset.Charset
import java.util.Locale

/**
 * NDEF 记录模型：把 Android 的 [NdefRecord] 包装成便于 UI 展示的结构。
 *
 * [kind] 是应用层的细分归类（TNF 之上的二次分类），
 * 例如 Wi-Fi 配网会被归为 [NdefKind.WIFI] 而不是笼统的 MIME。
 */
data class NdefRecordInfo(
    val typeName: String,
    val payloadSize: Int,
    val text: String?,
    val uri: String?,
    val mimeType: String?,
    val id: ByteArray?,
    val raw: ByteArray,
    val kind: NdefKind = NdefKind.UNKNOWN,
    val details: List<NdefDetail> = emptyList(),
    /** TNF 编号（1=Well-known, 2=MIME, 3=Absolute URI, 4=External…） */
    val tnf: Int = 0,
    /** 原始 type 字段字节，排查协议问题时需要 */
    val typeBytes: ByteArray = ByteArray(0),
) {
    val typeLetter: Char
        get() = kind.letter

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NdefRecordInfo) return false
        return typeName == other.typeName &&
            payloadSize == other.payloadSize &&
            text == other.text &&
            uri == other.uri &&
            mimeType == other.mimeType &&
            kind == other.kind &&
            details == other.details &&
            tnf == other.tnf &&
            typeBytes.contentEquals(other.typeBytes) &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = typeName.hashCode()
        result = 31 * result + payloadSize
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (uri?.hashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + kind.hashCode()
        result = 31 * result + details.hashCode()
        result = 31 * result + tnf
        result = 31 * result + typeBytes.contentHashCode()
        result = 31 * result + raw.contentHashCode()
        return result
    }
}

/**
 * NDEF 编解码器。
 *
 * 覆盖 NFC Forum 定义的全部标准类型：
 *  - TNF 0x01 Well-known: Text / URI / Smart Poster / Action / Signature
 *  - TNF 0x02 MIME Media
 *  - TNF 0x03 Absolute URI
 *  - TNF 0x04 External
 *  - TNF 0x05 Unknown
 *  - TNF 0x06 Unchanged
 *  - TNF 0x07 Reserved
 *  - TNF 0x08 AID (NFC Forum Type 4 标签)
 */
object NdefCodec {

    // ---------- 编码 ----------

    /**
     * 构造纯文本记录。
     *
     * Android 的 NdefRecord.createTextRecord(language, text) 只支持 UTF-16，
     * 中文会被编码成 UTF-16。这里手工构造 payload 以使用 UTF-8。
     *
     * ⚠️ 状态字节第一字节 **不是 0**：bit0-5 必须放语言码长度。
     * 早期实现硬编码 0x00，读方按 langLen=0 解析，会把 "zh" 当成正文开头
     * （显示成「zh你好」），记录实际不合法。
     */
    fun createTextRecord(
        text: String,
        charset: Charset = Charsets.UTF_8,
        language: String = "zh",
    ): NdefRecord = NdefRecord(
        AndroidRecord.TNF_WELL_KNOWN,
        "T".toByteArray(Charsets.US_ASCII),
        ByteArray(0),
        buildTextPayload(text, charset, language),
    )

    /**
     * 构造 Text 记录的 payload（纯字节，不依赖 Android API，便于单测）。
     *
     * 结构：`[status][language][text]`
     *  - status bit7 = 编码（0=UTF-8，1=UTF-16）
     *  - status bit0-5 = 语言码字节长度
     *
     * ⚠️ 状态字节**不是 0**：早期实现硬编码 0x00，读方按 langLen=0 解析，
     * 会把 "zh" 当成正文开头（显示成「zh你好」），记录实际不合法。
     */
    fun buildTextPayload(
        text: String,
        charset: Charset = Charsets.UTF_8,
        language: String = "zh",
    ): ByteArray {
        val langBytes = language.toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(charset)
        val isUtf16 = charset == Charsets.UTF_16 || charset == Charsets.UTF_16BE ||
            charset == Charsets.UTF_16LE
        val payload = ByteArray(1 + langBytes.size + textBytes.size)
        payload[0] = ((if (isUtf16) 0x80 else 0x00) or (langBytes.size and 0x3F)).toByte()
        System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)
        return payload
    }

    /** 构造 URI 记录 */
    fun createUriRecord(uri: String): NdefRecord =
        NdefRecord.createUri(uri)

    /** 构造 MIME 记录 */
    fun createMimeRecord(mimeType: String, data: ByteArray): NdefRecord =
        NdefRecord.createMime(mimeType, data)

    /** 构造绝对 URI 记录（TNF 0x03） */
    fun createAbsoluteUriRecord(uri: String): NdefRecord =
        NdefRecord.createUri(uri)

    /** 构造外部类型记录（TNF 0x04） */
    fun createExternalRecord(domain: String, type: String, data: ByteArray): NdefRecord =
        NdefRecord.createExternal(domain, type, data)

    /**
     * 构造 AID 记录（TNF 0x08），用于 NFC Forum Type 4 标签。
     *
     * Android 的 NdefRecord 构造函数只接受 TNF_WELL_KNOWN / TNF_MIME_MEDIA /
     * TNF_ABSOLUTE_URI / TNF_EXTERNAL_TYPE / TNF_UNKNOWN / TNF_UNCHANGED / TNF_EMPTY，
     * 没有 TNF_AID。要表达 AID，用 TNF_ABSOLUTE_URI 且 payload 为 AID 字节。
     */
    fun createAidRecord(aid: ByteArray): NdefRecord {
        require(aid.isNotEmpty()) { AppStrings.get(R.string.err_aid_empty) }
        return NdefRecord(
            AndroidRecord.TNF_ABSOLUTE_URI,
            ByteArray(0),
            ByteArray(0),
            aid
        )
    }

    /** 把多段文本拼成单条记录（用于写入长度受限的场景） */
    fun buildMessage(records: List<NdefRecord>): NdefMessage =
        NdefMessage(records.toTypedArray())

    // ---------- 解码 ----------

    /** 把 NdefMessage 解码为可展示的记录列表 */
    fun decode(message: NdefMessage): List<NdefRecordInfo> =
        message.records.map { decodeRecord(it) }

    fun decodeRecord(record: NdefRecord): NdefRecordInfo {
        val raw = record.toByteArray()
        val kind = NdefTypeRegistry.classify(record)
        return NdefRecordInfo(
            typeName = typeNameOf(record, kind),
            payloadSize = record.payload.size,
            text = decodeText(record),
            uri = decodeUri(record),
            mimeType = if (record.tnf == AndroidRecord.TNF_MIME_MEDIA) {
                String(record.type, Charsets.UTF_8)
            } else null,
            id = record.id?.takeIf { it.isNotEmpty() },
            raw = raw,
            kind = kind,
            details = NdefTypeRegistry.details(record),
            tnf = record.tnf.toInt(),
            typeBytes = record.type,
        )
    }

    /**
     * 记录类型名。
     *
     * 对已识别的细分类型直接用其名称（如"Wi-Fi 配网"），
     * 未识别的才回退到 TNF 层面的描述（如"MIME"、"External"）。
     */
    private fun typeNameOf(record: NdefRecord, kind: NdefKind): String = when (kind) {
        NdefKind.WIFI,
        NdefKind.VCARD,
        NdefKind.MECARD,
        NdefKind.VCALENDAR,
        NdefKind.ANDROID_APP,
        NdefKind.HANDOVER,
        NdefKind.SMART_POSTER,
        NdefKind.GEO,
        NdefKind.BLUETOOTH_OOB -> AppStrings.get(kind.labelRes)

        NdefKind.TEXT -> "Text"
        NdefKind.URI -> if (record.tnf == AndroidRecord.TNF_ABSOLUTE_URI) "Absolute URI" else "URI"
        NdefKind.AID -> "AID"
        NdefKind.MIME -> "MIME"
        NdefKind.EXTERNAL -> "External"
        NdefKind.UNKNOWN -> when (record.tnf) {
            AndroidRecord.TNF_UNKNOWN -> "Unknown"
            AndroidRecord.TNF_UNCHANGED -> "Unchanged"
            AndroidRecord.TNF_EMPTY -> "Empty"
            else -> "TNF ${record.tnf}"
        }
    }

    /** 解码 Text 记录 */
    fun decodeText(record: NdefRecord): String? {
        if (record.tnf != AndroidRecord.TNF_WELL_KNOWN) return null
        if (record.type.contentEquals("T".toByteArray(Charsets.US_ASCII))) {
            val payload = record.payload
            if (payload.isEmpty()) return ""
            val status = payload[0].toInt()
            val langLen = status and 0x3F
            if (payload.size < 1 + langLen) return null
            val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
            val textStart = 1 + langLen
            return String(payload, textStart, payload.size - textStart, charset)
        }
        return null
    }

    /** 解码 URI 记录 */
    fun decodeUri(record: NdefRecord): String? {
        if (record.tnf == AndroidRecord.TNF_ABSOLUTE_URI) {
            return String(record.payload, Charsets.UTF_8)
        }
        if (record.tnf != AndroidRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals("U".toByteArray(Charsets.US_ASCII))) return null
        val payload = record.payload
        if (payload.isEmpty()) return null
        val prefixIndex = payload[0].toInt() and 0xFF
        val uri = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        val prefix = URI_PREFIXES.getOrNull(prefixIndex) ?: ""
        return prefix + uri
    }

    /** 依据 payload 猜测媒体类型 */
    fun guessMimeType(data: ByteArray): String = when {
        data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> "image/png"
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
        data.size >= 3 && data[0] == 0x47.toByte() && data[1] == 0x49.toByte() -> "image/gif"
        data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" -> "audio/wav"
        data.size >= 3 && String(data, 0, 3, Charsets.US_ASCII) == "ID3" -> "audio/mpeg"
        data.size >= 4 && String(data, 0,  4, Charsets.US_ASCII) == "%PDF" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** NFC Forum URI 前缀表（URIPrefix 0x00 ~ 0x2B） */
    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://",
        "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
        "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
        "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:",
        "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
        "urn:nfc:"
    )
}
