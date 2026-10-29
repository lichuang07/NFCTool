package com.nfctool.app.core.nfc.ndef

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.nfc.NdefRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wi-Fi Simple Configuration（WSC）编解码器。
 *
 * ## 规范事实（已核对 AOSP 源码 `NfcWifiProtectedSetup.java`）
 *
 * Android 系统识别 Wi-Fi 标签时，匹配的是 **MIME 类型记录**：
 *
 * ```
 * NFC_TOKEN_MIME_TYPE = "application/vnd.wfa.wsc"
 * ```
 *
 * payload 为一层 WSC TLV（16 位 type + 16 位 length + value，均为大端）：
 *
 * ```
 * 0x100E  Credential      复合字段，内部再套一层同名 TLV
 *   0x1045  SSID           网络名（UTF-8）
 *   0x1027  Network Key    密码（明文，最大 64 字节）
 *   0x1003  Auth Type      认证类型（2 字节）
 * ```
 *
 * 认证类型取值（AOSP 常量）：
 * `0x0001` Open、`0x0002` WPA-PSK、`0x0008` WPA-EAP、
 * `0x0010` WPA2-EAP、`0x0020` WPA2-PSK、`0x0022` WPA/WPA2-PSK 混合。
 *
 * ## 与"扫一眼就自动连"的关系
 *
 * 系统侧逻辑在 `parseCredential`：SSID 必须存在；若认证类型不是 Open，
 * 则密码也必须存在，否则整条记录被判定为无效、不会触发连接。
 * 因此本实现**不允许**写出"有认证类型但没密码"的组合。
 *
 * 注意：iOS 不会因该记录自动联网，只会在通知栏提示，需手动到设置确认。
 */
object WifiWscCodec {

    /** Android 系统识别的 MIME 类型 */
    const val MIME_TYPE = "application/vnd.wfa.wsc"

    /** 部分旧工具使用的别名，读的时候一并识别 */
    private const val MIME_TYPE_ALIAS = "application/vnd.wifi.wsc"

    // ---- WSC 属性 ID（来自 AOSP NfcWifiProtectedSetup）----
    private const val ATTR_CREDENTIAL = 0x100E
    private const val ATTR_SSID = 0x1045
    private const val ATTR_NETWORK_KEY = 0x1027
    private const val ATTR_AUTH_TYPE = 0x1003

    // ---- 认证类型 ----
    const val AUTH_OPEN = 0x0001
    const val AUTH_WPA_PSK = 0x0002
    const val AUTH_WPA_EAP = 0x0008
    const val AUTH_WPA2_EAP = 0x0010
    const val AUTH_WPA2_PSK = 0x0020
    const val AUTH_WPA_PSK_WPA2_PSK = 0x0022

    /** 密码最大长度（WSC 规定 64 字节） */
    private const val MAX_KEY_SIZE = 64

    /**
     * Wi-Fi 配网信息。
     */
    data class WifiConfig(
        val ssid: String,
        val password: String,
        val authType: Int,
        val hidden: Boolean = false,
    ) {
        /** 是否为开放网络（无密码） */
        val isOpen: Boolean get() = authType == AUTH_OPEN
    }

    /**
     * 判断一条记录是否为 Wi-Fi 配网记录。
     */
    fun isWifiRecord(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_MIME_MEDIA) return false
        val type = String(record.type, Charsets.UTF_8)
        return type.equals(MIME_TYPE, ignoreCase = true) ||
            type.equals(MIME_TYPE_ALIAS, ignoreCase = true)
    }

    /**
     * 解析 Wi-Fi 配网记录，返回 null 表示不是有效的配网记录。
     */
    fun decode(record: NdefRecord): WifiConfig? {
        if (!isWifiRecord(record)) return null
        return decodePayload(record.payload)
    }

    /**
     * 纯字节解析，不依赖 Android API，便于单元测试。
     */
    fun decodePayload(payload: ByteArray): WifiConfig? {
        var ssid: String? = null
        var password: String? = null
        var authType: Int? = null
        var hidden = false

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        while (buffer.remaining() >= 4) {
            val fieldId = buffer.short.toInt() and 0xFFFF
            val fieldSize = buffer.short.toInt() and 0xFFFF
            if (fieldSize > buffer.remaining()) break

            if (fieldId == ATTR_CREDENTIAL) {
                // Credential 是复合字段，内部再是一层 TLV
                val inner = buffer.slice().limit(fieldSize) as ByteBuffer
                inner.order(ByteOrder.BIG_ENDIAN)
                while (inner.remaining() >= 4) {
                    val subId = inner.short.toInt() and 0xFFFF
                    val subSize = inner.short.toInt() and 0xFFFF
                    if (subSize > inner.remaining()) break
                    val value = ByteArray(subSize)
                    inner.get(value)
                    when (subId) {
                        ATTR_SSID -> {
                            ssid = String(value, Charsets.UTF_8)
                            // 空 SSID 表示隐藏网络
                            if (ssid.isEmpty()) hidden = true
                        }
                        ATTR_NETWORK_KEY -> password = String(value, Charsets.UTF_8)
                        ATTR_AUTH_TYPE ->
                            if (value.size == 2) {
                                authType = ((value[0].toInt() and 0xFF) shl 8) or
                                    (value[1].toInt() and 0xFF)
                            }
                    }
                }
                buffer.position(buffer.position() + fieldSize)
            } else {
                // 顶层非 Credential 属性（有的写入工具会把字段平铺在顶层）
                val value = ByteArray(fieldSize)
                buffer.get(value)
                when (fieldId) {
                    ATTR_SSID -> {
                        ssid = String(value, Charsets.UTF_8)
                        if (ssid.isEmpty()) hidden = true
                    }
                    ATTR_NETWORK_KEY -> password = String(value, Charsets.UTF_8)
                    ATTR_AUTH_TYPE ->
                        if (value.size == 2) {
                            authType = ((value[0].toInt() and 0xFF) shl 8) or
                                (value[1].toInt() and 0xFF)
                        }
                }
            }
        }

        val name = ssid ?: return null
        val auth = authType ?: return null

        // 与系统一致的校验：非开放网络必须有密码
        if (auth != AUTH_OPEN && password.isNullOrEmpty()) return null
        if (auth == AUTH_OPEN && !password.isNullOrEmpty()) return null

        return WifiConfig(
            ssid = name,
            password = password.orEmpty(),
            authType = auth,
            hidden = hidden,
        )
    }

    /**
     * 把配网信息编码为 WSC payload。
     *
     * 结构与 AOSP 解析逻辑严格对应：
     * ```
     * 0x100E Credential
     *   0x1045 SSID
     *   0x1027 Network Key
     *   0x1003 Auth Type
     * ```
     */
    fun encode(config: WifiConfig): ByteArray {
        require(config.ssid.isNotEmpty()) { AppStrings.get(R.string.err_wifi_ssid_empty) }
        require(config.password.length <= MAX_KEY_SIZE) {
            AppStrings.get(R.string.err_wifi_password_len, MAX_KEY_SIZE, config.password.length)
        }
        if (config.authType != AUTH_OPEN) {
            require(config.password.isNotEmpty()) { AppStrings.get(R.string.err_wifi_password_required) }
        }

        val credential = buildCredential(config)
        val out = ByteBuffer.allocate(4 + credential.size).order(ByteOrder.BIG_ENDIAN)
        out.putShort(ATTR_CREDENTIAL.toShort())
        out.putShort(credential.size.toShort())
        out.put(credential)
        return out.array()
    }

    /**
     * 构造一条 Wi-Fi 配网 NDEF 记录。
     */
    fun createRecord(config: WifiConfig): NdefRecord =
        NdefRecord.createMime(MIME_TYPE, encode(config))

    private fun buildCredential(config: WifiConfig): ByteArray {
        val ssidBytes = config.ssid.toByteArray(Charsets.UTF_8)
        val keyBytes = config.password.toByteArray(Charsets.UTF_8)
        val authBytes = byteArrayOf(
            ((config.authType shr 8) and 0xFF).toByte(),
            (config.authType and 0xFF).toByte(),
        )

        var size = 0
        size += 4 + ssidBytes.size
        if (keyBytes.isNotEmpty()) size += 4 + keyBytes.size
        size += 4 + authBytes.size

        val out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        out.putShort(ATTR_SSID.toShort())
        out.putShort(ssidBytes.size.toShort())
        out.put(ssidBytes)

        if (keyBytes.isNotEmpty()) {
            out.putShort(ATTR_NETWORK_KEY.toShort())
            out.putShort(keyBytes.size.toShort())
            out.put(keyBytes)
        }

        out.putShort(ATTR_AUTH_TYPE.toShort())
        out.putShort(authBytes.size.toShort())
        out.put(authBytes)

        return out.array()
    }

    /**
     * 认证类型的中文名，用于 UI 展示。
     */
    fun authTypeName(authType: Int): String = when (authType) {
        AUTH_OPEN -> AppStrings.get(R.string.wifi_auth_open)
        AUTH_WPA_PSK -> "WPA-PSK"
        AUTH_WPA_EAP -> "WPA-EAP"
        AUTH_WPA2_EAP -> "WPA2-EAP"
        AUTH_WPA2_PSK -> "WPA2-PSK"
        AUTH_WPA_PSK_WPA2_PSK -> AppStrings.get(R.string.wifi_auth_mixed)
        else -> AppStrings.get(R.string.wifi_auth_unknown, authType.toString(16).uppercase())
    }

    /**
     * 把认证类型翻译成二维码那种通用写法里的标识，便于用户对照。
     */
    fun authTypeShortName(authType: Int): String = when (authType) {
        AUTH_OPEN -> "nopass"
        AUTH_WPA_PSK, AUTH_WPA2_PSK, AUTH_WPA_PSK_WPA2_PSK -> "WPA"
        AUTH_WPA_EAP, AUTH_WPA2_EAP -> "WPA2-EAP"
        else -> "WPA"
    }
}
