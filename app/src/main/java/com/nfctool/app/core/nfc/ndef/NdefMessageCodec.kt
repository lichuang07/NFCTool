package com.nfctool.app.core.nfc.ndef

import java.io.ByteArrayOutputStream

/**
 * NDEF **消息层**的纯字节编解码。
 *
 * ## 为什么需要它
 *
 * 复制标签要把源卡上**整条 NDEF 消息**原样搬到目标卡，需要「消息 → 字节 → 消息」
 * 这一层。两个现成方案都不能用：
 *  - `NdefMessage.toByteArray()` 已废弃；
 *  - `NdefRecord` 在 JUnit 环境里不可用，直接操作它就写不了单测。
 *
 * 所以照项目「Codec 纯字节」的约定，把消息层序列化做成**不依赖 Android API 的纯函数**：
 * 既能把快照持久化（存文件 / 存历史），也能在单测里双向对拍。
 *
 * ## NDEF 1.0 记录布局
 *
 * ```
 * [header 1B]      bit7 MB | bit6 ME | bit5 CF | bit4 SR | bit3 IL | bit2-0 TNF
 * [type length 1B]
 * [payload length 1B(SR=1) 或 4B 大端(SR=0)]
 * [id length 1B]      仅 IL=1 时存在
 * [type] [id] [payload]
 * ```
 *
 * ⚠️ **不支持 chunked 记录**（CF=1）。这类记录在消费级标签上极罕见，
 * 遇到时 [decodeMessage] 会直接报错而不是静默截断。
 */
object NdefMessageCodec {

    /** 一条记录的四个原始字段（不含 MB/ME —— 位置信息由 [encodeMessage] 决定） */
    data class RawRecord(
        val tnf: Int,
        val type: ByteArray = ByteArray(0),
        val id: ByteArray = ByteArray(0),
        val payload: ByteArray = ByteArray(0),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawRecord) return false
            return tnf == other.tnf &&
                type.contentEquals(other.type) &&
                id.contentEquals(other.id) &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = tnf
            result = 31 * result + type.contentHashCode()
            result = 31 * result + id.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    // ---- 记录头标志位 ----
    private const val FLAG_MB = 0x80 // 消息首条
    private const val FLAG_ME = 0x40 // 消息末条
    private const val FLAG_CF = 0x20 // chunked（不支持）
    private const val FLAG_SR = 0x10 // 短记录：payload 长度用 1 字节
    private const val FLAG_IL = 0x08 // 存在 ID 字段

    /** 短记录阈值：payload ≤ 255 字节时用 1 字节长度 */
    private const val MAX_SHORT_PAYLOAD = 0xFF

    /**
     * 单条记录 → 字节。
     *
     * @param isFirst 是否为消息首条记录（置 MB）
     * @param isLast  是否为消息末条记录（置 ME）
     */
    fun encodeRecord(rec: RawRecord, isFirst: Boolean, isLast: Boolean): ByteArray {
        val sr = rec.payload.size <= MAX_SHORT_PAYLOAD
        val il = rec.id.isNotEmpty()
        var header = rec.tnf and 0x07
        if (il) header = header or FLAG_IL
        if (sr) header = header or FLAG_SR
        if (isFirst) header = header or FLAG_MB
        if (isLast) header = header or FLAG_ME
        // CF 永远不置位：本项目不生成 chunked 记录

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(rec.type.size and 0xFF)
        if (sr) {
            out.write(rec.payload.size and 0xFF)
        } else {
            val len = rec.payload.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        }
        if (il) out.write(rec.id.size and 0xFF)
        out.write(rec.type)
        if (il) out.write(rec.id)
        out.write(rec.payload)
        return out.toByteArray()
    }

    /** 多条记录 → 一条 NDEF 消息的字节（首尾自动补 MB / ME） */
    fun encodeMessage(records: List<RawRecord>): ByteArray {
        require(records.isNotEmpty()) { "NDEF 消息至少要有一条记录" }
        val out = ByteArrayOutputStream()
        records.forEachIndexed { i, rec ->
            val encoded = encodeRecord(rec, isFirst = i == 0, isLast = i == records.lastIndex)
            out.write(encoded, 0, encoded.size)
        }
        return out.toByteArray()
    }

    /**
     * 一条 NDEF 消息的字节 → 记录列表。
     *
     * 用于回读校验与单测对拍；遇到非法长度 / chunked 记录会返回 failure，
     * 不会静默截断出一条"看起来能写"的消息。
     */
    fun decodeMessage(bytes: ByteArray): Result<List<RawRecord>> = runCatching {
        val result = mutableListOf<RawRecord>()
        var i = 0
        while (i < bytes.size) {
            require(i + 2 <= bytes.size) { "记录头截断（偏移 $i，剩余 ${bytes.size - i} 字节）" }
            val header = bytes[i].toInt() and 0xFF
            val sr = (header and FLAG_SR) != 0
            val il = (header and FLAG_IL) != 0
            require((header and FLAG_CF) == 0) { "不支持 chunked 记录（CF=1）" }
            i += 1

            val typeLen = bytes[i].toInt() and 0xFF
            i += 1

            val payloadLen = if (sr) {
                require(i + 1 <= bytes.size) { "payload 长度字段截断" }
                val v = bytes[i].toInt() and 0xFF
                i += 1
                v
            } else {
                require(i + 4 <= bytes.size) { "payload 长度字段截断" }
                val v = ((bytes[i].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i + 3].toInt() and 0xFF)
                i += 4
                v
            }

            val idLen = if (il) {
                require(i + 1 <= bytes.size) { "ID 长度字段截断" }
                val v = bytes[i].toInt() and 0xFF
                i += 1
                v
            } else {
                0
            }

            val bodyEnd = i + typeLen + idLen + payloadLen
            require(bodyEnd <= bytes.size) {
                "记录体截断（需 $bodyEnd 字节，实际 ${bytes.size} 字节）"
            }

            val type = bytes.copyOfRange(i, i + typeLen)
            i += typeLen
            val id = bytes.copyOfRange(i, i + idLen)
            i += idLen
            val payload = bytes.copyOfRange(i, i + payloadLen)
            i += payloadLen

            result += RawRecord(
                tnf = header and 0x07,
                type = type,
                id = id,
                payload = payload,
            )
        }
        result
    }
}
