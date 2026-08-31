package com.Johnny.wcx.features.items.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.WeColorField
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 归拢摘要配色设置项，与「对话归拢」在设置列表中同级展示。
 *
 * 读写与 [ConversationAggregation] 完全相同的 WePrefs 键（agg_mention_* / agg_folder_*），
 * 互不依赖对方是否启用；保存即生效，重启微信生效。
 *
 * 每个颜色项都带「预设色」展开菜单（DropdownMenu）：点击直接套用预设色值；
 * 也可在 WeColorField 里输入自定义 hex，或点色块打开完整 HSV 选择器。
 */
@Feature(
    name = "对话归拢摘要颜色",
    categories = ["聊天"],
    description = "归拢摘要配色：[@全体]/[有人@我]、[N个聊天]/[N个消息]、[自己]、群成员提及、文件夹标题\n与「对话归拢」同级展示"
)
object ConversationAggregationColors : ClickableFeature() {

    /** 与 @Feature(name=...) 一致；染色逻辑按此 pref 键判断开关（隐藏该行后仍可读写）。 */
    const val ENABLED_PREF_KEY = "对话归拢摘要颜色"

    private const val DEFAULT_AT_COLOR = "#FF2E78E6"
    private const val DEFAULT_COUNT_COLOR = "#FFF2D200"
    private const val DEFAULT_SELF_COLOR = "#FF222222"
    private const val DEFAULT_MEMBER_COLOR = "#FFE8E8E8"
    private const val DEFAULT_TITLE_COLOR = "#FFFF8800"

    private var mentionAtColor by WePrefs.prefOption("agg_mention_at_color", DEFAULT_AT_COLOR)
    private var mentionCountColor by WePrefs.prefOption("agg_mention_count_color", DEFAULT_COUNT_COLOR)
    private var mentionSelfColor by WePrefs.prefOption("agg_mention_self_color", DEFAULT_SELF_COLOR)
    private var mentionMemberColor by WePrefs.prefOption("agg_mention_member_color", DEFAULT_MEMBER_COLOR)
    private var folderTitleColor by WePrefs.prefOption("agg_folder_title_color", DEFAULT_TITLE_COLOR)
    private var folderTitleEnabled by WePrefs.prefOption("agg_folder_title_enabled", true)
    private var mentionSelfEnabled by WePrefs.prefOption("agg_mention_self_enabled", true)
    private var mentionMemberEnabled by WePrefs.prefOption("agg_mention_member_enabled", true)

    /** 预设色板（展开菜单内容）：名称 -> #AARRGGBB，与 WeColorField 的保存格式一致。 */
    private val PRESET_COLORS = listOf(
        "红色" to "#FFFF3B30",
        "橙色" to "#FFFF9500",
        "黄色" to "#FFFFCC00",
        "绿色" to "#FF34C759",
        "蓝色" to "#FF007AFF",
        "紫色" to "#FFAF52DE",
        "灰色" to "#FF8E8E93",
        "黑色" to "#FF000000",
        "白色" to "#FFFFFFFF"
    )

    override fun onClick(context: ComponentActivity) {
        showColorSettingsDialog(context)
    }

    /**
     * 实时颜色设置内容（改动即写 pref、立即生效），供「对话归拢」弹窗的「摘要颜色」
     * 展开区嵌入。总开关关闭后染色逻辑（渲染时读 pref）直接恢复微信默认灰色摘要。
     */
    @Composable
    fun ColorSettingsExpandedContent() {
        var colorsOn by remember { mutableStateOf(WePrefs.getBoolOrFalse(ENABLED_PREF_KEY)) }
        var atColor by remember { mutableStateOf(mentionAtColor) }
        var countColor by remember { mutableStateOf(mentionCountColor) }
        var selfColor by remember { mutableStateOf(mentionSelfColor) }
        var memberColor by remember { mutableStateOf(mentionMemberColor) }
        var titleColor by remember { mutableStateOf(folderTitleColor) }
        var titleEnabled by remember { mutableStateOf(folderTitleEnabled) }
        var selfEnabled by remember { mutableStateOf(mentionSelfEnabled) }
        var memberEnabled by remember { mutableStateOf(mentionMemberEnabled) }

        // 染色在渲染时读 pref，改动后需刷新会话列表才会重新渲染。连续输入合并为一次刷新。
        val scope = rememberCoroutineScope()
        var refreshJob by remember { mutableStateOf<Job?>(null) }
        fun refreshSoon() {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                delay(400)
                runCatching { WeConversationApi.reloadConversations() }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "摘要颜色染色",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "关闭后恢复微信默认灰色摘要",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = colorsOn,
                    onCheckedChange = {
                        colorsOn = it
                        WePrefs.putBool(ENABLED_PREF_KEY, it)
                        refreshSoon()
                    }
                )
            }
            ColorItem(label = "[@全体]/[有人@我]", value = atColor, onValueChange = { atColor = it; mentionAtColor = it; refreshSoon() })
            ColorItem(label = "[N个聊天]/[N个消息]", value = countColor, onValueChange = { countColor = it; mentionCountColor = it; refreshSoon() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("[自己]", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = selfEnabled, onCheckedChange = { selfEnabled = it; mentionSelfEnabled = it; refreshSoon() })
            }
            if (selfEnabled) {
                ColorItem(label = "[自己]", value = selfColor, onValueChange = { selfColor = it; mentionSelfColor = it; refreshSoon() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("(群成员)", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = memberEnabled, onCheckedChange = { memberEnabled = it; mentionMemberEnabled = it; refreshSoon() })
            }
            if (memberEnabled) {
                ColorItem(label = "(群成员)", value = memberColor, onValueChange = { memberColor = it; mentionMemberColor = it; refreshSoon() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("文件夹标题染色", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = titleEnabled, onCheckedChange = { titleEnabled = it; folderTitleEnabled = it; refreshSoon() })
            }
            if (titleEnabled) {
                ColorItem(label = "文件夹标题", value = titleColor, onValueChange = { titleColor = it; folderTitleColor = it; refreshSoon() })
            }
        }
    }

    /** 摘要颜色设置弹窗（供「对话归拢」行下方子项复用）。 */
    fun showColorSettingsDialog(context: Context) {
        showComposeDialog(context) {
            var atColor by remember { mutableStateOf(mentionAtColor) }
            var countColor by remember { mutableStateOf(mentionCountColor) }
            var selfColor by remember { mutableStateOf(mentionSelfColor) }
            var memberColor by remember { mutableStateOf(mentionMemberColor) }
            var titleColor by remember { mutableStateOf(folderTitleColor) }
            var titleEnabled by remember { mutableStateOf(folderTitleEnabled) }
            var selfEnabled by remember { mutableStateOf(mentionSelfEnabled) }
            var memberEnabled by remember { mutableStateOf(mentionMemberEnabled) }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("对话归拢摘要颜色") },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            ColorItem(label = "[@全体]/[有人@我]", value = atColor, onValueChange = { atColor = it })
                        }
                        item {
                            ColorItem(label = "[N个聊天]/[N个消息]", value = countColor, onValueChange = { countColor = it })
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("[自己]", modifier = Modifier.weight(1f))
                                Switch(checked = selfEnabled, onCheckedChange = { selfEnabled = it })
                            }
                            if (selfEnabled) {
                                ColorItem(label = "[自己]", value = selfColor, onValueChange = { selfColor = it })
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("(群成员)", modifier = Modifier.weight(1f))
                                Switch(checked = memberEnabled, onCheckedChange = { memberEnabled = it })
                            }
                            if (memberEnabled) {
                                ColorItem(label = "(群成员)", value = memberColor, onValueChange = { memberColor = it })
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("文件夹标题染色", modifier = Modifier.weight(1f))
                                Switch(checked = titleEnabled, onCheckedChange = { titleEnabled = it })
                            }
                            if (titleEnabled) {
                                ColorItem(label = "文件夹标题", value = titleColor, onValueChange = { titleColor = it })
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } },
                confirmButton = {
                    Button(onClick = {
                        mentionAtColor = atColor
                        mentionCountColor = countColor
                        mentionSelfColor = selfColor
                        mentionMemberColor = memberColor
                        folderTitleColor = titleColor
                        folderTitleEnabled = titleEnabled
                        mentionSelfEnabled = selfEnabled
                        mentionMemberEnabled = memberEnabled
                        // 染色在渲染时读取 pref，保存后刷新会话列表即可立即生效，无需重启。
                        runCatching { WeConversationApi.reloadConversations() }
                        showToast(context, "已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    /**
     * 单个颜色项：「预设色」展开菜单（右对齐）+ hex 输入（label 只在输入框显示一次，
     * 避免与展开菜单行重复）。
     */
    @Composable
    private fun ColorItem(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        enabled: Boolean = true
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                TextButton(onClick = { menuOpen = true }, enabled = enabled) { Text("预设色") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    PRESET_COLORS.forEach { (name, hex) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                onValueChange(hex)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
            WeColorField(label = label, value = value, onValueChange = onValueChange, enabled = enabled)
        }
    }
}
