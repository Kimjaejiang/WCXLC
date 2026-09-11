package com.Johnny.wcx.features.items.secret_friend

import android.content.ContentValues
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BString
import java.util.LinkedList

/**
 * 密友各开关共用的 hook 安装器。
 *
 * 全部为 [BaseFeature] 扩展（hookBefore/hookAfter 是 BaseFeature 的成员扩展，只能在
 * Feature 接收者上调用）。回调体一律不包 try-catch（工程约定）；形状探测失败走
 * `isPlaceholder` / `== null` 守卫整段跳过，等价 allowFailure 语义。
 */

private const val TAG = "SecretFriendHooks"

/**
 * MvvmList 快照过滤（通讯录 / 标签成员 / 选择器同款，备份版 HideSecretFriendContacts 语义）。
 *
 * `MvvmList.e(List snapshotList)` 是微信各 MvvmList 的「快照预处理」入口：把密友条目从
 * args[0] **替换**（而非原地 removeAll）出去——该 list 是 MvvmList 自身的持久 snapshot
 * 字段，原地删除会让临时显示无法恢复条目。
 *
 * 条目类是混淆类，但只持有一个 `com.tencent.mm.storage` 类型字段（Contact），按声明
 * 类型选取可跨字段重命名存活（与 HideContactsLists 同一形状判定）。
 */
internal fun BaseFeature.hookSecretMvvmListFilter(target: DexMethodDelegate, label: String) {
    if (target.isPlaceholder) {
        WeLogger.w(TAG, "$label preprocess method wasn't resolved; that list stays unfiltered")
        return
    }
    target.hookBefore {
        // 空名单 / 临时显示态 → 全部放行，零开销
        if (SecretFriendState.isEmpty() || SecretFriendState.isTemporarilyShown()) return@hookBefore

        val contacts = args[0] as? List<*> ?: return@hookBefore
        if (contacts.isEmpty()) return@hookBefore

        val sample = contacts.firstNotNullOfOrNull { it } ?: return@hookBefore
        val contactInfoField = sample.reflekt()
            .firstFieldOrNull { type { it.name.startsWith("${PackageNames.WECHAT}.storage") } }
            ?.self?.makeAccessible()
            ?: return@hookBefore
        val usernameField = contactInfoField.type.reflekt()
            .firstFieldOrNull {
                name = "field_username"
                superclass()
            }?.self?.makeAccessible()
            ?: return@hookBefore

        val kept = contacts.filterNot { contact ->
            val contactInfo = contact?.let { contactInfoField.get(it) } ?: return@filterNot false
            val username = usernameField.get(contactInfo) as? String ?: return@filterNot false
            SecretFriendState.isSecret(username)
        }
        if (kept.size == contacts.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${contacts.size - kept.size} secret friend(s) out of $label")
        // 替换而非 removeAll：这是 MvvmList 自身持久 snapshot 字段（见 KDoc）
        args[0] = ArrayList(kept)
    }
}

/**
 * 通用「List 参数按条目 String 字段命中密友」过滤（最近转发 / 状态页 / 存储空间等
 * 结构式兜底共用）。条目类每版重命名，按「任一 String 字段值恰为密友 wxid」判定命中，
 * 与备份版群成员搜索过滤同一判定方式。找不到字段时整段跳过。
 */
internal fun BaseFeature.hookSecretStringArgListFilter(target: DexMethodDelegate, label: String, argIndex: Int = 0) {
    if (target.isPlaceholder) {
        WeLogger.w(TAG, "$label target wasn't resolved; that surface stays unfiltered")
        return
    }
    target.hookBefore {
        if (SecretFriendState.isEmpty() || SecretFriendState.isTemporarilyShown()) return@hookBefore
        val secrets = SecretFriendState.getWxIds()

        val items = args.getOrNull(argIndex) as? List<*> ?: return@hookBefore
        if (items.isEmpty()) return@hookBefore

        val filtered = items.filterNot { it != null && entryMentionsSecret(it, secrets) }
        if (filtered.size == items.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${items.size - filtered.size} secret entry(ies) out of $label")
        args[argIndex] = ArrayList(filtered)
    }
}

/** 条目的任一 String 字段（含父类）持有密友 wxid 即命中。 */
internal fun entryMentionsSecret(entry: Any, secrets: Set<String>): Boolean {
    val strings = entry.reflekt().fields {
        type = BString
        superclass()
    }
    return strings.any { field ->
        val value = field.self.makeAccessible().get(entry) as? String ?: return@any false
        value.isNotEmpty() && value in secrets
    }
}

/**
 * 朋友圈内联点赞/评论过滤（备份版 HideSecretFriendMoments 的 snsInfoToSnsStruct 段，独立成
 * 共用安装器：HideMoments 与 HideMomentsInteraction 各自注册，同开时第二次命中已是
 * 过滤后的克隆体，幂等无副作用）。
 */
internal fun BaseFeature.installSnsInlineSecretFilter(methodSnsInfoToSnsStruct: DexMethodDelegate) {
    if (methodSnsInfoToSnsStruct.isPlaceholder) {
        WeLogger.w(TAG, "snsInfoToSnsStruct wasn't resolved; moments inline likes/comments stay visible")
        return
    }

    // 条目类名混淆且每版漂移, 借 SnsCommentFooter.getCommentInfo() 的真实方法名拿返回类型。
    val entryClass = runCatching {
        com.tencent.mm.plugin.sns.ui.SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve the SNS like/comment entry class; moments inline hiding unavailable", it)
        return
    }

    // 声明的 String 字段按源序为 [Username, Nickname, Content, ReplyUsername, ...]。
    // 按声明类型 + 序数选取可跨字段重命名存活。
    val stringFields = entryClass.reflekt().fields { type = BString }.map { it.self.makeAccessible() }
    val usernameField = stringFields.getOrNull(0)
    val replyUsernameField = stringFields.getOrNull(3)
    if (usernameField == null || replyUsernameField == null) {
        WeLogger.w(TAG, "failed to resolve username/replyUsername fields on the SNS entry class; moments inline hiding unavailable")
        return
    }

    // SnsObject 无 clone(); 唯一可靠的复制方式是序列化 + 经其自带 protobuf 编解码重解析。
    val parseFromMethod = runCatching {
        com.tencent.mm.protocal.protobuf.SnsObject::class.reflekt()
            .firstMethod { name = "parseFrom"; superclass() }.self
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve SnsObject.parseFrom; moments inline hiding unavailable", it)
        return
    }

    fun isEntrySecret(entry: Any, secrets: Set<String>): Boolean {
        val username = usernameField.get(entry) as? String
        if (username != null && username in secrets) return true
        // 引用密友评论的回复（"回复 张三: ..."）同样要剔除。
        val replyUsername = replyUsernameField.get(entry) as? String
        return replyUsername != null && replyUsername in secrets
    }

    methodSnsInfoToSnsStruct.hookBefore {
        val secrets = SecretFriendState.getWxIds()
        // 空名单 / 临时显示态快速路径, 跳过全部反射
        if (secrets.isEmpty() || SecretFriendState.isTemporarilyShown()) return@hookBefore

        val original = args.getOrNull(1) as? com.tencent.mm.protocal.protobuf.SnsObject ?: return@hookBefore

        val likeList = original.LikeUserList as? List<*>
        val commentList = original.CommentUserList as? List<*>
        val hasSecretLike = likeList?.any { it != null && isEntrySecret(it, secrets) } == true
        val hasSecretComment = commentList?.any { it != null && isEntrySecret(it, secrets) } == true
        if (!hasSecretLike && !hasSecretComment) return@hookBefore

        // 必须在克隆上操作: SnsInfoStorageLogic 按内容哈希缓存同一 SnsObject 实例,
        // 原地删除会在下次序列化时把条目从持久层也删掉。
        val clone = com.tencent.mm.protocal.protobuf.SnsObject().also { parseFromMethod.invoke(it, original.toByteArray()) }

        if (hasSecretLike) {
            val filtered = LinkedList((clone.LikeUserList as List<*>).filterNotNull().filterNot { isEntrySecret(it, secrets) })
            clone.LikeUserList = filtered
            clone.LikeUserListCount = filtered.size
            clone.LikeCount = filtered.size
        }
        if (hasSecretComment) {
            val filtered = LinkedList((clone.CommentUserList as List<*>).filterNotNull().filterNot { isEntrySecret(it, secrets) })
            clone.CommentUserList = filtered
            clone.CommentUserListCount = filtered.size
            clone.CommentCount = filtered.size
        }

        args[1] = clone
    }
}

/**
 * 密友新消息判定（消息与通知组共用）：message 表插入、别人发的、talker 在名单内时返回
 * talker，否则 null。会话消息行的 talker 即发送者（单聊）或群 id（群聊）。
 */
internal fun secretIncomingTalker(table: String, values: ContentValues): String? {
    if (table != "message") return null
    if ((values.getAsInteger("isSend") ?: 0) == 1) return null
    val talker = values.getAsString("talker") ?: return null
    return talker.takeIf { SecretFriendState.isSecret(it) }
}
