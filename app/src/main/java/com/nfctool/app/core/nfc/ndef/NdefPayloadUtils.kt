package com.nfctool.app.core.nfc.ndef

/**
 * NDEF payload 的纯字节判定工具。
 *
 * ## 为什么单独抽出来
 *
 * Android 的 `NdefRecord` 在 JUnit 里不可用（android.jar 只是桩），
 * 所以凡是「只依赖 payload 字节、不需要 NdefRecord」的判定逻辑，
 * 都放在这里做成纯函数，才能写单元测试。
 *
 * ## AID 记录的特殊性
 *
 * NFC Forum Type 4 标签的 AID（Application Identifier）记录，
 * 规范里是 TNF 0x08。但 Android 公开 API **没有** `TNF_AID` 常量，
 * `NdefRecord` 构造函数也不接受它。
 *
 * 因此本项目写入 AID 时用的是 `TNF_ABSOLUTE_URI` + payload 放 AID 字节
 * （见 [com.nfctool.app.core.nfc.NdefCodec.createAidRecord]）。
 * 代价是：读回来时无法凭 TNF 区分「真 URI」和「AID 字节」，
 * 只能靠 payload 内容来区分 ——
 * 合法 URI 一定是可打印 ASCII 且含 scheme 冒号；
 * 而 AID 是二进制（如 NFC Forum Type 4 的 `D2760000850101`）。
 */
object NdefPayloadUtils {

    /** AID 字节长度下限（ISO 7816-4 规定 AID 为 5~16 字节） */
    const val AID_MIN_LENGTH = 5

    /** AID 字节长度上限 */
    const val AID_MAX_LENGTH = 16

    /**
     * 判断 payload 是否是 AID 字节（而非 URI 文本）。
     *
     * 判据（需同时满足）：
     *  1. 长度在 5~16 字节之间
     *  2. 不是合法的 URI 文本 —— 合法 URI 必为可打印 ASCII 且含 `:`，
     *     而 AID 通常含非 ASCII 字节
     */
    fun isAidPayload(payload: ByteArray): Boolean {
        if (payload.size < AID_MIN_LENGTH || payload.size > AID_MAX_LENGTH) return false
        if (isUriText(payload)) return false
        return true
    }

    /**
     * 判断 payload 是否像一段 URI 文本。
     *
     * 判据：全部字节为可打印 ASCII，且第 1 个 `:` 出现在合理位置
     * （scheme 不能为空、不能超过 32 字节）。
     */
    fun isUriText(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val colon = payload.indexOf(':'.code.toByte())
        if (colon <= 0 || colon > 32) return false
        for (b in payload) {
            val v = b.toInt() and 0xFF
            // 可打印 ASCII：0x20(空格) ~ 0x7E(~)
            if (v < 0x20 || v > 0x7E) return false
        }
        // scheme 首字符必须是字母
        val first = (payload[0].toInt() and 0xFF)
        val isLetter = (first in 'a'.code..'z'.code) || (first in 'A'.code..'Z'.code)
        return isLetter
    }

    /**
     * 判断 payload 是否「看起来像文本」。
     *
     * 用于 MIME 记录的内容预览：含大量可打印字符且无 NUL 字节时，
     * 直接当文本展示比只给 hex 更有用。
     *
     * 判据要覆盖 **UTF-8 多字节序列**：
     *  - ASCII：0x20 ~ 0x7E
     *  - UTF-8 首字节：0xC0 ~ 0xDF
     *  - UTF-8 续字节：0x80 ~ 0xBF（不能单独判为可打印，
     *    但如果整体是合法 UTF-8 就应算作文本）
     *
     * 所以这里用的是「**能按 UTF-8 正确解码且不含控制字符**」的判据，
     * 而不是逐字节数可打印字符 —— 否则中文会被误判成二进制。
     */
    fun isProbablyText(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        if (payload.size > 2048) return false
        // 含 NUL 或控制字符 → 视为二进制
        for (b in payload) {
            val v = b.toInt() and 0xFF
            if (v == 0x00) return false
            if (v < 0x20 && v != 0x09 && v != 0x0A && v != 0x0D) return false
        }
        // 能被 UTF-8 正确解码（不抛异常且不产生替换字符）→ 是文本
        val decoded = String(payload, Charsets.UTF_8)
        if (decoded.contains('�')) return false
        return true
    }

    /**
     * 把字节数组按十六进制格式化（带空格分隔）。
     */
    fun toHexSpaced(payload: ByteArray): String =
        payload.joinToString(" ") { "%02X".format(it) }
}
