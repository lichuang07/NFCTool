package com.nfctool.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R

/**
 * 统一风格的结果对话框。
 */
@Composable
fun AppAlertDialog(
    title: String,
    message: String,
    confirmText: String = stringResource(R.string.action_ok),
    dismissText: String? = null,
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmText) }
        },
        dismissButton = if (dismissText != null) {
            {
                TextButton(onClick = onDismiss) { Text(dismissText) }
            }
        } else null,
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
