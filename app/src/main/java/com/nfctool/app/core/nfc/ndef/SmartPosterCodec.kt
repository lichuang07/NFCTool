package com.nfctool.app.core.nfc.ndef

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings

/**
 * Smart Poster 编解码器（NFC Forum Smart Poster RTD）。
 *
 * ## 规范结构（2026-10-25 重写）
 *
 * 早期实现有两个硬伤：记录类型写成 `"S"`（规范是 `"Sp"`），payload 用自造的
 * 「action count + action 段」结构。结果是写出的智能海报任何手机都不认，
 * 真实的 Sp 标签也解不出标题。
 *
 * 正确结构：
 * ```
 * Sp 记录：TNF_WELL_KNOWN，type = "Sp"
 *   payload = 一条嵌套的 NDEF 消息，其中按顺序包含：
 *     T   记录（标题，可选）
 *     U   记录（链接，至少一条）
 *     act 记录（动作：0x00=DO / 0x01=SAVE / 0x02=EDIT，可选）
 *     s   记录（字节数，可选）
 *     t   记录（MIME 类型，可选）
 * ```
 *
 * 嵌套消息按 NDEF 序列化：每条记录前有 flags 字节
 * （bit7=MB 首条 / bit6=ME 末条 / bit4=SR 短记录 / bit3=IL 有 ID / bit0-2=TNF），
 * 随后是 type 长度、payload 长度（SR 时 1 字节否则 4 字节）、type、payload。
 *
 * 注意：`android.nfc.SmartPoster` 是隐藏 API，第三方应用不可调用，
 * 因此这里手工构造与解析。payload 编解码为纯字节实现，可在 JUnit 中对拍。
 */
object SmartPosterCodec {

    /** Smart Poster 记录类型名（NFC Forum RTD "Sp"）——测试与断言用 */
    const val TYPE_SMART_POSTER_NAME = "Sp"

    /** Smart Poster 记录类型（NFC Forum RTD "Sp"） */
    private val TYPE_SMART_POSTER = "Sp".toByteArray(Charsets.US_ASCII)

    /** 标题记录类型 */
    private val TYPE_TEXT = "T".toByteArray(Charsets.US_ASCII)

    /** 链接记录类型 */
    private val TYPE_URI = "U".toByteArray(Charsets.US_ASCII)

    /** 动作记录类型 */
    private val TYPE_ACT = "act".toByteArray(Charsets.US_ASCII)

    /** 字节数记录类型 */
    private val TYPE_SIZE = "s".toByteArray(Charsets.US_ASCII)

    /** MIME 类型记录类型 */
    private val TYPE_TYPE = "t".toByteArray(Charsets.US_ASCII)

    /** TNF 0x01 Well-known */
    private const val TNF_WELL_KNOWN = 0x01

    // flags 位
    private const val FLAG_MB = 0x80
    private const val FLAG_ME = 0x40
    private const val FLAG_SR = 0x10

    /** 动作值（规范定义） */
    const val ACTION_DO = 0x00
    const val ACTION_SAVE_FOR_LATER = 0x01
    const val ACTION_EDIT = 0x02

    data class SmartPoster(
        val title: String?,
        val language: String,
        val uris: List<String>,
        /** 动作值（ACTION_DO / ACTION_SAVE_FOR_LATER / ACTION_EDIT）；记录未携带时为 null */
        val action: Int? = null,
        /** 声明的内容字节数（s 记录） */
        val size: Int? = null,
        /** 声明的 MIME 类型（t 记录） */
        val mimeType: String? = null,
    )

    fun isSmartPoster(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return false
        return record.type.contentEquals(TYPE_SMART_POSTER)
    }

    /**
     * 解析 Sp 记录的 payload（嵌套 NDEF 消息）。
     *
     * 纯字节实现，不依赖 Android API，便于单测。
     */
    fun decodePayload(payload: ByteArray): SmartPoster? {
        val records = parseNdefMessage(payload)
        if (records.isEmpty()) return null

        var title: String? = null
        var language = "zh"
        val uris = mutableListOf<String>()
        var action: Int? = null
        var size: Int? = null
        var mimeType: String? = null

        for ((type, data) in records) {
            when {
                type.contentEquals(TYPE_TEXT) -> {
                    val text = decodeTextPayload(data)
                    if (text != null) {
                        title = text.first
                        language = text.second
                    }
                }

                type.contentEquals(TYPE_URI) -> {
                    decodeUriPayload(data)?.let { uris += it }
                }

                type.contentEquals(TYPE_ACT) -> {
                    if (data.isNotEmpty()) action = data[0].toInt() and 0xFF
                }

                type.contentEquals(TYPE_SIZE) -> {
                    size = data.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                }

                type.contentEquals(TYPE_TYPE) -> {
                    mimeType = String(data, Charsets.UTF_8)
                }
            }
        }

        return SmartPoster(
            title = title,
            language = language,
            uris = uris,
            action = action,
            size = size,
            mimeType = mimeType,
        )
    }

    /** 解析 Sp 记录 */
    fun decode(record: NdefRecord): SmartPoster? {
        if (!isSmartPoster(record)) return null
        return decodePayload(record.payload)
    }

    /**
     * 组装 Sp 记录的 payload（嵌套 NDEF 消息，纯字节实现）。
     *
     * @param title    标题（可空）
     * @param uris     链接列表，每个生成一条 U 记录
     * @param language 标题的语言码（RFC 3066），中文用 "zh"
     * @param action   可选动作值
     */
    fun encodePayload(
        title: String?,
        uris: List<String>,
        language: String = "zh",
        action: Int? = null,
    ): ByteArray {
        require(uris.isNotEmpty()) { AppStrings.get(R.string.err_ho_no_carrier) }

        val inner = mutableListOf<Pair<ByteArray, ByteArray>>() // type to payload

        val titleText = title?.takeIf { it.isNotBlank() }
        if (titleText != null) {
            inner += TYPE_TEXT to encodeTextPayload(titleText, language)
        }
        uris.forEach { uri ->
            inner += TYPE_URI to encodeUriPayload(uri)
        }
        if (action != null) {
            inner += TYPE_ACT to byteArrayOf(action.toByte())
        }

        // 序列化成 NDEF 消息：首条打 MB，末条打 ME，短记录打 SR
        val out = mutableListOf<Byte>()
        inner.forEachIndexed { index, (type, data) ->
            var flags = TNF_WELL_KNOWN
            if (index == 0) flags = flags or FLAG_MB
            if (index == inner.size - 1) flags = flags or FLAG_ME
            if (data.size <= 0xFF) flags = flags or FLAG_SR
            out += flags.toByte()
            out += type.size.toByte()
            if (data.size <= 0xFF) {
                out += data.size.toByte()
            } else {
                // 长记录：4 字节大端长度
                val len = data.size
                out += ((len shr 24) and 0xFF).toByte()
                out += ((len shr 16) and 0xFF).toByte()
                out += ((len shr 8) and 0xFF).toByte()
                out += (len and 0xFF).toByte()
            }
            type.forEach { out += it }
            data.forEach { out += it }
        }
        return out.toByteArray()
    }

    /**
     * 构造一条 Smart Poster 消息。
     *
     * @param title    标题（可空）
     * @param uris     动作链接列表，每个会生成一条 U 记录
     * @param language 标题语言码（RFC 3066），中文用 "zh"
     * @param action   可选动作值（0=DO / 1=SAVE / 2=EDIT）
     */
    fun buildMessage(
        title: String?,
        uris: List<String>,
        language: String = "zh",
        action: Int? = null,
    ): NdefMessage {
        val records = mutableListOf<NdefRecord>(
            NdefRecord(
                NdefRecord.TNF_WELL_KNOWN,
                TYPE_SMART_POSTER,
                ByteArray(0),
                encodePayload(title, uris, language, action),
            )
        )
        // U 记录本身也要进消息，读方才能取到链接
        uris.forEach { uri -> records += NdefRecord.createUri(uri) }
        return NdefMessage(records.toTypedArray())
    }

    // ---------- 内部：NDEF 消息序列化 ----------

    /** 解析嵌套 NDEF 消息，返回 (type, payload) 列表 */
    private fun parseNdefMessage(payload: ByteArray): List<Pair<ByteArray, ByteArray>> {
        val result = mutableListOf<Pair<ByteArray, ByteArray>>()
        var index = 0
        while (index < payload.size) {
            val flags = payload[index].toInt() and 0xFF
            index++
            if (index >= payload.size) break
            val typeLen = payload[index].toInt() and 0xFF
            index++
            val shortRecord = flags and FLAG_SR != 0
            val payloadLen = if (shortRecord) {
                if (index >= payload.size) break
                payload[index].toInt() and 0xFF
            } else {
                if (index + 4 > payload.size) break
                ((payload[index].toInt() and 0xFF) shl 24) or
                    ((payload[index + 1].toInt() and 0xFF) shl 16) or
                    ((payload[index + 2].toInt() and 0xFF) shl 8) or
                    (payload[index + 3].toInt() and 0xFF)
            }
            index += if (shortRecord) 1 else 4
            val hasId = flags and 0x08 != 0
            val idLen = if (hasId) {
                if (index >= payload.size) break
                payload[index].toInt() and 0xFF
            } else 0
            if (hasId) index++
            if (index + typeLen + payloadLen > payload.size) break
            val type = payload.copyOfRange(index, index + typeLen)
            index += typeLen
            index += idLen
            val data = payload.copyOfRange(index, index + payloadLen)
            index += payloadLen
            result += type to data
            // 分块记录（CF）不支持，遇到即停
            if (flags and 0x20 != 0) break
        }
        return result
    }

    /** 构造 T 记录的 payload：[status][lang][text] */
    private fun encodeTextPayload(text: String, language: String): ByteArray {
        val langBytes = language.toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + langBytes.size + textBytes.size)
        payload[0] = (langBytes.size and 0x3F).toByte()
        System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)
        return payload
    }

    /** 解析 T 记录的 payload，返回 (文本, 语言码) */
    private fun decodeTextPayload(payload: ByteArray): Pair<String, String>? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val langLen = status and 0x3F
        if (payload.size < 1 + langLen) return null
        val language = String(payload, 1, langLen, Charsets.US_ASCII)
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        val text = String(payload, 1 + langLen, payload.size - 1 - langLen, charset)
        return text to language
    }

    /** 构造 U 记录的 payload：[0x00][uri]（0x00 = 无前缀，全文） */
    private fun encodeUriPayload(uri: String): ByteArray {
        val uriBytes = uri.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x00) + uriBytes
    }

    /** 解析 U 记录的 payload，按前缀表还原完整 URI */
    private fun decodeUriPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val prefixIndex = payload[0].toInt() and 0xFF
        val rest = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        val prefix = URI_PREFIXES.getOrNull(prefixIndex) ?: ""
        return prefix + rest
    }

    /** NFC Forum URI 前缀表（URIPrefix 0x00 ~ 0x2B） */
    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://",
        "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
        "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
        "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:",
        "sip:", "sips:", "tftp://", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
        "urn:nfc:",
    )
}
