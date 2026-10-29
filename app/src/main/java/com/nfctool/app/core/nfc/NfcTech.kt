package com.nfctool.app.core.nfc

import androidx.annotation.StringRes
import com.nfctool.app.R

/**
 * 手机 NFC 芯片能够识别的全部标签技术。
 *
 * 数据来源：Android `Tag.getTechList()` 返回的技术列表，
 * 与 `android.nfc.tech` 下的技术实现一一对应。
 *
 * 展示字段（displayName / description）为字符串资源 id，
 * 界面随语言切换（2026-10-25 国际化改造）。
 */
enum class NfcTech(
    @StringRes val displayNameRes: Int,
    val techClass: String,
    /** 该技术是否支持读取 */
    val readable: Boolean,
    /** 该技术是否支持写入 */
    val writable: Boolean,
    @StringRes val descriptionRes: Int,
) {
    NDEF(
        R.string.tech_ndef_name, "android.nfc.tech.Ndef", true, true,
        R.string.tech_ndef_desc,
    ),
    NDEF_FORMATABLE(
        R.string.tech_ndef_formatable_name, "android.nfc.tech.NdefFormatable", false, true,
        R.string.tech_ndef_formatable_desc,
    ),
    MIFARE_CLASSIC(
        R.string.tech_mifare_classic_name, "android.nfc.tech.MifareClassic", true, true,
        R.string.tech_mifare_classic_desc,
    ),
    MIFARE_ULTRALIGHT(
        R.string.tech_mifare_ultralight_name, "android.nfc.tech.MifareUltralight", true, true,
        R.string.tech_mifare_ultralight_desc,
    ),
    MIFARE_PLUS(
        R.string.tech_mifare_plus_name, "android.nfc.tech.MifareClassic", true, true,
        R.string.tech_mifare_plus_desc,
    ),
    MIFARE_DESFIRE(
        R.string.tech_mifare_desfire_name, "android.nfc.tech.IsoDep", true, true,
        R.string.tech_mifare_desfire_desc,
    ),
    ISO_DEP(
        R.string.tech_iso_dep_name, "android.nfc.tech.IsoDep", true, true,
        R.string.tech_iso_dep_desc,
    ),
    NFC_A(
        R.string.tech_nfc_a_name, "android.nfc.tech.NfcA", true, true,
        R.string.tech_nfc_a_desc,
    ),
    NFC_B(
        R.string.tech_nfc_b_name, "android.nfc.tech.NfcB", true, true,
        R.string.tech_nfc_b_desc,
    ),
    NFC_F(
        R.string.tech_nfc_f_name, "android.nfc.tech.NfcF", true, true,
        R.string.tech_nfc_f_desc,
    ),
    NFC_V(
        R.string.tech_nfc_v_name, "android.nfc.tech.NfcV", true, true,
        R.string.tech_nfc_v_desc,
    ),
    NFC_BARCODE(
        R.string.tech_nfc_barcode_name, "android.nfc.tech.NfcBarcode", true, false,
        R.string.tech_nfc_barcode_desc,
    ),
    UNKNOWN(
        R.string.tech_unknown_name, "", false, false,
        R.string.tech_unknown_desc,
    );

    companion object {
        private val byClass: Map<String, NfcTech> = entries.associateBy { it.techClass }

        /**
         * 从 techList 中识别所有已知技术。
         *
         * 注意 MIFARE Plus 与 Classic 共享同一个 techClass
         * （android.nfc.tech.MifareClassic），需靠 TYPE_PLUS 的卡片类型区分。
         */
        fun fromTechList(techList: Array<String>): List<NfcTech> {
            val result = mutableListOf<NfcTech>()
            for (raw in techList) {
                val tech = when (raw) {
                    MifareTech.ULTRALIGHT -> MIFARE_ULTRALIGHT
                    MifareTech.CLASSIC -> MIFARE_CLASSIC
                    else -> byClass[raw]
                }
                if (tech != null && tech !in result) result += tech
            }
            return result
        }
    }
}

/** MIFARE 家族的 tech 字符串常量（android.nfc.tech.MifareClassic 等） */
object MifareTech {
    const val CLASSIC = "android.nfc.tech.MifareClassic"
    const val ULTRALIGHT = "android.nfc.tech.MifareUltralight"
}
