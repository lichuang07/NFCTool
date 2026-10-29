package com.nfctool.app.core.service

import android.content.Context
import android.nfc.Tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 后台扫描事件总线。
 *
 * 用单例把 Service / Receiver 与 ViewModel 解耦：
 * 后台服务扫描到标签 → 发射到总线 → ViewModel 订阅并更新 UI。
 */
object BackgroundScanBus {

    private val _events = MutableSharedFlow<BackgroundTagEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BackgroundTagEvent> = _events.asSharedFlow()

    /** 发射一个后台扫描到的标签 */
    fun emit(context: Context, tag: Tag, id: ByteArray?) {
        _events.tryEmit(BackgroundTagEvent(tag = tag, id = id))
    }
}

/**
 * 后台扫描事件。
 */
data class BackgroundTagEvent(
    val tag: Tag,
    val id: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackgroundTagEvent) return false
        return tag.id.contentEquals(other.tag.id)
    }

    override fun hashCode(): Int = tag.id.contentHashCode()
}
