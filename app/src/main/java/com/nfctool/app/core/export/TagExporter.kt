package com.nfctool.app.core.export

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.nfctool.app.core.nfc.Hex
import com.nfctool.app.core.nfc.TagOverview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 标签数据导出器。
 *
 * 支持两种格式：
 *  - JSON：结构化，便于后续导入或程序处理
 *  - 文本：人可读，含 hexdump
 */
object TagExporter {

    private val json = Json { prettyPrint = true }

    /**
     * 生成 JSON 导出内容。
     *
     * @param includeSecrets 是否原样导出敏感字段（如 Wi-Fi 密码）。默认脱敏。
     */
    fun toJson(
        overview: TagOverview,
        mifareDump: Map<Int, ByteArray> = emptyMap(),
        includeSecrets: Boolean = false,
    ): String {
        val root = buildJsonObject {
            put("uid", Hex.formatUid(overview.uid))
            put("uidHex", Hex.toHexNoSep(overview.uid))
            put("uidReversed", Hex.formatUid(Hex.reversed(overview.uid)))
            put("cardType", AppStrings.get(overview.cardType.displayNameRes))
            put("cardCategory", AppStrings.get(overview.cardType.categoryRes))
            put("technologies", buildJsonArray {
                overview.techs.forEach { tech ->
                    add(
                        buildJsonObject {
                            put("name", AppStrings.get(tech.displayNameRes))
                            put("class", tech.techClass)
                            put("readable", tech.readable)
                            put("writable", tech.writable)
                        }
                    )
                }
            })
            put("ndefWritable", overview.ndefWritable)
            put("ndefCapacity", overview.ndefCapacity)
            put("ndefState", overview.ndefState.name)
            put("ndefFormatable", overview.ndefFormatable)
            put("mifareSize", overview.mifareSize)
            put("mifareSectors", overview.mifareSectors)
            put("mifareBlocks", overview.mifareBlocks)
            put("ultralightPages", overview.ultralightPages)
            put("isoDepProtocol", overview.isoDepProtocol)

            overview.ndefRecords?.let { records ->
                put("ndef", buildJsonArray {
                    records.forEach { rec ->
                        add(
                            buildJsonObject {
                                put("type", rec.typeName)
                                put("kind", rec.kind.name)
                                put("tnf", rec.tnf)
                                put("payloadSize", rec.payloadSize)
                                rec.text?.let { put("text", it) }
                                rec.uri?.let { put("uri", it) }
                                rec.mimeType?.let { put("mimeType", it) }
                                if (rec.details.isNotEmpty()) {
                                    put("details", buildJsonObject {
                                        rec.details.forEach { detail ->
                                            put(
                                                detail.label,
                                                if (includeSecrets) detail.value
                                                else maskSecret(detail.label, detail.value),
                                            )
                                        }
                                    })
                                }
                                put("rawHex", Hex.toHexNoSep(rec.raw))
                            }
                        )
                    }
                })
            }

            if (mifareDump.isNotEmpty()) {
                put("mifareDump", buildJsonArray {
                    mifareDump.entries.sortedBy { it.key }.forEach { (block, data) ->
                        add(
                            buildJsonObject {
                                put("block", block)
                                put("hex", Hex.toHexNoSep(data))
                                put("ascii", asciiOf(data))
                            }
                        )
                    }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * 生成纯文本导出内容。
     *
     * @param includeSecrets 是否原样导出敏感字段（如 Wi-Fi 密码）。默认脱敏。
     */
    fun toText(
        overview: TagOverview,
        mifareDump: Map<Int, ByteArray> = emptyMap(),
        includeSecrets: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.appendLine(AppStrings.get(R.string.export_header))
        sb.appendLine(AppStrings.get(R.string.export_card_type, AppStrings.get(overview.cardType.displayNameRes), AppStrings.get(overview.cardType.categoryRes)))
        sb.appendLine(AppStrings.get(R.string.export_card_summary, AppStrings.get(overview.cardType.summaryRes)))
        sb.appendLine("UID      : ${Hex.formatUid(overview.uid)}")
        sb.appendLine(AppStrings.get(R.string.export_uid_reversed, Hex.formatUid(Hex.reversed(overview.uid))))
        sb.appendLine(AppStrings.get(R.string.export_techs, overview.techs.joinToString(", ") { AppStrings.get(it.displayNameRes) }))
        sb.appendLine(AppStrings.get(R.string.export_raw_techs, overview.rawTechList.joinToString(", ")))
        sb.appendLine(AppStrings.get(R.string.export_ndef_state, overview.ndefState.name))
        if (overview.ndefCapacity > 0) {
            sb.appendLine(AppStrings.get(R.string.export_ndef_capacity, overview.ndefCapacity, overview.ndefWritable.toString()))
        }
        if (overview.ndefFormatable) {
            sb.appendLine(AppStrings.get(R.string.export_ndef_blank))
        }
        overview.ndefError?.let { sb.appendLine(AppStrings.get(R.string.export_ndef_error, it)) }
        if (overview.mifareSize > 0) {
            sb.appendLine(AppStrings.get(R.string.export_mifare, overview.mifareSize / 1024, overview.mifareSectors, overview.mifareBlocks))
        }
        if (overview.ultralightPages > 0) {
            sb.appendLine(AppStrings.get(R.string.export_ultralight, overview.ultralightPages))
        }
        sb.appendLine()

        overview.ndefRecords?.let { records ->
            if (records.isNotEmpty()) {
                sb.appendLine(AppStrings.get(R.string.export_records_header))
                records.forEachIndexed { index, rec ->
                    sb.appendLine(AppStrings.get(R.string.export_record_title, index + 1, rec.typeName, rec.payloadSize))
                    rec.text?.let { sb.appendLine(AppStrings.get(R.string.export_record_text, it)) }
                    rec.uri?.let { sb.appendLine(AppStrings.get(R.string.export_record_uri, it)) }
                    rec.mimeType?.let { sb.appendLine(AppStrings.get(R.string.export_record_mime, it)) }
                    rec.details.forEach { detail ->
                        val value = if (includeSecrets) detail.value
                        else maskSecret(detail.label, detail.value)
                        sb.appendLine("     ${detail.label} : $value")
                    }
                    sb.appendLine(AppStrings.get(R.string.export_record_raw, Hex.toHex(rec.raw)))
                }
                sb.appendLine()
            }
        }

        if (mifareDump.isNotEmpty()) {
            sb.appendLine(AppStrings.get(R.string.export_blocks_header))
            mifareDump.entries.sortedBy { it.key }.forEach { (block, data) ->
                sb.appendLine(AppStrings.get(R.string.export_block, block, Hex.toHex(data)))
                sb.appendLine("         |${asciiOf(data)}|")
            }
        }

        return sb.toString()
    }

    private fun asciiOf(data: ByteArray): String =
        data.joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar().toString() else "."
        }

    /**
     * 敏感字段脱敏：密码之类的内容默认不原样导出。
     */
    private fun maskSecret(label: String, value: String): String {
        val sensitive = label.contains("密码") || label.contains("密钥") ||
            label.contains("Password") || label.contains("Key")
        if (!sensitive || value.isEmpty()) return value
        return "*".repeat(value.length)
    }

    /**
     * 把内容写入缓存目录的临时文件，返回可分享的 Uri。
     */
    fun writeToCache(context: Context, content: String, fileName: String): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * 分享导出内容。
     */
    fun share(context: Context, content: String, fileName: String, mimeType: String) {
        val uri = writeToCache(context, content, fileName)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, AppStrings.get(R.string.export_share_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 生成带时间戳的文件名。
     */
    fun buildFileName(overview: TagOverview, extension: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val type = AppStrings.get(overview.cardType.displayNameRes).replace(Regex("[^\\w\\u4e00-\\u9fa5]"), "_")
        return "NFC_${type}_${overview.uid.joinToString("") { "%02X".format(it) }}_$stamp.$extension"
    }
}
