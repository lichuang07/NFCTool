package com.nfctool.app.core.nfc

import androidx.annotation.StringRes
import com.nfctool.app.R

/**
 * 写入项目录。
 *
 * 写入页只展示这份目录，每个条目点进去是各自的表单页面。
 * 这样新增写入能力不需要动写入页布局，只需在目录里加一条 + 实现对应表单。
 *
 * @param id          唯一标识，同时用作导航路由
 * @param titleRes    卡片上显示的标题（字符串资源，随语言切换）
 * @param summaryRes  一句话说明，显示在标题下方
 * @param group       归类分组
 * @param requiredTech 只有标签具备其中任一技术时才显示；空表示始终显示
 */
enum class WriteEntry(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val group: WriteGroup,
    val requiredTech: List<String> = emptyList(),
) {
    // ---- NDEF：基础内容 ----
    TEXT(
        id = "text",
        titleRes = R.string.we_text_title,
        summaryRes = R.string.we_text_summary,
        group = WriteGroup.BASIC,
    ),
    URI(
        id = "uri",
        titleRes = R.string.we_uri_title,
        summaryRes = R.string.we_uri_summary,
        group = WriteGroup.BASIC,
    ),
    GEO(
        id = "geo",
        titleRes = R.string.we_geo_title,
        summaryRes = R.string.we_geo_summary,
        group = WriteGroup.BASIC,
    ),
    WIFI(
        id = "wifi",
        titleRes = R.string.we_wifi_title,
        summaryRes = R.string.we_wifi_summary,
        group = WriteGroup.BASIC,
    ),

    // ---- NDEF：联系与日程 ----
    VCARD(
        id = "vcard",
        titleRes = R.string.we_vcard_title,
        summaryRes = R.string.we_vcard_summary,
        group = WriteGroup.CONTACT,
    ),
    MECARD(
        id = "mecard",
        titleRes = R.string.we_mecard_title,
        summaryRes = R.string.we_mecard_summary,
        group = WriteGroup.CONTACT,
    ),
    VCALENDAR(
        id = "vcalendar",
        titleRes = R.string.we_vcalendar_title,
        summaryRes = R.string.we_vcalendar_summary,
        group = WriteGroup.CONTACT,
    ),
    SMART_POSTER(
        id = "smartposter",
        titleRes = R.string.we_smartposter_title,
        summaryRes = R.string.we_smartposter_summary,
        group = WriteGroup.CONTACT,
    ),

    // ---- NDEF：图片 ----
    IMAGE(
        id = "image",
        titleRes = R.string.we_image_title,
        summaryRes = R.string.we_image_summary,
        group = WriteGroup.ADVANCED,
    ),
    QRCODE(
        id = "qrcode",
        titleRes = R.string.we_qrcode_title,
        summaryRes = R.string.we_qrcode_summary,
        group = WriteGroup.ADVANCED,
    ),

    // ---- NDEF：系统与协议 ----
    ANDROID_APP(
        id = "android_app",
        titleRes = R.string.we_androidapp_title,
        summaryRes = R.string.we_androidapp_summary,
        group = WriteGroup.SYSTEM,
    ),
    HANDOVER(
        id = "handover",
        titleRes = R.string.we_handover_title,
        summaryRes = R.string.we_handover_summary,
        group = WriteGroup.SYSTEM,
    ),
    BLUETOOTH_OOB(
        id = "bluetooth_oob",
        titleRes = R.string.we_bluetooth_title,
        summaryRes = R.string.we_bluetooth_summary,
        group = WriteGroup.SYSTEM,
    ),
    AID(
        id = "aid",
        titleRes = R.string.we_aid_title,
        summaryRes = R.string.we_aid_summary,
        group = WriteGroup.SYSTEM,
    ),

    // ---- NDEF：高级自定义 ----
    MIME(
        id = "mime",
        titleRes = R.string.we_mime_title,
        summaryRes = R.string.we_mime_summary,
        group = WriteGroup.ADVANCED,
    ),
    EXTERNAL(
        id = "external",
        titleRes = R.string.we_external_title,
        summaryRes = R.string.we_external_summary,
        group = WriteGroup.ADVANCED,
    ),

    // ---- 存储技术 ----
    MIFARE_CLASSIC(
        id = "mifare_classic",
        titleRes = R.string.we_mifareclassic_title,
        summaryRes = R.string.we_mifareclassic_summary,
        group = WriteGroup.STORAGE,
        requiredTech = listOf("MIFARE Classic", "MIFARE Plus"),
    ),
    MIFARE_ULTRALIGHT(
        id = "mifare_ultralight",
        titleRes = R.string.we_mifareultralight_title,
        summaryRes = R.string.we_mifareultralight_summary,
        group = WriteGroup.STORAGE,
        requiredTech = listOf("MIFARE Ultralight"),
    ),
    ISO15693(
        id = "iso15693",
        titleRes = R.string.we_iso15693_title,
        summaryRes = R.string.we_iso15693_summary,
        group = WriteGroup.STORAGE,
        requiredTech = listOf("NFC-V (ISO 15693)"),
    ),
    DESFIRE(
        id = "desfire",
        titleRes = R.string.we_desfire_title,
        summaryRes = R.string.we_desfire_summary,
        group = WriteGroup.STORAGE,
        requiredTech = listOf("ISO 7816 (IsoDep)", "MIFARE DESFire"),
    ),

    // ---- 标签管理 ----
    FORMAT(
        id = "format",
        titleRes = R.string.we_format_title,
        summaryRes = R.string.we_format_summary,
        group = WriteGroup.MANAGEMENT,
    ),
    RESTORE_MIFARE(
        id = "restore_mifare",
        titleRes = R.string.we_restoremifare_title,
        summaryRes = R.string.we_restoremifare_summary,
        group = WriteGroup.MANAGEMENT,
        requiredTech = listOf("MIFARE Classic", "MIFARE Plus"),
    ),
    ;

    companion object {
        fun byId(id: String?): WriteEntry? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 写入项的分组，用于在目录页分区展示。
 */
enum class WriteGroup(@StringRes val labelRes: Int) {
    BASIC(R.string.wg_basic),
    CONTACT(R.string.wg_contact),
    SYSTEM(R.string.wg_system),
    ADVANCED(R.string.wg_advanced),
    STORAGE(R.string.wg_storage),
    MANAGEMENT(R.string.wg_management),
}
