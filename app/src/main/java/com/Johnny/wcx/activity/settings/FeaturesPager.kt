package com.Johnny.wcx.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Search
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.easter_egg.AprilFools
import com.Johnny.wcx.features.items.easter_egg.isAprilFools
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.Johnny.wcx.ui.content.MiuixSmallTitle
import java.time.LocalDate


// ---------------------------------------------------------------------------
//  Page 1 — Features (search bar + category list)
// ---------------------------------------------------------------------------

@Composable
fun FeaturesPager(onOpenCategory: (String) -> Unit) {
    val showAprilFools = remember { LocalDate.now().isAprilFools }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val searching = query.isNotBlank()

    val searchableItems = remember {
        FeaturesProvider.ALL_HOOK_ITEMS
            .filterIsInstance<SwitchFeature>()
            // 「对话归拢摘要颜色」收进「对话归拢」弹窗内，不再作为独立项目出现在搜索。
            .filterNot { it.name == "对话归拢摘要颜色" }
    }
    val filteredItems = remember(query) {
        if (!searching) emptyList()
        else searchableItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
        }
    }
    val switchStates = remember { mutableStateMapOf<String, Boolean>() }

    // A back press while searching clears the query first (after the IME's own
    // back has dismissed the keyboard) rather than exiting the module settings.
    BackHandler(enabled = searching) { queryState.clearText() }

    MiuixListScaffold(title = "功能") {
        item {
            TextField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = "搜索功能",
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { queryState.clearText() }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "Clear query",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                },
            )
        }

        if (searching) {
            // Search results replace the category list while a query is active
            if (filteredItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "未匹配到任何相关功能",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                itemsIndexed(filteredItems, key = { _, item -> item.name }) { index, item ->
                    Column(
                        modifier = Modifier
                            .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                            .groupedCardItem(index, filteredItems.size),
                    ) {
                        FeatureRow(
                            item = item,
                            checked = switchStates[item.name] ?: WePrefs.getBoolOrFalse(item.name),
                            onCheckedChange = { switchStates[item.name] = it },
                        )
                    }
                }
            }
        } else {
            if (showAprilFools) {
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                    ) {
                        ArrowPreference(
                            title = "🏳",
                            summary = "投降喵投降喵",
                            onClick = {
                                WePrefs.putBool(AprilFools.KEY_SURRENDER, true)
                                CoroutineScope(Dispatchers.Main).launch { showToastSuspend("重启生效") }
                            },
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                ) {
                    FEATURE_CATEGORIES.forEach { (name, icon) ->
                        ArrowPreference(
                            title = name,
                            startAction = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                            onClick = { onOpenCategory(name) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Category detail (replaces CategorySettingsScreen)
// ---------------------------------------------------------------------------

/**
 * 不在功能列表里展示的项（按 [com.Johnny.wcx.features.core.Feature.name] 匹配）。
 *
 * `隐藏联系人` 是历史遗留的中间层：它与「密友名单管理」共用同一份名单，且我们已把它的
 * 入口改成委托密友名单编辑器，于是列表里会出现两个都能改同一份名单的开关，用户无法分辨。
 *
 * 它本身仍有实现价值——22 个隐藏面里密友侧未覆盖的 8 个（音视频通话、摇一摇、
 * 角标计数、拍一拍、收藏、视频号点赞、群成员列表、微信运动）都挂在它的 onEnable 上。
 * 所以做法是**保留 object、隐藏列表项**：开关状态改由
 * [com.Johnny.wcx.features.items.secret_friend.SecretFriendManager] 主控驱动，
 * 这 8 个面随之并入密友总控，用户只需面对一个开关。
 */
internal val HIDDEN_ITEM_NAMES = setOf("隐藏联系人")

@Composable
fun CategoryDetailScreen(categoryName: String, onBack: () -> Unit) {
    val items = remember(categoryName) {
        val all = FeaturesProvider.ALL_HOOK_ITEMS.filter { categoryName in it.categories }
        val filtered = all.filterNot { it.name == "对话归拢摘要颜色" || it.name in HIDDEN_ITEM_NAMES }
        // 诊断：确认过滤逻辑与运行时 name（排查平级行残留）
        WeLogger.i(
            "FeaturesPager",
            "cat=$categoryName before=${all.map { it.name }} after=${filtered.map { it.name }}"
        )
        filtered
    }

    // 分组在 LazyListScope 之外算好：LazyListScope 不是 @Composable 上下文，
    // 不能在其中调用 remember。
    val grouped = remember(categoryName, items) { FeatureGroups.group(items) }

    val switchStates = remember(categoryName) {
        mutableStateMapOf<String, Boolean>().apply {
            items.forEach { put(it.name, WePrefs.getBoolOrFalse(it.name)) }
        }
    }

    MiuixListScaffold(
        title = categoryName,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Arrow_back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        if (items.isEmpty()) return@MiuixListScaffold

        // 按用途分组渲染（见 FeatureGroups）。分组只是展示层排序，不影响功能的启用逻辑。
        grouped.forEachIndexed { groupIndex, (groupTitle, groupItems) ->
            if (groupTitle != null) {
                item(key = "group-header-$groupTitle") {
                    MiuixSmallTitle(
                        text = groupTitle,
                        modifier = Modifier.padding(top = if (groupIndex == 0) 12.dp else 20.dp),
                    )
                }
            }
            itemsIndexed(groupItems, key = { _, item -> item.name }) { index, item ->
                Column(
                    modifier = Modifier
                        .then(
                            if (groupTitle == null && groupIndex == 0 && index == 0) {
                                Modifier.padding(top = 12.dp)
                            } else {
                                Modifier
                            }
                        )
                        .groupedCardItem(index, groupItems.size),
                ) {
                    FeatureRow(
                        item = item,
                        checked = switchStates[item.name] ?: false,
                        onCheckedChange = { switchStates[item.name] = it },
                    )
                    item.Ui()
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
