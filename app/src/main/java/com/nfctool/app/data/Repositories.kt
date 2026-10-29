package com.nfctool.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "nfctool_settings")

/**
 * 单条扫描历史记录。
 */
@Serializable
data class ScanRecord(
    val id: String,
    val timestamp: Long,
    val uidHex: String,
    val cardType: String,
    val techSummary: String,
    val ndefSummary: String?,
    val extra: String? = null,
) {
    /** 用于展示的一行摘要 */
    val displayTitle: String get() = ndefSummary?.takeIf { it.isNotBlank() } ?: cardType
}

/**
 * 历史记录存储：使用 DataStore 存放 JSON 数组。
 * 记录数量有限（最多 200 条），不需要引入数据库。
 */
class ScanHistoryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("scan_history")

    val history: Flow<List<ScanRecord>> = context.dataStore.data.map { prefs ->
        decode(prefs[key])
    }

    private fun decode(raw: String?): List<ScanRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ScanRecord>>(raw) }
            .getOrDefault(emptyList())
    }

    suspend fun add(record: ScanRecord) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[key])
            val updated = (listOf(record) + current).take(MAX_RECORDS)
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it[key] = "" }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            val updated = decode(prefs[key]).filterNot { it.id == id }
            prefs[key] = json.encodeToString(updated)
        }
    }

    companion object {
        const val MAX_RECORDS = 200
    }
}

/**
 * 应用设置。
 */
@Serializable
/**
 * 应用设置。
 *
 * 2026-10-25 清理：删掉了 5 个从未被读取的死字段
 * （soundOnScan / autoReadNdef / keepScreenOn / hceForegroundPreferred /
 * backgroundScanning）。Json 配了 ignoreUnknownKeys，旧数据里的这些键
 * 会被安全忽略，不需要迁移。
 */
data class AppSettings(
    val vibrateOnScan: Boolean = true,
    val includeNfcB: Boolean = true,
    val skipNdefCheck: Boolean = false,
    val themeMode: String = "dark",
    /** 强调色预设 key（历史值为中文名「星云紫」，accentPalette() 两种都认） */
    val accentColor: String = "星云紫",
    /** 导出时原样包含密码等敏感字段（默认脱敏） */
    val exportSecrets: Boolean = false,
)

/**
 * 设置持久化。
 */
class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("app_settings")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        decode(prefs[key])
    }

    private fun decode(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }
            .getOrDefault(AppSettings())
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(transform(decode(prefs[key])))
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it[key] = "" }
    }
}

/**
 * MIFARE Classic 密钥本。
 *
 * ## 为什么需要
 * 整卡备份要用密钥认证每个扇区，而**很多卡并不是默认密钥**
 * （FFFFFFFFFFFF）—— 小区门禁、电梯卡常被改成自定义密钥。
 * 只能填一个密钥的话，备份成功率很低。密钥本把常用密钥存下来，
 * 扫描时依次尝试，命中就用。
 *
 * ## 边界
 * 只用于**自己拥有的**卡片。本应用不提供任何密钥破解 / 推导能力，
 * 密钥本里的每一条都由用户自己录入。
 */
class MifareKeyStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("mifare_keys")

    /** 已保存的密钥（规范化为大写 12 位 hex） */
    val keys: Flow<List<String>> = context.dataStore.data.map { prefs -> decode(prefs[key]) }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
    }

    suspend fun add(hex: String) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[key])
            if (hex in current) return@edit // 去重
            prefs[key] = json.encodeToString((current + hex).take(MAX_KEYS))
        }
    }

    suspend fun remove(hex: String) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it == hex })
        }
    }

    companion object {
        const val MAX_KEYS = 20
    }
}

/**
 * 写入模板（收藏夹）的一条记录。
 *
 * ## 为什么不是直接存 SharedContentParser.Parsed
 * `Parsed` 里含 `ByteArray`（图片）与 `VCardCodec.VCard` 对象，**不可直接序列化**。
 * 所以模板只存**轻量的可序列化字段**；vCard 存原始文本，应用时再走
 * [com.nfctool.app.core.nfc.ndef.SharedContentParser.parse] 重建。
 */
@Serializable
data class WriteTemplate(
    val id: String = "",
    val name: String = "",
    /** [SharedContentParser.Kind] 的名字：TEXT / URI / GEO / VCARD */
    val kind: String = "TEXT",
    /** 原始内容：纯文本、链接、vCard 原文 */
    val text: String = "",
    /** 网页标题（URI 类目展示用） */
    val title: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 地点名称（GEO 类目） */
    val label: String? = null,
    val coordinateSystem: String? = null,
    val createdAt: Long = 0L,
) {
    /** 列表里展示的一行摘要 */
    val summary: String
        get() {
            val lat = latitude
            val lon = longitude
            if (lat != null && lon != null) return "$lat, $lon"
            val t = title
            if (!t.isNullOrBlank()) return t
            return text
        }
}

/**
 * 写入模板持久化：DataStore 存 JSON 数组。
 *
 * 数量有限（上限 30 条），不需要数据库。
 */
class WriteTemplateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("write_templates")

    val templates: Flow<List<WriteTemplate>> = context.dataStore.data.map { prefs ->
        decode(prefs[key])
    }

    private fun decode(raw: String?): List<WriteTemplate> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<WriteTemplate>>(raw) }
            .getOrDefault(emptyList())
    }

    suspend fun add(template: WriteTemplate) {
        context.dataStore.edit { prefs ->
            val updated = (listOf(template) + decode(prefs[key])).take(MAX_TEMPLATES)
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.id == id })
        }
    }

    companion object {
        const val MAX_TEMPLATES = 30
    }
}
