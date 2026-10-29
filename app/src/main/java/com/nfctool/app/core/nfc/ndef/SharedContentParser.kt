package com.nfctool.app.core.nfc.ndef

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings

/**
 * 分享内容解析器：把别的应用分享过来的文字解析成可写入标签的结构。
 *
 * ## 场景
 *
 * 用户在地图应用里点「分享」，或者在浏览器里复制链接，
 * 选择本应用后，应当**自动填好表单**，而不是让用户手动敲坐标和地址。
 *
 * ## 支持的来源
 *
 * | 来源 | 分享出来的形态 | 处理 |
 * |---|---|---|
 * | 地图应用「分享地点」 | `geo:39.9,116.4?q=天安门` | 直接解析坐标 |
 * | Google 地图 | `https://maps.google.com/?q=39.9,116.4` 或 `https://www.google.com/maps/place/.../@39.9,116.4,15z` | 提取坐标 |
 * | 高德地图 | `https://uri.amap.com/marker?position=116.4,39.9&name=天安门` | ⚠️ **经度在前** |
 * | 百度地图 | `https://api.map.baidu.com/marker?location=39.9,116.4&title=天安门` | 纬度在前 |
 * | 腾讯地图 | `https://apis.map.qq.com/uri/v1/marker?marker=coord:39.9,116.4;title:天安门` | 纬度在前 |
 * | 浏览器 | `https://example.com/page` | 作为链接写入 |
 * | 备忘录 / 微信 | 一段纯文本 | 作为纯文本写入 |
 * | 通讯录 / 微信「分享联系人」 | `BEGIN:VCARD...END:VCARD` | 预填电子名片 |
 * | 相册 / 图库 | `image` 通配 MIME 的 content URI | 写入图片 MIME 记录 |
 *
 * ## 经纬度顺序是最大的坑
 *
 * 高德用 `position=经度,纬度`，百度用 `location=纬度,经度`，
 * 腾讯用 `coord:纬度,经度` —— 搞反了会写到地球另一半。
 * 所以每种来源必须**独立**解析，不能套用同一个模板。
 *
 * ## 坐标系说明
 *
 * 高德/百度/腾讯使用各自的 GCJ-02 加密坐标系，
 * 而 `geo:` URI 按 RFC 5870 期望 WGS-84。
 * 本应用不做坐标系转换（离线无密钥也无法可靠转换），
 * 但会把这个事实如实展示给用户，避免误导。
 */
object SharedContentParser {

    /**
     * 解析结果的类型。
     */
    enum class Kind {
        /** 纯文本 */
        TEXT,

        /** 普通链接 */
        URI,

        /** 地理位置 */
        GEO,

        /** 电子名片（vCard） */
        VCARD,

        /** 图片 */
        IMAGE,
    }

    /**
     * 解析结果。
     *
     * @param kind         结果类型
     * @param text         原始分享文本
     * @param uri          URI 类型时可直接写入的地址
     * @param latitude     GEO 类型的纬度
     * @param longitude    GEO 类型的经度
     * @param label        GEO 类型的位置名称（可能为空）
     * @param title        网页标题（浏览器分享时通常来自 `EXTRA_SUBJECT`）
     * @param source       来源 App 的可读名称（已本地化），用于提示
     * @param sourceKey    来源的稳定标识（amap/baidu/qq/google/contact/image/geo_uri），
     *                     与语言无关，便于测试与程序判断
     * @param coordinateSystem 坐标系说明（如 GCJ-02 / WGS-84），无则为 null
     * @param vcard        VCARD 类型的解析结果
     * @param imageBytes   IMAGE 类型的图片字节
     * @param imageMime    IMAGE 类型的图片 MIME（如 image/jpeg）
     * @param error        识别失败的原因；非 null 表示**识别到了格式但内容非法**，
     *                    此时不应静默降级为普通文本，否则用户会写入一个
     *                    「碰一下毫无反应」的标签而不知情
     */
    data class Parsed(
        val kind: Kind,
        val text: String,
        val uri: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val label: String? = null,
        val title: String? = null,
        val source: String? = null,
        val sourceKey: String? = null,
        val coordinateSystem: String? = null,
        val vcard: VCardCodec.VCard? = null,
        val imageBytes: ByteArray? = null,
        val imageMime: String? = null,
        val error: String? = null,
    ) {
        /** 是否为可用的解析结果 */
        val isValid: Boolean get() = error == null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return kind == other.kind && text == other.text && uri == other.uri &&
                latitude == other.latitude && longitude == other.longitude &&
                label == other.label && title == other.title && source == other.source &&
                coordinateSystem == other.coordinateSystem && vcard == other.vcard &&
                imageMime == other.imageMime && error == other.error &&
                (imageBytes?.contentEquals(other.imageBytes) ?: (other.imageBytes == null))
        }

        override fun hashCode(): Int {
            var result = kind.hashCode()
            result = 31 * result + text.hashCode()
            result = 31 * result + (uri?.hashCode() ?: 0)
            result = 31 * result + (latitude?.hashCode() ?: 0)
            result = 31 * result + (longitude?.hashCode() ?: 0)
            result = 31 * result + (label?.hashCode() ?: 0)
            result = 31 * result + (title?.hashCode() ?: 0)
            result = 31 * result + (source?.hashCode() ?: 0)
        result = 31 * result + (sourceKey?.hashCode() ?: 0)
            result = 31 * result + (coordinateSystem?.hashCode() ?: 0)
            result = 31 * result + (vcard?.hashCode() ?: 0)
            result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
            result = 31 * result + (imageMime?.hashCode() ?: 0)
            result = 31 * result + (error?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * 解析分享过来的文字。
     *
     * @param raw 分享 Intent 里的文本内容
     * @param subject 分享 Intent 里的 `EXTRA_SUBJECT`（网页标题），可空
     * @return 解析结果；内容为空时返回 null
     */
    fun parse(raw: String?, subject: String? = null): Parsed? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 0. vCard：通讯录、微信「分享联系人」都会带这个
        if (looksLikeVCard(text)) {
            return parseVCard(text)
        }

        // 1. 标准 geo: URI（RFC 5870）—— 纬度在前，经度在后
        if (text.startsWith("geo:", ignoreCase = true)) {
            return parseGeoUri(text)
        }

        // 2. 各家地图的 scheme
        //    识别到 scheme 但内容非法时返回带 error 的结果，不静默降级
        when {
            text.startsWith("amap://", ignoreCase = true) ->
                return parseAmapScheme(text)

            text.startsWith("baidumap://", ignoreCase = true) ->
                return parseBaiduScheme(text)

            text.startsWith("qqmap://", ignoreCase = true) ->
                return parseQqScheme(text)

            text.startsWith("comgooglemaps://", ignoreCase = true) ->
                return parseGoogleScheme(text)
        }

        // 3. 文本里含 http/https 链接
        //    注意：浏览器分享不一定只给纯 URL，也可能是「标题 + 换行 + URL」，
        //    甚至「URL + 分享语」，所以统一走「先摘出 URL」再判断是不是地图服务。
        extractUrlFromMixedText(text)?.let { (url, inlineTitle) ->
            parseHttp(url)?.let { return it.withTitle(subject ?: inlineTitle) }
        }

        // 4. 纯文本
        return Parsed(kind = Kind.TEXT, text = text, title = subject)
    }

    /**
     * 附加网页标题。
     *
     * 优先用 Intent 带来的 `EXTRA_SUBJECT`（Chrome 等浏览器的标准做法），
     * 没有才用从混合文本里推断出来的标题。
     */
    private fun Parsed.withTitle(title: String?): Parsed {
        val cleaned = title?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned == null) return this
        return copy(title = cleaned)
    }

    /**
     * 从混合文本里提取 URL 与可能的标题。
     *
     * 处理形态：
     *  - `标题\nhttps://example.com`
     *  - `标题 https://example.com`
     *  - `https://example.com\n分享语`
     *  - `看看这个 https://example.com 很有意思`
     *
     * 标题的取法：**URL 之前**的非空行作为标题，
     * URL 之后的内容视为分享语（丢弃）。
     *
     * ⚠️ 正则里**不能排除逗号** —— 地图 URL 的坐标参数是 `lat,lon`，
     * 排除逗号会把 `?q=39.9,116.4` 截断成 `?q=39.9`。
     * 改为匹配完整 token 后，再从尾部剥离中文标点与句末符号。
     */
    private fun extractUrlFromMixedText(text: String): Pair<String, String?>? {
        // URL token：不含空白与中文标点，但保留 ASCII 逗号（坐标分隔符）
        val pattern = Regex(
            "https?://[^\\s\\u3000，。、；：）】」』《》\"'“”‘’<>]+",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(text) ?: return null

        // 从尾部剥离非 URL 成分：中文标点、句末符号
        var url = match.value
        while (url.isNotEmpty() && url.last() in TRAILING_CHARS) {
            url = url.dropLast(1)
        }
        if (url.length <= "https://".length) return null

        // URL 之前的部分作为标题候选
        val before = text.substring(0, match.range.first).trim()
        val title = before
            .substringAfterLast('\n')
            .trim()
            .takeIf { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) }

        return url to title
    }

    /** URL 末尾需要剥离的字符（中文标点 + 英文句末符号） */
    private val TRAILING_CHARS = charArrayOf(
        '，', '。', '、', '；', '：', '）', '】', '」', '』', '《', '》',
        '"', '\'', '“', '”', '‘', '’',
        '.', ',', ';', ':', '!', '?', '）',
    )

    // ---------- vCard 电子名片 ----------

    /**
     * 判断文本是否像一份 vCard。
     *
     * vCard 3.0 / 4.0 都以 `BEGIN:VCARD` 开头、以 `END:VCARD` 结尾。
     * 微信、系统通讯录分享联系人时给的就是这个。
     */
    fun looksLikeVCard(text: String): Boolean =
        text.contains("BEGIN:VCARD", ignoreCase = true)

    /**
     * 解析 vCard 文本。
     *
     * 复用 [VCardCodec]，避免两套 vCard 解析逻辑。
     */
    private fun parseVCard(text: String): Parsed {
        val card = VCardCodec.parse(text.toByteArray(Charsets.UTF_8))
            ?: return Parsed(
                kind = Kind.VCARD,
                text = text,
                error = AppStrings.get(R.string.err_vcard_parse),
            )
        return Parsed(
            kind = Kind.VCARD,
            text = text,
            vcard = card,
            title = card.fullName,
            source = AppStrings.get(R.string.share_source_contact),
            sourceKey = "contact",
        )
    }

    // ---------- 图片 ----------

    /**
     * 构造图片分享的解析结果。
     *
     * 图片走的是 `EXTRA_STREAM`（content URI），不是文本，
     * 所以由 [com.nfctool.app.MainActivity] 读出字节后调这里。
     *
     * @param bytes  图片字节
     * @param mime   图片 MIME 类型
     */
    fun parseImage(bytes: ByteArray, mime: String?): Parsed {
        val effectiveMime = mime?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        if (bytes.isEmpty()) {
            return Parsed(
                kind = Kind.IMAGE,
                text = "",
                imageMime = effectiveMime,
                error = AppStrings.get(R.string.err_share_image_empty),
            )
        }
        return Parsed(
            kind = Kind.IMAGE,
            text = "",
            imageBytes = bytes,
            imageMime = effectiveMime,
            title = AppStrings.get(R.string.share_image_title),
            source = AppStrings.get(R.string.share_source_image),
            sourceKey = "image",
        )
    }

    // ---------- geo: URI ----------

    /**
     * 解析 `geo:lat,lon[,alt][;u=unc][?q=label][&z=zoom]`。
     *
     * 复用 [GeoUriCodec] 避免两套解析逻辑。
     *
     * 识别到 `geo:` 前缀但内容非法时，**不降级为纯文本**，
     * 而是返回带 error 的结果 —— 否则用户会写入一个坐标无效的标签，
     * 碰一下毫无反应却不知原因。
     */
    private fun parseGeoUri(text: String): Parsed {
        val geo = GeoUriCodec.parse(text)
            ?: return Parsed(
                kind = Kind.GEO,
                text = text,
                source = "geo: URI",
                sourceKey = "geo_uri",
                error = AppStrings.get(R.string.err_geo_uri),
            )
        return Parsed(
            kind = Kind.GEO,
            text = text,
            latitude = geo.latitude,
            longitude = geo.longitude,
            label = geo.label,
            source = "geo: URI",
            sourceKey = "geo_uri",
            coordinateSystem = "WGS-84",
        )
    }

    // ---------- 各家地图 scheme ----------

    /**
     * `amap://view?lat=39.9&lon=116.4&name=xxx` / `amap://poi?...`
     */
    private fun parseAmapScheme(text: String): Parsed {
        val query = queryOf(text)
        val lat = query["lat"]?.toDoubleOrNull()
        val lon = query["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null) {
            return schemeError(text, AppStrings.get(R.string.share_source_amap), AppStrings.get(R.string.err_share_map_no_coord), "amap")
        }
        return Parsed(
            kind = Kind.GEO,
            text = text,
            latitude = lat,
            longitude = lon,
            label = query["name"] ?: query["address"],
            source = AppStrings.get(R.string.share_source_amap),
            sourceKey = "amap",
            coordinateSystem = "GCJ-02",
        )
    }

    /**
     * `baidumap://map/marker?location=39.9,116.4&title=xxx`
     */
    private fun parseBaiduScheme(text: String): Parsed {
        val query = queryOf(text)
        val location = query["location"]
        val pair = location?.let { splitLatLon(it) }
        if (pair == null) {
            return schemeError(text, AppStrings.get(R.string.share_source_baidu), AppStrings.get(R.string.err_share_map_no_coord), "baidu")
        }
        val (lat, lon) = pair
        return Parsed(
            kind = Kind.GEO,
            text = text,
            latitude = lat,
            longitude = lon,
            label = query["title"] ?: query["content"],
            source = AppStrings.get(R.string.share_source_baidu),
            sourceKey = "baidu",
            coordinateSystem = "BD-09",
        )
    }

    /**
     * `qqmap://map/marker?marker=coord:39.9,116.4;title:xxx;addr:yyy`
     *
     * 腾讯的 marker 参数是分号分隔的键值串，不是标准 query。
     */
    private fun parseQqScheme(text: String): Parsed {
        val query = queryOf(text)
        val marker = query["marker"]
        val coordMatch = marker?.let { Regex("coord:([-\\d.]+),([-\\d.]+)").find(it) }
        val lat = coordMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val lon = coordMatch?.groupValues?.get(2)?.toDoubleOrNull()
        if (marker == null || lat == null || lon == null) {
            return schemeError(text, AppStrings.get(R.string.share_source_qq), AppStrings.get(R.string.err_share_map_no_coord), "qq")
        }
        return Parsed(
            kind = Kind.GEO,
            text = text,
            latitude = lat,
            longitude = lon,
            label = markerField(marker, "title") ?: markerField(marker, "addr"),
            source = AppStrings.get(R.string.share_source_qq),
            sourceKey = "qq",
            coordinateSystem = "GCJ-02",
        )
    }

    /**
     * `comgooglemaps://?q=39.9,116.4` 或 `comgooglemaps://maps?q=...`
     */
    private fun parseGoogleScheme(text: String): Parsed {
        val query = queryOf(text)
        val q = query["q"]
        val pair = q?.let { splitLatLon(it) }
        if (q == null || pair == null) {
            return schemeError(text, AppStrings.get(R.string.share_source_google), AppStrings.get(R.string.err_share_map_no_coord), "google")
        }
        val (lat, lon) = pair
        return Parsed(
            kind = Kind.GEO,
            text = text,
            latitude = lat,
            longitude = lon,
            label = queryFieldOf(q, "label"),
            source = AppStrings.get(R.string.share_source_google),
            sourceKey = "google",
            coordinateSystem = "WGS-84",
        )
    }

    /**
     * 构造「识别到地图 scheme 但内容非法」的结果。
     */
    private fun schemeError(text: String, source: String, reason: String, sourceKey: String? = null): Parsed =
        Parsed(
            kind = Kind.GEO,
            text = text,
            source = source,
            sourceKey = sourceKey,
            error = AppStrings.get(R.string.err_share_map_parse, source, reason),
        )

    // ---------- http(s) 链接 ----------

    /**
     * 解析 http/https 链接：先判断是不是地图服务，是则提取坐标，否则当普通链接。
     *
     * ⚠️ **必须校验 host**：`position` / `location` / `marker` / `q`
     * 都是极其常见的 query 参数名。早年只对 Google 校验了域名，
     * 结果任意网页只要带上 `?position=116.4,39.9` 就会被判成高德地图坐标，
     * 用户照此写出的标签，坐标语义完全错误。
     */
    private fun parseHttp(text: String): Parsed? {
        val query = queryOf(text)
        val host = hostOf(text)

        // 高德 Web：https://uri.amap.com/marker?position=lng,lat&name=xxx
        // ⚠️ position 是「经度,纬度」，与其他家相反
        if (hostMatches(host, "amap.com")) {
            query["position"]?.let { position ->
                val pair = splitLatLon(position)
                if (pair != null) {
                    val (lon, lat) = pair
                    return Parsed(
                        kind = Kind.GEO,
                        text = text,
                        latitude = lat,
                        longitude = lon,
                        label = query["name"],
                        source = AppStrings.get(R.string.share_source_amap),
                        sourceKey = "amap",
                        coordinateSystem = "GCJ-02",
                    )
                }
            }
        }

        // 百度 Web：https://api.map.baidu.com/marker?location=lat,lng&title=xxx
        if (hostMatches(host, "baidu.com")) {
            query["location"]?.let { location ->
                val pair = splitLatLon(location)
                if (pair != null) {
                    val (lat, lon) = pair
                    return Parsed(
                        kind = Kind.GEO,
                        text = text,
                        latitude = lat,
                        longitude = lon,
                        label = query["title"] ?: query["content"],
                        source = AppStrings.get(R.string.share_source_baidu),
                        sourceKey = "baidu",
                        coordinateSystem = "BD-09",
                    )
                }
            }
        }

        // 腾讯 Web：https://apis.map.qq.com/uri/v1/marker?marker=coord:lat,lng;title:xxx
        if (hostMatches(host, "map.qq.com")) {
            query["marker"]?.let { marker ->
                val coordMatch = Regex("coord:([-\\d.]+),([-\\d.]+)").find(marker)
                if (coordMatch != null) {
                    val lat = coordMatch.groupValues[1].toDoubleOrNull()
                    val lon = coordMatch.groupValues[2].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        return Parsed(
                            kind = Kind.GEO,
                            text = text,
                            latitude = lat,
                            longitude = lon,
                            label = markerField(marker, "title") ?: markerField(marker, "addr"),
                            source = AppStrings.get(R.string.share_source_qq),
                            sourceKey = "qq",
                            coordinateSystem = "GCJ-02",
                        )
                    }
                }
            }
        }

        // Google 地图：https://www.google.com/maps/place/xxx/@39.9,116.4,15z
        parseGoogleMapsPath(text)?.let { return it }

        // Google 地图：https://maps.google.com/?q=39.9,116.4
        if (isGoogleMapsUrl(host, text)) {
            query["q"]?.let { q ->
                val pair = splitLatLon(q)
                if (pair != null) {
                    val (lat, lon) = pair
                    return Parsed(
                        kind = Kind.GEO,
                        text = text,
                        latitude = lat,
                        longitude = lon,
                        label = queryFieldOf(q, "label") ?: queryFieldOf(q, "query"),
                        source = AppStrings.get(R.string.share_source_google),
                        sourceKey = "google",
                        coordinateSystem = "WGS-84",
                    )
                }
            }
        }

        // 不是地图服务 → 普通链接
        return Parsed(kind = Kind.URI, text = text, uri = text)
    }

    /**
     * 从 Google 地图网页链接的路径里抠坐标。
     *
     * 形如 `https://www.google.com/maps/place/天安门/@39.9042,116.4074,15z/data=...`
     * 坐标在 `@` 之后，格式为 `lat,lon,zoomz`。
     */
    private fun parseGoogleMapsPath(text: String): Parsed? {
        if (!text.contains("google.com/maps", ignoreCase = true)) return null
        val atIndex = text.indexOf('@')
        if (atIndex < 0) return null
        val after = text.substring(atIndex + 1)
        val coordPart = after.substringBefore('?').substringBefore('/')
        val pieces = coordPart.split(',')
        if (pieces.size < 2) return null
        val lat = pieces[0].toDoubleOrNull() ?: return null
        val lon = pieces[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        // 地点名一般在 /place/ 与 /@ 之间
        val placeSegment = text.substringAfter("/place/", "").substringBefore("/@").substringBefore('?')
        return Parsed(
            kind = Kind.GEO,
            text = text,
            latitude = lat,
            longitude = lon,
            label = placeSegment.takeIf { it.isNotBlank() }?.let { decodeIfNeeded(it) },
            source = AppStrings.get(R.string.share_source_google),
            sourceKey = "google",
            coordinateSystem = "WGS-84",
        )
    }

    // ---------- 工具函数 ----------

    /**
     * 取出 URL 的 host：小写、去端口、去用户信息。
     *
     * 刻意不用 `android.net.Uri` —— 保持纯逻辑可在 JUnit 里测试。
     */
    private fun hostOf(url: String): String {
        val rest = url.substringAfter("://", url)
        val hostPort = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return hostPort.substringAfterLast('@').substringBefore(':').lowercase()
    }

    /** host 是否等于某域或是其子域 */
    private fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /**
     * 是否为 Google 地图链接。
     *
     * `maps.google.com` 任意路径都算；其他 google.com 子域要求路径含 `/maps`，
     * 避免把 `https://www.google.com/search?q=39.9,116.4` 误判成地图。
     */
    private fun isGoogleMapsUrl(host: String, url: String): Boolean {
        if (!hostMatches(host, "google.com")) return false
        if (host.startsWith("maps.")) return true
        val path = url.substringAfter("://").substringAfter('/', "")
        return path.startsWith("maps")
    }

    /**
     * 拆出 URL 的 query 部分并解析成键值对。
     *
     * 刻意不依赖 `android.net.Uri` —— 这样纯逻辑可以在 JUnit 里测试。
     */
    private fun queryOf(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (key.isNotEmpty()) {
                result[key.lowercase()] = decodeIfNeeded(value)
            }
        }
        return result
    }

    /**
     * 拆出 `lat,lon` 形式的一对坐标。
     */
    private fun splitLatLon(value: String): Pair<Double, Double>? {
        val pieces = value.split(',')
        if (pieces.size < 2) return null
        val first = pieces[0].trim().toDoubleOrNull() ?: return null
        val second = pieces[1].trim().toDoubleOrNull() ?: return null
        return first to second
    }

    /**
     * 从 `q=39.9,116.4(名称)` 或 `q=名称@39.9,116.4` 这类复合值里取名称。
     */
    private fun queryFieldOf(value: String, field: String): String? {
        val pattern = Regex("$field=([^&]+)")
        val match = pattern.find(value) ?: return null
        return decodeIfNeeded(match.groupValues[1])
    }

    /**
     * 从腾讯的 `key:value;key:value` 串里取某个字段。
     */
    private fun markerField(marker: String, field: String): String? {
        val pattern = Regex("(?:^|;)$field:([^;]*)")
        val match = pattern.find(marker) ?: return null
        return decodeIfNeeded(match.groupValues[1]).takeIf { it.isNotBlank() }
    }

    /**
     * URL 百分号解码。
     *
     * 注意 `+` 在 query 里代表空格，但地名里的 `+` 有时是字面加号，
     * 所以只把 `+` 还原成空格前先看它是否被 `%20` 之外的场景使用 ——
     * 这里采用保守策略：`+` 一律还原为空格（标准 query 编码规则）。
     */
    private fun decodeIfNeeded(value: String): String {
        if (value.isEmpty()) return value
        val decoded = try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
        return decoded
    }
}
