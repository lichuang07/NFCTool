package com.nfctool.app.core.nfc

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import com.nfctool.app.core.nfc.ndef.AndroidAppRecordCodec
import com.nfctool.app.core.nfc.ndef.BluetoothOobCodec
import com.nfctool.app.core.nfc.ndef.GeoUriCodec
import com.nfctool.app.core.nfc.ndef.HandoverCodec
import com.nfctool.app.core.nfc.ndef.MeCardCodec
import com.nfctool.app.core.nfc.ndef.NdefVerify
import com.nfctool.app.core.nfc.ndef.QrCodeEncoder
import com.nfctool.app.core.nfc.ndef.SmartPosterCodec
import com.nfctool.app.core.nfc.ndef.VCalendarCodec
import com.nfctool.app.core.nfc.ndef.VCardCodec
import com.nfctool.app.core.nfc.ndef.WifiWscCodec
import java.io.IOException

/**
 * NDEF 读写管理器。
 *
 * 覆盖三种场景：
 *  1. 已有 NDEF 的标签 → 读取 / 覆盖写入
 *  2. 空白可格式化标签 → 先 format 再写入
 *  3. 无 NDEF 但有 Ultralight 等 → 直接写底层页
 */
class NdefManager(private val session: TagSession) {

    private val ndef get() = session.ndef
    private val formatable get() = session.ndefFormatable

    val canRead: Boolean get() = ndef != null
    val canWrite: Boolean get() = ndef != null || formatable != null

    /** 标签是否为空白可格式化标签（写入时会先格式化） */
    val canFormat: Boolean get() = formatable != null

    /** 读取 NDEF 消息 */
    fun read(): Result<NdefMessage> = runCatching {
        val n = ndef ?: error(AppStrings.tagNoNdef())
        if (!n.isConnected) n.connect()
        n.ndefMessage ?: error(AppStrings.noNdefMessage())
    }

    /**
     * NDEF 区状态。
     *
     * 读取页要区分「不支持 NDEF」「NDEF 区为空」「读取失败」三种情况，
     * 否则用户看到的是一片空白，不知道到底发生了什么。
     */
    enum class NdefState {
        /** 标签不支持 NDEF */
        UNSUPPORTED,

        /** 支持 NDEF 但内容区为空（空白标签，尚未格式化） */
        EMPTY,

        /** 读取成功且有消息 */
        OK,

        /** 读取过程中出错 */
        ERROR,
    }

    /**
     * 探测 NDEF 区状态。
     *
     * 与 [read] 分开是为了让 UI 能区分「空标签」和「读取失败」——
     * 这两种情况都表现为「读不到消息」，但含义完全不同。
     */
    fun readState(): Pair<NdefState, String?> = runCatching {
        val n = ndef ?: return NdefState.UNSUPPORTED to null
        if (!n.isConnected) n.connect()
        if (n.ndefMessage == null) NdefState.EMPTY to null else NdefState.OK to null
    }.getOrElse { e ->
        NdefState.ERROR to (e.message ?: AppStrings.get(R.string.err_ndef_read_failed))
    }

    /** 读取 NDEF 消息并解码为可展示结构 */
    fun readDecoded(): Result<List<NdefRecordInfo>> = read().map { NdefCodec.decode(it) }

    /** 标签容量（字节） */
    fun capacity(): Int = ndef?.maxSize ?: 0

    /** 标签类型 */
    fun tagType(): String = ndef?.type ?: ""

    /** 标签是否可写 */
    fun isWritable(): Boolean = ndef?.isWritable ?: false

    /**
     * 写入 NDEF 消息。
     *
     * 若标签尚未格式化（NdefFormatable 可用），先格式化再写。
     *
     * 写入后**必须回读校验**：部分机型在标签已离开感应范围时，
     * `writeNdefMessage` 不会抛异常却实际什么都没写进去，
     * 只有回读才能确认数据真的落到了标签上。
     */
    fun write(message: NdefMessage): Result<Unit> = runCatching {
        val n = ndef
        if (n != null) {
            if (!n.isConnected) n.connect()
            if (!n.isWritable) error(AppStrings.tagLocked())
            // 写入前预检容量：超限时给出人能看懂的原因，
            // 而不是让平台的英文异常原样冒到界面上。
            val limit = n.maxSize
            if (limit > 0) {
                val size = message.toByteArray().size
                if (size > limit) {
                    error(AppStrings.get(R.string.err_write_over_capacity, size, limit))
                }
            }
            n.writeNdefMessage(message)
            verifyReadBack(n, message)
            return@runCatching
        }
        // 空白标签：先格式化
        val f = formatable ?: error(AppStrings.tagNotFormattable())
        if (!f.isConnected) f.connect()
        f.format(message)
    }

    /**
     * 写入后回读校验：把标签上的内容读回来，与期望写入的消息逐字节比对。
     *
     * 失败时抛出带明确原因的异常，让 UI 能区分
     * 「标签离开了感应范围」与「内容不一致」。
     */
    private fun verifyReadBack(n: Ndef, expected: NdefMessage) {
        val actual = try {
            n.ndefMessage
        } catch (e: TagLostException) {
            error(AppStrings.get(R.string.err_tag_left_range))
        } catch (e: IOException) {
            error(AppStrings.get(R.string.err_write_readback_failed, e.message ?: AppStrings.get(R.string.err_comm_abnormal)))
        } ?: error(AppStrings.get(R.string.err_write_readback_empty))

        val actualBytes = actual.records.map { record ->
            NdefVerify.RecordBytes(
                tnf = record.tnf.toInt(),
                type = record.type,
                id = record.id ?: ByteArray(0),
                payload = record.payload,
            )
        }
        val expectedBytes = expected.records.map { record ->
            NdefVerify.RecordBytes(
                tnf = record.tnf.toInt(),
                type = record.type,
                id = record.id ?: ByteArray(0),
                payload = record.payload,
            )
        }

        NdefVerify.describeMismatch(actualBytes, expectedBytes)?.let { reason ->
            error(AppStrings.get(R.string.err_write_verify_failed, reason))
        }
    }

    /**
     * 格式化标签。
     *
     * @param readOnly true 表示格式化为只读（不可逆，标签将永久无法写入）
     * @param message  格式化时写入的初始消息，只读模式下会被忽略
     */
    fun format(readOnly: Boolean = false, message: NdefMessage? = null): Result<Unit> = runCatching {
        val f = formatable ?: error(AppStrings.get(R.string.err_tag_not_formattable_short))
        if (!f.isConnected) f.connect()
        if (readOnly) {
            // formatReadOnly 要求传入一个非空消息作为初始内容
            f.formatReadOnly(message ?: NdefMessage(NdefCodec.createTextRecord("")))
        } else {
            f.format(message ?: NdefMessage(NdefCodec.createTextRecord("")))
        }
    }

    /** 快速写文本 */
    fun writeText(text: String, language: String = "zh"): Result<Unit> =
        write(NdefCodec.buildMessage(listOf(NdefCodec.createTextRecord(text, Charsets.UTF_8, language))))

    /** 快速写链接 */
    fun writeUri(uri: String): Result<Unit> =
        write(NdefCodec.buildMessage(listOf(NdefCodec.createUriRecord(uri))))

    /** 快速写 MIME 内容 */
    fun writeMime(mimeType: String, data: ByteArray): Result<Unit> =
        write(NdefCodec.buildMessage(listOf(NdefCodec.createMimeRecord(mimeType, data))))

    /**
     * 写入 Wi-Fi 配网记录。
     *
     * 生成 `application/vnd.wfa.wsc` MIME 记录，Android 系统读到后会提示连接该网络。
     * 注意 iOS 不会自动联网，只在通知栏提示。
     */
    fun writeWifi(config: WifiWscCodec.WifiConfig): Result<Unit> =
        write(NdefCodec.buildMessage(listOf(WifiWscCodec.createRecord(config))))

    /**
     * 写入 vCard 电子名片。
     *
     * payload 为标准 vCard 3.0 文本，MIME 用 `text/vcard`。
     */
    fun writeVCard(
        fullName: String,
        phone: String? = null,
        email: String? = null,
        organization: String? = null,
    ): Result<Unit> = writeVCardFull(
        fullName = fullName,
        phone = phone,
        email = email,
        organization = organization,
    )

    /** 写入完整字段的 vCard（带职位 / 网址 / 地址） */
    fun writeVCardFull(
        fullName: String,
        phone: String? = null,
        email: String? = null,
        organization: String? = null,
        title: String? = null,
        url: String? = null,
        address: String? = null,
    ): Result<Unit> {
        val text = VCardCodec.build(
            fullName = fullName,
            phone = phone,
            email = email,
            organization = organization,
            title = title,
            url = url,
            address = address,
        )
        return write(
            NdefCodec.buildMessage(
                listOf(NdefCodec.createMimeRecord("text/vcard", text.toByteArray(Charsets.UTF_8)))
            )
        )
    }

    /**
     * 写入 MECARD 紧凑名片。
     *
     * 比 vCard 省空间，NTAG213 上更实用；日系手机原生识别。
     */
    fun writeMeCard(
        name: String,
        phone: String? = null,
        email: String? = null,
        url: String? = null,
        address: String? = null,
        note: String? = null,
    ): Result<Unit> {
        val text = MeCardCodec.build(
            name = name,
            phone = phone,
            email = email,
            url = url,
            address = address,
            note = note,
        )
        return write(
            NdefCodec.buildMessage(
                listOf(NdefCodec.createMimeRecord("text/x-mecard", text.toByteArray(Charsets.UTF_8)))
            )
        )
    }

    /**
     * 写入 vCalendar 日历事件。
     */
    fun writeVCalendar(
        summary: String,
        start: String,
        end: String,
        location: String? = null,
        description: String? = null,
    ): Result<Unit> {
        val text = VCalendarCodec.build(
            summary = summary,
            start = start,
            end = end,
            location = location,
            description = description,
        )
        return write(
            NdefCodec.buildMessage(
                listOf(NdefCodec.createMimeRecord("text/vcalendar", text.toByteArray(Charsets.UTF_8)))
            )
        )
    }

    /**
     * 写入 Smart Poster 复合记录（S 记录 + U 记录）。
     */
    fun writeSmartPoster(
        title: String?,
        uris: List<String>,
        language: String = "zh",
    ): Result<Unit> = write(SmartPosterCodec.buildMessage(title, uris, language))

    /**
     * 写入 Handover 连接移交记录。
     *
     * @param isRequest true 生成 `Hr`，false 生成 `Hs`
     * @param carriers   载体列表，每个为 (carrier type id, 载体数据)
     */
    fun writeHandover(
        isRequest: Boolean,
        carriers: List<Pair<Int, ByteArray>>,
    ): Result<Unit> =
        write(NdefCodec.buildMessage(listOf(HandoverCodec.build(isRequest, carriers))))

    /**
     * 写入 AID 记录（Type 4 标签的应用标识）。
     *
     * @param aidHex AID 字节的十六进制形式，如 `F0010203040506`
     */
    fun writeAid(aidHex: String): Result<Unit> {
        val aid = Hex.parseLenient(aidHex)
        require(aid.isNotEmpty()) { AppStrings.get(R.string.err_aid_empty) }
        require(aid.size <= 16) { AppStrings.get(R.string.err_aid_too_long) }
        return write(NdefCodec.buildMessage(listOf(NdefCodec.createAidRecord(aid))))
    }

    /**
     * 写入外部类型记录（domain:type 形式）。
     */
    fun writeExternal(domain: String, type: String, data: ByteArray): Result<Unit> =
        write(NdefCodec.buildMessage(listOf(NdefCodec.createExternalRecord(domain, type, data))))

    /**
     * 写入地理位置记录（`geo:` URI，RFC 5870）。
     *
     * Android 系统原生支持 `geo:` URI，碰一下会直接唤起地图应用并定位。
     */
    fun writeGeo(
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        uncertainty: Double? = null,
        label: String? = null,
        zoom: Int? = null,
    ): Result<Unit> {
        val uri = GeoUriCodec.build(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            uncertainty = uncertainty,
            label = label,
            zoom = zoom,
        )
        return writeUri(uri)
    }

    /**
     * 写入蓝牙 BR/EDR OOB 配对记录。
     *
     * 注意：Android 系统不自动解析这类记录（已核对 AOSP NfcService），
     * 只有厂商自己注册了对应 MIME 的 App 才会响应。
     */
    fun writeBluetoothOobBrEdr(
        address: String,
        deviceName: String? = null,
    ): Result<Unit> {
        val ads = mutableListOf<BluetoothOobCodec.AdStructure>()
        deviceName?.takeIf { it.isNotBlank() }?.let {
            ads += BluetoothOobCodec.AdStructure(
                BluetoothOobCodec.AD_COMPLETE_LOCAL_NAME,
                it.toByteArray(Charsets.UTF_8),
            )
        }
        val payload = BluetoothOobCodec.buildBrEdr(address, ads)
        return write(
            NdefCodec.buildMessage(
                listOf(NdefCodec.createMimeRecord(BluetoothOobCodec.MIME_BR_EDR, payload))
            )
        )
    }

    /**
     * 写入蓝牙 LE OOB 配对记录。
     */
    fun writeBluetoothOobLe(
        address: String,
        deviceName: String? = null,
    ): Result<Unit> {
        val ads = mutableListOf<BluetoothOobCodec.AdStructure>()
        deviceName?.takeIf { it.isNotBlank() }?.let {
            ads += BluetoothOobCodec.AdStructure(
                BluetoothOobCodec.AD_COMPLETE_LOCAL_NAME,
                it.toByteArray(Charsets.UTF_8),
            )
        }
        val payload = BluetoothOobCodec.buildLe(address, ads)
        return write(
            NdefCodec.buildMessage(
                listOf(NdefCodec.createMimeRecord(BluetoothOobCodec.MIME_LE, payload))
            )
        )
    }

    /**
     * 写入 Android Application Record（强制唤起指定应用）。
     */
    fun writeAndroidApp(packageName: String): Result<Unit> =
        write(NdefCodec.buildMessage(listOf(AndroidAppRecordCodec.createRecord(packageName))))

    /**
     * 写入图片记录（MIME 记录）。
     *
     * ⚠️ 标签容量有限（NTAG213 只有 137 字节），图片几乎必然放不下。
     * 调用方应先确认字节数不超过标签容量，否则会写入失败。
     *
     * @param bytes 图片字节
     * @param mime 图片 MIME 类型，如 image/jpeg
     */
    fun writeImage(bytes: ByteArray, mime: String): Result<Unit> {
        require(bytes.isNotEmpty()) { AppStrings.imageEmpty() }
        return write(NdefCodec.buildMessage(listOf(NdefCodec.createMimeRecord(mime, bytes))))
    }

    /**
     * 写入二维码。
     *
     * 采用 **Smart Poster 风格**的两条记录：
     *  1. 一条 URI 记录 —— 内容本身（网址/文本）
     *  2. 一条 `image/png` MIME 记录 —— 二维码图片
     *
     * 这样两种用法都成立：
     *  - 支持 NDEF 图片识别的手机：碰一下直接显示二维码，相机可扫
     *  - 普通手机：碰一下直接打开链接
     *
     * @param pngBytes   二维码 PNG 字节
     * @param content    二维码承载的原始内容
     */
    fun writeQrCode(pngBytes: ByteArray, content: String): Result<Unit> {
        require(pngBytes.isNotEmpty()) { AppStrings.qrImageEmpty() }
        val records = mutableListOf<NdefRecord>()
        records += contentRecord(content)
        records += NdefCodec.createMimeRecord(QrCodeEncoder.MIME, pngBytes)
        return write(NdefCodec.buildMessage(records))
    }

    /**
     * 只写入原始内容，不带二维码图片。
     *
     * 这是**最省空间**的方式：一条 URI 记录只占几十字节，
     * 标签容量再小（NTAG213 仅 137 字节）也放得下。
     * 碰一下手机直接打开链接，不经过相机扫码。
     */
    fun writeContentOnly(content: String): Result<Unit> {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { AppStrings.contentEmpty() }
        return write(NdefCodec.buildMessage(listOf(contentRecord(trimmed))))
    }

    /**
     * 只写入二维码图片，不带原始内容。
     *
     * 用于「内容很长、原始内容放不下」的情况 ——
     * 原始内容可以通过扫描二维码得到，所以标签里只需要图。
     */
    fun writeQrImageOnly(pngBytes: ByteArray): Result<Unit> {
        require(pngBytes.isNotEmpty()) { AppStrings.qrImageEmpty() }
        return write(
            NdefCodec.buildMessage(
                listOf(NdefCodec.createMimeRecord(QrCodeEncoder.MIME, pngBytes))
            )
        )
    }

    /**
     * 把内容转成合适的 NDEF 记录。
     *
     * 网址用 URI 记录（系统能直接处理，碰一下就打开），
     * 其他内容用 Text 记录。
     */
    private fun contentRecord(content: String): NdefRecord {
        val trimmed = content.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            NdefCodec.createUriRecord(trimmed)
        } else {
            NdefCodec.createTextRecord(content)
        }
    }
}

/**
 * 标签总览信息：读卡后一次性向 UI 提供的所有元数据。
 */
data class TagOverview(
    val uid: ByteArray,
    val techs: List<NfcTech>,
    val cardType: CardType,
    val ndefRecords: List<NdefRecordInfo>?,
    val ndefCapacity: Int,
    val ndefWritable: Boolean,
    val mifareSize: Int,
    val mifareSectors: Int,
    val mifareBlocks: Int,
    val ultralightPages: Int,
    val isoDepProtocol: Int,
    val historicalBytes: ByteArray?,
    val rawTechList: List<String>,
    /** NDEF 区状态：区分「不支持 / 为空 / 正常 / 读取失败」 */
    val ndefState: NdefManager.NdefState = NdefManager.NdefState.UNSUPPORTED,
    /** NDEF 读取失败时的原因（仅 ndefState == ERROR 时非空） */
    val ndefError: String? = null,
    /**
     * 标签是否支持 NDEF 格式化（空白标签）。
     *
     * 这类标签 `ndefWritable` 为 false，但写入路径会先格式化再写，
     * 所以 UI 判定「可写」时必须把它算进去。
     */
    val ndefFormatable: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagOverview) return false
        return uid.contentEquals(other.uid) && techs == other.techs &&
            cardType == other.cardType && ndefRecords == other.ndefRecords &&
            ndefCapacity == other.ndefCapacity && ndefWritable == other.ndefWritable &&
            mifareSize == other.mifareSize && mifareSectors == other.mifareSectors &&
            mifareBlocks == other.mifareBlocks && ultralightPages == other.ultralightPages &&
            isoDepProtocol == other.isoDepProtocol &&
            ndefState == other.ndefState && ndefError == other.ndefError &&
            ndefFormatable == other.ndefFormatable &&
            rawTechList == other.rawTechList
    }

    override fun hashCode(): Int {
        var result = uid.contentHashCode()
        result = 31 * result + techs.hashCode()
        result = 31 * result + cardType.hashCode()
        result = 31 * result + (ndefRecords?.hashCode() ?: 0)
        result = 31 * result + ndefCapacity
        result = 31 * result + ndefWritable.hashCode()
        result = 31 * result + mifareSize
        result = 31 * result + mifareSectors
        result = 31 * result + mifareBlocks
        result = 31 * result + ultralightPages
        result = 31 * result + isoDepProtocol
        result = 31 * result + ndefState.hashCode()
        result = 31 * result + (ndefError?.hashCode() ?: 0)
        result = 31 * result + ndefFormatable.hashCode()
        result = 31 * result + rawTechList.hashCode()
        return result
    }
}

/**
 * 标签读取器：把一个 [Tag] 解析成 [TagOverview]。
 */
object TagReader {

    fun readOverview(session: TagSession): TagOverview {
        val techs = session.techs
        val uid = session.uid

        val mifare = MifareClassicOps(session)
        val ultralight = MifareUltralightOps(session)
        val apdu = ApduEngine(session)

        val ndefManager = NdefManager(session)

        // ⚠️ 顺序很重要：一个 Tag 同时只能连接一个 TagTechnology。
        // NTAG 同时带有 Ndef 与 MifareUltralight 两种技术，若先读 NDEF 且不关闭，
        // 后面 Ultralight 的 connect() 会抛 IllegalStateException ——
        // 早期实现正是如此，异常被 runCatching 吞掉，页数恒为 0，
        // 所有 NTAG 都被识别成「Ultralight」。
        // 因此先探测 Ultralight（用完自己关），再读 NDEF。
        val ultralightPages = probeUltralightPages(ultralight)

        // 先探状态，再读内容：这样 UI 能区分「不支持 NDEF」「NDEF 区为空」
        // 「读取失败」三种情形，而不是一律显示成空列表。
        val (ndefState, ndefError) = ndefManager.readState()
        val ndefRecords = if (ndefState == NdefManager.NdefState.OK) {
            runCatching { ndefManager.readDecoded().getOrNull() }.getOrNull()
        } else {
            null
        }

        val cardType = CardDetector.detect(
            uid = uid,
            techs = techs,
            mifareSize = if (mifare.isAvailable) mifare.size else null,
            ultralightPages = ultralightPages.takeIf { it > 0 },
        )

        return TagOverview(
            uid = uid,
            techs = techs,
            cardType = cardType,
            ndefRecords = ndefRecords,
            ndefCapacity = ndefManager.capacity(),
            ndefWritable = ndefManager.isWritable(),
            mifareSize = if (mifare.isAvailable) mifare.size else 0,
            mifareSectors = if (mifare.isAvailable) mifare.sectorCount else 0,
            mifareBlocks = if (mifare.isAvailable) mifare.blockCount else 0,
            ultralightPages = ultralightPages,
            isoDepProtocol = apdu.protocol,
            historicalBytes = apdu.historicalBytes,
            rawTechList = session.tag.techList.toList(),
            ndefState = ndefState,
            ndefError = ndefError,
            ndefFormatable = ndefManager.canFormat,
        )
    }

    /**
     * 探测 Ultralight 家族的总页数，**用完立即关闭连接**。
     *
     * Android 的 `MifareUltralight.getType()` 对所有 NTAG 都返回
     * TYPE_ULTRALIGHT，拿不到具体型号；直接写死 48 页会把 NTAG213/215/216
     * 全部认错（它们分别是 45/135/231 页）。
     * 因此读 Capability Container（第 3 页）反推：
     * - 页 3 首字节 0xE1 是 CC 魔数，第 3 字节 MLEN = 用户区字节数 / 8
     * - 总页数 = 用户页 + 9（4 页 UID/CC/锁 + 5 页配置区）
     * 没有合法 CC（普通 Ultralight / Ultralight C）时按 48 页处理。
     */
    private fun probeUltralightPages(ops: MifareUltralightOps): Int {
        if (!ops.isAvailable) return 0
        return runCatching {
            val mc = ops.card ?: return@runCatching 0
            if (!mc.isConnected) mc.connect()
            try {
                when (mc.type) {
                    android.nfc.tech.MifareUltralight.TYPE_ULTRALIGHT,
                    android.nfc.tech.MifareUltralight.TYPE_ULTRALIGHT_C ->
                        // 走 MifareUltralightOps 的 CC 探测：getType() 分辨不出 NTAG 型号
                        ops.detectedPageCount
                    else -> 0
                }
            } finally {
                // 必须关：否则后面 Ndef 的 connect 会抛 IllegalStateException
                runCatching { mc.close() }
            }
        }.getOrDefault(0)
    }

}
