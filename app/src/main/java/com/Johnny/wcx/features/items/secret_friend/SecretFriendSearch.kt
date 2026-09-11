package com.Johnny.wcx.features.items.secret_friend

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.contacts.hidecontacts.rewriteFtsSql
import com.Johnny.wcx.features.items.contacts.hidecontacts.rewriteWrapperSql
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Field

/**
 * 主页搜索隐藏（MaskWechat「HideSearchListUI」→ 备份版 HideSecretFriendSearch 全量语义）。
 *
 * IQueryListener 双挂两套重写（临时显示态/已注入时跳过，链式分发与「隐藏联系人」同开合法）：
 * - FTS 全局搜索主结果（消息/联系人/群搜索）：[rewriteFtsSql]
 *   （aux_index NOT IN 包装 + FTS5ChatRoomMembers join 约束）；
 * - 搜索下拉/联系人列表等 wrapper 查询：[rewriteWrapperSql] 全套规则（密友名单）；
 * - 群成员搜索 / 共同群聊好友建议：两条 FTS task 的结构式过滤（SQL 之外的最后两个搜索面）。
 */
@Feature(
    name = "主页搜索隐藏",
    categories = ["密友功能"],
    description = "全局搜索、搜索下拉与群成员搜索中不再出现密友：FTS 主结果、联系人下拉、群成员搜索与共同群聊建议全部过滤"
)
object HideSearch : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideSearch"

    /** `fts.logic.q0.p(FTSResult)` — SearchChatroomMemberTask（群聊内搜索成员）。 */
    private val methodFtsSearchChatroomMemberTask by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("SearchChatroomMemberTask")
            }
            paramCount(1)
            returnType("void")
        }
    }

    /** `fts.logic.h.p(FTSResult)` — SearchCommonChatroomUserTask（共同群聊好友建议）。 */
    private val methodFtsSearchCommonChatroomUserTask by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("SearchCommonChatroomUserTask")
            }
            paramCount(1)
            returnType("void")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        if (!methodFtsSearchChatroomMemberTask.isPlaceholder) {
            methodFtsSearchChatroomMemberTask.hookAfter {
                val secrets = SecretFriendState.getWxIds()
                if (secrets.isEmpty() || SecretFriendState.isTemporarilyShown()) return@hookAfter

                val entries = searchResultEntries(args[0]) ?: return@hookAfter
                for (entry in entries) {
                    entry ?: continue
                    val membersField = singleListField(entry) ?: continue
                    val members = membersField.get(entry) as? List<*> ?: continue
                    if (members.isEmpty()) continue

                    val filtered = members.filterNot { it != null && entryMentionsSecret(it, secrets) }
                    if (filtered.size == members.size) continue

                    WeLogger.d(TAG, "filtered ${members.size - filtered.size} secret friend(s) from 群成员搜索")
                    membersField.set(entry, ArrayList(filtered))
                }
            }
        } else {
            WeLogger.w(TAG, "SearchChatroomMemberTask 未解析, 群成员搜索不过滤")
        }

        if (!methodFtsSearchCommonChatroomUserTask.isPlaceholder) {
            methodFtsSearchCommonChatroomUserTask.hookAfter {
                val secrets = SecretFriendState.getWxIds()
                if (secrets.isEmpty() || SecretFriendState.isTemporarilyShown()) return@hookAfter

                val response = args[0] ?: return@hookAfter
                val entriesField = singleListField(response) ?: return@hookAfter
                val entries = entriesField.get(response) as? List<*> ?: return@hookAfter
                if (entries.isEmpty()) return@hookAfter

                val filtered = entries.filterNot { it != null && entryMentionsSecret(it, secrets) }
                if (filtered.size == entries.size) return@hookAfter

                WeLogger.d(TAG, "filtered ${entries.size - filtered.size} secret friend suggestion(s)")
                entriesField.set(response, ArrayList(filtered))
            }
        } else {
            WeLogger.w(TAG, "SearchCommonChatroomUserTask 未解析, 共同群聊建议不过滤")
        }
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    /**
     * FTS 主搜索 + wrapper 下拉双入口。会话列表等 wrapper 规则由「主页会话隐藏」负责，
     * 本功能在两套规则各自的查询形状上补齐搜索面；已注入过的 SQL 直接放行防重复包装。
     */
    override fun onQuery(sql: String): String? {
        if (SecretFriendState.isTemporarilyShown()) return null
        val secrets = SecretFriendState.getWxIds()
        if (secrets.isEmpty()) return null

        val lower = sql.lowercase()
        if (lower.contains("from snsinfo")) return null // 朋友圈 feed 由 HideMoments 负责
        if (lower.contains("aux_index NOT IN") || lower.contains("rcontact.username NOT IN")) return null

        // FTS 先判（准确形状），wrapper 规则兜搜索下拉等 rcontact 列表查询
        rewriteFtsSql(sql, secrets)?.let { return it }
        return rewriteWrapperSql(sql, secrets)
    }

    /** `FTSResult.entries` — task 输出列表，形状不符时返回 null。 */
    private fun searchResultEntries(response: Any?): List<*>? {
        response ?: return null
        val field = singleListField(response) ?: return null
        return field.get(response) as? List<*>
    }

    /** FTS 模型类每版都重命名，按形状定位：类层级上唯一的 java.util.List 字段。 */
    private fun singleListField(obj: Any): Field? = obj.reflekt()
        .firstFieldOrNull {
            type = List::class
            superclass()
        }?.self?.makeAccessible()
}
