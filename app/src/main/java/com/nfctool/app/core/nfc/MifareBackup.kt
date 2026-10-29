package com.nfctool.app.core.nfc

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MIFARE Classic 整卡备份与恢复。
 *
 * ## 能做什么
 * 把自己拥有的卡片全量读出来存成 JSON，需要时写回一张**同型号**的空白卡。
 * 典型用途：卡快坏了做备份、手里有多张同型号卡要写成一样的内容。
 *
 * ## 刻意不做的事（安全边界，勿回退）
 *
 * - **不碰 sector trailer**。尾块存着 Key A / Key B，备份文件可能被分享出去，
 *   把密钥带出去风险太大 —— 读取时一律脱敏，恢复时一律跳过。
 *   代价是恢复后目标卡沿用**它自己的**密钥，不会继承原卡密钥，
 *   所以**不能**指望靠它把一张"加密卡"变成另一张。
 *
 * - **不写块 0**。那是 UID，出厂写死，任何卡都写不了。
 *   所以恢复出来的卡 UID 仍是目标卡自己的 —— 这正是
 *   **「不能用来复制门禁卡」**的根本原因（门禁读头认的就是 UID）。
 *
 * 这两条合起来，本功能定位是「数据备份 / 批量复制同型卡」，不是「克隆卡片身份」。
 */
object MifareBackup {

    const val VERSION = 1

    /** 备份类型：MIFARE Classic（扇区 / 块 / 密钥） */
    const val KIND_CLASSIC = "classic"

    /** 备份类型：MIFARE Ultralight / NTAG（页，无密钥） */
    const val KIND_ULTRALIGHT = "ultralight"

    // ---------- 数据模型 ----------

    @Serializable
    data class BlockDump(
        val index: Int,
        val isTrailer: Boolean,
        /** 读取是否成功。trailer 即使读到也置为脱敏（hex 为空串） */
        val readable: Boolean,
        /** 16 字节数据的 hex；trailer 或读取失败时为空串 */
        val hex: String = "",
    ) {
        /** 是否可以写回：排除 trailer、块 0（UID）、读失败的块 */
        val isRestorable: Boolean
            get() = readable && hex.isNotEmpty() && !isTrailer && index != 0
    }

    @Serializable
    data class SectorDump(
        val index: Int,
        val blockStart: Int,
        val blockCount: Int,
        /** 该扇区是否用给定密钥认证成功 */
        val authenticated: Boolean,
        /** 认证成功的密钥类型："A" 或 "B" */
        val keyType: String? = null,
        val blocks: List<BlockDump> = emptyList(),
    )

    @Serializable
    data class Backup(
        val version: Int = VERSION,
        /** 备份类型标记，用于从 JSON 自动识别走哪条解析路径 */
        val kind: String = KIND_CLASSIC,
        val uid: String = "",
        /** 卡型：CLASSIC_1K / CLASSIC_2K / CLASSIC_4K / CLASSIC_UNKNOWN */
        val cardType: String = "",
        val sizeBytes: Int = 0,
        val sectorCount: Int = 0,
        val blockCount: Int = 0,
        val createdAt: Long = 0L,
        val sectors: List<SectorDump> = emptyList(),
    ) {
        /** 真正可以写回的块数 */
        val restorableBlocks: Int
            get() = sectors.sumOf { s -> s.blocks.count { it.isRestorable } }

        /** 因认证失败而缺失的扇区数（备份会如实标注，不假装完整） */
        val unreadableSectors: Int
            get() = sectors.count { !it.authenticated }
    }

    /** 恢复结果报告 */
    data class RestoreReport(
        val written: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        /** 跳过 / 失败的具体原因，用于 UI 如实汇报 */
        val details: List<String> = emptyList(),
    )

    /** 默认尝试的密钥（出厂默认 FFFFFFFFFFFF），用户可追加 */
    val DEFAULT_KEYS: List<ByteArray> = listOf(MifareClassicOps.DEFAULT_KEY)

    /**
     * 规范化并校验一条 MIFARE 密钥（6 字节）。
     *
     * 接受 `FFFFFFFFFFFF`、`FF FF FF FF FF FF`、`ff:ff:ff:ff:ff:ff` 等写法，
     * 统一成**大写无分隔的 12 位 hex**，便于密钥本去重与展示。
     */
    fun normalizeKey(input: String): Result<String> = runCatching {
        val bytes = Hex.parse(input)
        require(bytes.size == 6) { AppStrings.get(R.string.err_backup_key_invalid) }
        Hex.toHexNoSep(bytes)
    }

    // ---------- 抓取 ----------

    /**
     * 读取全卡生成备份。
     *
     * @param keys 尝试的密钥列表，按顺序逐个试 Key A 再 Key B
     */
    fun capture(ops: MifareClassicOps, keys: List<ByteArray> = DEFAULT_KEYS): Result<Backup> =
        runCatching {
            if (!ops.isAvailable) error(AppStrings.tagNoMifareClassic())
            val mc = ops.card ?: error(AppStrings.tagNoMifareClassic())
            if (!mc.isConnected) mc.connect()
            val effectiveKeys = keys.ifEmpty { DEFAULT_KEYS }

            val sectors = mutableListOf<SectorDump>()
            for (sector in 0 until ops.sectorCount) {
                val start = mc.sectorToBlock(sector)
                val count = mc.getBlockCountInSector(sector)
                val keyType = authenticateAny(ops, sector, effectiveKeys)
                if (keyType == null) {
                    // 认证失败不中断：记下这个扇区，其余继续读，
                    // 否则一张卡只要有一个扇区加了密就整份备份报废
                    sectors += SectorDump(
                        index = sector,
                        blockStart = start,
                        blockCount = count,
                        authenticated = false,
                    )
                    continue
                }
                val blocks = (0 until count).map { offset ->
                    val block = start + offset
                    val trailer = ops.isTrailer(block)
                    val data = runCatching { ops.readBlock(block).getOrThrow() }.getOrNull()
                    BlockDump(
                        index = block,
                        isTrailer = trailer,
                        readable = data != null,
                        // trailer 读到也不存：里面是 Key A / Key B
                        hex = if (data != null && !trailer) Hex.toHexNoSep(data) else "",
                    )
                }
                sectors += SectorDump(
                    index = sector,
                    blockStart = start,
                    blockCount = count,
                    authenticated = true,
                    keyType = keyType,
                    blocks = blocks,
                )
            }

            Backup(
                uid = Hex.toHexNoSep(mc.tag.id ?: ByteArray(0)),
                cardType = when (ops.size) {
                    1024 -> "CLASSIC_1K"
                    2048 -> "CLASSIC_2K"
                    4096 -> "CLASSIC_4K"
                    else -> "CLASSIC_UNKNOWN"
                },
                sizeBytes = ops.size,
                sectorCount = ops.sectorCount,
                blockCount = ops.blockCount,
                createdAt = System.currentTimeMillis(),
                sectors = sectors,
            )
        }

    /**
     * 把备份写回目标卡。
     *
     * 目标卡必须**同型号**（扇区数 / 块数一致），否则扇区布局对不上，
     * 写出来的数据会错位 —— 宁可一开始就拒绝，也不要写出一份看起来成功、
     * 实际内容全乱的卡。
     */
    fun restore(
        ops: MifareClassicOps,
        backup: Backup,
        keys: List<ByteArray> = DEFAULT_KEYS,
    ): Result<RestoreReport> = runCatching {
        if (!ops.isAvailable) error(AppStrings.tagNoMifareClassic())
        if (backup.blockCount != ops.blockCount || backup.sectorCount != ops.sectorCount) {
            error(AppStrings.get(R.string.err_backup_size_mismatch, backup.blockCount, ops.blockCount))
        }
        val effectiveKeys = keys.ifEmpty { DEFAULT_KEYS }

        var written = 0
        var skipped = 0
        var failed = 0
        val details = mutableListOf<String>()

        for (s in backup.sectors) {
            if (!s.authenticated) {
                skipped += s.blockCount
                details += AppStrings.get(R.string.err_backup_sector_skipped, s.index)
                continue
            }
            if (authenticateAny(ops, s.index, effectiveKeys) == null) {
                failed += s.blockCount
                details += AppStrings.get(R.string.err_sector_auth_failed, s.index)
                continue
            }
            for (b in s.blocks) {
                if (!b.isRestorable) {
                    skipped++
                    continue
                }
                val result = ops.writeBlock(b.index, Hex.parse(b.hex))
                if (result.isSuccess) {
                    written++
                } else {
                    failed++
                    details += AppStrings.get(
                        R.string.err_restore_block_failed,
                        b.index,
                        result.exceptionOrNull()?.message.orEmpty(),
                    )
                }
            }
        }
        RestoreReport(written = written, skipped = skipped, failed = failed, details = details)
    }

    /**
     * 依次尝试密钥，先 Key A 再 Key B，返回成功的那个类型。
     *
     * 注意认证状态是"粘性"的：成功后一直有效到下次认证，
     * 所以调用方必须在**读完/写完整个扇区之后**再认证下一个。
     */
    private fun authenticateAny(
        ops: MifareClassicOps,
        sector: Int,
        keys: List<ByteArray>,
    ): String? {
        for (key in keys) {
            if (ops.authenticate(sector, key, MifareClassicOps.KEY_TYPE_A).isSuccess) return "A"
            if (ops.authenticate(sector, key, MifareClassicOps.KEY_TYPE_B).isSuccess) return "B"
        }
        return null
    }

    // ---------- 序列化 ----------

    // ---------- Ultralight / NTAG ----------

    @Serializable
    data class PageDump(
        val index: Int,
        val readable: Boolean,
        /** 4 字节数据的 hex；读取失败时为空串 */
        val hex: String = "",
    ) {
        val isRestorable: Boolean get() = readable && hex.isNotEmpty()
    }

    @Serializable
    data class UltralightBackup(
        val version: Int = VERSION,
        val kind: String = KIND_ULTRALIGHT,
        val uid: String = "",
        val cardType: String = "",
        val pageCount: Int = 0,
        /** 用户数据页起止（含两端） */
        val userPageStart: Int = 0,
        val userPageEnd: Int = 0,
        val createdAt: Long = 0L,
        val pages: List<PageDump> = emptyList(),
    ) {
        val restorablePages: Int get() = pages.count { it.isRestorable }
    }

    /**
     * 读取 Ultralight / NTAG 的用户区生成备份。
     *
     * **只备份用户数据页**（第 4 页起），其余一概不碰：
     * - 页 0-1：UID，出厂写死
     * - 页 2：锁定位，动它等于改写整卡的可写性
     * - 页 3：CC 能力声明，型号不同 CC 不同，写错标签就废了
     * - 末尾配置区：含 PWD / PACK（密码），与 Classic 的 trailer 同等处理
     */
    fun captureUltralight(ops: MifareUltralightOps): Result<UltralightBackup> = runCatching {
        if (!ops.isAvailable) error(AppStrings.get(R.string.err_tag_no_mifare_ultralight))
        val mc = ops.card ?: error(AppStrings.get(R.string.err_tag_no_mifare_ultralight))
        if (!mc.isConnected) mc.connect()

        val userPages = ops.detectUserPageCount()
        require(userPages > 0) { AppStrings.get(R.string.err_backup_empty) }
        val start = MifareUltralightOps.NDEF_START_PAGE
        // readPages 内部分批（每批 4 页），这里直接一次拿全
        val data = ops.readPages(start, userPages).getOrThrow()

        UltralightBackup(
            uid = Hex.toHexNoSep(mc.tag.id ?: ByteArray(0)),
            cardType = when (userPages) {
                36 -> "NTAG213"
                124 -> "NTAG215"
                218 -> "NTAG216"
                MifareUltralightOps.DEFAULT_USER_PAGES -> "ULTRALIGHT"
                else -> "NTAG_UNKNOWN"
            },
            pageCount = ops.detectedPageCount,
            userPageStart = start,
            userPageEnd = start + userPages - 1,
            createdAt = System.currentTimeMillis(),
            pages = data.mapIndexed { offset, bytes ->
                PageDump(index = start + offset, readable = true, hex = Hex.toHexNoSep(bytes))
            },
        )
    }

    /**
     * 把备份写回目标 Ultralight / NTAG 卡。
     *
     * 只要求目标卡的**用户页数不少于**备份（大卡可以装小备份），
     * 不要求型号完全一致 —— 页布局是线性的，不存在扇区错位问题。
     */
    fun restoreUltralight(
        ops: MifareUltralightOps,
        backup: UltralightBackup,
    ): Result<RestoreReport> = runCatching {
        if (!ops.isAvailable) error(AppStrings.get(R.string.err_tag_no_mifare_ultralight))
        val needed = backup.userPageEnd - backup.userPageStart + 1
        val targetUserPages = ops.detectUserPageCount()
        if (targetUserPages < needed) {
            error(AppStrings.get(R.string.err_backup_page_mismatch, needed, targetUserPages))
        }

        var written = 0
        var skipped = 0
        var failed = 0
        val details = mutableListOf<String>()
        for (p in backup.pages) {
            if (!p.isRestorable) {
                skipped++
                continue
            }
            val result = ops.writePage(p.index, Hex.parse(p.hex))
            if (result.isSuccess) {
                written++
            } else {
                failed++
                details += AppStrings.get(
                    R.string.err_restore_block_failed,
                    p.index,
                    result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
        RestoreReport(written = written, skipped = skipped, failed = failed, details = details)
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // ⚠️ 必须显式打开：version 与 kind 在绝大多数备份里都是**默认值**，
        // 一旦被省略，detectKind 就认不出这是哪种备份（实测返回 null），
        // 恢复流程直接断掉。备份文件也必须完整记录字段，便于向前兼容。
        encodeDefaults = true
    }

    fun toJson(backup: Backup): String = json.encodeToString(backup)

    fun toJson(backup: UltralightBackup): String = json.encodeToString(backup)

    /**
     * 探测备份文件的类型，供 UI 决定走哪条解析路径。
     *
     * 两种备份的字段差异很大（扇区/块 vs 页），靠一个 `kind` 标记区分，
     * 比让调用方"先试 Classic 再试 Ultralight"干净得多。
     */
    /**
     * 只取 `kind` 一个字段的中间类型。
     *
     * 用它反序列化比手工解析 JsonElement 稳：多余字段靠
     * `ignoreUnknownKeys` 忽略，缺字段则用默认值。
     */
    @Serializable
    private data class KindOnly(val kind: String = "")

    fun detectKind(text: String): String? = runCatching {
        json.decodeFromString<KindOnly>(text).kind.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun fromUltralightJson(text: String): Result<UltralightBackup> = runCatching {
        val parsed = json.decodeFromString<UltralightBackup>(text)
        require(parsed.version == VERSION) {
            AppStrings.get(R.string.err_backup_version, parsed.version)
        }
        require(parsed.pages.isNotEmpty()) { AppStrings.get(R.string.err_backup_empty) }
        parsed
    }

    fun fromJson(text: String): Result<Backup> = runCatching {
        val parsed = json.decodeFromString<Backup>(text)
        require(parsed.version == VERSION) {
            AppStrings.get(R.string.err_backup_version, parsed.version)
        }
        require(parsed.blockCount > 0 && parsed.sectors.isNotEmpty()) {
            AppStrings.get(R.string.err_backup_empty)
        }
        parsed
    }
}
