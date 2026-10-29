package com.nfctool.app.core.nfc

import androidx.annotation.StringRes
import com.nfctool.app.R

/**
 * 卡片类型识别结果，用于给用户一个直观的“这张卡是什么”的判断。
 */
enum class CardType(
    @StringRes val displayNameRes: Int,
    @StringRes val categoryRes: Int,
    @StringRes val summaryRes: Int,
) {
    NDEF_GENERIC(
        displayNameRes = R.string.card_ndef_generic_name,
        categoryRes = R.string.card_ndef_generic_category,
        summaryRes = R.string.card_ndef_generic_summary,
    ),
    NDEF_EMPTY(
        displayNameRes = R.string.card_ndef_empty_name,
        categoryRes = R.string.card_ndef_empty_category,
        summaryRes = R.string.card_ndef_empty_summary,
    ),
    NTAG213(
        displayNameRes = R.string.card_ntag213_name,
        categoryRes = R.string.card_ntag213_category,
        summaryRes = R.string.card_ntag213_summary,
    ),
    NTAG215(
        displayNameRes = R.string.card_ntag215_name,
        categoryRes = R.string.card_ntag215_category,
        summaryRes = R.string.card_ntag215_summary,
    ),
    NTAG216(
        displayNameRes = R.string.card_ntag216_name,
        categoryRes = R.string.card_ntag216_category,
        summaryRes = R.string.card_ntag216_summary,
    ),
    ULTRALIGHT(
        displayNameRes = R.string.card_ultralight_name,
        categoryRes = R.string.card_ultralight_category,
        summaryRes = R.string.card_ultralight_summary,
    ),
    ULTRALIGHT_C(
        displayNameRes = R.string.card_ultralight_c_name,
        categoryRes = R.string.card_ultralight_c_category,
        summaryRes = R.string.card_ultralight_c_summary,
    ),
    MIFARE_CLASSIC_1K(
        displayNameRes = R.string.card_mifare_classic_1k_name,
        categoryRes = R.string.card_mifare_classic_1k_category,
        summaryRes = R.string.card_mifare_classic_1k_summary,
    ),
    MIFARE_CLASSIC_4K(
        displayNameRes = R.string.card_mifare_classic_4k_name,
        categoryRes = R.string.card_mifare_classic_4k_category,
        summaryRes = R.string.card_mifare_classic_4k_summary,
    ),
    MIFARE_PLUS(
        displayNameRes = R.string.card_mifare_plus_name,
        categoryRes = R.string.card_mifare_plus_category,
        summaryRes = R.string.card_mifare_plus_summary,
    ),
    MIFARE_DESFIRE(
        displayNameRes = R.string.card_mifare_desfire_name,
        categoryRes = R.string.card_mifare_desfire_category,
        summaryRes = R.string.card_mifare_desfire_summary,
    ),
    ISO15693(
        displayNameRes = R.string.card_iso15693_name,
        categoryRes = R.string.card_iso15693_category,
        summaryRes = R.string.card_iso15693_summary,
    ),
    FELICA(
        displayNameRes = R.string.card_felica_name,
        categoryRes = R.string.card_felica_category,
        summaryRes = R.string.card_felica_summary,
    ),
    BANK_CARD(
        displayNameRes = R.string.card_bank_name,
        categoryRes = R.string.card_bank_category,
        summaryRes = R.string.card_bank_summary,
    ),
    CIVIC_ID(
        displayNameRes = R.string.card_civic_name,
        categoryRes = R.string.card_civic_category,
        summaryRes = R.string.card_civic_summary,
    ),
    ACCESS_CARD(
        displayNameRes = R.string.card_access_name,
        categoryRes = R.string.card_access_category,
        summaryRes = R.string.card_access_summary,
    ),
    UNKNOWN(
        displayNameRes = R.string.card_unknown_name,
        categoryRes = R.string.card_unknown_category,
        summaryRes = R.string.card_unknown_summary,
    ),
    ;

    val isKnown: Boolean get() = this != UNKNOWN
}

/**
 * 卡片识别器：依据 UID 特征 + 技术组合 + 容量信息推断卡片型号。
 *
 * UID 判据参考各厂商公开的 UID 结构：
 *  - NXP 单字节 UID：前 1 字节为 0x04
 *  - NXP 4/7 字节 UID：前 2 字节为 0xE0 0x04
 */
object CardDetector {

    fun detect(
        uid: ByteArray?,
        techs: List<NfcTech>,
        mifareSize: Int? = null,
        ultralightPages: Int? = null,
        iso15693BlockSize: Int? = null,
    ): CardType {
        val hasNdef = techs.contains(NfcTech.NDEF)
        val hasFormatable = techs.contains(NfcTech.NDEF_FORMATABLE)
        val hasClassic = techs.contains(NfcTech.MIFARE_CLASSIC)
        val hasUltralight = techs.contains(NfcTech.MIFARE_ULTRALIGHT)
        val hasIsoDep = techs.contains(NfcTech.ISO_DEP) || techs.contains(NfcTech.MIFARE_DESFIRE)
        val has15693 = techs.contains(NfcTech.NFC_V)
        val hasFelica = techs.contains(NfcTech.NFC_F)

        if (hasFelica) return CardType.FELICA
        if (has15693) return CardType.ISO15693
        if (hasClassic) {
            return when (mifareSize) {
                1024 -> CardType.MIFARE_CLASSIC_1K
                4096 -> CardType.MIFARE_CLASSIC_4K
                else -> CardType.MIFARE_CLASSIC_1K
            }
        }
        if (hasUltralight) {
            return when (ultralightPages) {
                45 -> CardType.NTAG213
                135 -> CardType.NTAG215
                231 -> CardType.NTAG216
                48 -> CardType.ULTRALIGHT
                else -> CardType.ULTRALIGHT
            }
        }
        if (hasIsoDep) {
            // DESFire 通过 IsoDep 暴露，靠 UID 前缀和 AID 判定
            if (uid != null && uid.size >= 3 && uid[0] == 0x04.toByte()) {
                return CardType.MIFARE_DESFIRE
            }
            return CardType.BANK_CARD
        }
        if (hasNdef) return CardType.NDEF_GENERIC
        if (hasFormatable) return CardType.NDEF_EMPTY

        // 依据 UID 前缀兜底判断
        if (uid != null && uid.isNotEmpty()) {
            val first = uid[0].toInt() and 0xFF
            val second = if (uid.size > 1) uid[1].toInt() and 0xFF else 0
            if (first == 0x04) return CardType.NTAG213
            if (first == 0xE0 && second == 0x04) return CardType.ACCESS_CARD
        }
        return CardType.UNKNOWN
    }

    /**
     * 银行卡 EMV AID 列表，用于识别支付卡。
     */
    val EMV_AIDS = listOf(
        "A0000000031010" to "Visa",
        "A0000000041010" to "Mastercard",
        "A0000000250102" to "银联 UnionPay",
        "A0000000090000" to "美国运通 Amex",
        "A0000000270020" to "JCB",
        "A0000000010000" to "美国运通旧版",
    )
}
