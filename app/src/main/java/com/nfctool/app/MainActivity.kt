package com.nfctool.app

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nfctool.app.core.i18n.AppStrings
import com.nfctool.app.core.i18n.LocalePref
import com.nfctool.app.core.share.ShareIntentRouter
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.NfctoolAppRoot
import com.nfctool.app.ui.theme.NfctoolTheme

class NfctoolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // core 层（ops / codec / exporter）的用户可见文案经此取词，
        // 必须在任何 NFC 操作之前初始化
        AppStrings.init(this)
    }
}

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var readerModeActive = false

    /** 由 Compose 侧回填，供 reader mode 回调与 Intent 转发标签 */
    private var viewModel: MainViewModel? = null

    /**
     * 冷启动时 [onResume] 早于首次组合执行 —— 窗口 attach 在 onResume 之后，
     * Compose 组合要等首帧，所以那一刻 [viewModel] 还没回填，
     * 此时处理 Intent 等于静默丢弃（`viewModel?.xxx` 是空调用）。
     * 先把 Intent 存起来，等组合就绪后由 [onViewModelReady] 补处理。
     */
    private var pendingIntent: Intent? = null

    /**
     * 当前启动 Intent 是否已被处理过。
     *
     * 处理完必须「消费」：否则每次 [onResume] 都会把同一个 Intent 再跑一遍 ——
     * 图片分享会重复读流重复压缩；更糟的是用户改过表单后按 Home 再回来，
     * 填写内容会被覆盖成最初的分享内容，编辑全部丢失。
     * 旋转重建后系统会用原始启动 Intent 重新填充 `getIntent()`，
     * 所以这个标记要随 savedInstanceState 持久化。
     */
    private var intentHandled = false

    /**
     * 语言装配：把用户选择的语言包进基 Context。
     *
     * Compose 的 `stringResource` 走的是 Activity Context 的 configuration，
     * 所以这里包装一次，整个 UI 树的资源解析就都跟随所选语言。
     * 「跟随系统」时不包装，走系统默认（中文系统命中 values/，其他命中 values-en/）。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        val locale = LocalePref.currentLocale(newBase)
        super.attachBaseContext(LocalePref.wrap(newBase, locale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        super.onCreate(savedInstanceState)
        intentHandled = savedInstanceState?.getBoolean(STATE_INTENT_HANDLED) == true
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            val vm: MainViewModel = viewModel()
            viewModel = vm
            // 冷启动时 onResume 早于本组合执行，分享 / 标签 Intent 当时还没人接；
            // 这里 viewModel 已就绪，补处理一次
            LaunchedEffect(Unit) { onViewModelReady() }
            val settings by vm.settings.collectAsState()
            val isDark = when (settings.themeMode) {
                "light" -> false
                else -> true
            }

            NfctoolTheme(accentKey = settings.accentColor, darkMode = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        NfctoolAppRoot(vm = vm)
                    }
                }
            }
            ready = true
        }
    }

    override fun onStart() {
        super.onStart()
        enableReaderMode()
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    override fun onStop() {
        super.onStop()
        disableReaderMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_INTENT_HANDLED, intentHandled)
    }

    /**
     * 由 Compose 侧在 viewModel 回填后调用。
     *
     * 冷启动时 [onResume] 里 viewModel 还是 null，Intent 被暂存在
     * [pendingIntent]；这里补处理一次，避免分享内容被静默丢弃。
     */
    fun onViewModelReady() {
        val pending = pendingIntent ?: return
        pendingIntent = null
        handleIntent(pending)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 新内容到来，允许处理（见 intentHandled 注释）
        intentHandled = false
        handleIntent(intent)
    }

    /**
     * 处理来自其他应用的分享。
     *
     * ## ⚠️ 为什么不能「看到 EXTRA_STREAM 就当图片」
     *
     * **Chrome 分享网页时会同时带 URL 和页面截图**：
     * ```
     * intent.type = "text/plain"                    // MIME 是文本
     * intent.putExtra(EXTRA_SUBJECT, titleOfPage)  // 网页标题
     * intent.putExtra(EXTRA_TEXT, urlOfPage)       // 网页 URL
     * intent.putExtra(EXTRA_STREAM, screenshotUri)  // 页面截图！
     * ```
     * Chrome 会顺手把当前页面截个图放进 EXTRA_STREAM。
     * 若优先取 stream，Chrome 分享网页就会进「图片写入」页 —— 明明分享的是链接。
     *
     * 所以判定交给 [ShareIntentRouter]：**MIME type 优先**，
     * `text/` 前缀的 MIME 一律走文本分支，附带的截图被忽略。
     *
     * 判定结果再交给 `SharedContentParser` 做二次分类
     * （链接 / 地理位置 / vCard / 纯文本）。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val clip = intent.clipData
        val firstItem = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)

        val decision = ShareIntentRouter.decide(
            mime = intent.type,
            extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
            streamUri = extractStreamUri(intent)?.toString(),
            clipText = firstItem?.text?.toString(),
            clipUri = firstItem?.uri?.toString(),
        )

        when (decision.route) {
            ShareIntentRouter.Route.TEXT -> {
                val text = decision.text ?: return
                viewModel?.handleSharedText(text, decision.subject)
            }

            ShareIntentRouter.Route.IMAGE -> {
                val uri = decision.imageUri ?: return
                readSharedImage(uri, intent.type)
            }

            ShareIntentRouter.Route.NONE -> {
                viewModel?.showShareError(
                    decision.reason.ifEmpty { "无法识别分享内容" }
                )
            }
        }
    }

    /**
     * 取出分享的二进制内容 URI。
     *
     * 优先 `EXTRA_STREAM`（Android 7.0+ 官方渠道），
     * 回退到 ClipData 里的 URI。
     */
    private fun extractStreamUri(intent: Intent): android.net.Uri? {
        val fromExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
        }
        if (fromExtra != null) return fromExtra

        return intent.clipData?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).uri else null
        }
    }

    /**
     * 读取分享出来的图片。
     *
     * 分享方通过 `FLAG_GRANT_READ_URI_PERMISSION` 授予了临时读权限，
     * 所以这里能直接用 ContentResolver 打开 content URI。
     *
     * 读取是阻塞 IO，必须放到后台线程 —— 这里用最简单的
     * 单线程后台执行，完成后回主线程交给 ViewModel。
     *
     * @param uriString content URI 的字符串形式
     */
    private fun readSharedImage(uriString: String, declaredMime: String?) {
        val uri = uriString.toUri()
        val resolver = applicationContext.contentResolver
        val resolvedType = resolver.getType(uri)
        // MIME 要以实际 URI 的类型为准：分享方声明的 type 可能是 text/plain
        // （Chrome 会把网页截图放进 EXTRA_STREAM），不能直接拿来当图片类型
        val mime = resolvedType
            ?: declaredMime?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

        // 文件管理器分享 .txt / .vcf 等文本文件时：MIME 是 text/*，
        // 内容只放在 stream 里（没有 EXTRA_TEXT）。这种要按文本读 ——
        // 否则一个文本文件会流进图片表单，压缩必然失败，用户莫名所以。
        // 仅当解析器明确报告文本类型时才走这条，getType 为 null 时维持原逻辑。
        if (resolvedType?.startsWith("text/") == true) {
            readSharedTextFile(uri)
            return
        }

        Thread {
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                }
            }.getOrNull()

            runOnUiThread {
                val vm = viewModel ?: return@runOnUiThread
                if (bytes == null || bytes.isEmpty()) {
                    vm.handleSharedImage(null, mime)
                } else {
                    vm.handleSharedImage(bytes, mime)
                }
            }
        }.start()
    }

    /**
     * 读取分享出来的文本文件（文件管理器分享 .txt / .vcf 的场景）。
     *
     * 与 [readSharedImage] 同样的后台线程 + 回主线程模式。
     */
    private fun readSharedTextFile(uri: android.net.Uri) {
        val resolver = applicationContext.contentResolver
        Thread {
            val text = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    // 去掉可能存在的 UTF-8 BOM，否则 geo: / BEGIN:VCARD 前缀判断会失灵
                    input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
                }
            }.getOrNull()
            runOnUiThread {
                val vm = viewModel ?: return@runOnUiThread
                if (text.isNullOrBlank()) {
                    vm.showShareError("分享的文件内容为空或无法读取")
                } else {
                    vm.handleSharedText(text)
                }
            }
        }.start()
    }

    /**
     * 启用 Reader Mode。
     *
     * 关键点：读卡成功后不要立刻关闭 reader mode，否则系统默认的标签应用
     * 会被拉起并把本应用挤到后台 —— 这是 Reader Mode 最常见的坑。
     * 因此只在 onPause / onStop 时关闭。
     */
    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (readerModeActive) return
        try {
            adapter.enableReaderMode(
                this,
                { tag: Tag ->
                    // 回调发生在 Binder 线程，转交到主线程再交给 ViewModel
                    runOnUiThread { viewModel?.handleIntentTag(tag) }
                },
                READER_FLAGS,
                null,
            )
            readerModeActive = true
        } catch (e: Exception) {
            readerModeActive = false
        }
    }

    private fun disableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!readerModeActive) return
        runCatching { adapter.disableReaderMode(this) }
        readerModeActive = false
    }

    /** 处理 Intent 中携带的标签或分享内容（冷启动 / onNewIntent 场景） */
    @Suppress("DEPRECATION") // ACTION_TAG_DISCOVERED 已弃用，但冷启动兜底仍要认它
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val isShare = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
        val isTag = action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED
        if (!isShare && !isTag) return
        // 同一个 Intent 只处理一次（见 intentHandled 注释）
        if (intentHandled) return
        // 冷启动 / 旋转后重建：组合还没跑，viewModel 是 null，
        // 现在处理会被静默丢弃 —— 先存起来，等 onViewModelReady 补处理
        if (viewModel == null) {
            pendingIntent = intent
            return
        }

        if (isShare) {
            handleShareIntent(intent)
        } else {
            // getParcelableExtra(String, Class) 需要 API 33，
            // 低版本走 deprecated 的单参数重载。
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) as? Tag
            } ?: return
            viewModel?.handleIntentTag(tag)
        }

        // 消费：标记已处理，并换上一个空 Intent，
        // 防止 getIntent() 仍持有旧内容被下次 onResume 重复处理
        intentHandled = true
        pendingIntent = null
        setIntent(Intent(Intent.ACTION_MAIN))
    }

    companion object {
        /** savedInstanceState 键：启动 Intent 是否已处理过 */
        private const val STATE_INTENT_HANDLED = "intentHandled"

        /**
         * Reader Mode 标志位。
         *
         *  - NFC_A / NFC_B / NFC_F / NFC_V：轮询全部射频协议
         *  - NO_PLATFORM_SOUNDS：关闭系统提示音，由应用自己给反馈
         */
        private const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
