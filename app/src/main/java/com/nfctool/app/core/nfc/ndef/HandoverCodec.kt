package com.nfctool.app.core.nfc.ndef

import androidx.annotation.StringRes
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.nfc.NdefRecord

/**
 * NFC Forum Connection Handover（连接移交）记录解析器。
 *
 * Handover 用于点对点把「用哪种无线技术继续通信」的选择权交给对端，
 * 例如 NFC 碰一下后改用蓝牙/Wi-Fi 传输。
 *
 * 两种 well-known 类型（AOSP `NdefRecord` 常量）：
 *  - `Hr` = Handover Request（选择请求）
 *  - `Hs` = Handover Select（选择应答）
 *
 * payload 结构（NFC Forum Connection Handover 规范）：
 * ```
 * version (1 字节)
 * number of alternatives (1 字节)
 * 重复：
 *   carrier type id (1 字节)
 *   carrier data length (1 字节)
 *   carrier data (可变)
 * ```
 *
 * 常见的 carrier type id：
 * `0x00` NFC Forum Device、`0x01` Bluetooth、`0x02` Wi-Fi、
 * `0x03` URI / `0x04` 未知。
 *
 * 实用性说明：Android 早期（4.x）的 NfcHandover 只实现了蓝牙音频，
 * 现代 Android 已基本不用这套机制，因此这里只做**只读解析与展示**。
 */
object HandoverCodec {

    /** Handover Request */
    private val RTD_REQUEST = "Hr".toByteArray(Charsets.US_ASCII)

    /** Handover Select */
    private val RTD_SELECT = "Hs".toByteArray(Charsets.US_ASCII)

    // ---- carrier type id ----

    /** NFC Forum Device */
    const val CARRIER_NFC_DEVICE = 0x00

    /** Bluetooth */
    const val CARRIER_BLUETOOTH = 0x01

    /** Wi-Fi */
    const val CARRIER_WIFI = 0x02

    /** URI */
    const val CARRIER_URI = 0x03

    /**
     * 可选的移交载体类型，供写入侧使用。
     */
    enum class CarrierType(val id: Int, val label: String, @StringRes val hintRes: Int) {
        NFC_DEVICE(CARRIER_NFC_DEVICE, "NFC Forum Device", R.string.carrier_nfc_desc),
        BLUETOOTH(CARRIER_BLUETOOTH, "Bluetooth", R.string.carrier_bt_desc),
        WIFI(CARRIER_WIFI, "Wi-Fi", R.string.carrier_wifi_desc),
        URI(CARRIER_URI, "URI", R.string.carrier_uri_desc),
        ;

        companion object {
            fun byId(id: Int): CarrierType? = entries.firstOrNull { it.id == id }
        }
    }

    data class Handover(
        val isRequest: Boolean,
        val version: Int,
        val alternatives: List<Alternative>,
    )

    data class Alternative(
        val carrierType: Int,
        val carrierName: String,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Alternative) return false
            return carrierType == other.carrierType && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * carrierType + data.contentHashCode()
    }

    fun isHandoverRecord(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return false
        val type = record.type
        return type.contentEquals(RTD_REQUEST) || type.contentEquals(RTD_SELECT)
    }

    /**
     * 解析 Handover 记录；格式非法返回 null。
     */
    fun decode(record: NdefRecord): Handover? {
        if (!isHandoverRecord(record)) return null
        val isRequest = record.type.contentEquals(RTD_REQUEST)
        val payload = record.payload
        if (payload.size < 2) return null

        val version = payload[0].toInt() and 0xFF
        val count = payload[1].toInt() and 0xFF

        val alternatives = mutableListOf<Alternative>()
        var index = 2
        repeat(count) {
            if (index + 2 > payload.size) return null
            val carrierType = payload[index].toInt() and 0xFF
            val length = payload[index + 1].toInt() and 0xFF
            index += 2
            if (index + length > payload.size) return null
            val data = payload.copyOfRange(index, index + length)
            index += length
            alternatives += Alternative(carrierType, carrierName(carrierType), data)
        }

        return Handover(isRequest, version, alternatives)
    }

    private fun carrierName(type: Int): String = when (type) {
        0x00 -> "NFC Forum Device"
        0x01 -> "Bluetooth"
        0x02 -> "Wi-Fi"
        0x03 -> "URI"
        0x04 -> "Unknown"
        else -> AppStrings.get(R.string.carrier_unknown, type.toString(16).uppercase())
    }

    /**
     * 组装 Handover 记录的 payload（纯字节，不依赖 Android API，便于单测）。
     *
     * @param carriers 载体列表，每个为 (carrier type id, 载体数据)
     */
    fun encodePayload(carriers: List<Pair<Int, ByteArray>>): ByteArray {
        require(carriers.isNotEmpty()) { AppStrings.get(R.string.err_ho_no_carrier) }
        val payload = ByteArray(2 + carriers.sumOf { 2 + it.second.size })
        var index = 0
        payload[index++] = 0x01 // version
        payload[index++] = (carriers.size and 0xFF).toByte()
        carriers.forEach { (type, data) ->
            require(data.size <= 0xFF) { AppStrings.get(R.string.err_ho_data_too_long) }
            payload[index++] = (type and 0xFF).toByte()
            payload[index++] = (data.size and 0xFF).toByte()
            System.arraycopy(data, 0, payload, index, data.size)
            index += data.size
        }
        return payload
    }

    /**
     * 构造一条 Handover 记录。
     *
     * @param isRequest true 生成 `Hr`（Handover Request），false 生成 `Hs`（Handover Select）
     * @param carriers  候选载体列表，每个为 (carrier type id, 载体数据)
     */
    fun build(isRequest: Boolean, carriers: List<Pair<Int, ByteArray>>): NdefRecord =
        NdefRecord(
            NdefRecord.TNF_WELL_KNOWN,
            if (isRequest) RTD_REQUEST else RTD_SELECT,
            ByteArray(0),
            encodePayload(carriers),
        )

    /**
     * 解析 Handover payload（纯字节）。
     */
    fun decodePayload(payload: ByteArray, isRequest: Boolean = true): Handover? {
        if (payload.size < 2) return null

        val version = payload[0].toInt() and 0xFF
        val count = payload[1].toInt() and 0xFF

        val alternatives = mutableListOf<Alternative>()
        var index = 2
        repeat(count) {
            if (index + 2 > payload.size) return null
            val carrierType = payload[index].toInt() and 0xFF
            val length = payload[index + 1].toInt() and 0xFF
            index += 2
            if (index + length > payload.size) return null
            val data = payload.copyOfRange(index, index + length)
            index += length
            alternatives += Alternative(carrierType, carrierName(carrierType), data)
        }

        return Handover(isRequest, version, alternatives)
    }
}
