package com.nfctool.app.core.nfc.ndef

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import java.io.ByteArrayOutputStream

/**
 * 蓝牙 OOB（Out of Band）配对记录编解码器。
 *
 * ## 规范依据
 *
 * 蓝牙核心规范 Core Spec 5.2.2.7（Generic Access Profile → Out of Band）定义了
 * OOB 数据块，并指定了两个 IANA 注册的 MIME 类型：
 *
 * - `application/vnd.bluetooth.ep.oob` —— BR/EDR（经典蓝牙，"Easy Pairing"）
 * - `application/vnd.bluetooth.le.oob` —— Bluetooth LE
 *
 * ### BR/EDR 记录布局
 *
 * ```
 * OOB Data Length (2 字节，小端)   —— 长度包含自身这 2 字节
 * BD_ADDR (6 字节，小端)           —— 蓝牙设备地址
 * EIR / AD 结构 (可变)             —— 可选的附加信息
 * ```
 *
 * BD_ADDR 是**小端序**：MAC 为 `AA:BB:CC:DD:EE:FF` 时，字节序为
 * `FF EE DD CC BB AA`（这是最容易搞反的一点）。
 *
 * ### EIR / AD 结构
 *
 * BD_ADDR 之后是若干「长度 + AD 类型 + 数据」的连续结构，长度包含
 * AD 类型字节自身。常用 AD 类型：
 *
 * | 类型 | 含义 |
 * |---|---|
 * | 0x01 | Flags |
 * | 0x03 | 16-bit Service Class UUID |
 * | 0x09 | Complete Local Name（设备名） |
 * | 0x0E | Secure Simple Pairing Hash C-192（16 字节） |
 * | 0x0F | Secure Simple Pairing Randomizer R-192（16 字节） |
 * | 0x1B | LE Bluetooth Device Address（6 字节） |
 * | 0x1C | LE Role |
 * | 0x1D | Secure Simple Pairing Hash C-256（16 字节） |
 * | 0x1E | Secure Simple Pairing Randomizer R-256（16 字节） |
 * | 0x22 | LE SC Confirmation Value（16 字节） |
 * | 0x23 | LE SC Random Value（16 字节） |
 *
 * ### LE 记录布局
 *
 * LE OOB 记录**没有**长度头与固定 BD_ADDR，直接是一串 AD 结构，
 * 其中地址放在 AD 类型 0x1B 里。
 *
 * ## 重要事实：Android 不自动解析
 *
 * 已核对 AOSP `packages/apps/Nfc` 的 `NfcService.java`，
 * **没有**任何针对 `application/vnd.bluetooth.ep.oob` /
 * `application/vnd.bluetooth.le.oob` 的特殊处理，
 * `android.jar` 里也没有对应的常量。
 *
 * 也就是说：Android 系统只会把它当作普通 MIME 记录派发
 * （ACTION_NDEF_DISCOVERED，MIME 为该类型）。
 * 真正触发配对的是**厂商 App**（耳机/音箱厂商自己注册的 IntentFilter）。
 *
 * 老式 Android Beam 时代（4.x）系统内置解析过这类记录并自动发起配对，
 * 但 Android Beam 已在 Android 10 废弃、Android 14 移除，这条路已经不通。
 *
 * 因此本 codec 的定位是：**结构正确的写入 + 清晰的解析展示**，
 * 让用户能生成符合蓝牙规范的 OOB 记录（供厂商 App 或产线工具消费），
 * 以及读懂别人写的蓝牙配对标签。
 */
object BluetoothOobCodec {

    /** BR/EDR（Easy Pairing）OOB */
    const val MIME_BR_EDR = "application/vnd.bluetooth.ep.oob"

    /** Bluetooth LE OOB */
    const val MIME_LE = "application/vnd.bluetooth.le.oob"

    private val MIME_ALIASES = setOf(MIME_BR_EDR, MIME_LE)

    // ---- AD / EIR 类型 ----

    const val AD_FLAGS = 0x01
    const val AD_CLASS_OF_DEVICE = 0x02
    const val AD_SERVICE_CLASS_UUID_16 = 0x03
    const val AD_SERVICE_CLASS_UUID_32 = 0x04
    const val AD_SERVICE_CLASS_UUID_128 = 0x05
    const val AD_COMPLETE_LOCAL_NAME = 0x09
    const val AD_SERVICE_UUID_16 = 0x03
    const val AD_HASH_C_192 = 0x0E
    const val AD_RANDOMIZER_R_192 = 0x0F
    const val AD_LE_DEVICE_ADDRESS = 0x1B
    const val AD_LE_ROLE = 0x1C
    const val AD_HASH_C_256 = 0x1D
    const val AD_RANDOMIZER_R_256 = 0x1E
    const val AD_LE_SC_CONFIRMATION = 0x22
    const val AD_LE_SC_RANDOM = 0x23

    /**
     * 一条 AD（Attribute Data）/ EIR 结构。
     */
    data class AdStructure(
        val type: Int,
        val data: ByteArray,
    ) {
        val typeName: String
            get() = adTypeName(type)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AdStructure) return false
            return type == other.type && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * type + data.contentHashCode()
    }

    /**
     * 解析出的蓝牙 OOB 信息。
     */
    data class BluetoothOob(
        val isLe: Boolean,
        val address: String?,
        val deviceName: String?,
        val adStructures: List<AdStructure>,
    ) {
        /** 取出指定类型的 AD 数据 */
        fun dataOf(type: Int): ByteArray? =
            adStructures.firstOrNull { it.type == type }?.data

        /** 是否携带 SSP 校验材料（Hash C / Randomizer R） */
        val hasSspMaterial: Boolean
            get() = adStructures.any {
                it.type == AD_HASH_C_192 || it.type == AD_RANDOMIZER_R_192 ||
                    it.type == AD_HASH_C_256 || it.type == AD_RANDOMIZER_R_256 ||
                    it.type == AD_LE_SC_CONFIRMATION || it.type == AD_LE_SC_RANDOM
            }
    }

    /**
     * 判断一条 NDEF 记录是否为蓝牙 OOB 记录。
     */
    fun isOobRecord(mimeType: String): Boolean =
        mimeType.trim().lowercase() in MIME_ALIASES

    /**
     * 判断是否为 LE 类型的 OOB 记录。
     */
    fun isLe(mimeType: String): Boolean =
        mimeType.trim().equals(MIME_LE, ignoreCase = true)

    /**
     * 解析蓝牙 OOB payload。
     *
     * @param payload 记录 payload
     * @param isLe    true 按 LE 格式解析（无长度头）
     */
    fun parse(payload: ByteArray, isLe: Boolean): BluetoothOob? {
        if (payload.isEmpty()) return null

        var offset = 0
        var address: String? = null

        if (!isLe) {
            // BR/EDR：2 字节小端长度 + 6 字节小端 BD_ADDR
            if (payload.size < 8) return null
            val declared = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
            if (declared < 8) return null
            // 声明长度可能超过实际数据（标签被截断），取实际可用长度
            val limit = minOf(declared, payload.size)
            address = formatAddress(payload, 2, littleEndian = true)
            offset = 8
            if (limit <= offset) {
                return BluetoothOob(isLe = false, address = address, deviceName = null, adStructures = emptyList())
            }
            // 只在声明的长度范围内解析 AD 结构
            val ads = parseAdStructures(payload.copyOfRange(0, limit), offset)
                ?: return null
            val name = ads.firstOrNull { it.type == AD_COMPLETE_LOCAL_NAME }
                ?.data
                ?.let { String(it, Charsets.UTF_8) }
            return BluetoothOob(
                isLe = false,
                address = address,
                deviceName = name,
                adStructures = ads,
            )
        }

        val ads = parseAdStructures(payload, offset) ?: return null
        val leAddress = ads.firstOrNull { it.type == AD_LE_DEVICE_ADDRESS }
            ?.data
            ?.let { formatAddress(it, 0, littleEndian = true) }

        val name = ads.firstOrNull { it.type == AD_COMPLETE_LOCAL_NAME }
            ?.data
            ?.let { String(it, Charsets.UTF_8) }

        return BluetoothOob(
            isLe = isLe,
            address = address ?: leAddress,
            deviceName = name,
            adStructures = ads,
        )
    }

    /**
     * 组装 BR/EDR OOB payload。
     *
     * @param address 蓝牙设备地址，格式 `AA:BB:CC:DD:EE:FF` 或 `AABBCCDDEEFF`
     * @param ads     可选的 AD 结构
     */
    fun buildBrEdr(address: String, ads: List<AdStructure> = emptyList()): ByteArray {
        val addr = parseAddress(address) ?: error(AppStrings.get(R.string.err_bt_address_format, address))
        val adBytes = encodeAdStructures(ads)
        // 规范要求长度字段包含自身这 2 字节
        val total = 8 + adBytes.size
        val out = ByteArrayOutputStream()
        out.write(total and 0xFF)          // 长度，小端
        out.write((total shr 8) and 0xFF)
        out.write(addr.reversedArray())   // BD_ADDR 小端序
        out.write(adBytes)
        return out.toByteArray()
    }

    /**
     * 组装 LE OOB payload（纯 AD 结构序列，无长度头）。
     */
    fun buildLe(
        address: String? = null,
        ads: List<AdStructure> = emptyList(),
    ): ByteArray {
        val all = mutableListOf<AdStructure>()
        if (address != null) {
            val addr = parseAddress(address) ?: error(AppStrings.get(R.string.err_bt_address_format, address))
            all += AdStructure(AD_LE_DEVICE_ADDRESS, addr.reversedArray())
        }
        all += ads
        return encodeAdStructures(all)
    }

    /**
     * 把 AD 结构列表序列化为字节。
     *
     * 每个结构的布局为 `[Length(1)][Type(1)][Data]`，
     * Length = 类型(1) + 数据长度，**不含 Length 自身**。
     */
    fun encodeAdStructures(ads: List<AdStructure>): ByteArray {
        val out = ByteArrayOutputStream()
        ads.forEach { ad ->
            require(ad.data.size <= 0xFF) { AppStrings.get(R.string.err_bt_ad_too_long) }
            out.write(ad.data.size + 1)      // Length：类型字节 + 数据
            out.write(ad.type and 0xFF)
            out.write(ad.data)
        }
        return out.toByteArray()
    }

    /**
     * 解析连续的 AD 结构。
     *
     * 蓝牙规范中 Length 字段表示「AD 类型 + AD 数据」的字节数，
     * **不含 Length 字段自身**，因此：
     *
     * ```
     * [Length(1)][Type(1)][Data(length - 1)]   结构总长 = 1 + length
     * ```
     */
    fun parseAdStructures(payload: ByteArray, offset: Int): List<AdStructure>? {
        val out = mutableListOf<AdStructure>()
        var i = offset
        while (i < payload.size) {
            val length = payload[i].toInt() and 0xFF
            if (length == 0) break
            // 至少要有 长度字节 + 类型字节
            if (length < 2) return null
            // 结构总长 = 1 + length
            if (i + 1 + length > payload.size) return null
            val type = payload[i + 1].toInt() and 0xFF
            val data = payload.copyOfRange(i + 2, i + 1 + length)
            out += AdStructure(type, data)
            i += 1 + length
        }
        return out
    }

    /**
     * 把 MAC 字节解析为可读地址。
     *
     * @param littleEndian true 表示字节是小端序（BD_ADDR 规范要求）
     */
    private fun formatAddress(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        if (bytes.size < offset + 6) return ""
        val mac = bytes.copyOfRange(offset, offset + 6)
        // 规范里 BD_ADDR 是小端存放，显示时需还原成大端（MSB first）
        val display = if (littleEndian) mac.reversedArray() else mac
        return display.joinToString(":") { "%02X".format(it) }
    }

    /**
     * 把可读地址解析为 6 字节（显示序，MSB first）。
     */
    fun parseAddress(address: String): ByteArray? {
        val hex = address.replace(":", "").replace("-", "").replace(" ", "")
        if (hex.length != 12) return null
        if (!hex.matches(Regex("[0-9a-fA-F]{12}"))) return null
        val out = ByteArray(6)
        for (i in 0 until 6) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /**
     * 把 AD 类型 0x1B（LE 设备地址）的数据格式化为可读地址。
     *
     * 该 AD 内的地址同样按小端存放，显示时需还原。
     */
    fun formatLeAddress(data: ByteArray): String {
        if (data.size < 6) return ""
        return data.reversedArray().joinToString(":") { "%02X".format(it) }
    }

    /**
     * AD 类型的可读名称。
     */
    fun adTypeName(type: Int): String = when (type) {
        AD_FLAGS -> "Flags"
        AD_CLASS_OF_DEVICE -> "Class of Device"
        AD_SERVICE_CLASS_UUID_16 -> "Service Class UUID (16-bit)"
        AD_SERVICE_CLASS_UUID_32 -> "Service Class UUID (32-bit)"
        AD_SERVICE_CLASS_UUID_128 -> "Service Class UUID (128-bit)"
        AD_COMPLETE_LOCAL_NAME -> AppStrings.get(R.string.bt_ad_complete_name)
        AD_HASH_C_192 -> "SSP Hash C-192"
        AD_RANDOMIZER_R_192 -> "SSP Randomizer R-192"
        AD_LE_DEVICE_ADDRESS -> AppStrings.get(R.string.bt_ad_le_address)
        AD_LE_ROLE -> AppStrings.get(R.string.bt_ad_le_role)
        AD_HASH_C_256 -> "SSP Hash C-256"
        AD_RANDOMIZER_R_256 -> "SSP Randomizer R-256"
        AD_LE_SC_CONFIRMATION -> AppStrings.get(R.string.bt_ad_le_sc_confirm)
        AD_LE_SC_RANDOM -> AppStrings.get(R.string.bt_ad_le_sc_random)
        else -> AppStrings.get(R.string.bt_ad_unknown, type.toString(16).uppercase())
    }
}
