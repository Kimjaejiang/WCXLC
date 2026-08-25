package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.children
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_box
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_drop_down
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Attach_money
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Mail
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Redeem
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Smart_toy
import com.composables.icons.materialsymbols.outlined.Video_chat
import com.composables.icons.materialsymbols.outlined.Voice_chat
import com.tencent.mm.pluginsdk.ui.chat.AppPanel
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.agent.WeAgentService
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.system.agent.WeAgentOverlayController
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.InjectedUiTheme
import com.Johnny.wcx.ui.utils.LifecycleOwnerProvider
import com.Johnny.wcx.ui.utils.findViewByChildIndexes
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.iterable
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.constructor
import com.Johnny.wcx.utils.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private enum class ToolbarDisplayMode(val preferenceValue: String, val label: String) {
    ICON_AND_TEXT("icon_and_text", "图标+文字"),
    ICON_ONLY("icon_only", "仅图标"),
    TEXT_ONLY("text_only", "仅文字");

    companion object {
        fun fromPreference(value: String): ToolbarDisplayMode =
            entries.firstOrNull { it.preferenceValue == value } ?: ICON_AND_TEXT
    }
}

@SuppressLint("StaticFieldLeak")
@Feature(name = "聊天工具栏", categories = ["聊天"], description = "在输入框上方添加工具栏")
object ChatToolbar : ClickableFeature(), IResolveDex {

    private const val TAG = "ChatToolbar"

    private val NAME_TO_ICON_MAP = mapOf(
        "相册" to MaterialSymbols.Outlined.Photo_library,
        "拍摄" to MaterialSymbols.Outlined.Camera,
        "系统拍摄" to MaterialSymbols.Outlined.Camera,
        "视频通话" to MaterialSymbols.Outlined.Video_chat,
        "语音通话" to MaterialSymbols.Outlined.Voice_chat,
        "位置" to MaterialSymbols.Outlined.Location_on,
        "红包" to MaterialSymbols.Outlined.Mail,
        "礼物" to MaterialSymbols.Outlined.Redeem,
        "转账" to MaterialSymbols.Outlined.Attach_money,
        "语音输入" to MaterialSymbols.Outlined.Mic,
        "收藏" to MaterialSymbols.Outlined.Favorite,
        "接龙" to MaterialSymbols.Outlined.Format_list_numbered,
        "文件" to MaterialSymbols.Outlined.Attach_file,
        "个人名片" to MaterialSymbols.Outlined.Account_box,
        "音乐" to MaterialSymbols.Outlined.Music_note
    )

    // 快捷回复 and WeAgent are wekit-injected items (not backed by a WeChat grid tool), so they
    // live outside NAME_TO_ICON_MAP. Their icons are resolved via iconFor().
    private const val QUICK_REPLY_NAME = "快捷回复"
    private const val WEAGENT_NAME = "WeAgent"

    private val methodAppPanelInitAppGrid by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.chat.AppPanel"
            usingEqStrings("MicroMsg.AppPanel", "initAppGrid()")
        }
    }
    private val methodAppPanelOnMeasure by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings(
                "MicroMsg.AppPanel",
                "onMeasure width: %d, heigth:%d, isMeasured:%b, gridWidth:%d, gridHeight:%d"
            )
        }
    }

    private data class MenuItem(
        val name: String,
        val onClickListener: AdapterView.OnItemClickListener,
        val onLongClickListener: AdapterView.OnItemLongClickListener,
        val appPanel: WeakReference<AppPanel>,
        val gridView: WeakReference<GridView>,
        val itemView: WeakReference<View>,
        val indexInGrid: Int
    )

    private data class QuickReplyDraft(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
    )

    /** 触发微信工具。
     * 1) 第一屏工具（相册/拍摄/视频通话/位置/红包/转账等）：在含相册的第一屏 grid 中按名字
     *    动态定位 position（微信 listener 按 position 语义触发，与 view/tag 无关）。
     * 2) 收藏：不在第一屏，listener 无法触发，改走微信全局收藏页。
     * 3) 其余更多页工具：按名字实时匹配（尽力而为，微信 listener 可能不支持）。 */
    private fun clickTool(appPanel: AppPanel, name: String, default: MenuItem) {
        val grids = mutableListOf<GridView>()
        fun collectGrids(r: View) {
            if (r is GridView) grids.add(r)
            if (r is ViewGroup) for (i in 0 until r.childCount) collectGrids(r.getChildAt(i))
        }
        collectGrids(appPanel)

        // 第一屏 grid（含相册）优先，保证 grid 与 position 语义一致
        val firstScreen = grids.firstOrNull { g ->
            g.isAttachedToWindow && runCatching {
                (0 until (g.adapter?.count ?: 0)).any { i -> extractItemName(g.adapter!!.getView(i, null, g)) == "相册" }
            }.getOrDefault(false)
        }

        // 优先在 firstScreen（含相册的第一屏 grid）中按名字动态定位 position —— 与微信 listener 的
        // position 语义一致，避免硬编码映射在版本/顺序变化时错位（如语音通话无独立格子）。
        val grid = firstScreen ?: grids.firstOrNull { it.isAttachedToWindow }
            ?: default.gridView.get() ?: return
        val adapter = grid.adapter ?: return
        val position = (0 until adapter.count).firstOrNull { idx ->
            extractItemName(adapter.getView(idx, null, grid)) == name
        }
        if (position != null) {
            val liveView = adapter.getView(position, null, grid)
            WeLogger.i(TAG, "CLICK-MAP name=" + name + " pos=" + position + " gridCount=" + adapter.count)
            default.onClickListener.onItemClick(grid, liveView, position, 0)
            return
        }
        if (name == "收藏") {
            // 微信 8.0.77 收藏页 Activity 名已变，逐个尝试常见类名
            val ctx = appPanel.context
            val candidates = listOf(
                "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
            )
            for (cls in candidates) {
                runCatching {
                    val intent = android.content.Intent().setClassName("com.tencent.mm", cls)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    WeLogger.i(TAG, "FAV-OK " + cls)
                    return
                }
            }
            WeLogger.i(TAG, "FAV-ALL-FAIL")
            return
        }

        // 其他更多页工具：按名字实时匹配（尽力而为）
        val ordered = grids.filter { it.isAttachedToWindow || it.childCount > 0 } + grids
        for (grid in ordered.distinct()) {
            val adapter = grid.adapter ?: continue
            val index = (0 until adapter.count).firstOrNull { idx ->
                extractItemName(adapter.getView(idx, null, grid)) == name
            } ?: continue
            val liveView = grid.getChildAt(index) ?: adapter.getView(index, null, grid)
            WeLogger.i(TAG, "CLICK name=" + name + " index=" + index)
            default.onClickListener.onItemClick(grid, liveView, index, 0)
            return
        }
        WeLogger.i(TAG, "CLICK-FAIL name=" + name)
    }


    private class PanelTools {
        val flow = MutableStateFlow<List<Pair<String, MenuItem>>>(emptyList())

        /** null until this panel's grid has been read at least once. */
        var lastSnapshotTime: Instant? = null
        var refreshScheduled = false
    }

    /**
     * A tool list belongs to one AppPanel, not to the process: every chat footer builds its own
     * panel, and WeChat builds more than one footer at a time (see [scheduleGridInitWatchdog]), so a
     * background chat's panel must not be able to overwrite the visible chat's toolbar — nor hand it
     * click targets that belong to another conversation.
     *
     * [MenuItem] only ever holds *weak* references to the panel's views, so a value can't pin its
     * own key here.
     */
    private val panelTools = WeakHashMap<AppPanel, PanelTools>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun toolsOf(appPanel: AppPanel): PanelTools =
        synchronized(panelTools) { panelTools.getOrPut(appPanel) { PanelTools() } }

    private var itemsOrder by WePrefs.prefOption("chat_toolbar_order", NAME_TO_ICON_MAP.keys.joinToString(","))
    private var enabledItems by WePrefs.prefOption("chat_toolbar_enabled_items", NAME_TO_ICON_MAP.keys)
    private var displayModeValue by WePrefs.prefOption(
        "chat_toolbar_display_mode",
        ToolbarDisplayMode.ICON_AND_TEXT.preferenceValue,
    )

    // quick replies are stored as a JSON string array so individual replies may safely
    // contain commas, newlines or any other character
    private var quickRepliesRaw by WePrefs.prefOption("chat_toolbar_quick_replies", "")

    private val quickRepliesSerializer = ListSerializer(String.serializer())

    private fun loadQuickReplies(): List<String> {
        val raw = quickRepliesRaw
        if (raw.isEmpty()) return emptyList()
        return runCatching { Json.decodeFromString(quickRepliesSerializer, raw) }
            .getOrElse {
                WeLogger.w(TAG, "failed to parse quick replies, resetting: ${it.message}")
                emptyList()
            }
    }

    private fun saveQuickReplies(replies: List<String>) {
        quickRepliesRaw = Json.encodeToString(quickRepliesSerializer, replies)
    }

    private fun iconFor(name: String): ImageVector = when (name) {
        QUICK_REPLY_NAME -> MaterialSymbols.Outlined.Chat
        WEAGENT_NAME -> MaterialSymbols.Outlined.Smart_toy
        else -> NAME_TO_ICON_MAP.getValue(name)
    }

    // Ensures every supported item is present while preserving the user's saved order. Legacy
    // configs that predate quick replies get that item inserted first, and ones that predate the
    // WeAgent entry get it inserted right before 快捷回复.
    private fun normalizeOrder(order: List<String>): List<String> {
        val supportedItems = setOf(QUICK_REPLY_NAME, WEAGENT_NAME) + NAME_TO_ICON_MAP.keys
        val result = order.filter { it in supportedItems }.distinct().toMutableList()
        if (QUICK_REPLY_NAME !in result) result.add(0, QUICK_REPLY_NAME)
        if (WEAGENT_NAME !in result) result.add(result.indexOf(QUICK_REPLY_NAME), WEAGENT_NAME)
        NAME_TO_ICON_MAP.keys.forEach { if (it !in result) result.add(it) }
        return result
    }

    private fun insertQuickReply(text: String) {
        WeMessageApi.sendText(WeCurrentConversationApi.value, text)
    }

    /**
     * Reading a panel's grid inflates one item view per entry, and WeChat re-runs initAppGrid in
     * bursts, so snapshots are debounced. The debounce is trailing-edge: a suppressed call schedules
     * a single delayed refresh instead of being dropped, so the grid's final state always reaches the
     * toolbar. (Dropping used to lose the *only* initAppGrid of a chat when it happened to land in
     * the window — e.g. right after WeKit loaded, when the initial baseline was still "now".)
     */
    private val toolListDebounce = 2.seconds

    /**
     * AppPanel.t() schedules its grid data load with a 1000ms delay, so WeChat's own initAppGrid
     * normally lands a bit after that; only step in once it clearly hasn't.
     */
    private const val GRID_INIT_WATCHDOG_DELAY_MS = 1500L

    /** 从 AppPanel 格子 item 提取工具名。优先读 item 布局的显示文本（用户看到的，如「相册」），
     * 微信 tag 结构里第一个 TextView 可能是长按提示（如「系统拍摄」），会导致入口消失或错位。 */
    /** 调试：收集 item 内所有 TextView 文本，排查名字提取问题。 */
    private fun collectTexts(root: View, out: MutableList<String>) {
        if (root is TextView && root.text.isNotBlank()) out.add(root.text.toString())
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collectTexts(root.getChildAt(i), out)
        }
    }

    private fun extractItemName(itemView: View): String {
        findFirstText(itemView)?.let { if (it.isNotBlank()) return it }
        return runCatching {
            (itemView.tag.reflekt()
                .firstField { type = TextView::class }
                .get()!! as TextView).text.toString()
        }.getOrElse { "" }
    }

    /** 深度遍历取第一个非空 TextView 文本（工具名格子通常是 图标+文字 垂直布局）。 */
    private fun findFirstText(root: View): String? {
        if (root is TextView && root.text.isNotBlank()) return root.text.toString()
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstText(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
    /** 调试：递归遍历 AppPanel 视图树，打印所有 GridView（找含相册的第一屏 grid）。 */
    private fun dumpAllGrids(root: View, depth: Int = 0) {
        if (root is GridView) {
            val adapter = root.adapter
            val n = adapter?.count ?: 0
            val items = (0 until minOf(n, 3)).map { idx ->
                val v = adapter?.getView(idx, null, root) ?: return@map "?"
                extractItemName(v)
            }
            WeLogger.i(TAG, "GRIDVIEW depth=$depth class=" + root.javaClass.name + " count=$n first3=$items")
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) dumpAllGrids(root.getChildAt(i), depth + 1)
        }
    }

    /** Reads the panel's grids into its [PanelTools.flow]. No-op while the grid isn't built yet. */
    private fun snapshotTools(appPanel: AppPanel) {
        val tools = mutableListOf<Pair<String, MenuItem>>()
        dumpAllGrids(appPanel)

        // (0, 0, 0) is the MMFlipper holding one GridView per page; absent until AppPanel.init()
        // has inflated the panel's layout.
        // 微信 8.0.77 面板含多个 AppGrid（第一屏+更多页）：findViewByChildIndexes(0,0,0) 只命中一组，
        // 会漏掉相册（第一屏 index 0）。递归收集所有 GridView 合并工具。
        val grids = mutableListOf<GridView>()
        fun collectGrids(r: View) {
            if (r is GridView) grids.add(r)
            if (r is ViewGroup) for (i in 0 until r.childCount) collectGrids(r.getChildAt(i))
        }
        collectGrids(appPanel)
        if (grids.isEmpty()) return

        grids.forEach { grid ->
            val onClickListener = grid.reflekt()
                .firstField { type = AdapterView.OnItemClickListener::class }.get()!! as AdapterView.OnItemClickListener
            val onLongClickListener = grid.reflekt()
                .firstField { type = AdapterView.OnItemLongClickListener::class }.get()!! as AdapterView.OnItemLongClickListener
            val listAdapter = grid.adapter

            listAdapter.iterable(grid).forEachIndexed { index, itemView ->
                val name = extractItemName(itemView)
                if (name.isEmpty()) return@forEachIndexed
                tools.add(
                    name to MenuItem(
                        name,
                        onClickListener,
                        onLongClickListener,
                        WeakReference(appPanel),
                        WeakReference(grid),
                        WeakReference(itemView),
                        index
                    )
                )
            }
        }

        // An empty read means initAppGrid bailed out before building anything (it returns early
        // while the grid dimensions are still unknown). Publishing that would clear a toolbar that
        // already works and mark the panel as snapshotted.
        if (tools.isEmpty()) return

        val state = toolsOf(appPanel)
        state.flow.value = tools
        state.lastSnapshotTime = now()
        WeLogger.d(TAG, "populated tool list with ${tools.size} items")
    }

    /**
     * Makes sure a chat footer's grid gets built even when WeChat never asks for it.
     *
     * initAppGrid has exactly two triggers: the MMFlipper's onMeasure listener — which needs the
     * panel to actually be laid out, i.e. the user tapping "+" — and AppPanel.loadData(), scheduled
     * by AppPanel.init() with a 1000ms delay. loadData() is the one that makes the toolbar work
     * without user interaction, but it runs on a *process-wide* task group tagged
     * "AppPanel-loadinfo" that AppPanel.loadData() itself cancels on every call. So a second chat
     * footer built within that 1s window silently cancels the first panel's pending load, and that
     * panel's grid then stays empty until the user opens it by hand.
     *
     * Opening a chat from a notification or an external app share is exactly that case: WeChat
     * builds more than one chat footer in quick succession. Kick the grid off ourselves rather than
     * depending on WeChat's cancellable schedule.
     */
    private fun scheduleGridInitWatchdog(appPanel: AppPanel) {
        mainHandler.postDelayed({
            if (toolsOf(appPanel).lastSnapshotTime != null) return@postDelayed
            // initAppGrid dereferences views that AppPanel.init() inflates, so only force it once
            // the panel's layout is there.
            if (appPanel.findViewByChildIndexes<ViewGroup>(0, 0, 0) == null) return@postDelayed

            WeLogger.d(TAG, "grid was never initialized for this chat footer, forcing initAppGrid")
            // R8 staticizes initAppGrid on current builds, which is why the hooks read the panel out
            // of args[0]; tolerate the instance shape too, since this call is outside a hook and an
            // argument mismatch would take the process down.
            val method = methodAppPanelInitAppGrid.method
            if (java.lang.reflect.Modifier.isStatic(method.modifiers)) method.invoke(null, appPanel)
            else method.invoke(appPanel)
        }, GRID_INIT_WATCHDOG_DELAY_MS)
    }

    override fun onEnable() {
        methodAppPanelInitAppGrid.apply {
            hookBefore {
                val appPanel = args[0] as AppPanel
                // WeChat normally lets MMFlipper.onMeasure feed the real measured size into the
                // measurer (g.a). We have to invoke initAppGrid before the panel is laid out, so we
                // reproduce WeChat's own natural dimensions instead of hardcoding pixels.
                //   width  = screen width (initAppGrid derives column count as gridWidth / dp(82))
                //   height = the MMFlipper height. initAppGrid spreads any height left over after
                //            the icon rows into grid spacing/top-padding, so overshooting here shows
                //            up as extra padding at the bottom of the panel.
                // The panel's port height is NOT a fixed 215dp: getPortHeightPX() returns a value
                // set to match the soft-keyboard height (setPortHeighPx), which is device/IME
                // dependent. The container LinearLayout (a1r, child path 0,0) already has that
                // resolved height in its layoutParams (set in AppPanel.y()), so read it at runtime
                // and only fall back to the 215dp portrait / 158dp landscape default. The flipper
                // is that container minus the MMDotView strip below it (6dp dot + 16dp paddingBottom
                // = 22dp, see layout hy.xml), which is fixed in dp.
                val metrics = appPanel.resources.displayMetrics
                val width = metrics.widthPixels
                val fallbackDp = if (metrics.widthPixels < metrics.heightPixels) 215 else 158
                val containerHeight = appPanel.findViewByChildIndexes<View>(0, 0)
                    ?.layoutParams?.height?.takeIf { it > 0 }
                    ?: (fallbackDp * metrics.density).toInt()
                val dotStrip = (22 * metrics.density).toInt()
                val height = (containerHeight - dotStrip).coerceAtLeast(1)
                val measurer = methodAppPanelOnMeasure.method.declaringClass.createInstance(appPanel)
                methodAppPanelOnMeasure.method.invoke(measurer, width, height)
            }

            hookAfter {
                val appPanel = args[0] as AppPanel
                val state = toolsOf(appPanel)

                val elapsed = state.lastSnapshotTime?.let { now() - it }
                if (elapsed == null || elapsed >= toolListDebounce) {
                    snapshotTools(appPanel)
                    return@hookAfter
                }

                // Inside the cooldown: coalesce into one trailing refresh so this update is delayed
                // rather than lost — it may well be the one carrying the panel's final item set.
                if (state.refreshScheduled) return@hookAfter
                state.refreshScheduled = true
                mainHandler.postDelayed({
                    state.refreshScheduled = false
                    snapshotTools(appPanel)
                }, (toolListDebounce - elapsed).inWholeMilliseconds.coerceAtLeast(1))
            }
        }

        ChatFooter::class.constructor.hookAfter {
            val chatFooter = thisObject as FrameLayout
            val activity = chatFooter.context as Activity

            val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)

            chatFooter.setLifecycleOwner(lifecycleOwner)
            val linearLayout = chatFooter.findViewByChildIndexes<LinearLayout>(0, 1)!!
            linearLayout.setLifecycleOwner(lifecycleOwner)
            if (linearLayout.findViewWhich<View> { it is ComposeView } != null) return@hookAfter
            activity.window.decorView.setLifecycleOwner(lifecycleOwner)

            // The panel is part of the footer's own layout and ChatFooter.initAppPanel() has already
            // run inside the constructor, so it is reachable here. Bind this toolbar to that panel
            // only, and make sure something initializes its grid.
            val appPanel = chatFooter.findViewWhich<AppPanel> { it is AppPanel }
            if (appPanel == null) WeLogger.w(TAG, "no AppPanel in this chat footer, toolbar will stay empty")
            val toolsFlow = appPanel?.let { toolsOf(it).flow } ?: MutableStateFlow(emptyList())
            appPanel?.let { scheduleGridInitWatchdog(it) }

            linearLayout.addView(ComposeView(activity).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                    InjectedUiTheme {
                        val tools by toolsFlow.collectAsStateWithLifecycle()
                        val itemsOrder = remember { itemsOrder }
                        val enabledItems = remember { enabledItems }
                        val displayMode = remember { ToolbarDisplayMode.fromPreference(displayModeValue) }

                        val sortedVisibleItems = remember(tools) {
                            if (tools.isEmpty()) return@remember emptyList()

                            val firstTool = tools[0].second
                            val orderList = normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() })
                            val list = mutableListOf<Pair<String, () -> Unit>>()

                            list.add(WEAGENT_NAME to {
                                // The panel is a system overlay window, so it works from any
                                // Activity — and stays reachable when the ball is disabled.
                                WeAgentService.init()
                                WeAgentOverlayController.openPanel()
                            })

                            list.add(QUICK_REPLY_NAME to {
                                showQuickReplyPicker(activity)
                            })

                            // 系统拍摄 is not a grid entry of its own: it is what long-pressing the
                            // first item (相册, grid position 0) does. WeChat's long-click listener
                            // only looks at the position, so the view arguments may stay null.
                            list.add("系统拍摄" to {
                                firstTool.onLongClickListener.onItemLongClick(null, null, 0, 0)
                            })

                            tools.forEach { (name, menuItem) ->
                                if (name in NAME_TO_ICON_MAP && name != "系统拍摄") {
                                    list.add(name to {
                                        menuItem.appPanel.get()?.let { clickTool(it, name, menuItem) }
                                    })
                                }
                            }

                            // 相册在微信所有会话的第一屏 grid 都有，但平铺时第一屏 grid 不在 AppPanel
                            // 视图树（快照读不到），需注入显示；其余工具以快照为准（会话没有的格子不显示，
                            // 如群聊无视频通话格子、普通聊天无语音通话/接龙格子）。
                            val presentNames = tools.map { it.first }.toSet()
                            val defaultMenuItem = tools.firstOrNull()?.second
                            if (defaultMenuItem != null && "相册" in enabledItems && "相册" !in presentNames) {
                                list.add("相册" to {
                                    defaultMenuItem.appPanel.get()?.let { clickTool(it, "相册", defaultMenuItem) }
                                })
                            }

                            list.distinctBy { it.first }
                                .filter { it.first in enabledItems }
                                .sortedBy { item ->
                                    val idx = orderList.indexOf(item.first)
                                    if (idx == -1) Int.MAX_VALUE else idx
                                }
                        }
                        WeLogger.i(TAG, "TOOLS-RENDER enabled=" + enabledItems + " rendered=" + sortedVisibleItems.map { it.first })

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            items(sortedVisibleItems, key = { it.first }) { (name, onClick) ->
                                val icon = iconFor(name)
                                FeatureChip(name, icon, displayMode, onClick)
                            }
                        }
                    }
                }
            }, 0)

            // 调试：打印 footer 子 View 结构（确认 ComposeView 添加位置与微信 UI）
            fun dumpChildren(r: View, d: Int = 0) {
                if (d > 4) return
                WeLogger.i(TAG, "FOOTER d" + d + " " + r.javaClass.name + " children=" + (if (r is ViewGroup) r.childCount else 0))
                if (r is ViewGroup) {
                    for (i in 0 until minOf(r.childCount, 5)) dumpChildren(r.getChildAt(i), d + 1)
                }
            }
            dumpChildren(chatFooter)
        }
    }

    override fun onDisable() {
        synchronized(panelTools) {
            panelTools.values.forEach { it.flow.value = emptyList() }
            panelTools.clear()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val currentOrder = remember {
                normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() }).toMutableStateList()
            }
            val currentEnabled = remember { enabledItems.toMutableStateList() }
            var currentDisplayMode by remember {
                mutableStateOf(ToolbarDisplayMode.fromPreference(displayModeValue))
            }
            var displayModeMenuExpanded by remember { mutableStateOf(false) }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text("聊天工具栏") },
                text = {
                    DefaultColumn {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { displayModeMenuExpanded = true },
                                headlineContent = { Text("显示样式") },
                                supportingContent = { Text(currentDisplayMode.label) },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Arrow_drop_down,
                                        contentDescription = "选择显示样式",
                                    )
                                },
                            )
                            DropdownMenu(
                                expanded = displayModeMenuExpanded,
                                onDismissRequest = { displayModeMenuExpanded = false },
                            ) {
                                ToolbarDisplayMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        trailingIcon = if (mode == currentDisplayMode) ({
                                            Icon(
                                                MaterialSymbols.Outlined.Check,
                                                contentDescription = null,
                                            )
                                        }) else null,
                                        onClick = {
                                            currentDisplayMode = mode
                                            displayModeMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Column {
                            Text("显示与顺序", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "长按拖动手柄调整顺序，使用开关控制是否显示",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReorderableList(
                            items = currentOrder,
                            itemKey = { it },
                            onMove = { from, to ->
                                currentOrder.add(to, currentOrder.removeAt(from))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        ) { name, dragHandleModifier ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .then(dragHandleModifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        MaterialSymbols.Outlined.Drag_handle,
                                        contentDescription = "拖动 $name",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        iconFor(name),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = name,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (name == QUICK_REPLY_NAME) {
                                    IconButton(onClick = { showQuickReplyConfig(context) }) {
                                        Icon(
                                            MaterialSymbols.Outlined.Settings,
                                            contentDescription = "配置快捷回复",
                                        )
                                    }
                                }
                                Switch(
                                    checked = name in currentEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (name !in currentEnabled) currentEnabled.add(name)
                                        } else {
                                            currentEnabled.remove(name)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        itemsOrder = currentOrder.joinToString(",")
                        enabledItems = currentEnabled.toSet()
                        displayModeValue = currentDisplayMode.preferenceValue
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }

    // shown when the user taps the 快捷回复 chip in the chat toolbar: pick a reply to insert
    private fun showQuickReplyPicker(context: Context) {
        showComposeDialog(context) {
            val replies = remember { loadQuickReplies() }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(QUICK_REPLY_NAME) },
                text = {
                    if (replies.isEmpty()) {
                        Text("暂无快捷回复, 请在「聊天工具栏」设置中配置")
                    } else {
                        LazyColumn {
                            items(replies) { reply ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        insertQuickReply(reply)
                                        onDismiss()
                                    },
                                    headlineContent = { Text(reply) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            )
        }
    }

    private fun showQuickReplyEditor(
        context: Context,
        title: String,
        initialValue: String = "",
        onSave: (String) -> Unit,
    ) {
        showComposeDialog(context) {
            var value by remember { mutableStateOf(initialValue) }

            AlertDialogContent(
                title = { Text(title) },
                text = {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入回复内容") },
                        minLines = 3,
                        maxLines = 8,
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(
                        onClick = {
                            onSave(value.trim())
                            onDismiss()
                        },
                        enabled = value.isNotBlank(),
                    ) { Text("保存") }
                },
            )
        }
    }

    // Shown from the settings button in the quick-reply row.
    @OptIn(ExperimentalFoundationApi::class)
    private fun showQuickReplyConfig(context: Context) {
        showComposeDialog(context) {
            val replies = remember {
                loadQuickReplies().map { QuickReplyDraft(text = it) }.toMutableStateList()
            }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(QUICK_REPLY_NAME) },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("回复内容", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "点击编辑，长按手柄调整顺序",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    showQuickReplyEditor(context, "添加快捷回复") { text ->
                                        replies.add(QuickReplyDraft(text = text))
                                    }
                                }
                            ) {
                                Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                                Text("添加")
                            }
                        }

                        if (replies.isEmpty()) {
                            Text(
                                "暂无快捷回复，点击右上角“添加”创建。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp),
                            )
                        } else {
                            ReorderableList(
                                items = replies,
                                itemKey = { it.id },
                                onMove = { from, to ->
                                    replies.add(to, replies.removeAt(from))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                            ) { reply, dragHandleModifier ->
                                val editReply = {
                                    showQuickReplyEditor(
                                        context = context,
                                        title = "编辑快捷回复",
                                        initialValue = reply.text,
                                    ) { text ->
                                        val index = replies.indexOfFirst { it.id == reply.id }
                                        if (index >= 0) replies[index] = reply.copy(text = text)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .then(dragHandleModifier),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            MaterialSymbols.Outlined.Drag_handle,
                                            contentDescription = "拖动快捷回复",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = reply.text,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(onClick = editReply)
                                            .padding(horizontal = 8.dp, vertical = 12.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = editReply) {
                                        Icon(
                                            MaterialSymbols.Outlined.Edit,
                                            contentDescription = "编辑快捷回复",
                                        )
                                    }
                                    IconButton(onClick = { replies.removeAll { it.id == reply.id } }) {
                                        Icon(
                                            MaterialSymbols.Outlined.Delete,
                                            contentDescription = "删除快捷回复",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        saveQuickReplies(replies.map { it.text.trim() }.filter { it.isNotEmpty() })
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> ReorderableList(
    items: List<T>,
    itemKey: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = modifier,
        userScrollEnabled = draggingKey == null,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> itemKey(item) },
        ) { _, item ->
            val key = itemKey(item)
            val isDragging = draggingKey == key
            val dragHandleModifier = Modifier.pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) {
                            draggingKey = key
                            dragOffset = 0f
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        draggingKey = null
                        dragOffset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (draggingKey != key) return@detectDragGesturesAfterLongPress
                        dragOffset += amount.y

                        val currentInfo = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == key }
                            ?: return@detectDragGesturesAfterLongPress
                        val currentIndex = currentInfo.index
                        val start = currentInfo.offset + dragOffset
                        val end = start + currentInfo.size
                        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { targetInfo ->
                            if (targetInfo.index == currentIndex) {
                                false
                            } else if (dragOffset > 0f) {
                                targetInfo.index > currentIndex &&
                                        end > targetInfo.offset + targetInfo.size / 2
                            } else {
                                targetInfo.index < currentIndex &&
                                        start < targetInfo.offset + targetInfo.size / 2
                            }
                        }
                        if (target != null) {
                            onMove(currentIndex, target.index)
                            dragOffset -= target.offset - currentInfo.offset
                        }

                        val viewport = listState.layoutInfo
                        val center = currentInfo.offset + dragOffset + currentInfo.size / 2
                        when {
                            center < viewport.viewportStartOffset + 56 && listState.canScrollBackward ->
                                coroutineScope.launch { listState.scrollBy(-12f) }

                            center > viewport.viewportEndOffset - 56 && listState.canScrollForward ->
                                coroutineScope.launch { listState.scrollBy(12f) }
                        }
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else 0f
                        scaleX = if (isDragging) 1.02f else 1f
                        scaleY = if (isDragging) 1.02f else 1f
                        shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                    }
                    .then(if (isDragging) Modifier else Modifier.animateItem())
            ) {
                itemContent(item, dragHandleModifier)
            }
        }
    }
}

@Composable
private fun FeatureChip(
    text: String,
    icon: ImageVector,
    displayMode: ToolbarDisplayMode,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            when (displayMode) {
                ToolbarDisplayMode.ICON_ONLY -> Icon(
                    icon,
                    contentDescription = text,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )

                ToolbarDisplayMode.ICON_AND_TEXT,
                ToolbarDisplayMode.TEXT_ONLY -> Text(text)
            }
        },
        leadingIcon = if (displayMode == ToolbarDisplayMode.ICON_AND_TEXT) ({
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        }) else null,
    )
}
