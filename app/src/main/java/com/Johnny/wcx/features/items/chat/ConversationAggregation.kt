package com.Johnny.wcx.features.items.chat
import de.robv.android.xposed.XC_MethodHook

import android.app.Activity
import android.os.Parcel
import android.os.Parcelable
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.conversation.BaseConversationUI
import com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI
import com.tencent.mm.ui.conversation.MainUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.SelfProfileField
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.ui.WeStartActivityApi
import com.Johnny.wcx.features.api.ui.WeConversationContextMenuApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.contacts.CustomLocalFriendAvatars
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.BaseContactSelector
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.EditIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HookParam
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.HostInfo
import androidx.core.graphics.toColorInt
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.captureOriginalMethod
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.reflection.BString
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.lang.reflect.Proxy
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.UUID
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.lang.reflect.Modifier as JavaModifier

@Feature(name = "对话归拢", categories = ["聊天"], description = "将多个对话归拢在一个文件夹内\n设置对话头像需同时启用「自定义好友本地头像」")
object ConversationAggregation : ClickableFeature(),
    WeDatabaseListenerApi.IQueryListener,
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    WeStartActivityApi.IStartActivityListener,
    IResolveDex {

    private const val TAG = "AggregateChats"
    const val FOLDER_PREFIX = "wekit_folder_"
    @Volatile private var mvvmRedirecting = false
    @Volatile private var mvvmSelectedWxid: String? = null
    @Volatile private var mvvmSelectedTs = 0L
    @Volatile private var mvvmPickedMember: String? = null  // 最近一次 folder picker 所选成员(持久,无过期)
    // 拦截首页长按菜单「标为已读」：对归拢文件夹行改为标记其全部成员会话（见 markFolderAsRead）。
    private val folderMarkReadInterceptor = WeConversationContextMenuApi.INativeMenuInterceptor { context, menuItem ->
        val title = menuItem.title?.toString().orEmpty()
        if (title.contains("已读") && isFolderId(context.talker)) {
            runCatching { markFolderAsRead(context.talker) }
                .onFailure { WeLogger.e(TAG, "mark folder read failed", it) }
            true
        } else {
            false
        }
    }

    private const val FOLDER_CONFIG_MENU_ID = 0x0721C0DE
    private const val MOVE_TO_FOLDER_MENU_ID = 777021
    private const val MOVE_TO_FOLDER_MENU_ORDER = 1001
    private const val REMOVE_FROM_FOLDER_MENU_ID = 777020

    // Order pushes our item to the end of the container's context menu (its own items use 0).
    private const val REMOVE_FROM_FOLDER_MENU_ORDER = 1000

    // rconversation.flag packing (see WeChat xg3.b.c): high 8 bits = pin / move-up state
    // owned by WeChat (setPlacedTop / unSetPlacedTop), low 56 bits = conversationTime.
    private const val FLAG_TIME_MASK = 0x00FFFFFFFFFFFFFFL
    private const val FLAG_HIGH_MASK = FLAG_TIME_MASK.inv()

    // attrflag bit the conversation box uses to mark "has muted unread" so the homepage
    // badge renders a small dot instead of a number (WeChat w3.b / s2 require this bit set
    // alongside unReadMuteCount > 0 when unReadCount == 0).
    private const val ATTR_FLAG_MUTE_BIT = 2097152

    // rconversation.atCount 的高位标志：bit 24 (0x01000000) = 有人 @所有人（微信权威标记，
    // 不依赖摘要文本关键词）。日志实测 @所有人 时 atCount=16777216，摘要文本可能不含
    // 「所有人/全体」等词，仅靠文本判断会漏判成 [有人@我]。
    private const val AT_COUNT_EVERYONE_BIT = 0x01000000

    // Truncation + tint used by the FunBox-style "someone @ me" prefix on folder rows.
    private const val MAX_DIGEST_NAME_LEN = 8
    private const val MAX_FOLDER_DISPLAY_NAME = 12
    // 括号内发送者名截断长度（Eatmelons → Eatm...），括号内空间小，比群名更短
    private const val MAX_SENDER_NAME_LEN = 4
    // 归拢摘要红绿灯配色：[全体]/[有人@我] 红、[N个聊天]/[N个消息] 黄、[自己] 绿
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
    private fun parseColor(value: String, fallback: String): Int =
        runCatching { value.toColorInt() }.getOrElse { fallback.toColorInt() }
    /** 暗色/亮色模式适配：暗色模式将染色提亮（HSV 明度下限 0.78），保证深底可读（类似微信原生暗色白字）；亮色模式返回原色 */
    private fun adaptNight(ctx: Context?, color: Int): Int {
        if (ctx == null) return color
        val night = ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (!night) return color
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] = maxOf(hsv[2], 0.78f)
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv)
    }

    private val MENTION_RED: Int get() = parseColor(mentionAtColor, DEFAULT_AT_COLOR)
    private val MENTION_YELLOW: Int get() = parseColor(mentionCountColor, DEFAULT_COUNT_COLOR)
    private val MENTION_GREEN: Int get() = parseColor(mentionSelfColor, DEFAULT_SELF_COLOR)
    /** 归拢文件夹标题蓝色 */
    private val MENTION_TITLE_BLUE: Int get() = parseColor(folderTitleColor, DEFAULT_TITLE_COLOR)
    private val MENTION_MEMBER: Int get() = parseColor(mentionMemberColor, DEFAULT_MEMBER_COLOR)
    private val MEMBER_PAREN_REGEX = Regex("[（(][^（）()]+[）()]")
    private val CHAT_COUNT_REGEX = Regex("\\[[^\\]]*\\u4e2a(?:\\u804a\\u5929|\\u6d88\\u606f)\\]")

    // 归拢配置按账号隔离：每个账号独立配置文件，避免切换账号后
    // 显示其他账号的归拢文件夹（成员存的是该账号的联系人/群聊）。
    private val legacyFoldersFile by lazy { KnownPaths.moduleData / "chat_folders.json" }

    private fun foldersFileFor(wxid: String?) = if (!wxid.isNullOrBlank()) {
        KnownPaths.moduleData / "chat_folders_$wxid.json"
    } else {
        legacyFoldersFile
    }

    private fun currentAccountWxid(): String? = runCatching {
        WeDatabaseApi.getSelfProfileField(SelfProfileField.WXID)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()


    private const val CONTAINER_UI_NAME = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
    private const val BRAND_FLUTTER_UI = "com.tencent.mm.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity"
    private const val BRAND_SERVICE_UI = "com.tencent.mm.ui.brandservice.BrandServiceTimelineUI"
    private const val ENTERPRISE_UI = "com.tencent.mm.ui.conversation.EnterpriseConversationUI"
    private val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }
    private val methodConversationStorageQueryByParent by dexMethod(allowFailure = true) {
        matcher {
            usingStrings(
                "select * from rconversation where ",
                " order by flag desc, conversationTime desc"
            )
            paramTypes("int", "java.util.List", "java.lang.String", "int")
            returnType("android.database.Cursor")
        }
    }

    // SelectConversationUI#doClickUser(username) — the single entry point for all conversation
    // taps in the "share to conversation" picker. WeChat only intercepts known virtual usernames
    // ("conversationboxservice", "opencustomerservicemsg") before forwarding to its share logic.
    // Our folder rows (wekit_folder_XXX) pass those guards and reach the share machinery, which
    // tries to open a chat thread for a non-existent contact → crash.
    private val methodSelectConversationDoClickUser by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.SelectConversationUI", "doClickUser=%s")
            paramTypes("java.lang.String")
            returnType("void")
        }
    }

    // The MVVM "select contact" picker (com.tencent.mm.ui.mvvm.MvvmContactListUI) used for in-app
    // forwarding routes every row tap through its list item-click listener cj5.g2#g(View, item, int)
    // (interface in5.u). A tap on a normal conversation dispatches wi5.c0(listOf(username)) to the
    // state center, which sets the "Select_Conv_User" result extra and finishes. Our folder rows
    // (wekit_folder_XXX) reach that same path with a non-existent username → crash downstream.
    // We match the two concrete listeners (main list + search results) by their unique log tags.
    private val methodMvvmMainListItemClick by dexMethod {
        matcher {
            usingStrings("MicroMsg.SelectContactMainRecycleViewUIC", "onItemClickListener data.type")
        }
    }
    private val methodMvvmSearchItemClick by dexMethod {
        matcher {
            usingStrings("MicroMsg.SelectContactSearchMvvmListUIC", "onItemClick: isAlwaysCheck=")
            paramTypes("android.view.View", null, "int")
            returnType("void")
        }
    }

    // com.tencent.mm.storage.m4 (ConversationStorage)#b0(username) — "updateUnreadByTalker".
    // The folder container (ConvBoxServiceConversationUI) sets its superUsername to our folder id
    // (via the Contact_User extra we inject). WeChat's ConvBoxServiceConversationFmUI.onPause()
    // then calls b0(superUsername), which zeroes unReadCount / unReadMuteCount and clears the mute
    // attrflag bit on that exact row — wiping our folder's badge just for opening and leaving the
    // folder without touching any member. We no-op it for folder ids so the aggregate row keeps
    // reflecting its members' (still-unread) state.
    private val methodConversationStorageUpdateUnreadByTalker by dexMethod(allowFailure = true) {
        matcher {
            usingStrings("MicroMsg.ConversationStorage", "updateUnreadByTalker %s", "update conversation failed")
            paramTypes("java.lang.String")
            returnType("boolean")
        }
    }

    // com.tencent.mm.ui.widget.menu.MMPopupMenu#showMenu(view, pos, id, onCreateListener, selectCb, x, y)
    // The shared long-press popup used by both the homepage list and the folder container. We hook
    // it (gated on activeFolderId) to inject a "remove from folder" item only inside our folders.
    private val methodShowPopupMenu by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingStrings("MicroMsg.MMPopupMenu")
            }
            paramTypes(
                "android.view.View", "int", "long",
                $$"android.view.View$OnCreateContextMenuListener", null, "int", "int"
            )
            returnType("void")
        }
    }

    @Volatile
    private var activeFolderId: String? = null
    private val cachedHomeIntents = HashMap<String, Intent>()
    @Volatile
    private var suppressNextClick = false
    // WeChat's FlutterPageInfo carries a process-scoped session id (fields d/e) that is regenerated
    // on every process start; a cached one from a previous process is rejected (opens a plain
    // conversation). We capture the current process's values when WeChat constructs a page_info.
    @Volatile
    private var currentPageInfoId: String? = null
    @Volatile
    private var currentPageInfoE: Int? = null
    // A live FlutterPageInfo object WeChat itself constructed for the biz (official-account)
    // flutter page in the current process. WeChat's own objects carry engine state that
    // deserialized copies never have, so using this live object opens the page correctly.
    @Volatile
    private var livePageInfoBiz: Parcelable? = null
    // Same capture for the service-official-account (brand_service) flutter page; its own
    // page_info carries a different page id than the biz page, so using the biz object for it
    // opens the wrong (biz) content.
    @Volatile
    private var livePageInfoBrandService: Parcelable? = null

    @Volatile
    private var folderSchemaReady: Boolean? = null

    @Volatile
    private var foldersCache: List<ChatFolder>? = null

    @Volatile
    private var foldersCacheWxid: String? = null

    private val folderMembersCache = ConcurrentHashMap<String, List<String>>()

    @Volatile
    private var membersByFolder: Map<String, List<String>> = emptyMap()

    @Volatile
    private var folderByMember: Map<String, String> = emptyMap()

    private val suppressQueryRewrite = ThreadLocal.withInitial { false }
    /** 8.0.78 涓荤晫闈笉鍐嶈蛋 MainUI.onResume锛氶椤甸甯у彂鐜版垚鍛樼储寮曚负绌烘椂鎳掕Е鍙戜竴娆″叏閲忓璐︺€?*/
    private var lastReconcileAttempt = 0L
    @Volatile private var cachedConvListView: WeakReference<View>? = null

    // Reactive refresh: WeChat updates member conversation rows (new message / read state)
    // through the ContentValues insert/update path, but our materialized folder rows are
    // written via raw execSQL and never recomputed until MainUI.onResume. We listen for
    // member-row writes and debounce a lightweight summary recompute so the homepage folder
    // row tracks its members in real time.
    private const val REFRESH_DEBOUNCE_MS = 250L
    private val REFRESH_TASK_TOKEN = Any()
    private val RECONCILE_TASK_TOKEN = Any()
    private const val SQLITE_BIND_CHUNK_SIZE = 900
    private var lastDoRefreshAt = 0L
    private const val MIN_REFRESH_GAP = 800L
    private const val DIAG_MAX_BYTES = 6L * 1024 * 1024
    private const val DIAG_KEEP_BYTES = 1024L * 1024
    private val pendingRefreshMembers = ConcurrentHashMap.newKeySet<String>()
    private val pendingRefreshLock = Any()
    private val refreshAllFolders = AtomicBoolean(false)

    @Volatile
    private var refreshThread: HandlerThread? = null

    @Volatile
    private var refreshHandler: Handler? = null

    override fun onEnable() {
        WeLogger.i(TAG, "onEnable: begin")
        diagFile("onEnable: begin")
        WeDatabaseListenerApi.addListener(this)
        WeConversationContextMenuApi.addNativeInterceptor(folderMarkReadInterceptor)
        WeStartActivityApi.addListener(this)
        // 切换微信账号后 storage 重新初始化（新账号数据库），需要把归拢文件夹
        // 对账写入新账号的库，否则新账号页面看不到归拢。
        WeDatabaseApi.addDatabaseSwitchListener(::onDatabaseSwitched)

        startRefreshThread()

        hookMainUiRefresh()
        WeLogger.i(TAG, "onEnable: after hookMainUiRefresh")
        diagFile("onEnable: after hookMainUiRefresh")
        hookOpenFolder()
        hookChattingUiFolderRedirect()
        WeLogger.i(TAG, "onEnable: after hookOpenFolder")
        diagFile("onEnable: after hookOpenFolder")
        // 尽早 hook 微信打开链（ok0.l1），捕获其实例；微信启动早期会自动调用一次（如 voip 页），
        // 之后文件夹点击即可重走微信打开链，无需用户先开首页聚合页预热。
        hookOpenChainNow()
        WeLogger.i(TAG, "onEnable: after hookOpenChainNow")
        hookConversationPages()
        WeLogger.i(TAG, "onEnable: after hookConversationPages")
        diagFile("onEnable: after hookConversationPages")
hookRowMenuInjectG()
        hookRowMenuHost()
hookConversationLongMenuProbe()
hookViewLongClickProbe()
        hookPopupHostProbe()
        hookConversationClick()
        hookFolderFragmentMethods()
        hookFolderItemClick()
        WeLogger.i(TAG, "onEnable: after hookFolderContextMenu")
        diagFile("onEnable: after hookFolderContextMenu")
        hookSelectConversationUi()
        WeLogger.i(TAG, "onEnable: after hookSelectConversationUi")
        diagFile("onEnable: after hookSelectConversationUi")
        hookMvvmContactListItemClick()
        WeLogger.i(TAG, "onEnable: after hookMvvmContactListItemClick")
        diagFile("onEnable: after hookMvvmContactListItemClick")
        hookSqliteWrapperQuery()
        hookSqliteExec()
        WeLogger.i(TAG, "onEnable: after hookSqliteWrapperQuery")
        diagFile("onEnable: after hookSqliteWrapperQuery")
        hookConversationStorageParentQuery()
        WeLogger.i(TAG, "onEnable: after hookConversationStorageParentQuery")
        diagFile("onEnable: after hookConversationStorageParentQuery")
        hookConversationStorageUpdateUnread()
        WeLogger.i(TAG, "onEnable: after hookConversationStorageUpdateUnread")
        diagFile("onEnable: after hookConversationStorageUpdateUnread")
        hookMentionTint()
        WeLogger.i(TAG, "onEnable: after hookMentionTint")
        diagFile("onEnable: after hookMentionTint")
        hookTextViewSetText()
        hookAllTextViewDraw()
        WeLogger.i(TAG, "onEnable: done")
        diagFile("onEnable: done")

        CustomLocalFriendAvatars.fallbackUsernameProvider = { folderId ->
            if (isFolderId(folderId) && !CustomLocalFriendAvatars.avatarMap.containsKey(folderId)) {
                getFallbackAvatarMember(folderId)
            } else {
                null
            }
        }

        // Restore the materialized folder rows when re-enabled at runtime (DB already up), since
        // onDisable released them. On cold startup the DB isn't ready yet and this is a no-op —
        // MainUI.onResume (hookMainUiRefresh) runs the first sync once WeChat is up.
        if (WeDatabaseApi.isReady) {
            syncFoldersToDatabase()
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeConversationContextMenuApi.removeNativeInterceptor(folderMarkReadInterceptor)
        WeStartActivityApi.removeListener(this)
        WeDatabaseApi.removeDatabaseSwitchListener(::onDatabaseSwitched)
        CustomLocalFriendAvatars.fallbackUsernameProvider = null
        stopRefreshThread()

        // Release every folder back to the homepage — unmap members and delete all wekit_folder_*
        // rows — so disabling doesn't leave ghost aggregate conversations behind, exactly as if the
        // user had deleted every folder. The saved config is left untouched so onEnable can restore.
        releaseAllFolders()
    }

    /**
     * Reverses [syncFoldersToDatabase]: returns every folder member to the root homepage list and
     * removes all folder rows (rconversation / rcontact / img_flag). Mirrors deleting every folder
     * by hand, but keeps the on-disk config so the folders come back on the next onEnable.
     */
    /**
     * 微信切换账号后 WeDatabaseApi 已把 db 引用切到新账号：
     * 重新对账 folder 行到新库（reconcile 是差异写入，新库无行则全量重建），
     * 并刷新会话列表让归拢立即显示。
     */
    private fun onDatabaseSwitched() {
        WeLogger.i(TAG, "account/database switched, re-syncing folders to new database")
        runCatching {
            // 配置按账号隔离：db 变化说明账号已切换，清缓存按新账号重新加载
            foldersCache = null
            foldersCacheWxid = null
            folderMembersCache.clear()
            if (WeDatabaseApi.isReady && isFolderSchemaReady()) {
                syncFoldersToDatabase()
                WeConversationApi.reloadConversations()
            }
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to re-sync folders after account switch", e)
        }
    }

    private fun releaseAllFolders() {
        if (!WeDatabaseApi.isReady) return
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                val folders = loadFolders()
                persistChangedPinFlags(folders, readStoredFolderRows().mapValues { it.value.flag })
                WeDatabaseApi.transaction { clearStaleFolderMappings() }
                membersByFolder = emptyMap()
                folderByMember = emptyMap()
                folderMembersCache.clear()
            }
            WeConversationApi.reloadConversations()
            WeLogger.i(TAG, "released all folders on disable")
        }.onFailure {
            WeLogger.e(TAG, "failed to release folders on disable", it)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showManagerDialog(context)
    }

    /** Whether [username] is one of our materialized folder rows (vs. a real conversation). */
    fun isAggregationFolderId(username: String): Boolean = isFolderId(username)

    /** A folder choice exposed to other features (e.g. the "add to folder" conversation menu). */
    data class FolderChoice(val id: String, val name: String, val isAuto: Boolean)

    /** Public snapshot of the configured folders, for features that let the user pick one. */
    fun aggregationFolders(): List<FolderChoice> =
        loadFolders().map { FolderChoice(it.id, it.name, it.type != FolderType.MANUAL) }

    /**
     * Adds [talker] to the manual folder [folderId] and opens the existing edit dialog so the
     * user can review and save. Returns false without acting when the folder is missing or in an
     * auto mode (members are computed, not hand-picked); callers surface that to the user.
     */
    fun showAddToFolderDialog(context: Context, folderId: String, talker: String): Boolean {
        val folder = folderById(folderId) ?: return false
        if (folder.type != FolderType.MANUAL) return false
        val updated = folder.copy(members = (folder.members + talker).distinct().sorted())
        showEditFolderDialog(
            context = context,
            folder = updated,
            onFolderUpdated = {
                syncFoldersToDatabase()
            },
            onFolderDeleted = {
                syncFoldersToDatabase()
            }
        )
        return true
    }

    /**
     * Adds [talker] to the manual folder [folderId] and persists immediately (no dialog),
     * rebuilding the index so the row appears in the folder. Returns false without acting when the
     * folder is missing or in an auto mode (members are computed, not hand-picked).
     */
    fun addToFolder(folderId: String, talker: String): Boolean {
        val folder = folderById(folderId) ?: return false
        if (folder.type != FolderType.MANUAL) return false
        if (talker !in folder.members) {
            val updated = folder.copy(members = (folder.members + talker).distinct().sorted())
            saveFolders(loadFolders().map { if (it.id == updated.id) updated else it })
            syncFoldersToDatabase()
        }
        return true
    }

    /**
     * Removes [talker] from the manual folder [folderId], persists, and rebuilds the index so the
     * row disappears from the folder immediately. No-op for missing / auto folders, or when the
     * talker isn't actually a member.
     */
    private fun removeMemberFromFolder(folderId: String, talker: String) {
        val folder = folderById(folderId) ?: return
        if (folder.type != FolderType.MANUAL || talker !in folder.members) {
            showToast("该对话不在此手动文件夹中!")
            return
        }
        val updated = folder.copy(members = folder.members.filterNot { it == talker })
        saveFolders(loadFolders().map { if (it.id == updated.id) updated else it })
        syncFoldersToDatabase()
        showToast("已移出「${folder.name}」")
    }
    /** Long-press menu "move to folder": move talker from its current manual folder to another manual folder. */
    private fun showMoveToFolderDialog(context: Context, talker: String) {
        val source = loadFolders().firstOrNull { talker in it.members && it.type == FolderType.MANUAL } ?: run {
            showToast("该对话不在手动文件夹中!")
            return
        }
        val targets = loadFolders().filter { it.id != source.id && it.type == FolderType.MANUAL }
        if (targets.isEmpty()) {
            showToast("没有其他手动文件夹可移入!")
            return
        }
        showComposeDialog(context) {
            val dismiss = this.onDismiss
            AlertDialogContent(
                title = { Text("移到文件夹") },
                text = {
                    LazyColumn {
                        items(targets) { target ->
                            Text(
                                target.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val current = loadFolders()
                                        val srcRemoved = current.map {
                                            if (it.id == source.id) it.copy(members = it.members.filterNot { m -> m == talker }) else it
                                        }
                                        val finalList = srcRemoved.map {
                                            if (it.id == target.id) it.copy(members = (it.members + talker).distinct().sorted()) else it
                                        }
                                        saveFolders(finalList)
                                        syncFoldersToDatabase()
                                        showToast("已移到「${target.name}」")
                                        dismiss()
                                    }
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            )
        }
    }

    private val dbDiagLast = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun diagDb(kind: String, key: String, msg: String) {
        val now = System.currentTimeMillis()
        val k = kind + "|" + key
        val last = dbDiagLast[k] ?: 0L
        if (now - last < 10000) return
        dbDiagLast[k] = now
        diagFile(msg)
    }

    // Called by WeDatabaseListenerApi when WeChat inserts a conversation row
    override fun onInsert(table: String, values: ContentValues) {
        if (table != ConversationTable.NAME) return
        val username = values.getAsString(ConversationTable.USERNAME) ?: return
        if (isFolderId(username)) return  // skip our own folder row writes
        WeLogger.i(TAG, "dbListener onInsert row username=$username")
        diagDb("ins", username, "db onInsert u=" + username)
        scheduleRefresh(username)
    }

    // Called by WeDatabaseListenerApi when WeChat updates conversation rows
    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != ConversationTable.NAME) return
        // Skip updates that target only folder rows
        val targetUsername = values.getAsString(ConversationTable.USERNAME)
            ?: whereArgs?.singleOrNull()?.takeIf {
                whereClause?.contains(ConversationTable.USERNAME, ignoreCase = true) == true
            }
        if (targetUsername != null && isFolderId(targetUsername)) return
        val unreadKeys = listOf("unReadCount", "unReadMuteCount", "digest", "conversationTime")
        val touches = values.keySet().any { unreadKeys.contains(it) }
        WeLogger.i(TAG, "dbListener onUpdate row target=$targetUsername touchesUnread=$touches values=${values.keySet().take(6).joinToString()}")
        diagDb("upd", targetUsername ?: "?", "db onUpdate u=" + targetUsername + " touches=" + touches + " keys=" + values.keySet().take(8).joinToString(","))
        if (!touches) return  // 仅摘要相关列变化才刷新(消息风暴降频)
        diagDb("upd", targetUsername ?: "?", "db onUpdate u=" + targetUsername + " touches=" + touches + " keys=" + values.keySet().take(8).joinToString(","))
        scheduleRefresh(targetUsername)
    }

    private fun scheduleRefresh(username: String?) {
        WeLogger.i(TAG, "scheduleRefresh username=$username")
        val handler = refreshHandler ?: run { WeLogger.i(TAG, "scheduleRefresh no handler"); return }
        if (loadFolders().isEmpty()) { WeLogger.i(TAG, "scheduleRefresh no folders"); return }
        if (username == null) {
            refreshAllFolders.set(true)
        } else {
            synchronized(pendingRefreshLock) { pendingRefreshMembers += username }
        }
        handler.removeCallbacksAndMessages(REFRESH_TASK_TOKEN)
        handler.postAtTime(
            ::doRefreshFolderSummaries,
            REFRESH_TASK_TOKEN,
            SystemClock.uptimeMillis() + REFRESH_DEBOUNCE_MS
        )
    }

    private fun doRefreshFolderSummaries() {
        WeLogger.i(TAG, "doRefresh begin")
        diagFile("refresh begin")
        if (!WeDatabaseApi.isReady) { WeLogger.i(TAG, "doRefresh db not ready"); return }
        val folders = loadFolders()
        if (folders.isEmpty()) { WeLogger.i(TAG, "doRefresh no folders"); return }
        val changedMembers = synchronized(pendingRefreshLock) {
            pendingRefreshMembers.toSet().also { pendingRefreshMembers.clear() }
        }
        val refreshAll = refreshAllFolders.getAndSet(false)

        val nowGap = SystemClock.uptimeMillis()
        if (nowGap - lastDoRefreshAt < MIN_REFRESH_GAP) {
            synchronized(pendingRefreshLock) {
                pendingRefreshMembers += changedMembers
                if (refreshAll) refreshAllFolders.set(true)
            }
            val h = refreshHandler ?: return@doRefreshFolderSummaries
            h.removeCallbacksAndMessages(REFRESH_TASK_TOKEN)
            h.postAtTime(::doRefreshFolderSummaries, REFRESH_TASK_TOKEN, lastDoRefreshAt + MIN_REFRESH_GAP)
            diagFile("refresh throttled")
            return
        }
        lastDoRefreshAt = nowGap

        // A custom SQL rule may depend on any rconversation column. Reconcile it before using the
        // reverse index, because this write may have changed membership rather than just a summary.
        if (folders.any { it.type == FolderType.SQL } ||
            changedMembers.any { it !in folderByMember } && folders.any { it.type != FolderType.MANUAL }
        ) {
            reconcileFolders(folders)
            return
        }

        val affectedFolderIds = if (refreshAll) {
            membersByFolder.keys
        } else {
            changedMembers.mapNotNullTo(linkedSetOf()) { folderByMember[it] }
        }
        if (affectedFolderIds.isEmpty()) {
            diagFile("refresh noAffected changed=" + changedMembers.take(5).joinToString(",") + " fb=" + folderByMember.size + " mb=" + membersByFolder.size)
            WeLogger.i(TAG, "doRefresh no affected changed=${changedMembers.take(3)} " +
                "fbSize=${folderByMember.size} mbSize=${membersByFolder.size} " +
                "fbHasFirst=${changedMembers.firstOrNull()?.let { folderByMember.containsKey(it) }} " +
                "cfgFolders=${folders.size} fbKeys=${folderByMember.keys.take(4)}")
            return
        }

        runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                val affectedMembers = membersByFolder.filterKeys { it in affectedFolderIds }
                WeDatabaseApi.transaction {
                    affectedMembers.forEach { (folderId, members) ->
                        reanchorFolderMembers(folderId, members)
                    }
                    val summaries = readFolderSummaries(affectedMembers)
                    affectedFolderIds.forEach { folderId ->
                        writeFolderSummaryRow(folderId, summaries[folderId] ?: FolderSummary())
                    }
                }
            }
            WeConversationApi.reloadConversations()
            // 8.0.78: notify does not rebind the static home list - force a redraw so the
            // dispatchDraw retitle pass re-injects the freshly computed summary. The digest
            // cache must be dropped first or retitle re-injects the pre-refresh summary.
            forceHomeRefresh("new-msg")
            WeLogger.d(
                TAG,
                "refreshed ${affectedFolderIds.size} folders for ${changedMembers.size} members in " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }.onFailure {
            WeLogger.e(TAG, "failed to refresh folder summaries", it)
        }
    }

    /**
     * Restores [ConversationTable.PARENT_REF] = [folderId] for any member whose row was
     * replaced by WeChat's own conversation update without a parentRef. Only rows where
     * parentRef is currently NULL or '' are touched — rows already mapped to this folder
     * (or to another folder) are left unchanged.
     */
    private fun reanchorFolderMembers(folderId: String, members: List<String>) {
        if (members.isEmpty()) return
        members.chunked(SQLITE_BIND_CHUNK_SIZE - 1).forEach { chunk ->
            WeDatabaseApi.execStatement(
                """
                UPDATE ${ConversationTable.NAME}
                SET ${ConversationTable.PARENT_REF}=?
                WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})
                  AND (${ConversationTable.PARENT_REF} IS NULL OR ${ConversationTable.PARENT_REF}='')
                """.trimIndent(),
                arrayOf(folderId, *chunk.toTypedArray())
            )
        }
    }

    private fun startRefreshThread() {
        val thread = HandlerThread("wekit-folder-refresh").also {
            it.start()
            refreshThread = it
        }
        refreshHandler = Handler(thread.looper)
    }

    private fun stopRefreshThread() {
        refreshHandler?.removeCallbacksAndMessages(null)
        refreshHandler = null
        refreshThread?.quitSafely()
        refreshThread = null
        synchronized(pendingRefreshLock) { pendingRefreshMembers.clear() }
        refreshAllFolders.set(false)
    }

    override fun onQuery(sql: String): String? {
        if (suppressQueryRewrite.get()!!) return null

        val folderId = activeFolderId ?: return null
        return rewriteContainerSql(sql, folderId).takeIf { it != sql }
    }

    override fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        val startCtx = param.thisObject as? Context
        if (cachedHomeIntents.isEmpty()) startCtx?.let { loadCachedIntents(it) }
        WeLogger.i(TAG, "startActivity: " + intent.component?.className + " uri=" + intent.toUri(0) + " keys=" + (intent.extras?.keySet()?.joinToString(",")))
        val targetCls = intent.component?.className
        if (targetCls != null && (targetCls == BRAND_FLUTTER_UI || targetCls == BRAND_SERVICE_UI || targetCls == ENTERPRISE_UI)) {
            cachedHomeIntents[targetCls] = Intent(intent)
            if (startCtx != null) saveCachedIntent(startCtx, targetCls, intent)
            WeLogger.i(TAG, "stack for " + targetCls + "\\n" + Throwable().stackTrace.take(25).joinToString("\\n") { "    at " + it.toString() })
            val hpi = runCatching { intent.getParcelableExtra<Parcelable>("page_info") }.getOrNull()
            WeLogger.i(TAG, "homePi cls=" + (hpi?.javaClass?.name) + " sameLoader=" + (hpi?.javaClass?.classLoader === startCtx?.classLoader) + " val=" + hpi)
            WeLogger.i(TAG, "homeFlags=" + intent.flags + " act=" + intent.action + " data=" + intent.dataString)
            dumpPageInfo("homePi", hpi)
            runCatching { hpi?.javaClass?.declaredFields?.forEach { f -> f.isAccessible = true; WeLogger.i(TAG, "  piField " + f.name + "=" + f.get(hpi)) } }
        }
        val folderId = readFolderIdFromIntent(intent) ?: return
        val componentName = intent.component?.className
        if (componentName != CONTAINER_UI_NAME) {
            activeFolderId = folderId
            intent.setClassName(param.thisObject as? Context ?: return, CONTAINER_UI_NAME)
        }
        applyFolderContainerIntent(intent, folderId)
    }

    private fun hookMainUiRefresh() {
        MainUI::class.reflekt().firstMethod("onResume").hookAfter {
            syncFoldersToDatabase()
        }
    }

    private fun hookOpenFolder() {
        // 捕获首页 LauncherUI 实例：文件夹点击重走微信打开链（ok0.l1.c）时用它作上下文。
        runCatching {
            val onCreate = LauncherUI::class.java.declaredMethods.firstOrNull {
                it.name == "onCreate" && it.parameterCount == 1
            }
            if (onCreate != null) {
                onCreate.hookAfter {
                    cachedLauncherUi = thisObject
                    WeLogger.i(TAG, "captured LauncherUI " + (thisObject?.javaClass?.name))
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook LauncherUI.onCreate failed", it) }

        LauncherUI::class.reflekt().firstMethod("startChatting").hookBefore {
            WeLogger.i(TAG, "startChatting(Launcher): username=" + (args.firstOrNull() as? String) + " src=" + thisObject?.javaClass?.simpleName)
            interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                result = null
            }
        }

        BaseConversationUI::class.reflekt().firstMethod("startChatting").hookBefore {
            WeLogger.i(TAG, "startChatting(Base): username=" + (args.firstOrNull() as? String) + " src=" + thisObject?.javaClass?.simpleName)
            interceptFolderChatOpen(args.firstOrNull() as? String, thisObject) {
                result = null
            }
        }
        // WX 8.0.78+: conversation opens run through LauncherUI.NewChattingTabUI. The tap path is
        // pf.a (mStartChattingRunnable) -> NewChattingTabUI.e(...) == prepareChattingFragment.
        // Cancelling e() alone is NOT safe: pf.a dereferences the fragment view right after e()
        // (newChattingTabUI.o.getView().findViewById) and NPEs because o stays null. So intercept
        // pf.a itself: cancel the whole runnable (nothing runs afterwards) and open the folder
        // container. e() stays hooked for observability only. 8.0.77 startChatting hooks above
        // remain for older WeChat versions.
        runCatching {
            val pfCls = Class.forName("com.tencent.mm.ui.pf")
            val pfRun = pfCls.declaredMethods.firstOrNull { it.name == "a" && it.parameterCount == 0 }
            val ntCls = Class.forName("com.tencent.mm.ui.NewChattingTabUI")
            val eM = ntCls.declaredMethods.firstOrNull { it.name == "e" && it.parameterCount == 3 }
            if (eM != null) {
                eM.isAccessible = true
                de.robv.android.xposed.XposedBridge.hookMethod(eM, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[1] as? Intent ?: return@beforeHookedMethod
                        val username = intent.getStringExtra("Chat_User") ?: return@beforeHookedMethod
                        WeLogger.i(TAG, "NewChattingTabUI.e: username=" + username + (if (isFolderId(username)) " (observe only)" else ""))
                    }
                })
            }
            if (pfRun != null) {
                pfRun.isAccessible = true
                de.robv.android.xposed.XposedBridge.hookMethod(pfRun, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pfInst = param.thisObject ?: return@beforeHookedMethod
                        val tab = findFieldOfType(pfInst, "com.tencent.mm.ui.NewChattingTabUI") ?: return@beforeHookedMethod
                        val username = runCatching { tab.javaClass.getField("h").get(tab) as? String }.getOrNull() ?: return@beforeHookedMethod
                        WeLogger.i(TAG, "pf.a(startChattingRunnable): Chat_User=" + username)
                        if (isFolderId(username)) {
                            param.result = null
                            activeFolderId = username
                            val act = runCatching { tab.javaClass.getField("a").get(tab) as? Activity }.getOrNull()
                            if (act != null) launchFolderContainer(act, username)
                            else WeLogger.w(TAG, "pf.a: folder id but no host activity")
                        }
                    }
                })
                WeLogger.i(TAG, "pf.a hooked (8.0.78 folder-open runnable)")
            }
        }.onFailure { WeLogger.w(TAG, "hook NewChattingTabUI.e/pf.a failed", it) }
    }
    // 最终兜底：某些分享/转发路径直接启动 ChattingUI(folderId)（不经 pf.a/NewChattingTab），
    // 假会话没有真实聊天线程会在触摸时崩。拦 ChattingUI 打开：folder 目标 -> 弹成员选择器，
    // 选定后以原 intent（保留分享草稿 extra）重定向到该成员聊天窗。
    private var lastChattingFolderPickTs = 0L
    private fun hookChattingUiFolderRedirect() {
        runCatching {
            val cCls = Class.forName("com.tencent.mm.ui.chatting.ChattingUI")
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                cCls, "onCreate", android.os.Bundle::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val act = param.thisObject as? android.app.Activity ?: return
                            val intent = act.intent ?: return
                            val username = intent.getStringExtra("Chat_User") ?: return
                            if (!isFolderId(username)) return
                            val folder = folderById(username) ?: return
                            val now = System.currentTimeMillis()
                            if (now - lastChattingFolderPickTs < 1500) return
                            lastChattingFolderPickTs = now
                            WeLogger.i(TAG, "ChattingUI folder open intercepted: " + username)
                            val origIntent = android.content.Intent(intent)
                            act.window?.decorView?.post({
                                try {
                                    showFolderMemberPicker(act, folder) { selected ->
                                        runCatching {
                                            android.content.Intent(origIntent).putExtra("Chat_User", selected).let {
                                                act.startActivity(it)
                                                act.finish()
                                            }
                                        }.onFailure { e -> WeLogger.e(TAG, "ChattingUI redirect to member failed", e) }
                                    }
                                } catch (e: Throwable) { WeLogger.e(TAG, "ChattingUI folder picker err", e) }
                            })
                        } catch (e: Throwable) { WeLogger.e(TAG, "ChattingUI folder intercept err", e) }
                    }
                }
            )
            WeLogger.i(TAG, "ChattingUI folder-open redirect hooked (8.0.78 fallback)")
        }.onFailure { WeLogger.w(TAG, "hook ChattingUI folder redirect failed", it) }
    }

    // Find the first field whose declared type matches targetTypeName (e.g. the NewChattingTabUI
    // stored inside the pf runnable). Obfuscated field names differ per WeChat build, so match on
    // Find the first field whose declared type matches targetTypeName (e.g. the NewChattingTabUI
    // stored inside the pf runnable). Obfuscated field names differ per WeChat build, so match on
    // the resolved class name instead of the field name.
    private fun findFieldOfType(holder: Any, targetTypeName: String): Any? {
        var cls: Class<*>? = holder.javaClass
        while (cls != null && cls != Any::class.java) {
            for (fld in cls.declaredFields) {
                if (fld.type.name == targetTypeName) {
                    return runCatching { fld.isAccessible = true; fld.get(holder) }.getOrNull()
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private var serviceSHookArmed = false
    private fun ensureConversationServiceSHook() {
        if (serviceSHookArmed) return
        serviceSHookArmed = true
        runCatching {
            val h9c = Class.forName("b41.h9")
            val inst = h9c.getMethod("b").invoke(null)
            val svc = inst.javaClass.getMethod("s").invoke(inst)
            val svcCls = svc.javaClass
            val sM = svcCls.methods.firstOrNull { m -> m.name == "s" && m.parameterCount == 4 && m.parameterTypes[2] == String::class.java }
            if (sM != null) {
                de.robv.android.xposed.XposedBridge.hookMethod(sM, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val folderId = activeFolderId ?: return@beforeHookedMethod
                        val ref = param.args[2] as? String ?: return@beforeHookedMethod
                        if (ref == "conversationboxservice") {
                            param.args[2] = folderId
                            WeLogger.i(TAG, "svc.s superUsername rewrite: conversationboxservice -> " + folderId)
                        }
                    }
                })
                WeLogger.i(TAG, "ConversationService.s hooked on " + svcCls.name)
            } else {
                WeLogger.w(TAG, "ConversationService.s(int,List,String,int) not found on " + svcCls.name)
                serviceSHookArmed = false
            }
        }.onFailure { WeLogger.w(TAG, "hook ConversationService.s failed", it); serviceSHookArmed = false }
    }

    private inline fun interceptFolderChatOpen(
        username: String?,
        source: Any?,
        cancelOriginal: () -> Unit
    ) {
        if (username == null) return
        if (isFolderId(username)) {
            activeFolderId = username
            launchFolderContainer(source, username)
            cancelOriginal()
            return
        }
        // 容器内点击成员：微信容器的打开逻辑对公众号/企业微信等特殊会话会走错路径，
        // 改为直接用 ChattingUI 打开，由微信自身判断会话类型（公众号/企业微信/群聊）。
        if (activeFolderId != null) {
            val context = source as? Context
            if (context != null) {
                WeApi.openContact(context, username, WeApi.OpenContactDestination.CONVERSATION)
                cancelOriginal()
            }
        }
    }

    private fun hookConversationPages() {
        // WX 8.0.78: the folder container adapter (u0.q) loads its list through the conversation
        // service s(int,List,String,int) with a HARDCODED superUsername "conversationboxservice"
        // (service-box semantics), ignoring the Contact_User folder id on the container intent.
        // Ensure the service query rewrite (see ensureConversationServiceSHook) is armed whenever
        // the container adapter refreshes, so queries hit parentRef=<folderId> rows again.
        runCatching {
            val u0Cls = Class.forName("com.tencent.mm.ui.conversation.u0")
            val qm = u0Cls.declaredMethods.firstOrNull { it.name == "q" && it.parameterCount == 0 }
            if (qm != null) {
                qm.isAccessible = true
                de.robv.android.xposed.XposedBridge.hookMethod(qm, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (activeFolderId != null) ensureConversationServiceSHook()
                    }
                })
                WeLogger.i(TAG, "u0.q hooked (8.0.78 folder list refresh)")
            }
        }.onFailure { WeLogger.w(TAG, "hook u0.q failed", it) }
        ConvBoxServiceConversationUI::class.hookBeforeOnCreate {
            val activity = thisObject as? Activity ?: return@hookBeforeOnCreate
            activeFolderId = readFolderIdFromIntent(activity.intent) ?: activeFolderId
        }

        listOf(BRAND_FLUTTER_UI, BRAND_SERVICE_UI).forEach { cls ->
            runCatching { Class.forName(cls).getMethod("onResume").hookAfter { WeLogger.i(TAG, "aggPage onResume " + (thisObject?.javaClass?.name)) } }
        }

        runCatching {
            val bcu = Class.forName("com.tencent.mm.ui.conversation.BaseConversationUI")
            bcu.getMethod("onResume").hookAfterDirectly {
                val activity = thisObject as? BaseConversationUI ?: return@hookAfterDirectly
                WeLogger.i(TAG, "onResume act=" + activity.javaClass.simpleName + " folder=" + activeFolderId + " extra=" + activity.intent?.getStringExtra(WeChatIntentExtra.CONTACT_USER))
                activeFolderId = activeFolderId ?: readFolderIdFromIntent(activity.intent)
                configureFolderActivity(activity)
                diagFile("onResume act=" + activity.javaClass.simpleName + " folder=" + activeFolderId + " extra=" + activity.intent?.getStringExtra(WeChatIntentExtra.CONTACT_USER))
                if (activity is ConvBoxServiceConversationUI) dumpContainerViews(activity)
                configureFolderActivity(activity)
            }
            WeLogger.i(TAG, "BaseConversationUI.onResume hooked (getMethod)")
            bcu.getMethod("onDestroy").hookAfterDirectly {
                if (thisObject is ConvBoxServiceConversationUI) {
                    activeFolderId = null
                    // WeChat zeroes the home folder-row badge in its UI state on leaving the box
                    // page (data untouched). Push the DB badge back once home resumes.
                    forceHomeRefresh("leave-folder")
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook BaseConversationUI onResume/onDestroy failed", it) }
        // Diagnose the method that opens the official-account flutter page from the home tab.
        runCatching {
            val mmLoader = Class.forName("com.tencent.mm.ui.LauncherUI").classLoader
            val clz = Class.forName("ok0\u0024l1", false, mmLoader)
            val ws = clz.declaredMethods.filter { it.name == "w" }
            WeLogger.i(TAG, "ok0.l1 methods w=" + ws.size + " loader=" + clz.classLoader?.javaClass?.name)
            ws.forEach { m ->
                m.hookBefore {
                    WeLogger.i(TAG, "ok0.l1.w args=" + (args.map { a: Any? -> (a?.javaClass?.name ?: "null") + ":" + a }.joinToString(" | ")) + " this=" + thisObject?.javaClass?.name)
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook ok0.l1.w failed", it) }
        // Capture the current process's FlutterPageInfo session id (fields d/e) whenever WeChat
        // constructs one, so folder taps can refresh cached page_infos with live values.
        runCatching {
            val mmLoader = Class.forName("com.tencent.mm.ui.LauncherUI").classLoader
            val fpi = Class.forName("com.tencent.mm.flutter.ui.FlutterPageInfo", false, mmLoader)
            fpi.declaredConstructors.forEach { c ->
                c.hookAfter {
                    runCatching {
                        val thisObj = thisObject
                        // Dump every field so we can diff pre-warmed vs homepage-opened page_infos.
                        val sb = StringBuilder("FlutterPageInfo fields ")
                        fpi.declaredFields.forEach { fd ->
                            runCatching {
                                fd.isAccessible = true
                                val v = fd.get(thisObj)
                                val sv = if (v == null) "null" else v.toString().take(48)
                                sb.append("[").append(fd.name).append(":").append(fd.type.simpleName).append("=").append(sv).append("] ")
                            }
                        }
                        WeLogger.i(TAG, sb.toString())
                        val d = fpi.getDeclaredField("d").apply { isAccessible = true }.get(thisObj) as? String
                        val e = fpi.getDeclaredField("e").apply { isAccessible = true }.get(thisObj) as? String
                        val f = fpi.getDeclaredField("f").apply { isAccessible = true }.get(thisObj) as? String
                        if (d != null && d.isNotBlank()) currentPageInfoId = d
                        if (e != null) currentPageInfoE = e?.toIntOrNull()
                        // Parcel-deserialized copies carry no engine binding and must NOT
                        // replace WeChat's own live object (they fail to open).
                        val st = Thread.currentThread().stackTrace
                        val isParcelCopy = st.any { it.methodName == "createFromParcel" }
                        if (!isParcelCopy && thisObj is Parcelable) {
                            when (f) {
                                "biz" -> livePageInfoBiz = thisObj as Parcelable
                                "brand_service" -> livePageInfoBrandService = thisObj as Parcelable
                            }
                        }
                        WeLogger.i(TAG, "FlutterPageInfo ctor d=" + d + " e=" + e + " f=" + f + " parcel=" + isParcelCopy)
                        // Trace the construction call stack: pre-warmed (startup) vs homepage-tap
                        // vs folder-tap differ in how WeChat opens the flutter page; the frame
                        // unique to the homepage path is the open-chain entry we can call.
                        runCatching {
                            val frames = st.take(40).joinToString(" | ") { it.className + "." + it.methodName }
                            WeLogger.i(TAG, "FlutterPageInfo stack f=" + f + " " + frames)
                            // Dump ctor args: one of them may carry the brandservice plugin
                            // ClassLoader, which is the only way to load the open-chain class
                            // (ok0$l1) that lives in the plugin dex, not the host loader.
                            args?.forEachIndexed { idx, a ->
                                if (a != null) {
                                    val ld = runCatching { a.javaClass.classLoader?.toString()?.take(120) }.getOrNull()
                                    WeLogger.i(TAG, "FlutterPageInfo arg" + idx + " cls=" + a.javaClass.name + " ld=" + ld)
                                    val l = a.javaClass.classLoader
                                    if (l != null && l !== mmLoader && l !== ClassLoaders.BOOT && ctorArgPluginLoader == null) {
                                        ctorArgPluginLoader = l
                                    }
                                }
                            }
                            // Find the open-chain frame (ok0.l1.w) and lazily hook it to capture
                            // the real invocation prototype (params + thisObject).
                            val openFrame = st.take(40).firstOrNull { it.methodName == "w" }
                            if (openFrame != null) {
                                hookOpenChain(openFrame)
                            }
                            // The plugin loader can't come from ctor args (they are plain Strings);
                            // fish it out of a Flutter Activity's fields instead.
                            if (ctorArgPluginLoader == null) {
                                pluginLoaderFromActivity(mmLoader)
                            }
                        }
                        // WeChat may set the e field shortly after construction (engine attach);
                        // capture it late so folder taps can stamp a live value.
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching {
                                val e2 = fpi.getDeclaredField("e").apply { isAccessible = true }.get(thisObj) as? String
                                val f2 = fpi.getDeclaredField("f").apply { isAccessible = true }.get(thisObj) as? String
                                if (e2 != null) currentPageInfoE = e2?.toIntOrNull()
                                if (thisObj is Parcelable) {
                                    when (f2) {
                                        "biz" -> livePageInfoBiz = thisObj as Parcelable
                                        "brand_service" -> livePageInfoBrandService = thisObj as Parcelable
                                    }
                                }
                                WeLogger.i(TAG, "FlutterPageInfo late f=" + f2 + " e=" + e2)
                            }
                        }, 1500)
                    }
                }
            }
            WeLogger.i(TAG, "FlutterPageInfo ctor hooked=" + fpi.declaredConstructors.size)
            // Trace method calls on FlutterPageInfo to find the "register" step WeChat does when
            // opening from the homepage (absent for pre-warmed objects, which fail to open).
            fpi.declaredMethods.forEach { m ->
                m.hookBefore {
                    runCatching {
                        val a = args?.map { it?.javaClass?.simpleName ?: "null" }?.joinToString(",")
                        WeLogger.i(TAG, "FlutterPageInfo.call " + m.name + "(" + a + ")")
                    }
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook FlutterPageInfo ctor failed", it) }
    }

    // The folder container (ConvBoxServiceConversationUI) does NOT use the homepage's
    // ConversationLongClickListener that WeConversationContextMenuApi hooks; it builds its long-press
    // menu through the shared MMPopupMenu.showMenu(...). We hook that chokepoint, gated on
    // activeFolderId (null on the homepage, so that path is untouched), and inject a "remove from
    // folder" item by wrapping the menu-create listener and the (obfuscated) select callback.
    private fun hookConversationClick() {
        val clazz = runCatching {
            Class.forName("com.tencent.mm.ui.conversation.ConversationClickListener")
        }.getOrNull() ?: run {
            WeLogger.i(TAG, "ConversationClickListener not found")
            return
        }
        clazz.declaredMethods.forEach { m ->
            runCatching {
                m.hookBeforeDirectly {
                    val argsStr = args.joinToString("|") { a: Any? -> a?.javaClass?.simpleName ?: "null" }
                    WeLogger.i(TAG, "ConvClick: " + m.name + " args=" + argsStr)
                    diagDb("conv", m.name, "ConvClick " + m.name + " args=" + argsStr + " self=" + thisObject?.javaClass?.name)
                }
            }.onFailure { }
        }
    }

    private fun hookFolderFragmentMethods() {
        val clazz = runCatching {
            Class.forName("com.tencent.mm.ui.conversation.ConvBoxServiceConversationFmUI")
        }.getOrNull() ?: run {
            WeLogger.i(TAG, "ConvBoxFmUI not found")
            return
        }
        clazz.declaredMethods.forEach { m ->
            val mn = m.name
            if (mn.startsWith("on") && mn.length <= 12) return@forEach
            runCatching {
                m.hookBeforeDirectly {
                    val raw = args.joinToString("|") { a: Any? -> a?.javaClass?.simpleName ?: "null" }
                    val argsStr = if (raw.length > 80) raw.substring(0, 80) else raw
                    WeLogger.i(TAG, "FmUI: " + mn + " args=" + argsStr)
                    diagDb("fm", mn, "FmUI " + mn + " args=" + argsStr)
                }
            }.onFailure { }
        }
    }

    // Special rows in a folded folder ("公众号" / "服务号" / "学校通知") must open the same
    // aggregated pages as their homepage counterparts. The container switches views on item
    // click (no new Activity), so we hook the shared AdapterView.performItemClick while a
    // folder container is open, launch the cached homepage intent for those rows, and
    // invalidate the position so WeChat's own handler has nothing left to open.
    // Special rows in a folded folder ("公众号" / "服务号" / "学校通知") must open the same
    // aggregated pages as their homepage counterparts. The container switches views on item
    // click (no new Activity), so we hook the shared AdapterView.performItemClick while a
    // folder container is open, launch the cached homepage intent for those rows, and
    // invalidate the position so WeChat's own handler has nothing left to open.
    // Special rows in a folded folder ("officialaccounts" / "service_officialaccounts" / the
    // school row) must open the same aggregated pages as their homepage counterparts. The
    // container switches views on item click (no new Activity), so we wrap the
    // AdapterView.OnItemClickListener with a proxy that, while a folder container is open,
    // launches the cached homepage intent for those rows and skips the original listener
    // (whose invalid-position fallback otherwise clears the folder list on return).
    // Special rows in a folded folder ("officialaccounts" / "service_officialaccounts" / the
    // school row) must open the same aggregated pages as their homepage counterparts. The
    // container switches views on item click (no new Activity). We wrap the
    // AdapterView.OnItemClickListener that WeChat installs (via the public
    // setOnItemClickListener, so no hidden-API reflection is needed) with a proxy that,
    // while a folder container is open, launches the cached homepage intent for those rows
    // and skips the original listener.
    // WeChat's folder container routes a tap through AdapterView.performItemClick with the
    // real position, then separately calls the OnItemClickListener with position=-1. So we
    // detect the special row from the real position, launch the homepage target, and make the
    // listener proxy swallow the bogus onItemClick(-1) so the container list stays untouched.
    private fun hookFolderItemClick() {
        val perform = runCatching {
            AdapterView::class.java.getMethod(
                "performItemClick", View::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
        }.getOrNull() ?: return
        perform.hookBeforeDirectly {
            if (activeFolderId == null) return@hookBeforeDirectly
            val av = thisObject as? AdapterView<*> ?: return@hookBeforeDirectly
            val position = args[1] as? Int ?: return@hookBeforeDirectly
            val adapter = av.adapter ?: return@hookBeforeDirectly
            if (position < 0 || position >= adapter.count) return@hookBeforeDirectly
            val item = adapter.getItem(position) ?: return@hookBeforeDirectly
            val talker = item.reflekt()
                .firstFieldOrNull { name = "field_username"; superclass() }
                ?.get() as? String ?: return@hookBeforeDirectly
            // 公众号/服务号：优先重走微信自己的打开链（构造引擎绑定的 page_info，重启后也有效）。
            if (talker == "officialaccounts" || talker == "service_officialaccounts") {
                if (openViaChain(talker, av.context)) {
                    WeLogger.i(TAG, "folderItemClick via openChain talker=" + talker)
                    suppressNextClick = true
                    return@hookBeforeDirectly
                }
            }
            val homeIntent = homeIntentFor(talker, av.context) ?: return@hookBeforeDirectly
            WeLogger.i(TAG, "folderItemClick intercept talker=" + talker + " -> " + homeIntent.component?.className)
            val piDbg = runCatching { homeIntent.getParcelableExtra<Parcelable>("page_info") }.getOrNull()
            WeLogger.i(TAG, "open try cls=" + homeIntent.component?.className + " pi=" + (piDbg?.javaClass?.name) + " uri=" + homeIntent.toUri(0))
            WeLogger.i(TAG, "openFlags=" + homeIntent.flags + " act=" + homeIntent.action + " data=" + homeIntent.dataString)
            WeLogger.i(TAG, "openCtx act=" + (av.context is Activity) + " " + av.context.javaClass.name)
            runCatching {
                val ctx = av.context
                if (ctx !is Activity) homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(homeIntent)
                WeLogger.i(TAG, "opened ok " + homeIntent.component?.className)
            }.onFailure { WeLogger.w(TAG, "failed to open aggregated page", it) }
            suppressNextClick = true
        }

        // Proxy the installed item click listener; swallow the -1 click that follows our launch.
        val setListener = runCatching {
            AdapterView::class.java.getMethod("setOnItemClickListener", AdapterView.OnItemClickListener::class.java)
        }.getOrNull() ?: return
        setListener.hookBeforeDirectly {
            if (activeFolderId == null) return@hookBeforeDirectly
            val original = args.getOrNull(0) as? AdapterView.OnItemClickListener ?: return@hookBeforeDirectly
            if (original is Proxy) return@hookBeforeDirectly
            val av = thisObject as? AdapterView<*> ?: return@hookBeforeDirectly
            val proxy = Proxy.newProxyInstance(
                original.javaClass.classLoader,
                arrayOf(AdapterView.OnItemClickListener::class.java)
            ) { _, method, methodArgs ->
                if (method.name == "onItemClick") {
                    if (suppressNextClick) {
                        suppressNextClick = false
                        return@newProxyInstance null
                    }
                    method.invoke(original, *(methodArgs ?: emptyArray()))
                }
                null
            }
            args[0] = proxy
        }
    }

    private fun homeIntentFor(talker: String, context: Context): Intent? {
        val cls = when (talker) {
            "officialaccounts" -> BRAND_FLUTTER_UI
            "service_officialaccounts" -> BRAND_SERVICE_UI
            "gh_158599a58f81" -> ENTERPRISE_UI
            else -> return null
        }
        val cached = cachedHomeIntents[cls]
        if (cached != null) {
            refreshPageInfoId(cached, cls)
            dumpIntent("USED", cached)
            return cached
        }
        return Intent().setClassName(context, cls)
    }

    // The page_info carries a process-scoped session uuid (field d) that WeChat regenerates per
    // process; a cached one from a previous process is rejected after restart (tapping opens a
    // plain conversation or a blank flutter page). WeChat's own live FlutterPageInfo objects
    // (engine state intact) are preferred; otherwise refresh d to the live process uuid and set
    // e to a fresh value (a null/previous-process e is rejected by the flutter page).
    private fun refreshPageInfoId(intent: Intent, cls: String) {
        val live = when (cls) {
            BRAND_FLUTTER_UI -> livePageInfoBiz ?: livePageInfoBrandService
            BRAND_SERVICE_UI -> livePageInfoBrandService ?: livePageInfoBiz
            else -> null
        }
        if (live != null) {
            runCatching {
                val cur = intent.getParcelableExtra<Parcelable>("page_info") ?: return
                // WeChat's pre-warmed biz page_info has e=null; stamp a fresh value so the
                // flutter page accepts it, then swap it in.
                val fe = live.javaClass.getDeclaredField("e")
                fe.isAccessible = true
                fe.set(live, freshPageInfoE())
                // 页面类型（f）必须匹配目标 Activity：服务号页面用 brand_service，公众号用 biz。
                // 微信首页只构造过 biz 的 live 对象时，服务号点击兜底复用它的 session(d)，
                // 但 f 要改成 brand_service，否则 BrandServiceTimelineUI 拒绝。
                val targetF = when (cls) {
                    BRAND_SERVICE_UI -> "brand_service"
                    else -> "biz"
                }
                runCatching {
                    val ff = live.javaClass.getDeclaredField("f")
                    ff.isAccessible = true
                    if (ff.get(live) != targetF) {
                        ff.set(live, targetF)
                        WeLogger.i(TAG, "pageInfo f switched to " + targetF)
                    }
                }
                intent.putExtra("page_info", live)
                WeLogger.i(TAG, "pageInfo replaced with live biz obj e=" + fe.get(live))
                return
            }.onFailure { WeLogger.w(TAG, "replace pageInfo failed", it) }
        }
        val curD = currentPageInfoId ?: UUID.randomUUID().toString()
        if (currentPageInfoId == null) currentPageInfoId = curD
        if (curD == null) {
            WeLogger.i(TAG, "pageInfo refresh skipped no live id")
            return
        }
        runCatching {
            val pi = intent.getParcelableExtra<Parcelable>("page_info") ?: return
            val fd = pi.javaClass.getDeclaredField("d")
            fd.isAccessible = true
            fd.set(pi, curD)
            val fe = pi.javaClass.getDeclaredField("e")
            fe.isAccessible = true
            fe.set(pi, freshPageInfoE())
            WeLogger.i(TAG, "pageInfo d/e refreshed=" + curD + " / " + fe.get(pi))
        }.onFailure { WeLogger.w(TAG, "refresh pageInfo d/e failed", it) }
    }

    // WeChat's page_info.e is a String (e.g. "88463146"). Fresh value per tap.
    private fun freshPageInfoE(): String = (System.currentTimeMillis() % 100000000L).toString()

    // Lazily hook the flutter open-chain method (ok0$l1.w) once the brandservice plugin is
    // loaded. The class lives in the plugin dex; resolve it via a stack-frame class name plus
    // the plugin ClassLoader found on a ctor arg.
    @Volatile
    private var openChainHooked = false
    private fun hookOpenChain(frame: StackTraceElement) {
        if (openChainHooked) return
        openChainHooked = true
        runCatching {
            val frameCls = frame.className // e.g. ok0$l1
            // Prefer the plugin loader; fall back to the host loader (the class may actually
            // live in the host dex and only fail early during module init before WeChat loads).
            val loader = ctorArgPluginLoader ?: Class.forName("com.tencent.mm.ui.LauncherUI").classLoader
            if (loader == null) {
                WeLogger.i(TAG, "hookOpenChain no plugin loader for " + frameCls)
                return
            }
            val clz = try {
                loader.loadClass(frameCls)
            } catch (e: Throwable) {
                if (loader !== Class.forName("com.tencent.mm.ui.LauncherUI").classLoader) {
                    Class.forName("com.tencent.mm.ui.LauncherUI").classLoader.loadClass(frameCls)
                } else null
            } ?: return
            // 诊断 + 兜底：dump 打开链类构造签名/静态字段，hook 构造记录实例，供 openViaChain 自建实例。
            runCatching {
                clz.declaredConstructors.forEach { c ->
                    WeLogger.i(TAG, "openChain ctor " + c.parameterTypes.joinToString(",") { it.name })
                }
                clz.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        WeLogger.i(TAG, "openChain staticField " + f.name + "=" + f.type.name + ":" + (f.get(null)?.toString()?.take(60) ?: "null"))
                    }
                }
            }.onFailure { WeLogger.w(TAG, "dump openChain class failed", it) }
            clz.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.hookAfter {
                        val inst = thisObject
                        if (inst != null) {
                            cachedOpenChainInstance = inst
                            WeLogger.i(TAG, "openChain ctor created inst=" + inst.javaClass.name)
                            dumpOpenChainInstance(inst)
                        }
                    }
                }
            }
            clz.declaredMethods.forEach { m ->
                m.hookBefore {
                    runCatching {
                        val a = args?.map {
                            it?.javaClass?.simpleName + ":" + runCatching { it?.toString()?.take(200) }.getOrNull()
                        }?.joinToString(" || ")
                        val static = java.lang.reflect.Modifier.isStatic(m.modifiers)
                        WeLogger.i(TAG, "openChain." + m.name + " called static=" + static + " this=" + thisObject?.javaClass?.simpleName + " args=" + a)
                        if (m.name == "b" || m.name == "h") {
                            val inst = thisObject
                            if (inst != null) cachedOpenChainInstance = inst
                        }
                        // 捕获微信打开链入口 c(LauncherUI, Class, Intent, null)：入口实例 +
                        // LauncherUI 上下文，文件夹点击时重走微信自己的打开链构造有效 page_info。
                        if (m.name == "c" && args != null) {
                            runCatching {
                                cachedOpenChainInstance = thisObject
                                cachedLauncherUi = args.getOrNull(0)
                                cachedOpenChainIntent = args.getOrNull(2) as? Intent
                                WeLogger.i(TAG, "openChain.c params=" + m.parameterTypes.joinToString(",") { it.name })
                                (args.getOrNull(2) as? Intent)?.let { dumpIntent("openChain.c-intent", it) }
                            }
                        }
                    }
                }
                WeLogger.i(TAG, "openChain hooked " + m.name + " static=" + java.lang.reflect.Modifier.isStatic(m.modifiers))
            }
        }.onFailure { WeLogger.w(TAG, "hookOpenChain failed", it) }
    }

    // Plugin ClassLoader discovered from a FlutterPageInfo ctor arg (the plugin-domain object).
    @Volatile
    private var ctorArgPluginLoader: ClassLoader? = null

    // The ok0.l1 open-chain instance WeChat used for the most recent homepage open; needed to
    // re-invoke the open-chain entry from a folder tap (its methods are instance methods).
    @Volatile
    private var cachedOpenChainInstance: Any? = null

    // WeChat's open-chain entry c(LauncherUI, Class, Intent, null) launches the flutter page
    // through its own chain (d/w), which constructs a fresh, engine-registered page_info. The
    // LauncherUI context and the intent it received are captured from the homepage open.
    @Volatile
    private var cachedLauncherUi: Any? = null
    @Volatile
    private var cachedOpenChainIntent: Intent? = null

    // Folder taps on the virtual-session rows re-invoke WeChat's own open chain: it constructs
    // the page_info, registers it with the live flutter engine, then starts the target
    // Activity. This mirrors the homepage tap exactly, so the page opens with correct content
    // even after the engine was recycled or the process restarted (a fresh ok0.l1 instance is
    // constructed when no cached one exists).
    private fun tryOpenChain(talker: String): Boolean {        return runCatching {
            val hostLoader = Class.forName("com.tencent.mm.ui.LauncherUI").classLoader
            val openClz = Class.forName("ok0.l1", false, hostLoader)
            val l1Inst = cachedOpenChainInstance ?: openClz.getDeclaredConstructor().let { c ->
                c.isAccessible = true
                c.newInstance()
            }
            val activityClsName = if (talker == "service_officialaccounts") BRAND_SERVICE_UI else BRAND_FLUTTER_UI
            val activityCls = Class.forName(activityClsName, false, hostLoader)
            val activityInst = activityCls.getDeclaredConstructor().let { c ->
                c.isAccessible = true
                c.newInstance()
            }
            val b = openClz.declaredMethods.firstOrNull { m ->
                m.name == "b" && m.parameterCount == 1 && m.parameterTypes[0].isAssignableFrom(activityCls)
            }
            if (b == null) {
                WeLogger.i(TAG, "openChain no b method for " + talker)
                return false
            }
            b.isAccessible = true
            b.invoke(l1Inst, activityInst)
            WeLogger.i(TAG, "openChain b invoked for " + talker + " inst=" + l1Inst.javaClass.name)
            true
        }.getOrElse {
            WeLogger.w(TAG, "openChain invoke failed for " + talker, it)
            false
        }
    }

    // Re-run WeChat's own open-chain entry c(LauncherUI, Class, Intent, null) captured from the
    // homepage open. WeChat's d/w inside c constructs a fresh page_info bound to the live flutter
    // engine, so the aggregated page opens with valid content without needing a pre-warm tap.
    private fun openViaChain(talker: String, context: Context): Boolean {
        val launcher = cachedLauncherUi ?: return false.also { WeLogger.i(TAG, "openViaChain no launcherUi") }
        val cls = when (talker) {
            "officialaccounts" -> BRAND_FLUTTER_UI
            "service_officialaccounts" -> BRAND_SERVICE_UI
            else -> return false.also { WeLogger.i(TAG, "openViaChain unsupported " + talker) }
        }
        return runCatching {
            val hostLoader = Class.forName("com.tencent.mm.ui.LauncherUI").classLoader
            val openClz = runCatching {
                Class.forName("ok0.l1", false, hostLoader)
            }.getOrElse { Class.forName("ok0\u0024l1", false, hostLoader) }
            // 优先用微信最近一次打开链实例；否则 new 一个（微信 c 方法内部会完成 page_info 构造）。
            val l1 = cachedOpenChainInstance ?: openClz.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            val m = openClz.declaredMethods.firstOrNull { it.name == "c" && it.parameterCount == 4 }
            if (m == null) {
                WeLogger.i(TAG, "openChain no c(4) method")
                return false
            }
            m.isAccessible = true
            // c(LauncherUI, Class, Intent, null): mirror the captured homepage call; the intent
            // carries KEY_HOME_PAGE_CLS etc. and WeChat rebuilds page_info for the target.
            val intent = Intent().setClassName(context, cls)
            val activityCls = Class.forName(cls, false, hostLoader)
            m.invoke(l1, launcher, activityCls, intent, null)
            WeLogger.i(TAG, "openChain c invoked for " + talker + " -> " + cls + " l1=" + l1.javaClass.name)
            true
        }.getOrElse {
            WeLogger.w(TAG, "openChain c failed for " + talker, it)
            false
        }
    }

    // The flutter/brand Activities declare plugin-domain objects as fields; their loaders are
    // the plugin ClassLoader that can load the open-chain class (ok0$l1).
    private fun pluginLoaderFromActivity(mmLoader: ClassLoader) {
        runCatching {
            listOf("com.tencent.mm.plugin.flutter.ui.MMFlutterViewActivity", BRAND_FLUTTER_UI).forEach { n ->
                runCatching {
                    val c = Class.forName(n, false, mmLoader)
                    WeLogger.i(TAG, "hostLoader has " + n)
                    c.declaredMethods.firstOrNull { it.name == "onCreate" && it.parameterCount == 1 }?.hookAfter {
                        runCatching {
                            if (ctorArgPluginLoader != null) return@hookAfter
                            thisObject?.javaClass?.declaredFields?.forEach { fd ->
                                runCatching {
                                    fd.isAccessible = true
                                    val v = fd.get(thisObject)
                                    if (v != null) {
                                        val vl = v.javaClass.classLoader
                                        if (vl != null && vl !== mmLoader && vl !== ClassLoaders.BOOT) {
                                            ctorArgPluginLoader = vl
                                            WeLogger.i(TAG, "plugin loader from " + thisObject?.javaClass?.simpleName + "." + fd.name + " -> " + vl)
                                            hookOpenChainNow()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Once the plugin loader is known, hook every ok0$l1 method to capture the real open-chain
    // invocation prototype (which method takes the session talker, instance vs static, params).
    @Volatile
    private var openChainFieldsDumped = false
    private fun hookOpenChainNow() {
        val hostLoader = Class.forName("com.tencent.mm.ui.LauncherUI").classLoader
        val loader = ctorArgPluginLoader ?: hostLoader
        runCatching {
            val clz = try {
                loader.loadClass("ok0.l1")
            } catch (e: Throwable) {
                if (loader !== hostLoader) hostLoader.loadClass("ok0.l1") else throw e
            }
            clz.declaredMethods.forEach { m ->
                m.hookBefore {
                    runCatching {
                        val a = args?.map {
                            it?.javaClass?.simpleName + ":" + runCatching { it?.toString()?.take(50) }.getOrNull()
                        }?.joinToString(",")
                        WeLogger.i(TAG, "ok0l1." + m.name + " this=" + thisObject?.javaClass?.simpleName +
                            " static=" + java.lang.reflect.Modifier.isStatic(m.modifiers) + " args=" + a)
                        val inst = thisObject
                        if (inst != null) {
                            cachedOpenChainInstance = inst
                            if (!openChainFieldsDumped) {
                                openChainFieldsDumped = true
                                dumpOpenChainInstance(inst)
                            }
                        }
                    }
                }
            }
            WeLogger.i(TAG, "ok0l1 hooked methods=" + clz.declaredMethods.size)
        }.onFailure { WeLogger.w(TAG, "hook ok0l1 failed", it) }
    }

    // 找微信 Flutter 引擎会话 UUID（page_info.d 的来源）：dump 打开链实例字段，关注 UUID 字符串
    // 和引擎类引用，重启后可用真实 d 自己构造 page_info，不再依赖先开首页预热。
    private fun dumpOpenChainInstance(inst: Any) {
        runCatching {
            var c: Class<*>? = inst.javaClass
            var guard = 0
            while (c != null && guard < 8) {
                guard++
                c.declaredFields.forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(inst)
                        if (v != null) {
                            val vs = v.toString().take(80)
                            WeLogger.i(TAG, "openChainInst " + c?.name + "." + f.name + "=" + v.javaClass.name + ":" + vs)
                        }
                    }
                }
                c = c.superclass
            }
        }.onFailure { WeLogger.w(TAG, "dump openChain inst failed", it) }
    }

    private fun dumpIntent(tag: String, intent: Intent) {
        runCatching {
            val sb = StringBuilder(tag + " flags=" + intent.flags + " act=" + intent.action +
                " data=" + intent.dataString + " type=" + intent.type + " id=" + intent.identifier +
                " clip=" + (intent.clipData != null) + " pkg=" + intent.`package`)
            val b = intent.extras
            if (b != null) {
                sb.append(" extras=" + b.size() + " classLoader=" + (b.classLoader?.javaClass?.name))
                b.keySet().forEach { k ->
                    val v = runCatching { b.get(k) }.getOrNull()
                    val clsName = v?.javaClass?.name ?: "null"
                    val pi = if (v is Parcelable) {
                        runCatching { intent.getParcelableExtra<Parcelable>(k) }.getOrNull()
                    } else null
                    sb.append(" | ").append(k).append("=").append(clsName)
                    if (pi != null) sb.append("[ok:").append(pi.javaClass.name).append("]")
                    else if (v is Parcelable) sb.append("[FAIL]")
                }
            } else sb.append(" extras=null")
            WeLogger.i(TAG, sb.toString())
        }.onFailure { WeLogger.w(TAG, "dumpIntent failed", it) }
    }

    private fun dumpPageInfo(tag: String, pi: Any?) {
        if (pi == null) { WeLogger.i(TAG, tag + " pi=null"); return }
        var c: Class<*>? = pi.javaClass
        while (c != null) {
            runCatching {
                c.declaredFields.forEach { f ->
                    f.isAccessible = true
                    WeLogger.i(TAG, tag + " field " + c?.name + "." + f.name + "=" + f.get(pi))
                }
            }
            c = c.superclass
        }
    }

    // Persist the homepage intents so folder taps work right after a restart.
    private fun saveCachedIntent(context: Context, cls: String, intent: Intent) {
        runCatching {
            val dir = context.getDir("wekit_cache", Context.MODE_PRIVATE)
            val p1 = Parcel.obtain()
            intent.writeToParcel(p1, 0)
            File(dir, cls.replace('.', '_') + ".intent").writeBytes(p1.marshall())
            p1.recycle()
            val extras = intent.extras
            if (extras != null) {
                val p2 = Parcel.obtain()
                extras.writeToParcel(p2, 0)
                File(dir, cls.replace('.', '_') + ".bundle").writeBytes(p2.marshall())
                p2.recycle()
            }
        }.onFailure { WeLogger.w(TAG, "save cache failed", it) }
    }

    private fun loadCachedIntents(context: Context) {
        runCatching {
            val mmLoader = Class.forName("com.tencent.mm.ui.LauncherUI").classLoader
            val dir = context.getDir("wekit_cache", Context.MODE_PRIVATE)
            val files = dir.listFiles() ?: return
            files.filter { it.name.endsWith(".intent") }.forEach { f ->
                runCatching {
                    val bytes = f.readBytes()
                    val p1 = Parcel.obtain()
                    p1.unmarshall(bytes, 0, bytes.size)
                    p1.setDataPosition(0)
                    val intent = Intent.CREATOR.createFromParcel(p1)
                    p1.recycle()
                    val cls = f.name.substringBeforeLast('.').replace('_', '.')
                    val bFile = File(dir, f.name.substringBeforeLast('.') + ".bundle")
                    if (bFile.exists()) {
                        val eb = bFile.readBytes()
                        val p2 = Parcel.obtain()
                        p2.unmarshall(eb, 0, eb.size)
                        p2.setDataPosition(0)
                        // Deserialize the extras with the WeChat loader so page_info/page_style
                        // are real objects created by the same loader WeChat itself uses.
                        val extras = p2.readBundle(mmLoader)
                        p2.recycle()
                        if (extras != null) intent.replaceExtras(extras)
                    }
                    cachedHomeIntents[cls] = intent
                    WeLogger.i(TAG, "restored cache " + cls)
                    dumpIntent("RESTORED", intent)
                    val pi = runCatching { intent.getParcelableExtra<Parcelable>("page_info") }.getOrNull()
                    WeLogger.i(TAG, "pageInfo restored ok=" + (pi != null) + " cls=" + (pi?.javaClass?.name))
                }.onFailure { }
            }
        }.onFailure { }
    }

    // 8.0.78 容器长按若改走与首页一致的 ConversationLongClickListener(不再经 MMPopupMenu.showMenu)，
    // 在此探测并在菜单构建后注入「移出/移到文件夹」项。
    private var dumpedContainerOnce = false
    private var lastLongClickDiag = 0L
    private var lastPopupDiag = 0L
    private var pendingMenuTalker: String? = null
    private var pendingMenuCtx: android.content.Context? = null
    private fun hookRowMenuInjectG() {
        val gCls = runCatching { Class.forName("eu5.s0") }.getOrNull()
        if (gCls == null) { diagFile("gm s0 missing"); return }
        val gm = gCls.declaredMethods.firstOrNull { it.name == "g" && it.parameterCount == 7 }
        if (gm == null) { diagFile("gm g missing"); return }
        gm.isAccessible = true
        de.robv.android.xposed.XposedBridge.hookMethod(gm, object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(p: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                runCatching {
                    if (activeFolderId == null) return@runCatching
                    val folderId = activeFolderId ?: return@runCatching
                    val folder = folderById(folderId) ?: return@runCatching
                    if (folder.type != FolderType.MANUAL) return@runCatching
                    val createListener = p.args.getOrNull(3) as? android.view.View.OnCreateContextMenuListener ?: return@runCatching
                    val selectCb = p.args.getOrNull(4) ?: return@runCatching
                    val position = p.args.getOrNull(1) as? Int ?: return@runCatching
                    val talker = resolveContainerRowUsername(createListener, position)
                    diagFile("gm hit talker=" + talker + " pos=" + position + " listener=" + createListener.javaClass.name)
                    if (talker == null || talker == folderId || talker !in folder.members) {
                        diagFile("gm skip talker=" + talker + " folderId=" + folderId + " members=" + folder.members.take(6).joinToString(","))
                        return@runCatching
                    }
                    val rowCtx = (p.args.getOrNull(0) as? android.view.View)?.context
                    val listener0 = createListener
                    p.args[3] = android.view.View.OnCreateContextMenuListener { menu, view, menuInfo ->
                        listener0.onCreateContextMenu(menu, view, menuInfo)
                        runCatching {
                            menu.add(0, REMOVE_FROM_FOLDER_MENU_ID, REMOVE_FROM_FOLDER_MENU_ORDER, "移出文件夹")
                            menu.add(0, MOVE_TO_FOLDER_MENU_ID, MOVE_TO_FOLDER_MENU_ORDER, "移到文件夹")
                        }
                    }
                    p.args[4] = java.lang.reflect.Proxy.newProxyInstance(
                        gm.parameterTypes[4].classLoader,
                        arrayOf(gm.parameterTypes[4])
                    ) { _, method, methodArgs ->
                        val params = methodArgs ?: emptyArray()
                        if (method.name == "onMMMenuItemSelected") {
                            val menuItem = params.getOrNull(0) as? android.view.MenuItem
                            if (menuItem?.itemId == REMOVE_FROM_FOLDER_MENU_ID) {
                                diagFile("gm click remove talker=" + talker)
                                removeMemberFromFolder(folderId, talker)
                                return@newProxyInstance null
                            }
                            if (menuItem?.itemId == MOVE_TO_FOLDER_MENU_ID) {
                                diagFile("gm click move talker=" + talker)
                                if (rowCtx != null) showMoveToFolderDialog(rowCtx, talker)
                                return@newProxyInstance null
                            }
                        }
                        method.invoke(selectCb, *params)
                    }
                    diagFile("gm inject ok talker=" + talker)
                }.onFailure { diagFile("gm err: " + it) }
            }
        })
        diagFile("gm s0.g inject armed")
    }

    private fun resolveContainerRowUsername(listener: Any, pos: Int): String? {
        runCatching {
            var owner: Any? = listener
            var c: Class<*>? = listener.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) runCatching {
                    if (f.type.name.endsWith(".r0")) { f.isAccessible = true; owner = f.get(listener) }
                }
                c = c.superclass
            }
            var frag: Any? = null
            if (owner != null && owner !== listener) {
                c = owner.javaClass
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) runCatching {
                        if (f.type.name.contains("FmUI")) { f.isAccessible = true; frag = f.get(owner) }
                    }
                    c = c.superclass
                }
            }
            val roots = mutableListOf<Any>()
            if (frag != null) roots.add(frag)
            if (owner != null) roots.add(owner)
            roots.add(listener)
            for (root in roots) {
                val item = runCatching { listItemAt(root, pos) }.getOrNull()
                if (item == null) continue
                readRowUsername(item)?.let { if (it.isNotEmpty()) return it }
                strictItemUsername(item)?.let { if (it.isNotEmpty()) return it }
            }
        }
        return null
    }

    private fun listItemAt(owner: Any, pos: Int): Any? {
        var c: Class<*>? = owner.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val v = runCatching { f.isAccessible = true; f.get(owner) }.getOrNull() ?: continue
                if (v is android.widget.AdapterView<*>) {
                    val it = runCatching { (v as android.widget.AbsListView).getItemAtPosition(pos) }.getOrNull()
                    if (it != null) return it
                } else if (v is android.widget.ListAdapter) {
                    if (pos in 0 until v.count) {
                        val it = runCatching { v.getItem(pos) }.getOrNull()
                        if (it != null) return it
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun strictItemUsername(item: Any): String? {
        runCatching {
            for (m in item.javaClass.methods) {
                if (m.parameterCount == 0 && m.name in setOf("getUsername", "getUserName", "getTalker", "getWxId", "getWxid")) {
                    val v = m.invoke(item)?.toString()
                    if (!v.isNullOrEmpty() && isPlainUsername(v)) return v
                }
            }
            var c: Class<*>? = item.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.type == String::class.java) {
                        runCatching { f.isAccessible = true
                            val v = f.get(item) as? String
                            if (v != null && isPlainUsername(v)) return v
                        }
                    }
                }
                c = c.superclass
            }
        }
        return null
    }

    private fun isPlainUsername(v: String): Boolean {
        if (v.isEmpty() || v.length > 80) return false
        if (v == WeChatFolderPlaceholder.CONVERSATION_BOX || v == WeChatFolderPlaceholder.MESSAGE_FOLD) return false
        if (v.startsWith(FOLDER_PREFIX)) return false
        if (v.any { it.code > 127 }) return false
        return true
    }


    private fun hookRowMenuHost() {
        // r0 = 8.0.78 会话列表(首页/文件夹容器共用)行点击监听器。长按弹菜单主入口=r0.onItemLongClick。
        val r0Cls = runCatching { Class.forName("com.tencent.mm.ui.conversation.r0") }.getOrNull()
        if (r0Cls == null) { diagFile("rowmenu r0 missing"); return }
        r0Cls.declaredMethods.forEach { m ->
            when {
                m.name == "onItemLongClick" && m.parameterCount == 4 -> {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(p: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                            runCatching {
                                val parent = p.args.getOrNull(0) as? android.widget.AdapterView<*>
                                val pos = p.args.getOrNull(2) as? Int
                                if (activeFolderId == null || parent == null || pos == null || pos < 0) return
                                val item = runCatching { parent.adapter.getItem(pos) }.getOrNull() ?: return
                                val talker = itemToUsername(item)
                                pendingMenuTalker = talker
                                pendingMenuCtx = parent.context
                                diagFile("rowmenu longClick talker=" + talker + " pos=" + pos)
                            }
                        }
                    })
                    diagFile("rowmenu r0.onItemLongClick armed")
                }
                m.name == "onCreateContextMenu" && m.parameterCount == 3 -> {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                        override fun afterHookedMethod(p: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                            runCatching {
                                if (activeFolderId == null) return
                                val folderId = activeFolderId ?: return
                                val folder = folderById(folderId) ?: return
                                if (folder.type != FolderType.MANUAL) return
                                val talker = pendingMenuTalker ?: return
                                if (talker !in folder.members) return
                                val menu = p.args.getOrNull(0) as? android.view.Menu ?: return
                                diagFile("rowmenu createCtx talker=" + talker + " menu=" + menu.javaClass.name + " size=" + menu.size())
                                val rem = menu.add(0, REMOVE_FROM_FOLDER_MENU_ID, REMOVE_FROM_FOLDER_MENU_ORDER, "移出文件夹")
                                rem.setOnMenuItemClickListener { removeMemberFromFolder(folderId, talker); true }
                                val mv = menu.add(0, MOVE_TO_FOLDER_MENU_ID, MOVE_TO_FOLDER_MENU_ORDER, "移到文件夹")
                                mv.setOnMenuItemClickListener {
                                    val ac = pendingMenuCtx
                                    if (ac != null) showMoveToFolderDialog(ac, talker) else { showToast("移出/移动需在文件夹内使用") }
                                    true
                                }
                            }
                        }
                    })
                    diagFile("rowmenu r0.onCreateContextMenu armed")
                }
            }
        }
    }


    private fun hookPopupHostProbe() {
        val clsNames = listOf("android.widget.PopupWindow", "android.app.Dialog")
        for (cn in clsNames) {
            runCatching {
                val cls = Class.forName(cn)
                val ms = cls.declaredMethods.filter { it.name == "showAtLocation" || it.name == "show" }
                for (m in ms) {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(p: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                            runCatching {
                                if (activeFolderId == null) return
                                val now = System.currentTimeMillis()
                                if (now - lastPopupDiag < 2000) return
                                lastPopupDiag = now
                                val self = p.thisObject?.javaClass?.name
                                val st = Thread.currentThread().stackTrace
                                diagFile("POPUPMENU self=" + self + " " + m.name + "\\n" + st.take(12).joinToString("\\n") { "   " + it.className + "." + it.methodName })
                            }
                        }
                    })
                }
            }.onFailure { }
        }
        diagFile("popup probe armed")
    }


    private fun hookViewLongClickProbe() {
        runCatching {
            val cls = android.view.View::class.java
            val targets = cls.declaredMethods.filter { it.name == "performLongClick" && it.parameterCount == 0 }
            for (m in targets) {
                de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(p: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                        runCatching {
                            if (activeFolderId == null) return
                            val now = System.currentTimeMillis()
                            if (now - lastLongClickDiag < 1500) return
                            lastLongClickDiag = now
                            val v = p.thisObject as? android.view.View
                            val top = v?.context as? android.app.Activity
                            if (top !is ConvBoxServiceConversationUI) return
                            val st = Thread.currentThread().stackTrace
                            diagFile("LONGCLICK view=" + v?.javaClass?.name + "\\n" + st.take(10).joinToString("\\n") { "   " + it.className + "." + it.methodName })
                        }
                    }
                })
            }
            diagFile("longclick probe armed")
        }.onFailure { diagFile("longclick probe err: " + it) }
    }


    private fun dumpContainerViews(activity: Activity) {
        if (dumpedContainerOnce) return
        dumpedContainerOnce = true
        val root = activity.window?.decorView ?: return
        root.postDelayed({
            runCatching {
                val seen = linkedSetOf<String>()
                val queue = java.util.ArrayDeque<android.view.View>()
                queue.add(root)
                var guard = 0
                while (queue.isNotEmpty() && guard++ < 600) {
                    val v = queue.removeFirst()
                    val n = v.javaClass.name
                    if (n.contains("Folder", true) || n.contains("Conv", true) || n.contains("Adapter", true) || n.contains("Box", true) || n.contains("ListView", true) || n.contains("Recycler", true) || n.contains("u0", true)) {
                        if (seen.size < 36) seen.add(n)
                    }
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
                }
                diagFile("container dump:\\n" + seen.joinToString("\\n"))
            }.onFailure { diagFile("container dump err: " + it) }
        }, 500)
    }


    private fun hookConversationLongMenuProbe() {
        runCatching {
            val clz = Class.forName("com.tencent.mm.ui.conversation.ConversationLongClickListener")
            val createM = clz.declaredMethods.firstOrNull { it.name == "onCreateContextMenu" && it.parameterCount == 3 }
            if (createM == null) { diagFile("menu long no create"); return@runCatching }
            de.robv.android.xposed.XposedBridge.hookMethod(createM, object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(p: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                    runCatching {
                        val info = p.args.getOrNull(2) as? android.widget.AdapterView.AdapterContextMenuInfo
                        val self = p.thisObject?.javaClass?.name
                        diagFile("menu long create folder=" + activeFolderId + " self=" + self + " pos=" + (info?.position) + "\\n" + Thread.currentThread().stackTrace.take(6).joinToString("\\n") { "   " + it.className + "." + it.methodName })
                    }
                }
            })
            diagFile("menu long probe armed on " + createM.declaringClass.name)
        }.onFailure { diagFile("menu long probe err: " + it) }
    }


    private fun hookFolderContextMenu() {
        diagFile("menu hookFolderContextMenu placeholder=" + methodShowPopupMenu.isPlaceholder + " m=" + (methodShowPopupMenu.method))
        if (methodShowPopupMenu.isPlaceholder) return


        // The 5th parameter's declared type is the obfuscated select-callback interface (db5.t4,
        // with the single method onMMMenuItemSelected). We proxy it to intercept our own item.
        val selectCallbackInterface = methodShowPopupMenu.method.parameterTypes[4]

        methodShowPopupMenu.hookBefore {
            diagFile("menu hook hit folder=" + activeFolderId + " viewCls=" + runCatching { args[0]?.javaClass?.name }.getOrNull() + " listener=" + runCatching { args[3]?.javaClass?.name }.getOrNull() + " pos=" + args[1] + "\\n" + Thread.currentThread().stackTrace.take(5).joinToString("\\n") { "   " + it.className + "." + it.methodName })
            val folderId = activeFolderId ?: return@hookBefore
            val menuContext = (args[0] as? android.view.View)?.context ?: return@hookBefore
            val folder = folderById(folderId) ?: return@hookBefore
            if (folder.type != FolderType.MANUAL) return@hookBefore

            val createListener = args[3] as? View.OnCreateContextMenuListener ?: return@hookBefore
            val originalSelect = args[4] ?: return@hookBefore
            val position = args[1] as? Int ?: return@hookBefore

            val talker = runCatching { extractFolderTalker(createListener, position) }
                .onFailure { WeLogger.w(TAG, "failed to resolve long-pressed conversation", it) }
                .getOrNull()
            diagFile("menu hook talker=" + talker + " pos=" + position + " listener=" + createListener.javaClass.name + " inMembers=" + (talker?.let { it in folder.members }))
            if (talker == null) return@hookBefore
            WeLogger.i(TAG, "longpress talker=" + talker + " pos=" + position)

            // Only offer removal on a row that is actually a member of this manual folder.
            if (talker !in folder.members) return@hookBefore

            args[3] = View.OnCreateContextMenuListener { menu, view, menuInfo ->
                createListener.onCreateContextMenu(menu, view, menuInfo)
                runCatching {
                    menu.add(0, REMOVE_FROM_FOLDER_MENU_ID, REMOVE_FROM_FOLDER_MENU_ORDER, "移出文件夹")
                    menu.add(0, MOVE_TO_FOLDER_MENU_ID, MOVE_TO_FOLDER_MENU_ORDER, "移到文件夹")
                }.onFailure { WeLogger.e(TAG, "failed to add folder menu item", it) }
            }

            args[4] = Proxy.newProxyInstance(
                selectCallbackInterface.classLoader,
                arrayOf(selectCallbackInterface)
            ) { _, method, methodArgs ->
                val params = methodArgs ?: emptyArray()
                if (method.name == "onMMMenuItemSelected") {
                    val menuItem = params.getOrNull(0) as? MenuItem
                    if (menuItem?.itemId == REMOVE_FROM_FOLDER_MENU_ID) {
                        runCatching { removeMemberFromFolder(folderId, talker) }
                            .onFailure { WeLogger.e(TAG, "failed to remove from folder", it) }
                        return@newProxyInstance null
                    }
                    if (menuItem?.itemId == MOVE_TO_FOLDER_MENU_ID) {
                        runCatching { showMoveToFolderDialog(menuContext, talker) }
                            .onFailure { WeLogger.e(TAG, "failed to move to folder", it) }
                        return@newProxyInstance null
                    }
                }
                method.invoke(originalSelect, *params)
            }
        }
    }

    // Intercepts the "share to conversation" picker (SelectConversationUI) before WeChat's share
    // machinery runs. Our folder rows appear in that list because their parentRef is '' (root-level),
    // but they have no real chat thread — forwarding to one crashes. We cancel the call, show a
    // picker scoped to that folder's members, then re-invoke doClickUser with the chosen member so
    // the original share flow proceeds normally.
    private fun itemToUsername(item: Any?): String? {
        if (item == null) return null
        if (item is String) return item
        runCatching {
            for (m in item.javaClass.methods) {
                if (m.parameterCount == 0 && m.name in setOf("getUsername", "getUserName", "getTalker", "getTalkerName", "getWxId", "getWxid")) {
                    val v = m.invoke(item)?.toString()
                    if (!v.isNullOrEmpty()) return v
                }
            }
        }
        // 部分 8.0.78 行 item（如 contact.item.c0）没有标准 getter，扫描字段找归拢 folder id
        runCatching {
            var cls: Class<*>? = item.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (f.type == String::class.java) {
                        f.isAccessible = true
                        val v = f.get(item) as? String
                        if (v != null && v.startsWith(FOLDER_PREFIX)) return v
                    }
                }
                cls = cls.superclass
            }
        }
        return null
    }

    // [TEMP-DIAG] 捕获联系人/会话/分享列表上注册的行点击监听器（定位 8.0.78 Mvvm 分享列表真实点击入口）
    private var diagClickListenerHooked = false
    private fun hookViewClickListenersDiag() {
        if (diagClickListenerHooked) return
        diagClickListenerHooked = true
        runCatching { diagFile("DIAG begin hookViewClickListenersDiag") }
        runCatching {
            diagFile("DIAG hooking setOnClickListener")
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                android.view.View::class.java, "setOnClickListener",
                android.view.View.OnClickListener::class.java,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val v = param.thisObject as? android.view.View ?: return
                            val act = v.context as? android.app.Activity ?: return
                            val cn = act.javaClass.name
                            if (cn.contains("Contact") || cn.contains("Select") || cn.contains("Transmit") || cn.contains("Mvvm")) {
                                val l = param.args[0]
                                WeLogger.i(TAG, "DIAG clickListener act=$cn view=${v.javaClass.name} listener=${l?.javaClass?.name}")
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
            WeLogger.i(TAG, "DIAG setOnClickListener hook armed")
        }.onFailure { WeLogger.w(TAG, "DIAG hook failed", it); diagFile("DIAG hook failed: $it") }
        diagFile("DIAG armed ok")
    }

    // [TEMP-DIAG2] 目标 UI onResume 后 dump 列表结构与行点击载体
    private var lastResumedDiag: String? = null
    private fun hookActivityListDumpDiag() {
        runCatching {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java, "onResume",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? android.app.Activity ?: return
                        val cn = act.javaClass.name
                        try { if (cn != lastResumedDiag) { lastResumedDiag = cn; diagFile("DIAG2 resumed: $cn") } } catch (_: Throwable) {}
                        if (!cn.contains("Contact") && !cn.contains("Select") && !cn.contains("Transmit") && !cn.contains("Mvvm")) return
                        val rvRef = java.lang.ref.WeakReference(act)
                        act.window?.decorView?.postDelayed({
                            val a = rvRef.get() ?: return@postDelayed
                            runCatching { dumpActivityLists(a, cn) }
                        }, 1200)
                    }
                }
            )
            diagFile("DIAG2 Activity.onResume dump hook armed")
        }.onFailure { diagFile("DIAG2 arm failed: $it") }
    }

    private fun dumpActivityLists(act: android.app.Activity, cn: String) {
        try {
            val roots = java.util.ArrayList<android.view.View>()
            collectAdapterViews(act.window.decorView, roots, 0, java.util.HashSet<String>())
            diagFile("DIAG2 act=$cn scan foundAdapterViews=${roots.size}")
            for (rv in roots) {
                val ad = runCatching { rv.javaClass.getMethod("getAdapter").invoke(rv) }.getOrNull()
                diagFile("DIAG2   list=${rv.javaClass.name} adapter=${ad?.javaClass?.name} childCount=${(rv as? android.view.ViewGroup)?.childCount}")
                if (ad?.javaClass?.name == "android.widget.HeaderViewListAdapter") {
                    val wrapped = runCatching { ad.javaClass.getMethod("getWrappedAdapter").invoke(ad) }.getOrNull()
                    diagFile("DIAG2     wrappedAdapter=${wrapped?.javaClass?.name}")
                }
                val oicl = runCatching { rv.javaClass.getMethod("getOnItemClickListener").invoke(rv) }.getOrNull()
                if (oicl != null) diagFile("DIAG2     OnItemClick=${oicl.javaClass.name}")
                val oilcl = runCatching { rv.javaClass.getMethod("getOnItemLongClickListener").invoke(rv) }.getOrNull()
                if (oilcl != null) diagFile("DIAG2     OnItemLongClick=${oilcl.javaClass.name}")
                if (rv.javaClass.name.contains("RecyclerView")) {
                    val vh = runCatching { rv.javaClass.getMethod("findViewHolderForAdapterPosition", Int::class.javaPrimitiveType).invoke(rv, 0) }.getOrNull()
                    val iv = if (vh != null) runCatching { vh.javaClass.getField("itemView").get(vh) as? android.view.View }.getOrNull() else null
                    if (iv != null) {
                        diagFile("DIAG2     itemView=${iv.javaClass.name} clickable=${iv.isClickable}")
                        dumpViewListener(iv, "     iv")
                        if (iv is android.view.ViewGroup) dumpChildListeners(iv, 0)
                    } else diagFile("DIAG2     holder/invisible: vh=${vh?.javaClass?.name}")
                } else if (rv is android.view.ViewGroup && rv.childCount > 0) {
                    for (ci in 0 until rv.childCount) {
                        val row = rv.getChildAt(ci)
                        dumpViewListener(row, "     row$ci")
                        if (row is android.view.ViewGroup) dumpChildListeners(row, 0)
                    }
                }
            }
        } catch (e: Throwable) { diagFile("DIAG2 dump failed: $e") }
    }

    private fun collectAdapterViews(v: android.view.View, out: java.util.ArrayList<android.view.View>, depth: Int, seen: java.util.HashSet<String>) {
        if (depth > 25 || out.size >= 6) return
        val n = v.javaClass.name
        if (v.isClickable || v.isLongClickable) dumpViewListener(v, "   click@$depth")
        if ((n.contains("RecyclerView") || n.contains("ListView") || v is android.widget.AbsListView || v is android.widget.AdapterView<*>)) {
            if (seen.add(n)) out.add(v)
            return
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) collectAdapterViews(v.getChildAt(i), out, depth + 1, seen)
        }
    }

    private fun dumpChildListeners(vg: android.view.ViewGroup, depth: Int) {
        if (depth > 2) return
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i)
            if (c.javaClass.name.contains("RecyclerView")) continue
            if (c.isClickable || c.isLongClickable) dumpViewListener(c, "   c$depth")
            if (c is android.view.ViewGroup && c.childCount > 0) dumpChildListeners(c, depth + 1)
        }
    }

    private fun dumpViewListener(v: android.view.View, tag: String) {
        try {
            val m = android.view.View::class.java.getDeclaredMethod("getListenerInfo")
            m.isAccessible = true
            val li = m.invoke(v) ?: return
            val fOnClick = li.javaClass.getDeclaredField("mOnClickListener")
            fOnClick.isAccessible = true
            val clk = fOnClick.get(li)
            val fOnTouch = li.javaClass.getDeclaredField("mOnTouchListener")
            fOnTouch.isAccessible = true
            val tch = fOnTouch.get(li)
            diagFile("DIAG2 $tag ${v.javaClass.name} clickL=${clk?.javaClass?.name} touchL=${tch?.javaClass?.name}")
        } catch (e: Throwable) { diagFile("DIAG2 listener read fail ${v.javaClass.name}: $e") }
    }

    private fun hookSelectConversationUi() {
        if (methodSelectConversationDoClickUser.isPlaceholder) return
        // [DIAG7] 抓 SelectConversationUI 正常行点击后回传调用方的 result 内容（keys+flags）
        // [DIAG9] 分享 wrapper SendAppMessageWrapperUI 的目标传递探针
        runCatching {
            val wr = Class.forName("com.tencent.mm.ui.transmit.SendAppMessageWrapperUI")
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(wr, "onCreate", android.os.Bundle::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val act = p.thisObject as? android.app.Activity ?: return@runCatching
                        val i = act.intent ?: return@runCatching
                        val ex = i.extras
                        diagFile("DIAG9 wrapper onCreate intent cmp=" + i.component + " keys=" + (ex?.keySet()?.joinToString(",") ?: "null"))
                        if (ex != null) {
                            val ks = ex.keySet().toList()
                            for (k in ks) {
                                val v = ex.get(k)
                                if (v is String && v.length < 200) diagFile("DIAG9   " + k + "=" + v)
                            }
                        }
                    }
                }
            })
            diagFile("DIAG9 armed")
        }.onFailure { diagFile("DIAG9 arm err: " + it) }
        runCatching {
            val sconD = Class.forName("com.tencent.mm.ui.transmit.SelectConversationUI")
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Activity::class.java, "setResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val act = p.thisObject as? android.app.Activity ?: return
                        if (act.javaClass.name != sconD.name) return
                        val i = p.args[1] as? android.content.Intent
                        val extra = if (i != null) i.extras?.keySet()?.joinToString(",") else "null-intent"
                        diagFile("DIAG7 setResult code=" + p.args[0] + " keys=" + extra + " flags=" + (i?.flags))
                        if (i != null) {
                            val ks = i.extras?.keySet() ?: emptySet()
                            for (k in ks) {
                                val v = i.extras?.get(k)
                                if (v is String) diagFile("DIAG7   $k=" + v)
                            }
                        }
                    } catch (e: Throwable) { diagFile("DIAG7 setResult err: " + e) }
                }
            })
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Activity::class.java, "finish", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try {
                        val act = p.thisObject as? android.app.Activity ?: return
                        if (act.javaClass.name != sconD.name) return
                        diagFile("DIAG7 finish called")
                        runCatching { val ex = act.intent?.extras; diagFile("DIAG7   intent keys=" + (ex?.keySet()?.joinToString(",") ?: "null")) ; if (ex != null) { for (k in listOf("Select_Open_Id","Select_Conv_Type","Select_Conv_NextStep","Select_App_Id")) { val v = ex.get(k); diagFile("DIAG7   " + k + "=" + v) } } }
                    } catch (e: Throwable) { }
                }
            })
            diagFile("DIAG7 armed")
        }.onFailure { diagFile("DIAG7 arm err: " + it) }
        methodSelectConversationDoClickUser.hookBefore {
            val username = args.firstOrNull() as? String ?: return@hookBefore
            if (!isFolderId(username)) return@hookBefore

            val folder = folderById(username) ?: return@hookBefore
            val context = thisObject as? Context ?: return@hookBefore
            val originalMethod = captureOriginalMethod()

            // Cancel forwarding to the folder row itself — it has no real chat thread.
            result = null

            showFolderMemberPicker(context, folder) { selectedWxId ->
                runCatching {
                    originalMethod(arrayOf(selectedWxId))
                }.onFailure {
                    WeLogger.e(TAG, "failed to forward share to member $selectedWxId", it)
                }
            }
        }

        // 8.0.78: SelectConversationUI 行点击由列表 OnItemClickListener 处理，但 8.0.78 中该
        // Activity 自身不再实现 onItemClick（在父类链上），hookAllMethods 只覆盖子类声明方法，
        // 因此沿继承链找第一个 onItemClick 实现并 hook，识别 folder 行，防止微信把假会话当真会话处理。
        runCatching {
            val sconUi = Class.forName("com.tencent.mm.ui.transmit.SelectConversationUI")
            var hooked = false
            var cls: Class<*>? = sconUi
            while (cls != null && cls != Any::class.java) {
                val m = cls.declaredMethods.firstOrNull {
                    it.name == "onItemClick" && it.parameterCount == 4
                            && it.parameterTypes[0] == android.widget.AdapterView::class.java
                            && it.parameterTypes[1] == android.view.View::class.java
                }
                if (m != null) {
                    de.robv.android.xposed.XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val parent = param.args[0] as? android.widget.AdapterView<*> ?: return
                                val pos = (param.args[2] as? Int) ?: return
                                val item = runCatching { parent.adapter?.getItem(pos) }.getOrNull()
                                val username = itemToUsername(item)
                                diagFile("DIAG3 onItemClick host=${param.thisObject?.javaClass?.name} pos=$pos itemCls=${item?.javaClass?.name} username=$username")
                                if (username != null && !isFolderId(username)) {
                                    diagFile("DIAG8 normal-row stack:\\n" + Thread.currentThread().stackTrace.take(16).joinToString("\\n") { "    " + it.className + "." + it.methodName + ":" + it.lineNumber })
                                }
                                if (username != null && isFolderId(username)) {
                                    diagFile("DIAG3 folder row tapped username=$username")
                                    val folder = folderById(username) ?: return
                                    var fld: java.lang.reflect.Field? = null
                                    if (item != null) {
                                        var c: Class<*>? = item.javaClass
                                        while (c != null && c != Any::class.java && fld == null) {
                                            for (f in c.declaredFields) {
                                                if (f.type == String::class.java) {
                                                    f.isAccessible = true
                                                    if (f.get(item) == username) { fld = f; break }
                                                }
                                            }
                                            c = c.superclass
                                        }
                                    }
                                    param.result = null // 取消微信对假会话的处理
                                    val act = param.thisObject as? android.app.Activity ?: return
                                    val orig: java.lang.reflect.Method = param.method as java.lang.reflect.Method
                                    val self: Any? = param.thisObject
                                    val argsCopy = param.args.copyOf()
                                    showFolderMemberPicker(act, folder) { selectedWxId ->
                                        diagFile("folder member chosen=" + selectedWxId + ", resuming original with all-fields rewrite")
                                        runCatching {
                                            val fldsAll = java.util.ArrayList<Pair<java.lang.reflect.Field, Any>>()
                                            if (item != null) {
                                                var c: Class<*>? = item.javaClass
                                                while (c != null && c != Any::class.java) {
                                                    for (f in c.declaredFields) {
                                                        if (f.type == String::class.java) {
                                                            f.isAccessible = true
                                                            if (f.get(item) == username) fldsAll.add(Pair(f, item))
                                                        }
                                                    }
                                                    c = c.superclass
                                                }
                                            }
                                            diagFile("folder rewrite fields=" + fldsAll.size)
                                            if (fldsAll.isEmpty()) {
                                                diagFile("folder rewrite NO FIELD")
                                            } else {
                                                for (fp in fldsAll) fp.first.set(fp.second, selectedWxId)
                                            }
                                            try { orig.invoke(self, *argsCopy) } finally { for (fp in fldsAll) fp.first.set(fp.second, username) }
                                        }.onFailure { e -> diagFile("folder resume err: " + e) }
                                    }
                                }
                            } catch (e: Throwable) { diagFile("DIAG3 oic err: $e") }
                        }
                    })
                    diagFile("DIAG3 onItemClick hooked on ${cls!!.name}")
                    hooked = true
                    break
                }
                cls = cls.superclass
            }
            if (!hooked) diagFile("DIAG3 onItemClick NOT found on class chain")
        }.onFailure { diagFile("DIAG3 arm err: $it") }
    }

    // Same folder-row problem as SelectConversationUI, but for the MVVM contact picker
    // (com.tencent.mm.ui.mvvm.MvvmContactListUI) used by in-app forwarding. Every row tap goes
    // through a list item-click listener (cj5.g2#g for the main list, cj5.e4#g for search) whose
    // 2nd arg is the tapped item model (ri5.j). A normal conversation is forwarded by dispatching
    // wi5.c0(listOf(username)); our folder rows reach that path with a non-existent username →
    // crash. We cancel the tap and re-run the ORIGINAL listener with the model's username rewritten
    // to the chosen member so WeChat's own forward flow proceeds.
    private fun hookMvvmContactListItemClick() {
        // [DIAG-SEND] 侦察发送按钮：MvvmContactListUI 页面所有非行监听器(发送/确认按钮)的 onClick。
        runCatching {
            fun ctxAct(c: android.content.Context?): android.app.Activity? {
                var cur: android.content.Context? = c
                while (cur is android.content.ContextWrapper) {
                    if (cur is android.app.Activity) return cur
                    cur = cur.baseContext
                }
                return null
            }
            val known = setOf("vv5.f1", "vv5.k0")
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.view.View::class.java, "setOnClickListener", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val v = p.thisObject as? android.view.View ?: return@runCatching
                        val act = ctxAct(v.context) ?: return@runCatching
                        if (act.javaClass.name != "com.tencent.mm.ui.mvvm.MvvmContactListUI") return@runCatching
                        val l = p.args[0] ?: return@runCatching
                        val ln = l.javaClass.name
                        if (known.any { ln == it || ln.startsWith(it) }) return@runCatching
                        diagFile("mvvm sendbtn listener=" + ln + " view=" + v.javaClass.name)
                        runCatching {
                            for (m in l.javaClass.methods.filter { it.name == "onClick" }) {
                                de.robv.android.xposed.XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        runCatching {
                                            val btnObj = p.thisObject
                                            val selM = mvvmPickedMember ?: mvvmSelectedWxid
                                            var hostAct: android.app.Activity? = null
                                            var ctxIt: android.content.Context? = (btnObj as? android.view.View)?.context
                                            var ccw = ctxIt
                                            while (ccw is android.content.ContextWrapper) {
                                                if (ccw is android.app.Activity) { hostAct = ccw; break }
                                                ccw = ccw.baseContext
                                            }
                                            if (hostAct == null) {
                                                runCatching {
                                                    val at = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
                                                    val fAms = at.javaClass.getDeclaredField("mActivities"); fAms.isAccessible = true
                                                    val ams = fAms.get(at) as? java.util.Map<*, *>
                                                    val top = ams?.entrySet()?.mapNotNull { en -> val ev = en.value; runCatching { ev.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(ev) as? android.app.Activity }.getOrNull() }?.firstOrNull()
                                                    hostAct = top
                                                }
                                            }
                                            diagFile("mvvm sendbtn click host=" + hostAct?.javaClass?.name + " btn=" + btnObj?.javaClass?.name + " sel=" + selM + "\\n" + Thread.currentThread().stackTrace.take(8).joinToString("\\n") { "   " + it.className + "." + it.methodName })
                                            val selV = selM
                                            val rootA = hostAct
                                            if (selV != null && rootA != null) {
                                                runCatching {
                                                    var repAll = 0
                                                    val queue3 = java.util.ArrayDeque<Pair<Any, Int>>()
                                                    val seen3 = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                                                    queue3.add(Pair(rootA, 0))
                                                    var visited3 = 0
                                                    while (queue3.isNotEmpty() && visited3 < 60000) {
                                                        val (cur, dep) = queue3.poll()
                                                        if (cur == null || !seen3.add(cur)) continue
                                                        visited3++
                                                        if (dep > 8) continue
                                                        var cc3: Class<*>? = cur.javaClass
                                                        while (cc3 != null && cc3 != Any::class.java) {
                                                            for (f in cc3.declaredFields) {
                                                                runCatching {
                                                                    f.isAccessible = true
                                                                    val v = f.get(cur) ?: return@runCatching
                                                                    if (v is String) {
                                                                        if (isFolderId(v)) { f.set(cur, selV); repAll++ }
                                                                    } else if (v is java.util.List<*>) {
                                                                        for (i in 0 until v.size) {
                                                                            val e = v[i]
                                                                            if (e is String && isFolderId(e)) { runCatching { (v as java.util.List<Any>).set(i, selV); repAll++ } }
                                                                        }
                                                                        if (v.size < 120) for (e in v) if (e != null && e !== cur) queue3.add(Pair(e, dep + 1))
                                                                    } else if (v is java.util.Set<*>) {
                                                                        val fv = v.firstOrNull { it is String && isFolderId(it) }
                                                                        if (fv != null) { runCatching { v.remove(fv); (v as java.util.Set<Any>).add(selV) }; repAll++ }
                                                                    } else if (v is java.util.Map<*, *>) {
                                                                        val jm = v as java.util.Map<Any?, Any?>
                                                                        for (en in jm.entrySet()) {
                                                                            val vv = en.value
                                                                            if (vv is String && isFolderId(vv)) { runCatching { jm.put(en.key, selV) }; repAll++ }
                                                                        }
                                                                    } else if (v is Array<*>) {
                                                                        for (i in v.indices) {
                                                                            val e = v[i]
                                                                            if (e is String && isFolderId(e)) { runCatching { (v as Array<Any?>)[i] = selV; repAll++ } }
                                                                        }
                                                                    } else if (v.javaClass.name.startsWith("[")) {
                                                                    } else if (!(v is Number || v is Boolean || v is CharSequence || v is Class<*> || v.javaClass.name.startsWith("java.") || v.javaClass.name.startsWith("kotlin.") || v.javaClass.name.startsWith("android."))) {
                                                                        if (dep < 7) queue3.add(Pair(v, dep + 1))
                                                                    }
                                                                }
                                                            }
                                                            cc3 = cc3.superclass
                                                        }
                                                    }
                                                    diagFile("mvvm sendbtn replace visited=" + visited3 + " replaced=" + repAll)
                                                }.onFailure { diagFile("mvvm sendbtn replace err: " + it) }
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            })
            diagFile("mvvm sendbtn hook armed")
        }.onFailure { diagFile("mvvm sendbtn err: " + it) }

        // [PERFCLK] 抓发送按钮点击调用栈(定提交入口)
        runCatching {
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.view.View::class.java, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val v = p.thisObject as? android.view.View ?: return@runCatching
                        var c2: android.content.Context? = v.context
                        var isM = false
                        while (c2 is android.content.ContextWrapper) {
                            if (c2 is android.app.Activity && c2.javaClass.name == "com.tencent.mm.ui.mvvm.MvvmContactListUI") { isM = true; break }
                            c2 = c2.baseContext
                        }
                        if (!isM) return@runCatching
                        val txt = if (v is android.widget.TextView) v.text?.toString().orEmpty() else ""
                        if (txt.contains("发送") || txt.contains("确定") || txt.contains("完成") || txt.isBlank()) {
                            diagFile("mvvm performClick txt=" + txt.take(12) + " view=" + v.javaClass.name + "\\n" + Thread.currentThread().stackTrace.take(22).joinToString("\\n") { "   " + it.className + "." + it.methodName + ":" + it.lineNumber })
                        }
                    }
                }
            })
            diagFile("mvvm performClick hook armed")
        }.onFailure { diagFile("mvvm performClick err: " + it) }

        // 打开聊天页前改写：发送流程把目标 folder 作为 Chat_User 启动 ChattingUI(folder)。
        // 在 startActivity 处若 mvvmSelectedWxid 有效(刚经 folder 成员选择器选定)则改写为成员，
        // 微信随后在成员聊天页自动发出转发内容，不再闪 folder 假页/二次弹窗。
        runCatching {
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Activity::class.java, "startActivity", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val intent = p.args.getOrNull(0) as? android.content.Intent ?: return@runCatching
                        val u = intent.getStringExtra("Chat_User")
                        val cmp = intent.component?.className ?: ""
                        val actN = p.thisObject?.javaClass?.name ?: ""
                        if (u == null && !cmp.contains("Chat") && !cmp.contains("chat") && !intent.hasExtra("SendMsgUsernames")) return@runCatching
                        if (u == null && !actN.contains("Chatting") && !actN.contains("Launcher") && !actN.contains("Forward") && !actN.contains("mvvm") && !actN.contains("Mvvm") && !actN.contains("ContactSelect")) return@runCatching
                        if (u != null && isFolderId(u)) {
                            val m = mvvmPickedMember ?: mvvmSelectedWxid
                            if (m != null) {
                                intent.putExtra("Chat_User", m)
                                diagFile("mvvm startActivity patch Chat_User folder->$m to=" + cmp)
                                intent.extras?.let { ex ->
                                    for (k in ex.keySet().toList()) {
                                        val v = ex.get(k)
                                        if (v is String && isFolderId(v)) { ex.putString(k, m); diagFile("mvvm startActivity extra patch $k folder->$m") }
                                        else if (v is java.util.List<*>) {
                                            for (i in 0 until v.size) {
                                                val e = v[i]
                                                if (e is String && isFolderId(e)) {
                                                    try { (v as java.util.List<Any>).set(i, m); diagFile("mvvm startActivity list patch $k i=$i folder->$m") } catch (t: Throwable) { }
                                                }
                                            }
                                        }
                                    }
                                }
                                mvvmPickedMember = null
                                mvvmSelectedWxid = null
                            } else {
                                diagFile("mvvm startActivity folder-no-sel to=" + cmp + " u=" + u)
                            }
                        } else {
                            diagFile("mvvm startActivity obs act=" + p.thisObject?.javaClass?.name + " to=" + cmp + " u=" + u + " hasSend=" + intent.hasExtra("SendMsgUsernames") + "\\n" + Thread.currentThread().stackTrace.take(14).joinToString("\\n") { "   " + it.className + "." + it.methodName + ":" + it.lineNumber })
                        }
                    }
                }
            })
            diagFile("mvvm startActivity hook armed")
        }.onFailure { diagFile("mvvm startActivity hook err: " + it) }

        // 发送点改写：微信把消息发给某会话最终构造 NetSceneSendMsg(toUser,...) 入队。
        // 观察并在构造层把 folder 目标就地替换为所选成员。
        runCatching {
            val nmCls = com.Johnny.wcx.features.api.core.WeMessageApi.classNetSceneSendMsg.clazz
            for (ct in nmCls.constructors.filter { it.parameterTypes.any { p -> p == String::class.java } }) {
                de.robv.android.xposed.XposedBridge.hookMethod(ct, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        runCatching {
                            val t = p.args.firstOrNull { it is String } as? String ?: return@runCatching
                            val sel = mvvmPickedMember ?: mvvmSelectedWxid
                            if (t.isNotBlank() && t.length <= 80) {
                                if (isFolderId(t)) {
                                    if (sel != null) {
                                        if (System.currentTimeMillis() - mvvmSelectedTs > 8000) { mvvmSelectedWxid = null }
                                        p.args[p.args.indexOfFirst { it is String }] = sel
                                        mvvmSelectedWxid = null
                                        diagFile("mvvm sendmsg patch folder->$sel")
                                    } else {
                                        diagFile("mvvm sendmsg folder-no-sel: " + t)
                                    }
                                } else {
                                    diagFile("mvvm sendmsg target=" + t.take(30))
                                }
                            }
                        }
                    }
                })
            }
            diagFile("mvvm sendmsg ctor hook armed " + nmCls.name)
        }.onFailure { diagFile("mvvm sendmsg ctor err: " + it) }

        // 出口改写：转发选择页(MvvmContactListUI Activity)选中行后会 setResult(folder) 并 finish，
        // 宿主读取该 result 才真正发送。在出口把 folder 改成最近一次 folder picker 选定的成员(持久)。
        runCatching {
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Activity::class.java, "setResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val act = p.thisObject as? android.app.Activity ?: return@runCatching
                        val isMvvm = act.javaClass.name == "com.tencent.mm.ui.mvvm.MvvmContactListUI"
                        if (!isMvvm) return@runCatching
                        val code = p.args.getOrNull(0)
                        val sel = mvvmPickedMember ?: mvvmSelectedWxid
                        val intent = p.args.getOrNull(1) as? android.content.Intent
                        if (intent == null) {
                            diagFile("mvvm setResult(int) code=" + code + " sel=" + sel + "\\n" + Thread.currentThread().stackTrace.take(8).joinToString("\\n") { "   " + it.className + "." + it.methodName + ":" + it.lineNumber })
                        } else {
                            var patched = false
                            if (sel != null) {
                                intent.extras?.let { ex ->
                                    for (k in ex.keySet().toList()) {
                                        val v = ex.get(k)
                                        if (v is String) {
                                            if (isFolderId(v)) { ex.putString(k, sel); patched = true; diagFile("mvvm setResult patch $k folder->$sel") }
                                        } else if (v is java.util.List<*>) {
                                            for (i in 0 until v.size) {
                                                val e = v[i]
                                                if (e is String && isFolderId(e)) {
                                                    try { (v as java.util.List<Any>).set(i, sel); patched = true; diagFile("mvvm setResult list patch $k i=$i folder->$sel") } catch (t: Throwable) { diagFile("mvvm setResult list err: " + t) }
                                                }
                                            }
                                        } else if (v is java.util.Set<*>) {
                                            val fv = v.firstOrNull { it is String && isFolderId(it) }
                                            if (fv != null) {
                                                runCatching { v.remove(fv); (v as java.util.Set<Any>).add(sel) }
                                                patched = true
                                                diagFile("mvvm setResult set patch $k folder->$sel")
                                            }
                                        } else if (v is Array<*>) {
                                            for (i in v.indices) {
                                                val e = v[i]
                                                if (e is String && isFolderId(e)) {
                                                    try { (v as Array<Any?>)[i] = sel; patched = true; diagFile("mvvm setResult array patch $k i=$i folder->$sel") } catch (t: Throwable) { }
                                                }
                                            }
                                        }
                                    }
                                }
                                intent.data?.toString()?.let { d -> if (isFolderId(d)) diagFile("mvvm setResult data-uri folder: " + d.take(80)) }
                            }
                            if (!patched) diagFile("mvvm setResult(int,Intent) sel=" + sel + " keys=" + (intent.extras?.keySet()?.toString().orEmpty().take(160)))

                            // 深度内存替换：宿主可能从 Mvvm UI 内部状态(非 result)直接读转发目标
                            if (sel != null) {
                                runCatching {
                                    var repAll = 0
                                    val queue2 = java.util.ArrayDeque<Pair<Any, Int>>()
                                    val seen2 = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                                    queue2.add(Pair(act, 0))
                                    var visited2 = 0
                                    while (queue2.isNotEmpty() && visited2 < 30000) {
                                        val (cur, dep) = queue2.poll()
                                        if (cur == null || !seen2.add(cur)) continue
                                        visited2++
                                        if (dep > 7) continue
                                        var cc2: Class<*>? = cur.javaClass
                                        while (cc2 != null && cc2 != Any::class.java) {
                                            for (f in cc2.declaredFields) {
                                                runCatching {
                                                    f.isAccessible = true
                                                    val v = f.get(cur) ?: return@runCatching
                                                    if (v is String) {
                                                        if (isFolderId(v)) { f.set(cur, sel); repAll++; diagFile("mvvm deep field patch " + f.name + "@" + cc2!!.name) }
                                                    } else if (v is java.util.List<*>) {
                                                        for (i in 0 until v.size) {
                                                            val e = v[i]
                                                            if (e is String && isFolderId(e)) { runCatching { (v as java.util.List<Any>).set(i, sel); repAll++; diagFile("mvvm deep list patch " + f.name + " i=$i") } }
                                                        }
                                                        if (v.size < 100) for (e in v) if (e != null && e !== cur) queue2.add(Pair(e, dep + 1))
                                                    } else if (v is java.util.Set<*>) {
                                                        val fv = v.firstOrNull { it is String && isFolderId(it) }
                                                        if (fv != null) { runCatching { v.remove(fv); (v as java.util.Set<Any>).add(sel) }; repAll++; diagFile("mvvm deep set patch " + f.name) }
                                                    } else if (v is java.util.Map<*, *>) {
                                                        val jm = v as java.util.Map<Any?, Any?>
                                                        for (en in jm.entrySet()) {
                                                            val kk = en.key
                                                            val vv = en.value
                                                            if (vv is String && isFolderId(vv)) { runCatching { jm.put(kk, sel) }; repAll++ }
                                                        }
                                                    } else if (v is Array<*>) {
                                                        for (i in v.indices) {
                                                            val e = v[i]
                                                            if (e is String && isFolderId(e)) { runCatching { (v as Array<Any?>)[i] = sel; repAll++ } }
                                                        }
                                                    } else if (v.javaClass.name.startsWith("[")) {
                                                    } else if (!(v is Number || v is Boolean || v is CharSequence || v is Class<*> || v.javaClass.name.startsWith("java.") || v.javaClass.name.startsWith("kotlin.") || v.javaClass.name.startsWith("android."))) {
                                                        if (dep < 6) queue2.add(Pair(v, dep + 1))
                                                    }
                                                }
                                            }
                                            cc2 = cc2.superclass
                                        }
                                    }
                                    diagFile("mvvm deep scan visited=" + visited2 + " replaced=" + repAll)
                                }.onFailure { diagFile("mvvm deep scan err: " + it) }
                            }
                        }
                    }
                }
            })
            diagFile("mvvm setResult hook armed")
        }.onFailure { diagFile("mvvm setResult hook err: " + it) }

        // Mvvm 转发选择页每次新开(Activity 创建)清掉上一次 folder picker 所选成员，防止误用于后续普通转发。
        runCatching {
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Activity::class.java, "onCreate", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val act = p.thisObject as? android.app.Activity ?: return@runCatching
                    }
                }
            })
        }.onFailure { }

        runCatching {
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Activity::class.java, "finish", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val act = p.thisObject as? android.app.Activity ?: return@runCatching
                        if (act.javaClass.name != "com.tencent.mm.ui.mvvm.MvvmContactListUI") return@runCatching
                        diagFile("mvvm ui finish\\n" + Thread.currentThread().stackTrace.take(12).joinToString("\\n") { "   " + it.className + "." + it.methodName + ":" + it.lineNumber })
                    }
                }
            })
            diagFile("mvvm ui finish hook armed")
        }.onFailure { diagFile("mvvm ui finish hook err: " + it) }

        // [HOST] 抓 Mvvm finish 后宿主 onActivityResult(真正读取转发结果的入口)
        runCatching {
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.app.Activity::class.java, "onActivityResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val act = p.thisObject as? android.app.Activity ?: return@runCatching
                        val ic = p.args.getOrNull(2) as? android.content.Intent
                        val extraTxt = runCatching {
                            ic?.extras?.let { ex -> ex.keySet().joinToString(",") { k -> k + "=" + (ex.get(k)?.toString()?.take(40)) } }.orEmpty()
                        }.getOrElse { "err" }
                        diagFile("mvvm host onAR act=" + act.javaClass.name + " req=" + p.args.getOrNull(0) + " res=" + p.args.getOrNull(1) + " extra=" + extraTxt.take(300) + "\\n" + Thread.currentThread().stackTrace.take(18).joinToString("\\n") { "   " + it.className + "." + it.methodName + ":" + it.lineNumber })
                    }
                }
            })
            diagFile("mvvm host onAR armed")
        }.onFailure { diagFile("mvvm host onAR err: " + it) }

        // [DIAG5] 8.0.78 MvvmContactListUI 实际行点击回调挖掘：从 view context 判定活跃 Activity 抓 setOnClickListener
        // [DIAG10] 验证 Mvvm 转发提交点 wi5.c0(8.0.77 推测) 在普通行点击时是否被调用
        runCatching {
            val wi5 = Class.forName("wi5")
            de.robv.android.xposed.XposedBridge.hookAllMethods(wi5, "c0", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val a0 = p.args.firstOrNull()
                        diagFile("DIAG10 wi5.c0 hit self=" + p.thisObject?.javaClass?.name + " a0=" + a0?.javaClass?.name + " v=" + (a0?.toString()?.take(120)))
                    }
                }
            })
            diagFile("DIAG10 wi5 armed")
        }.onFailure { diagFile("DIAG10 wi5 missing: " + it) }

        runCatching {
            val mvvmName5 = "com.tencent.mm.ui.mvvm.MvvmContactListUI"
            fun ctxActivity(c: android.content.Context?): android.app.Activity? {
                var cur: android.content.Context? = c
                while (cur is android.content.ContextWrapper) {
                    if (cur is android.app.Activity) return cur
                    cur = cur.baseContext
                }
                return null
            }
            de.robv.android.xposed.XposedBridge.hookAllMethods(android.view.View::class.java, "setOnClickListener", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    runCatching {
                        val v = p.thisObject as? android.view.View ?: return@runCatching
                        val act = ctxActivity(v.context) ?: return@runCatching
                        if (act.javaClass.name != mvvmName5) return@runCatching
                        val l = p.args[0] ?: return@runCatching
                        diagFile("DIAG5 setOnClick listener=" + l.javaClass.name + " onView=" + v.javaClass.name)
                    }
                }
            })
            diagFile("DIAG5 armed")
            for (cn5 in listOf("vv5.f1", "vv5.k0", "vv5", "com.tencent.mm.ui.contact.t8")) {
                runCatching {
                    val cl5 = Class.forName(cn5)
                    diagFile("DIAG5 reflect $cn5 super=" + cl5.superclass?.name)
                    cl5.declaredMethods.forEach { m5 -> diagFile("DIAG5   $cn5 m ${m5.returnType.name} ${m5.name}(${m5.parameterTypes.joinToString { it.name }})") }
                    cl5.declaredFields.forEach { f5 -> diagFile("DIAG5   $cn5 f ${f5.type.name} ${f5.name}") }
                }.onFailure { diagFile("DIAG5 reflect $cn5 err: $it") }
            }
        }.onFailure { diagFile("DIAG5 arm err: $it") }
        listOf(
            methodMvvmMainListItemClick,
            methodMvvmSearchItemClick
        ).forEach { method ->
            if (method.isPlaceholder) return@forEach
            method.hookBefore { handleMvvmFolderTap(this) }
        }
        // 8.0.78 MvvmContactListUI(内转发) 行点击真实入口 vv5.f1/vv5.k0.onClick。点击 folder 行时
        // 取消微信原处理并弹成员选择器；选定成员后把 data 中 username 改写成该成员并重跑原 onClick，
        // 让微信自己的转发流程以真实成员为目标完成。
        runCatching {
            fun normalizeFolderId(raw: String): String {
                val rest = raw.removePrefix(FOLDER_PREFIX)
                val digits = rest.takeWhile { it.isDigit() }
                return if (digits.isNotEmpty()) FOLDER_PREFIX + digits else raw
            }
            fun memberPickedRedirect(l: Any, view: android.view.View, pos: Int, rawUser: String, orig: java.lang.reflect.Method, self: Any, args: Array<Any?>, adapter: Any) {
                val folder = folderById(normalizeFolderId(rawUser)) ?: return
                val fldsAll = java.util.ArrayList<Pair<java.lang.reflect.Field, Any>>()
                fun scanFolderFields(o: Any, out: java.util.ArrayList<Pair<java.lang.reflect.Field, Any>>, depth: Int) {
                    if (depth > 3) return
                    var c: Class<*>? = o.javaClass
                    while (c != null && c != Any::class.java) {
                        for (f in c.declaredFields) {
                            runCatching {
                                f.isAccessible = true
                                val v = f.get(o)
                                if (v is String) {
                                    if (isFolderId(v)) out.add(Pair(f, o))
                                } else if (v != null && depth < 3) {
                                    val vc = v.javaClass.name
                                    val interesting = (v !is Number && v !is Boolean && v !is CharSequence && v !is java.util.Collection<*> && v !is java.util.Map<*,*> && !vc.startsWith("java.") && !vc.startsWith("android.") && !vc.startsWith("kotlin."))
                                    if (interesting) scanFolderFields(v, out, depth + 1)
                                }
                            }
                        }
                        c = c.superclass
                    }
                }
                runCatching {
                    val list2 = runCatching { adapter.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 } }
                        .getOrNull()?.invoke(adapter) as? List<*>
                    val target = list2?.getOrNull(pos)
                    if (target != null) scanFolderFields(target, fldsAll, 0)
                }
                diagFile("mvvm redirect deep fields=" + fldsAll.size + " " + fldsAll.joinToString(",") { it.first.name + "@" + it.second.javaClass.simpleName })
                showFolderMemberPicker(view.context, folder) { selectedWxId ->
                    runCatching {
                        val originals = fldsAll.map { runCatching { it.first.get(it.second) as? String }.getOrNull() }
                        for (fp in fldsAll) fp.first.set(fp.second, selectedWxId)
                        val back = runCatching { fldsAll.first().first.get(fldsAll.first().second) as? String }.getOrNull()
                        diagFile("mvvm redirect fields=" + fldsAll.size + " set=" + selectedWxId + " readback=" + back)
                        mvvmSelectedWxid = selectedWxId
                        mvvmSelectedTs = System.currentTimeMillis()
                        mvvmRedirecting = true
                        try { orig.invoke(self, *args) } catch (t: Throwable) { diagFile("mvvm invoke err: " + t) } finally {
                            mvvmRedirecting = false
                            for (i in fldsAll.indices) { runCatching { fldsAll[i].first.set(fldsAll[i].second, originals[i]) } }
                        }
                        // 替换转发页「已选目标集合」里的 folder 元素为所选成员(点发送时微信读的就是这份)
                        var rep = 0
                        try {
                            val folderNorm = normalizeFolderId(rawUser)
                            var actv: android.app.Activity? = null
                            var c2: android.content.Context? = view.context
                            while (c2 is android.content.ContextWrapper) {
                                if (c2 is android.app.Activity) { actv = c2; break }
                                c2 = c2.baseContext
                            }
                            val root = actv ?: return@runCatching
                            val queue = java.util.ArrayDeque<Pair<Any, Int>>()
                            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                            queue.add(Pair(root, 0))
                            var visited = 0
                            while (queue.isNotEmpty() && visited < 5000) {
                                val (cur, dep) = queue.poll()
                                if (cur == null || !seen.add(cur)) continue
                                visited++
                                if (dep > 5) continue
                                var cc: Class<*>? = cur.javaClass
                                while (cc != null && cc != Any::class.java) {
                                    for (f in cc.declaredFields) {
                                        runCatching {
                                            f.isAccessible = true
                                            val v = f.get(cur) ?: return@runCatching
                                            if (v is java.util.List<*>) {
                                                for (i in 0 until v.size) {
                                                    val e = v[i]
                                                    if (e is String && isFolderId(e) && normalizeFolderId(e) == folderNorm) {
                                                        runCatching { (v as java.util.List<Any>).set(i, selectedWxId); rep++; diagFile("mvvm selcoll list patch " + f.name + "@" + cc!!.name + " i=" + i) }
                                                    }
                                                }
                                                if (v.size < 30) for (e in v) if (e != null && e !== cur) queue.add(Pair(e, dep + 1))
                                            } else if (v is java.util.Set<*>) {
                                                val fv = v.firstOrNull { it is String && isFolderId(it) && normalizeFolderId(it) == folderNorm }
                                                if (fv != null) { runCatching { v.remove(fv); rep++; diagFile("mvvm selcoll set patch " + f.name + "@" + cc!!.name) } }
                                            } else if (v is String && isFolderId(v) && normalizeFolderId(v) == folderNorm) {
                                                f.set(cur, selectedWxId); rep++; diagFile("mvvm selcoll field patch " + f.name + "@" + cc!!.name)
                                            } else if (dep < 5 && !(v is Number || v is Boolean || v is CharSequence || v is Class<*> || v is java.util.Collection<*> || v is java.util.Map<*,*>) && (v.javaClass.name.startsWith("com.tencent.mm.") || v.javaClass.name.startsWith("wekit_") || v.javaClass.name.contains("uic") || v.javaClass.name.contains("mvvm") || v.javaClass.name.contains("ui."))) {
                                                queue.add(Pair(v, dep + 1))
                                            }
                                        }
                                    }
                                    cc = cc.superclass
                                }
                            }
                            diagFile("mvvm selcoll visited=" + visited + " replaced=" + rep)
                        } catch (t: Throwable) { diagFile("mvvm selcoll err: " + t) }
                    }.onFailure { e -> WeLogger.e(TAG, "mvvm folder redirect to member failed", e) }
                }
            }
            for (cn6 in listOf("vv5.f1", "vv5.k0")) {
                runCatching {
                    val cl6 = Class.forName(cn6)
                    de.robv.android.xposed.XposedBridge.hookAllMethods(cl6, "onClick", object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            try {
                                val l = p.thisObject ?: return
                                val view = p.args[0] as? android.view.View ?: return
                                val adapter = runCatching {
                                    var c: Class<*>? = l.javaClass
                                    var found: Any? = null
                                    while (c != null && c != Any::class.java && found == null) {
                                        for (f in c.declaredFields) {
                                            if (f.type.name.contains("Adapter")) { f.isAccessible = true; found = f.get(l); break }
                                        }
                                        c = c.superclass
                                    }
                                    found
                                }.getOrNull() ?: return
                                var pos: Int? = null
                                var c0: Class<*>? = l.javaClass
                                while (c0 != null && c0 != Any::class.java && pos == null) {
                                    for (f in c0.declaredFields) {
                                        if (f.type == Int::class.java || f.type == Integer::class.java) { f.isAccessible = true; val v = f.get(l) as? Int; if (v != null && v >= 0) pos = v }
                                    }
                                    c0 = c0.superclass
                                }
                                val posI = pos ?: return
                                val data = runCatching {
                                    val getData = adapter.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 }
                                    if (getData != null) { getData.isAccessible = true; (getData.invoke(adapter) as? List<*>)?.getOrNull(posI) } else null
                                }.getOrNull()
                                val username = itemToUsername(data)
                                if (data != null) {
                                    var cD: Class<*>? = data.javaClass
                                    val sbD = java.lang.StringBuilder("mvvm row " + data.javaClass.name + " u=" + username)
                                    while (cD != null && cD != Any::class.java) {
                                        for (fd in cD.declaredFields) {
                                            runCatching {
                                                fd.isAccessible = true
                                                val fv = fd.get(data)
                                                val fvs = when (fv) {
                                                    is String -> fv.take(40)
                                                    is Int, is Long, is Boolean -> fv.toString()
                                                    null -> "null"
                                                    else -> "<" + fv.javaClass.name + ">"
                                                }
                                                sbD.append(" | ").append(fd.name).append("=").append(fvs)
                                            }
                                        }
                                        cD = cD.superclass
                                    }
                                    diagFile(sbD.toString().take(1500))
                                }
                                if (username == null || !isFolderId(username)) {
                                    diagFile("mvvm normrow tap u=" + username + "\\n" + Thread.currentThread().stackTrace.take(30).joinToString("\\n") { "   " + it.className + "." + it.methodName + ":" + it.lineNumber })
                                    return
                                }
                                diagFile("mvvm tap folder row pos=$posI username=$username adapter=" + adapter.javaClass.name)
                                runCatching {
                                    val hj = Class.forName("hr5\$j")
                                    for (hm in hj.methods.filter { it.parameterCount == 0 }) {
                                        de.robv.android.xposed.XposedBridge.hookMethod(hm, object : XC_MethodHook() {
                                            override fun afterHookedMethod(p: MethodHookParam) {
                                                runCatching {
                                                    val r = p.result
                                                    if (r is String) diagFile("mvvm g " + hm.name + " -> " + r.take(50))
                                                }
                                            }
                                        })
                                    }
                                }
                                p.result = null
                                memberPickedRedirect(l, view, posI, username, p.method as java.lang.reflect.Method, p.thisObject, p.args.copyOf(), adapter)
                            } catch (e: Throwable) { diagFile("mvvm folder tap err: " + e) }
                        }
                    })
                    WeLogger.i(TAG, "MvvmContactListUI folder-row tap hooked ($cn6)")
                }.onFailure { WeLogger.w(TAG, "hook $cn6 onClick failed", it) }
            }
        }.onFailure { WeLogger.e(TAG, "Mvvm folder tap hook failed", it) }
    }

    private fun handleMvvmFolderTap(param: XC_MethodHook.MethodHookParam) {
        val itemView = param.args[0] as View
        val data = param.args[1]!!

        val folderField = data.reflekt().fields {
            type = BString
            modifiers(Modifiers.FINAL)
        }[1]
        val folderId = folderField.get()!! as String

        val folder = folderById(folderId) ?: return
        val originalMethod = param.captureOriginalMethod()

        // Cancel the tap on the folder row itself — it has no real chat thread.
        param.result = null

        showFolderMemberPicker(itemView.context, folder) { selectedWxId ->
            runCatching {
                folderField.set(selectedWxId)
                try {
                    // Re-run the ORIGINAL listener (bypasses this hook → no recursion) so WeChat
                    // forwards to the real member exactly as if that row had been tapped.
                    originalMethod()
                } finally {
                    folderField.set(folderId)
                }
            }.onFailure {
                WeLogger.e(TAG, "failed to forward folder tap to member $selectedWxId", it)
            }
        }
    }

    // Shows a picker scoped to a folder's members and invokes onMemberSelected with the chosen
    // member's wxid. Shared by both the SelectConversationUI and MvvmContactListUI interceptions.
    private fun showFolderMemberPicker(
        context: Context,
        folder: ChatFolder,
        onMemberSelected: (String) -> Unit
    ) {
        val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
        if (members.isEmpty()) {
            showToast("文件夹中没有对话")
            return
        }

        val membersSet = members.toHashSet()
        val contacts = runCatching {
            withQueryRewriteSuppressed {
                WeDatabaseApi.getContacts().filter { it.wxId in membersSet }
            }
        }.getOrDefault(emptyList())

        showComposeDialog(context) {
            FolderShareTargetSelector(
                contacts = contacts,
                onDismiss = onDismiss,
                onSelect = { selectedWxId ->
                    onDismiss()
                    onMemberSelected(selectedWxId)
                }
            )
        }
    }

    // A member picker for the "share to conversation" folder interception. Mirrors the
    // CustomLocalFriendAvatars pattern: no confirm button, each row carries a "选择" trailing
    // button that fires the forward immediately (onItemclick does the same for convenience).
    @Composable
    private fun FolderShareTargetSelector(
        contacts: List<IWeContact>,
        onDismiss: () -> Unit,
        onSelect: (String) -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
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
            title = "选择文件夹里的转发对象",
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            filteredContacts = filteredContacts,
            confirmButtonText = "",
            confirmButtonEnabled = false,
            showConfirmButton = false,
            dismissButtonText = "取消",
            onDismiss = onDismiss,
            onConfirm = {},
            selectionKey = Unit,
            isSelected = { false },
            trailingControl = { contact ->
                TextButton(onClick = { onSelect(contact.wxId) }) { Text("选择") }
            },
            onItemClick = { contact -> onSelect(contact.wxId) }
        )
    }

    // Resolves the long-pressed conversation's username from the menu-create listener WeChat passes
    // into MMPopupMenu.showMenu. Chain: createListener -> its OnItemLongClickListener -> the
    // container fragment -> its list adapter -> adapter.getItem(position) (an rconversation row) ->
    // its field_username (kept unobfuscated by WeChat's auto-DB ORM).
    // 8.0.78: 容器长按行对象类型与字段名均有变动，逐层降级解析(多通道)以确保拿到被长按会话的 username。
    private fun extractFolderTalker(createListener: Any, position: Int): String? {
        fun anyAdapter(owner: Any): android.widget.Adapter? {
            var c: Class<*>? = owner.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    runCatching {
                        if (android.widget.Adapter::class.java.isAssignableFrom(f.type) || f.type.name.endsWith("Adapter")) {
                            f.isAccessible = true
                            (f.get(owner) as? android.widget.Adapter)?.let { return it }
                        }
                    }
                }
                c = c.superclass
            }
            return null
        }
        fun anyFragment(owner: Any): Any {
            var c: Class<*>? = owner.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(owner) ?: continue
                        val n = v.javaClass.name
                        if (n.contains("ConvBox") || n.contains("ConversationFm") || n.contains("ConversationUI")) return v
                    }
                }
                c = c.superclass
            }
            return owner
        }
        fun usernameOf(item: Any): String? {
            runCatching {
                for (m in item.javaClass.methods) {
                    if (m.parameterCount == 0 && m.name in setOf("getUsername", "getUserName", "getTalker", "getTalkerName", "getWxId", "getWxid")) {
                        val v = m.invoke(item)?.toString()
                        if (!v.isNullOrEmpty()) return v
                    }
                }
            }
            var c: Class<*>? = item.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.type == String::class.java) {
                        runCatching {
                            f.isAccessible = true
                            val v = f.get(item) as? String
                            if (v != null && v.isNotEmpty() && v != WeChatFolderPlaceholder.CONVERSATION_BOX && v != WeChatFolderPlaceholder.MESSAGE_FOLD && !v.startsWith(FOLDER_PREFIX)) return v
                        }
                    }
                }
                c = c.superclass
            }
            return null
        }
        val longClickListener = if (createListener is AdapterView.OnItemLongClickListener) createListener else {
            createListener.reflekt()
                .firstFieldOrNull { type { it isSubclassOf AdapterView.OnItemLongClickListener::class } }
                ?.get() ?: createListener
        }
        val fragment = longClickListener.reflekt()
            .firstFieldOrNull { type { it.name.endsWith("ConvBoxServiceConversationFmUI") || it.name.contains("ConvBox") } }
            ?.get() ?: anyFragment(longClickListener)
        val adapter = fragment.reflekt()
            .firstFieldOrNull { type { it isSubclassOf android.widget.Adapter::class } }
            ?.get() as? android.widget.Adapter ?: anyAdapter(fragment) ?: anyAdapter(longClickListener)
        if (adapter == null) {
            diagFile("menu extract no adapter listener=" + createListener.javaClass.name)
            return null
        }
        if (position < 0 || position >= adapter.count) {
            diagFile("menu extract pos out adapterCount=" + adapter.count + " pos=" + position)
            return null
        }
        val conversation = adapter.getItem(position) ?: return null
        usernameOf(conversation)?.let { return it }
        return conversation.reflekt()
            .firstFieldOrNull { name = "field_username"; superclass() }
            ?.get() as? String
    }

    private fun hookSqliteWrapperQuery() {
        if (methodSqliteWrapperRawQuery.isPlaceholder) return
        methodSqliteWrapperRawQuery.hookBefore {
            if (suppressQueryRewrite.get()!!) return@hookBefore
            val sql = args.firstOrNull() as? String ?: return@hookBefore
            if (sql.contains("rconversation") && (activeFolderId != null || sql.contains("update", true) || sql.contains("unread", true))) {
                WeLogger.i(TAG, "rawQuery: $sql")
                diagFile("rawQuery: $sql")
            }
            onQuery(sql)?.let { args[0] = it }
        }
    }

    private fun hookConversationStorageParentQuery() {
        if (methodConversationStorageQueryByParent.isPlaceholder) return
        methodConversationStorageQueryByParent.hookBefore {
            val folderId = activeFolderId ?: return@hookBefore
            val parentRef = args.getOrNull(2) as? String ?: return@hookBefore
            if (parentRef == WeChatFolderPlaceholder.CONVERSATION_BOX ||
                parentRef == WeChatFolderPlaceholder.MESSAGE_FOLD
            ) {
                args[2] = folderId
            }
        }
    }

    // See methodConversationStorageUpdateUnreadByTalker: cancel the "mark box read on leave" that
    // WeChat's folder container fires against our folder id, so exiting a folder without opening any
    // member never clears the aggregate row's unread badge.
    private val methodMvvmConversationAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
            }
            name = "getView"
        }
    }

    private val methodConversationWithCacheAdapterGetView by dexMethod(allowFailure = true, allowMultiple = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType("android.view.View")
        }
    }

    /** tint the injected "someone @ me" prefix blue (matches FunBox) on both conversation adapters */
    private fun hookMentionTint() {
        WeLogger.i(TAG, "tint hooks: mvvmGetView placeholder=${methodMvvmConversationAdapterGetView.isPlaceholder}, cacheGetView placeholder=${methodConversationWithCacheAdapterGetView.isPlaceholder}, textViewSetText placeholder=${methodTextViewSetText.isPlaceholder}")
        diagFile("tint hooks: mvvm=${methodMvvmConversationAdapterGetView.isPlaceholder} cache=${methodConversationWithCacheAdapterGetView.isPlaceholder} setText=${methodTextViewSetText.isPlaceholder}")
        if (!methodMvvmConversationAdapterGetView.isPlaceholder) {
            methodMvvmConversationAdapterGetView.hookAfter {
                val root = result as? ViewGroup ?: return@hookAfter
                tintMentionLabels(root, "getView")
                markVirtualRow(root)
                tintFolderTitleByText(root)
                root.post {
                    tintMentionLabels(root, "getView-post")
                    markVirtualRow(root)
                    tintFolderTitleByText(root)
                }
            }
        }
        if (!methodConversationWithCacheAdapterGetView.isPlaceholder) {
            methodConversationWithCacheAdapterGetView.hookAfter {
                val root = result as? ViewGroup ?: return@hookAfter
                tintMentionLabels(root, "getView")
                markVirtualRow(root)
                tintFolderTitleByText(root)
                root.post {
                    tintMentionLabels(root, "getView-post")
                    markVirtualRow(root)
                    tintFolderTitleByText(root)
                }
            }
        }
        hookConversationListDraw()
        // FunBox 同款：hook RecyclerView.Adapter.bindViewHolder（framework 基类方法，微信 adapter 不 override，
        // 一定触发，覆盖微信主列表与归拢内部列表的 RecyclerView 行渲染）
        runCatching {
            val holderCls = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$ViewHolder",
                false, ClassLoaders.HOST
            )
            val adapterCls = Class.forName(
                "androidx.recyclerview.widget.RecyclerView\$Adapter",
                false, ClassLoaders.HOST
            )
            adapterCls.getMethod(
                "bindViewHolder", holderCls, Int::class.javaPrimitiveType, java.util.List::class.java
            ).hookAfterDirectly { tintHolder() }
            runCatching {
                adapterCls.getMethod(
                    "bindViewHolder", holderCls, Int::class.javaPrimitiveType
                ).hookAfterDirectly { tintHolder() }
                WeLogger.i(TAG, "bindViewHolder(vh,int) androidx hook registered")
                diagFile("bindViewHolder(vh,int) androidx registered")
            }.onFailure { WeLogger.w(TAG, "hook androidx bindViewHolder(2-arg) failed", it) }
            WeLogger.i(TAG, "bindViewHolder androidx hook registered")
            diagFile("bindViewHolder androidx registered")
        }.onFailure { WeLogger.w(TAG, "hook androidx bindViewHolder failed", it); diagFile("bindViewHolder androidx FAILED: $it") }
        runCatching {
            val holderCls = Class.forName(
                "android.support.v7.widget.RecyclerView\$ViewHolder",
                false, ClassLoaders.HOST
            )
            val adapterCls = Class.forName(
                "android.support.v7.widget.RecyclerView\$Adapter",
                false, ClassLoaders.HOST
            )
            adapterCls.getMethod(
                "bindViewHolder", holderCls, Int::class.javaPrimitiveType, java.util.List::class.java
            ).hookAfterDirectly { tintHolder() }
            WeLogger.i(TAG, "bindViewHolder support hook registered")
            diagFile("bindViewHolder support registered")
        }.onFailure { WeLogger.w(TAG, "hook support bindViewHolder failed", it); diagFile("bindViewHolder support FAILED: $it") }
    }


    private val hookedTextClasses = java.util.Collections.synchronizedSet(java.util.HashSet<Class<*>>())

    /** hook 微信具体 TextView 类的 setText(CharSequence) —— 微信 digest 是 override setText 的自定义类，
     *  不走基类；绑定到 Item 行内每个 TextView 的具体类，一次注册全部生效 */
    private fun hookTextViewClass(v: TextView) {
        val cls = v.javaClass
        if (!hookedTextClasses.add(cls)) return
        runCatching {
            cls.getMethod("setText", java.lang.CharSequence::class.java).hookAfterDirectly {
                val tv = thisObject as? TextView ?: return@hookAfterDirectly
                val s = tv.text?.toString().orEmpty()
                val tinted = tintMention(s, tv.context)
                if (tinted != null) setTextSpanDirect(tv, tinted)
            }
        }.onFailure { diagFile("clsSetText FAILED ${cls.name}: $it") }
        runCatching {
            val bufType = Class.forName("android.widget.TextView\$BufferType", false, ClassLoaders.HOST)
            cls.getMethod("setText", java.lang.CharSequence::class.java, bufType).hookAfterDirectly {
                val tv = thisObject as? TextView ?: return@hookAfterDirectly
                val s = tv.text?.toString().orEmpty()
                val tinted = tintMention(s, tv.context)
                if (tinted != null) {
                    setTextSpanDirect(tv, tinted)
                }
            }
            diagFile("clsSetText(CS,BT) hooked: ${cls.name}")
        }.onFailure { diagFile("clsSetText(CS,BT) FAILED ${cls.name}: $it") }
    }

    /** 反射直写 TextView 私有 mText/mTransformedText，绕过所有 setText 重载（方案 B） */
    private fun setTextSpanDirect(tv: TextView, spannable: CharSequence) {
        runCatching {
            val f = android.widget.TextView::class.java.getDeclaredField("mText")
            f.isAccessible = true
            f.set(tv, spannable)
            val tf = android.widget.TextView::class.java.getDeclaredField("mTransformedText")
            tf.isAccessible = true
            tf.set(tv, spannable)
            tv.invalidate()
            tv.requestLayout()
            diagFile("setTextSpanDirect applied: ${tv.javaClass.name}")
        }.onFailure { diagFile("setTextSpanDirect FAILED: $it") }
    }

    /**
     * 归拢摘要彩色（FunBox 叠加模式）：hook NoMeasuredTextView.onDraw **after**，
     * 不拦截原生绘制（灰色原文照常输出），在原绘制完成后用同一 Canvas 叠加彩色标签
     * （蓝 [有人@我]/[全体]、黄 [N个聊天]），彩色文字盖在灰色文字上方。
     * 优点：不依赖 Item 根类 / adapter 渲染路径 / Tag 向上遍历，摘要文本出现即染色。
     */
    /**
     * 归拢摘要彩色（FunBox 叠加模式）：全局 hook TextView.onDraw **after**。
     * 已证实微信主列表摘要不经过 NoMeasuredTextView（其 onDraw 从不触发），改为捕获所有
     * TextView 子类绘制：文本含归拢标记（[有人@我]/[全体]/[N个聊天]）即用控件本地坐标
     * 画布叠加彩色标签（原生灰色保留）。同时全量输出绘制诊断以定位真实摘要控件类名。
     */
    private fun hookAllTextViewDraw() {
        // 归拢摘要染色（微信主列表）：摘要控件 = NoMeasuredTextView（extends X2CView，非 TextView，
        // getText() 为空）。归拢摘要经 setText(CharSequence) 注入；hookBefore 直接替换为 Spannable
        // 上色（蓝 [有人@我]/[全体]、黄 [N个聊天]），微信自绘渲染 span 颜色即上色。
        // 已实测：系统 Framework 类 hook（View.draw/onAttachedToWindow）对微信无效，不再使用。
        runCatching {
            val nmtCls = Class.forName("com.tencent.mm.ui.base.NoMeasuredTextView")
            nmtCls.declaredMethods.filter { it.name == "setText" }.forEach { m ->
                m.apply { isAccessible = true }.hookBeforeDirectly {
                    val text = args.getOrNull(0)?.toString() ?: return@hookBeforeDirectly
                    if (folderTitleNames().contains(text.trim())) {
                        // 文件夹标题染色：受「文件夹标题染色」独立开关控制（与摘要总开关互不影响）。
                        if (folderTitleEnabled) {
                        args[0] = android.text.SpannableString(text).apply {
                            setSpan(android.text.style.ForegroundColorSpan(MENTION_TITLE_BLUE), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        }
                        return@hookBeforeDirectly
                    }
                    if (isAggSummary(text)) {
                        // 总开关（摘要颜色染色）关闭时不注入彩色 span，恢复微信默认色。
                        if (WePrefs.getBoolOrFalse(ConversationAggregationColors.ENABLED_PREF_KEY)) {
                            args[0] = tintAggSummary(text, (thisObject as? View)?.context)
                        }
                    }
                    if (CHAT_COUNT_REGEX.containsMatchIn(text)) {
                        tintFolderTitle(thisObject as? View, text)
                    }
                    if (CHAT_COUNT_REGEX.containsMatchIn(text)) {
                        tintFolderTitle(thisObject as? View, text)
                    }
                }
            }
            diagFile("NMTV tint hook installed")
            WeLogger.i(TAG, "NoMeasuredTextView.setText tint hook installed")
        }.onFailure {
            diagFile("NMTV tint hook FAILED: $it")
            WeLogger.e(TAG, "NoMeasuredTextView.setText tint hook failed", it)
        }
    }

    /**
     * WX 8.0.78: WeChat renders every parent conversation (incl. our folder rows) with the native
     * "ConversationFolderItemView" fold template whose title text is a hardcoded "折叠置顶聊天".
     * For rows whose adapter item is one of our folders, restore the folder name (blue title).
     * Runs every dispatchDraw frame, but setText/setTextColor are skipped while the text is already
     * what we want, so cost stays minimal after the first pass. Also restores the original row title
     * when the recycled row is no longer bound to a folder.
     */
    private fun retitleFolderRow(list: View, row: ViewGroup) {
        runCatching {
            val pos = runCatching { list.javaClass.getMethod("getPositionForView", View::class.java).invoke(list, row) as? Int }.getOrDefault(-1)
            if (pos == null || pos < 0) return
            val item = runCatching { list.javaClass.getMethod("getItemAtPosition", Int::class.javaPrimitiveType).invoke(list, pos) }.getOrNull() ?: return
            val uname = readRowUsername(item) ?: return
            if (!isFolderId(uname)) return  // 普通会话行不处理(8.0.78 普通行标题也是 NoMeasuredTextView，避免误染)
            val title = findTitleTextView(row)
            if (title == null) {
                WeLogger.w(TAG, "retitle: no title view for " + uname)
                return
            }
            val folder = folderById(uname)
            val cur = viewText(title)
            if (folder == null) {
                if (cur.startsWith(FOLDER_PREFIX)) {
                    // Recycled row still shows an old folder name but no longer maps to a folder.
                    WeLogger.i(TAG, "retitle: stale folder title cleared")
                    setViewText(title, "")
                }
                return
            }
            // 文本修正：TextView(g_u 展开态/回收行) cur 可读时才改；NoMeasuredTextView(cur 空) 文本由微信 bind(folder 名)
            if (cur.isNotEmpty() && cur != folder.name) setViewText(title, folder.name)
            // 颜色驱动补色：微信 bind 会把颜色重置回默认——viewColor != blue 时补一次；开关关闭则保持微信默认
            val blue = adaptNight(row.context, MENTION_TITLE_BLUE)
            if (folderTitleEnabled) {
                if (viewColor(title) != blue) {
                    setViewColor(title, blue)
                    val nT = System.currentTimeMillis()
                    if (nT - (lastRetitleLog[uname] ?: 0L) > 2000) {
                        lastRetitleLog[uname] = nT
                        WeLogger.i(TAG, "retitle: " + uname + " -> " + folder.name)
                    }
                }
            }
            // —— 摘要上色：折叠模板摘要 = NoMeasuredTextView(ht5 自绘)；展开态 = TextView(dfs) ——
            // 摘要各色值/独立开关在 tintAggSummary 内按 pref 实时读取(总开关关→原样文本无色)
            val sv = findSummaryViewCompat(row, title)
            if (sv != null) {
                val digest = folderDigestCached(uname)
                // 摘要文本在微信每次 bind/重绘时会被重置为纯文本(灰色)——必须在每次 dispatchDraw 重设彩色 span
                // (幂等,内容相同无闪)；节流会留下「忽有忽无」灰色窗口。开关在 tintAggSummary 内实时读取。
                if (digest.isNotEmpty() && isAggSummary(digest)) {
                    setViewText(sv, tintAggSummary(digest, row.context))
                }
            }
        }.onFailure { WeLogger.w(TAG, "retitleFolderRow failed", it) }
    }

    /** Extract the conversation username (field_username) from an adapter item (com.tencent.mm.storage.k4). */
    private fun readRowUsername(item: Any): String? {
        var cls: Class<*>? = item.javaClass
        var guard = 0
        while (cls != null && cls != Any::class.java && guard++ < 5) {
            val f = runCatching { cls.getDeclaredField("field_username") }.getOrNull()
            if (f != null) { f.isAccessible = true; return f.get(item) as? String }
            cls = cls.superclass
        }
        return null
    }

    private fun tintFolderTitleByText(root: ViewGroup) {
        // 开关关闭时不染文件夹标题。
        if (!WePrefs.getBoolOrFalse(ConversationAggregationColors.ENABLED_PREF_KEY)) return
        runCatching {
            if (!folderTitleEnabled) {
                val orig = root.getTag(TAG_KEY_TITLE_ORIG) as? Int
                val title = root.getTag(TAG_KEY_TITLE_TV) as? TextView
                if (orig != null && title != null && title.currentTextColor != orig) title.setTextColor(orig)
                return
            }
            val queue = java.util.ArrayDeque<View>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 200) {
                val v = queue.removeFirst()
                if (v is TextView) {
                    val t = v.text?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty() && folderTitleNames().contains(t) && v.currentTextColor != adaptNight(root.context, MENTION_TITLE_BLUE)) {
                        v.setTextColor(adaptNight(root.context, MENTION_TITLE_BLUE))
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }.onFailure {
            diagFile("ConversationList dispatchDraw FAILED: $it")
            WeLogger.w(TAG, "ConversationList dispatchDraw hook fail", it)
        }
    }
    /** Hook WeChat conversation list dispatchDraw: tint folder-title rows every frame (idempotent) */
    private fun hookConversationListDraw() {
        var lastDump = 0L
        runCatching {
            val cls = Class.forName("com.tencent.mm.ui.conversation.ConversationListView")
            cls.getMethod("dispatchDraw", android.graphics.Canvas::class.java).hookAfterDirectly {
                val list = thisObject as? ViewGroup ?: return@hookAfterDirectly
                cachedConvListView = WeakReference(list)
                runCatching {
                    // Self-heal: 8.0.78 never routes the home resume through MainUI.onResume,
                    // so the folder index can stay empty after a cold start and refreshes no-op.
                    if (folderByMember.isEmpty() && WeDatabaseApi.isReady &&
                        SystemClock.uptimeMillis() - lastReconcileAttempt > 2000L
                    ) {
                        lastReconcileAttempt = SystemClock.uptimeMillis()
                        WeLogger.i(TAG, "lazy reconcile: index empty on home frame")
                        syncFoldersToDatabase()
                    }
                    for (i in 0 until list.childCount) {
                        val row = list.getChildAt(i)
                        if (row is ViewGroup) {
                            tintFolderTitleByText(row)
                            retitleFolderRow(list, row)
                        }
                    }
                }
            }
            WeLogger.i(TAG, "ConversationList dispatchDraw hooked")
        }.onFailure {
            WeLogger.w(TAG, "ConversationList dispatchDraw hook fail", it)
        }
    }
    /** 8.0.78 fold 行标题可能是 NoMeasuredTextView(X2C 自绘,非 TextView,getText 空)。
     * 候选 = 可见 且 (NoMeasured 或 含字母非摘要 TextView)；字号最大优先(NoMeasured 不可读给 0)。
     * GONE 子树直接跳过——修正此前常改到回收/GONE 区视图的问题。 */
    private fun findTitleTextView(root: ViewGroup): View? {
        var best: View? = null
        var bestSize = -1f
        val queue = java.util.ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (!v.isShown) continue
            val isNM = v.javaClass.name.contains("NoMeasured")
            val tvText = (v as? TextView)?.text?.toString().orEmpty()
            val size = if (v is TextView) v.textSize else runCatching { v.javaClass.getMethod("getTextSize").invoke(v) as? Float }.getOrNull() ?: -1f
            val ok = isNM || (!isAggSummary(tvText) && tvText.any { it.isLetter() })
            if (ok && (best == null || size > bestSize)) { best = v; bestSize = size }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
        }
        return best
    }

    private fun viewText(v: View): String = (v as? TextView)?.text?.toString().orEmpty()

    private fun viewColor(v: View): Int = (v as? TextView)?.currentTextColor
        ?: runCatching { v.javaClass.getMethod("getCurrentTextColor").invoke(v) as? Int }.getOrNull() ?: 0

    /** 8.0.78 home list is static: MStorage notify does not rebind. Drop the digest cache then
     * force the ListView adapter to rebind on the main thread so WeChat re-queries (module
     * rewrite returns the fresh digest) and re-renders folder rows. */
    /** 8.0.78 leave-folder: WeChat hides the home folder-row unread badge (TextView + red-dot
     * ImageView around the avatar lower-right) instead of clearing data. Flip visibility back
     * and refresh the number from the DB so the badge returns instantly, no full rebind. */
    private fun restoreHomeFolderBadge() {
        runCatching {
            val lv = cachedConvListView?.get() as? android.widget.ListView ?: return@runCatching
            for (i in 0 until lv.childCount) {
                val row = lv.getChildAt(i) as? ViewGroup ?: continue
                val pos = runCatching { lv.javaClass.getMethod("getPositionForView", View::class.java).invoke(lv, row) as? Int }.getOrDefault(-1)
                val item = runCatching { lv.javaClass.getMethod("getItemAtPosition", Int::class.javaPrimitiveType).invoke(lv, pos) }.getOrNull() ?: continue
                val uname = readRowUsername(item) ?: continue
                if (!isFolderId(uname)) continue
                val unread = runCatching {
                    var u = 0
                    WeDatabaseApi.rawQuery(
                        "SELECT ${ConversationTable.UNREAD_COUNT} FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME}=?",
                        arrayOf(uname)
                    ).use { c -> if (c.moveToFirst()) u = c.getIntOrZero(ConversationTable.UNREAD_COUNT) }
                    u
                }.getOrDefault(0).coerceAtLeast(0)
                val queue = java.util.ArrayDeque<View>(); queue.add(row); var g = 0
                var badgeTv: android.widget.TextView? = null
                var dotIv: View? = null
                while (queue.isNotEmpty() && g++ < 120) {
                    val v = queue.removeFirst()
                    if (v is android.widget.TextView && v.width in 30..100 && v.height in 30..100 &&
                        v.left < 320 && (v.text?.toString()?.all { it.isDigit() } == true)
                    ) {
                        badgeTv = v
                        // red-dot bg only when the sibling overlaps the number closely and is about
                        // the same size; small corner icons (mute/folded etc) must be left alone
                        if (v.parent is ViewGroup) {
                            val p = v.parent as ViewGroup
                            for (c in 0 until p.childCount) {
                                val s = p.getChildAt(c)
                                if (s !== v && s.width in 40..v.width + 30 && s.height in 40..v.height + 30 &&
                                    s is android.widget.ImageView && Math.abs(s.left - v.left) < v.width / 2) dotIv = s
                            }
                        }
                        break
                    }
                    if (v is ViewGroup) for (c in 0 until v.childCount) queue.addLast(v.getChildAt(c))
                }
                if (badgeTv != null) {
                    badgeTv.text = if (unread > 0) unread.toString() else ""
                    badgeTv.visibility = if (unread > 0) View.VISIBLE else View.INVISIBLE
                    if (dotIv != null) dotIv.visibility = if (unread > 0) View.VISIBLE else View.INVISIBLE
                    WeLogger.i(TAG, "restoreHomeBadge uname=$uname unread=$unread tv=${badgeTv.javaClass.simpleName} dot=${dotIv != null}")
                } else {
                    WeLogger.i(TAG, "restoreHomeBadge uname=$uname unread=$unread no badge tv found")
                }
            }
        }.onFailure { WeLogger.w(TAG, "restoreHomeBadge failed: " + it) }
    }

    private fun forceHomeRefresh(tag: String) {
        synchronized(digestCache) { digestCache.clear() }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                val lv = cachedConvListView?.get() as? android.widget.ListView ?: return@runCatching
                val adapter = lv.javaClass.getMethod("getAdapter").invoke(lv) ?: return@runCatching
                // 8.0.78 home list adapter is wrapped in HeaderViewListAdapter; its own
                // notifyDataSetChanged is not resolvable via getMethod, unwrap first.
                var target = adapter
                if (adapter.javaClass.name.contains("HeaderViewListAdapter")) {
                    target = runCatching { adapter.javaClass.getMethod("getWrappedAdapter").invoke(adapter) }
                        .getOrNull() ?: adapter
                }
                restoreHomeFolderBadge()
                val m = runCatching { target.javaClass.getMethod("notifyDataSetChanged") }
                    .getOrElse { adapter.javaClass.methods.firstOrNull { it.name == "notifyDataSetChanged" && it.parameterCount == 0 } }
                    ?: throw NoSuchMethodException("notifyDataSetChanged on " + target.javaClass.name)
                m.isAccessible = true
                m.invoke(target)
                WeLogger.i(TAG, "adapter notifyDataSetChanged ($tag) on " + target.javaClass.name)
                lv.postDelayed({ runCatching { lv.invalidate() } }, 60)
            }.onFailure { WeLogger.w(TAG, "adapter refresh ($tag) failed: " + it, it) }
        }, 300)
    }

    private fun setViewText(v: View, s: CharSequence) {
        if (v is TextView) v.text = s
        else runCatching { v.javaClass.getMethod("setText", CharSequence::class.java).invoke(v, s) }.onFailure { WeLogger.w(TAG, "setViewText fail " + v.javaClass.name + ": " + it) }
    }

    /** 折叠行摘要 View：压缩态=可见 NoMeasuredTextView(ht5 自绘, 非标题)；展开态=与标题同父可见含字母 TextView(dfs)。 */
    private fun findSummaryViewCompat(row: ViewGroup, title: View?): View? {
        runCatching {
            val nmTitle = title?.javaClass?.name?.contains("NoMeasured") == true
            val queue = java.util.ArrayDeque<View>(); queue.add(row)
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 120) {
                val v = queue.removeFirst()
                if (!v.isShown || v === title) continue
                if (v.javaClass.name.contains("NoMeasured")) {
                    if (nmTitle) return v
                } else if (v is TextView && !nmTitle) {
                    val t = v.text?.toString().orEmpty()
                    if (t.any { it.isLetter() } && !isAggSummary(t)) return v
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }.onFailure { }
        return null
    }

    private val digestCache = HashMap<String, Pair<String, Long>>()
    private fun folderDigestCached(username: String): String {
        val now = System.currentTimeMillis()
        synchronized(digestCache) { digestCache[username]?.let { (d, ts) -> if (now - ts < 3000) return d } }
        val d = runCatching {
            var res = ""
            WeDatabaseApi.rawQuery(
                "SELECT ${ConversationTable.DIGEST} FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME}=?",
                arrayOf(username)
            ).use { cursor -> if (cursor.moveToFirst()) res = cursor.getStringOrEmpty(ConversationTable.DIGEST) }
            res
        }.getOrDefault("")
        synchronized(digestCache) { digestCache[username] = d to now }
        return d
    }

    private val summarySpanTs = HashMap<String, Long>()
    private val lastRetitleLog = HashMap<String, Long>()

    private fun setViewColor(v: View, c: Int) {
        if (v is TextView) v.setTextColor(c)
        else runCatching { v.javaClass.getMethod("setTextColor", Int::class.javaPrimitiveType).invoke(v, c) }.onFailure { WeLogger.w(TAG, "setViewColor fail " + v.javaClass.name + ": " + it) }
    }

    /** Fallback for cache-adapter rows: tint the row title with folder-title color */
    private fun tintFolderTitle(summaryView: View?, text: String) {
        if (summaryView == null) { diagFile("tintFolderTitle: summaryView null"); return }
        if (!CHAT_COUNT_REGEX.containsMatchIn(text)) return
        diagFile("tintFolderTitle: start sv=" + summaryView.javaClass.simpleName)
        runCatching {
            var v: View? = summaryView
            var guard = 0
            var firstGroup: ViewGroup? = null
            while (v != null && guard++ < 8) {
                v = v.parent as? View ?: break
                diagFile("tintFolderTitle: parent#" + guard + " " + v.javaClass.simpleName + " vg=" + (v is ViewGroup))
                if (v is ViewGroup) {
                    if (firstGroup == null) firstGroup = v
                    val title = findTitleTextView(v)
                    val tt = title?.let { viewText(it) } ?: ""
                    diagFile("tintFolderTitle: title=" + (tt.take(15)))
                    if (title == null) continue
                    if (!folderTitleNames().contains(tt)) { diagFile("tintFolderTitle: skip not-whitelist: " + tt.take(15)); continue }
                    if (folderTitleEnabled) setViewColor(title, adaptNight(summaryView.context, MENTION_TITLE_BLUE))
                    diagFile("tintFolderTitle: tinted " + tt.take(20) + " color=" + Integer.toHexString(MENTION_TITLE_BLUE))
                    return@runCatching
                }
            }
        }
    }
    @Volatile
    private var folderNameCache: Set<String>? = null
    @Volatile
    private var lastFolderNameLoad = 0L

    /** Folder-name whitelist (cached 5s) so native WeChat fold rows like "fold top chats" are never tinted */
    private fun folderTitleNames(): Set<String> {
        val now = System.currentTimeMillis()
        val cached = folderNameCache
        if (cached != null && now - lastFolderNameLoad < 5000) return cached
        val names = runCatching { loadFolders().map { it.name }.toSet() }.getOrDefault(emptySet())
        diagFile("folderNames: " + names.sorted().joinToString(","))
        folderNameCache = names
        lastFolderNameLoad = now
        return names
    }

    /** 归拢摘要标记判断 */
    private fun isAggSummary(text: String): Boolean =
        text.contains("[有人@我]") || text.contains("[全体]") || text.contains("[@全体]") || text.contains("[自己]") || CHAT_COUNT_REGEX.containsMatchIn(text)

    /** 归拢摘要 Spannable 上色：蓝 [有人@我]/[全体]、黄 [N个聊天]（其余保持微信原生颜色） */
    private fun tintAggSummary(text: String, ctx: Context?): CharSequence {
        val sp = SpannableString(text)
        val atIdx = text.indexOf("[有人@我]")
        if (atIdx >= 0) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_RED)), atIdx, atIdx + "[有人@我]".length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val allIdx = text.indexOf("[@全体]").let { if (it >= 0) it else text.indexOf("[全体]") }
        if (allIdx >= 0) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_RED)), allIdx, text.indexOf(']', allIdx) + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val selfIdx = text.indexOf("[自己]")
        if (selfIdx >= 0 && mentionSelfEnabled) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_GREEN)), selfIdx, selfIdx + "[自己]".length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val m = CHAT_COUNT_REGEX.find(text)
        if (m != null) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_YELLOW)), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val member = MEMBER_PAREN_REGEX.find(text)
        if (member != null && mentionMemberEnabled) sp.setSpan(ForegroundColorSpan(adaptNight(ctx, MENTION_MEMBER)), member.range.first, member.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    /** 反射 dump 实例字段（含父类 4 层），定位 paint/baseline 字段 */
    private fun dumpFields(obj: Any) {
        runCatching {
            val sb = StringBuilder()
            var cls: Class<*>? = obj.javaClass
            var guard = 0
            while (cls != null && guard++ < 4) {
                cls.declaredFields.forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(obj)
                        val vs = v?.toString()?.take(40) ?: "null"
                        sb.append(f.name).append(':').append(f.type.simpleName).append('=').append(vs).append("; ")
                    }
                }
                cls = cls.superclass
            }
            diagFile("NMTV_FIELDS[${obj.javaClass.simpleName}] $sb")
        }
    }


    /** 归拢摘要分段着色：[@] 蓝、[N个聊天] 黄、其余恢复微信原生颜色，尾部超宽省略 */
    private fun drawTintedSummary(tv: TextView, canvas: Canvas, text: String) {
        // 开关关闭时不叠加彩色标签（实时读 pref，设置子项切换后立即生效）。
        if (!WePrefs.getBoolOrFalse(ConversationAggregationColors.ENABLED_PREF_KEY)) return
        runCatching {
            val paint = TextPaint(tv.paint)
            val originalColor = tv.currentTextColor
            val baseX = tv.paddingLeft.toFloat()
            val baseY = tv.baseline.toFloat()
            val maxWidth = tv.width - tv.paddingLeft - tv.paddingRight
            val red = adaptNight(tv.context, MENTION_RED)
            val yellow = adaptNight(tv.context, MENTION_YELLOW)
            val green = adaptNight(tv.context, MENTION_GREEN)
            val memberColor = adaptNight(tv.context, MENTION_MEMBER)

            data class Seg(val start: Int, val end: Int, val color: Int)
            val segs = mutableListOf<Seg>()
            val atIdx = text.indexOf("[有人@我]")
            if (atIdx >= 0) segs.add(Seg(atIdx, atIdx + "[有人@我]".length, red))
            val allIdx = text.indexOf("[@全体]").let { if (it >= 0) it else text.indexOf("[全体]") }
            if (allIdx >= 0) segs.add(Seg(allIdx, text.indexOf(']', allIdx) + 1, red))
            val selfIdx = text.indexOf("[自己]")
            if (selfIdx >= 0 && mentionSelfEnabled) segs.add(Seg(selfIdx, selfIdx + "[自己]".length, green))
            val m = Regex("""\[[^\]]*个(?:聊天|消息)\]""").find(text)
            if (m != null) segs.add(Seg(m.range.first, m.range.last + 1, yellow))
            segs.sortBy { it.start }
            val member = MEMBER_PAREN_REGEX.find(text)
            if (member != null && mentionMemberEnabled) segs.add(Seg(member.range.first, member.range.last + 1, memberColor))

            var x = baseX
            var cur = 0
            for (seg in segs) {
                if (seg.start > cur) {
                    // 段间未着色文本：原色
                    paint.color = originalColor
                    val s = text.substring(cur, seg.start)
                    canvas.drawText(s, x, baseY, paint)
                    x += paint.measureText(s)
                }
                paint.color = seg.color
                val s = text.substring(seg.start, seg.end)
                canvas.drawText(s, x, baseY, paint)
                x += paint.measureText(s)
                cur = seg.end
            }
            if (cur < text.length) {
                val rest = text.substring(cur)
                val remain = maxWidth - (x - baseX)
                val shown = if (remain > 0) {
                    TextUtils.ellipsize(rest, paint, remain, TextUtils.TruncateAt.END).toString()
                } else ""
                if (shown.isNotEmpty()) {
                    paint.color = originalColor
                    canvas.drawText(shown, x, baseY, paint)
                }
            }
        }
    }

    private var diagWriteCount = 0L
    private fun diagFile(msg: String) {
        runCatching {
            val f = java.io.File("/sdcard/Android/data/com.tencent.mm/WCX/diag.log")
            f.parentFile?.mkdirs()
            if ((++diagWriteCount and 0x3F) == 1L) { // every 64 writes, bound the file
                val len = f.length()
                if (len > DIAG_MAX_BYTES) {
                    java.io.RandomAccessFile(f, "rw").use { raf ->
                        val total = raf.length()
                        val skip = (total - DIAG_KEEP_BYTES).toInt().coerceAtLeast(0)
                        raf.seek(skip.toLong())
                        val buf = ByteArray((total - skip).toInt())
                        raf.readFully(buf)
                        var start = 0
                        while (start < buf.size && buf[start] != 10.toByte()) start++
                        raf.setLength(0)
                        raf.seek(0)
                        raf.write(buf, start, buf.size - start)
                    }
                }
            }
            f.appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
        }
    }

    // ==================== FunBox 同款：归拢摘要叠加染色（Item 根 View dispatchDraw after） ====================
    // 方案：不拦截 NoMeasuredTextView 原生绘制（灰色原文照常输出），
    // 在 Item 根 View dispatchDraw 之后用 Canvas 叠加彩色标签（蓝 [有人@我]/[全体]、黄 [N个聊天]），
    // 彩色文字盖在灰色文字上方。bind 阶段（getView）给 Item 根 setTag，无需向上遍历父布局。

    /** Item 根 View 的 Tag key：标记归拢虚拟行 */
    private const val TAG_KEY_VIRTUAL = 0x5A110001
    /** Item 根 View 的 Tag key：归拢摘要着色状态 */
    private const val TAG_KEY_STATE = 0x5A110002
    /** Item 根 View 的 Tag key：内部摘要 NoMeasuredTextView 引用 */
    private const val TAG_KEY_SUMMARY_TV = 0x5A110003
    /** Item 根 View 的 Tag key：内部标题 TextView 引用（文件夹标题染蓝） */
    private const val TAG_KEY_TITLE_TV = 0x5A110004
    private const val TAG_KEY_TITLE_ORIG = 0x5A110005

    /** 归拢摘要着色状态（bind 阶段解析，onDraw 阶段直接读取） */
    private class MergeUiState(
        val atAll: Boolean,
        val atMe: Boolean,
        val self: Boolean,
        val chatCount: Int,
        val memberName: String?,
        val fullText: String
    )

    /** 已 hook dispatchDraw 的 Item 根类（去重） */
    private val hookedItemDrawClasses = java.util.Collections.synchronizedSet(java.util.HashSet<Class<*>>())

    /**
     * bind 阶段（getView 后）识别归拢虚拟行：找内部 NoMeasuredTextView 摘要控件，
     * 文本含 [有人@我]/[全体]/[N个聊天] 归拢标记即标记该行为虚拟行并记录着色状态。
     * RecyclerView 复用旧 item 时先清 tag（未命中即普通会话）。
     */
    private fun markVirtualRow(root: ViewGroup) {
        runCatching {
            val queue = java.util.ArrayDeque<View>()
            queue.add(root)
            var summaryTv: TextView? = null
            var titleTv: TextView? = null
            var fullText = ""
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                if (v is TextView) {
                    val t = v.text?.toString().orEmpty()
                    // 标题：非摘要、含文字（排除未读数角标等纯数字/时间控件）、字号最大的 TextView
                    if (!isAggSummary(t) && t.any { it.isLetter() } && (titleTv == null || v.textSize > titleTv.textSize)) {
                        titleTv = v
                        WeLogger.i(TAG, "titleTv candidate: class=${v.javaClass.simpleName} textSize=${v.textSize} text=${t.take(20)}")
                    }
                    // 归拢摘要标记命中即识别（摘要控件不限于 NoMeasuredTextView）
                    if (isAggSummary(t) && summaryTv == null) {
                        summaryTv = v
                        fullText = t
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
            if (summaryTv == null && fullText.isEmpty()) {
                root.setTag(TAG_KEY_VIRTUAL, null)
                root.setTag(TAG_KEY_STATE, null)
                root.setTag(TAG_KEY_SUMMARY_TV, null)
                root.setTag(TAG_KEY_TITLE_TV, null)
                return
            }
            val atAll = fullText.contains("[全体]") || fullText.contains("[@全体]")
            val atMe = fullText.contains("[有人@我]")
            val chatCount = runCatching {
                CHAT_COUNT_REGEX.find(fullText)?.value
                    ?.trim('[', ']')?.replace("个聊天", "")?.replace("个消息", "")?.toIntOrNull() ?: 0
            }.getOrDefault(0)
            root.setTag(TAG_KEY_VIRTUAL, true)
            root.setTag(TAG_KEY_STATE, MergeUiState(atAll, atMe, fullText.contains("[自己]"), chatCount, MEMBER_PAREN_REGEX.find(fullText)?.value, fullText))
            root.setTag(TAG_KEY_SUMMARY_TV, summaryTv)
            root.setTag(TAG_KEY_TITLE_TV, titleTv)
            root.setTag(TAG_KEY_TITLE_ORIG, titleTv?.currentTextColor)
            ensureItemDispatchDrawHook(root.javaClass)
        }
    }

    /** 首次遇到某 Item 根类时 hook 其 dispatchDraw(after)：子 View（含灰色摘要）画完后叠加彩色 */
    private fun ensureItemDispatchDrawHook(cls: Class<*>) {
        if (!hookedItemDrawClasses.add(cls)) return
        runCatching {
            cls.getMethod("dispatchDraw", android.graphics.Canvas::class.java).hookAfterDirectly {
                val list = thisObject as? ViewGroup ?: return@hookAfterDirectly
                cachedConvListView = WeakReference(list)
                runCatching {
                    // Self-heal: 8.0.78 never routes the home resume through MainUI.onResume,
                    // so the folder index can stay empty after a cold start and refreshes no-op.
                    if (folderByMember.isEmpty() && WeDatabaseApi.isReady &&
                        SystemClock.uptimeMillis() - lastReconcileAttempt > 2000L
                    ) {
                        lastReconcileAttempt = SystemClock.uptimeMillis()
                        WeLogger.i(TAG, "lazy reconcile: index empty on home frame")
                        syncFoldersToDatabase()
                    }
                    for (i in 0 until list.childCount) {
                        val row = list.getChildAt(i)
                        if (row is ViewGroup) tintFolderTitleByText(row)
                    }
                }
            }
            diagFile("ItemDispatchDraw hook: ${cls.name}")
            WeLogger.i(TAG, "ItemDispatchDraw hook: ${cls.name}")
        }.onFailure {
            diagFile("ItemDispatchDraw hook FAIL ${cls.name}: $it")
            WeLogger.w(TAG, "ItemDispatchDraw hook fail", it)
        }
    }



    private fun XC_MethodHook.MethodHookParam.tintHolder() {
        val holder = args?.getOrNull(0) ?: return
        val itemView = runCatching {
            holder.javaClass.getMethod("getItemView").invoke(holder) as? View
        }.getOrNull() ?: return
        val root = itemView as? ViewGroup ?: return
        WeLogger.i(TAG, "bindViewHolder fired: root=${root.javaClass.simpleName} children=${root.childCount}")
        diagFile("bindViewHolder fired: ${root.javaClass.simpleName} children=${root.childCount}")
        tintMentionLabels(root, "bind")
        markVirtualRow(root)
        root.post {
            WeLogger.i(TAG, "bindViewHolder post fired: root=${root.javaClass.simpleName}")
        diagFile("bindViewHolder post fired: ${root.javaClass.simpleName}")
            tintMentionLabels(root, "post")
            markVirtualRow(root)
        }
    }

    private fun tintMention(text: String, ctx: Context?): CharSequence? {
        // 「对话归拢摘要颜色」开关控制所有摘要染色：关闭时不注入彩色 span，恢复微信默认灰色。
        if (!WePrefs.getBoolOrFalse(ConversationAggregationColors.ENABLED_PREF_KEY)) return null
        val atIdx = text.indexOf("[\u6709\u4eba@\u6211]")
        val selfIdx = text.indexOf("[自己]")
        val chatMatch = CHAT_COUNT_REGEX.find(text)
        if (atIdx < 0 && chatMatch == null && selfIdx < 0) return null
        val spannable = SpannableString(text)
        if (atIdx >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(MENTION_RED),
                atIdx,
                (atIdx + 6).coerceAtMost(text.length),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (selfIdx >= 0 && mentionSelfEnabled) {
            spannable.setSpan(
                ForegroundColorSpan(MENTION_GREEN),
                selfIdx,
                selfIdx + "[自己]".length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (chatMatch != null) {
            spannable.setSpan(
                ForegroundColorSpan(MENTION_YELLOW),
                chatMatch.range.first,
                chatMatch.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun tintMentionLabels(root: ViewGroup, tag: String) {
        var tvCount = 0
        var hit = 0
        val queue = java.util.ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is TextView) {
                tvCount++
                hookTextViewClass(v)
                val text = v.text?.toString().orEmpty()
                val tinted = tintMention(text, root.context)
                if (tinted != null) {
                    hit++
                    v.setText(tinted)
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }
        if (hit > 0) WeLogger.i(TAG, "tint[$tag] done tv=$tvCount hit=$hit")
    }

    private fun hookSqliteExec() {
        runCatching {
            android.database.sqlite.SQLiteDatabase::class.java
                .getMethod("execSQL", String::class.java)
                .hookBeforeDirectly {
                    val sql = args?.getOrNull(0) as? String ?: return@hookBeforeDirectly
                    val low = sql.lowercase()
                    if (low.contains("rconversation") && (low.contains("update") || low.contains("unread"))) {
                        WeLogger.i(TAG, "execSQL: $sql")
                        diagFile("execSQL: $sql")
                    }
                }
            WeLogger.i(TAG, "execSQL hook registered")
            diagFile("execSQL hook registered")
        }.onFailure { WeLogger.w(TAG, "hook execSQL failed", it) }
    }

    private val methodRecyclerOnBind by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "onBindViewHolder"
            paramTypes("androidx.recyclerview.widget.RecyclerView\$ViewHolder", "int")
        }
    }

    private val methodSupportRecyclerOnBind by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "onBindViewHolder"
            paramTypes("android.support.v7.widget.RecyclerView\$ViewHolder", "int")
        }
    }

    private val methodTextViewSetText by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "setText"
            paramTypes("java.lang.CharSequence")
        }
    }

    /** Global fallback: tint injected mention/chat-count text wherever a TextView renders it. */
    private fun hookTextViewSetText() {
        if (methodTextViewSetText.isPlaceholder) return
        methodTextViewSetText.hookBefore {
            val a = args ?: return@hookBefore
            val text = a.getOrNull(0) as? CharSequence ?: return@hookBefore
            val s0 = text.toString()
            if (folderTitleNames().contains(s0)) {
                // 文件夹标题染色：受「文件夹标题染色」独立开关控制（与摘要总开关互不影响）。
                if (folderTitleEnabled) {
                a[0] = android.text.SpannableString(s0).apply { setSpan(android.text.style.ForegroundColorSpan(MENTION_TITLE_BLUE), 0, s0.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                return@hookBefore
            }
            val tinted = tintMention(s0, (thisObject as? View)?.context)
            if (tinted != null) {
                a[0] = tinted
            }
        }

        // 兜底：hook 基类 TextView.setText，覆盖不 override 的 TextView 子类
        runCatching {
            val baseSetText = android.widget.TextView::class.java
                .getMethod("setText", java.lang.CharSequence::class.java)
            baseSetText.hookBeforeDirectly {
                val a = args ?: return@hookBeforeDirectly
                val text = a.getOrNull(0) as? CharSequence ?: return@hookBeforeDirectly
                val tinted = tintMention(text.toString(), (thisObject as? View)?.context)
                if (tinted != null) {
                    a[0] = tinted
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook TextView.setText failed", it) }

    }

    private fun hookConversationStorageUpdateUnread() {
        if (methodConversationStorageUpdateUnreadByTalker.isPlaceholder) {
            WeLogger.i(TAG, "updateUnreadByTalker hook MISSING in 8.0.78 — box read-on-leave not blocked")
            diagFile("updateUnreadByTalker hook MISSING in 8.0.78")
            return
        }
        methodConversationStorageUpdateUnreadByTalker.hookBefore {
            val username = args.firstOrNull() as? String ?: return@hookBefore
            WeLogger.i(TAG, "updateUnreadByTalker: $username folder=${isFolderId(username)}")
            diagFile("updateUnreadByTalker: $username folder=${isFolderId(username)}")
            // WeChat fires b0(conversationboxservice) while ENTERING the box page (8.0.78 observed) -
            // it clears the box aggregate read; our folder container reuses the box UI so block it too.
            if (isFolderId(username) || username == WeChatFolderPlaceholder.CONVERSATION_BOX) result = true
        }
    }

    private fun launchFolderContainer(source: Any?, folderId: String) {
        val context = source as? Context ?: return
        val intent = Intent().apply {
            setClassName(context, CONTAINER_UI_NAME)
            applyFolderContainerIntent(this, folderId)
        }
        context.startActivity(intent)
    }

    private fun applyFolderContainerIntent(intent: Intent, folderId: String) {
        intent.putExtra(WeChatIntentExtra.CONTACT_USER, folderId)
        intent.putExtra(WeChatIntentExtra.CONTACT_CHAT_ROOM_ID, folderId)
        intent.putExtra(WeChatIntentExtra.ROOM_NAME, folderId)
    }

    private fun configureFolderActivity(activity: BaseConversationUI) {
        val folder = folderById(activeFolderId ?: return) ?: return
        activity.setTitle(folder.name)

        val fragment = activity.conversationFm
        runCatching {
            val fm = fragment.javaClass
            fm.getMethod("setMMTitle", String::class.java).invoke(fragment, folder.name)
        }.onFailure { WeLogger.w(TAG, "setMMTitle fallback failed", it) }

        // onResume may fire repeatedly; drop any previous entry before re-adding
        fragment.removeOptionMenu(FOLDER_CONFIG_MENU_ID)

        val listener = MenuItem.OnMenuItemClickListener {
            showEditFolderDialog(
                context = activity,
                folder = folder,
                onFolderUpdated = {
                    syncFoldersToDatabase()
                    configureFolderActivity(activity)
                },
                onFolderDeleted = {
                    syncFoldersToDatabase()
                    activity.finish()
                }
            )
            true
        }

        fragment.addIconOptionMenu(FOLDER_CONFIG_MENU_ID, "配置", EditIcon, listener)
    }

    private fun syncFoldersToDatabase() {
        val handler = refreshHandler ?: return
        handler.removeCallbacksAndMessages(RECONCILE_TASK_TOKEN)
        handler.postAtTime(
            { reconcileFolders(loadFolders()) },
            RECONCILE_TASK_TOKEN,
            SystemClock.uptimeMillis()
        )
    }

    private fun reconcileFolders(folders: List<ChatFolder>) {
        if (!WeDatabaseApi.isReady) return
        val startedAt = SystemClock.elapsedRealtime()
        var databaseChanged = false
        runCatching {
            withQueryRewriteSuppressed {
                if (!isFolderSchemaReady()) return@withQueryRewriteSuppressed
                folderMembersCache.clear()
                val desiredMembers = resolveOwnedMembers(folders)
                val desiredOwners = reverseMemberIndex(desiredMembers)
                val currentOwners = readCurrentMemberOwners()
                val storedRows = readStoredFolderRows()
                val liveFlags = storedRows.mapValues { it.value.flag }
                persistChangedPinFlags(folders, liveFlags)

                val desiredFolderIds = folders.mapTo(linkedSetOf()) { it.id }
                val storedFolderIds = readStoredFolderIds()
                val removedFolderIds = storedFolderIds - desiredFolderIds
                val changedOwnerMembers = currentOwners.filter { (member, owner) ->
                    desiredOwners[member] != owner
                }.keys
                val removedMembers = changedOwnerMembers.filterTo(linkedSetOf()) { it !in desiredOwners }
                val changedBindings = desiredOwners.filter { (member, owner) ->
                    currentOwners[member] != owner
                }
                val existingContacts = readFolderContactNames(desiredFolderIds)
                val existingAvatarRows = readExistingAvatarRows(desiredFolderIds)
                val summaries = readFolderSummaries(desiredMembers, storedRows)
                val changedSummaries = folders.mapNotNull { folder ->
                    val summary = summaries[folder.id] ?: FolderSummary()
                    val stored = storedRows[folder.id]
                    if (stored == null ||
                        stored.summary != summary ||
                        stored.attrFlag != summary.attrFlag ||
                        stored.flag and FLAG_TIME_MASK != summary.conversationTime and FLAG_TIME_MASK
                    ) {
                        folder.id to summary
                    } else {
                        null
                    }
                }
                databaseChanged = changedBindings.isNotEmpty() || changedSummaries.isNotEmpty() ||
                        removedMembers.isNotEmpty() || removedFolderIds.isNotEmpty() ||
                        folders.any { it.id !in storedRows || existingContacts[it.id] != it.name } ||
                        desiredFolderIds.any { it !in existingAvatarRows }

                if (databaseChanged) {
                    WeDatabaseApi.transaction {
                        deleteEmptyPlaceholderRows(removedMembers)
                        unbindMembers(removedMembers)
                        ensureManualMemberRows(folders, changedBindings.keys)
                        bindMembers(changedBindings)
                        deleteStoredFolders(removedFolderIds)

                        folders.forEach { folder ->
                            if (folder.id !in storedRows) {
                                ensureFolderConversationRow(folder)
                            }
                            if (existingContacts[folder.id] != folder.name) {
                                writeFolderContact(folder)
                            }
                            if (folder.id !in existingAvatarRows) {
                                writeFolderAvatar(folder.id)
                            }
                        }

                        changedSummaries.forEach { (folderId, summary) ->
                            writeFolderSummaryRow(folderId, summary)
                        }
                    }
                }

                membersByFolder = desiredMembers
                folderByMember = desiredOwners
                desiredMembers.forEach { (folderId, members) ->
                    folderMembersCache[folderId] = members
                }

                WeLogger.i(
                    TAG,
                    "reconciled ${folders.size} folders: bindings=${changedBindings.size}, " +
                    "unbound=${removedMembers.size}, removed=${removedFolderIds.size}, " +
                            "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
                )
            }
            if (databaseChanged) WeConversationApi.reloadConversations()
        }.onFailure {
            WeLogger.e(TAG, "failed to sync folders", it)
        }
    }

    private fun resolveOwnedMembers(folders: List<ChatFolder>): Map<String, List<String>> {
        val candidates = linkedMapOf<String, List<String>>()
        val ownerByMember = linkedMapOf<String, String>()
        folders.forEach { folder ->
            val members = resolveFolderMembers(folder).filterNot(::isFolderId).distinct()
            candidates[folder.id] = members
            members.forEach { ownerByMember[it] = folder.id }
        }
        return candidates.mapValues { (folderId, members) ->
            members.filter { ownerByMember[it] == folderId }
        }
    }

    private fun reverseMemberIndex(byFolder: Map<String, List<String>>): Map<String, String> =
        buildMap { byFolder.forEach { (folderId, members) -> members.forEach { put(it, folderId) } } }

    private fun readCurrentMemberOwners(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        WeDatabaseApi.rawQuery(
            "SELECT ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF} " +
                    "FROM ${ConversationTable.NAME} WHERE ${ConversationTable.PARENT_REF} LIKE ?",
            arrayOf("$FOLDER_PREFIX%")
        ).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
        }
        return result
    }

    private fun readStoredFolderRows(): Map<String, StoredFolderRow> {
        val result = linkedMapOf<String, StoredFolderRow>()
        WeDatabaseApi.rawQuery(
            """
            SELECT ${ConversationTable.USERNAME}, ${ConversationTable.FLAG}, ${ConversationTable.DIGEST},
                   ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS},
                   ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.UNREAD_COUNT},
                   ${ConversationTable.UNREAD_MUTE_COUNT}, ${ConversationTable.CONTENT},
                   ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}, ${ConversationTable.ATTR_FLAG}, ${ConversationTable.AT_COUNT}
            """.trimIndent() + " " +
                    "FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} LIKE ?",
            arrayOf("$FOLDER_PREFIX%")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = StoredFolderRow(
                    flag = cursor.getLongOrZero(ConversationTable.FLAG),
                    attrFlag = cursor.getIntOrZero(ConversationTable.ATTR_FLAG),
                    summary = FolderSummary(
                        digest = cursor.getStringOrEmpty(ConversationTable.DIGEST),
                        digestUser = cursor.getStringOrEmpty(ConversationTable.DIGEST_USER),
                        isSend = cursor.getIntOrZero(ConversationTable.IS_SEND),
                        status = cursor.getIntOrZero(ConversationTable.STATUS),
                        conversationTime = cursor.getLongOrZero(ConversationTable.CONVERSATION_TIME),
                        unreadCount = cursor.getIntOrZero(ConversationTable.UNREAD_COUNT),
                        unreadMuteCount = cursor.getIntOrZero(ConversationTable.UNREAD_MUTE_COUNT),
                        content = cursor.getStringOrEmpty(ConversationTable.CONTENT),
                        msgType = cursor.getStringOrEmpty(ConversationTable.MSG_TYPE),
                        chatMode = cursor.getIntOrZero(ConversationTable.CHAT_MODE),
                        atMeCount = cursor.getIntOrZero(ConversationTable.AT_COUNT)
                    )
                )
            }
        }
        return result
    }

    private fun readStoredFolderIds(): Set<String> {
        val result = linkedSetOf<String>()
        listOf(ConversationTable.NAME, ContactTable.NAME, "img_flag").forEach { table ->
            WeDatabaseApi.rawQuery(
                "SELECT username FROM $table WHERE username LIKE ?",
                arrayOf("$FOLDER_PREFIX%")
            ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        }
        return result
    }

    private fun readFolderContactNames(folderIds: Set<String>): Map<String, String> {
        if (folderIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        folderIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { ids ->
            WeDatabaseApi.rawQuery(
                "SELECT ${ContactTable.USERNAME}, ${ContactTable.NICKNAME} FROM ${ContactTable.NAME} " +
                        "WHERE ${ContactTable.USERNAME} IN (${placeholders(ids.size)})",
                ids.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1) ?: ""
            }
        }
        return result
    }

    private fun readExistingAvatarRows(folderIds: Set<String>): Set<String> {
        if (folderIds.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        folderIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { ids ->
            WeDatabaseApi.rawQuery(
                "SELECT username FROM img_flag WHERE username IN (${placeholders(ids.size)})",
                ids.toTypedArray()
            ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        }
        return result
    }

    private fun persistChangedPinFlags(folders: List<ChatFolder>, liveFlags: Map<String, Long>) {
        var changed = false
        val updated = folders.map { folder ->
            val liveHigh = liveFlags[folder.id]?.and(FLAG_HIGH_MASK) ?: return@map folder
            if (liveHigh == folder.pinFlag) return@map folder
            changed = true
            folder.copy(pinFlag = liveHigh)
        }
        if (changed) saveFolders(updated)
    }

    private fun clearStaleFolderMappings() {
        listOf(FOLDER_PREFIX).forEach { prefix ->
            WeDatabaseApi.execStatement(
                """
                DELETE FROM ${ConversationTable.NAME}
                WHERE ${ConversationTable.PARENT_REF} LIKE ?
                  AND ${ConversationTable.DIGEST}=''
                  AND ${ConversationTable.CONTENT}=''
                  AND ${ConversationTable.UNREAD_COUNT}=0
                  AND ${ConversationTable.CONVERSATION_TIME}=0
                  AND ${ConversationTable.FLAG}=0
                  AND ${ConversationTable.MSG_TYPE}=''
                  AND ${ConversationTable.STATUS}=0
                  AND ${ConversationTable.IS_SEND}=0
                """.trimIndent(),
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}='' WHERE ${ConversationTable.PARENT_REF} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ConversationTable.NAME} WHERE ${ConversationTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM ${ContactTable.NAME} WHERE ${ContactTable.USERNAME} LIKE ?",
                arrayOf("$prefix%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM img_flag WHERE username LIKE ?",
                arrayOf("$prefix%")
            )
        }
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

    private fun deleteEmptyPlaceholderRows(members: Collection<String>) {
        members.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            WeDatabaseApi.execStatement(
                """
                DELETE FROM ${ConversationTable.NAME}
                WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})
                  AND ${ConversationTable.PARENT_REF} LIKE ?
                  AND ${ConversationTable.DIGEST}='' AND ${ConversationTable.CONTENT}=''
                  AND ${ConversationTable.UNREAD_COUNT}=0 AND ${ConversationTable.CONVERSATION_TIME}=0
                  AND ${ConversationTable.FLAG}=0 AND ${ConversationTable.MSG_TYPE}=''
                  AND ${ConversationTable.STATUS}=0 AND ${ConversationTable.IS_SEND}=0
                """.trimIndent(),
                arrayOf(*chunk.toTypedArray(), "$FOLDER_PREFIX%")
            )
        }
    }

    private fun unbindMembers(members: Collection<String>) {
        members.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            WeDatabaseApi.execStatement(
                "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}='' " +
                        "WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)}) " +
                        "AND ${ConversationTable.PARENT_REF} LIKE ?",
                arrayOf(*chunk.toTypedArray(), "$FOLDER_PREFIX%")
            )
        }
    }

    private fun ensureManualMemberRows(folders: List<ChatFolder>, changedMembers: Collection<String>) {
        val changed = changedMembers.toHashSet()
        folders.filter { it.type == FolderType.MANUAL }.forEach { folder ->
            folder.members.asSequence()
                .filter { it in changed && !isFolderId(it) }
                .distinct()
                .chunked(SQLITE_BIND_CHUNK_SIZE / 2)
                .forEach { chunk ->
                    val values = chunk.joinToString(",") { "(?, ?, '', '', 0, 0, 0, 0, 0, 0, '', '', 0)" }
                    val args: Array<Any> = chunk.flatMap { listOf<Any>(it, folder.id) }.toTypedArray()
                    WeDatabaseApi.execStatement(
                        """
                        INSERT OR IGNORE INTO ${ConversationTable.NAME} (
                            ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF}, ${ConversationTable.DIGEST},
                            ${ConversationTable.DIGEST_USER}, ${ConversationTable.IS_SEND}, ${ConversationTable.STATUS},
                            ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.FLAG}, ${ConversationTable.UNREAD_COUNT},
                            ${ConversationTable.UNREAD_MUTE_COUNT}, ${ConversationTable.CONTENT},
                            ${ConversationTable.MSG_TYPE}, ${ConversationTable.CHAT_MODE}
                        ) VALUES $values
                        """.trimIndent(),
                        args
                    )
                }
        }
    }

    private fun bindMembers(bindings: Map<String, String>) {
        bindings.entries.groupBy({ it.value }, { it.key }).forEach { (folderId, members) ->
            members.chunked(SQLITE_BIND_CHUNK_SIZE - 1).forEach { chunk ->
                WeDatabaseApi.execStatement(
                    "UPDATE ${ConversationTable.NAME} SET ${ConversationTable.PARENT_REF}=? " +
                            "WHERE ${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})",
                    arrayOf(folderId, *chunk.toTypedArray())
                )
            }
        }
    }

    private fun deleteStoredFolders(folderIds: Set<String>) {
        folderIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            val where = "username IN (${placeholders(chunk.size)})"
            listOf(ConversationTable.NAME, ContactTable.NAME, "img_flag").forEach { table ->
                WeDatabaseApi.execStatement("DELETE FROM $table WHERE $where", chunk.toTypedArray())
            }
        }
    }

    private fun ensureFolderConversationRow(folder: ChatFolder) {
        WeDatabaseApi.execStatement(
            """
            INSERT OR IGNORE INTO ${ConversationTable.NAME} (
                ${ConversationTable.USERNAME}, ${ConversationTable.PARENT_REF}, ${ConversationTable.FLAG},
                ${ConversationTable.CONVERSATION_TIME}, ${ConversationTable.DIGEST}, ${ConversationTable.CONTENT}
            ) VALUES (?, '', ?, 0, '', '')
            """.trimIndent(),
            arrayOf(folder.id, folder.pinFlag and FLAG_HIGH_MASK)
        )
    }

    private fun writeFolderContact(folder: ChatFolder) {
        WeDatabaseApi.execStatement(
            """
            REPLACE INTO ${ContactTable.NAME} (
                ${ContactTable.USERNAME}, ${ContactTable.NICKNAME}, ${ContactTable.TYPE}, ${ContactTable.VERIFY_FLAG}
            ) VALUES (?, ?, 3, 0)
            """.trimIndent(),
            arrayOf(
                folder.id,
                folder.name.take(MAX_FOLDER_DISPLAY_NAME) +
                    if (folder.name.length > MAX_FOLDER_DISPLAY_NAME) "\u2026" else ""
            )
        )
    }

    private fun writeFolderAvatar(folderId: String) {
        WeDatabaseApi.execStatement(
            """
            INSERT OR IGNORE INTO img_flag (username, imgflag, lastupdatetime, reserved1, reserved2)
            VALUES (?, 3, ?, 0, ?)
            """.trimIndent(),
            arrayOf(folderId, System.currentTimeMillis() / 1000, "http://wekit.local/avatar/$folderId")
        )
    }

    /** Updates a materialized folder row while preserving WeChat's live pin bits. */
    private fun writeFolderSummaryRow(folderId: String, summary: FolderSummary) {
        WeDatabaseApi.execStatement(
            """
            UPDATE ${ConversationTable.NAME} SET
                ${ConversationTable.DIGEST}=?, ${ConversationTable.DIGEST_USER}=?,
                ${ConversationTable.IS_SEND}=?, ${ConversationTable.STATUS}=?,
                ${ConversationTable.CONVERSATION_TIME}=?,
                ${ConversationTable.FLAG}=(${ConversationTable.FLAG} & ?) | ?,
                ${ConversationTable.UNREAD_COUNT}=?, ${ConversationTable.UNREAD_MUTE_COUNT}=?,
                ${ConversationTable.CONTENT}=?, ${ConversationTable.MSG_TYPE}=?,
                ${ConversationTable.CHAT_MODE}=?, ${ConversationTable.ATTR_FLAG}=?,
                ${ConversationTable.AT_COUNT}=?
            WHERE ${ConversationTable.USERNAME}=?
            """.trimIndent(),
            arrayOf(
                summary.digest,
                summary.digestUser,
                summary.isSend,
                summary.status,
                summary.conversationTime,
                FLAG_HIGH_MASK,
                summary.conversationTime and FLAG_TIME_MASK,
                summary.unreadCount,
                summary.unreadMuteCount,
                summary.content,
                summary.msgType,
                summary.chatMode,
                summary.attrFlag,
                summary.atMeCount,
                folderId
            )
        )
    }

    private fun readFolderSummaries(
        byFolder: Map<String, List<String>>,
        storedRows: Map<String, StoredFolderRow> = emptyMap()
    ): Map<String, FolderSummary> {
        val ownerByMember = reverseMemberIndex(byFolder)
        val states = byFolder.mapValuesTo(linkedMapOf()) { SummaryAccumulator() }
        val members = ownerByMember.keys.toList()

        members.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            WeDatabaseApi.rawQuery(
                """
                SELECT r.${ConversationTable.USERNAME}, r.${ConversationTable.DIGEST},
                       r.${ConversationTable.DIGEST_USER}, r.${ConversationTable.IS_SEND},
                       r.${ConversationTable.STATUS}, r.${ConversationTable.CONVERSATION_TIME},
                       r.${ConversationTable.UNREAD_COUNT}, r.${ConversationTable.UNREAD_MUTE_COUNT},
                       r.${ConversationTable.CONTENT},
                       r.${ConversationTable.MSG_TYPE}, r.${ConversationTable.CHAT_MODE},
                       r.${ConversationTable.AT_COUNT},
                       c.${ContactTable.TYPE}, c.${ContactTable.LV_BUFF},
                       c.${ContactTable.CON_REMARK}, c.${ContactTable.NICKNAME}
                FROM ${ConversationTable.NAME} r
                LEFT JOIN ${ContactTable.NAME} c
                  ON c.${ContactTable.USERNAME}=r.${ConversationTable.USERNAME}
                WHERE r.${ConversationTable.USERNAME} IN (${placeholders(chunk.size)})
                """.trimIndent(),
                chunk.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val username = cursor.getStringOrEmpty(ConversationTable.USERNAME)
                    val folderId = ownerByMember[username] ?: continue
                    val state = states.getValue(folderId)
                    val unread = cursor.getIntOrZero(ConversationTable.UNREAD_COUNT).coerceAtLeast(0)
                    // 微信权威免打扰未读数：免打扰会话 unReadCount==0、unReadMuteCount>0，
                    // 必须直接进 mutedUnread，否则免打扰未读永远不会计入文件夹角标。
                    val muteUnread = cursor.getIntOrZero(ConversationTable.UNREAD_MUTE_COUNT).coerceAtLeast(0)
                    val digestRaw = cursor.getStringOrEmpty(ConversationTable.DIGEST)
                    val contentRaw = cursor.getStringOrEmpty(ConversationTable.CONTENT)
                    // atCount 的 bit24 = 微信权威「有人@所有人」标志（日志实测 16777216），
                    // 摘要文本不一定含「所有人/全体」等词，仅靠文本会漏判成 [有人@我]。
                    val atCountRow = cursor.getIntOrZero(ConversationTable.AT_COUNT).coerceAtLeast(0)
                    val everyoneBitHit = atCountRow and AT_COUNT_EVERYONE_BIT != 0
                    if (muteUnread > 0) {
                        // 微信权威免打扰未读：unReadMuteCount 本身即免打扰未读数
                        state.mutedUnread += muteUnread
                        state.unreadChatCount++
                        if (everyoneBitHit || isEveryoneMention(digestRaw, contentRaw)) state.everyoneMentioned = true
                    } else if (unread > 0) {
                        val muted = if (username.endsWith("@chatroom")) {
                            val index = cursor.getColumnIndex(ContactTable.LV_BUFF)
                            val lvBuff = if (index >= 0 && !cursor.isNull(index)) cursor.getBlob(index) else null
                            val notify = WeConversationApi.parseChatRoomNotify(lvBuff)
                            // lvbuff 缺失/解析失败时 parseChatRoomNotify 返回 null（null==0 会误判为非免打扰），
                            // 保守视为免打扰：宁可归入小圆点也不让免打扰群聊未读闪现红色数字角标
                            notify == null || notify == 0
                        } else {
                            cursor.getIntOrZero(ContactTable.TYPE) and 512 != 0
                        }
                        if (muted) {
                            state.mutedUnread += unread
                        } else {
                            state.normalUnread += unread
                        }
                        // [N个聊天] = 归拢文件夹里有未读的聊天数（FunBox 语义，不限免打扰）
                        state.unreadChatCount++
                        // @所有人 是聚合标记：任一未读成员行命中即显示 [@全体]，不只看最新一条
                        if (everyoneBitHit || isEveryoneMention(digestRaw, contentRaw)) state.everyoneMentioned = true
                    }
                    state.atMeCount += atCountRow

                    val time = cursor.getLongOrZero(ConversationTable.CONVERSATION_TIME)
                    if (state.latest == null || time > state.latest!!.conversationTime) {
                        val nickname = cursor.getStringOrEmpty(ContactTable.NICKNAME)
                        val remark = cursor.getStringOrEmpty(ContactTable.CON_REMARK)
                        val displayName = if (username.endsWith("@chatroom")) nickname else remark.ifBlank { nickname }
                        state.latest = MemberSummaryRow(
                            digest = prefixWithConversationName(
                                displayName.takeIf { it.isNotBlank() && it != username },
                                stripWxidPrefix(digestRaw),
                                username.endsWith("@chatroom"),
                                cursor.getStringOrEmpty(ConversationTable.DIGEST_USER).ifBlank { null },
                                username
                            ),
                            digestUser = cursor.getStringOrEmpty(ConversationTable.DIGEST_USER),
                            isSend = cursor.getIntOrZero(ConversationTable.IS_SEND),
                            status = cursor.getIntOrZero(ConversationTable.STATUS),
                            conversationTime = time,
                            content = contentRaw,
                            msgType = cursor.getStringOrEmpty(ConversationTable.MSG_TYPE),
                            chatMode = cursor.getIntOrZero(ConversationTable.CHAT_MODE)
                        )
                    }
                }
            }
        }

        return states.mapValues { (folderId, state) ->
            val latest = state.latest
            if (latest == null) {
                FolderSummary(
                    conversationTime = storedRows[folderId]?.summary?.conversationTime
                        ?: System.currentTimeMillis()
                )
            } else {
                // 任一未读成员行命中 @所有人 即显示 [@全体]（不只看最新一条）；
                // 但该标记应只对「有未读」的文件夹生效（已读后恢复普通摘要）。
                val everyoneHit = state.everyoneMentioned
                WeLogger.i(
                    TAG,
                    "folderSummary diag folderId=$folderId atMeCount=${state.atMeCount} " +
                        "unreadChatCount=${state.unreadChatCount} normal=${state.normalUnread} muted=${state.mutedUnread} everyoneHit=$everyoneHit " +
                        "digest=[${latest.digest}] content=[${latest.content.take(80)}]"
                )
                FolderSummary(
                    digest = (
                        if (everyoneHit && (state.atMeCount > 0 || state.unreadChatCount > 0)) "[@全体]"
                        else if (state.atMeCount > 0) "[有人@我]"
                        else ""
                    ) + (
                        if (state.unreadChatCount > 0)
                            "[${state.unreadChatCount}个聊天]" else ""
                    ) + (
                        if (latest.isSend == 1) "[自己]" else ""
                    ) + latest.digest,
                    digestUser = latest.digestUser,
                    isSend = latest.isSend,
                    status = latest.status,
                    conversationTime = latest.conversationTime.takeIf { it > 0L }
                        ?: storedRows[folderId]?.summary?.conversationTime
                        ?: System.currentTimeMillis(),
                    // 8.0.78: WeChat renders the folder badge from unReadCount alone and gives
                    // unReadMuteCount a separate muted style - folding all unread chats (incl.
                    // muted) into the count matches 8.0.77 (badge = unread chat count).
                    // User expectation: home badge = normal (non-muted) unread messages only.
                    unreadCount = state.normalUnread,
                    unreadMuteCount = 0,
                    content = latest.content,
                    msgType = latest.msgType,
                    chatMode = latest.chatMode
                )
            }
        }
    }

    /** 判断最新摘要/消息是否为「@所有人」群发提及，用于把 [有人@我] 换成 [全体]。
     *  微信摘要的 @所有人 形式多样（"@所有人"、"所有人:"、"全体成员" 等），
     *  因此按「所有人/全体」关键词匹配而非要求带 @ 符号。 */
    private fun isEveryoneMention(digest: String, content: String): Boolean =
        containsEveryone(digest) || containsEveryone(content)

    private fun containsEveryone(s: String): Boolean {
        val t = s.lowercase()
        return t.contains("所有人") || t.contains("全体") || t.contains("全部人") ||
            t.contains("全员") || t.contains("@all") ||
            t.contains("@everyone") || t.contains("all members")
    }
    /**
     * Prefixes the folder digest with the originating conversation's display name, so the
     * homepage folder row reads like "群聊名: 最新一条消息" instead of a bare message whose
     * source is ambiguous once several chats are aggregated. Returns the digest untouched
     * when it is blank or the name can't be resolved, to avoid a dangling "name: " prefix.
     */
    private val SENDER_PREFIX_REGEX = Regex("^(?:\uFF08([^\uFF09]+)\uFF09\uFF1A|([^:\uFF1A]+)[:\uFF1A])")

    private val WXID_PREFIX_REGEX = Regex("^wxid_[A-Za-z0-9_]+:\\s*")

    private fun stripWxidPrefix(digest: String): String {
        if (digest.isBlank()) return digest
        val m = WXID_PREFIX_REGEX.find(digest) ?: return digest
        return digest.substring(m.value.length)
    }

    private fun chineseNumber(n: Int): String = when (n) {
        in 1..9 -> arrayOf("\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u4e03", "\u516b", "\u4e5d")[n - 1]
        in 10..99 -> {
            val tens = arrayOf("", "\u5341", "\u4e8c\u5341", "\u4e09\u5341", "\u56db\u5341", "\u4e94\u5341", "\u516d\u5341", "\u4e03\u5341", "\u516b\u5341", "\u4e5d\u5341")[n / 10]
            val ones = n % 10
            if (ones == 0) tens else "$tens${arrayOf("\u4e00", "\u4e8c", "\u4e09", "\u56db", "\u4e94", "\u516d", "\u4e03", "\u516b", "\u4e5d")[ones - 1]}"
        }
        else -> n.toString()
    }

    private fun prefixWithConversationName(
        displayName: String?,
        digest: String,
        isChatroom: Boolean,
        senderWxid: String? = null,
        groupId: String? = null
    ): String {
        if (digest.isBlank() || displayName.isNullOrBlank()) return digest
        val name = displayName.take(MAX_DIGEST_NAME_LEN) +
            if (displayName.length > MAX_DIGEST_NAME_LEN) "\u2026" else ""
        if (isChatroom) {
            val m = SENDER_PREFIX_REGEX.find(digest)
            if (m != null) {
                val sender = m.groupValues[1].ifEmpty { m.groupValues[2] }
                val rawSender = resolveSenderDisplayName(sender, senderWxid, groupId)
                val senderName = rawSender.take(MAX_SENDER_NAME_LEN) +
                    if (rawSender.length > MAX_SENDER_NAME_LEN) "\u2026" else ""
                // 8.0.78 微信把发送者与正文用换行分隔(DIGEST=「wxid:\\\\n内容」)——单行摘要需并入空格，否则正文被截
                val rest = digest.substring(m.value.length).replace("\\r?\\n", " ").trimStart()
                // 发送者名无法解析（无备注、无群名片）时不显示空括号
                return if (senderName.isBlank()) "$name: $rest" else "$name($senderName):$rest"
            }
        }
        return "$name: ${digest.replace("\\r?\\n", " ")}"
    }

    private val senderNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Resolve the digest's raw sender handle to the display name shown in the folder digest.
     *  与微信群聊一致：备注 → 群名片（该群内，chatroom.roomdata displayName）→ 微信昵称。
     *  不直接显示微信号/用户 ID（wxid_、带 @ 的账号标识）。
     *  [senderWxid] 来自微信 digestUser（发送者账号，最精确）；[groupId] 为所在群（@chatroom），
     *  用于取群名片（如无群名片时微信摘要可能显示昵称，但群名片才是用户在该群的名字）。 */
    private fun resolveSenderDisplayName(sender: String, senderWxid: String? = null, groupId: String? = null): String {
        if (sender.isBlank() && senderWxid.isNullOrBlank()) return ""
        val cacheKey = "$senderWxid|$sender|$groupId"
        senderNameCache[cacheKey]?.let { return it }
        val final = runCatching {
            val wxid = senderWxid?.takeIf { it.isNotBlank() } ?: sender
            val isAccount = wxid.startsWith("wxid_") || wxid.contains("@")
            // 1) 备注（个人备注优先，与微信一致）
            val remark = queryContactField(wxid, ContactTable.CON_REMARK)
            if (remark.isNotBlank()) return@runCatching remark
            // 2) 群名片：发送者是该群成员时的群内显示名（优先于昵称）
            if (groupId != null && wxid.startsWith("wxid_")) {
                val card = WeDatabaseApi.getGroupMemberDisplayName(groupId, wxid)
                if (card.isNotBlank()) return@runCatching card
            }
            // 3) 微信昵称
            val nickname = queryContactField(wxid, ContactTable.NICKNAME)
            if (nickname.isNotBlank()) return@runCatching nickname
            // 4) 账号标识：查不到名字则不显示（避免暴露用户 ID）
            if (isAccount) return@runCatching ""
            // 5) sender 是显示名文本（微信摘要里的群名片/昵称）——反查昵称得账号后取群名片
            val nicknameWxid = runCatching {
                WeDatabaseApi.rawQuery(
                    "SELECT ${ContactTable.USERNAME} FROM ${ContactTable.NAME} WHERE ${ContactTable.NICKNAME}=? LIMIT 1",
                    arrayOf(sender)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (nicknameWxid != null && groupId != null) {
                val card = WeDatabaseApi.getGroupMemberDisplayName(groupId, nicknameWxid)
                if (card.isNotBlank()) return@runCatching card
            }
            sender
        }.getOrDefault("")
        senderNameCache[cacheKey] = final
        return final
    }

    /** 查询联系人单字段（备注或昵称），无值返回空串。 */
    private fun queryContactField(username: String, column: String): String = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT $column FROM ${ContactTable.NAME} WHERE ${ContactTable.USERNAME}=?",
            arrayOf(username)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrEmpty(column) else ""
        }
    }.getOrDefault("")

    private fun isFolderSchemaReady(): Boolean {
        folderSchemaReady?.let { return it }
        val result = runCatching {
            val conversationColumns = tableColumns(ConversationTable.NAME)
            WeLogger.i(TAG, "rconversation columns: $conversationColumns")
            runCatching {
                WeDatabaseApi.rawQuery("SELECT name FROM sqlite_master WHERE type='table'").use { tc ->
                    val tns = mutableListOf<String>()
                    while (tc.moveToNext()) tns += tc.getString(0)
                    WeLogger.i(TAG, "sqlite tables: ${tns.joinToString()}")
                }
            }.onFailure { WeLogger.w(TAG, "list tables failed", it) }
            val contactColumns = tableColumns(ContactTable.NAME)
            val missingConversationColumns = ConversationTable.REQUIRED_COLUMNS - conversationColumns
            val missingContactColumns = ContactTable.REQUIRED_COLUMNS - contactColumns
            if (missingConversationColumns.isNotEmpty() || missingContactColumns.isNotEmpty()) {
                WeLogger.w(
                    TAG,
                    "skip folders sync, schema mismatch: " +
                            "rconversation missing=${missingConversationColumns.joinToString()}, " +
                            "rcontact missing=${missingContactColumns.joinToString()}"
                )
                false
            } else {
                true
            }
        }.onFailure {
            WeLogger.w(TAG, "skip folders sync, failed to inspect WeChat database schema", it)
        }.getOrNull()
        // Only latch the outcome when the check actually completed. A transient failure (the
        // database being briefly locked or closing right after WeDatabaseApi.isReady flips)
        // must not permanently disable folder sync for the rest of the process — leave the
        // cached value unset so the next call retries.
        if (result != null) {
            folderSchemaReady = result
        }
        return result == true
    }

    private fun tableColumns(table: String): Set<String> {
        val columns = linkedSetOf<String>()
        val cursor = WeDatabaseApi.rawQuery("PRAGMA table_info($table)")
        cursor.use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }
        return columns
    }

    private fun rewriteContainerSql(sql: String, folderId: String): String {
        if (!sql.contains(ConversationTable.NAME, ignoreCase = true) ||
            !sql.contains(ConversationTable.PARENT_REF, ignoreCase = true)
        ) {
            return sql
        }
        if (!sql.contains(WeChatFolderPlaceholder.CONVERSATION_BOX) && !sql.contains(WeChatFolderPlaceholder.MESSAGE_FOLD)) {
            return sql
        }
        return sql
            .replace(WeChatFolderPlaceholder.CONVERSATION_BOX, folderId)
            .replace(WeChatFolderPlaceholder.MESSAGE_FOLD, folderId)
    }

    private fun readFolderIdFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return WeChatIntentExtra.ALL
            .asSequence()
            .mapNotNull { intent.getStringExtra(it) }
            .firstOrNull(::isFolderId)
    }

    private inline fun <T> withQueryRewriteSuppressed(action: () -> T): T {
        val oldValue = suppressQueryRewrite.get()
        suppressQueryRewrite.set(true)
        return try {
            action()
        } finally {
            suppressQueryRewrite.set(oldValue)
        }
    }

    private fun showManagerDialog(context: Context) {
        showComposeDialog(context) {
            var folders by remember { mutableStateOf(loadFolders()) }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("对话归拢") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (folders.isEmpty()) {
                                item {
                                    Text("暂无文件夹, 点击「新建」来创建一个")
                                }
                            }
                            itemsIndexed(folders, key = { _, f -> f.id }) { index, folder ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FolderRow(
                                        folder,
                                        onClick = {
                                            showEditFolderDialog(
                                                context = context,
                                                folder = folder,
                                                onFolderUpdated = { folders = loadFolders() },
                                                onFolderDeleted = { folders = loadFolders() }
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        enabled = index > 0,
                                        onClick = {
                                            val list = folders.toMutableList()
                                            list.add(index - 1, list.removeAt(index))
                                            folders = list
                                        }
                                    ) { Text("↑") }
                                    TextButton(
                                        enabled = index < folders.size - 1,
                                        onClick = {
                                            val list = folders.toMutableList()
                                            list.add(index + 1, list.removeAt(index))
                                            folders = list
                                        }
                                    ) { Text("↓") }
                                }
                            }
                        }
                        // 摘要颜色：文件夹列表下方的独立标题，展开/收起
                        var colorsExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { colorsExpanded = !colorsExpanded }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "摘要颜色",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (colorsExpanded) "收起" else "展开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (colorsExpanded) {
                            ConversationAggregationColors.ColorSettingsExpandedContent()
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                    TextButton(onClick = {
                        syncFoldersToDatabase()
                        showToast("已重建文件夹索引")
                    }) { Text("重载") }
                    TextButton(onClick = {
                        showCreateFolderDialog(context) {
                            folders = loadFolders()
                        }
                    }) { Text("新建") }
                },
                confirmButton = {
                    Button(onClick = {
                        saveFolders(folders)
                        syncFoldersToDatabase()
                        runCatching { WeConversationApi.reloadConversations() }
                        showToast(context, "已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    private fun showCreateFolderDialog(context: Context, onFolderCreated: () -> Unit) {
        showComposeDialog(context) {
            FolderEditorDialog(
                title = "新建文件夹",
                folder = null,
                onDismiss = onDismiss,
                onSave = { folder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders + folder)
                    onFolderCreated()
                    onDismiss()
                }
            )
        }
    }

    private fun showEditFolderDialog(
        context: Context,
        folder: ChatFolder,
        onFolderUpdated: () -> Unit,
        onFolderDeleted: () -> Unit
    ) {
        showComposeDialog(context) {
            val editorDismiss = this.onDismiss
            FolderEditorDialog(
                title = "编辑文件夹",
                folder = folder,
                onDismiss = editorDismiss,
                onDelete = {
                    // 删除前确认，防止误删整个文件夹
                    showComposeDialog(context) {
                        val confirmDismiss = this.onDismiss
                        AlertDialogContent(
                            title = { Text("删除文件夹") },
                            text = { Text("确定删除「${folder.name}」吗？删除后该文件夹不再归拢其中的对话。") },
                            dismissButton = { TextButton(confirmDismiss) { Text("取消") } },
                            confirmButton = {
                                Button(onClick = {
                                    val currentFolders = loadFolders()
                                    saveFolders(currentFolders.filterNot { it.id == folder.id })
                                    onFolderDeleted()
                                    confirmDismiss()
                                    editorDismiss()
                                }) { Text("删除") }
                            }
                        )
                    }
                },
                onSave = { updatedFolder ->
                    val currentFolders = loadFolders()
                    saveFolders(currentFolders.map { if (it.id == updatedFolder.id) updatedFolder else it })
                    onFolderUpdated()
                    editorDismiss()
                }
            )
        }
    }

    @Composable
    private fun FolderRow(
        folder: ChatFolder,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val count = remember(folder) { getFolderMembers(folder).size }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text(folder.name)
            val desc = when (folder.type) {
                FolderType.MANUAL -> "手动选择: $count 个对话"
                FolderType.PRESET_GROUPS -> "所有群聊: $count 个对话"
                FolderType.PRESET_OFFICIALS -> "所有公众号: $count 个对话"
                FolderType.SQL -> "SQL规则: $count 个对话"
            }
            Text(desc)
        }
    }

    @Composable
    private fun FolderEditorDialog(
        title: String,
        folder: ChatFolder?,
        onDismiss: () -> Unit,
        onDelete: (() -> Unit)? = null,
        onSave: (ChatFolder) -> Unit
    ) {
        val folderId = remember(folder) { folder?.id ?: newFolderId() }
        var name by remember(folder) { mutableStateOf(folder?.name ?: "") }
        var members by remember(folder) { mutableStateOf(folder?.members?.toSet().orEmpty()) }

        var type by remember(folder) { mutableStateOf(folder?.type ?: FolderType.MANUAL) }
        var selectFields by remember(folder) { mutableStateOf(folder?.selectFields ?: "r.username") }
        var whereClause by remember(folder) { mutableStateOf(folder?.whereClause ?: "") }

        val matchedCount = remember(type, members, selectFields, whereClause) {
            val tempFolder = ChatFolder(
                id = folderId,
                name = name,
                members = members.toList(),
                type = type,
                selectFields = selectFields,
                whereClause = whereClause
            )
            // Resolve directly instead of going through getFolderMembers: that cache is keyed
            // by folder id, and this preview folder reuses the id of the folder being edited,
            // so the cached (stale) member list would freeze the count at the first result.
            resolveFolderMembers(tempFolder).size
        }

        var hasAvatar by remember(folderId) {
            mutableStateOf(CustomLocalFriendAvatars.avatarMap.containsKey(folderId))
        }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            title = { Text(title) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文件夹名称") },
                        singleLine = true
                    )

                    var typeExpanded by remember { mutableStateOf(false) }
                    Column {
                        Text("归拢模式", style = MaterialTheme.typography.labelSmall)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { typeExpanded = true }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = when (type) {
                                    FolderType.MANUAL -> "手动选择"
                                    FolderType.PRESET_GROUPS -> "自动所有群聊"
                                    FolderType.PRESET_OFFICIALS -> "自动所有公众号"
                                    FolderType.SQL -> "自定义 SQL 规则"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("手动选择") },
                                onClick = {
                                    type = FolderType.MANUAL
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有群聊") },
                                onClick = {
                                    type = FolderType.PRESET_GROUPS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自动所有公众号") },
                                onClick = {
                                    type = FolderType.PRESET_OFFICIALS
                                    typeExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("自定义 SQL 规则") },
                                onClick = {
                                    type = FolderType.SQL
                                    typeExpanded = false
                                }
                            )
                        }
                    }

                    when (type) {
                        FolderType.MANUAL -> {
                            Text("已选择 $matchedCount 个对话")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val context = LocalContext.current
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        showComposeDialog(context) {
                                            ContactsSelector(
                                                title = "选择对话",
                                                contacts = remember { WeDatabaseApi.getContacts() },
                                                initialSelectedWxIds = members,
                                                onDismiss = this.onDismiss,
                                                onConfirm = {
                                                    members = it
                                                    this.onDismiss()
                                                }
                                            )
                                        }
                                    }
                                ) {
                                    Text("选择对话")
                                }

                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(onClick = {
                                    if (!CustomLocalFriendAvatars.isEnabled) {
                                        showToast("请启用「自定义好友本地头像」以使用头像相关功能!")
                                    }

                                    CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                }) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                            val context = LocalContext.current
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (members.isEmpty()) {
                                        showToast("文件夹暂无成员可移出")
                                        return@Button
                                    }
                                    val others = loadFolders().filter { it.id != folderId && it.type == FolderType.MANUAL }
                                    if (others.isEmpty()) {
                                        showToast("没有其他手动文件夹可移出")
                                        return@Button
                                    }
                                    showComposeDialog(context) {
                                        val dismiss = this.onDismiss
                                        ContactsSelector(
                                            title = "选择要移出的对话",
                                            contacts = remember { WeDatabaseApi.getContacts().filter { it.wxId in members } },

                                            initialSelectedWxIds = emptySet(),
                                            onDismiss = dismiss,
                                            onConfirm = { toMove ->
                                                dismiss()
                                                showComposeDialog(context) {
                                                    val innerDismiss = this.onDismiss
                                                    AlertDialogContent(
                                                        title = { Text("移出到其他文件夹") },
                                                        text = {
                                                            LazyColumn {
                                                                items(others) { target ->
                                                                    Text(
                                                                        target.name,
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .clickable {
                                                                                val current = loadFolders()
                                                                                val curList = current.map { if (it.id == folderId) it.copy(members = it.members - toMove) else it }
                                                                                val finalList = curList.map { if (it.id == target.id) it.copy(members = (it.members + toMove).distinct().sorted()) else it }
                                                                                saveFolders(finalList)
                                                                                members = members - toMove
                                                                                innerDismiss()
                                                                                dismiss()
                                                                                onSave(folder!!.copy(members = (members - toMove).sorted()))
                                                                            }
                                                                            .padding(12.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text("移出到其他文件夹")
                            }
                        }

                        FolderType.PRESET_GROUPS -> {
                            Text("自动归拢所有群聊（当前匹配到 $matchedCount 个对话）")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }

                        FolderType.PRESET_OFFICIALS -> {
                            Text("自动归拢所有公众号（当前匹配到 $matchedCount 个对话）")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }

                        FolderType.SQL -> {
                            OutlinedTextField(
                                value = selectFields,
                                onValueChange = { selectFields = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("SELECT 字段") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = whereClause,
                                onValueChange = { whereClause = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("WHERE 条件") },
                                singleLine = false,
                                maxLines = 4
                            )
                            Text(
                                text = "当前匹配到 $matchedCount 个对话",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "数据源自 rcontact r, img_flag i, rconversation c\n示例: c.unReadCount > 0 AND r.username LIKE '%@chatroom'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hasAvatar) {
                                    Button(onClick = {
                                        CustomLocalFriendAvatars.removeAvatar(folderId)
                                        hasAvatar = false
                                    }) {
                                        Text("清除头像")
                                    }
                                }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        CustomLocalFriendAvatars.selectAvatarImage(HostInfo.application, folderId)
                                    }
                                ) {
                                    Text(if (hasAvatar) "更换头像" else "设置头像")
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                if (onDelete != null) {
                    TextButton(onDelete) { Text("删除") }
                }
                TextButton(onDismiss) { Text("取消") }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val next = ChatFolder(
                            id = folderId,
                            name = name.trim(),
                            members = members.toList().sorted(),
                            type = type,
                            selectFields = selectFields.trim(),
                            whereClause = whereClause.trim(),
                            // Carry the pin state forward — editing a folder must not reset its pin.
                            pinFlag = folder?.pinFlag ?: 0L
                        )
                        onSave(next)
                        showToast("已保存")
                    }
                ) { Text("确定") }
            }
        )
    }

    private fun resolveFolderMembers(folder: ChatFolder): List<String> {
        return when (folder.type) {
            FolderType.MANUAL -> folder.members
            FolderType.PRESET_GROUPS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE '%@chatroom'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset groups", it)
                    emptyList()
                }
            }

            FolderType.PRESET_OFFICIALS -> {
                runCatching {
                    val result = WeDatabaseApi.executeQuery(
                        "SELECT r.username FROM rcontact r WHERE r.username LIKE 'gh_%'"
                    )
                    result.mapNotNull { it["username"]?.toString() }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query preset officials", it)
                    emptyList()
                }
            }

            FolderType.SQL -> {
                runCatching {
                    val select = folder.selectFields.ifBlank { "r.username" }
                    val where = folder.whereClause.ifBlank { "1=1" }
                    val query =
                        "SELECT $select FROM rcontact r LEFT JOIN img_flag i ON r.username = i.username LEFT JOIN rconversation c ON r.username = c.username WHERE $where"
                    val result = WeDatabaseApi.executeQuery(query)
                    result.mapNotNull { row ->
                        val username = row["username"]?.toString()
                        if (username != null) return@mapNotNull username
                        row.values.firstOrNull()?.toString()
                    }
                }.getOrElse {
                    WeLogger.e(TAG, "failed to query custom sql for folder ${folder.id}", it)
                    emptyList()
                }
            }
        }
    }

    private fun getFolderMembers(folder: ChatFolder): List<String> {
        if (folder.type == FolderType.MANUAL) {
            return folder.members
        }
        val cached = folderMembersCache[folder.id]
        if (cached != null) return cached

        if (!WeDatabaseApi.isReady) {
            return emptyList()
        }
        val resolved = resolveFolderMembers(folder)
        if (resolved.isNotEmpty()) {
            folderMembersCache[folder.id] = resolved
        }
        return resolved
    }

    private fun getFallbackAvatarMember(folderId: String): String? {
        val folder = folderById(folderId) ?: return null
        val members = getFolderMembers(folder).filterNot(::isFolderId).distinct()
        if (members.isEmpty()) return null
        // Prefer the member whose conversation most recently saw activity: WeChat bumps
        // rconversation.conversationTime on every sent or received message, so the folder
        // borrows the avatar of the chat that last lit up rather than an arbitrary first
        // member. Falls back to the first member when none of them has any message yet.
        return latestActiveMember(members) ?: members.firstOrNull()
    }

    /** Member with the newest conversationTime (latest sent/received message), or null. */
    private fun latestActiveMember(members: List<String>): String? {
        if (members.isEmpty() || !WeDatabaseApi.isReady) return null
        return runCatching {
            // Suppress the container SQL fallback while querying aggregate members directly.
            withQueryRewriteSuppressed {
                val placeholders = members.joinToString(",") { "?" }
                val cursor = WeDatabaseApi.rawQuery(
                    """
                    SELECT ${ConversationTable.USERNAME}
                    FROM ${ConversationTable.NAME}
                    WHERE ${ConversationTable.USERNAME} IN ($placeholders) AND ${ConversationTable.CONVERSATION_TIME} > 0
                    ORDER BY ${ConversationTable.CONVERSATION_TIME} DESC
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(*members.toTypedArray())
                )
                cursor.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to resolve latest active member", it)
        }.getOrNull()
    }

    private fun loadFolders(): List<ChatFolder> {
        val wxid = currentAccountWxid()
        if (foldersCache != null && foldersCacheWxid == wxid) return foldersCache!!
        val file = foldersFileFor(wxid)
        val folders = runCatching {
            if (file.exists()) {
                decodeFoldersFrom(file)
            } else if (!wxid.isNullOrBlank() && legacyFoldersFile.exists()) {
                // 账号首次使用：继承旧的共享配置（一次性迁移），之后各账号独立
                decodeFoldersFrom(legacyFoldersFile)
            } else {
                emptyList()
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode folders config from $file", it)
        }.getOrDefault(emptyList())
        // 账号文件不存在但继承到了旧配置 -> 落盘到账号文件（一次性迁移）
        if (folders.isNotEmpty() && !file.exists()) {
            saveFoldersTo(file, folders)
        }
        foldersCache = folders
        foldersCacheWxid = wxid
        return folders
    }

    private fun decodeFoldersFrom(file: Path): List<ChatFolder> {
        if (!file.exists()) return emptyList()
        return DefaultJson.decodeFromString<List<ChatFolder>>(file.readText())
            .map { folder ->
                folder.copy(members = folder.members.filter { it.isNotBlank() })
            }
            .filter { isFolderId(it.id) && it.name.isNotBlank() }
    }

    private fun saveFoldersTo(file: Path, folders: List<ChatFolder>) {
        runCatching {
            val raw = DefaultJson.encodeToString(folders)
            file.writeText(raw)
        }.onFailure {
            WeLogger.w(TAG, "failed to save folders to $file", it)
        }
    }


    private fun saveFolders(folders: List<ChatFolder>) {
        foldersCache = folders
        foldersCacheWxid = currentAccountWxid()
        folderMembersCache.clear()
        saveFoldersTo(foldersFileFor(foldersCacheWxid), folders)
    }


    private fun folderById(folderId: String): ChatFolder? {
        return loadFolders().firstOrNull { it.id == folderId }
    }

    private fun newFolderId(): String = "$FOLDER_PREFIX${System.currentTimeMillis()}"

    private fun isFolderId(value: String): Boolean = value.startsWith(FOLDER_PREFIX)
    /**
     * Marks every member conversation of [folderId] as read. WeChat's own
     * updateUnreadByTalker(folderId) is a no-op for folder rows (see
     * methodConversationStorageUpdateUnreadByTalker) because the folder container also fires it
     * on leave; the home-list "标为已读" long-press menu therefore did nothing. Here we clear the
     * real member rows, then reconcile so the aggregate badge drops.
     */
    fun markFolderAsRead(folderId: String) {
        val folder = folderById(folderId) ?: return
        // 排除嵌套文件夹行（仅标记真实成员会话），空成员时提前返回避免无效 sync
        val members = resolveFolderMembers(folder).filterNot(::isFolderId)
        if (members.isEmpty()) return
        var failed = 0
        members.forEach { member ->
            runCatching { WeConversationApi.markAsRead(member) }
                .onFailure {
                    failed++
                    WeLogger.w(TAG, "markAsRead failed for $member", it)
                }
        }
        WeLogger.i(TAG, "markFolderAsRead: ${members.size} members${if (failed > 0) ", $failed failed" else ""}")
        syncFoldersToDatabase()
        showToast("已标为已读")
    }



    enum class FolderType {
        MANUAL,
        PRESET_GROUPS,
        PRESET_OFFICIALS,
        SQL
    }

    @Serializable
    private data class ChatFolder(
        val id: String = "",
        val name: String = "",
        val members: List<String> = emptyList(),
        val type: FolderType = FolderType.MANUAL,
        val selectFields: String = "",
        val whereClause: String = "",
        // High 8 bits (pin / move-up state, owned by WeChat's setPlacedTop / unSetPlacedTop) of this
        // folder's rconversation row, mirrored here so it survives onDisable deleting the row. Kept
        // in sync from the live row before a folder row is removed.
        val pinFlag: Long = 0L
    )

    private data class StoredFolderRow(
        val flag: Long,
        val attrFlag: Int,
        val summary: FolderSummary
    )

    private data class MemberSummaryRow(
        val digest: String,
        val digestUser: String,
        val isSend: Int,
        val status: Int,
        val conversationTime: Long,
        val content: String,
        val msgType: String,
        val chatMode: Int
    )

    private class SummaryAccumulator {
        var latest: MemberSummaryRow? = null
        var normalUnread: Int = 0
        var mutedUnread: Int = 0
        var unreadChatCount: Int = 0
        var atMeCount: Int = 0
        // 任一未读成员行命中 @所有人（摘要/内容含 所有人/全体/全员 等）即置位，
        // 用于显示 [@全体] 而非 [有人@我]（聚合判断，不只看最新一条摘要）。
        var everyoneMentioned: Boolean = false
    }

    private data class FolderSummary(
        val digest: String = "",
        val digestUser: String = "",
        val isSend: Int = 0,
        val status: Int = 0,
        val conversationTime: Long = System.currentTimeMillis(),
        val unreadCount: Int = 0,
        val unreadMuteCount: Int = 0,
        val atMeCount: Int = 0,
        val content: String = "",
        val msgType: String = "",
        val chatMode: Int = 0
    ) {
        /**
         * The folder row needs a mute attrflag bit set for the homepage badge to render a
         * small dot (WeChat w3.b requires unReadCount==0 && unReadMuteCount>0 && attrflag has
         * a mute bit). We add the bit only when there's muted-but-no-normal unread, and clear
         * it otherwise so a stale dot never lingers.
         */
        val attrFlag: Int
            get() = if (unreadCount == 0 && unreadMuteCount > 0) ATTR_FLAG_MUTE_BIT else 0
    }

    private object ConversationTable {
        const val NAME = "rconversation"
        const val USERNAME = "username"
        const val PARENT_REF = "parentRef"
        const val DIGEST = "digest"
        const val DIGEST_USER = "digestUser"
        const val IS_SEND = "isSend"
        const val STATUS = "status"
        const val CONVERSATION_TIME = "conversationTime"
        const val FLAG = "flag"
        const val UNREAD_COUNT = "unReadCount"
        const val UNREAD_MUTE_COUNT = "unReadMuteCount"
        const val CONTENT = "content"
        const val MSG_TYPE = "msgType"
        const val CHAT_MODE = "chatmode"
        const val ATTR_FLAG = "attrflag"
        const val AT_COUNT = "atCount"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            PARENT_REF,
            DIGEST,
            DIGEST_USER,
            IS_SEND,
            STATUS,
            CONVERSATION_TIME,
            FLAG,
            UNREAD_COUNT,
            UNREAD_MUTE_COUNT,
            CONTENT,
            MSG_TYPE,
            CHAT_MODE,
            ATTR_FLAG
        )
    }

    private object ContactTable {
        const val NAME = "rcontact"
        const val USERNAME = "username"
        const val NICKNAME = "nickname"
        const val CON_REMARK = "conRemark"
        const val LV_BUFF = "lvbuff"
        const val TYPE = "type"
        const val VERIFY_FLAG = "verifyFlag"

        val REQUIRED_COLUMNS = setOf(
            USERNAME,
            NICKNAME,
            TYPE,
            CON_REMARK,
            LV_BUFF,
            VERIFY_FLAG
        )
    }

    private object WeChatIntentExtra {
        const val CONTACT_USER = "Contact_User"
        const val CONTACT_CHAT_ROOM_ID = "Contact_ChatRoomId"
        const val ROOM_NAME = "room_name"
        const val CHAT_USER = "Chat_User"

        val ALL = listOf(
            CONTACT_USER,
            CONTACT_CHAT_ROOM_ID,
            ROOM_NAME,
            CHAT_USER
        )
    }

    private object WeChatFolderPlaceholder {
        const val CONVERSATION_BOX = "conversationboxservice"
        const val MESSAGE_FOLD = "message_fold"
    }


    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) ?: "" else ""
    }

    private fun android.database.Cursor.getIntOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun android.database.Cursor.getLongOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

}
