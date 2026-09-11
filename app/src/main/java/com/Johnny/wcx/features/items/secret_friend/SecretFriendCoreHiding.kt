package com.Johnny.wcx.features.items.secret_friend

import android.app.Activity
import android.content.ContentValues
import android.util.Base64
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.tencent.mm.ui.chatting.ChattingUI
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeStartActivityApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.contacts.hidecontacts.injectCondition
import com.Johnny.wcx.features.items.contacts.hidecontacts.rewriteWrapperSql
import com.Johnny.wcx.features.items.contacts.hidecontacts.toSqlList
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import dev.ujhhgtg.reflekt.reflekt
import com.tencent.wcdb.database.SQLiteDatabase
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Modifier as JavaModifier

/**
 * 核心隐藏 1–5：主页会话 / 通讯录 / 标签内 / 禁止进入聊天 / 禁止查看资料。
 *
 * 各开关过滤前先查 [SecretFriendState.isTemporarilyShown]，临时显示态整段放行。
 */

// ─────────────────────────── 1. 主页会话隐藏 ───────────────────────────

/**
 * 主页会话隐藏（MaskWechat「HideMainUIList」语义 → 备份版 HideSecretFriendConversations 实现）。
 *
 * 双入口：
 * - IQueryListener：主页会话列表、未读数等 wrapper 查询复用「隐藏联系人」已验证的
 *   [rewriteWrapperSql] 规则（行保留在数据库、聊天记录不丢，关闭开关或移出名单立即恢复）；
 * - IInsertListener：密友的 rconversation 行新插入（新消息到达）时立即删除该行并刷新列表，
 *   兜住绕过 wrapper 规则形状的查询。行删除走微信原生「不显示该聊天」语义（delChatContact），
 *   聊天记录不受影响。
 */
@Feature(
    name = "主页会话隐藏",
    categories = ["密友功能"],
    description = "把密友的会话从主页聊天列表隐藏：名单内成员发来新消息时也保持隐藏；关闭开关后会话立即恢复（不删聊天记录）"
)
object HideConversations : SwitchFeature(), IResolveDex,
    WeDatabaseListenerApi.IQueryListener,
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "HideConversations"

    /**
     * 主页会话列表游标由微信自己的 SQLite wrapper 构建（HideContacts.methodSqliteWrapperRawQuery
     * 同一 chokepoint），**不走** wcdb rawQuery（WeDatabaseListenerApi 挂不到）——
     * 只依赖 IQueryListener 时本功能完全无效，必须直接挂 wrapper。
     */
    private val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        if (methodSqliteWrapperRawQuery.isPlaceholder) {
            WeLogger.w(TAG, "SQLite wrapper query method not resolved; conversation hiding disabled")
        } else {
            methodSqliteWrapperRawQuery.hookBefore {
                // 钩子是进程级常驻的，必须随开关状态失效，否则关闭功能后仍在过滤
                if (!_isEnabled) return@hookBefore
                wrapperNativeDb = thisObject as? SQLiteDatabase
                if (SecretFriendState.isTemporarilyShown()) return@hookBefore
                val sql = args.firstOrNull() as? String ?: return@hookBefore
                val rewritten = rewriteConversationSql(sql, SecretFriendState.getWxIds())
                    ?: rewriteWrapperSql(sql, SecretFriendState.getWxIds())
                    ?: return@hookBefore
                args[0] = rewritten
            }
        }

        // 再次开启时把已恢复显示的会话行重新隐藏（删行=已验证的隐藏机制）：
        // 关闭→开启期间 DB 无变化，适配器不会因开关翻转自己重建，必须主动删行+刷新。
        // 启动早期 storage 未就绪时失败无碍——首屏列表查询本身已被过滤。
        removeSecretRows()
        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
        // 关闭后立即恢复：重建此前为隐藏而删除的会话行并刷新列表
        restoreHiddenRows()
        WeConversationApi.reloadConversations()
    }

    /**
     * 密友会话专用改写：任何 rconversation 多行查询（列表/文件夹/未读数/聚合计数）一律注入
     * NOT IN。新消息到达后微信常用单行/局部查询刷新列表，仅靠 display-list 形状规则会漏；
     * 单行读取（username=/rowid=）不过滤——聊天页等内部读取依赖它。
     */
    private fun rewriteConversationSql(sql: String, secrets: Set<String>): String? {
        if (secrets.isEmpty()) return null
        val lower = sql.lowercase()
        if (!lower.contains("from rconversation")) return null
        if (lower.contains("username =") || lower.contains("username=") || lower.contains("rowid =")) return null
        if (lower.contains("rconversation.username not in")) return null
        return injectCondition(sql, "rconversation.username NOT IN (${secrets.toSqlList()})")
    }

    /** 主页会话列表、未读数等 wrapper 查询按密友名单过滤；其余 SQL 原样放行。 */
    override fun onQuery(sql: String): String? {
        if (SecretFriendState.isTemporarilyShown()) return null
        // 已被自己/同列规则注入过则跳过，避免重复包装
        if (sql.contains("rconversation.username NOT IN", ignoreCase = true)) return null
        return rewriteConversationSql(sql, SecretFriendState.getWxIds())
            ?: rewriteWrapperSql(sql, SecretFriendState.getWxIds())
    }

    /** 密友会话行写入处置：原生删除会话行（delChatContact 语义，不删聊天记录）并刷新列表。 */
    private fun onConversationRowWrite(username: String) {
        if (SecretFriendState.isTemporarilyShown()) return
        if (!SecretFriendState.isSecret(username)) return

        WeLogger.d(TAG, "secret conversation row written, removing: $username")
        runOnUiThread {
            WeConversationApi.hideConversation(username)
            WeConversationApi.reloadConversations()
        }
    }

    // ── 会话行快照与临时解除重建 ──
    // 删除会话行（delChatContact 语义）是 8.0.77 上唯一可靠的即时隐藏手段，但行删除后
    // 临时解除无行可显（要等密友再发消息）。删除前捕获行快照（INSERT 全量 / UPDATE
    // 增量合并），临时解除时用 wcdb insertWithOnConflict(REPLACE) 重建，到期统一再删。

    private val rowSnapshots = ConcurrentHashMap<String, ContentValues>()

    // 快照跨进程持久化：会话行被物理删除后，若进程重启，内存快照会丢，
    // 「关闭开关恢复」就无从恢复。删除/捕获时同步写 mmkv，恢复时先读回。

    private val SNAPSHOT_PREF_KEY = "secret_friend_row_snapshots_v2"

    private fun loadPersistedSnapshots() {
        rowSnapshots.clear()
        runCatching {
            val raw = WePrefs.getString(SNAPSHOT_PREF_KEY)
            if (raw.isNullOrBlank()) return
            val root = JSONObject(raw)
            val it = root.keys()
            while (it.hasNext()) {
                val user = it.next()
                val cols = root.optJSONObject(user) ?: continue
                rowSnapshots[user] = cvFromJson(cols)
            }
        }.onFailure { WeLogger.w(TAG, "load persisted snapshots failed", it) }
    }

    private fun persistSnapshots() {
        runCatching {
            val root = JSONObject()
            for ((user, cv) in rowSnapshots) root.put(user, cvToJson(cv))
            WePrefs.putString(SNAPSHOT_PREF_KEY, root.toString())
        }.onFailure { WeLogger.w(TAG, "persist snapshots failed", it) }
    }

    private fun cvToJson(cv: ContentValues): JSONObject {
        val cols = JSONObject()
        for (key in cv.keySet()) {
            val v = cv.get(key) ?: continue
            val entry = JSONObject()
            when (v) {
                is ByteArray -> {
                    entry.put("t", "b")
                    entry.put("v", Base64.encodeToString(v, Base64.NO_WRAP))
                }
                is Boolean -> { entry.put("t", "z"); entry.put("v", v) }
                is Double, is Float -> { entry.put("t", "d"); entry.put("v", (v as Number).toDouble()) }
                is Int, is Short, is Byte, is Long -> { entry.put("t", "l"); entry.put("v", (v as Number).toLong()) }
                is String -> { entry.put("t", "s"); entry.put("v", v) }
                else -> continue
            }
            cols.put(key, entry)
        }
        return cols
    }

    private fun cvFromJson(cols: JSONObject): ContentValues {
        val cv = ContentValues()
        val it = cols.keys()
        while (it.hasNext()) {
            val key = it.next()
            val entry = cols.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "s" -> cv.put(key, entry.optString("v"))
                "l" -> cv.put(key, entry.optLong("v"))
                "d" -> cv.put(key, entry.optDouble("v"))
                "z" -> cv.put(key, entry.optBoolean("v"))
                "b" -> runCatching {
                    cv.put(key, Base64.decode(entry.optString("v"), Base64.NO_WRAP))
                }.onFailure { WeLogger.w(TAG, "decode blob $key failed", it) }
            }
        }
        return cv
    }

    private fun putSnapshot(username: String, values: ContentValues) {
        rowSnapshots[username] = ContentValues(values)
        persistSnapshots()
    }

    private fun mergeSnapshot(username: String, values: ContentValues) {
        val snapshot = rowSnapshots[username]
        if (snapshot != null) snapshot.putAll(ContentValues(values))
        else rowSnapshots[username] = ContentValues(values)
        persistSnapshots()
    }

    // wrapper 钩子捕获到的原生 db 实例（主页列表查询极频繁，进程启动后即有值，
    // 用作行重建的 db 来源，不依赖「进程内发生过插入」）
    @Volatile private var wrapperNativeDb: SQLiteDatabase? = null

    private val insertWithOnConflictMethod by lazy {
        runCatching {
            SQLiteDatabase::class.reflekt()
                .firstMethodOrNull {
                    name = "insertWithOnConflict"
                    parameters(String::class, String::class, ContentValues::class, Int::class)
                }
        }.getOrNull()
    }

    /**
     * 临时解除/名单变动/关闭开关：用快照重建已删除的密友会话行。不做 isSecret 过滤——
     * 已移出名单的密友其会话行同样要回来（调用方随后按需 removeSecretRows 再隐藏）。
     * 返回重建条数。
     */
    fun restoreHiddenRows(): Int {
        if (rowSnapshots.isEmpty()) loadPersistedSnapshots()
        if (rowSnapshots.isEmpty()) {
            WeLogger.i(TAG, "restoreHiddenRows: no snapshots to restore")
            return 0
        }
        val db = WeDatabaseListenerApi.lastInsertDb ?: wrapperNativeDb
        if (db == null) {
            WeLogger.w(TAG, "no wcdb db instance available; cannot restore hidden rows")
            return 0
        }
        val insert = insertWithOnConflictMethod ?: run {
            WeLogger.w(TAG, "insertWithOnConflict not resolvable; cannot restore hidden rows")
            return 0
        }
        var restored = 0
        for ((username, values) in rowSnapshots) {
            runCatching {
                insert.invoke(db, "rconversation", null, values, 5)
                restored++
                WeLogger.d(TAG, "restored conversation row: $username")
            }.onFailure { WeLogger.w(TAG, "restore conversation row failed: $username", it) }
        }
        if (restored > 0) WeLogger.i(TAG, "restoreHiddenRows: $restored restored")
        return restored
    }

    /** 临时展示结束：删除所有密友会话行（已验证的隐藏机制），恢复隐藏。 */
    fun removeSecretRows() {
        var removed = 0
        for (wxId in SecretFriendState.getWxIds()) {
            if (WeConversationApi.hideConversation(wxId)) removed++
        }
        if (removed > 0) WeLogger.i(TAG, "temp-show ended, removed $removed secret conversation rows")
    }

    /**
     * 名单变更后的对账：先恢复全部快照行（含已移出名单的密友），
     * 再把仍在名单中的密友行重新隐藏（临时显示态除外），最后刷新列表。
     */
    fun reconcileOnListChange() {
        val restored = restoreHiddenRows()
        if (!SecretFriendState.isTemporarilyShown()) {
            removeSecretRows()
        }
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "reconcileOnListChange: restored=$restored")
    }

    /** 密友会话行新入库（新消息到达）即删 + 刷新，兜住 wrapper 规则之外的查询形状。 */
    override fun onInsert(table: String, values: ContentValues) {
        if (table != "rconversation") return
        val username = values.getAsString("username") ?: return
        putSnapshot(username, values)
        onConversationRowWrite(username)
    }

    /**
     * 已有会话行更新（密友发新消息走 UPDATE 而非 INSERT）同样立即原生删除 + 刷新——
     * 这是「收到密友消息后主页会话重新出现、要重启微信才恢复隐藏」的处置。
     */
    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "rconversation") return
        val username = values.getAsString("username")
            ?: whereClause?.takeIf { it.contains("username", ignoreCase = true) }
                ?.let { whereArgs?.firstOrNull() }
            ?: return
        // 增量合并到既有快照（UPDATE 值不完整；无既有快照时存部分值，聊胜于无）
        mergeSnapshot(username, values)
        onConversationRowWrite(username)
    }
}

// ─────────────────────────── 2. 通讯录隐藏 ───────────────────────────

/**
 * 通讯录隐藏（MaskWechat「HideContactList」语义 → 备份版 HideSecretFriendContacts 实现）。
 *
 * - IQueryListener：通讯录/选择器类 rcontact 列表查询（含标签成员、群聊列表、公众号列表）
 *   复用 [rewriteWrapperSql] 的 contact-list 规则（display list 必有 ORDER BY，单行查询不受影响）；
 * - AddressLiveList.e(List)：通讯录 MvvmList 的「快照预处理」，把密友条目从快照**替换**出去
 *   （替换而非 removeAll——该 list 是 MvvmList 自身持久 snapshot 字段）。
 */
@Feature(
    name = "通讯录隐藏",
    categories = ["密友功能"],
    description = "把密友从通讯录列表隐藏：SQL 列表查询与通讯录快照预处理双入口过滤，名单为空或临时显示时全部放行"
)
object HideSecretContacts : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideSecretContacts"

    /**
     * `AddressLiveList.e(List snapshotList)` — 通讯录 MvvmList 预处理器。
     * 与 HideContactsLists 同一 matcher：`"snapshotList"` 是 e() 内 o.g 空检查字面量。
     */
    private val methodAddressMvvmListPreprocessList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.AddressLiveList"
            usingEqStrings("snapshotList")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        hookSecretMvvmListFilter(methodAddressMvvmListPreprocessList, "AddressLiveList")
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onQuery(sql: String): String? {
        if (SecretFriendState.isTemporarilyShown()) return null
        if (sql.contains("rcontact.username NOT IN", ignoreCase = true)) return null
        return rewriteWrapperSql(sql, SecretFriendState.getWxIds())
    }
}

// ─────────────────────────── 3. 标签内隐藏 ───────────────────────────

/**
 * 标签内隐藏（浮云「标签内隐藏」语义：标签成员列表 + 「谁可以看」选择器中的密友过滤）。
 *
 * - SQL：标签成员列表 / 谁可以看选择器都从 rcontact 的 display list 查询取数，
 *   [rewriteWrapperSql] 的 contact-list 规则直接覆盖；
 * - 结构式兜底：ui.contact 包内 MvvmList 预处理（allowFailure + 形状守卫），类名漂移时整段跳过。
 */
@Feature(
    name = "标签内隐藏",
    categories = ["密友功能"],
    description = "通讯录标签成员列表与朋友圈「谁可以看」选择器中不再出现密友"
)
object HideTagMembers : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideTagMembers"

    /**
     * 结构式兜底：ui.contact 包内 MvvmList 快照预处理（含标签成员列表）。
     * 无稳定类名锚点 → allowFailure 多候选取首个，hook 体形状守卫（非联系人条目直接放行）。
     */
    private val methodContactMvvmListPreprocess by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.contact")
        matcher {
            usingEqStrings("snapshotList")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        hookSecretMvvmListFilter(methodContactMvvmListPreprocess, "ContactLabelMvvmList")
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onQuery(sql: String): String? {
        if (SecretFriendState.isTemporarilyShown()) return null
        if (sql.contains("rcontact.username NOT IN", ignoreCase = true)) return null
        return rewriteWrapperSql(sql, SecretFriendState.getWxIds())
    }
}

// ─────────────────────────── 4. 禁止进入聊天 ───────────────────────────

/**
 * 禁止进入密友聊天（MaskWechat「EnterChattingUI」→ 备份版 BlockSecretFriendAccess 聊天段）。
 *
 * - ChattingUI.onCreate 检查 intent 的 "Chat_User"，命中密友名单 → finish（页面完全起不来）；
 * - WeStartActivityApi 兜底：启动指向聊天页且带密友目标的 intent 直接取消。
 * 临时显示态下放行（解除后可正常进入密友对话）。
 */
@Feature(
    name = "禁止进入聊天",
    categories = ["密友功能"],
    description = "点开密友的聊天页立即退出；临时解除隐藏期间可正常进入"
)
object BlockChat : SwitchFeature() {

    private const val TAG = "BlockChat"

    override fun onEnable() {
        // 第二道兜底: 启动 Activity 时就拦下指向聊天页且带密友目标的 intent
        // (void 方法在 before 里给 result 赋非 null 哨兵即跳过原方法, 启动被取消)
        WeStartActivityApi.addListener { param, intent ->
            if (SecretFriendState.isTemporarilyShown()) return@addListener
            val wxId = intent.getStringExtra("Chat_User") ?: return@addListener
            if (!SecretFriendState.isSecret(wxId)) return@addListener
            WeLogger.d(TAG, "cancelling startActivity toward secret friend chat $wxId")
            param.result = 0
        }

        ChattingUI::class.reflekt()
            .firstMethod { name = "onCreate"; parameterCount = 1 }
            .hookAfter {
                // after 里 finish，绝不能在 before 里跳过 onCreate——原方法被跳过后
                // super.onCreate() 得不到调用，framework 抛 SuperNotCalledException 直接崩
                //（第一道拦截在 WeStartActivityApi，页面通常根本不会启动）
                val activity = thisObject as? Activity ?: return@hookAfter
                val wxId = activity.intent?.getStringExtra("Chat_User") ?: return@hookAfter
                if (!_isEnabled || SecretFriendState.isTemporarilyShown()) return@hookAfter
                if (!SecretFriendState.isSecret(wxId)) return@hookAfter
                if (activity.isFinishing) return@hookAfter
                WeLogger.d(TAG, "blocked entering chat with secret friend $wxId")
                activity.finish()
            }
    }
}

// ─────────────────────────── 5. 禁止查看资料 ───────────────────────────

/**
 * 禁止查看密友资料（MaskWechat「BlockContactInfo」→ 备份版 BlockSecretFriendAccess 资料段）。
 *
 * ContactInfoUI.onCreate 检查 intent 的 "Contact_User"，命中密友名单 → finish；
 * WeStartActivityApi 同款兜底。临时显示态下放行。
 */
@Feature(
    name = "禁止查看资料",
    categories = ["密友功能"],
    description = "点开密友的资料页立即退出；临时解除隐藏期间可正常查看"
)
object BlockProfile : SwitchFeature() {

    private const val TAG = "BlockProfile"

    override fun onEnable() {
        WeStartActivityApi.addListener { param, intent ->
            if (SecretFriendState.isTemporarilyShown()) return@addListener
            val wxId = intent.getStringExtra("Contact_User") ?: return@addListener
            if (!SecretFriendState.isSecret(wxId)) return@addListener
            WeLogger.d(TAG, "cancelling startActivity toward secret friend profile $wxId")
            param.result = 0
        }

        // ContactInfoUI stub 不是 Activity, 统一按 Activity 处理(真实微信里它是 Activity)
        ContactInfoUI::class.reflekt()
            .firstMethod { name = "onCreate"; parameterCount = 1 }
            .hookAfter {
                // after 里 finish，绝不能在 before 里跳过 onCreate（SuperNotCalledException）
                val activity = thisObject as? Activity ?: return@hookAfter
                val wxId = activity.intent?.getStringExtra("Contact_User") ?: return@hookAfter
                if (!_isEnabled || SecretFriendState.isTemporarilyShown()) return@hookAfter
                if (!SecretFriendState.isSecret(wxId)) return@hookAfter
                if (activity.isFinishing) return@hookAfter
                WeLogger.d(TAG, "blocked viewing profile of secret friend $wxId")
                activity.finish()
            }
    }
}
