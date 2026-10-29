package com.nfctool.app.core.nfc

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings

/**
 * MIFARE DESFire EV1/EV2 高级操作。
 *
 * DESFire 通过 ISO 7816-4 APDU 与卡片通信，原生命令按
 * `CLA=0x90, INS, P1, P2, Lc, Data…, Le` 包装。
 *
 * ⚠️ 2026-10-25 修正：早期实现的文件类指令码大面积错误（GetFileIDs 发 0xF2、
 * ReadData 发 0xF5、WriteData 发 0xF3、CreateStdDataFile 发 0xF1、
 * DeleteFile 发 0xD6、GetKeySettings 发 0xF3），按 NXP AN12752 原生指令集
 * 更正后，未认证状态下至少 GetVersion / GetFileIDs / GetKeySettings 可用。
 * 正确指令码（已对照 NXP 指令表与 libfreefare）：
 * ```
 * GetVersion          0x60    GetFileIDs          0x6F
 * GetFileSettings     0xF5    ReadData            0xBD
 * WriteData           0x3D    CreateStdDataFile   0xCD
 * DeleteFile          0xDF    GetKeySettings      0x45
 * ```
 *
 * ReadData / WriteData 的 offset 与 length 是 **3 字节小端**字段，
 * 且 DESFire over ISO 的封装不带 Lc（`90 <INS> <params…> 00`）。
 *
 * 重要限制：
 *  - 未认证（Master Key）时只能读取版本与文件 ID 等公开信息
 *  - 读取文件内容需要对应密钥
 */
class DesfireOps(private val session: TagSession) {

    private val apdu = ApduEngine(session)

    val isAvailable: Boolean get() = apdu.isAvailable

    /**
     * 读取卡片版本信息（GetVersion）。
     *
     * 响应首帧 7 字节：
     * `[0] 厂商 ID(0x04=NXP) [1] 类型 [2] 子类型 [3] 硬件主版本 [4] 硬件次版本
     *  [5] 存储容量 [6] 协议`。
     * 早期实现取 data[0]/data[1] 当版本号，显示永远是「未知(4.x)」——
     * 那两个字节是厂商 ID 与类型，不是版本。
     */
    fun getVersion(): Result<DesfireVersion> = runCatching {
        val response = apdu.transceiveDetailed(byteArrayOf(0x90.toByte(), 0x60, 0x00, 0x00, 0x00))
        val data = response.getOrThrow().data
        if (data.size < 5) error(AppStrings.get(R.string.err_desfire_version_len, data.size))
        DesfireVersion(
            major = data[3].toInt() and 0xFF,
            minor = data[4].toInt() and 0xFF,
            raw = data.copyOfRange(0, minOf(7, data.size)),
        )
    }

    /**
     * 读取文件 ID 列表（GetFileIDs）。
     *
     * 响应格式：`[文件数量][FID1][FID2]…`，**每个文件 ID 占 1 字节**。
     * 早期实现按 4 字节一项跳过（把 GetFileSettings 的布局套了过来），
     * 文件 ID 列表全部错乱。
     */
    fun getFileIds(fileId: Int = 0x00): Result<List<ByteArray>> = runCatching {
        val response = apdu.transceiveDetailed(
            byteArrayOf(0x90.toByte(), 0x6F, 0x00, 0x00, 0x00)
        ).getOrThrow()
        val data = response.data
        if (data.isEmpty()) return@runCatching emptyList()

        val count = data[0].toInt() and 0xFF
        val ids = mutableListOf<ByteArray>()
        var index = 1
        repeat(count) {
            if (index >= data.size) return@repeat
            ids += byteArrayOf(data[index])
            index += 1
        }
        ids
    }

    /**
     * 读取文件内容（ReadData）。
     *
     * 帧格式：`90 BD <FileID> <Offset LE 3> <Length LE 3> 00`
     */
    fun readFile(
        fileId: Int,
        offset: Int = 0,
        length: Int = 0,
    ): Result<ByteArray> = runCatching {
        val command = byteArrayOf(
            0x90.toByte(),
            0xBD.toByte(),
            fileId.toByte(),
            (offset and 0xFF).toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            ((offset shr 16) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            ((length shr 16) and 0xFF).toByte(),
            0x00,
        )
        val response = apdu.transceiveDetailed(command).getOrThrow()
        response.data
    }

    /**
     * 写入文件内容（WriteData）。
     *
     * 帧格式：`90 3D <FileID> <Offset LE 3> <Length LE 3> <Data…> 00`
     */
    fun writeFile(
        fileId: Int,
        data: ByteArray,
        offset: Int = 0,
    ): Result<Unit> = runCatching {
        val length = data.size
        val command = byteArrayOf(
            0x90.toByte(),
            0x3D.toByte(),
            fileId.toByte(),
            (offset and 0xFF).toByte(),
            ((offset shr 8) and 0xFF).toByte(),
            ((offset shr 16) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            ((length shr 16) and 0xFF).toByte(),
        ) + data + byteArrayOf(0x00)
        val response = apdu.transceiveDetailed(command).getOrThrow()
        if (!response.isSuccess) {
            error(AppStrings.get(R.string.err_desfire_write, ApduEngine.describeStatus(response.statusWord)))
        }
    }

    /**
     * 创建标准数据文件（CreateStdDataFile）。
     *
     * 帧格式：`90 CD <FileID> <ISO FID 2> <CommMode> <AccessR 2> <AccessW 2>
     * <FileSize 3 LE> 00`。file size 为 3 字节小端，>255 也不会被截断。
     */
    fun createStandardFile(
        fileId: Int,
        size: Int,
        isoFileId: Int = 0x0000,
        commMode: Int = 0x00,
        readKey: Int = 0x00,
        writeKey: Int = 0x00,
    ): Result<Unit> = runCatching {
        val command = byteArrayOf(
            0x90.toByte(),
            0xCD.toByte(),
            fileId.toByte(),
            (isoFileId and 0xFF).toByte(),
            ((isoFileId shr 8) and 0xFF).toByte(),
            commMode.toByte(),
            (readKey and 0xFF).toByte(),
            ((readKey shr 8) and 0xFF).toByte(),
            (writeKey and 0xFF).toByte(),
            ((writeKey shr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            0x00,
        )
        val response = apdu.transceiveDetailed(command).getOrThrow()
        if (!response.isSuccess) {
            error(AppStrings.get(R.string.err_desfire_create_file, ApduEngine.describeStatus(response.statusWord)))
        }
    }

    /** 删除文件（DeleteFile） */
    fun deleteFile(fileId: Int): Result<Unit> = runCatching {
        val command = byteArrayOf(0x90.toByte(), 0xDF.toByte(), fileId.toByte(), 0x00, 0x00)
        val response = apdu.transceiveDetailed(command).getOrThrow()
        if (!response.isSuccess) {
            error(AppStrings.get(R.string.err_desfire_delete_file, ApduEngine.describeStatus(response.statusWord)))
        }
    }

    /**
     * 读取密钥配置（GetKeySettings）。
     *
     * 响应 2 字节：`[0] 密钥设置（bit0-5 = 密钥数量相关）`、`[1] 密钥数量`。
     * 早期实现 INS 发成 0xF3（那是 WriteData），必然失败。
     */
    fun getKeySettings(): Result<DesfireKeySettings> = runCatching {
        val command = byteArrayOf(0x90.toByte(), 0x45, 0x00, 0x00, 0x00)
        val response = apdu.transceiveDetailed(command).getOrThrow()
        val data = response.data
        if (data.isEmpty()) error(AppStrings.get(R.string.err_desfire_key_empty))
        DesfireKeySettings(
            keyCount = if (data.size > 1) data[1].toInt() and 0xFF else data[0].toInt() and 0x3F,
            currentKey = 0,
        )
    }

    /**
     * 读取卡片信息汇总。
     */
    fun readCardInfo(): Result<DesfireCardInfo> = runCatching {
        val version = getVersion().getOrNull()
        val fileIds = getFileIds(0x00).getOrNull().orEmpty()
        val keySettings = getKeySettings().getOrNull()
        DesfireCardInfo(
            version = version,
            fileIds = fileIds,
            keySettings = keySettings,
        )
    }
}

/**
 * DESFire 版本号。
 */
data class DesfireVersion(
    val major: Int,
    val minor: Int,
    val raw: ByteArray,
) {
    /** 例如 EV1.1 / EV2.2 */
    val displayName: String
        get() = when (major) {
            0x01 -> "EV1.$minor"
            0x02 -> "EV2.$minor"
            0x03 -> "EV3.$minor"
            else -> AppStrings.get(R.string.err_desfire_version_unknown, major.toString(), minor.toString())
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DesfireVersion) return false
        return major == other.major && minor == other.minor && raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = major
        result = 31 * result + minor
        result = 31 * result + raw.contentHashCode()
        return result
    }
}

/**
 * DESFire 密钥配置。
 */
data class DesfireKeySettings(
    val keyCount: Int,
    val currentKey: Int,
) {
    val displayName: String
        get() = AppStrings.get(R.string.desfire_keys_summary, keyCount)
}

/**
 * DESFire 卡片信息汇总。
 */
data class DesfireCardInfo(
    val version: DesfireVersion?,
    val fileIds: List<ByteArray>,
    val keySettings: DesfireKeySettings?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DesfireCardInfo) return false
        return version == other.version &&
            fileIds.size == other.fileIds.size &&
            keySettings == other.keySettings &&
            fileIds.indices.all { fileIds[it].contentEquals(other.fileIds[it]) }
    }

    override fun hashCode(): Int {
        var result = version?.hashCode() ?: 0
        result = 31 * result + fileIds.size
        result = 31 * result + (keySettings?.hashCode() ?: 0)
        return result
    }
}
