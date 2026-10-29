package com.nfctool.app.ui

import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings
import android.app.Application
import android.nfc.Tag
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nfctool.app.core.export.TagExporter
import com.nfctool.app.core.hce.HceManager
import com.nfctool.app.core.hce.HceStatus
import com.nfctool.app.core.nfc.ApduEngine
import com.nfctool.app.core.nfc.ApduExchange
import com.nfctool.app.core.nfc.CardType
import com.nfctool.app.core.nfc.DesfireCardInfo
import com.nfctool.app.core.nfc.DesfireOps
import com.nfctool.app.core.nfc.Hex
import com.nfctool.app.core.nfc.Iso15693Ops
import com.nfctool.app.core.nfc.MifareBackup
import com.nfctool.app.core.nfc.MifareClassicOps
import com.nfctool.app.core.nfc.MifareUltralightOps
import com.nfctool.app.core.nfc.NdefManager
import com.nfctool.app.core.nfc.NdefRecordInfo
import com.nfctool.app.core.nfc.NfcSessionManager
import com.nfctool.app.core.nfc.NfcStatus
import com.nfctool.app.core.nfc.NfcTech
import com.nfctool.app.core.nfc.RawChannel
import com.nfctool.app.core.nfc.TagOverview
import com.nfctool.app.core.nfc.TagReader
import com.nfctool.app.core.nfc.TagCloner
import com.nfctool.app.core.nfc.TagSession
import com.nfctool.app.core.nfc.ndef.ImageCompressor
import com.nfctool.app.core.nfc.ndef.NdefMessageCodec
import com.nfctool.app.core.nfc.ndef.QrCodeEncoder
import com.nfctool.app.core.nfc.ndef.SharedContentParser
import com.nfctool.app.core.nfc.ndef.WifiWscCodec
import com.nfctool.app.core.service.BackgroundScanBus
import com.nfctool.app.core.service.BackgroundScanService
import com.nfctool.app.core.i18n.LocalePref
import com.nfctool.app.data.AppSettings
import com.nfctool.app.data.MifareKeyStore
import com.nfctool.app.data.WriteTemplate
import com.nfctool.app.data.WriteTemplateStore
import com.nfctool.app.data.ScanHistoryStore
import com.nfctool.app.data.ScanRecord
import com.nfctool.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 复制标签的执行阶段。
 */
enum class ClonePhase {
    /** 空闲：没有进行中的复制任务 */
    IDLE,

    /** 等待靠近源标签 */
    READING_SOURCE,

    /** 源标签已读取，等待用户发起写入 */
    SOURCE_READY,

    /** 等待靠近目标标签并执行写入 */
    WRITING_TARGET,
}

/**
 * 复制标签的执行状态。
 *
 * [source] 是源卡的 NDEF 消息快照，写入目标卡时原样搬过去。
 */
data class CloneState(
    val phase: ClonePhase = ClonePhase.IDLE,
    val source: TagCloner.CloneSource? = null,
    /** 当前源标签已成功复制的张数（换源标签时归零） */
    val copiesDone: Int = 0,
    /** 最近一次操作的结果提示（成功 / 失败原因） */
    val message: String? = null,
    /** 最近一次操作是否成功；null 表示还没有结果 */
    val lastSucceeded: Boolean? = null,
)

/**
 * 主界面状态。
 */
data class NfcUiState(
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val overview: TagOverview? = null,
    val rawChannelName: String? = null,
    val error: String? = null,
    val toast: String? = null,
    val lastExchange: ApduExchange? = null,
    val mifareDump: Map<Int, ByteArray> = emptyMap(),
    val ultralightPages: List<ByteArray> = emptyList(),
    val backgroundScanActive: Boolean = false,

    /**
     * 是否正处于「等待用户贴近标签以执行写入」状态。
     *
     * 写入不再依赖先读一次标签：点写入后进入此状态并弹出靠近提示，
     * reader mode 检测到标签的瞬间就地执行写入。
     */
    val awaitingTag: Boolean = false,

    /** 等待执行的写入操作名称，用于提示弹窗的文案 */
    val awaitingWriteLabel: String? = null,
)

/**
 * 控制台日志条目。
 */
data class ConsoleEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val direction: Direction,
    val payload: ByteArray,
    val note: String? = null,
) {
    enum class Direction { TX, RX, INFO, ERROR }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConsoleEntry) return false
        return id == other.id && direction == other.direction &&
            payload.contentEquals(other.payload) && note == other.note
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (note?.hashCode() ?: 0)
        return result
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val nfcManager = NfcSessionManager(app)
    private val historyStore = ScanHistoryStore(app)
    private val settingsStore = SettingsStore(app)
    private val keyStore = MifareKeyStore(app)
    private val templateStore = WriteTemplateStore(app)
    private val hceManager = HceManager(app)

    val nfcStatus: StateFlow<NfcStatus> = nfcManager.status

    /** HCE 主机卡模拟状态 */
    private val _hceStatus = MutableStateFlow(HceStatus())
    val hceStatus: StateFlow<HceStatus> = _hceStatus.asStateFlow()

    /** 开启后台读卡 */
    fun startBackgroundScan() {
        BackgroundScanService.start(getApplication())
        _uiState.value = _uiState.value.copy(backgroundScanActive = true)
    }

    /** 关闭后台读卡 */
    fun stopBackgroundScan() {
        BackgroundScanService.stop(getApplication())
        _uiState.value = _uiState.value.copy(backgroundScanActive = false)
    }

    /** 刷新 HCE 状态 */
    fun refreshHceStatus() {
        _hceStatus.value = hceManager.readStatus()
    }

    /** 打开系统卡模拟设置 */
    fun openHceSettings() = hceManager.openCardEmulationSettings()

    /** 切换前台优先卡模拟 */
    fun setHceForegroundPreferred(activity: android.app.Activity, enable: Boolean) {
        hceManager.setForegroundPreferred(activity, enable)
        refreshHceStatus()
    }

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * 当前语言选择（跟随系统 / 中文 / English）。
     * 唯一事实源是 LocalePref 的 SharedPreferences（attachBaseContext 要同步读）。
     */
    private val _language = MutableStateFlow(LocalePref.language(getApplication()))
    val language: StateFlow<String> = _language.asStateFlow()

    /**
     * 切换语言：写偏好 → 刷新 core 层取词 Context → 重建 Activity 让 UI 生效。
     * core 层的错误信息经 AppStrings 取词，不依赖 Activity 重建。
     */
    fun setLanguage(tag: String, activity: android.app.Activity? = null) {
        if (tag == _language.value) return
        LocalePref.setLanguage(getApplication(), tag)
        AppStrings.update(getApplication())
        _language.value = tag
        activity?.recreate()
    }

    val history: StateFlow<List<ScanRecord>> = historyStore.history
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow(NfcUiState())
    val uiState: StateFlow<NfcUiState> = _uiState.asStateFlow()

    /**
     * 复制标签的执行状态。
     */
    private val _clone = MutableStateFlow(CloneState())
    val clone: StateFlow<CloneState> = _clone.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val console: StateFlow<List<ConsoleEntry>> = _console.asStateFlow()

    /** 当前持有的标签会话（用于后续读写操作） */
    private var session: TagSession? = null

    /**
     * 待执行的写入任务。
     *
     * 写入流程与读取彻底解耦：用户点「写入」后只把任务记在这里，
     * 同时弹出「请靠近标签」提示；等 reader mode 检测到标签时再取出来执行。
     * 这样既符合主流 NFC 工具的使用习惯，也不会出现
     * 「点了写入却提示请先读取一张标签」这种断点。
     */
    private data class PendingWrite(
        val label: String,
        val block: suspend (TagSession) -> Result<Any?>,
        val onDone: (Boolean, String) -> Unit,
    )

    private var pendingWrite: PendingWrite? = null

    /**
     * 待预填的分享内容。
     *
     * 从别的应用（地图、浏览器等）分享过来时，把解析结果暂存于此，
     * 写入页据此自动填好表单，用户只需贴标签写入。
     */
    private val _pendingPrefill = MutableStateFlow<SharedContentParser.Parsed?>(null)
    val pendingPrefill: StateFlow<SharedContentParser.Parsed?> = _pendingPrefill.asStateFlow()

    /**
     * 预填内容的递增序号。
     *
     * 单独用序号做触发键，是因为连续两次分享**内容完全相同**时，
     * `StateFlow` 会因 `equals` 相等而不重新发射，表单的 `LaunchedEffect`
     * 也就不会重新执行 —— 表单会保留用户上次改过的值，而不是重新填入。
     */
    private val _prefillSeq = MutableStateFlow(0)
    val prefillSeq: StateFlow<Int> = _prefillSeq.asStateFlow()

    /**
     * 接收来自其他应用的分享内容。
     *
     * @param raw 分享的文本内容
     * @param subject 网页标题（来自 Intent 的 `EXTRA_SUBJECT`），可空
     * @return 解析结果；内容为空或无法解析时返回 null
     */
    fun handleSharedText(raw: String?, subject: String? = null): SharedContentParser.Parsed? {
        val parsed = SharedContentParser.parse(raw, subject) ?: return null
        // 识别到格式但内容非法（如坐标越界）→ 明确报错，
        // 不要把无效内容推给表单，否则用户会写出一个"碰一下没反应"的标签。
        if (!parsed.isValid) {
            _uiState.value = _uiState.value.copy(
                toast = parsed.error ?: AppStrings.get(R.string.err_share_parse),
            )
            return parsed
        }
        _pendingPrefill.value = parsed
        // 序号自增，作为表单侧「需要重新预填」的唯一可靠信号
        _prefillSeq.value = _prefillSeq.value + 1
        return parsed
    }

    /** 清除待预填内容（写入成功或用户手动改完表单后调用） */
    fun clearPrefill() {
        _pendingPrefill.value = null
    }

    /**
     * 接收分享过来的图片。
     *
     * @param bytes 图片字节；为 null 表示读取失败
     * @param mime  图片 MIME 类型
     */
    fun handleSharedImage(bytes: ByteArray?, mime: String?) {
        val parsed = SharedContentParser.parseImage(bytes ?: ByteArray(0), mime)
        if (!parsed.isValid) {
            _uiState.value = _uiState.value.copy(
                toast = parsed.error ?: AppStrings.get(R.string.err_share_image),
            )
            return
        }
        _pendingPrefill.value = parsed
        _prefillSeq.value = _prefillSeq.value + 1
    }

    /**
     * 分享内容无法识别时提示用户。
     */
    fun showShareError(message: String) {
        _uiState.value = _uiState.value.copy(toast = message)
    }

    /** 防抖状态：上一次处理的标签 UID 与时间戳 */
    private var lastTagUid: String? = null
    private var lastTagTime: Long = 0L

    init {
        // Reader Mode 由 Activity 生命周期统一管理，标签统一经 handleIntentTag 入口，
        // 此处不再重复注册回调，避免同一张卡被处理两次。

        // 订阅后台扫描服务发现的事件
        viewModelScope.launch {
            BackgroundScanBus.events.collect { event ->
                onTagDiscovered(event.tag)
            }
        }
    }

    fun refreshStatus() = nfcManager.refreshStatus()

    fun openNfcSettings() = nfcManager.openNfcSettings()

    fun openDefaultAppsSettings() = nfcManager.openDefaultAppsSettings()

    /** 进入应用时开启扫描态 */
    fun startScanning() {
        _uiState.value = _uiState.value.copy(scanning = true)
    }

    /** 停止扫描态（离开读取页或进入写入页时） */
    fun stopScanning() {
        _uiState.value = _uiState.value.copy(scanning = false)
    }

    /** 读取 Intent 中携带的标签（冷启动场景） */
    fun handleIntentTag(tag: Tag?) {
        if (tag == null) return
        // 防抖：标签持续在场时 reader mode 可能重复回调，
        // 短时间内同一 UID 只处理一次。
        val uid = tag.id?.joinToString(":") { "%02X".format(it) } ?: return
        val now = System.currentTimeMillis()
        if (uid == lastTagUid && now - lastTagTime < DEBOUNCE_MS) return
        lastTagUid = uid
        lastTagTime = now
        onTagDiscovered(tag)
    }

    private fun onTagDiscovered(tag: Tag) {
        // 有待执行的写入任务时，标签一到场就直接写，不走读取流程 ——
        // 这就是「写入不依赖先读一次」的实现：写入自己等标签，而不是让用户先读。
        val pending = pendingWrite
        if (pending != null) {
            executePendingWrite(tag, pending)
            return
        }
        // 正在读写另一张卡时忽略新回调，避免并发操作同一个会话
        if (_uiState.value.busy) return

        // 复制流程的「读取源卡」阶段：标签到场抓快照，不走普通读取
        if (_clone.value.phase == ClonePhase.READING_SOURCE) {
            captureCloneSource(tag)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(scanning = false, busy = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    session?.close()
                    val s = TagSession.open(tag)
                    session = s
                    val overview = TagReader.readOverview(s)
                    overview to s
                }
            }
            result.onSuccess { (overview, s) ->
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    overview = overview,
                    rawChannelName = s.openRawChannel()?.channelName,
                )
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.INFO,
                        payload = ByteArray(0),
                        note = AppStrings.get(R.string.msg_tag_detected, Hex.formatUid(overview.uid), AppStrings.get(overview.cardType.displayNameRes)),
                    )
                )
                saveHistory(overview)
                if (settings.value.vibrateOnScan) vibrate()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    error = e.message ?: AppStrings.readFailed(),
                )
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.ERROR,
                        payload = ByteArray(0),
                        note = e.message ?: AppStrings.readFailed(),
                    )
                )
            }
        }
    }

    /**
     * 执行待定的写入任务。
     *
     * 标签一到场就地开会话、执行写入、汇报结果，全程不经过读取流程。
     * 写入中的各种校验（容量、只读、格式不支持）都由各写操作自己给出明确错误。
     */
    private fun executePendingWrite(tag: Tag, pending: PendingWrite) {
        // 先摘除任务：写入过程中同一张卡可能再次触发回调，
        // 不摘的话会被当成又一次「等待中的写入」重复执行。
        pendingWrite = null
        viewModelScope.launch {
            // 弹窗先留着，文案切成「正在写入」，避免写入成功的瞬间界面闪烁
            _uiState.value = _uiState.value.copy(
                awaitingTag = true,
                awaitingWriteLabel = pending.label,
                busy = true,
                error = null,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    session?.close()
                    val s = TagSession.open(tag)
                    session = s
                    // 内部写操作一律返回 Result，失败时是 failure 而不是抛异常，
                    // 这里用 getOrThrow 转成异常，否则失败会被误判成成功。
                    pending.block(s).getOrThrow()
                }
            }
            // 重置防抖：允许用户立刻换一张（或同一张）标签重试
            lastTagUid = null
            lastTagTime = 0L
            result.onSuccess { value ->
                _uiState.value = _uiState.value.copy(awaitingTag = false, busy = false)
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.INFO,
                        payload = ByteArray(0),
                        note = AppStrings.get(R.string.msg_write_ok, pending.label),
                    )
                )
                // 写入完成，预填内容不再需要，避免下次进表单被旧值覆盖
                clearPrefill()
                vibrate()
                pending.onDone(true, value as? String ?: AppStrings.get(R.string.msg_write_ok, pending.label))
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(awaitingTag = false, busy = false)
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.ERROR,
                        payload = ByteArray(0),
                        note = AppStrings.get(R.string.msg_write_failed_reason, pending.label, e.message.orEmpty()),
                    )
                )
                pending.onDone(false, e.message ?: AppStrings.get(R.string.msg_write_failed, pending.label))
            }
        }
    }

    private suspend fun saveHistory(overview: TagOverview) {
        val summary = overview.ndefRecords?.firstOrNull()?.let { rec ->
            rec.text ?: rec.uri ?: rec.mimeType
        }
        historyStore.add(
            ScanRecord(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                uidHex = Hex.formatUid(overview.uid),
                cardType = overview.cardType.name,
                techSummary = overview.techs.joinToString(" · ") { it.name },
                ndefSummary = summary,
            )
        )
    }

    private fun vibrate() {
        runCatching {
            val vibrator = getApplication<Application>()
                .getSystemService(Application.VIBRATOR_SERVICE) as? android.os.Vibrator
                ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用 VibrationEffect
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        40,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                )
            } else {
                // 低版本走 deprecated 的时长重载
                @Suppress("DEPRECATION")
                vibrator.vibrate(40L)
            }
        }
    }

    // ---------------- 复制标签 ----------------

    /**
     * 开始一次复制：进入「等待靠近源标签」。
     *
     * 与写入共用同一套扫描入口，区别在于这一步不写任何东西，
     * 只把标签内容抓成快照留在内存里。
     */
    fun startCloneRead() {
        _clone.value = CloneState(phase = ClonePhase.READING_SOURCE)
        // 重置防抖：允许直接沿用刚才扫过的那张卡，
        // 否则 1.5s 内的重复贴卡会被吞掉，表现为「点了没反应」
        lastTagUid = null
        lastTagTime = 0L
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * 把当前已读取的标签直接当作复制源（读取页入口）。
     *
     * 省掉「再贴一次源卡」的步骤 —— 用户刚扫过的那张就是源。
     * 这里从已解析的记录反解出原始字节：记录里的 `raw` 是**整条记录**的字节，
     * 所以先用 [NdefMessageCodec.decodeMessage] 拆出 payload，再重新编码，
     * 顺带把消息规范化。
     *
     * @return true 表示快照已就绪、可以进复制页；false 表示当前没有可用源，已给出提示
     */
    fun useOverviewAsCloneSource(): Boolean {
        val overview = _uiState.value.overview
        if (overview == null || overview.ndefRecords.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                toast = AppStrings.get(R.string.err_clone_need_source),
            )
            return false
        }
        val records = overview.ndefRecords!!
        val bytes = runCatching {
            NdefMessageCodec.decodeMessage(records.fold(ByteArray(0)) { acc, r -> acc + r.raw })
                .getOrThrow()
                .let { NdefMessageCodec.encodeMessage(it) }
        }.getOrNull()
        if (bytes == null) {
            _uiState.value = _uiState.value.copy(toast = AppStrings.readFailed())
            return false
        }
        _clone.value = CloneState(
            phase = ClonePhase.SOURCE_READY,
            source = TagCloner.CloneSource(
                uidHex = Hex.formatUid(overview.uid),
                cardTypeKey = overview.cardType.name,
                techKeys = overview.techs.map { it.name },
                recordCount = records.size,
                byteCount = bytes.size,
                capacity = overview.ndefCapacity,
                writable = overview.ndefWritable || overview.ndefFormatable,
                messageBytes = bytes,
            ),
        )
        return true
    }

    /**
     * 抓取源卡快照：标签到场时由 [onTagDiscovered] 调用。
     *
     * 失败时把阶段退回 [ClonePhase.IDLE]，避免界面一直停在「正在读取」。
     */
    private fun captureCloneSource(tag: Tag) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(scanning = false, busy = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    session?.close()
                    val s = TagSession.open(tag)
                    session = s
                    s.connect()
                    TagCloner.capture(s).getOrThrow()
                }
            }
            result.onSuccess { source ->
                _uiState.value = _uiState.value.copy(busy = false)
                _clone.value = CloneState(phase = ClonePhase.SOURCE_READY, source = source)
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.INFO,
                        payload = ByteArray(0),
                        note = AppStrings.get(
                            R.string.msg_clone_captured,
                            source.recordCount,
                            source.byteCount,
                        ),
                    )
                )
                vibrate()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    error = e.message ?: AppStrings.readFailed(),
                )
                _clone.value = CloneState(phase = ClonePhase.IDLE)
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.ERROR,
                        payload = ByteArray(0),
                        note = e.message ?: AppStrings.readFailed(),
                    )
                )
            }
        }
    }

    /**
     * 发起写入：挂起一个「把快照写进下一张贴上来的标签」的任务。
     *
     * 写入成功后**不清空源快照**，而是回到 [ClonePhase.SOURCE_READY]
     * 并累加张数 —— 这样可以拿着同一张源卡连续复制多张。
     */
    fun beginCloneWrite(lock: Boolean = false) {
        val source = _clone.value.source ?: return
        _clone.value = _clone.value.copy(phase = ClonePhase.WRITING_TARGET, message = null)
        launchWrite(
            label = AppStrings.get(R.string.op_clone_write),
            block = { s -> TagCloner.write(s, source, lock = lock).map { null } },
            onDone = { ok, msg ->
                _clone.value = _clone.value.copy(
                    phase = ClonePhase.SOURCE_READY,
                    copiesDone = if (ok) _clone.value.copiesDone + 1 else _clone.value.copiesDone,
                    message = msg,
                    lastSucceeded = ok,
                )
            },
        )
    }

    /** 结束复制：清掉源快照与计数 */
    fun cancelClone() {
        pendingWrite = null
        _clone.value = CloneState()
        _uiState.value = _uiState.value.copy(awaitingTag = false)
    }

    // ---------------- MIFARE 整卡备份 / 恢复 ----------------

    /** 密钥本里已保存的密钥（大写 12 位 hex） */
    val mifareKeys: StateFlow<List<String>> = keyStore.keys.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    /**
     * 组装本次要尝试的密钥列表，顺序即优先级：
     *
     * 1. 用户这次临时输入的（最可能是当前这张卡的）
     * 2. 密钥本里保存的
     * 3. 默认密钥 FFFFFFFFFFFF（兜底）
     *
     * 依次尝试能显著提高备份成功率 —— 很多门禁卡 / 电梯卡的密钥都被改过，
     * 只试默认密钥必然抓不全。
     */
    fun buildMifareKeyCandidates(input: String): Result<List<ByteArray>> = runCatching {
        val result = mutableListOf<ByteArray>()
        val trimmed = input.trim()
        if (trimmed.isNotEmpty()) {
            // 输入框的格式错误要报给用户，不能静默跳过
            val bytes = Hex.parse(trimmed)
            require(bytes.size == 6) { AppStrings.get(R.string.err_backup_key_invalid) }
            result += bytes
        }
        // 密钥本里的数据来自本地存储，坏数据跳过而不是让整个流程失败
        mifareKeys.value.forEach { hex ->
            runCatching { Hex.parse(hex) }
                .getOrNull()
                ?.takeIf { it.size == 6 }
                ?.let { result += it }
        }
        result += MifareClassicOps.DEFAULT_KEY
        // 按内容去重，避免重复认证同一个密钥
        result.distinctBy { Hex.toHexNoSep(it) }
    }

    /**
     * 把输入框里的密钥存进密钥本。
     *
     * @return 规范化后的密钥；格式不合法或已达上限时返回 failure
     */
    fun saveMifareKey(input: String): Result<String> =
        MifareBackup.normalizeKey(input).onSuccess { normalized ->
            if (mifareKeys.value.size >= MifareKeyStore.MAX_KEYS &&
                normalized !in mifareKeys.value
            ) {
                return Result.failure(
                    IllegalStateException(
                        AppStrings.get(R.string.err_keybook_full, MifareKeyStore.MAX_KEYS),
                    ),
                )
            }
            viewModelScope.launch { keyStore.add(normalized) }
        }

    /** 从密钥本里删除一条 */
    fun removeMifareKey(hex: String) {
        viewModelScope.launch { keyStore.remove(hex) }
    }

    /**
     * 备份当前 MIFARE Classic 卡为 JSON 文件并分享出去。
     *
     * 走的是当前已读取的 [session]（而不是等新标签），因为备份的对象
     * 就是用户手上刚扫过的那张卡。
     */
    fun backupMifare(keys: List<ByteArray>, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val s = session
            if (s == null) {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, AppStrings.needReadFirst())
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { MifareBackup.capture(MifareClassicOps(s), keys).getOrThrow() }
            }
            result.onSuccess { backup ->
                _uiState.value = _uiState.value.copy(busy = false)
                runCatching {
                    TagExporter.share(
                        context = getApplication(),
                        content = MifareBackup.toJson(backup),
                        fileName = "mifare-${backup.cardType}-${backup.uid}.json",
                        mimeType = "application/json",
                    )
                }.onSuccess {
                    // 有扇区认证失败时如实说明"部分备份"，
                    // 否则用户会以为拿到了一份完整的卡
                    onDone(
                        true,
                        if (backup.unreadableSectors > 0) {
                            AppStrings.get(
                                R.string.mifare_backup_done_partial,
                                backup.sectorCount,
                                backup.unreadableSectors,
                            )
                        } else {
                            AppStrings.get(
                                R.string.mifare_backup_done,
                                backup.sectorCount,
                                backup.restorableBlocks,
                            )
                        },
                    )
                }.onFailure {
                    onDone(false, it.message ?: AppStrings.get(R.string.err_export_failed))
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, it.message ?: AppStrings.get(R.string.err_mifare_not_classic))
            }
        }
    }

    /**
     * 把备份写回贴近的 MIFARE Classic 卡。
     *
     * 与其他写入一样走 `launchWrite`：点了恢复之后弹窗提示靠近标签，
     * 标签一到场就地执行 —— 目标卡是用户"下一张贴上来"的那张，不是当前这张。
     */
    fun restoreMifare(
        backup: MifareBackup.Backup,
        keys: List<ByteArray>,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_restore_backup),
        block = { s ->
            runCatching {
                val report = MifareBackup.restore(MifareClassicOps(s), backup, keys).getOrThrow()
                val summary = AppStrings.get(
                    R.string.mifare_restore_done,
                    report.written,
                    report.skipped,
                    report.failed,
                )
                // 有块写失败就整体判失败，并把前几条原因带出去 ——
                // 只报"恢复完成"会让用户以为整张卡都写好了
                if (report.failed > 0) {
                    error(summary + "\n" + report.details.take(3).joinToString("\n"))
                }
                summary
            }
        },
        onDone = onDone,
    )

    /**
     * 备份当前 Ultralight / NTAG 卡为 JSON 并分享。
     *
     * 与 Classic 的区别：**不需要密钥**（Ultralight 没有扇区认证），
     * 备份范围只有用户数据页。
     */
    fun backupUltralight(onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val s = session
            if (s == null) {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, AppStrings.needReadFirst())
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { MifareBackup.captureUltralight(MifareUltralightOps(s)).getOrThrow() }
            }
            result.onSuccess { backup ->
                _uiState.value = _uiState.value.copy(busy = false)
                runCatching {
                    TagExporter.share(
                        context = getApplication(),
                        content = MifareBackup.toJson(backup),
                        fileName = "ntag-${backup.cardType}-${backup.uid}.json",
                        mimeType = "application/json",
                    )
                }.onSuccess {
                    onDone(
                        true,
                        AppStrings.get(R.string.ul_backup_done, backup.cardType, backup.restorablePages),
                    )
                }.onFailure {
                    onDone(false, it.message ?: AppStrings.get(R.string.err_export_failed))
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, it.message ?: AppStrings.get(R.string.err_tag_no_mifare_ultralight))
            }
        }
    }

    /** 把备份写回贴近的 Ultralight / NTAG 卡 */
    fun restoreUltralight(
        backup: MifareBackup.UltralightBackup,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_restore_backup),
        block = { s ->
            runCatching {
                val report = MifareBackup.restoreUltralight(MifareUltralightOps(s), backup).getOrThrow()
                val summary = AppStrings.get(
                    R.string.ul_restore_done,
                    report.written,
                    report.skipped,
                    report.failed,
                )
                if (report.failed > 0) {
                    error(summary + "\n" + report.details.take(3).joinToString("\n"))
                }
                summary
            }
        },
        onDone = onDone,
    )

    // ---------------- 写入模板（收藏夹） ----------------

    /** 已保存的写入模板 */
    val templates: StateFlow<List<WriteTemplate>> = templateStore.templates.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    /** 名称留空时自动取内容摘要 —— 不该逼用户必须为模板起名字 */
    private fun autoName(name: String, fallback: String?): String =
        name.trim().ifBlank { fallback?.take(24)?.ifBlank { AppStrings.get(R.string.template_unnamed) } ?: AppStrings.get(R.string.template_unnamed) }

    private fun storeTemplate(name: String, parsed: SharedContentParser.Parsed): Result<Unit> =
        runCatching {
            require(parsed.isValid) {
                parsed.error ?: AppStrings.get(R.string.err_template_nothing)
            }
            // 模板只覆盖文本类内容：图片要存字节，模型里没有
            require(parsed.kind != SharedContentParser.Kind.IMAGE) {
                AppStrings.get(R.string.err_template_image)
            }
            viewModelScope.launch {
                templateStore.add(
                    WriteTemplate(
                        id = UUID.randomUUID().toString(),
                        name = autoName(name, parsed.title ?: parsed.text),
                        kind = parsed.kind.name,
                        text = parsed.text,
                        title = parsed.title,
                        latitude = parsed.latitude,
                        longitude = parsed.longitude,
                        label = parsed.label,
                        coordinateSystem = parsed.coordinateSystem,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

    /** 把当前待预填的内容（从别的应用分享进来的）存成模板 */
    fun savePrefillAsTemplate(name: String): Result<Unit> = runCatching {
        val parsed = _pendingPrefill.value
            ?: error(AppStrings.get(R.string.err_template_nothing))
        storeTemplate(name, parsed).getOrThrow()
    }

    /**
     * 把当前读到的标签首条记录存成模板（读卡 → 存 → 以后写到别的卡）。
     *
     * 直接交给 [SharedContentParser.parse] 判断类型：
     * 它能自动识别 vCard / geo / 链接 / 纯文本，不用在这里重复分支。
     */
    fun saveOverviewAsTemplate(name: String): Result<Unit> = runCatching {
        val rec = _uiState.value.overview?.ndefRecords?.firstOrNull()
            ?: error(AppStrings.get(R.string.err_template_nothing))
        val content = rec.text ?: rec.uri
            ?: error(AppStrings.get(R.string.err_template_unsupported))
        val parsed = SharedContentParser.parse(content)
            ?: error(AppStrings.get(R.string.err_template_nothing))
        storeTemplate(name, parsed).getOrThrow()
    }

    /**
     * 应用模板：把内容放回 `pendingPrefill` 并自增序号。
     *
     * **不需要自己导航** —— `NfctoolAppRoot` 里有个 `LaunchedEffect(prefillSeq)`，
     * 序号一变就会自动跳到对应的写入详情页。模板复用的是分享预填那条现成通道。
     */
    fun applyTemplate(template: WriteTemplate) {
        val kind = runCatching { SharedContentParser.Kind.valueOf(template.kind) }.getOrNull()
            ?: return
        val parsed = when (kind) {
            // vCard 存的是原文，重新解析还原
            SharedContentParser.Kind.VCARD ->
                SharedContentParser.parse(template.text) ?: return
            SharedContentParser.Kind.GEO -> SharedContentParser.Parsed(
                kind = SharedContentParser.Kind.GEO,
                text = template.text,
                latitude = template.latitude,
                longitude = template.longitude,
                label = template.label,
                coordinateSystem = template.coordinateSystem,
            )
            SharedContentParser.Kind.URI -> SharedContentParser.Parsed(
                kind = SharedContentParser.Kind.URI,
                text = template.text,
                uri = template.text,
                title = template.title,
            )
            SharedContentParser.Kind.TEXT -> SharedContentParser.Parsed(
                kind = SharedContentParser.Kind.TEXT,
                text = template.text,
            )
            SharedContentParser.Kind.IMAGE -> return
        }
        if (!parsed.isValid) return
        _pendingPrefill.value = parsed
        _prefillSeq.value = _prefillSeq.value + 1
    }

    fun removeTemplate(id: String) {
        viewModelScope.launch { templateStore.remove(id) }
    }

    // ---------------- 写入操作 ----------------

    /** 写入 NDEF 文本 */
    fun writeText(text: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_write_text),
        block = { s -> NdefManager(s).writeText(text) },
        onDone = onDone,
    )

    /** 写入 NDEF 链接 */
    fun writeUri(uri: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_write_uri),
        block = { s -> NdefManager(s).writeUri(uri) },
        onDone = onDone,
    )

    /** 写入 MIME 内容 */
    fun writeMime(mimeType: String, data: ByteArray, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_write_mime),
        block = { s -> NdefManager(s).writeMime(mimeType, data) },
        onDone = onDone,
    )

    /** 写入 Wi-Fi 配网记录 */
    fun writeWifi(config: WifiWscCodec.WifiConfig, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_write_wifi),
        block = { s -> NdefManager(s).writeWifi(config) },
        onDone = onDone,
    )

    /** 写入 vCard 电子名片 */
    fun writeVCard(
        fullName: String,
        phone: String,
        email: String,
        organization: String,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_vcard),
        block = { s -> NdefManager(s).writeVCard(fullName, phone, email, organization) },
        onDone = onDone,
    )

    /** 写入完整字段的 vCard（带职位 / 网址 / 地址） */
    fun writeVCardFull(
        fullName: String,
        phone: String,
        email: String,
        organization: String,
        title: String,
        url: String,
        address: String,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_vcard),
        block = {
            s -> NdefManager(s).writeVCardFull(
                fullName = fullName,
                phone = phone,
                email = email,
                organization = organization,
                title = title,
                url = url,
                address = address,
            )
        },
        onDone = onDone,
    )

    /** 写入 MECARD 紧凑名片 */
    fun writeMeCard(
        name: String,
        phone: String,
        email: String,
        url: String,
        address: String,
        note: String,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_mecard),
        block = {
            s -> NdefManager(s).writeMeCard(
                name = name,
                phone = phone,
                email = email,
                url = url,
                address = address,
                note = note,
            )
        },
        onDone = onDone,
    )

    /** 写入 vCalendar 日历事件 */
    fun writeVCalendar(
        summary: String,
        start: String,
        end: String,
        location: String,
        description: String,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_vcalendar),
        block = {
            s -> NdefManager(s).writeVCalendar(
                summary = summary,
                start = start,
                end = end,
                location = location,
                description = description,
            )
        },
        onDone = onDone,
    )

    /** 写入 Smart Poster 复合记录 */
    fun writeSmartPoster(
        title: String?,
        uris: List<String>,
        language: String,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_smartposter),
        block = { s -> NdefManager(s).writeSmartPoster(title, uris, language) },
        onDone = onDone,
    )

    /** 写入 Handover 连接移交记录 */
    fun writeHandover(
        isRequest: Boolean,
        carriers: List<Pair<Int, ByteArray>>,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_handover),
        block = { s -> NdefManager(s).writeHandover(isRequest, carriers) },
        onDone = onDone,
    )

    /** 写入 AID 记录 */
    fun writeAid(aidHex: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_write_aid),
        block = { s -> NdefManager(s).writeAid(aidHex) },
        onDone = onDone,
    )

    /** 写入外部类型记录 */
    fun writeExternal(
        domain: String,
        type: String,
        data: ByteArray,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_external),
        block = { s -> NdefManager(s).writeExternal(domain, type, data) },
        onDone = onDone,
    )

    /** 写入地理位置记录（geo: URI） */
    fun writeGeo(
        latitude: Double,
        longitude: Double,
        altitude: String,
        uncertainty: String,
        label: String,
        zoom: String,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_geo),
        block = {
            s -> NdefManager(s).writeGeo(
                latitude = latitude,
                longitude = longitude,
                altitude = altitude.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
                uncertainty = uncertainty.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
                label = label.trim().takeIf { it.isNotEmpty() },
                zoom = zoom.trim().takeIf { it.isNotEmpty() }?.toIntOrNull(),
            )
        },
        onDone = onDone,
    )

    /** 写入蓝牙 OOB 配对记录 */
    fun writeBluetoothOob(
        isLe: Boolean,
        address: String,
        deviceName: String,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_bluetooth),
        block = {
            s -> if (isLe) {
                NdefManager(s).writeBluetoothOobLe(address, deviceName)
            } else {
                NdefManager(s).writeBluetoothOobBrEdr(address, deviceName)
            }
        },
        onDone = onDone,
    )

    /** 写入 Android Application Record */
    fun writeAndroidApp(packageName: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_write_android_app),
        block = { s -> NdefManager(s).writeAndroidApp(packageName) },
        onDone = onDone,
    )

    /**
     * 写入二维码（可选择写入模式）。
     *
     * 三种模式的容量需求差别很大：
     *  - [QR_MODE_BOTH]  内容 + 二维码图：最占空间，但两种用法都成立
     *  - [QR_MODE_CONTENT] 仅内容：一条 URI 记录，几十字节，标签再小也放得下
     *  - [QR_MODE_IMAGE] 仅二维码图：内容靠扫码获得，适合内容超长时
     *
     * @param content 要编码的内容
     * @param mode    写入模式
     */
    fun writeQrCode(
        content: String,
        mode: Int = QR_MODE_BOTH,
        onDone: (Boolean, String) -> Unit,
    ) = launchWrite(
        label = AppStrings.get(R.string.op_write_qrcode),
        block = { s ->
            val manager = NdefManager(s)
            val capacity = manager.capacity()
            if (capacity <= 0) error(AppStrings.get(R.string.err_tag_no_ndef_write))

            val trimmed = content.trim()
            if (trimmed.isEmpty()) error(AppStrings.get(R.string.err_qr_content_empty))

            when (mode) {
                // 仅内容：一条记录，最省空间
                QR_MODE_CONTENT -> {
                    manager.writeContentOnly(trimmed).map {
                        AppStrings.get(R.string.msg_qr_raw_written, trimmed.toByteArray(Charsets.UTF_8).size)
                    }
                }

                // 仅二维码图
                QR_MODE_IMAGE -> {
                    val bytes = encodeQr(trimmed, capacity, reserveForContent = false)
                        ?: error(AppStrings.get(R.string.err_qr_encode_failed))
                    manager.writeQrImageOnly(bytes.first).map {
                        AppStrings.get(R.string.msg_qr_image_written, bytes.second, bytes.third)
                    }
                }

                // 内容 + 二维码图
                else -> {
                    val bytes = encodeQr(trimmed, capacity, reserveForContent = true)
                        ?: error(AppStrings.get(R.string.err_qr_capacity))
                    manager.writeQrCode(bytes.first, trimmed).map {
                        AppStrings.get(R.string.msg_qr_both_written, bytes.second, bytes.third)
                    }
                }
            }
        },
        onDone = onDone,
    )

    /**
     * 按标签容量自适应地生成二维码 PNG。
     *
     * 模块边长从大到小逐级尝试（体积随边长平方增长），
     * 取第一个塞得进预算的。
     *
     * @param reserveForContent 是否为原始内容记录预留空间
     * @return Triple(PNG 字节, PNG 体积, 实际模块边长)；放不下返回 null
     */
    private fun encodeQr(
        content: String,
        capacity: Int,
        reserveForContent: Boolean,
    ): Triple<ByteArray, Int, Int>? {
        // 原始内容记录至少占几十字节（URI/Text 记录头 + 内容）
        val overhead = if (reserveForContent) {
            val contentBytes = content.toByteArray(Charsets.UTF_8).size
            contentBytes + 32
        } else {
            16
        }
        val budget = (capacity - overhead).coerceAtLeast(32)

        for (module in MODULE_SIZE_LADDER) {
            val encoded = QrCodeEncoder.encode(content, moduleSize = module, margin = 1)
                ?: return null  // 内容太长，编码不出来
            val bytes = QrCodeEncoder.toPng(encoded) ?: continue
            if (bytes.size <= budget) {
                return Triple(bytes, bytes.size, module)
            }
        }
        return null
    }

    /**
     * 写入图片（自动压缩到标签容量以内）。
     *
     * @param bytes 原始图片字节
     * @param mime  原始 MIME
     */
    fun writeImage(bytes: ByteArray, mime: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_write_image),
        block = { s ->
            val capacity = NdefManager(s).capacity()
            if (capacity <= 0) error(AppStrings.get(R.string.err_tag_no_ndef_image))
            // 留 30 字节余量给 NDEF 记录头
            val budget = (capacity - 30).coerceAtLeast(64)
            val compressed = ImageCompressor.compress(bytes, budget)
                ?: error(AppStrings.get(R.string.err_image_decode))
            if (compressed.size > budget) {
                error(AppStrings.get(R.string.err_image_capacity, compressed.size, capacity))
            }
            NdefManager(s).writeImage(compressed.bytes, compressed.mime).map {
                AppStrings.get(
                    R.string.msg_image_written,
                    compressed.width, compressed.height, compressed.size, compressed.originalSize,
                )
            }
        },
        onDone = onDone,
    )

    /** 格式化空白标签 */
    fun formatTag(onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_format),
        block = { s -> NdefManager(s).format(readOnly = false) },
        onDone = onDone,
    )

    /** 写入 MIFARE Classic 单块 */
    fun writeMifareBlock(block: Int, data: ByteArray, key: ByteArray?, onDone: (Boolean, String) -> Unit) =
        launchWrite(
            label = AppStrings.get(R.string.op_write_block, block),
            block = { s ->
                runCatching {
                    val ops = MifareClassicOps(s)
                    if (key != null) {
                        ops.authenticate(ops.sectorOfBlock(block), key, MifareClassicOps.KEY_TYPE_A)
                            .getOrThrow()
                    } else {
                        ops.authenticateDefault(ops.sectorOfBlock(block)).getOrThrow()
                    }
                    ops.writeBlock(block, data).getOrThrow()
                }
            },
            onDone = onDone,
        )

    /** 恢复 MIFARE 出厂设置 */
    fun restoreMifare(onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_restore),
        block = { s ->
            runCatching {
                val restored = MifareClassicOps(s).restoreAllSectors().getOrThrow()
                AppStrings.get(R.string.msg_restored_sectors, restored)
            }
        },
        onDone = onDone,
    )

    /** 写入 MIFARE Ultralight 单页 */
    fun writeUltralightPage(page: Int, data: ByteArray, onDone: (Boolean, String) -> Unit) =
        launchWrite(
            label = AppStrings.get(R.string.op_write_page, page),
            block = { s ->
                runCatching {
                    MifareUltralightOps(s).writePage(page, data).getOrThrow()
                }
            },
            onDone = onDone,
        )

    /** 写入 ISO 15693 单块 */
    fun writeIso15693Block(block: Int, data: ByteArray, onDone: (Boolean, String) -> Unit) =
        launchWrite(
            label = AppStrings.get(R.string.op_write_block, block),
            block = { s -> runCatching { Iso15693Ops(s).writeBlock(block, data).getOrThrow() } },
            onDone = onDone,
        )

    /** 原始指令透传 */
    fun sendRaw(hexData: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_send_command),
        block = { s ->
            runCatching {
                val channel = s.openRawChannel() ?: error(AppStrings.get(R.string.err_no_raw_channel))
                val bytes = Hex.parseLenient(hexData)
                if (bytes.isEmpty()) error(AppStrings.get(R.string.err_command_empty))
                val response = channel.transceive(bytes)
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.TX,
                        payload = bytes,
                        note = "TX ${channel.channelName}",
                    )
                )
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.RX,
                        payload = response,
                        note = "RX ${channel.channelName}",
                    )
                )
                AppStrings.get(R.string.msg_raw_sent, bytes.size, response.size)
            }
        },
        onDone = onDone,
    )

    /** 发送 APDU */
    fun sendApdu(hexData: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_send_apdu),
        block = { s ->
            runCatching {
                val engine = ApduEngine(s)
                val bytes = Hex.parseLenient(hexData)
                if (bytes.isEmpty()) error(AppStrings.get(R.string.err_apdu_empty))
                val exchange = engine.transceiveDetailed(bytes).getOrThrow()
                _uiState.value = _uiState.value.copy(lastExchange = exchange)
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.TX,
                        payload = exchange.command,
                        note = "APDU",
                    )
                )
                appendConsole(
                    ConsoleEntry(
                        direction = ConsoleEntry.Direction.RX,
                        payload = exchange.response,
                        note = ApduEngine.describeStatus(exchange.statusWord),
                    )
                )
                AppStrings.get(R.string.msg_apdu_sent, bytes.size, Hex.toHex(exchange.statusWord))
            }
        },
        onDone = onDone,
    )

    /** SELECT AID */
    fun selectAid(aid: String, onDone: (Boolean, String) -> Unit) = launchWrite(
        label = AppStrings.get(R.string.op_select_aid),
        block = { s -> runCatching { ApduEngine(s).selectAid(aid).getOrThrow() } },
        onDone = onDone,
    )

    /** DESFire 卡片信息 */
    private val _desfireInfo = MutableStateFlow<DesfireCardInfo?>(null)
    val desfireInfo: StateFlow<DesfireCardInfo?> = _desfireInfo.asStateFlow()

    /** 读取 DESFire 卡片信息 */
    fun readDesfireInfo(onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val s = session
            if (s == null) {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, AppStrings.needReadFirst())
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { DesfireOps(s).readCardInfo().getOrThrow() }
            }
            result.onSuccess { info ->
                _uiState.value = _uiState.value.copy(busy = false)
                _desfireInfo.value = info
                onDone(true, AppStrings.get(R.string.msg_desfire_read))
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, e.message ?: "读取失败")
            }
        }
    }

    /** DESFire 读取文件内容 */
    fun readDesfireFile(fileId: Int, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val s = session
            if (s == null) {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, AppStrings.needReadFirst())
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { DesfireOps(s).readFile(fileId).getOrThrow() }
            }
            result.onSuccess { data ->
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(true, AppStrings.get(R.string.msg_desfire_file, fileId.toString(16), Hex.toHex(data)))
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, e.message ?: "读取失败")
            }
        }
    }

    /** 导出当前标签数据为 JSON 并分享 */
    fun exportJson(onDone: (Boolean, String) -> Unit) {
        val overview = _uiState.value.overview
        if (overview == null) {
            onDone(false, AppStrings.needReadFirst())
            return
        }
        runCatching {
            val content = TagExporter.toJson(
                overview = overview,
                mifareDump = _uiState.value.mifareDump,
                includeSecrets = settings.value.exportSecrets,
            )
            TagExporter.share(
                context = getApplication(),
                content = content,
                fileName = TagExporter.buildFileName(overview, "json"),
                mimeType = "application/json",
            )
        }.onSuccess {
            onDone(true, AppStrings.get(R.string.msg_exported_json))
        }.onFailure {
            onDone(false, it.message ?: AppStrings.get(R.string.err_export_failed))
        }
    }

    /** 导出当前标签数据为纯文本并分享 */
    fun exportText(onDone: (Boolean, String) -> Unit) {
        val overview = _uiState.value.overview
        if (overview == null) {
            onDone(false, AppStrings.needReadFirst())
            return
        }
        runCatching {
            val content = TagExporter.toText(
                overview = overview,
                mifareDump = _uiState.value.mifareDump,
                includeSecrets = settings.value.exportSecrets,
            )
            TagExporter.share(
                context = getApplication(),
                content = content,
                fileName = TagExporter.buildFileName(overview, "txt"),
                mimeType = "text/plain",
            )
        }.onSuccess {
            onDone(true, AppStrings.get(R.string.msg_exported_text))
        }.onFailure {
            onDone(false, it.message ?: AppStrings.get(R.string.err_export_failed))
        }
    }

    /** 清空已读取的块级数据 */
    fun clearBlockData() {
        _uiState.value = _uiState.value.copy(mifareDump = emptyMap(), ultralightPages = emptyList())
    }

    /** 读取 MIFARE 全卡 dump */
    fun dumpMifare(onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val s = session
            if (s == null) {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, AppStrings.needReadFirst())
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { MifareClassicOps(s).dumpAll().getOrThrow() }
            }
            result.onSuccess { dump ->
                _uiState.value = _uiState.value.copy(busy = false, mifareDump = dump)
                onDone(true, AppStrings.get(R.string.msg_dump_blocks, dump.size))
            }.onFailure {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, it.message ?: "读取失败")
            }
        }
    }

    /** 读取 Ultralight 全部用户页 */
    fun dumpUltralight(onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            val s = session
            if (s == null) {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, AppStrings.needReadFirst())
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ops = MifareUltralightOps(s)
                    ops.readPages(4, ops.userPageCount()).getOrThrow()
                }
            }
            result.onSuccess { pages ->
                _uiState.value = _uiState.value.copy(busy = false, ultralightPages = pages)
                onDone(true, AppStrings.get(R.string.msg_dump_pages, pages.size))
            }.onFailure {
                _uiState.value = _uiState.value.copy(busy = false)
                onDone(false, it.message ?: "读取失败")
            }
        }
    }

    /**
     * 统一的写入入口。
     *
     * 与旧实现的区别：**不再要求事先读到标签**。
     * 点写入只是把任务挂起并弹出「请靠近标签」提示，
     * 真正执行要等 reader mode 检测到标签（见 [executePendingWrite]）。
     *
     * [block] 必须返回 [Result]：内部所有写操作都用 `runCatching` 包裹，
     * 失败时是 `Result.failure` 而**不是抛异常**。
     * 执行侧用 [Result.getOrThrow] 把失败转成异常，
     * 否则失败会被误判成成功 —— 会出现「标签都没贴也显示写入成功」。
     */
    private fun launchWrite(
        label: String,
        block: suspend (TagSession) -> Result<Any?>,
        onDone: (Boolean, String) -> Unit,
    ) {
        // 连续点多个写入按钮时，以最后一次点击为准（覆盖此前的挂起任务）
        pendingWrite = PendingWrite(label, block, onDone)
        // 重置防抖：允许用户马上用同一张标签（比如刚在读取页扫过的那张）触发写入，
        // 否则 1.5 秒内的重复贴卡会被防抖逻辑吞掉，表现为「点了写入没反应」。
        lastTagUid = null
        lastTagTime = 0L
        _uiState.value = _uiState.value.copy(
            awaitingTag = true,
            awaitingWriteLabel = label,
            busy = false,
            error = null,
        )
        appendConsole(
            ConsoleEntry(
                direction = ConsoleEntry.Direction.INFO,
                payload = ByteArray(0),
                note = AppStrings.get(R.string.msg_await_tag, label),
            )
        )
    }

    // ---------------- 控制台 ----------------

    fun appendConsole(entry: ConsoleEntry) {
        _console.value = (listOf(entry) + _console.value).take(300)
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun showToast(message: String) {
        _uiState.value = _uiState.value.copy(toast = message)
    }

    /** 取消等待中的写入任务（用户在「请靠近标签」弹窗里点取消） */
    fun cancelPendingWrite() {
        pendingWrite = null
        _uiState.value = _uiState.value.copy(
            awaitingTag = false,
            awaitingWriteLabel = null,
            busy = false,
        )
    }

    fun clearSession() {
        session?.close()
        session = null
        pendingWrite = null
        // 重置防抖，允许立即重新扫描同一张卡
        lastTagUid = null
        lastTagTime = 0L
        _uiState.value = NfcUiState()
    }

    // ---------------- 设置 ----------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }

    fun removeHistory(id: String) {
        viewModelScope.launch { historyStore.remove(id) }
    }

    override fun onCleared() {
        session?.close()
    }

    companion object {
        /** 同一张标签的重复回调防抖窗口（毫秒） */
        private const val DEBOUNCE_MS = 1500L

        /**
         * 二维码模块边长的候选阶梯（像素）。
         *
         * 二维码 PNG 体积随模块边长**平方**增长（面积），
         * 所以从大到小逐级尝试，取第一个能塞进标签容量的。
         *
         * 最小 1px —— 再小就没法被相机识别了。
         */
        private val MODULE_SIZE_LADDER = intArrayOf(6, 4, 3, 2, 1)

        /** 写入模式：原始内容 + 二维码图片（两种用法都成立） */
        const val QR_MODE_BOTH = 0

        /** 写入模式：仅原始内容（最省空间，碰一下直接打开） */
        const val QR_MODE_CONTENT = 1

        /** 写入模式：仅二维码图片（内容靠扫码获得） */
        const val QR_MODE_IMAGE = 2
    }
}
