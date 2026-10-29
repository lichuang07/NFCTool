package com.nfctool.app.core.nfc

import android.nfc.tech.IsoDep
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings

/**
 * ISO 7816-4 APDU 引擎。
 *
 * 手机 NFC 可通过 IsoDep 与智能卡通信，典型应用场景：
 *  - 读取银行卡 EMV 数据（GET DATA / SELECT AID）
 *  - 读取身份证芯片信息
 *  - 与门禁卡、加密卡交互
 *
 * 关键点：ISO 7816 支持 "61xx" 状态字，表示还有 xx 字节待取，
 * 此时应发送 GET RESPONSE (00 C0 00 00) 继续读取。Android 的 IsoDep
 * 不会自动处理，必须手动实现。
 */
class ApduEngine(private val session: TagSession) {

    val isAvailable: Boolean get() = session.isoDep != null

    /**
     * 传输协议。
     * IsoDep 没有暴露协议号，只能通过历史字节（ATR）推断：
     *  - 直接以 0x3B 开头 → T=CL（ISO 14443-4）
     *  - 0x3B/0x3F/0x80 开头 → T=CL
     *  - 其余按 ISO 7816-3 ATR 解析
     */
    val protocol: Int get() {
        val hb = historicalBytes ?: return PROTOCOL_UNKNOWN
        return if (hb.isNotEmpty() && (hb[0] == 0x3B.toByte() || hb[0] == 0x3F.toByte())) {
            PROTOCOL_T_CL
        } else {
            PROTOCOL_T_ISO7816
        }
    }

    /** 历史字节（ATR） */
    val historicalBytes: ByteArray? get() = session.isoDep?.historicalBytes

    /** 是否支持扩展长度 APDU */
    val extendedLengthSupported: Boolean
        get() = session.isoDep?.isExtendedLengthApduSupported ?: false

    /**
     * 发送 APDU 并返回响应，自动处理 61xx / 6Cxx 链式续传。
     *
     * ⚠️ 61xx 续传必须**累加**各分片：GET RESPONSE 每次只取回一部分数据，
     * 早期实现用新分片覆盖旧响应，EMV 长响应（如银行卡记录）会被截断成
     * 最后一帧。正确做法是把每帧的数据部分拼起来，末帧再带上最终 SW。
     */
    fun transceive(apdu: ByteArray): Result<ByteArray> = runCatching {
        val iso = session.isoDep ?: error(AppStrings.tagNoIsoDep())
        if (!iso.isConnected) iso.connect()
        var cmd = apdu
        var response = iso.transceive(cmd)
        val accumulated = java.io.ByteArrayOutputStream()
        var guard = 0
        while (guard++ < MAX_CHAIN) {
            val sw = statusWord(response)
            when {
                // 61xx：还有更多数据，用 GET RESPONSE 继续
                sw.size == 2 && sw[0] == 0x61.toByte() -> {
                    // 先把本帧的数据部分留下（SW 之前的字节）
                    if (response.size > 2) {
                        accumulated.write(response, 0, response.size - 2)
                    }
                    val len = sw[1].toInt() and 0xFF
                    cmd = if (len == 0) {
                        byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00)
                    } else {
                        byteArrayOf(0x00, 0xC0.toByte(), 0x00, len.toByte())
                    }
                    response = iso.transceive(cmd)
                }
                // 6Cxx：长度错误，用卡片建议的长度重发。
                // 只对带 Le 的 APDU 有效 —— case 3（无 Le）时改末字节会改坏数据，
                // 此时原样返回让上层看到错误。
                sw.size == 2 && sw[0] == 0x6C.toByte() && hasLe(cmd) -> {
                    val len = sw[1].toInt() and 0xFF
                    val fixed = cmd.copyOf()
                    fixed[fixed.size - 1] = len.toByte()
                    cmd = fixed
                    response = iso.transceive(cmd)
                }
                else -> break
            }
        }
        if (accumulated.size() == 0) response else accumulated.toByteArray() + response
    }

    /** 发送 APDU 并返回 [响应数据 + 状态字]，便于完整展示 */
    fun transceiveDetailed(apdu: ByteArray): Result<ApduExchange> = runCatching {
        val iso = session.isoDep ?: error(AppStrings.tagNoIsoDep())
        if (!iso.isConnected) iso.connect()
        var cmd = apdu
        var response = iso.transceive(cmd)
        val accumulated = java.io.ByteArrayOutputStream()
        var guard = 0
        while (guard++ < MAX_CHAIN) {
            val sw = statusWord(response)
            when {
                sw.size == 2 && sw[0] == 0x61.toByte() -> {
                    if (response.size > 2) {
                        accumulated.write(response, 0, response.size - 2)
                    }
                    val len = sw[1].toInt() and 0xFF
                    cmd = byteArrayOf(
                        0x00, 0xC0.toByte(), 0x00,
                        if (len == 0) 0x00 else len.toByte(),
                    )
                    response = iso.transceive(cmd)
                }
                sw.size == 2 && sw[0] == 0x6C.toByte() && hasLe(cmd) -> {
                    val len = sw[1].toInt() and 0xFF
                    val fixed = cmd.copyOf()
                    fixed[fixed.size - 1] = len.toByte()
                    cmd = fixed
                    response = iso.transceive(cmd)
                }
                else -> break
            }
        }
        val full = if (accumulated.size() == 0) response else accumulated.toByteArray() + response
        val sw = statusWord(full)
        ApduExchange(
            command = cmd,
            response = full,
            data = full.dropLast(sw.size).toByteArray(),
            statusWord = sw,
        )
    }

    /**
     * 选择 AID（SELECT by identifier）。
     * @param aid AID 的十六进制串，如 "A0000000031010"
     */
    fun selectAid(aid: String): Result<ApduExchange> {
        val aidBytes = Hex.parseLenient(aid)
        // CLA=00, INS=A4, P1=04, P2=00, Lc=len, Aid
        val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aidBytes.size.toByte()) + aidBytes
        return transceiveDetailed(apdu)
    }

    /** 读取二进制数据（READ BINARY） */
    fun readBinary(fileId: Int, offset: Int, length: Int): Result<ApduExchange> {
        val apdu = byteArrayOf(
            0x00, 0xB0.toByte(),
            fileId.toByte(), (offset shr 8).toByte(), (offset and 0xFF).toByte(),
            length.toByte()
        )
        return transceiveDetailed(apdu)
    }

    /** 获取数据（GET DATA） */
    fun getData(tag: Int): Result<ApduExchange> {
        val apdu = byteArrayOf(0x80.toByte(), 0xCA.toByte(), tag.toByte(), 0x00, 0x00)
        return transceiveDetailed(apdu)
    }

    companion object {
        /** 协议：T=CL */
        const val PROTOCOL_T_CL = 2

        /** 协议：ISO 7816-3（T=0/T=1） */
        const val PROTOCOL_T_ISO7816 = 4

        /** 协议未知 */
        const val PROTOCOL_UNKNOWN = -1

        /** 61xx / 6Cxx 续传的最大轮数，防止卡片不按规矩响应时死循环 */
        private const val MAX_CHAIN = 16

        /** 提取状态字（最后 2 字节） */
        fun statusWord(response: ByteArray): ByteArray =
            if (response.size >= 2) response.copyOfRange(response.size - 2, response.size)
            else ByteArray(0)

        /**
         * 判断 APDU 是否带 Le 字节（case 2 / case 4）。
         *
         * 6Cxx 重发只能改 Le：case 3（无 Le）时末字节是数据，改它会写坏命令。
         * 判据：长度 5（CLA INS P1 P2 Le）或 Lc+5 == 长度-1（Lc + data + Le）。
         */
        fun hasLe(apdu: ByteArray): Boolean {
            if (apdu.size < 5) return false
            if (apdu.size == 5) return true
            val lc = apdu[4].toInt() and 0xFF
            return lc + 5 == apdu.size - 1
        }

        /** 状态字转可读文本 */
        fun describeStatus(sw: ByteArray): String {
            if (sw.size < 2) return AppStrings.get(R.string.sw_unknown)
            val hi = sw[0].toInt() and 0xFF
            val lo = sw[1].toInt() and 0xFF
            val name = when (hi) {
                0x90 -> AppStrings.get(R.string.sw_success)
                0x61 -> AppStrings.get(R.string.sw_more_data)
                0x6C -> AppStrings.get(R.string.sw_len_error)
                0x62 -> AppStrings.get(R.string.sw_warn_unchanged)
                0x63 -> AppStrings.get(R.string.sw_warn_auth)
                0x64 -> AppStrings.get(R.string.sw_exec_error)
                0x67 -> AppStrings.get(R.string.sw_len_error)
                0x69 -> AppStrings.get(R.string.sw_not_allowed)
                0x6A -> AppStrings.get(R.string.sw_param_error)
                0x6B -> AppStrings.get(R.string.sw_param_error)
                0x6D -> AppStrings.get(R.string.sw_ins_unsupported)
                0x6E -> AppStrings.get(R.string.sw_ins_unsupported)
                0x6F -> AppStrings.get(R.string.sw_len_error)
                else -> AppStrings.get(R.string.sw_status, hi.toString(), lo.toString(16).uppercase())
            }
            return "SW=${hi.toString(16).uppercase().padStart(2, '0')}" +
                "${lo.toString(16).uppercase().padStart(2, '0')} $name"
        }
    }
}

/** 一次 APDU 交互的完整记录 */
data class ApduExchange(
    val command: ByteArray,
    val response: ByteArray,
    val data: ByteArray,
    val statusWord: ByteArray,
) {
    val isSuccess: Boolean
        get() = statusWord.size == 2 && statusWord[0] == 0x90.toByte()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ApduExchange) return false
        return command.contentEquals(other.command) &&
            response.contentEquals(other.response) &&
            statusWord.contentEquals(other.statusWord)
    }

    override fun hashCode(): Int {
        var result = command.contentHashCode()
        result = 31 * result + response.contentHashCode()
        result = 31 * result + statusWord.contentHashCode()
        return result
    }
}
