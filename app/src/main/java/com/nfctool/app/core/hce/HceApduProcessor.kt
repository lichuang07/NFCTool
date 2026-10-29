package com.nfctool.app.core.hce

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import com.nfctool.app.core.nfc.Hex

/**
 * HCE 卡模拟的 APDU 处理器。
 *
 * 手机被当作卡片时，读卡器会向手机发送 ISO 7816-4 格式的 APDU。
 * 本处理器实现一个"通用演示卡"，让手机能响应常见的查询指令，
 * 可用于：
 *  - 验证读卡器与手机之间的链路是否通畅
 *  - 在自助终端 / 门禁读头前演示 NFC 通信流程
 *  - 作为编写自有 HCE 应用的参考实现
 *
 * 支持的指令：
 *  - SELECT AID (00 A4 04 00)     → 返回 FCI（文件控制信息）
 *  - GET DATA (80 CA xx xx 00)   → 返回预设数据
 *  - GET VERSION (00 D2 00 00 ..)→ 返回版本字符串
 *  - GET UID (00 CA 00 00 00)    → 返回模拟卡片的 UID
 */
class HceApduProcessor(
    private val uid: ByteArray,
    private val profile: HceProfile,
) {

    /**
     * 处理一条 APDU。
     * @return 响应字节（数据 + 状态字）
     */
    fun process(command: ByteArray): ByteArray {
        if (command.size < 4) return STATUS_WRONG_LENGTH

        val cla = command[0].toInt() and 0xFF
        val ins = command[1].toInt() and 0xFF
        val p1 = command[2].toInt() and 0xFF
        val p2 = command[3].toInt() and 0xFF

        // 只接受 CLA=0x00 的基本指令
        if (cla != 0x00 && cla != 0x80) return STATUS_CLA_NOT_SUPPORTED

        return when (ins) {
            INS_SELECT -> handleSelect(command, p1, p2)
            INS_GET_DATA -> handleGetData(p1, p2)
            INS_GET_VERSION -> handleGetVersion(command)
            INS_GET_UID -> handleGetUid(command)
            else -> STATUS_INS_NOT_SUPPORTED
        }
    }

    /**
     * SELECT AID。
     *
     * 标准响应应包含 FCI（File Control Information）模板：
     * 6F 09 <len> 84 05 <AID> A0 00 00 01 01 00
     */
    private fun handleSelect(command: ByteArray, p1: Int, p2: Int): ByteArray {
        // P1=0x04 表示按 AID 选择
        if (p1 != 0x04 && p1 != 0x00) return STATUS_WRONG_P1P2

        // 从命令中解析被选中的 AID（第 5 字节起，长度由 Lc 决定）
        val selectedAid = if (command.size > 5) {
            val lc = command[4].toInt() and 0xFF
            val end = (5 + lc).coerceAtMost(command.size)
            command.copyOfRange(5, end)
        } else {
            ByteArray(0)
        }

        // 只响应对自己注册的 AID 的选择
        if (selectedAid.isNotEmpty() && !profile.acceptsAid(selectedAid)) {
            return STATUS_FILE_NOT_FOUND
        }

        val fci = buildFci(selectedAid)
        return fci + STATUS_SUCCESS
    }

    /** 构造 FCI 模板 */
    private fun buildFci(aid: ByteArray): ByteArray {
        val aidBytes = if (aid.isEmpty()) profile.primaryAid else aid
        // FCI 模板：6F len 84 len AID A0 00 00 01 01 00
        val body = byteArrayOf(0x84.toByte(), aidBytes.size.toByte()) + aidBytes +
            byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x01, 0x01, 0x00)
        return byteArrayOf(0x6F, body.size.toByte()) + body
    }

    /**
     * GET DATA。
     * 返回该 P1/P2 对应的数据块；没有配置则返回 6A82（文件未找到）。
     */
    private fun handleGetData(p1: Int, p2: Int): ByteArray {
        val data = profile.getData(p1, p2) ?: return STATUS_FILE_NOT_FOUND
        return data + STATUS_SUCCESS
    }

    /** 返回应用版本信息 */
    private fun handleGetVersion(command: ByteArray): ByteArray {
        val version = profile.versionText.toByteArray(Charsets.UTF_8)
        // Le 字段缺失时补 0x00 表示 256 字节
        val withLe = if (command.size > 5) command else command + byteArrayOf(0x00)
        return version + STATUS_SUCCESS
    }

    /** 返回模拟卡片的 UID */
    private fun handleGetUid(command: ByteArray): ByteArray {
        val withLe = if (command.size > 5) command else command + byteArrayOf(0x00)
        return uid + STATUS_SUCCESS
    }

    companion object {
        const val INS_SELECT = 0xA4
        const val INS_GET_DATA = 0xCA
        const val INS_GET_VERSION = 0xD2

        /** GET UID（与 GET DATA 同 INS，靠 P1/P2 区分） */
        const val INS_GET_UID = 0xCA

        /** 成功 */
        val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)

        /** CLA 不支持 */
        val STATUS_CLA_NOT_SUPPORTED = byteArrayOf(0x6E.toByte(), 0x00)

        /** INS 不支持 */
        val STATUS_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        /** 参数错误 */
        val STATUS_WRONG_P1P2 = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        /** 长度错误 */
        val STATUS_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)

        /** 文件未找到 */
        val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    }
}

/**
 * HCE 卡模拟配置：注册哪些 AID、暴露哪些数据。
 */
data class HceProfile(
    /** 主 AID（十六进制） */
    val primaryAidHex: String,
    /** 附加入 AID 列表 */
    val extraAidHexList: List<String> = emptyList(),
    /** 应用名（显示给读卡器） */
    val applicationLabel: String = AppStrings.get(R.string.hce_card_label),
    /** 版本文本 */
    val versionText: String = "NFCTool 1.0.0",
    /** 数据字典：键为 (P1, P2) */
    val dataMap: Map<Pair<Int, Int>, ByteArray> = emptyMap(),
) {
    val primaryAid: ByteArray by lazy { Hex.parseLenient(primaryAidHex) }

    val allAids: List<ByteArray> by lazy {
        listOf(primaryAid) + extraAidHexList.map { Hex.parseLenient(it) }
    }

    val aidHexList: List<String> by lazy { listOf(primaryAidHex) + extraAidHexList }

    /** 判断是否响应该 AID 的选择 */
    fun acceptsAid(aid: ByteArray): Boolean =
        allAids.any { it.contentEquals(aid) }

    fun getData(p1: Int, p2: Int): ByteArray? = dataMap[Pair(p1, p2)]

    companion object {
        /**
         * 默认配置。
         *
         * 使用一个自定义的厂商私有 AID（F0... 段）作为主 AID，
         * 避免与支付类 AID 冲突 —— 本应用不参与任何支付流程。
         */
        fun default(): HceProfile = HceProfile(
            primaryAidHex = "F0010203040506",
            extraAidHexList = listOf("F0394148148100"),
            applicationLabel = AppStrings.get(R.string.hce_card_label),
            versionText = "NFCTool 1.0.0 · Android HCE",
            dataMap = mapOf(
                // 0x01 0x00：卡片类型标识
                (0x01 to 0x00) to "HCE-DEMO-CARD".toByteArray(Charsets.US_ASCII),
                // 0x01 0x01：应用名
                (0x01 to 0x01) to "NFC 工具箱".toByteArray(Charsets.UTF_8),
                // 0x02 0x00：模拟卡 UID
                (0x02 to 0x00) to ByteArray(0),
            ),
        )
    }
}
