package com.nfctool.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.data.ScanRecord
import com.nfctool.app.ui.MainViewModel
import com.nfctool.app.ui.components.AppAlertDialog
import com.nfctool.app.ui.components.GlassCard
import com.nfctool.app.ui.components.GhostButton
import com.nfctool.app.ui.components.SectionHeader
import com.nfctool.app.ui.components.StatusBadge
import com.nfctool.app.ui.theme.MonoText
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R
import com.nfctool.app.core.i18n.AppStrings

/**
 * 历史记录页：保存最近扫描过的标签。
 */
@Composable
fun HistoryPage(vm: MainViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 48.dp, bottom = 110.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tab_history),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.history_count, history.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textTertiary,
                    )
                }
                if (history.isNotEmpty()) {
                    GhostButton(
                        text = stringResource(R.string.action_clear),
                        icon = { Icon(Icons.Rounded.Delete, null, Modifier.size(14.dp)) },
                        onClick = { confirmClear = true },
                    )
                }
            }
        }

        if (history.isEmpty()) {
            item {
                GlassCard {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = extras.textTertiary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.history_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.history_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = extras.textTertiary,
                        )
                    }
                }
            }
        } else {
            items(history, key = { it.id }) { record ->
                HistoryCard(
                    record = record,
                    onCopyUid = {
                        clipboard.setText(AnnotatedString(record.uidHex))
                        vm.showToast(appContext.getString(R.string.toast_copied_uid))
                    },
                    onDelete = { vm.removeHistory(record.id) },
                )
            }
        }
    }

    if (confirmClear) {
        AppAlertDialog(
            title = stringResource(R.string.history_clear_title),
            message = stringResource(R.string.history_clear_msg, history.size),
            confirmText = stringResource(R.string.history_clear_button),
            onConfirm = { vm.clearHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HistoryCard(
    record: ScanRecord,
    onCopyUid: () -> Unit,
    onDelete: () -> Unit,
) {
    val e = extras
    GlassCard {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        record.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        record.uidHex,
                        style = MonoText.body,
                        color = e.accent.primary,
                    )
                }
                StatusBadge(cardTypeLabel(record.cardType))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                techSummaryLabel(record.techSummary),
                style = MaterialTheme.typography.bodySmall,
                color = e.textTertiary,
            )
            record.ndefSummary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    record.timestamp.toReadable(),
                    style = MonoText.caption,
                    color = e.textTertiary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(text = stringResource(R.string.action_copy_uid), onClick = onCopyUid)
                    GhostButton(text = stringResource(R.string.action_delete), onClick = onDelete)
                }
            }
        }
    }
}

private fun Long.toReadable(): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = this
    return String.format(
        java.util.Locale.US,
        "%04d-%02d-%02d %02d:%02d",
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

/**
 * 历史记录里存的是稳定标识（卡片类型枚举名 / 技术枚举名），
 * 展示时再本地化；兼容旧数据（当年存的是中文展示名）。
 */
private fun cardTypeLabel(stored: String): String {
    val type = runCatching { com.nfctool.app.core.nfc.CardType.valueOf(stored) }.getOrNull()
        ?: com.nfctool.app.core.nfc.CardType.entries.firstOrNull {
            AppStrings.get(it.displayNameRes) == stored
        }
        ?: return stored
    return AppStrings.get(type.displayNameRes)
}

private fun techSummaryLabel(stored: String): String =
    stored.split(" · ").joinToString(" · ") { name ->
        val tech = runCatching { com.nfctool.app.core.nfc.NfcTech.valueOf(name) }.getOrNull()
            ?: com.nfctool.app.core.nfc.NfcTech.entries.firstOrNull {
                AppStrings.get(it.displayNameRes) == name
            }
        if (tech != null) AppStrings.get(tech.displayNameRes) else name
    }
