package com.nfctool.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.StringRes
import com.nfctool.app.core.nfc.Hex
import com.nfctool.app.ui.ConsoleEntry
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.GhostButton
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.NeonButton
import com.nfctool.app.ui.components.SectionHeader
import com.nfctool.app.ui.components.StatusBadge
import com.nfctool.app.ui.theme.MonoText
import com.nfctool.app.ui.theme.StatusError
import com.nfctool.app.ui.theme.StatusInfo
import com.nfctool.app.ui.theme.StatusOk
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R

/**
 * 控制台页：原始指令收发与 APDU 透传。
 */
@Composable
fun ConsolePage(vm: MainViewModel) {
    val entries by vm.console.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ConsoleMode.RAW) }
    var aid by remember { mutableStateOf("A0000000031010") }
    var result by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(0)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 48.dp, bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text(
                    stringResource(R.string.tab_console),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(R.string.console_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textTertiary,
                )
            }
        }

        item {
            GlassCard(accent = true) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConsoleMode.entries.forEach { m ->
                            com.nfctool.app.ui.components.TechChip(
                                text = stringResource(if (m == ConsoleMode.RAW) R.string.console_mode_raw else R.string.console_mode_apdu),
                                selected = mode == m,
                                onClick = { mode = m },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (mode == ConsoleMode.APDU) {
                        Text(
                            stringResource(R.string.console_select_aid),
                            style = MaterialTheme.typography.labelMedium,
                            color = extras.textTertiary,
                        )
                        Spacer(Modifier.height(6.dp))
                        NfcTextField(
                            value = aid,
                            onValueChange = { aid = it.uppercase() },
                            placeholder = stringResource(R.string.console_aid_ph),
                            singleLine = true,
                            mono = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        GhostButton(
                            text = stringResource(R.string.console_select_aid_button),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            vm.selectAid(aid) { ok, msg -> result = if (ok) appContext.getString(R.string.msg_aid_selected, msg) else msg }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        stringResource(R.string.console_command_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    NfcTextField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = if (mode == ConsoleMode.RAW) "00 A4 04 00 00" else "00 A9 04 00 00",
                        singleLine = false,
                        mono = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    NeonButton(
                        text = stringResource(if (mode == ConsoleMode.RAW) R.string.console_send_raw else R.string.console_send_apdu),
                        modifier = Modifier.fillMaxWidth(),
                        // 发送不依赖先读卡：点发送后贴近标签即可执行
                        enabled = command.isNotBlank(),
                        icon = { Icon(Icons.Rounded.Send, null, Modifier.size(18.dp)) },
                    ) {
                        if (mode == ConsoleMode.RAW) {
                            vm.sendRaw(command) { ok, msg -> result = msg }
                        } else {
                            vm.sendApdu(command) { ok, msg -> result = msg }
                        }
                    }
                    if (ui.overview == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.console_await_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusInfo,
                        )
                    }
                }
            }
        }

        // 常用指令速查
        item { SectionHeader(stringResource(R.string.console_quick)) }
        item {
            GlassCard {
                Column {
                    QuickCommand(stringResource(R.string.console_quick_read_ndef), "00 B1 04 00 00") {
                        command = it; mode = ConsoleMode.RAW
                    }
                    QuickCommand(stringResource(R.string.console_quick_bank_aid), "00 A4 04 00 07 A0 00 00 03 10 10") {
                        command = it; mode = ConsoleMode.APDU
                    }
                    QuickCommand(stringResource(R.string.console_quick_get_response), "00 C0 00 00") {
                        command = it; mode = ConsoleMode.APDU
                    }
                    QuickCommand(stringResource(R.string.console_quick_get_data), "80 CA 9F 7F 00") {
                        command = it; mode = ConsoleMode.APDU
                    }
                }
            }
        }

        // 日志
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(stringResource(R.string.console_log), trailing = "${entries.size}")
                GhostButton(
                    text = "清空",
                    icon = { Icon(Icons.Rounded.Delete, null, Modifier.size(14.dp)) },
                    onClick = vm::clearConsole,
                )
            }
        }

        if (entries.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        stringResource(R.string.console_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                ConsoleRow(entry)
            }
        }
    }

    result?.let {
        com.nfctool.app.ui.components.AppAlertDialog(
            title = stringResource(R.string.console_result_title),
            message = it,
            onDismiss = { result = null },
        )
    }
}

private enum class ConsoleMode(@StringRes val labelRes: Int) {
    RAW(R.string.console_mode_raw),
    APDU(R.string.console_mode_apdu),
}

@Composable
private fun QuickCommand(title: String, hex: String, onPick: (String) -> Unit) {
    val e = extras
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onPick(hex) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                hex,
                style = MonoText.caption,
                color = e.accent.primary.copy(alpha = 0.8f),
            )
        }
        Text(
            stringResource(R.string.action_fill),
            style = MaterialTheme.typography.labelSmall,
            color = e.textTertiary,
        )
    }
}

@Composable
private fun ConsoleRow(entry: ConsoleEntry) {
    val e = extras
    val (color, prefix) = when (entry.direction) {
        ConsoleEntry.Direction.TX -> StatusInfo to "TX"
        ConsoleEntry.Direction.RX -> StatusOk to "RX"
        ConsoleEntry.Direction.INFO -> e.accent.primary to "INF"
        ConsoleEntry.Direction.ERROR -> StatusError to "ERR"
    }

    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.07f))
            .padding(12.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(prefix, color = color)
                    Spacer(Modifier.width(8.dp))
                    entry.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    entry.timestamp.toTimeString(),
                    style = MonoText.caption,
                    color = e.textTertiary,
                )
            }
            if (entry.payload.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    Hex.toHex(entry.payload),
                    style = MonoText.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun Long.toTimeString(): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = this
    return String.format(
        java.util.Locale.US,
        "%02d:%02d:%02d",
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
        cal.get(java.util.Calendar.SECOND),
    )
}

