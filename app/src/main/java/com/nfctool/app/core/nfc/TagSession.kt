package com.nfctool.app.core.nfc

import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight

/**
 * 底层原始指令通道。
 *
 * 手机 NFC 芯片能做的最底层的事，就是把一串字节发给卡片、把卡片的响应字节读回来。
 * 上层所有的高级能力（NDEF / MIFARE / DESFire）本质上都是在这个通道上跑的协议。
 */
sealed interface RawChannel {
    /** 发送指令并返回响应 */
    fun transceive(data: ByteArray): ByteArray

    /** 该通道对应的技术名，用于 UI 展示 */
    val channelName: String

    class NfcAChannel(private val tech: NfcA) : RawChannel {
        override val channelName = "NFC-A"
        override fun transceive(data: ByteArray): ByteArray = tech.transceive(data)
    }

    class NfcBChannel(private val tech: NfcB) : RawChannel {
        override val channelName = "NFC-B"
        override fun transceive(data: ByteArray): ByteArray = tech.transceive(data)
    }

    class NfcFChannel(private val tech: NfcF) : RawChannel {
        override val channelName = "NFC-F"
        override fun transceive(data: ByteArray): ByteArray = tech.transceive(data)
    }

    class NfcVChannel(private val tech: NfcV) : RawChannel {
        override val channelName = "NFC-V"
        override fun transceive(data: ByteArray): ByteArray = tech.transceive(data)
    }

    class IsoDepChannel(private val tech: IsoDep) : RawChannel {
        override val channelName = "ISO-DEP"
        override fun transceive(data: ByteArray): ByteArray = tech.transceive(data)
    }
}

/**
 * 标签会话：把一个 [Tag] 打开成可读写的会话对象。
 *
 * 生命周期：connect() -> 多次读写 -> close()。
 * Android 的 NFC 技术对象非线程安全，且同一时刻只能被一个连接持有，
 * 因此所有操作都必须串行，并且用完立刻 close()。
 */
class TagSession private constructor(
    val tag: Tag,
    val uid: ByteArray,
    val techs: List<NfcTech>,
    private val resources: MutableList<AutoCloseable>,
) : AutoCloseable {

    private var connected = false

    // ---- 各技术的懒加载句柄 ----
    val ndef: Ndef? by lazy {
        Ndef.get(tag)?.also { resources += it }
    }
    val ndefFormatable: NdefFormatable? by lazy {
        NdefFormatable.get(tag)?.also { resources += it }
    }
    val mifareClassic: MifareClassic? by lazy {
        MifareClassic.get(tag)?.also { resources += it }
    }
    val mifareUltralight: MifareUltralight? by lazy {
        MifareUltralight.get(tag)?.also { resources += it }
    }
    val isoDep: IsoDep? by lazy {
        IsoDep.get(tag)?.also { resources += it }
    }

    /** 打开底层原始通道（按优先级选取第一个可用技术） */
    fun openRawChannel(preferred: NfcTech? = null): RawChannel? {
        if (preferred != null) {
            when (preferred) {
                NfcTech.NFC_A -> NfcA.get(tag)?.let { return RawChannel.NfcAChannel(it) }
                NfcTech.NFC_B -> NfcB.get(tag)?.let { return RawChannel.NfcBChannel(it) }
                NfcTech.NFC_F -> NfcF.get(tag)?.let { return RawChannel.NfcFChannel(it) }
                NfcTech.NFC_V -> NfcV.get(tag)?.let { return RawChannel.NfcVChannel(it) }
                NfcTech.ISO_DEP -> IsoDep.get(tag)?.let { return RawChannel.IsoDepChannel(it) }
                else -> Unit
            }
        }
        NfcA.get(tag)?.let { return RawChannel.NfcAChannel(it) }
        IsoDep.get(tag)?.let { return RawChannel.IsoDepChannel(it) }
        NfcV.get(tag)?.let { return RawChannel.NfcVChannel(it) }
        NfcF.get(tag)?.let { return RawChannel.NfcFChannel(it) }
        NfcB.get(tag)?.let { return RawChannel.NfcBChannel(it) }
        return null
    }

    /**
     * 建立底层连接。
     *
     * ⚠️ **一个 Tag 同时只能连接一个 TagTechnology**：连续 connect 第二个技术
     * 会抛 IllegalStateException（早期实现连了 4 个，只有第一个成功，
     * 且把异常留给后面莫名其妙地出现）。这里按优先级只连第一个可用的，
     * 需要换技术时先 [close] 再连。
     */
    fun connect() {
        if (connected) return
        val tech = ndef ?: mifareClassic ?: mifareUltralight ?: isoDep ?: return
        if (!tech.isConnected) tech.connect()
        connected = true
    }

    override fun close() {
        resources.forEach { runCatching { it.close() } }
        resources.clear()
        connected = false
    }

    companion object {
        fun open(tag: Tag): TagSession {
            val uid = tag.id ?: ByteArray(0)
            val techs = NfcTech.fromTechList(tag.techList.toList().toTypedArray())
            return TagSession(tag, uid, techs, mutableListOf())
        }
    }
}
