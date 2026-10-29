package com.nfctool.app.core.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIFARE 备份的数据模型与序列化测试。
 *
 * 只覆盖**不依赖 Android API 的纯逻辑**：块可写性判定、统计口径、
 * JSON 往返与非法输入。真正读写卡片的部分只能在真机上验证。
 *
 * 其中「trailer 永不写回」「块 0 永不写回」是安全边界，
 * 用测试钉死，防止以后有人为了"让恢复更完整"而把它们放开。
 */
class MifareBackupTest {

    private fun block(
        index: Int,
        trailer: Boolean = false,
        readable: Boolean = true,
        hex: String = "00".repeat(16),
    ) = MifareBackup.BlockDump(
        index = index,
        isTrailer = trailer,
        readable = readable,
        hex = hex,
    )

    private fun sector(
        index: Int,
        start: Int,
        count: Int,
        authenticated: Boolean = true,
        blocks: List<MifareBackup.BlockDump> = emptyList(),
    ) = MifareBackup.SectorDump(
        index = index,
        blockStart = start,
        blockCount = count,
        authenticated = authenticated,
        keyType = if (authenticated) "A" else null,
        blocks = blocks,
    )

    /** 一份 1K 卡的 Classic 备份样本，供 detectKind 等用例复用 */
    private fun backup1K() = MifareBackup.Backup(
        uid = "04A23F9C",
        cardType = "CLASSIC_1K",
        sizeBytes = 1024,
        sectorCount = 16,
        blockCount = 64,
        createdAt = 1_700_000_000_000L,
        sectors = listOf(
            sector(0, 0, 4, blocks = listOf(block(1), block(2))),
        ),
    )

    // ---------- 可写性判定（安全边界） ----------

    @Test
    fun normalBlockIsRestorable() {
        assertTrue(block(index = 4).isRestorable)
    }

    @Test
    fun blockZeroIsNeverRestorable() {
        // 块 0 是 UID，出厂写死 —— 这是"恢复不能克隆卡片身份"的根因
        assertFalse(block(index = 0).isRestorable)
    }

    @Test
    fun trailerIsNeverRestorable() {
        // 尾块含 Key A / Key B，一律不碰
        assertFalse(block(index = 3, trailer = true).isRestorable)
    }

    @Test
    fun unreadableBlockIsNotRestorable() {
        assertFalse(block(index = 4, readable = false, hex = "").isRestorable)
    }

    @Test
    fun trailerWithDataStillNotRestorable() {
        // 即使 hex 非空（理论上不该发生），isTrailer 也必须一票否决
        assertFalse(block(index = 3, trailer = true, hex = "AA".repeat(16)).isRestorable)
    }

    // ---------- 统计口径 ----------

    @Test
    fun restorableBlocksExcludesTrailerAndBlockZero() {
        val backup = MifareBackup.Backup(
            sectorCount = 1,
            blockCount = 4,
            sectors = listOf(
                sector(
                    0,
                    0,
                    4,
                    blocks = listOf(block(0), block(1), block(2), block(3, trailer = true)),
                ),
            ),
        )
        // 4 块里去掉块 0 与 trailer，只剩 2 块可写
        assertEquals(2, backup.restorableBlocks)
    }

    @Test
    fun unreadableSectorsAreCounted() {
        val backup = MifareBackup.Backup(
            sectorCount = 3,
            blockCount = 12,
            sectors = listOf(
                sector(0, 0, 4, authenticated = true, blocks = listOf(block(1))),
                sector(1, 4, 4, authenticated = false),
                sector(2, 8, 4, authenticated = false),
            ),
        )
        assertEquals(2, backup.unreadableSectors)
    }

    @Test
    fun fullCardAllSectorsAuthenticated() {
        val backup = MifareBackup.Backup(
            sectorCount = 16,
            blockCount = 64,
            sectors = (0 until 16).map { s ->
                sector(
                    s,
                    s * 4,
                    4,
                    blocks = listOf(block(s * 4), block(s * 4 + 1), block(s * 4 + 2), block(s * 4 + 3, trailer = true)),
                )
            },
        )
        assertEquals(0, backup.unreadableSectors)
        // 16 扇区 × 3 个数据块 = 48，再去掉块 0（UID）= 47
        assertEquals(47, backup.restorableBlocks)
    }

    // ---------- 序列化 ----------

    @Test
    fun jsonRoundTrip() {
        val backup = MifareBackup.Backup(
            uid = "04A23F9C",
            cardType = "CLASSIC_1K",
            sizeBytes = 1024,
            sectorCount = 16,
            blockCount = 64,
            createdAt = 1_700_000_000_000L,
            sectors = listOf(
                sector(
                    0,
                    0,
                    4,
                    blocks = listOf(
                        block(0, hex = "04A23F9C" + "4808000000000000"),
                        block(1, hex = "00".repeat(16)),
                        block(2, hex = "00".repeat(16)),
                        block(3, trailer = true, hex = ""),
                    ),
                ),
            ),
        )
        val parsed = MifareBackup.fromJson(MifareBackup.toJson(backup)).getOrThrow()
        assertEquals(backup.uid, parsed.uid)
        assertEquals(backup.cardType, parsed.cardType)
        assertEquals(backup.blockCount, parsed.blockCount)
        assertEquals(1, parsed.sectors.size)
        assertEquals(4, parsed.sectors[0].blocks.size)
        // 脱敏的 trailer 往返后依然是空串
        assertEquals("", parsed.sectors[0].blocks[3].hex)
        assertTrue(parsed.sectors[0].blocks[3].isTrailer)
    }

    @Test
    fun wrongVersionIsRejected() {
        val json = """
            {"version": 99, "uid": "", "cardType": "", "sizeBytes": 0,
             "sectorCount": 16, "blockCount": 64, "createdAt": 0, "sectors": []}
        """.trimIndent()
        assertTrue(MifareBackup.fromJson(json).isFailure)
    }

    @Test
    fun emptyBackupIsRejected() {
        val json = """
            {"version": 1, "uid": "", "cardType": "", "sizeBytes": 0,
             "sectorCount": 0, "blockCount": 0, "createdAt": 0, "sectors": []}
        """.trimIndent()
        assertTrue(MifareBackup.fromJson(json).isFailure)
    }

    @Test
    fun malformedJsonIsRejected() {
        assertTrue(MifareBackup.fromJson("not json at all").isFailure)
    }

    // ---------- NTAG 页数探测（钉死「多除一次 8」的 bug） ----------

    private fun cc(magic: Int, version: Int, mlen: Int) =
        byteArrayOf(magic.toByte(), version.toByte(), mlen.toByte(), 0x00)

    @Test
    fun ntag213UserPages() {
        // NTAG213: MLEN=0x12(18) → 18×8=144 字节 → 144/4 = 36 页 → 总 45 页
        assertEquals(36, MifareUltralightOps.userPagesFromCc(cc(0xE1, 0x10, 0x12)))
    }

    @Test
    fun ntag215UserPages() {
        // NTAG215: MLEN=0x3E(62) → 62×8=496 字节 → 124 页 → 总 133 页
        assertEquals(124, MifareUltralightOps.userPagesFromCc(cc(0xE1, 0x10, 0x3E)))
    }

    @Test
    fun ntag216UserPages() {
        // NTAG216: MLEN=0x6D(109) → 109×8=872 字节 → 218 页 → 总 227 页
        assertEquals(218, MifareUltralightOps.userPagesFromCc(cc(0xE1, 0x10, 0x6D)))
    }

    @Test
    fun ntagTotalPagesAddNonUserPages() {
        val user = MifareUltralightOps.userPagesFromCc(cc(0xE1, 0x10, 0x12))
        assertEquals(45, user + MifareUltralightOps.NON_USER_PAGES)
    }

    @Test
    fun invalidCcReturnsMinusOne() {
        // 非 0xE1 魔数（普通 Ultralight 没有 CC）
        assertEquals(-1, MifareUltralightOps.userPagesFromCc(cc(0x01, 0x02, 0x03)))
        // 长度不足
        assertEquals(-1, MifareUltralightOps.userPagesFromCc(byteArrayOf(0xE1.toByte(), 0x10)))
        // MLEN 为 0
        assertEquals(-1, MifareUltralightOps.userPagesFromCc(cc(0xE1, 0x10, 0x00)))
    }

    // ---------- Ultralight / NTAG 备份 ----------

    private fun ulBackup(pages: Int = 36) = MifareBackup.UltralightBackup(
        uid = "04A23F9C",
        cardType = "NTAG213",
        pageCount = 45,
        userPageStart = 4,
        userPageEnd = 4 + pages - 1,
        createdAt = 1_700_000_000_000L,
        pages = (0 until pages).map { i ->
            MifareBackup.PageDump(index = 4 + i, readable = true, hex = "00".repeat(4))
        },
    )

    @Test
    fun ultralightJsonRoundTrip() {
        val parsed = MifareBackup.fromUltralightJson(MifareBackup.toJson(ulBackup())).getOrThrow()
        assertEquals("NTAG213", parsed.cardType)
        assertEquals(36, parsed.restorablePages)
        assertEquals(4, parsed.userPageStart)
        assertEquals(39, parsed.userPageEnd)
        assertEquals(45, parsed.pageCount)
    }

    @Test
    fun ultralightPageDumpRestorable() {
        assertTrue(MifareBackup.PageDump(index = 4, readable = true, hex = "00".repeat(4)).isRestorable)
        assertFalse(MifareBackup.PageDump(index = 4, readable = false, hex = "").isRestorable)
    }

    @Test
    fun detectKindDistinguishesBothFormats() {
        assertEquals(
            MifareBackup.KIND_CLASSIC,
            MifareBackup.detectKind(MifareBackup.toJson(backup1K())),
        )
        assertEquals(
            MifareBackup.KIND_ULTRALIGHT,
            MifareBackup.detectKind(MifareBackup.toJson(ulBackup())),
        )
    }

    @Test
    fun detectKindOnGarbageReturnsNull() {
        assertEquals(null, MifareBackup.detectKind("not json at all"))
    }

    @Test
    fun ultralightWrongVersionRejected() {
        val json = """
            {"version": 99, "kind": "ultralight", "uid": "", "cardType": "",
             "pageCount": 45, "userPageStart": 4, "userPageEnd": 39,
             "createdAt": 0, "pages": []}
        """.trimIndent()
        assertTrue(MifareBackup.fromUltralightJson(json).isFailure)
    }

    @Test
    fun ultralightEmptyPagesRejected() {
        val json = """
            {"version": 1, "kind": "ultralight", "uid": "", "cardType": "",
             "pageCount": 45, "userPageStart": 4, "userPageEnd": 39,
             "createdAt": 0, "pages": []}
        """.trimIndent()
        assertTrue(MifareBackup.fromUltralightJson(json).isFailure)
    }

    // ---------- 密钥规范化（密钥本用） ----------

    @Test
    fun normalizeKeyUppercaseNoSeparator() {
        assertEquals("FFFFFFFFFFFF", MifareBackup.normalizeKey("ffffffffffff").getOrThrow())
    }

    @Test
    fun normalizeKeyAcceptsSpacesAndColons() {
        assertEquals("A0A1A2A3A4A5", MifareBackup.normalizeKey("a0 a1 a2 a3 a4 a5").getOrThrow())
        assertEquals("A0A1A2A3A4A5", MifareBackup.normalizeKey("A0:A1:A2:A3:A4:A5").getOrThrow())
    }

    @Test
    fun normalizeKeyRejectsWrongLength() {
        assertTrue(MifareBackup.normalizeKey("FFFF").isFailure)         // 2 字节
        assertTrue(MifareBackup.normalizeKey("FF".repeat(8)).isFailure) // 8 字节
    }

    @Test
    fun normalizeKeyRejectsNonHex() {
        assertTrue(MifareBackup.normalizeKey("ZZZZZZZZZZZZ").isFailure)
    }

    @Test
    fun normalizedKeyRoundTrips() {
        // 规范化后的串必须还能被解析回同样的 6 字节
        val normalized = MifareBackup.normalizeKey("a0:a1:a2:a3:a4:a5").getOrThrow()
        assertEquals("A0A1A2A3A4A5", Hex.toHexNoSep(Hex.parse(normalized)))
    }
}
