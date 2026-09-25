package com.Johnny.wcx.features.items.secret_friend

import android.app.Activity
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
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
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
 * ## A1 方案（2026-09-26）：纯查询过滤，永不物理删行/重建
 *
 * 隐藏**完全依赖查询过滤**——SQLite wrapper 钩子 + IQueryListener 对 rconversation 多行查询
 * 注入 NOT IN。会话行**始终保留在数据库**，模块不删除、不 INSERT 重建。
 *
 * 背景：旧版用「delChatContact 删行 + 快照 INSERT 重建」实现即时隐藏，但快照只存部分字段、
 * 类型处理不完整，重建出的行字段残缺（`convItem.f321932i=0` → `getDrawable(0)` →
 * `Resources$NotFoundException`），导致微信主页会话列表反复崩溃（关闭模块/升级微信仍崩，
 * 分身数据干净不崩）。A1 把写入 rconversation 的路径整个移除，从根上杜绝写坏行。
 *
 * 收益/代价：
 * - 收益：rconversation 永不被模块写坏，崩溃根治；关闭开关 / 移出名单 / 临时解除立即恢复
 *   （行本来就在，过滤停用即出现），不删聊天记录。
 * - 代价：密友新消息到达后主页可能短暂闪现（下一轮过滤查询即隐去）——可接受。
 */
@Feature(
    name = "主页会话隐藏",
    categories = ["密友功能"],
    description = "把密友的会话从主页聊天列表隐藏：名单内成员发来新消息时也保持隐藏；关闭开关后会话立即恢复（不删聊天记录）"
)
object HideConversations : SwitchFeature(), IResolveDex,
    WeDatabaseListenerApi.IQueryListener {

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
                if (SecretFriendState.isTemporarilyShown()) return@hookBefore
                val sql = args.firstOrNull() as? String ?: return@hookBefore
                val rewritten = rewriteConversationSql(sql, SecretFriendState.getWxIds())
                    ?: rewriteWrapperSql(sql, SecretFriendState.getWxIds())
                    ?: return@hookBefore
                args[0] = rewritten
            }
        }

        // A1：隐藏依赖查询过滤（行保留），开启时刷新让过滤立即生效，不做任何删行/重建。
        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
        // 行从未被物理删除，过滤停用即恢复显示，只需刷新列表。
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

    /**
     * 名单变更 / 临时显示开合后的对账。
     *
     * A1 下会话行从不物理删除/重建，这里只需刷新列表：过滤随
     * [SecretFriendState.isTemporarilyShown] 与名单自动生效。
     */
    fun reconcileOnListChange() {
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "reconcileOnListChange: refreshed (query-filter hiding)")
    }

    /**
     * 临时解除后「恢复显示」。
     *
     * A1 下行从未被删除，过滤在临时显示态已放行，这里只需刷新列表即可让会话行出现。
     * 保留签名（SecretFriendState / HideContacts 等调用方依赖），返回 0 表示无需重建。
     */
    fun restoreHiddenRows(): Int {
        WeConversationApi.reloadConversations()
        return 0
    }

    /**
     * 临时显示结束后「恢复隐藏」。
     *
     * A1 下不物理删行：行保留，过滤随 [SecretFriendState.isTemporarilyShown] 变 false
     * 自动重新生效，这里只需刷新列表让隐藏立即呈现。
     */
    fun removeSecretRows() {
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "removeSecretRows: refreshed (query-filter hiding)")
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
 * 禁止查看密友资料（Maskwechat「BlockContactInfo」→ 备份版 BlockSecretFriendAccess 资料段）。
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
