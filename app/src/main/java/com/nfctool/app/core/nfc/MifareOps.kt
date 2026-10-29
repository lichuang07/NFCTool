package com.nfctool.app.core.nfc

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcV

/**
 * MIFARE Classic 读写器。
 *
 * 结构：1K 卡 = 16 扇区 × 4 块 = 64 块，每块 16 字节。
 *       每个扇区的最后一块是 "sector trailer"，含 Key A / Access bits / Key B。
 * 4K 卡 = 40 扇区，大扇区（32~39）每扇区 16 块。
 *
 * 注意：Android 的 MifareClassic 没有暴露 isBlockTrailer()，
 * trailer 位置需按扇区自行计算（1K/2K 每扇区 4 块，4K 大扇区 16 块）。
 */
class MifareClassicOps(private val session: TagSession) {

    val card: MifareClassic? get() = session.mifareClassic

    val isAvailable: Boolean get() = card != null

    /** 扇区数量 */
    val sectorCount: Int get() = card?.sectorCount ?: 0

    /** 块总数 */
    val blockCount: Int get() = card?.blockCount ?: 0

    /** 卡容量（字节） */
    val size: Int get() = card?.size ?: 0

    /** 卡片类型（TYPE_CLASSIC / TYPE_PLUS / TYPE_PRO / TYPE_UNKNOWN） */
    val type: Int get() = card?.type ?: MifareClassic.TYPE_UNKNOWN

    fun sectorOfBlock(block: Int): Int = card?.blockToSector(block) ?: -1

    /** 判断块是否为所在扇区的 trailer */
    fun isTrailer(block: Int): Boolean {
        val mc = card ?: return false
        if (block < 0 || block >= mc.blockCount) return false
        val sector = mc.blockToSector(block)
        val start = mc.sectorToBlock(sector)
        val blocksInSector = mc.getBlockCountInSector(sector)
        return block == start + blocksInSector - 1
    }

    /**
     * 使用给定密钥认证扇区。
     * @param key 6 字节密钥
     * @param keyType KEY_TYPE_A 或 KEY_TYPE_B
     */
    fun authenticate(sector: Int, key: ByteArray, keyType: Int): Result<Unit> = runCatching {
        val mc = card ?: error(AppStrings.tagNoMifareClassic())
        mc.connect()
        val ok = if (keyType == KEY_TYPE_B) {
            mc.authenticateSectorWithKeyB(sector, key)
        } else {
            mc.authenticateSectorWithKeyA(sector, key)
        }
        if (!ok) error(AppStrings.get(R.string.err_sector_auth_failed, sector))
    }

    /** 使用默认密钥认证（Key A / Key B 均为 FFFFFFFFFFFF） */
    fun authenticateDefault(sector: Int): Result<Unit> =
        authenticate(sector, DEFAULT_KEY, KEY_TYPE_A)

    /** 读取单个块（需先认证对应扇区） */
    fun readBlock(block: Int): Result<ByteArray> = runCatching {
        val mc = card ?: error(AppStrings.tagNoMifareClassic())
        if (!mc.isConnected) mc.connect()
        mc.readBlock(block) ?: error(AppStrings.get(R.string.err_read_block_failed, block))
    }

    /** 写入单个块（需先认证对应扇区），块大小必须为 16 字节 */
    fun writeBlock(block: Int, data: ByteArray): Result<Unit> = runCatching {
        require(data.size == 16) {
            AppStrings.get(R.string.err_mifare_block_size, data.size)
        }
        val mc = card ?: error(AppStrings.tagNoMifareClassic())
        if (!mc.isConnected) mc.connect()
        mc.writeBlock(block, data)
    }

    /** 读取整个扇区的所有块 */
    fun readSector(sector: Int): Result<List<ByteArray>> = runCatching {
        val mc = card ?: error(AppStrings.tagNoMifareClassic())
        val start = mc.sectorToBlock(sector)
        val count = mc.getBlockCountInSector(sector)
        (0 until count).map { offset ->
            mc.readBlock(start + offset) ?: error(AppStrings.get(R.string.err_read_block_failed_short, start + offset))
        }
    }

    /** 读取全卡所有扇区 */
    fun dumpAll(): Result<Map<Int, ByteArray>> = runCatching {
        val mc = card ?: error(AppStrings.tagNoMifareClassic())
        if (!mc.isConnected) mc.connect()
        val result = mutableMapOf<Int, ByteArray>()
        for (sector in 0 until mc.sectorCount) {
            // 优先用默认密钥认证，失败则跳过该扇区（通常是加了访问保护的扇区）
            if (!mc.authenticateSectorWithKeyA(sector, DEFAULT_KEY)) {
                mc.authenticateSectorWithKeyB(sector, DEFAULT_KEY)
            }
            val start = mc.sectorToBlock(sector)
            val count = mc.getBlockCountInSector(sector)
            for (offset in 0 until count) {
                mc.readBlock(start + offset)?.let { result[start + offset] = it }
            }
        }
        result
    }

    /**
     * 把指定扇区恢复出厂状态。
     *
     * Android 的 [MifareClassic.restore] 会把该扇区的 trailer 恢复为默认密钥
     * （Key A / Key B 均为 FFFFFFFFFFFF）并重置访问位。扇区 0 还会重写 MAD。
     *
     * @param sector 要恢复的扇区号
     */
    fun restoreFactory(sector: Int = 0): Result<Unit> = runCatching {
        val mc = card ?: error(AppStrings.tagNoMifareClassic())
        if (!mc.isConnected) mc.connect()
        mc.restore(sector)
    }

    /** 恢复全部扇区 */
    fun restoreAllSectors(): Result<Int> = runCatching {
        val mc = card ?: error(AppStrings.tagNoMifareClassic())
        if (!mc.isConnected) mc.connect()
        var count = 0
        for (sector in 0 until mc.sectorCount) {
            runCatching { mc.restore(sector) }.onSuccess { count++ }
        }
        count
    }

    companion object {
        /** Key A */
        const val KEY_TYPE_A = 0

        /** Key B */
        const val KEY_TYPE_B = 1

        /** MIFARE 默认密钥 */
        val DEFAULT_KEY = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )

        /** 默认 sector trailer: KeyA + Access(FF) + GPN(FF) + KeyB */
        val TRAILER_DEFAULT = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )
    }
}

/**
 * MIFARE Ultralight 读写器。
 *
 * 内存按「页(page)」组织，每页 4 字节。NTAG213 = 45 页，NTAG215 = 135 页。
 * 第 0-2 页存 UID/厂商信息，第 4 页起为用户数据。
 *
 * 注意：Android 的 MifareUltralight 只提供 readPages(int)，
 * 读取单页需自行截取返回数组。
 */
class MifareUltralightOps(private val session: TagSession) {

    val card: MifareUltralight? get() = session.mifareUltralight

    val isAvailable: Boolean get() = card != null

    /** 页大小（字节） */
    val pageSize: Int get() = MifareUltralight.PAGE_SIZE

    /** 总页数（Ultralight / Ultralight C 均为 48 页） */
    val pageCount: Int
        get() {
            val mc = card ?: return 0
            return when (mc.type) {
                MifareUltralight.TYPE_ULTRALIGHT, MifareUltralight.TYPE_ULTRALIGHT_C -> 48
                else -> 0
            }
        }

    /** 用户数据页数（从第 4 页开始到容量锁之前） */
    fun userPageCount(): Int = (pageCount - NDEF_START_PAGE).coerceAtLeast(0)

    /** 读取单页 */
    fun readPage(page: Int): Result<ByteArray> = runCatching {
        val mc = card ?: error(AppStrings.get(R.string.err_tag_no_mifare_ultralight))
        if (!mc.isConnected) mc.connect()
        val data = mc.readPages(page)
        if (data.size < pageSize) error(AppStrings.get(R.string.err_read_page_failed, page))
        data.copyOfRange(0, pageSize)
    }

    fun writePage(page: Int, data: ByteArray): Result<Unit> = runCatching {
        require(data.size == pageSize) {
            AppStrings.get(R.string.err_ultralight_page_size, pageSize, data.size)
        }
        val mc = card ?: error(AppStrings.get(R.string.err_tag_no_mifare_ultralight))
        if (!mc.isConnected) mc.connect()
        mc.writePage(page, data)
    }

    /**
     * 批量读页。
     *
     * ⚠️ Android 的 [MifareUltralight.readPages] 每次只返回 **4 页**
     * （从 pageOffset 起连续 4 页）。早期实现以为一次调用就能拿回 count 页，
     * 于是 count>4 时（读取页一次传 44）必然报「数据过短」。
     * 这里按 4 页一组循环读再拼接。
     */
    fun readPages(fromPage: Int, count: Int): Result<List<ByteArray>> = runCatching {
        val mc = card ?: error(AppStrings.get(R.string.err_tag_no_mifare_ultralight))
        if (!mc.isConnected) mc.connect()
        val pages = mutableListOf<ByteArray>()
        var page = fromPage
        var remaining = count
        while (remaining > 0) {
            val batch = minOf(4, remaining)
            // readPages(page) 固定返回 4 页（16 字节），取前 batch 页
            val data = mc.readPages(page)
            if (data.size < batch * pageSize) {
                error(AppStrings.get(R.string.err_read_page_failed, page))
            }
            for (i in 0 until batch) {
                pages += data.copyOfRange(i * pageSize, (i + 1) * pageSize)
            }
            page += batch
            remaining -= batch
        }
        pages
    }

    /**
     * 设置一次性可编程锁（OTP lock）。
     * 写入后该页永久不可再改，是不可逆操作，UI 层必须二次确认。
     *
     * Ultralight 的锁定通过 COMPATIBILITY WRITE（0xA0）+ 页号 + 4 字节数据完成，
     * 其中数据的低 2 位即该页的 lock bit。早期实现直接发 0xAA ——
     * 那不是任何 Ultralight 合法命令（0xAA 是 DESFire 的 AES 认证码），
     * 锁定必然失败。
     *
     * @param data 要写入该页的 4 字节数据，其 bit0-1 为 0 表示锁定该页
     */
    fun lockOtpPage(page: Int, data: ByteArray = LOCK_DATA): Result<Unit> = runCatching {
        require(data.size == pageSize) {
            "Ultralight 页大小必须为 $pageSize 字节，当前为 ${data.size}"
        }
        val mc = card ?: error(AppStrings.get(R.string.err_tag_no_mifare_ultralight))
        if (!mc.isConnected) mc.connect()
        // 0xA0 = COMPATIBILITY WRITE，随后页号 + 4 字节数据
        mc.transceive(byteArrayOf(0xA0.toByte(), page.toByte()) + data)
    }

    /**
     * 真实总页数。
     *
     * 不能只看 [pageCount]：Android 的 `MifareUltralight.getType()` 对
     * **所有 NTAG 都返回 TYPE_ULTRALIGHT**，而 NTAG213/215/216 实际是
     * 45 / 135 / 231 页。必须从 Capability Container 反推。
     */
    val detectedPageCount: Int get() = detectUserPageCount() + NON_USER_PAGES

    /**
     * 用户数据页数（第 4 页起）。
     *
     * 读不到合法 CC（普通 Ultralight / Ultralight C 没有 CC）时按 48 页卡处理。
     */
    fun detectUserPageCount(): Int = runCatching {
        val mc = card ?: return@runCatching DEFAULT_USER_PAGES
        if (!mc.isConnected) mc.connect()
        val cc = mc.readPages(3)
        val pages = userPagesFromCc(cc, pageSize)
        if (pages > 0) pages else DEFAULT_USER_PAGES
    }.getOrDefault(DEFAULT_USER_PAGES)

    companion object {
        /** NDEF 起始页（Ultralight 的 NDEF 消息从第 4 页开始） */
        const val NDEF_START_PAGE = 4

        /** 普通 Ultralight / Ultralight C 的总页数 */
        const val DEFAULT_PAGE_COUNT = 48

        /** 默认用户页数 = 48 - 4 */
        const val DEFAULT_USER_PAGES = DEFAULT_PAGE_COUNT - NDEF_START_PAGE

        /**
         * 总页数中不属于用户区的部分：4 页（UID + CC + 锁定位）+ 5 页配置区。
         * 用户页数 + 9 即 NTAG 的实际总页数。
         */
        const val NON_USER_PAGES = 9

        /**
         * 从 Capability Container 反推用户页数（**纯函数**，便于单测）。
         *
         * CC 布局（页 3）：`[0]=0xE1 魔数 [1]=版本 [2]=MLEN [3]=额外特性`
         *
         * ⚠️ **MLEN 的单位是「用户区字节数 / 8」**，不是字节数也不是页数：
         *  - NTAG213 → MLEN=0x12(18) → 18×8=144 字节 → 144/4 = **36** 页 → 总 45 页
         *  - NTAG215 → MLEN=0x3E(62) → 62×8=496 字节 → **124** 页 → 总 133 页
         *  - NTAG216 → MLEN=0x6D(109) → 109×8=872 字节 → **218** 页 → 总 227 页
         *
         * 早期实现写成 `mlen / 8 / 4`（多除了一次 8），NTAG213 的 18 会被算成
         * 0 页而退化成"普通 Ultralight 48 页" —— 所有 NTAG 型号都认错。
         *
         * @return 用户页数；读不到合法 CC 时返回 -1
         */
        fun userPagesFromCc(cc: ByteArray, pageSize: Int = 4): Int {
            if (cc.size < 3) return -1
            if (cc[0] != 0xE1.toByte()) return -1 // 非标准 CC，按普通 Ultralight 处理
            val mlen = cc[2].toInt() and 0xFF
            if (mlen <= 0) return -1
            val userPages = mlen * 8 / pageSize
            return if (userPages > 0) userPages else -1
        }

        /**
         * 锁定页数据：第 3 字节 0x00 0x00 会把对应页的 lock bit 清零。
         * COMPATIBILITY WRITE 的锁定语义：写入值 & 0x03 == 0 即锁定。
         */
        private val LOCK_DATA = byteArrayOf(0x00, 0x00, 0x00, 0x00)
    }
}

/**
 * ISO 15693 (NFC-V) 读写器。
 *
 * ## 帧格式（2026-10-25 修正）
 *
 * ISO 15693 的每条命令都必须以 **flags 字节**开头：
 * ```
 * [Flags][Command][Parameters…]
 * ```
 * 早期实现整个 flags 字节缺失、指令码也用错了（Read Block 发成 0x30、
 * Write Block 发成 0x08），NFC-V 读写全都发不出去，且被
 * `stripUid()` 的假结构掩盖成空响应。
 *
 *  - bit1 (0x02)：高数据率，Android NfcV 默认
 *  - bit5 (0x20)：寻址模式（命令尾附 8 字节 UID）
 *  - bit2 (0x04)：Inventory 命令专用
 *
 * 这里用**非寻址模式**（flags = 0x02）：要求场内只有一张卡，
 * 与本应用「一次读一张」的使用场景一致，省去 UID 拼接。
 *
 * 响应格式统一为 `[错误码][数据…]`，错误码 0x00 表示成功。
 * 正确指令码：Read Single Block = 0x20 / Write Single Block = 0x21 /
 * Lock Block = 0x22 / Read Multiple Blocks = 0x23 / Inventory = 0x01。
 */
class Iso15693Ops(private val session: TagSession) {

    /** 非寻址模式 + 高数据率 */
    val isAvailable: Boolean
        get() = session.tag.techList.contains("android.nfc.tech.NfcV")

    /** 读取单个块 */
    fun readBlock(block: Int): Result<ByteArray> = runCatching {
        val tech = connect()
        val response = tech.transceive(command(0x20, block.toByte()))
        dataOf(response)
    }

    /** 写入单个块 */
    fun writeBlock(block: Int, data: ByteArray): Result<Unit> = runCatching {
        val tech = connect()
        val response = tech.transceive(command(0x21, block.toByte(), *data))
        requireOk(response)
    }

    /** 读取多块（连续块，count 最多 32） */
    fun readBlocks(startBlock: Int, count: Int): Result<ByteArray> = runCatching {
        require(count in 1..32) { "多块读取数量必须在 1 到 32 之间" }
        val tech = connect()
        // 多块读取的参数字节是「块数 - 1」
        val response = tech.transceive(command(0x23, startBlock.toByte(), (count - 1).toByte()))
        dataOf(response)
    }

    /** 永久锁定块（不可逆） */
    fun lockBlock(block: Int): Result<Unit> = runCatching {
        val tech = connect()
        val response = tech.transceive(command(0x22, block.toByte()))
        requireOk(response)
    }

    /** 列出场内的 ISO 15693 标签（返回 DSFID + 8 字节 UID） */
    fun inventory(): Result<ByteArray> = runCatching {
        val tech = connect()
        // Inventory：flags 需置 bit2 (0x04)，AFI=0x00，掩码长度 0x00 = 16 slots
        val response = tech.transceive(byteArrayOf(0x26, 0x01, 0x00))
        requireOk(response)
        response.copyOfRange(1, response.size)
    }

    private fun connect(): NfcV {
        val tech = NfcV.get(session.tag) ?: error(AppStrings.tagNoNfcV())
        if (!tech.isConnected) tech.connect()
        return tech
    }

    /** 组装命令：[Flags=0x02][Command][Parameters…] */
    private fun command(cmd: Byte, vararg params: Byte): ByteArray =
        byteArrayOf(0x02, cmd, *params)

    /** 校验响应错误码并取出数据部分 */
    private fun dataOf(response: ByteArray): ByteArray {
        requireOk(response)
        return response.copyOfRange(1, response.size)
    }

    /** 响应首字节为错误码，0x00 表示成功 */
    private fun requireOk(response: ByteArray) {
        if (response.isEmpty()) error(AppStrings.get(R.string.err_read_failed))
        val code = response[0].toInt() and 0xFF
        if (code != 0x00) {
            error(AppStrings.get(R.string.err_iso15693_status, code.toString(16).uppercase()))
        }
    }
}
