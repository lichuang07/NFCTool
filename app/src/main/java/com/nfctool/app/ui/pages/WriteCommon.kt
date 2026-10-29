package com.nfctool.app.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nfctool.app.ui.theme.MonoText
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R

/**
 * 与主题一致的输入框。
 */
@Composable
fun NfcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val e = extras
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                placeholder,
                style = if (mono) MonoText.body else MaterialTheme.typography.bodyMedium,
                color = e.textTertiary,
            )
        },
        textStyle = if (mono) MonoText.body else MaterialTheme.typography.bodyMedium,
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = e.accent.primary.copy(alpha = 0.7f),
            unfocusedBorderColor = e.glassBorder,
            focusedContainerColor = e.glassSurface,
            unfocusedContainerColor = e.glassSurface,
            disabledContainerColor = e.glassSurface,
            cursorColor = e.accent.primary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 表单里的字段标签。
 */
@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = extras.textTertiary,
    )
}

/**
 * 通用单选行。
 */
@Composable
fun ChoiceRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val e = extras
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(
                        if (isSelected) e.accent.primary.copy(alpha = 0.18f) else e.glassSurface
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) e.accent.primary.copy(alpha = 0.7f) else e.glassBorder,
                        ),
                        shape,
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) e.accent.primary else e.textTertiary,
                )
            }
        }
    }
}

/**
 * 通用单选行（字符串值）。
 */
@Composable
fun ChoiceRowText(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val e = extras
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(
                        if (isSelected) e.accent.primary.copy(alpha = 0.18f) else e.glassSurface
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) e.accent.primary.copy(alpha = 0.7f) else e.glassBorder,
                        ),
                        shape,
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) e.accent.primary else e.textTertiary,
                )
            }
        }
    }
}

/**
 * 轻量开关（避免引入完整 Switch 的视觉噪音）。
 */
@Composable
fun SwitchLike(checked: Boolean, onToggle: () -> Unit) {
    val e = extras
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (checked) e.accent.primary.copy(alpha = 0.22f) else e.glassSurface)
            .border(
                BorderStroke(
                    1.dp,
                    if (checked) e.accent.primary.copy(alpha = 0.7f) else e.glassBorder,
                ),
                shape,
            )
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (checked) stringResource(R.string.action_enabled) else stringResource(R.string.action_disabled),
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) e.accent.primary else e.textTertiary,
        )
    }
}
