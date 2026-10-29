package com.nfctool.app.core.nfc.ndef

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.nfc.NdefRecord
import com.nfctool.app.core.nfc.Hex

/**
 * NDEF 细分类型注册表。
 *
 * ## 为什么需要这一层
 *
 * NDEF 规范本身只有 8 种 TNF（Type Name Format），所有业务语义都塞在
 * type 字段里。Android 的 `NdefRecord` 只提供 `createMime` / `createUri` /
 * `createExternal` / `createApplicationRecord` 这几个工厂方法，**不做任何细分识别**。
 *
 * 结果就是：一个 Wi-Fi 配网标签和一个 JPEG 标签，在 API 层面都是
 * "TNF_MIME_MEDIA + 一个字符串 type"，如果只按 MIME 归类，
 * 用户看到的就是两条一模一样的 "MIME" 记录，完全看不出这张卡是干什么的。
 *
 * 所以这里加一层**应用级归类**：按 type 字段把记录识别成
 * [NdefKind] 里的具体细分类型，并给出结构化的字段列表。
 *
 * ## 扩展方式
 *
 * 新增一种细分类型时：
 *  1. 在 [NdefKind] 里加一个枚举项
 *  2. 在本文件的 [classify] 里加一个匹配分支
 *  3. 如需结构化字段，写一个对应的 Codec 并在 [details] 里接入
 */
object NdefTypeRegistry {

    /**
     * 识别一条记录的细分类型。
     */
    fun classify(record: NdefRecord): NdefKind {
        // 1. MIME 类：先看是否是我们认识的业务 MIME
        if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
            val mime = String(record.type, Charsets.UTF_8).lowercase()
            if (WifiWscCodec.isWifiRecord(record)) return NdefKind.WIFI
            if (VCardCodec.isVCard(record)) return NdefKind.VCARD
            if (MeCardCodec.isMeCard(record)) return NdefKind.MECARD
            if (VCalendarCodec.isVCalendar(record)) return NdefKind.VCALENDAR
            if (BluetoothOobCodec.isOobRecord(mime)) return NdefKind.BLUETOOTH_OOB
            if (mime == "text/vcard" || mime == "text/x-vcard") return NdefKind.VCARD
            if (mime == "text/x-mecard") return NdefKind.MECARD
            if (mime == "text/vcalendar" || mime == "text/x-vcalendar") return NdefKind.VCALENDAR
            return NdefKind.MIME
        }

        // 2. 外部类型：AAR 是安卓特有的应用唤起记录
        if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE) {
            if (AndroidAppRecordCodec.isAndroidAppRecord(record)) return NdefKind.ANDROID_APP
            return NdefKind.EXTERNAL
        }

        // 3. Well-known：按单字节 type 细分
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN) {
            val type = record.type.firstOrNull()?.toInt()?.toChar()
            return when (type) {
                'T' -> NdefKind.TEXT
                'U' -> if (isGeoUri(record)) NdefKind.GEO else NdefKind.URI
                'S' -> NdefKind.SMART_POSTER
                'H' -> NdefKind.HANDOVER
                else -> NdefKind.UNKNOWN
            }
        }

        // 4. 绝对 URI（TNF 0x03）：需进一步区分 geo: / AID / 普通 URI
        if (record.tnf == NdefRecord.TNF_ABSOLUTE_URI) {
            return when {
                isGeoUri(record) -> NdefKind.GEO
                NdefPayloadUtils.isAidPayload(record.payload) -> NdefKind.AID
                else -> NdefKind.URI
            }
        }

        // 5. 未知 / 未改变 / 空
        return when (record.tnf) {
            NdefRecord.TNF_UNKNOWN, NdefRecord.TNF_UNCHANGED -> NdefKind.UNKNOWN
            else -> NdefKind.UNKNOWN
        }
    }

    /**
     * 解析出一条记录的结构化字段，供 UI 直接展示。
     *
     * 无法识别的记录返回空列表，调用方应回退到原始 hex 展示。
     */
    fun details(record: NdefRecord): List<NdefDetail> = when (classify(record)) {
        NdefKind.WIFI -> wifiDetails(record)
        NdefKind.VCARD -> vcardDetails(record)
        NdefKind.MECARD -> mecardDetails(record)
        NdefKind.VCALENDAR -> vcalendarDetails(record)
        NdefKind.ANDROID_APP -> appDetails(record)
        NdefKind.HANDOVER -> handoverDetails(record)
        NdefKind.SMART_POSTER -> smartPosterDetails(record)
        NdefKind.GEO -> geoDetails(record)
        NdefKind.BLUETOOTH_OOB -> bluetoothDetails(record)
        NdefKind.AID -> aidDetails(record)
        NdefKind.TEXT -> listOfNotNull(
            NdefDetail(AppStrings.get(R.string.detail_content), textOf(record) ?: return emptyList()),
        )
        NdefKind.URI -> listOfNotNull(
            NdefDetail(AppStrings.get(R.string.detail_address), uriOf(record) ?: return emptyList()),
        )
        NdefKind.MIME -> mimeDetails(record)
        NdefKind.EXTERNAL -> externalDetails(record)
        NdefKind.UNKNOWN -> unknownDetails(record)
    }

    /** AID（Type 4 标签应用标识）字段 */
    private fun aidDetails(record: NdefRecord): List<NdefDetail> {
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(AppStrings.get(R.string.detail_aid_bytes), NdefPayloadUtils.toHexSpaced(record.payload))
        out += NdefDetail(AppStrings.get(R.string.detail_aid_length), AppStrings.get(R.string.unit_bytes, record.payload.size))
        out += NdefDetail(AppStrings.get(R.string.detail_record_kind), AppStrings.get(R.string.detail_type4_app))
        return out
    }

    /** 普通 MIME 记录：给出类型、预览与长度 */
    private fun mimeDetails(record: NdefRecord): List<NdefDetail> {
        val mime = String(record.type, Charsets.UTF_8)
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(AppStrings.get(R.string.detail_mime_type), mime)
        out += NdefDetail(AppStrings.get(R.string.detail_payload_size), AppStrings.get(R.string.unit_bytes, record.payload.size))
        if (NdefPayloadUtils.isProbablyText(record.payload)) {
            out += NdefDetail(
                AppStrings.get(R.string.detail_content_preview),
                String(record.payload, Charsets.UTF_8).take(512),
            )
        } else {
            val preview = record.payload.take(32).toByteArray()
            out += NdefDetail(
                AppStrings.get(R.string.detail_binary_preview),
                NdefPayloadUtils.toHexSpaced(preview) +
                    if (record.payload.size > 32) " …" else "",
            )
        }
        return out
    }

    /** 外部类型记录（TNF 0x04） */
    private fun externalDetails(record: NdefRecord): List<NdefDetail> {
        val type = String(record.type, Charsets.UTF_8)
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(AppStrings.get(R.string.detail_external_type), type)
        // type 形如 "domain:typename"，拆开更好读
        val colon = type.indexOf(':')
        if (colon > 0) {
            out += NdefDetail(AppStrings.get(R.string.detail_domain), type.substring(0, colon))
            out += NdefDetail(AppStrings.get(R.string.detail_subtype), type.substring(colon + 1))
        }
        out += NdefDetail(AppStrings.get(R.string.detail_payload_size), AppStrings.get(R.string.unit_bytes, record.payload.size))
        if (NdefPayloadUtils.isProbablyText(record.payload)) {
            out += NdefDetail(AppStrings.get(R.string.detail_content_preview), String(record.payload, Charsets.UTF_8).take(512))
        }
        return out
    }

    /** 无法归类的记录：至少把 TNF 与 type 原始字节交代清楚 */
    private fun unknownDetails(record: NdefRecord): List<NdefDetail> {
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(AppStrings.get(R.string.detail_tnf), tnfName(record.tnf))
        out += NdefDetail(AppStrings.get(R.string.detail_type_bytes), NdefPayloadUtils.toHexSpaced(record.type))
        out += NdefDetail(AppStrings.get(R.string.detail_payload_size), AppStrings.get(R.string.unit_bytes, record.payload.size))
        return out
    }

    /** TNF 数值的可读名称 */
    private fun tnfName(tnf: Short): String = when (tnf) {
        NdefRecord.TNF_EMPTY -> AppStrings.get(R.string.tnf_empty)
        NdefRecord.TNF_WELL_KNOWN -> AppStrings.get(R.string.tnf_well_known)
        NdefRecord.TNF_MIME_MEDIA -> AppStrings.get(R.string.tnf_mime)
        NdefRecord.TNF_ABSOLUTE_URI -> AppStrings.get(R.string.tnf_absolute_uri)
        NdefRecord.TNF_EXTERNAL_TYPE -> AppStrings.get(R.string.tnf_external)
        NdefRecord.TNF_UNKNOWN -> AppStrings.get(R.string.tnf_unknown)
        NdefRecord.TNF_UNCHANGED -> AppStrings.get(R.string.tnf_unchanged)
        else -> AppStrings.get(R.string.tnf_number, tnf.toString())
    }

    private fun geoDetails(record: NdefRecord): List<NdefDetail> {
        val uri = uriOf(record) ?: return emptyList()
        val geo = GeoUriCodec.parse(uri) ?: return listOf(NdefDetail(AppStrings.get(R.string.detail_address), uri))
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(AppStrings.get(R.string.detail_latitude), "${geo.latitude}")
        out += NdefDetail(AppStrings.get(R.string.detail_longitude), "${geo.longitude}")
        geo.altitude?.let { out += NdefDetail(AppStrings.get(R.string.detail_altitude), AppStrings.get(R.string.unit_meters, it)) }
        geo.uncertainty?.let { out += NdefDetail(AppStrings.get(R.string.detail_accuracy), AppStrings.get(R.string.unit_meters, it)) }
        geo.label?.let { out += NdefDetail(AppStrings.get(R.string.detail_place_name), it) }
        geo.zoom?.let { out += NdefDetail(AppStrings.get(R.string.detail_zoom), "$it") }
        out += NdefDetail(AppStrings.get(R.string.detail_uri), uri)
        return out
    }

    private fun bluetoothDetails(record: NdefRecord): List<NdefDetail> {
        val mime = String(record.type, Charsets.UTF_8)
        val isLe = BluetoothOobCodec.isLe(mime)
        val oob = BluetoothOobCodec.parse(record.payload, isLe) ?: return emptyList()
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(AppStrings.get(R.string.detail_bt_record_type), if (isLe) AppStrings.get(R.string.detail_bt_le) else AppStrings.get(R.string.detail_bt_bredr))
        oob.address?.let { out += NdefDetail(AppStrings.get(R.string.detail_device_address), it) }
        oob.deviceName?.let { out += NdefDetail(AppStrings.get(R.string.detail_device_name), it) }
        if (oob.hasSspMaterial) {
            out += NdefDetail(AppStrings.get(R.string.detail_pairing_material), AppStrings.get(R.string.detail_pairing_ssp))
        }
        oob.adStructures.forEach { ad ->
            val value = when (ad.type) {
                BluetoothOobCodec.AD_COMPLETE_LOCAL_NAME ->
                    String(ad.data, Charsets.UTF_8)
                BluetoothOobCodec.AD_LE_DEVICE_ADDRESS ->
                    BluetoothOobCodec.formatLeAddress(ad.data)
                else -> Hex.toHex(ad.data)
            }
            out += NdefDetail(ad.typeName, value)
        }
        out += NdefDetail(AppStrings.get(R.string.detail_mime_type), mime)
        return out
    }

    private fun wifiDetails(record: NdefRecord): List<NdefDetail> {
        val config = WifiWscCodec.decode(record) ?: return emptyList()
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(AppStrings.get(R.string.detail_network_name), config.ssid)
        if (config.hidden) out += NdefDetail(AppStrings.get(R.string.detail_hidden_network), AppStrings.get(R.string.action_yes))
        out += NdefDetail(AppStrings.get(R.string.detail_auth_type), WifiWscCodec.authTypeName(config.authType))
        if (config.password.isNotEmpty()) {
            out += NdefDetail(AppStrings.get(R.string.detail_password), config.password)
        }
        out += NdefDetail(AppStrings.get(R.string.detail_mime_type), String(record.type, Charsets.UTF_8))
        return out
    }

    private fun vcardDetails(record: NdefRecord): List<NdefDetail> {
        val card = VCardCodec.parse(record.payload) ?: return emptyList()
        val out = mutableListOf<NdefDetail>()
        card.fullName?.let { out += NdefDetail(AppStrings.get(R.string.detail_full_name), it) }
        card.name?.takeIf { it != card.fullName }?.let { out += NdefDetail(AppStrings.get(R.string.detail_name_parts), it) }
        card.organization?.let { out += NdefDetail(AppStrings.get(R.string.detail_org), it) }
        card.title?.let { out += NdefDetail(AppStrings.get(R.string.detail_job_title), it) }
        card.phones.forEach { out += NdefDetail(AppStrings.get(R.string.detail_phone), it) }
        card.emails.forEach { out += NdefDetail(AppStrings.get(R.string.detail_email), it) }
        card.urls.forEach { out += NdefDetail(AppStrings.get(R.string.detail_url), it) }
        card.address?.let { out += NdefDetail(AppStrings.get(R.string.detail_address_field), it) }
        out += NdefDetail(AppStrings.get(R.string.detail_mime_type), String(record.type, Charsets.UTF_8))
        return out
    }

    private fun appDetails(record: NdefRecord): List<NdefDetail> {
        val pkg = AndroidAppRecordCodec.decode(record) ?: return emptyList()
        return listOf(
            NdefDetail(AppStrings.get(R.string.detail_target_app), pkg),
            NdefDetail(AppStrings.get(R.string.detail_bt_record_type), AppStrings.get(R.string.detail_aar_type)),
        )
    }

    private fun mecardDetails(record: NdefRecord): List<NdefDetail> {
        val card = MeCardCodec.parse(record.payload) ?: return emptyList()
        val out = mutableListOf<NdefDetail>()
        card.name?.let { out += NdefDetail(AppStrings.get(R.string.detail_full_name), it) }
        card.phones.forEach { out += NdefDetail(AppStrings.get(R.string.detail_phone), it) }
        card.emails.forEach { out += NdefDetail(AppStrings.get(R.string.detail_email), it) }
        card.urls.forEach { out += NdefDetail(AppStrings.get(R.string.detail_url), it) }
        card.addresses.forEach { out += NdefDetail(AppStrings.get(R.string.detail_address), it) }
        card.note?.let { out += NdefDetail(AppStrings.get(R.string.detail_note), it) }
        out += NdefDetail(AppStrings.get(R.string.detail_mime_type), String(record.type, Charsets.UTF_8))
        return out
    }

    private fun vcalendarDetails(record: NdefRecord): List<NdefDetail> {
        val event = VCalendarCodec.parse(record.payload) ?: return emptyList()
        val out = mutableListOf<NdefDetail>()
        event.summary?.let { out += NdefDetail(AppStrings.get(R.string.detail_event_title), it) }
        event.start?.let { out += NdefDetail(AppStrings.get(R.string.detail_start), it) }
        event.end?.let { out += NdefDetail(AppStrings.get(R.string.detail_end), it) }
        event.location?.let { out += NdefDetail(AppStrings.get(R.string.detail_location), it) }
        event.organizer?.let { out += NdefDetail(AppStrings.get(R.string.detail_organizer), it) }
        event.description?.let { out += NdefDetail(AppStrings.get(R.string.detail_description), it) }
        out += NdefDetail(AppStrings.get(R.string.detail_mime_type), String(record.type, Charsets.UTF_8))
        return out
    }

    private fun smartPosterDetails(record: NdefRecord): List<NdefDetail> {
        val poster = SmartPosterCodec.decode(record) ?: return emptyList()
        val out = mutableListOf<NdefDetail>()
        poster.title?.let { out += NdefDetail(AppStrings.get(R.string.detail_event_title), it) }
        out += NdefDetail(AppStrings.get(R.string.detail_language), poster.language)
        poster.action?.let {
            val actionName = when (it) {
                SmartPosterCodec.ACTION_DO -> "DO"
                SmartPosterCodec.ACTION_SAVE_FOR_LATER -> "SAVE FOR LATER"
                SmartPosterCodec.ACTION_EDIT -> "EDIT"
                else -> "0x${it.toString(16).uppercase()}"
            }
            out += NdefDetail(AppStrings.get(R.string.detail_record_kind), actionName)
        }
        poster.uris.forEach { out += NdefDetail(AppStrings.get(R.string.detail_uri), it) }
        poster.size?.let { out += NdefDetail(AppStrings.get(R.string.detail_payload_size), "$it") }
        poster.mimeType?.let { out += NdefDetail(AppStrings.get(R.string.detail_mime_type), it) }
        return out
    }

    private fun handoverDetails(record: NdefRecord): List<NdefDetail> {
        val handover = HandoverCodec.decode(record) ?: return emptyList()
        val out = mutableListOf<NdefDetail>()
        out += NdefDetail(
            AppStrings.get(R.string.detail_record_kind),
            if (handover.isRequest) AppStrings.get(R.string.detail_ho_request) else AppStrings.get(R.string.detail_ho_select),
        )
        out += NdefDetail(AppStrings.get(R.string.detail_version), handover.version.toString())
        handover.alternatives.forEach { alt ->
            val suffix = if (alt.data.isEmpty()) "" else " · ${Hex.toHex(alt.data)}"
            out += NdefDetail(AppStrings.get(R.string.detail_alt_carrier), alt.carrierName + suffix)
        }
        return out
    }

    private fun textOf(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals("T".toByteArray(Charsets.US_ASCII))) return null
        val payload = record.payload
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt()
        val langLen = status and 0x3F
        if (payload.size < 1 + langLen) return null
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        val start = 1 + langLen
        return String(payload, start, payload.size - start, charset)
    }

    private fun uriOf(record: NdefRecord): String? {
        if (record.tnf == NdefRecord.TNF_ABSOLUTE_URI) {
            return String(record.payload, Charsets.UTF_8)
        }
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals("U".toByteArray(Charsets.US_ASCII))) return null
        val payload = record.payload
        if (payload.isEmpty()) return null
        val prefixIndex = payload[0].toInt() and 0xFF
        val rest = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        val prefix = URI_PREFIXES.getOrNull(prefixIndex) ?: ""
        return prefix + rest
    }

    /**
     * 判断记录是否为 `geo:` URI（RFC 5870 地理位置）。
     */
    private fun isGeoUri(record: NdefRecord): Boolean =
        GeoUriCodec.isGeoUri(uriOf(record))

    /** NFC Forum URI 前缀表 */
    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://",
        "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
        "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
        "news:", "telnet://", "imap:", "rtsp:", "urn:", "pop:",
        "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
        "urn:nfc:",
    )
}
