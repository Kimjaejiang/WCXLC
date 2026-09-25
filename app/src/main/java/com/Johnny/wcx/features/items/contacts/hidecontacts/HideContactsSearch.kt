package com.Johnny.wcx.features.items.contacts.hidecontacts

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Field

private const val TAG = "HideContacts.Search"

/**
 * The two global-search tasks whose results cannot be reached from SQL.
 *
 * Everything else in 搜索 is filtered in HideContactsSql.kt, either by wrapping the FTS statement in
 * an `aux_index NOT IN (...)` outer select or by constraining the `FTS5ChatRoomMembers` join. These
 * two tasks are different because the hidden contact is not a *row* of the statement they run:
 *
 * - **SearchChatroomMemberTask** searches the members of one chatroom. Its FTS statement returns a
 *   single row for the chatroom — `aux_index` is the *group* id, not a member — and the individual
 *   members are decoded afterwards from `chatroom.memberlist`, a `;`-separated text column. So the
 *   existing `aux_index` filter only ever hides the group itself.
 * - **SearchCommonChatroomUserTask** suggests people you share groups with. It reads a packed
 *   `content` blob out of the index, splits it in Java and then resolves the contacts through the
 *   WCDB ORM builder, which emits no statement the rewriter can see.
 *
 * Both are cut at the same place: the task body (`r(Lu73/v;)V` on 8.0.78), filtered on the way out.
 * Removing entries after the task has finished keeps every index the task computed internally
 * consistent — SearchChatroomMemberTask in particular indexes into the sorted `memberlist` array
 * while it builds its entries, so filtering the input would silently shift every member's data onto
 * the wrong person.
 *
 * ## 8.0.78 class names (verified against `classes12.dex`)
 *
 * The `getName()` return strings are unique app-wide, which is what the matchers anchor on — the
 * *obfuscated* names must not be hard-coded:
 *
 * | `getName()` string | class |
 * | --- | --- |
 * | `SearchChatroomMemberTask` | `com.tencent.mm.plugin.fts.logic.s0` |
 * | `SearchCommonChatroomUserTask` | `com.tencent.mm.plugin.fts.logic.j` |
 *
 * The task body is `r(Lu73/v;)V` (`u73.v` is the FTSResult holder) on this version. Those method
 * names drift between releases, so both matchers pin down "the class carrying that string, then its
 * single 1-arg void method" instead of a method name.
 */
internal fun HideContacts.installSearchHooks() {
    installChatroomMemberSearchHook()
    installCommonChatroomUserSearchHook()
}

/**
 * 群聊内搜索成员（搜索结果的「群名(N)包含:某人」行）— `SearchChatroomMemberTask`.
 *
 * ## 8.0.78 实测形态（`classes12.dex`）
 *
 * 真实类是 **`com.tencent.mm.plugin.fts.logic.s0`**（任务体 `r(u73.v)`）。结果放在
 * `vVar.e`（`List<u73.z>`）；`u73.z extends u73.y`，wxid 存在继承来的
 * **`public String e`** 字段（`u73/y.java:18`），成员子表是 `u73.y.n`（`public List`）。
 *
 * 任务体的成员来源分两段，都不在 SQL 行内：
 * - 一条 `MATCH ... AND type = 131075 AND subtype = 38 AND aux_index = ?`（? = 群 id，群成员卡片）；
 * - 读 `chatroom.memberlist`（`;` 分隔文本列）后切分，逐个解析显示名写进成员条目的 `f`。
 *
 * 因此只能在任务体出口过滤 —— 过滤输入会让 `s0` 内部按 `memberlist` 排序的下标错位，
 * 把每个成员的数据安到别人身上。
 */
private fun HideContacts.installChatroomMemberSearchHook() {
    if (methodFtsSearchChatroomMemberTask.isPlaceholder) {
        WeLogger.w(TAG, "SearchChatroomMemberTask wasn't resolved; 群成员搜索 stays unfiltered")
        return
    }

    methodFtsSearchChatroomMemberTask.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        if (hiddenContacts.isEmpty()) return@hookAfter

        val entries = searchResultEntries(args[0]) ?: return@hookAfter
        for (entry in entries) {
            entry ?: continue
            val membersField = singleListField(entry) ?: continue
            val members = membersField.get(entry) as? List<*> ?: continue
            if (members.isEmpty()) continue

            val filtered = members.filterNot { it != null && mentionsHiddenContact(it) }
            if (filtered.size == members.size) continue

            WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from 群成员搜索")
            membersField.set(entry, ArrayList(filtered))
        }
    }
}

/**
 * 共同群聊的好友建议（「群名包含:某人」那类结果行）— `SearchCommonChatroomUserTask`.
 *
 * ## 8.0.78 实测形态（`classes12.dex`）
 *
 * 真实类是 **`com.tencent.mm.plugin.fts.logic.j`**（任务体 `r(u73.v)`）。它在
 * `j.java:99-106` 把每个建议**逐个**追加成 `u73.z` 并写入 `zVar.e = <wxid>`
 * （`vVar.e` 就是 `u73.y` 继承来的 `public List`/`public String e`），
 * 所以这里**丢的是条目本身**，不是某个嵌套子表。
 *
 * 该任务体第 47 行的 raw SQL（`SELECT content FROM <meta> NOT INDEXED JOIN <index>
 * ON (...) WHERE <meta> MATCH '...' AND entity_id <= 50 ORDER BY timestamp DESC LIMIT 10`）
 * **不在 [rewriteFtsSql] 的任何规则内**：它的投影不是
 * `docid, type, subtype, entity_id, aux_index`，也不是任何 `SQL_SELECT_*` 前缀，
 * 所以 SQL 改写完全够不着它；成员名是在 Java 侧按 `c.c` 正则从 `content` 里切出来的。
 * 这就是「搜一个被隐藏的人，仍会冒出他所在的群」的直接原因。
 */
private fun HideContacts.installCommonChatroomUserSearchHook() {
    if (methodFtsSearchCommonChatroomUserTask.isPlaceholder) {
        WeLogger.w(TAG, "SearchCommonChatroomUserTask wasn't resolved; 共同群聊好友建议 stays unfiltered")
        return
    }

    methodFtsSearchCommonChatroomUserTask.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        if (hiddenContacts.isEmpty()) return@hookAfter

        val response = args[0] ?: return@hookAfter
        val entriesField = singleListField(response) ?: return@hookAfter
        val entries = entriesField.get(response) as? List<*> ?: return@hookAfter
        if (entries.isEmpty()) return@hookAfter

        val filtered = entries.filterNot { it != null && mentionsHiddenContact(it) }
        if (filtered.size == entries.size) return@hookAfter

        WeLogger.d(TAG, "filtered ${entries.size - filtered.size} hidden 共同群聊 suggestion(s)")
        entriesField.set(response, ArrayList(filtered))
    }
}

/**
 * `FTSResult.entries` — the task output list (`u73.v.e`), or null when the shape is unexpected.
 *
 * `u73.v` declares exactly one `java.util.List` field on 8.0.78, so the shape probe stays stable
 * even though the class name is obfuscated and renumbered per version.
 */
private fun searchResultEntries(response: Any?): List<*>? {
    response ?: return null
    val field = singleListField(response) ?: return null
    return field.get(response) as? List<*>
}

/**
 * The single `java.util.List` field declared on [obj]'s class hierarchy.
 *
 * The FTS model classes are obfuscated and renumbered every version, so they are addressed by shape
 * instead of by name. `firstFieldOrNull` returns null rather than throwing if a future version grows
 * a second list and the shape stops being unique — worst case the surface stays unfiltered.
 */
private fun singleListField(obj: Any): Field? = obj.reflekt()
    .firstFieldOrNull {
        type = List::class
        superclass()
    }?.self?.makeAccessible()

/**
 * True when any `String` field of [entry] holds a hidden wxid.
 *
 * The search-entry and member-entry classes both carry the contact id in one specific obfuscated
 * field alongside several display strings (nickname, remark, highlighted label). Rather than pin
 * that field down by ordinal — obfuscated field *order* is as version-unstable as the names —
 * every string on the object is tested. A false positive would need a nickname or remark that is
 * character-for-character a hidden contact's wxid, which cannot happen in practice.
 */
private fun mentionsHiddenContact(entry: Any): Boolean {
    val strings = entry.reflekt().fields {
        type = String::class
        superclass()
    }
    return strings.any { field ->
        val value = field.self.makeAccessible().get(entry) as? String ?: return@any false
        value.isNotEmpty() && HideContacts.isHiddenNow(value)
    }
}
