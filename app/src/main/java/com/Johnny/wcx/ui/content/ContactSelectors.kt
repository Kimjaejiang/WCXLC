package com.Johnny.wcx.ui.content

import android.icu.text.Transliterator
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Compare_arrows
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Select_all
import com.composables.icons.materialsymbols.outlined.Sort_by_alpha
import com.composables.icons.materialsymbols.outlined.Swap_vert
import com.composables.icons.materialsymbols.outlined.Tag
import androidx.compose.ui.platform.LocalContext
import com.Johnny.wcx.features.items.chat.ConversationAggregation
import com.Johnny.wcx.features.items.chat.ConversationGrouping
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.features.api.core.WeContactLabelApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.core.models.WeContact
import com.Johnny.wcx.features.api.core.models.WeGroup
import com.Johnny.wcx.features.api.core.models.WeOfficialAccount
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

enum class FilterType(val displayName: String) {
    ALL("全部"),
    FRIENDS("好友"),
    GROUPS("群聊"),
    OFFICIAL_ACCOUNTS("公众号"),
    OTHERS("其他")
}

enum class SortMode(val displayName: String, val icon: ImageVector) {
    ALPHABETICAL("A-Z", MaterialSymbols.Outlined.Sort_by_alpha),
    LAST_MESSAGE_TIME("新-旧", MaterialSymbols.Outlined.Schedule);

    fun displayName(reversed: Boolean): String = when (this) {
        ALPHABETICAL -> if (reversed) "Z-A" else "A-Z"
        LAST_MESSAGE_TIME -> if (reversed) "旧-新" else "新-旧"
    }
}

/**
 * 联系人筛选维度。三者互斥，同一时刻只按其中一种筛：
 * - LABELS：微信自带标签（永远可用）
 * - AGGREGATION：本模块「对话归拢」的文件夹（该功能未启用时不出现）
 * - GROUPING：本模块「对话分组」的群组（该功能未启用时不出现）
 */
private enum class ContactFilterMode(val icon: ImageVector, val nameRes: String) {
    LABELS(MaterialSymbols.Outlined.Label, "标签"),
    AGGREGATION(MaterialSymbols.Outlined.Folder, "归拢"),
    GROUPING(MaterialSymbols.Outlined.Groups, "分组"),
}

// 非字母分节的 key。用 \u0000 开头是为了不跟真实联系人首字母撞车 ——
// 以前直接用 "已选"/"新-旧" 当 key，虽然当天看不出问题，但那是把「展示文本」
// 当「标识符」用，将来改文案就会连带改行为。
private const val SELECTED_SECTION_KEY = "\u0000selected"
private const val NEWEST_SECTION_KEY = "\u0000newest"
private const val OLDEST_SECTION_KEY = "\u0000oldest"

// 记住用户上次选的筛选维度。默认标签：那是唯一不依赖其它功能开关的维度。
private var persistedContactFilterMode by WePrefs.prefOption(
    "contact_selector_filter_mode",
    ContactFilterMode.LABELS.name,
)

/** 一个可筛选的集合（一个标签 / 一个归拢文件夹 / 一个分组）。 */
private data class ContactFilterOption(
    val id: String,
    val name: String,
    val wxIds: Set<String>,
)

@Composable
fun BaseContactSelector(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filteredContacts: List<IWeContact>,
    allContacts: List<IWeContact> = filteredContacts,
    confirmButtonText: String,
    confirmButtonEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    selectionKey: Any,
    isSelected: (IWeContact) -> Boolean,
    showConfirmButton: Boolean = true,
    dismissButtonText: String = "取消",
    avatarModelProvider: ((IWeContact) -> Any?)? = { it.avatarUrl },
    subtitleProvider: ((IWeContact) -> String)? = { it.wxId },
    leadingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    trailingControl: @Composable (LazyItemScope.(IWeContact) -> Unit)? = null,
    onItemClick: (IWeContact) -> Unit,
    onSelectAll: ((List<IWeContact>) -> Unit)? = null,
    onDeselectAll: ((List<IWeContact>) -> Unit)? = null,
    onInvertSelection: ((List<IWeContact>) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { listOf(SELECTED_SECTION_KEY) + ('A'..'Z').map { it.toString() } + "#" }

    val transliterator = remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    var friendWxIds by remember { mutableStateOf(emptySet<String>()) }
    var groupWxIds by remember { mutableStateOf(emptySet<String>()) }
    var officialAccountWxIds by remember { mutableStateOf(emptySet<String>()) }
    var allLabels by remember { mutableStateOf(emptyList<WeContactLabelApi.ContactLabel>()) }
    var labelContactsMap by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var aggregationOptions by remember { mutableStateOf(emptyList<ContactFilterOption>()) }
    var groupingOptions by remember { mutableStateOf(emptyList<ContactFilterOption>()) }
    var isFiltersLoaded by remember { mutableStateOf(false) }
    val currentLocalizedContext = rememberUpdatedState(LocalContext.current)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (WeDatabaseApi.isReady) {
                    val friends = WeDatabaseApi.getFriends().map { it.wxId }.toSet()
                    val groups = WeDatabaseApi.getGroups().map { it.wxId }.toSet()
                    val officialAccounts = WeDatabaseApi.getOfficialAccounts().map { it.wxId }.toSet()
                    val labels = WeContactLabelApi.getAllLabels()
                    val labelMap = labels.associate { label ->
                        label.labelName to WeContactLabelApi.getContactsByLabelId(label.labelId).toSet()
                    }
                    // 归拢/分组两个维度只在对应功能开着时才有内容 —— 功能没开时
                    // 不收集选项，也就不会在筛选栏里出现一个点了没反应的图标。
                    val aggregation = if (ConversationAggregation.isEnabled) {
                        ConversationAggregation.aggregationFolders().map { folder ->
                            ContactFilterOption(
                                id = folder.id,
                                name = folder.name,
                                wxIds = ConversationAggregation.folderMembers(folder.id).toSet(),
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val grouping = if (ConversationGrouping.isEnabled) {
                        ConversationGrouping.groupFilterOptions(currentLocalizedContext.value).map { group ->
                            ContactFilterOption(group.id, group.name, group.members.toSet())
                        }
                    } else {
                        emptyList()
                    }

                    withContext(Dispatchers.Main) {
                        friendWxIds = friends
                        groupWxIds = groups
                        officialAccountWxIds = officialAccounts
                        allLabels = labels
                        labelContactsMap = labelMap
                        aggregationOptions = aggregation
                        groupingOptions = grouping
                        isFiltersLoaded = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("数据库尚未初始化, 筛选将不可用!")
                        isFiltersLoaded = true
                    }
                }
            } catch (e: Exception) {
                WeLogger.e("ContactSelectors", "Failed to load filters in coroutine", e)
                withContext(Dispatchers.Main) {
                    isFiltersLoaded = true
                }
            }
        }
    }

    var selectedType by remember { mutableStateOf(FilterType.ALL) }
    var selectedLabelName by remember { mutableStateOf<String?>(null) }
    var selectedAggregationId by remember { mutableStateOf<String?>(null) }
    var selectedGroupingId by remember { mutableStateOf<String?>(null) }

    // 上次选的筛选维度可能依赖某个功能开关，而用户可能已经把它关掉了。
    // 那种时候静默退回标签维度 —— 直接显示一个空列表会让用户以为联系人丢了。
    // （选项是否真的有内容要等数据加载完才知道，由下面的 LaunchedEffect 兜底纠正。）
    var filterMode by remember {
        val persistedMode = ContactFilterMode.entries.firstOrNull {
            it.name == persistedContactFilterMode
        }
        mutableStateOf(
            when (persistedMode) {
                ContactFilterMode.AGGREGATION -> if (ConversationAggregation.isEnabled) persistedMode else ContactFilterMode.LABELS
                ContactFilterMode.GROUPING -> if (ConversationGrouping.isEnabled) persistedMode else ContactFilterMode.LABELS
                ContactFilterMode.LABELS, null -> ContactFilterMode.LABELS
            }
        )
    }
    var filtersExpanded by remember { mutableStateOf(true) }

    var sortMode by remember { mutableStateOf(SortMode.LAST_MESSAGE_TIME) }
    var sortReversed by remember { mutableStateOf(false) }
    var lastMessageTimes by remember { mutableStateOf<Map<String, Long>?>(null) }
    var isSortLoading by remember { mutableStateOf(false) }

    // 默认按「新-旧」（最近活跃时间）排序：首次进入自动加载时间数据。
    //
    // 性能说明：改走 rconversation.conversationTime（每个会话一行），
    // 不再对 message 表做 IN + GROUP BY。旧实现下这段查询是选择器
    // 「先按传入顺序显示 → 时间回来后突然重排」的根因，重排又让整个列表
    // 重新组合、头像重新加载，表现为「刷新时间有点长 + 头像缺失」。
    val currentFiltered = rememberUpdatedState(filteredContacts)
    LaunchedEffect(Unit) {
        if (sortMode == SortMode.LAST_MESSAGE_TIME && lastMessageTimes == null && !isSortLoading) {
            isSortLoading = true
            try {
                var times: Map<String, Long>? = null
                repeat(5) { // 数据库未就绪时最多等约 2.5 秒（正常开机后立即就绪）
                    val list = currentFiltered.value
                    if (WeDatabaseApi.isReady && list.isNotEmpty()) {
                        times = withContext(Dispatchers.IO) {
                            WeDatabaseApi.getConversationTimesFor(list.map { it.wxId })
                        }
                        if (times != null) return@repeat
                    }
                    delay(500)
                }
                if (times != null) lastMessageTimes = times
            } finally {
                isSortLoading = false
            }
        }
    }

    fun switchSortMode(target: SortMode) {
        if (target == sortMode || isSortLoading) return
        if (target == SortMode.ALPHABETICAL) {
            sortMode = SortMode.ALPHABETICAL
            return
        }
        // 切换到按最近消息时间排序
        if (lastMessageTimes != null) {
            sortMode = SortMode.LAST_MESSAGE_TIME
            return
        }
        isSortLoading = true
        coroutineScope.launch {
            val times = withContext(Dispatchers.IO) {
                if (WeDatabaseApi.isReady) WeDatabaseApi.getLastMessageTimes() else null
            }
            if (times == null) {
                showToast("数据库尚未初始化, 无法按时间排序!")
            } else {
                lastMessageTimes = times
                sortMode = SortMode.LAST_MESSAGE_TIME
            }
            isSortLoading = false
        }
    }

    val typeCounts = remember(filteredContacts, friendWxIds, groupWxIds, officialAccountWxIds) {
        var friends = 0
        var groups = 0
        var officialAccounts = 0
        var others = 0
        for (contact in filteredContacts) {
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0
            when {
                isGroup -> groups++
                isOfficial -> officialAccounts++
                isFriend -> friends++
                else -> others++
            }
        }
        mapOf(
            FilterType.ALL to filteredContacts.size,
            FilterType.FRIENDS to friends,
            FilterType.GROUPS to groups,
            FilterType.OFFICIAL_ACCOUNTS to officialAccounts,
            FilterType.OTHERS to others
        )
    }

    // Row visibility is decided from the full contact list so that filter rows do not
    // disappear while a search query is active (the filters still apply, hiding them is confusing).
    val allTypeCounts = remember(allContacts, friendWxIds, groupWxIds, officialAccountWxIds) {
        var friends = 0
        var groups = 0
        var officialAccounts = 0
        var others = 0
        for (contact in allContacts) {
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0
            when {
                isGroup -> groups++
                isOfficial -> officialAccounts++
                isFriend -> friends++
                else -> others++
            }
        }
        mapOf(
            FilterType.ALL to allContacts.size,
            FilterType.FRIENDS to friends,
            FilterType.GROUPS to groups,
            FilterType.OFFICIAL_ACCOUNTS to officialAccounts,
            FilterType.OTHERS to others
        )
    }

    val availableTypes = remember(allTypeCounts) {
        FilterType.entries.filter { type ->
            type == FilterType.ALL || allTypeCounts[type] ?: 0 > 0
        }
    }
    val showTypeFilterRow = remember(availableTypes, isFiltersLoaded) { isFiltersLoaded && availableTypes.size > 2 }

    // 本地头像文件解析会做 MD5 + 目录 listFiles（磁盘 IO）。它处在 Compose 渲染路径上，
    // 滚动列表时同一个 wxId 会被反复求值 —— 不缓存的话每次重组都去碰磁盘，
    // 正是「头像加载拖慢滑动」这类卡的来源。按 wxId 记缓存，
    // null 也缓存（代表「确实没有」），否则没有头像的人会一直重试。
    //
    // key 只用 wxId：不能用含 width 之类渲染期会变的量，那样缓存永不命中，
    // 等于没缓存。
    val localAvatarCache = remember { mutableMapOf<String, java.io.File?>() }
    val localAvatarResolver: (String) -> java.io.File? = remember {
        { wxId -> localAvatarCache.getOrPut(wxId) { WeDatabaseApi.getLocalAvatarFile(wxId) } }
    }


    val labelCounts = remember(filteredContacts, labelContactsMap) {
        labelContactsMap.mapValues { (_, wxIds) ->
            filteredContacts.count { it.wxId in wxIds }
        }
    }
    val availableLabels = remember(allContacts, allLabels, labelContactsMap) {
        allLabels.filter { label ->
            val wxIds = labelContactsMap[label.labelName] ?: emptySet()
            allContacts.any { it.wxId in wxIds }
        }
    }
    // 可选的归拢/分组选项：只列出当前联系人里真的有人属于的那些，
    // 否则筛选栏会堆满点了没结果（或结果为空）的选项。
    val availableAggregationOptions = remember(allContacts, aggregationOptions) {
        aggregationOptions.filter { option -> allContacts.any { it.wxId in option.wxIds } }
    }
    val availableGroupingOptions = remember(allContacts, groupingOptions) {
        groupingOptions.filter { option -> allContacts.any { it.wxId in option.wxIds } }
    }

    // 筛选维度行什么时候出现：等筛选数据读完再决定，否则会先闪一下
    // 只剩「标签」、数据回来后突然多出两个图标。
    //
    // 归拢/分组还要求「有选项」而不只是「功能开着」：空文件夹/空分组
    // 列表里一个可选项都没有，循环切换到那里只会得到一个空列表。
    val availableFilterModes = remember(
        isFiltersLoaded, availableAggregationOptions, availableGroupingOptions
    ) {
        if (!isFiltersLoaded) emptyList()
        else ContactFilterMode.entries.filter { mode ->
            when (mode) {
                ContactFilterMode.LABELS -> true
                ContactFilterMode.AGGREGATION ->
                    ConversationAggregation.isEnabled && availableAggregationOptions.isNotEmpty()
                ContactFilterMode.GROUPING ->
                    ConversationGrouping.isEnabled && availableGroupingOptions.isNotEmpty()
            }
        }
    }
    val showFilterModeRow = availableFilterModes.isNotEmpty()

    // 恢复上次选的维度必须等数据读完才能定：初始化时 aggregationOptions 还是空的，
    // isFiltersLoaded 也是 false，此刻按 isEnabled 判断会把「归拢开着但选项还没加载」
    // 误判成「可用」，chip 显示「归拢」而列表却是空的。
    // 数据到位后如果该维度真的没有内容，就退回标签，不让用户卡在空列表上。
    LaunchedEffect(isFiltersLoaded, availableFilterModes) {
        if (!isFiltersLoaded) return@LaunchedEffect
        val options = if (filterMode == ContactFilterMode.AGGREGATION) {
            availableAggregationOptions
        } else {
            availableGroupingOptions
        }
        if (filterMode != ContactFilterMode.LABELS && options.isEmpty()) {
            filterMode = ContactFilterMode.LABELS
        }
    }

    val displayedContacts = remember(
        filteredContacts, selectedType, selectedLabelName, filterMode,
        selectedAggregationId, selectedGroupingId,
        friendWxIds, groupWxIds, officialAccountWxIds, labelContactsMap,
        aggregationOptions, groupingOptions,
    ) {
        filteredContacts.filter { contact ->
            val isGroup = contact is WeGroup || contact.wxId.endsWith("@chatroom") || contact.wxId in groupWxIds
            val isOfficial = contact is WeOfficialAccount || contact.wxId.startsWith("gh_") || contact.wxId in officialAccountWxIds
            val isFriend = contact.wxId in friendWxIds || contact is WeContact && !isGroup && !isOfficial && contact.type and 1 != 0

            val matchesType = when (selectedType) {
                FilterType.ALL -> true
                FilterType.FRIENDS -> isFriend
                FilterType.GROUPS -> isGroup
                FilterType.OFFICIAL_ACCOUNTS -> isOfficial
                FilterType.OTHERS -> !isFriend && !isGroup && !isOfficial
            }

            // 没选具体集合时该维度不设限（等于「全部」），与类型筛选的 ALL 一致。
            val matchesMode = when (filterMode) {
                ContactFilterMode.LABELS ->
                    selectedLabelName == null || contact.wxId in (labelContactsMap[selectedLabelName] ?: emptySet())

                ContactFilterMode.AGGREGATION ->
                    selectedAggregationId == null ||
                            contact.wxId in (aggregationOptions.firstOrNull { it.id == selectedAggregationId }?.wxIds ?: emptySet())

                ContactFilterMode.GROUPING ->
                    selectedGroupingId == null ||
                            contact.wxId in (groupingOptions.firstOrNull { it.id == selectedGroupingId }?.wxIds ?: emptySet())
            }

            matchesType && matchesMode
        }
    }

    val groupedContacts = remember(displayedContacts, transliterator, selectionKey, sortMode, sortReversed, lastMessageTimes) {
        if (sortMode == SortMode.LAST_MESSAGE_TIME) {
            val times = lastMessageTimes ?: emptyMap()
            val sorted = if (sortReversed) {
                displayedContacts.sortedBy { times[it.wxId] ?: Long.MIN_VALUE }
            } else {
                displayedContacts.sortedByDescending { times[it.wxId] ?: Long.MIN_VALUE }
            }
            val (selected, rest) = sorted.partition { isSelected(it) }
            linkedMapOf<String, List<IWeContact>>().apply {
                if (selected.isNotEmpty()) put(SELECTED_SECTION_KEY, selected)
                if (rest.isNotEmpty()) put(if (sortReversed) OLDEST_SECTION_KEY else NEWEST_SECTION_KEY, rest)
            }
        } else {
            displayedContacts.groupBy { contact ->
                if (isSelected(contact)) {
                    SELECTED_SECTION_KEY
                } else {
                    val name = contact.displayName.trim()
                    if (name.isEmpty()) return@groupBy "#"

                    val firstChar = name.first()
                    if (firstChar.uppercaseChar() in 'A'..'Z') {
                        firstChar.uppercaseChar().toString()
                    } else if (transliterator != null) {
                        // safe to ignore since transliterator is null when SDK too low
                        val pinyin = transliterator.transliterate(firstChar.toString())
                        val initial = pinyin.firstOrNull()?.uppercaseChar() ?: '#'
                        if (initial in 'A'..'Z') initial.toString() else "#"
                    } else {
                        "#"
                    }
                }
            }.toSortedMap { c1, c2 ->
                when {
                    c1 == c2 -> 0
                    c1 == SELECTED_SECTION_KEY -> -1
                    c2 == SELECTED_SECTION_KEY -> 1
                    c1 == "#" -> 1
                    c2 == "#" -> -1
                    else -> if (sortReversed) c2.compareTo(c1) else c1.compareTo(c2)
                }
            } as Map<String, List<IWeContact>>
        }
    }

    val sectionIndices = remember(groupedContacts) {
        val mapping = mutableMapOf<String, Int>()
        var currentFlatIndex = 0
        groupedContacts.forEach { (letter, contactsInGroup) ->
            mapping[letter] = currentFlatIndex
            currentFlatIndex += 1
            currentFlatIndex += contactsInGroup.size
        }
        mapping
    }

    AlertDialogContent(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索昵称或微信号") },
                        leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = "Search") },
                        singleLine = true
                    )
                    IconButton(onClick = { filtersExpanded = !filtersExpanded }) {
                        Icon(
                            imageVector = if (filtersExpanded) {
                                MaterialSymbols.Outlined.Expand_less
                            } else {
                                MaterialSymbols.Outlined.Expand_more
                            },
                            contentDescription = if (filtersExpanded) "折叠筛选" else "展开筛选"
                        )
                    }
                }

                AnimatedVisibility(
                    visible = filtersExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (showTypeFilterRow) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                items(availableTypes) { type ->
                                    val isSelected = selectedType == type
                                    val count = typeCounts[type] ?: 0
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedType = type },
                                        label = { Text("${type.displayName} ($count)") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when (type) {
                                                    FilterType.ALL -> MaterialSymbols.Outlined.Search
                                                    FilterType.FRIENDS -> MaterialSymbols.Outlined.Person
                                                    FilterType.GROUPS -> MaterialSymbols.Outlined.Groups
                                                    FilterType.OFFICIAL_ACCOUNTS -> MaterialSymbols.Outlined.Chat
                                                    FilterType.OTHERS -> MaterialSymbols.Outlined.Tag
                                                },
                                                contentDescription = type.displayName,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        if (showFilterModeRow) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 单个图标循环切换维度：点击时在「标签/归拢/分组」间轮流，
                                // 并记住选择。三者只能选一个，所以做成一个循环按钮而非三个。
                                item {
                                    val modeIndex = availableFilterModes.indexOf(filterMode).coerceAtLeast(0)
                                    val hasMoreModes = availableFilterModes.size > 1
                                    FilterChip(
                                        selected = true,
                                        onClick = {
                                            if (!hasMoreModes) {
                                                showToast(
                                                    "可启用「对话归拢」或「对话分组」以使用更多筛选方式"
                                                )
                                            } else {
                                                filterMode = availableFilterModes[(modeIndex + 1) % availableFilterModes.size]
                                                persistedContactFilterMode = filterMode.name
                                                // 切换维度时清掉旧的选中项，否则会在新维度下
                                                // 拿旧 id 去比，列表直接空掉。
                                                selectedLabelName = null
                                                selectedAggregationId = null
                                                selectedGroupingId = null
                                            }
                                        },
                                        label = { Text(filterMode.nameRes) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = filterMode.icon,
                                                contentDescription = filterMode.nameRes,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }

                                if (filterMode == ContactFilterMode.LABELS) {
                                    item {
                                        val isSelected = selectedLabelName == null
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedLabelName = null },
                                            label = { Text("全部") }
                                        )
                                    }

                                    items(availableLabels) { label ->
                                        val isSelected = selectedLabelName == label.labelName
                                        val labelCount = labelCounts[label.labelName] ?: 0
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedLabelName = if (isSelected) null else label.labelName },
                                            label = { Text("${label.labelName} ($labelCount)") }
                                        )
                                    }
                                } else {
                                    val options = if (filterMode == ContactFilterMode.AGGREGATION) {
                                        availableAggregationOptions
                                    } else {
                                        availableGroupingOptions
                                    }
                                    item {
                                        val isSelected = if (filterMode == ContactFilterMode.AGGREGATION) {
                                            selectedAggregationId == null
                                        } else {
                                            selectedGroupingId == null
                                        }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (filterMode == ContactFilterMode.AGGREGATION) {
                                                    selectedAggregationId = null
                                                } else {
                                                    selectedGroupingId = null
                                                }
                                            },
                                            label = { Text("全部") },
                                        )
                                    }
                                    items(options, key = { it.id }) { option ->
                                        val selectedId = if (filterMode == ContactFilterMode.AGGREGATION) selectedAggregationId else selectedGroupingId
                                        val isSelected = selectedId == option.id
                                        val count = filteredContacts.count { it.wxId in option.wxIds }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (filterMode == ContactFilterMode.AGGREGATION) {
                                                    selectedAggregationId = if (isSelected) null else option.id
                                                } else {
                                                    selectedGroupingId = if (isSelected) null else option.id
                                                }
                                            },
                                            label = { Text("${option.name} ($count)") },
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .padding(bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SortMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = sortMode == mode,
                                    enabled = !isSortLoading,
                                    onClick = { switchSortMode(mode) },
                                    label = { Text(mode.displayName(sortReversed)) },
                                    leadingIcon = {
                                        if (isSortLoading && mode == SortMode.LAST_MESSAGE_TIME) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = mode.icon,
                                                contentDescription = mode.displayName(sortReversed),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }

                            FilterChip(
                                selected = sortReversed,
                                enabled = !isSortLoading,
                                onClick = { sortReversed = !sortReversed },
                                label = {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Swap_vert,
                                        contentDescription = "切换排序方向",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }

                        if (onSelectAll != null || onDeselectAll != null || onInvertSelection != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                                    .padding(bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                onSelectAll?.let {
                                    FilterChip(
                                        selected = false,
                                        onClick = { it(displayedContacts) },
                                        label = { Text("全选") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Select_all,
                                                contentDescription = "全选",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                onDeselectAll?.let {
                                    FilterChip(
                                        selected = false,
                                        onClick = { it(displayedContacts) },
                                        label = { Text("全不选") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Deselect,
                                                contentDescription = "全不选",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                                onInvertSelection?.let {
                                    FilterChip(
                                        selected = false,
                                        onClick = { it(displayedContacts) },
                                        label = { Text("反选") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Compare_arrows,
                                                contentDescription = "反选",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                if (displayedContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "无匹配的联系人",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            groupedContacts.forEach { (letter, contactsInGroup) ->
                                stickyHeader(key = "header_$letter") {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            text = when (letter) {
                                                SELECTED_SECTION_KEY -> "已选"
                                                NEWEST_SECTION_KEY -> "新-旧"
                                                OLDEST_SECTION_KEY -> "旧-新"
                                                else -> letter
                                            },
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                items(
                                    items = contactsInGroup,
                                    key = { it.wxId }
                                ) { contact ->
                                    Row(
                                        modifier = Modifier
                                            .animateItem()
                                            .fillMaxWidth()
                                            .clickable { onItemClick(contact) }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (leadingControl != null) {
                                            leadingControl(contact)
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }

                                        AsyncImage(
                                            // 头像回退链：调用方自定义 → contact.avatarUrl → 微信本地缓存文件。
                                            //
                                            // 最后一环是必需的：部分入口传入的 IWeContact 根本没有
                                            // avatarUrl（如自定义好友头像里的 SimpleContact 恒为空串），
                                            // 而 image_flag.reserved2 也只对「微信展示过」的账号才有值 ——
                                            // 两者都会让整列头像空白。微信其实已把头像缓存在
                                            // avatar/xx/yy/<md5>/user_*.png，直接给路径让 AsyncImage 去读。
                                            // 空字符串同样是无效 model（会让 AsyncImage 显示空白），
                                            // 一并归为 null，交给 Coil 的占位处理。
                                            // 注意不能用 `?:` 串：provider 和 avatarUrl 都可能是**空串**而不是
                                            // null，`?:` 会直接选它并停在那一环，永远走不到本地文件。
                                            // 所以逐环判断「非空白」。
                                            model = run {
                                                val fromProvider = avatarModelProvider?.invoke(contact)
                                                val usable = { candidate: Any? ->
                                                    candidate != null && (candidate !is String || candidate.isNotBlank())
                                                }
                                                when {
                                                    usable(fromProvider) -> fromProvider
                                                    usable(contact.avatarUrl) -> contact.avatarUrl
                                                    else -> localAvatarResolver(contact.wxId)
                                                }
                                            },
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                            imageLoader = GlobalImageLoader
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.displayName,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = subtitleProvider?.invoke(contact) ?: contact.wxId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (trailingControl != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            trailingControl(contact)
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(start = 8.dp, end = 4.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val displayAlphabet = if (sortReversed) {
                                listOf(SELECTED_SECTION_KEY) + ('A'..'Z').map { it.toString() }.reversed() + "#"
                            } else {
                                alphabet
                            }
                            if (sortMode == SortMode.ALPHABETICAL) displayAlphabet.forEach { letter ->
                                val isAvailable = groupedContacts.containsKey(letter)
                                Text(
                                    text = if (letter == SELECTED_SECTION_KEY) "✓" else letter,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isAvailable) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    },
                                    modifier = Modifier
                                        .clickable {
                                            val targetIndex = if (letter == SELECTED_SECTION_KEY) {
                                                sectionIndices[SELECTED_SECTION_KEY]
                                            } else {
                                                val letterKeys = sectionIndices.keys.filter { it != SELECTED_SECTION_KEY }
                                                val targetLetter = if (sortReversed) {
                                                    letterKeys.firstOrNull { it.first() <= letter.first() }
                                                } else {
                                                    letterKeys.firstOrNull { it.first() >= letter.first() }
                                                } ?: sectionIndices.keys.lastOrNull()
                                                targetLetter?.let { sectionIndices[it] }
                                            }
                                            targetIndex?.let { index ->
                                                coroutineScope.launch {
                                                    listState.scrollToItem(index)
                                                }
                                            }
                                        }
                                        .padding(vertical = 2.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(dismissButtonText) }
        },
        confirmButton = if (showConfirmButton) {
            {
                Button(
                    onClick = onConfirm,
                    enabled = confirmButtonEnabled
                ) {
                    Text(confirmButtonText)
                }
            }
        } else null
    )
}

@Composable
fun SingleContactSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxId by remember { mutableStateOf(initialSelectedWxId) }

    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

    val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "确定",
        confirmButtonEnabled = selectedWxId != null,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxId!!) },
        selectionKey = selectedWxId ?: "",
        isSelected = { it.wxId == selectedWxId },
        leadingControl = { contact ->
            RadioButton(
                selected = contact.wxId == selectedWxId,
                onClick = null
            )
        },
        onItemClick = { contact ->
            selectedWxId = contact.wxId
        }
    )
}

@Composable
fun ContactsSelector(
    title: String,
    contacts: List<IWeContact>,
    initialSelectedWxIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedWxIds by remember { mutableStateOf(initialSelectedWxIds) }

    val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

    val filteredContacts = remember(searchQuery, contacts, chinaCollator) {
        contacts.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.wxId.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<IWeContact> { it.displayName.isBlank() }
                .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
        )
    }

    BaseContactSelector(
        title = title,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        filteredContacts = filteredContacts,
        allContacts = contacts,
        confirmButtonText = "确定 (${selectedWxIds.size})",
        confirmButtonEnabled = true,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(selectedWxIds) },
        selectionKey = selectedWxIds,
        isSelected = { it.wxId in selectedWxIds },
        leadingControl = { contact ->
            Checkbox(
                checked = contact.wxId in selectedWxIds,
                onCheckedChange = null
            )
        },
        onItemClick = { contact ->
            selectedWxIds = if (contact.wxId in selectedWxIds) {
                selectedWxIds - contact.wxId
            } else {
                selectedWxIds + contact.wxId
            }
        },
        onSelectAll = { displayed ->
            selectedWxIds = selectedWxIds + displayed.map { it.wxId }
        },
        onDeselectAll = { displayed ->
            selectedWxIds = selectedWxIds - displayed.map { it.wxId }.toSet()
        },
        onInvertSelection = { displayed ->
            val displayedWxIds = displayed.map { it.wxId }.toSet()
            val newSelection = selectedWxIds.toMutableSet()
            for (wxId in displayedWxIds) {
                if (wxId in newSelection) {
                    newSelection.remove(wxId)
                } else {
                    newSelection.add(wxId)
                }
            }
            selectedWxIds = newSelection
        }
    )
}
