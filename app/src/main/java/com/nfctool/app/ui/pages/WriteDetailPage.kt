package com.nfctool.app.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.core.nfc.Hex
import com.nfctool.app.core.nfc.WriteEntry
import com.nfctool.app.core.nfc.ndef.BluetoothOobCodec
import com.nfctool.app.core.nfc.ndef.HandoverCodec
import com.nfctool.app.core.nfc.ndef.ImageCompressor
import com.nfctool.app.core.nfc.ndef.QrCodeEncoder
import com.nfctool.app.core.nfc.ndef.SharedContentParser
import com.nfctool.app.core.nfc.ndef.WifiWscCodec
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.GhostButton
import com.nfctool.app.ui.components.NeonButton
import com.nfctool.app.ui.theme.StatusError
import com.nfctool.app.ui.theme.StatusInfo
import com.nfctool.app.ui.theme.StatusOk
import com.nfctool.app.ui.theme.StatusWarn
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import androidx.core.graphics.createBitmap

/**
 * 写入详情页：根据目录条目分发到对应的专属表单。
 */
@Composable
fun WriteDetailPage(
    vm: MainViewModel,
    entryId: String,
    onBack: () -> Unit,
) {
    val entry = WriteEntry.byId(entryId)
    WriteDetailShell(entry = entry, onBack = onBack, vm = vm)
}

/**
 * 详情页外壳：统一的顶部返回栏 + 滚动容器 + 结果提示。
 */
@Composable
private fun WriteDetailShell(
    entry: WriteEntry?,
    onBack: () -> Unit,
    vm: MainViewModel,
) {
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(true) }

    val report: (Boolean, String) -> Unit = { ok, msg ->
        messageOk = ok
        message = msg
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 48.dp, bottom = 110.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DetailHeader(entry = entry, onBack = onBack)
            }
            item {
                WriteTargetStatus(vm)
            }
            item {
                if (entry == null) {
                    Text(stringResource(R.string.write_detail_unknown_type), color = extras.textTertiary)
                } else {
                    EntryForm(entry = entry, vm = vm, report = report)
                }
            }
        }
    }

    message?.let { msg ->
        // 成功只是「通知一声」：无按钮，2 秒后自动消失。
        // 失败必须手动关，错误信息要留时间读。
        if (messageOk) {
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                message = null
            }
        }
        WriteResultDialog(ok = messageOk, message = msg, onDismiss = { message = null })
    }
}

/**
 * 写入结果弹窗。
 *
 * 刻意不用 [AlertDialog]：成功态不该有按钮，而 Material3 的 AlertDialog
 * 去掉 confirmButton 后会留下一块空的按钮区。这里用 [Dialog] 自绘，
 * 成功 = 图标 + 文案自动消失，失败 = 图标 + 文案 + 「知道了」。
 */
@Composable
private fun WriteResultDialog(
    ok: Boolean,
    message: String,
    onDismiss: () -> Unit,
) {
    val e = extras
    val tone = if (ok) StatusOk else StatusError
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(accent = ok) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(tone.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = if (ok) stringResource(R.string.write_result_ok) else stringResource(R.string.write_result_fail),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = e.textTertiary,
                    textAlign = TextAlign.Center,
                )
                if (!ok) {
                    Spacer(Modifier.height(20.dp))
                    NeonButton(
                        text = stringResource(R.string.action_ok),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

/**
 * 详情页顶部：返回按钮 + 标题 + 说明。
 */
@Composable
private fun DetailHeader(entry: WriteEntry?, onBack: () -> Unit) {
    val e = extras
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(e.glassSurface)
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = e.accent.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry?.titleRes?.let { stringResource(it) } ?: stringResource(R.string.tab_write),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (entry != null) {
                Text(
                    stringResource(entry.summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textTertiary,
                )
                // 存储技术类条目对标签有要求，写在前列，
                // 免得用户贴错卡后才在错误提示里看到
                if (entry.requiredTech.isNotEmpty()) {
                    Text(
                        stringResource(R.string.write_entry_tech_required, entry.requiredTech.joinToString(" / ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusInfo,
                    )
                }
            }
        }
    }
}

/**
 * 统一的表单区块容器。
 */
@Composable
internal fun FormBlock(
    hint: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassCard {
        Column {
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textTertiary,
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/**
 * 写入目标状态条。
 *
 * 新版写入流程不再要求「先读一次标签」，所以这里展示的内容
 * 只是**上次读取的参考信息**（容量、UID），
 * 真正的写入对象是用户点击写入后贴近的那张标签。
 *
 * 这样设计的理由：旧版把「有没有读过卡」当成写入的前置条件，
 * 用户点写入只会得到「请先读取一张标签」，体验是断的；
 * 现在写入自己会等标签，可写性由写入时实际检测并给出明确错误。
 */
@Composable
private fun WriteTargetStatus(vm: MainViewModel) {
    val e = extras
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val overview = ui.overview

    val (title, subtitle, tone) = when {
        overview == null -> Triple(
            stringResource(R.string.write_detail_no_preread_title),
            stringResource(R.string.write_detail_no_preread_desc),
            StatusInfo,
        )

        overview.ndefWritable -> Triple(
            stringResource(R.string.write_detail_last_read, stringResource(overview.cardType.displayNameRes)),
            stringResource(R.string.write_detail_last_read_ref, Hex.formatUid(overview.uid), overview.ndefCapacity),
            StatusOk,
        )

        overview.ndefFormatable -> Triple(
            stringResource(R.string.write_detail_last_read_blank, stringResource(overview.cardType.displayNameRes)),
            stringResource(R.string.write_detail_last_read_blank_ref, Hex.formatUid(overview.uid)),
            StatusOk,
        )

        else -> Triple(
            stringResource(R.string.write_detail_last_read, stringResource(overview.cardType.displayNameRes)),
            stringResource(R.string.write_detail_last_read_locked_ref),
            StatusWarn,
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tone.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tone)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
        }
    }
}

/**
 * 坐标格式化：最多 6 位小数并去掉尾零。
 *
 * 分享来的坐标可能带很长的小数位，全量显示既难看又占输入框。
 */
private fun formatCoordinate(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return String.format(java.util.Locale.US, "%.6f", value)
        .trimEnd('0')
        .trimEnd('.')
}

/**
 * 未写时的可写性提示。
 *
 * 只在「明确知道不可写」时出现（上次读到的那张卡被锁定了）。
 * 没读过标签时不提示 —— 写入按钮照样可点，可写性由写入时实际检测。
 */
@Composable
private fun WritableHint(writable: Boolean) {
    if (!writable) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.write_hint_locked),
            style = MaterialTheme.typography.bodySmall,
            color = StatusWarn,
        )
    }
}

// ---------------- 各类型的专属表单 ----------------

@Composable
private fun EntryForm(
    entry: WriteEntry,
    vm: MainViewModel,
    report: (Boolean, String) -> Unit,
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val overview = ui.overview
    // 「可写」的语义已从「必须预先读到标签」改为「没有明确证据表明不可写」：
    // 没读过标签时一律放行，由写入时实际检测标签并给出错误。
    // 空白标签（NdefFormatable）的 ndefWritable 为 false，
    // 但写入路径会先格式化再写，所以也算「可写」。
    val writable = overview == null || overview.ndefWritable || overview.ndefFormatable

    when (entry) {
        WriteEntry.TEXT -> TextForm(writable, report, vm)
        WriteEntry.URI -> UriForm(writable, report, vm)
        WriteEntry.GEO -> GeoForm(writable, report, vm)
        WriteEntry.WIFI -> WifiForm(writable, report, vm)
        WriteEntry.VCARD -> VCardForm(writable, report, vm)
        WriteEntry.MECARD -> MeCardForm(writable, report, vm)
        WriteEntry.VCALENDAR -> VCalendarForm(writable, report, vm)
        WriteEntry.SMART_POSTER -> SmartPosterForm(writable, report, vm)
        WriteEntry.IMAGE -> ImageForm(writable, report, vm)
        WriteEntry.QRCODE -> QrCodeForm(writable, report, vm)
        WriteEntry.ANDROID_APP -> AndroidAppForm(writable, report, vm)
        WriteEntry.HANDOVER -> HandoverForm(writable, report, vm)
        WriteEntry.BLUETOOTH_OOB -> BluetoothOobForm(writable, report, vm)
        WriteEntry.AID -> AidForm(writable, report, vm)
        WriteEntry.MIME -> MimeForm(writable, report, vm)
        WriteEntry.EXTERNAL -> ExternalForm(writable, report, vm)
        WriteEntry.MIFARE_CLASSIC -> MifareClassicForm(report, vm, overview?.mifareSectors ?: 0, overview?.mifareBlocks ?: 0)
        WriteEntry.MIFARE_ULTRALIGHT -> UltralightForm(report, vm, overview?.ultralightPages ?: 0)
        WriteEntry.ISO15693 -> Iso15693Form(report, vm)
        WriteEntry.DESFIRE -> DesfireEntryForm(report, vm)
        WriteEntry.FORMAT -> FormatForm(report, vm)
        WriteEntry.RESTORE_MIFARE -> RestoreMifareForm(report, vm)
    }
}

@Composable
private fun TextForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    val prefill by vm.pendingPrefill.collectAsStateWithLifecycle()
    val prefillSeq by vm.prefillSeq.collectAsStateWithLifecycle()
    val prefillText = prefill?.takeIf { it.kind == SharedContentParser.Kind.TEXT }?.text

    var text by remember { mutableStateOf("") }
    // 分享内容到达时填入表单（用序号做 key，连续分享相同内容也能重新填）
    LaunchedEffect(prefillSeq) {
        if (!prefillText.isNullOrBlank()) text = prefillText
    }

    FormBlock {
        FieldLabel(stringResource(R.string.write_text_label))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.write_text_placeholder),
            singleLine = false,
        )
        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_text_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = text.isNotBlank(),
        ) { vm.writeText(text, report) }
        WritableHint(writable)
    }
}

@Composable
private fun UriForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    val prefill by vm.pendingPrefill.collectAsStateWithLifecycle()
    val prefillSeq by vm.prefillSeq.collectAsStateWithLifecycle()
    val uriPrefill = prefill?.takeIf { it.kind == SharedContentParser.Kind.URI }
    val prefillUri = uriPrefill?.uri
    val prefillTitle = uriPrefill?.title

    var uri by remember { mutableStateOf("https://") }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(prefillSeq) {
        if (!prefillUri.isNullOrBlank()) uri = prefillUri
        title = prefillTitle.orEmpty()
    }

    FormBlock {
        // 分享自浏览器时带回了网页标题，提示可写成带标题的智能海报
        if (!prefillTitle.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StatusInfo.copy(alpha = 0.10f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    tint = StatusInfo,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.write_uri_prefilled_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        FieldLabel(stringResource(R.string.write_uri_label))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = uri,
            onValueChange = { uri = it },
            placeholder = "https://example.com",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_uri_title_label))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = title,
            onValueChange = { title = it },
            placeholder = stringResource(R.string.write_uri_title_placeholder),
            singleLine = true,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton(
                text = stringResource(R.string.write_uri_button),
                modifier = Modifier.weight(1f),
                enabled = uri.isNotBlank(),
            ) { vm.writeUri(uri, report) }
            NeonButton(
                text = stringResource(R.string.write_uri_smartposter_button),
                modifier = Modifier.weight(1f),
                enabled = uri.isNotBlank(),
            ) {
                vm.writeSmartPoster(
                    title = title.trim().takeIf { it.isNotEmpty() },
                    uris = listOf(uri.trim()),
                    language = "zh",
                    onDone = report,
                )
            }
        }
        WritableHint(writable)
    }
}

@Composable
private fun GeoForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var latitude by remember { mutableStateOf("39.9042") }
    var longitude by remember { mutableStateOf("116.4074") }
    var altitude by remember { mutableStateOf("") }
    var uncertainty by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf("") }

    val prefill by vm.pendingPrefill.collectAsStateWithLifecycle()
    val prefillSeq by vm.prefillSeq.collectAsStateWithLifecycle()
    val geoPrefill = prefill?.takeIf { it.kind == SharedContentParser.Kind.GEO }

    // 分享来的坐标与名称自动填入
    LaunchedEffect(prefillSeq) {
        val p = geoPrefill ?: return@LaunchedEffect
        p.latitude?.let { latitude = formatCoordinate(it) }
        p.longitude?.let { longitude = formatCoordinate(it) }
        p.label?.takeIf { it.isNotBlank() }?.let { label = it }
    }

    val latValue = latitude.trim().toDoubleOrNull()
    val lonValue = longitude.trim().toDoubleOrNull()
    val latValid = latValue != null && latValue in -90.0..90.0
    val lonValid = lonValue != null && lonValue in -180.0..180.0

    FormBlock(hint = stringResource(R.string.write_geo_hint)) {
        // 分享来源与坐标系提示
        geoPrefill?.let { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StatusInfo.copy(alpha = 0.10f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    tint = StatusInfo,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.write_geo_prefilled, p.source ?: stringResource(R.string.action_unknown)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    p.coordinateSystem?.let { cs ->
                        Text(
                            text = stringResource(R.string.write_geo_coord_system, cs),
                            style = MaterialTheme.typography.bodySmall,
                            color = extras.textTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        FieldLabel(stringResource(R.string.write_geo_lat))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = latitude,
            onValueChange = { latitude = it },
            placeholder = "39.9042",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        )
        if (latitude.isNotBlank() && !latValid) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.write_geo_lat_error),
                style = MaterialTheme.typography.bodySmall,
                color = StatusWarn,
            )
        }

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_geo_lon))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = longitude,
            onValueChange = { longitude = it },
            placeholder = "116.4074",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        )
        if (longitude.isNotBlank() && !lonValid) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.write_geo_lon_error),
                style = MaterialTheme.typography.bodySmall,
                color = StatusWarn,
            )
        }

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_geo_label))
        Spacer(Modifier.height(6.dp))
        NfcTextField(label, { label = it }, stringResource(R.string.write_geo_label_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_geo_alt))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = altitude,
            onValueChange = { altitude = it },
            placeholder = "52",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_geo_unc))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = uncertainty,
            onValueChange = { uncertainty = it },
            placeholder = "35",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_geo_zoom))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = zoom,
            onValueChange = { zoom = it.filter(Char::isDigit) },
            placeholder = "16",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_geo_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = latValid && lonValid,
        ) {
            vm.writeGeo(
                latValue ?: 0.0,
                lonValue ?: 0.0,
                altitude, uncertainty, label, zoom,
                report,
            )
        }
        WritableHint(writable)
    }
}

/**
 * 图片写入表单。
 *
 * 标签容量极小（NTAG213 仅 137 字节），手机照片动辄几 MB，
 * 所以必须先压缩。这里把压缩后的预览与体积如实展示给用户，
 * 让用户知道标签里实际存的是什么。
 */
@Composable
private fun ImageForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    val appContext = LocalContext.current.applicationContext
    val prefill by vm.pendingPrefill.collectAsStateWithLifecycle()
    val prefillSeq by vm.prefillSeq.collectAsStateWithLifecycle()
    val imagePrefill = prefill?.takeIf { it.kind == SharedContentParser.Kind.IMAGE }

    val ui by vm.uiState.collectAsStateWithLifecycle()
    val capacity = ui.overview?.ndefCapacity ?: 0
    // 没读过标签时容量未知：预览按一个宽松预算压缩（比任何常见标签都大），
    // 真实容量在写入时检测，放不下会给出明确错误。
    // 留 30 字节给 NDEF 记录头。
    val budget = if (capacity > 0) (capacity - 30).coerceAtLeast(64) else 2048

    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var mime by remember { mutableStateOf("image/jpeg") }
    var compressed by remember { mutableStateOf<ImageCompressor.Result?>(null) }

    LaunchedEffect(prefillSeq) {
        val p = imagePrefill ?: return@LaunchedEffect
        val data = p.imageBytes ?: return@LaunchedEffect
        bytes = data
        mime = p.imageMime ?: "image/jpeg"
        compressed = ImageCompressor.compress(data, budget)
    }

    FormBlock(
        hint = stringResource(R.string.write_image_hint)
    ) {
        val current = bytes
        if (current == null) {
            Text(
                stringResource(R.string.write_image_empty),
                style = MaterialTheme.typography.bodySmall,
                color = extras.textTertiary,
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.write_image_data_label))
            Spacer(Modifier.height(6.dp))
            var hexInput by remember { mutableStateOf("") }
            NfcTextField(
                value = hexInput,
                onValueChange = { hexInput = it },
                placeholder = "FFD8FFDB0000…",
                singleLine = false,
                mono = true,
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = stringResource(R.string.write_image_load),
                modifier = Modifier.fillMaxWidth(),
                enabled = hexInput.isNotBlank(),
            ) {
                val data = Hex.parseLenient(hexInput)
                if (data.isEmpty()) {
                    report(false, appContext.getString(R.string.write_image_parse_fail))
                } else {
                    bytes = data
                    mime = ImageCompressor.guessMime(data)
                    compressed = ImageCompressor.compress(data, budget)
                }
            }
            return@FormBlock
        }

        // 压缩结果概览
        val result = compressed
        if (result != null) {
            MiniStat(stringResource(R.string.write_image_stat_original), stringResource(R.string.unit_bytes, result.originalSize))
            Spacer(Modifier.height(6.dp))
            MiniStat(stringResource(R.string.write_image_stat_compressed), stringResource(R.string.unit_bytes, result.size))
            Spacer(Modifier.height(6.dp))
            MiniStat(stringResource(R.string.write_image_stat_dimension), "${result.width} × ${result.height}")
            Spacer(Modifier.height(6.dp))
            MiniStat(stringResource(R.string.write_image_stat_format), result.mime)
            Spacer(Modifier.height(6.dp))
            MiniStat(
                stringResource(R.string.write_image_stat_capacity),
                if (capacity > 0) stringResource(R.string.unit_bytes, capacity) else stringResource(R.string.write_image_capacity_pending),
            )

            if (capacity > 0 && result.size > budget) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.write_image_over_capacity, result.size, budget),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusWarn,
                )
            } else if (capacity > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.write_image_ratio, String.format(Locale.US, "%.1f", result.ratio)),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusOk,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.write_image_auto),
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_image_button),
            modifier = Modifier.fillMaxWidth(),
            // 容量未知（没读过标签）时也放行：写入时会按实际标签容量再次压缩
            enabled = compressed != null &&
                (capacity <= 0 || (compressed?.size ?: 0) <= budget),
        ) {
            vm.writeImage(current, mime, report)
        }
        WritableHint(writable)
    }
}

/**
 * 二维码写入表单。
 *
 * 实时预览 —— 用户输入内容就能看到二维码，不必先写入标签才知道效果。
 * 预览用较大的模块尺寸保证清晰度，写入时再按标签容量自动缩小。
 */
@Composable
private fun QrCodeForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    val e = extras
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val capacity = ui.overview?.ndefCapacity ?: 0

    var content by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MainViewModel.QR_MODE_BOTH) }

    // 预览用的编码结果（模块大一点，看得清）
    val preview = remember(content) {
        if (content.isBlank()) null else QrCodeEncoder.encode(content, moduleSize = 6, margin = 2)
    }

    // 各种模式的预计写入体积
    val contentBytes = remember(content) { content.toByteArray(Charsets.UTF_8).size }
    val qrPngSize = remember(content) { estimateQrSize(content) }

    FormBlock(
        hint = stringResource(R.string.write_qr_hint)
    ) {
        FieldLabel(stringResource(R.string.write_qr_content))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = content,
            onValueChange = { content = it },
            placeholder = "https://example.com",
            singleLine = false,
        )

        Spacer(Modifier.height(12.dp))
        FieldLabel(stringResource(R.string.write_qr_mode))
        Spacer(Modifier.height(6.dp))
        ChoiceRow(
            options = listOf(
                MainViewModel.QR_MODE_BOTH to stringResource(R.string.write_qr_mode_both),
                MainViewModel.QR_MODE_CONTENT to stringResource(R.string.write_qr_mode_content),
                MainViewModel.QR_MODE_IMAGE to stringResource(R.string.write_qr_mode_image),
            ),
            selected = mode,
            onSelect = { mode = it },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (mode) {
                MainViewModel.QR_MODE_CONTENT ->
                    stringResource(R.string.write_qr_mode_content_desc)
                MainViewModel.QR_MODE_IMAGE ->
                    stringResource(R.string.write_qr_mode_image_desc)
                else ->
                    stringResource(R.string.write_qr_mode_both_desc)
            },
            style = MaterialTheme.typography.bodySmall,
            color = e.textTertiary,
        )
        if (mode != MainViewModel.QR_MODE_CONTENT) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.write_qr_tip),
                style = MaterialTheme.typography.bodySmall,
                color = StatusWarn,
            )
        }

        Spacer(Modifier.height(12.dp))
        if (content.isBlank()) {
            Text(
                stringResource(R.string.write_qr_preview_hint),
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
        } else if (preview == null) {
            Text(
                stringResource(R.string.write_qr_too_long),
                style = MaterialTheme.typography.bodySmall,
                color = StatusWarn,
            )
        } else {
            if (mode != MainViewModel.QR_MODE_CONTENT) {
                QrPreview(preview)
                Spacer(Modifier.height(10.dp))
            }

            MiniStat(stringResource(R.string.write_qr_stat_content_len), stringResource(R.string.unit_bytes, contentBytes))
            Spacer(Modifier.height(6.dp))
            if (mode != MainViewModel.QR_MODE_CONTENT) {
                MiniStat(
                    stringResource(R.string.write_qr_stat_version),
                    stringResource(R.string.write_qr_version_value, "${QrCodeEncoder.versionOf(preview.matrix, preview.margin)}"),
                )
                Spacer(Modifier.height(6.dp))
            }
            MiniStat(
                    stringResource(R.string.write_qr_stat_capacity),
                    if (capacity > 0) stringResource(R.string.unit_bytes, capacity) else stringResource(R.string.write_qr_capacity_unknown),
                )

            // 按模式给出预计写入体积与是否放得下
            val estimated = when (mode) {
                MainViewModel.QR_MODE_CONTENT -> contentBytes + 32
                MainViewModel.QR_MODE_IMAGE -> qrPngSize ?: 0
                else -> (qrPngSize ?: 0) + contentBytes + 32
            }
            if (capacity > 0) {
                Spacer(Modifier.height(6.dp))
                MiniStat(stringResource(R.string.write_qr_stat_estimated), stringResource(R.string.unit_bytes, estimated))
                if (estimated > capacity) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (mode) {
                            MainViewModel.QR_MODE_CONTENT ->
                                stringResource(R.string.write_qr_err_content_over, contentBytes, capacity)
                            MainViewModel.QR_MODE_IMAGE ->
                                stringResource(R.string.write_qr_err_qr_over, qrPngSize ?: 0, capacity)
                            else ->
                                stringResource(R.string.write_qr_err_total_over, estimated, capacity)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusWarn,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (mode) {
                            MainViewModel.QR_MODE_CONTENT ->
                                stringResource(R.string.write_qr_ok_content)
                            else ->
                                stringResource(R.string.write_qr_ok_total)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusOk,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = when (mode) {
                MainViewModel.QR_MODE_CONTENT -> stringResource(R.string.write_qr_button_content)
                MainViewModel.QR_MODE_IMAGE -> stringResource(R.string.write_qr_button_image)
                else -> stringResource(R.string.write_qr_button_both)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = content.isNotBlank() &&
                (mode == MainViewModel.QR_MODE_CONTENT || preview != null),
        ) { vm.writeQrCode(content, mode, report) }
        WritableHint(writable)
    }
}

/**
 * 二维码预览图。
 */
@Composable
private fun QrPreview(encoded: QrCodeEncoder.Encoded) {
    val e = extras
    // 预览时用较大尺寸绘制，缩放到固定宽度显示
    val pixels = remember(encoded) { QrCodeEncoder.toAlpha8(encoded) }
    val bitmap = remember(pixels) {
        runCatching {
            createBitmap(
                encoded.width,
                encoded.height,
                android.graphics.Bitmap.Config.ALPHA_8,
            ).apply {
                setPixels(
                    IntArray(pixels.size) { pixels[it].toInt() and 0xFF },
                    0,
                    encoded.width,
                    0,
                    0,
                    encoded.width,
                    encoded.height,
                )
            }
        }.getOrNull()
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.write_qr_preview),
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        } else {
            Text(
                stringResource(R.string.write_qr_preview_fail),
                style = MaterialTheme.typography.bodySmall,
                color = StatusError,
            )
        }
    }
}

/**
 * 估算二维码写入后的 PNG 体积。
 *
 * 用最大模块尺寸（1-bit PNG）的实际编码结果来估算，最准。
 */
private fun estimateQrSize(content: String): Int? {
    val encoded = QrCodeEncoder.encode(content, moduleSize = 4, margin = 1) ?: return null
    val png = QrCodeEncoder.toPng(encoded) ?: return null
    return png.size
}

@Composable
private fun BluetoothOobForm(
    writable: Boolean,
    report: (Boolean, String) -> Unit,
    vm: MainViewModel,
) {
    var isLe by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }

    val addressValid = BluetoothOobCodec.parseAddress(address.trim()) != null

    FormBlock(
        hint = stringResource(R.string.write_bt_hint)
    ) {
        FieldLabel(stringResource(R.string.write_bt_protocol))
        Spacer(Modifier.height(6.dp))
        ChoiceRow(
            options = listOf(
                0 to "Bluetooth LE",
                1 to stringResource(R.string.write_bt_protocol_bredr),
            ),
            selected = if (isLe) 0 else 1,
            onSelect = { isLe = it == 0 },
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_bt_address))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = address,
            onValueChange = { address = it },
            placeholder = "AA:BB:CC:DD:EE:FF",
            singleLine = true,
            mono = true,
        )
        if (address.isNotBlank() && !addressValid) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.write_bt_address_error),
                style = MaterialTheme.typography.bodySmall,
                color = StatusWarn,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.write_bt_little_endian),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textTertiary,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_bt_name))
        Spacer(Modifier.height(6.dp))
        NfcTextField(deviceName, { deviceName = it }, "WH-1000XM4")

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_bt_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = addressValid,
        ) { vm.writeBluetoothOob(isLe, address.trim(), deviceName.trim(), report) }
        WritableHint(writable)
    }
}

@Composable
private fun WifiForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(WifiWscCodec.AUTH_WPA2_PSK) }
    var hidden by remember { mutableStateOf(false) }

    FormBlock(
        hint = stringResource(R.string.write_wifi_hint)
    ) {
        FieldLabel(stringResource(R.string.write_wifi_ssid))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = ssid,
            onValueChange = { ssid = it },
            placeholder = "MyHomeWiFi",
            singleLine = true,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_wifi_security))
        Spacer(Modifier.height(6.dp))
        ChoiceRow(
            options = listOf(
                WifiWscCodec.AUTH_WPA2_PSK to "WPA2-PSK",
                WifiWscCodec.AUTH_WPA_PSK_WPA2_PSK to "WPA/WPA2",
                WifiWscCodec.AUTH_OPEN to stringResource(R.string.write_wifi_open),
            ),
            selected = authType,
            onSelect = { authType = it },
        )

        if (authType != WifiWscCodec.AUTH_OPEN) {
            Spacer(Modifier.height(10.dp))
            FieldLabel(stringResource(R.string.write_wifi_password))
            Spacer(Modifier.height(6.dp))
            NfcTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.write_wifi_password_ph),
                singleLine = true,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.write_wifi_hidden),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SwitchLike(checked = hidden, onToggle = { hidden = !hidden })
        }

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_wifi_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = ssid.isNotBlank() &&
                (authType == WifiWscCodec.AUTH_OPEN || password.isNotBlank()),
        ) {
            vm.writeWifi(
                WifiWscCodec.WifiConfig(
                    ssid = ssid.trim(),
                    password = password,
                    authType = authType,
                    hidden = hidden,
                ),
                report,
            )
        }
        WritableHint(writable)
    }
}

@Composable
private fun VCardForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    val prefill by vm.pendingPrefill.collectAsStateWithLifecycle()
    val prefillSeq by vm.prefillSeq.collectAsStateWithLifecycle()
    val cardPrefill = prefill?.takeIf { it.kind == SharedContentParser.Kind.VCARD }?.vcard

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    // 分享来的联系人信息自动填入
    LaunchedEffect(prefillSeq) {
        val c = cardPrefill ?: return@LaunchedEffect
        c.fullName?.let { name = it }
        c.phones.firstOrNull()?.let { phone = it }
        c.emails.firstOrNull()?.let { email = it }
        c.organization?.let { org = it }
        c.title?.let { title = it }
        c.urls.firstOrNull()?.let { url = it }
        c.address?.let { address = it }
    }

    FormBlock(hint = stringResource(R.string.write_vcard_hint)) {
        if (cardPrefill != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StatusInfo.copy(alpha = 0.10f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    tint = StatusInfo,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.write_vcard_prefilled),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        FieldLabel(stringResource(R.string.write_vcard_name))
        Spacer(Modifier.height(6.dp))
        NfcTextField(name, { name = it }, stringResource(R.string.write_vcard_name_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_phone))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = phone,
            onValueChange = { phone = it },
            placeholder = "13800000000",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_email))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = "someone@example.com",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_org))
        Spacer(Modifier.height(6.dp))
        NfcTextField(org, { org = it }, stringResource(R.string.write_vcard_org_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_title))
        Spacer(Modifier.height(6.dp))
        NfcTextField(title, { title = it }, stringResource(R.string.write_vcard_title_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_url))
        Spacer(Modifier.height(6.dp))
        NfcTextField(url, { url = it }, "https://example.com")

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_address))
        Spacer(Modifier.height(6.dp))
        NfcTextField(address, { address = it }, stringResource(R.string.write_vcard_address_ph))

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_vcard_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank(),
        ) {
            vm.writeVCardFull(
                name.trim(), phone.trim(), email.trim(), org.trim(),
                title.trim(), url.trim(), address.trim(), report,
            )
        }
        WritableHint(writable)
    }
}

@Composable
private fun MeCardForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    FormBlock(hint = stringResource(R.string.write_mecard_hint)) {
        FieldLabel(stringResource(R.string.write_vcard_name))
        Spacer(Modifier.height(6.dp))
        NfcTextField(name, { name = it }, stringResource(R.string.write_vcard_name_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_phone))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = phone,
            onValueChange = { phone = it },
            placeholder = "13800000000",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_email))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = "someone@example.com",
            singleLine = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_url))
        Spacer(Modifier.height(6.dp))
        NfcTextField(url, { url = it }, "https://example.com")

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcard_address))
        Spacer(Modifier.height(6.dp))
        NfcTextField(address, { address = it }, stringResource(R.string.write_vcard_address_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_mecard_note))
        Spacer(Modifier.height(6.dp))
        NfcTextField(note, { note = it }, stringResource(R.string.write_mecard_note_ph), singleLine = false)

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_mecard_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank(),
        ) {
            vm.writeMeCard(
                name.trim(), phone.trim(), email.trim(),
                url.trim(), address.trim(), note.trim(), report,
            )
        }
        WritableHint(writable)
    }
}

@Composable
private fun VCalendarForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var summary by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    FormBlock(hint = stringResource(R.string.write_vcal_hint)) {
        FieldLabel(stringResource(R.string.write_vcal_summary))
        Spacer(Modifier.height(6.dp))
        NfcTextField(summary, { summary = it }, stringResource(R.string.write_vcal_summary_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcal_start))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = start,
            onValueChange = { start = it },
            placeholder = "20261026T100000",
            singleLine = true,
            mono = true,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcal_end))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = end,
            onValueChange = { end = it },
            placeholder = "20261026T110000",
            singleLine = true,
            mono = true,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcal_location))
        Spacer(Modifier.height(6.dp))
        NfcTextField(location, { location = it }, stringResource(R.string.write_vcal_location_ph))

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_vcal_desc))
        Spacer(Modifier.height(6.dp))
        NfcTextField(description, { description = it }, stringResource(R.string.write_vcal_desc_ph), singleLine = false)

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_vcal_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = summary.isNotBlank(),
        ) {
            vm.writeVCalendar(
                summary.trim(), start.trim(), end.trim(),
                location.trim(), description.trim(), report,
            )
        }
        WritableHint(writable)
    }
}

@Composable
private fun SmartPosterForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var title by remember { mutableStateOf("") }
    var uris by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("zh") }

    FormBlock(hint = stringResource(R.string.write_sp_hint)) {
        FieldLabel(stringResource(R.string.write_sp_title))
        Spacer(Modifier.height(6.dp))
        NfcTextField(title, { title = it }, stringResource(R.string.write_sp_title_ph), singleLine = false)

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_sp_links))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = uris,
            onValueChange = { uris = it },
            placeholder = "https://example.com\nhttps://example.com/help",
            singleLine = false,
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_sp_lang))
        Spacer(Modifier.height(6.dp))
        ChoiceRowText(
            options = listOf(
                "zh" to stringResource(R.string.write_sp_lang_zh),
                "en" to stringResource(R.string.write_sp_lang_en),
                "ja" to stringResource(R.string.write_sp_lang_ja),
            ),
            selected = language,
            onSelect = { language = it },
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_sp_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = uris.isNotBlank(),
        ) {
            val list = uris.lines().map { it.trim() }.filter { it.isNotEmpty() }
            vm.writeSmartPoster(title.trim().ifBlank { null }, list, language, report)
        }
        WritableHint(writable)
    }
}

@Composable
private fun AndroidAppForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var packageName by remember { mutableStateOf("") }

    FormBlock(
        hint = stringResource(R.string.write_aar_hint)
    ) {
        FieldLabel(stringResource(R.string.write_aar_pkg))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = packageName,
            onValueChange = { packageName = it },
            placeholder = "com.example.app",
            singleLine = true,
            mono = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.write_aar_pkg_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textTertiary,
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_aar_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = packageName.isNotBlank(),
        ) { vm.writeAndroidApp(packageName.trim(), report) }
        WritableHint(writable)
    }
}

@Composable
private fun HandoverForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var isRequest by remember { mutableStateOf(true) }
    var carrierId by remember { mutableStateOf(HandoverCodec.CARRIER_URI) }
    var carrierData by remember { mutableStateOf("") }

    val carrier = HandoverCodec.CarrierType.byId(carrierId)

    FormBlock(hint = stringResource(R.string.write_ho_hint)) {
        FieldLabel(stringResource(R.string.write_ho_type))
        Spacer(Modifier.height(6.dp))
        ChoiceRow(
            options = listOf(
                HandoverCodec.CARRIER_URI to stringResource(R.string.write_ho_type_hr),
                HandoverCodec.CARRIER_BLUETOOTH to stringResource(R.string.write_ho_type_hs),
            ),
            selected = if (isRequest) 0 else 1,
            onSelect = { isRequest = it == 0 },
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_ho_carrier))
        Spacer(Modifier.height(6.dp))
        ChoiceRow(
            options = HandoverCodec.CarrierType.entries.map { it.id to it.label },
            selected = carrierId,
            onSelect = { carrierId = it },
        )

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_ho_carrier_data))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = carrierData,
            onValueChange = { carrierData = it },
            placeholder = carrier?.hintRes?.let { stringResource(it) } ?: "",
            singleLine = true,
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_ho_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = carrierData.isNotBlank(),
        ) {
            vm.writeHandover(
                isRequest,
                listOf(carrierId to carrierData.trim().toByteArray(Charsets.UTF_8)),
                report,
            )
        }
        WritableHint(writable)
    }
}

@Composable
private fun AidForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var aid by remember { mutableStateOf("F0010203040506") }

    FormBlock(hint = stringResource(R.string.write_aid_hint)) {
        FieldLabel(stringResource(R.string.write_aid_label))
        Spacer(Modifier.height(6.dp))
        NfcTextField(
            value = aid,
            onValueChange = { aid = it },
            placeholder = "F0010203040506",
            singleLine = true,
            mono = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.write_aid_example),
            style = MaterialTheme.typography.bodySmall,
            color = extras.textTertiary,
        )

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_aid_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = aid.isNotBlank(),
        ) { vm.writeAid(aid.trim(), report) }
        WritableHint(writable)
    }
}

@Composable
private fun MimeForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var mimeType by remember { mutableStateOf("text/plain") }
    var body by remember { mutableStateOf("Hello NFC") }

    FormBlock(hint = stringResource(R.string.write_mime_hint)) {
        FieldLabel(stringResource(R.string.write_mime_type))
        Spacer(Modifier.height(6.dp))
        NfcTextField(mimeType, { mimeType = it }, "text/plain")

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_mime_body))
        Spacer(Modifier.height(6.dp))
        NfcTextField(body, { body = it }, stringResource(R.string.write_mime_body_ph), singleLine = false)

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_mime_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = mimeType.isNotBlank(),
        ) { vm.writeMime(mimeType.trim(), body.toByteArray(Charsets.UTF_8), report) }
        WritableHint(writable)
    }
}

@Composable
private fun ExternalForm(writable: Boolean, report: (Boolean, String) -> Unit, vm: MainViewModel) {
    var domain by remember { mutableStateOf("com.example") }
    var type by remember { mutableStateOf("mytype") }
    var body by remember { mutableStateOf("") }

    FormBlock(hint = stringResource(R.string.write_ext_hint)) {
        FieldLabel(stringResource(R.string.write_ext_domain))
        Spacer(Modifier.height(6.dp))
        NfcTextField(domain, { domain = it }, "com.example", mono = true)

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_ext_type))
        Spacer(Modifier.height(6.dp))
        NfcTextField(type, { type = it }, "mytype", mono = true)

        Spacer(Modifier.height(10.dp))
        FieldLabel(stringResource(R.string.write_mime_body))
        Spacer(Modifier.height(6.dp))
        NfcTextField(body, { body = it }, stringResource(R.string.write_mime_body_ph), singleLine = false)

        Spacer(Modifier.height(12.dp))
        NeonButton(
            text = stringResource(R.string.write_ext_button),
            modifier = Modifier.fillMaxWidth(),
            enabled = domain.isNotBlank() && type.isNotBlank(),
        ) {
            vm.writeExternal(
                domain.trim(), type.trim(),
                body.toByteArray(Charsets.UTF_8), report,
            )
        }
        WritableHint(writable)
    }
}
