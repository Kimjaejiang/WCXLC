package com.Johnny.wcx.features.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.ui.content.BaseContactSelector
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.MINUTES_PER_DAY
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.WeTimeOfDayField
import com.Johnny.wcx.ui.content.formatMinuteOfDay
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Composable
internal fun AutomationRuleHeader(
    title: String,
    summary: String,
    enabled: Boolean,
    isOverridden: Boolean? = null,
    parentLabel: String = "",
    onActivate: () -> Unit = {},
    onReset: () -> Unit = {},
    onEnabledChange: (Boolean) -> Unit
) {
    val editable = isOverridden != false
    val effectiveSummary = if (isOverridden == false) "跟随$parentLabel: $summary" else summary
    ListItem(
        modifier = Modifier.clickable {
            if (editable) onEnabledChange(!enabled) else onActivate()
        },
        leadingContent = {
            Switch(
                checked = enabled,
                enabled = editable,
                onCheckedChange = if (editable) onEnabledChange else null
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(effectiveSummary) },
        trailingContent = if (isOverridden != null) {
            {
                TextButton(enabled = isOverridden, onClick = onReset) {
                    Text("重置")
                }
            }
        } else null
    )
}

@Composable
internal fun AutomationTimeRangeControls(
    rule: AutomationTimeRangeRule,
    editable: Boolean,
    onChange: (AutomationTimeRangeRule) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WeTimeOfDayField(
            modifier = Modifier.weight(1f),
            label = "开始",
            minuteOfDay = rule.startMinute,
            enabled = editable,
            onMinuteChange = { onChange(rule.copy(startMinute = it)) }
        )
        WeTimeOfDayField(
            modifier = Modifier.weight(1f),
            label = "结束",
            minuteOfDay = rule.endMinute,
            enabled = editable,
            onMinuteChange = { onChange(rule.copy(endMinute = it)) }
        )
    }
}

@Composable
internal fun AutomationKeywordControls(
    rule: AutomationKeywordRule,
    editable: Boolean,
    onChange: (AutomationKeywordRule) -> Unit
) {
    var pendingKeyword by remember { mutableStateOf("") }
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        AutomationKeywordMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = rule.mode == mode,
                enabled = editable,
                onClick = { onChange(rule.copy(mode = mode)) },
                shape = SegmentedButtonDefaults.itemShape(index, AutomationKeywordMode.entries.size)
            ) {
                Text(if (mode == AutomationKeywordMode.STRING_LIST) "字符串列表" else "正则表达式")
            }
        }
    }
    if (rule.mode == AutomationKeywordMode.STRING_LIST) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = pendingKeyword,
                enabled = editable,
                onValueChange = { pendingKeyword = it },
                label = { Text("新关键词") },
                singleLine = true
            )
            Button(
                enabled = editable && pendingKeyword.trim().isNotEmpty(),
                onClick = {
                    val keyword = pendingKeyword.trim()
                    if (keyword !in rule.strings) onChange(rule.copy(strings = rule.strings + keyword))
                    pendingKeyword = ""
                }
            ) { Text("添加") }
        }
        rule.strings.forEach { keyword ->
            ListItem(
                headlineContent = { Text(keyword) },
                trailingContent = {
                    TextButton(
                        enabled = editable,
                        onClick = { onChange(rule.copy(strings = rule.strings - keyword)) }
                    ) { Text("删除") }
                }
            )
        }
    } else {
        val regexError = rule.regex.takeIf(String::isNotBlank)?.let {
            runCatching { Regex(it) }.exceptionOrNull()?.message
        }
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            value = rule.regex,
            enabled = editable,
            onValueChange = { onChange(rule.copy(regex = it)) },
            label = { Text("Regex") },
            supportingText = regexError?.let { error -> { Text(error) } },
            isError = regexError != null,
            singleLine = true
        )
    }
}

@Composable
internal fun AutomationSettingsError(error: String?) {
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun AutomationScrollableColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}


