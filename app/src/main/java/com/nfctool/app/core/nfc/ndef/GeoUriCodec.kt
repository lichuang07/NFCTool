package com.nfctool.app.core.nfc.ndef
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings

/**
 * 地理位置 URI 编解码器（RFC 5870 `geo:` URI）。
 *
 * ## 规范依据
 *
 * RFC 5870 定义了 `geo:` URI scheme，用于表达 WGS-84 坐标：
 *
 * ```
 * geo:latitude,longitude[,altitude][;u=uncertainty][?q=label][&z=zoom]
 * ```
 *
 * - 纬度 latitude ∈ [-90, 90]
 * - 经度 longitude ∈ [-180, 180]
 * - 海拔 altitude（可选），单位为米
 * - `u` 参数（可选）：f=米 / m=英里 / km=公里 / mi=英里
 * - `q` 参数（可选）：位置名称
 * - `z` 参数（可选）：缩放级别
 *
 * 示例：
 * ```
 * geo:39.9042,116.4074
 * geo:39.9042,116.4074,52?u=35&q=天安门
 * ```
 *
 * ## 为什么值得单独做一个类型
 *
 * Android 公开 API **没有** `NdefRecord.createGeoUri()`，
 * `NdefRecord` 的工厂方法只有 `createUri` / `createTextRecord` /
 * `createMime` / `createExternal` / `createApplicationRecord`
 * （已用 javap android-37.0 与 AOSP `NdefRecord.java` 核对）。
 *
 * 但 `geo:` URI 本身是**系统原生支持**的：写进 URI 记录后，
 * 对方手机碰一下会直接唤起地图应用并定位到该坐标。
 * 这与 Wi-Fi 配网一样，属于「TNF 层面是 URI，应用层面是独立语义」的情况，
 * 因此需要在这层之上单独归类，否则读取页只会显示一条干巴巴的 URI。
 *
 * 注意：`geo:` 走的是 TNF 0x01（Well-known，type='U'），
 * 即便 payload 前缀码为 0x00（不缩写），也仍然是 URI 记录。
 */
object GeoUriCodec {

    /** `geo:` scheme 前缀 */
    private const val SCHEME = "geo:"

    /**
     * 解析出的地理位置。
     *
     * @param latitude    纬度
     * @param longitude   经度
     * @param altitude    海拔（米），无则 null
     * @param uncertainty 精度（米），无则 null
     * @param label       位置名称，无则 null
     * @param zoom        缩放级别，无则 null
     */
    data class GeoLocation(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        val uncertainty: Double? = null,
        val label: String? = null,
        val zoom: Int? = null,
    ) {
        /** 格式化回标准 geo: URI */
        fun toUri(): String = build(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            uncertainty = uncertainty,
            label = label,
            zoom = zoom,
        )
    }

    /**
     * 判断一个 URI 字符串是否为 geo: URI。
     */
    fun isGeoUri(uri: String?): Boolean {
        if (uri == null) return false
        return uri.trim().startsWith(SCHEME, ignoreCase = true)
    }

    /**
     * 解析 geo: URI；不是合法 geo URI 时返回 null。
     *
     * @param uri 完整 URI，可能带 `geo:` 前缀
     */
    fun parse(uri: String): GeoLocation? {
        val text = uri.trim()
        if (!text.startsWith(SCHEME, ignoreCase = true)) return null
        val body = text.substring(SCHEME.length)
        if (body.isEmpty()) return null

        // 拆出坐标部分与参数部分
        val coordPart = body.substringBefore(';').substringBefore('?')
        val params = body.substringAfter('?', "")

        // 坐标：lat,lon[,alt]
        val pieces = coordPart.split(',')
        if (pieces.size < 2) return null
        val latitude = pieces[0].trim().toDoubleOrNull() ?: return null
        val longitude = pieces[1].trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0) return null
        if (longitude !in -180.0..180.0) return null
        val altitude = pieces.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        var uncertainty: Double? = null
        var label: String? = null
        var zoom: Int? = null

        // 参数形式有两种：
        //   1) geo:lat,lon;u=35?q=名称&z=16
        //   2) geo:lat,lon?q=名称&z=16&u=35
        val semiParams = body.substringAfter(';', "").substringBefore('?')
        if (semiParams.isNotEmpty()) {
            semiParams.split(';').forEach { seg ->
                val key = seg.substringBefore('=').trim()
                val value = seg.substringAfter('=', "").trim()
                if (key.equals("u", ignoreCase = true)) {
                    uncertainty = value.toDoubleOrNull()
                }
            }
        }
        if (params.isNotEmpty()) {
            params.split('&').forEach { seg ->
                val key = seg.substringBefore('=').trim()
                val value = seg.substringAfter('=', "")
                when {
                    key.equals("q", ignoreCase = true) -> label = decodeQuery(value)
                    key.equals("z", ignoreCase = true) -> zoom = value.trim().toIntOrNull()
                    key.equals("u", ignoreCase = true) -> uncertainty = value.trim().toDoubleOrNull()
                }
            }
        }

        return GeoLocation(latitude, longitude, altitude, uncertainty, label, zoom)
    }

    /**
     * 生成 geo: URI。
     */
    fun build(
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        uncertainty: Double? = null,
        label: String? = null,
        zoom: Int? = null,
    ): String {
        require(latitude in -90.0..90.0) { AppStrings.get(R.string.err_geo_lat_range) }
        require(longitude in -180.0..180.0) { AppStrings.get(R.string.err_geo_lon_range) }

        val sb = StringBuilder(SCHEME)
        sb.append(trimNumber(latitude))
        sb.append(',')
        sb.append(trimNumber(longitude))
        if (altitude != null) {
            sb.append(',')
            sb.append(trimNumber(altitude))
        }
        if (uncertainty != null) {
            sb.append(";u=").append(trimNumber(uncertainty))
        }

        val query = mutableListOf<String>()
        label?.takeIf { it.isNotBlank() }?.let { query += "q=" + encodeQuery(it) }
        zoom?.let { query += "z=$it" }
        if (query.isNotEmpty()) {
            sb.append('?')
            sb.append(query.joinToString("&"))
        }
        return sb.toString()
    }

    /**
     * 把坐标格式化为不带多余小数位的字符串。
     */
    private fun trimNumber(value: Double): String {
        // 最多保留 6 位小数，去掉尾部多余的 0
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        val text = rounded.toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }

    /**
     * URI 查询参数编码（保留中文，空格转 %20）。
     */
    private fun encodeQuery(value: String): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-_.~") {
                sb.append(ch)
            } else if (ch == ' ') {
                sb.append("%20")
            } else {
                sb.append('%')
                sb.append(String.format("%02X", byte.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    /**
     * URI 查询参数解码。
     */
    private fun decodeQuery(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                ch == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3)
                    val b = hex.toIntOrNull(16)
                    if (b != null) {
                        bytes.add(b.toByte())
                        i += 3
                    } else {
                        bytes.add(ch.code.toByte())
                        i++
                    }
                }
                else -> {
                    bytes.addAll(ch.toString().toByteArray(Charsets.UTF_8).toList())
                    i++
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
