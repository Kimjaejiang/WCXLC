package com.Johnny.wcx.features.items.secret_friend

import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BString

/**
 * 朋友圈组 6 / 8 / 9：朋友圈隐藏（feed 注入 + 红点 + 内联）、朋友圈互动隐藏、分组图标隐藏。
 *
 * 6/8 与「隐藏联系人」的朋友圈过滤同一组已验证拦截点，判定换密友名单（备份版
 * HideSecretFriendMoments 语义）；所有 hook 过滤前先查临时显示态。
 */

// ─────────────────────────── 6. 朋友圈隐藏 ───────────────────────────

/**
 * 朋友圈隐藏（MaskWechat SNS 语义 → 备份版 HideSecretFriendMoments 全量语义）：
 * - 时间线主信息流：SnsInfo feed 查询注入 `SnsInfo.userName NOT IN (...)`（含
 *   EnhanceQuery (1=1) 标记两种形态；与「隐藏联系人」同开时合并进已有子句，两份名单都过滤）；
 * - 发现 tab「N 位朋友的新动态」红点：NetSceneSnsSync.updateSyncDataCache 命中密友即取消；
 * - 密友的点赞/评论内联：见 [installSnsInlineSecretFilter]（SnsObject 克隆后过滤）。
 */
@Feature(
    name = "朋友圈隐藏",
    categories = ["密友功能"],
    description = "时间线不再出现密友的帖子，密友的点赞/评论在共同好友帖子下也不显示，发现页红点不计密友新动态"
)
object HideMoments : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideMoments"

    // 在朋友圈信息流中隐藏密友发布的帖子; EnhanceQuery 会把信息流标记替换为 (1=1)
    private const val FEED_MARKER_RAW = "(sourceType & 2 != 0 )"
    private const val FEED_MARKER_ENHANCED = "(1=1)"

    /** `static void c3.H(c3, SnsObject)` — NetSceneSnsSync.updateSyncDataCache（发现页红点唯一写入点）。 */
    private val methodSnsSyncUpdateRedDotCache by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("updateSyncDataCache", "com.tencent.mm.plugin.sns.model.NetSceneSnsSync")
        }
    }

    /** `fb4.z0.D0(SnsInfo, SnsObject, ...)` — SnsUtil.snsInfoToSnsStruct（朋友圈渲染的单一咽喉点）。 */
    private val methodSnsInfoToSnsStruct by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        if (!methodSnsSyncUpdateRedDotCache.isPlaceholder) {
            methodSnsSyncUpdateRedDotCache.hookBefore {
                if (SecretFriendState.isTemporarilyShown()) return@hookBefore
                // 静态两参方法: args[0] 是 NetSceneSnsSync 实例, args[1] 是 SnsObject;
                // Username 是未混淆的 protobuf 字段。
                val snsObject = args.getOrNull(1) as? SnsObject ?: return@hookBefore
                val username = snsObject.Username ?: return@hookBefore
                if (!SecretFriendState.isSecret(username)) return@hookBefore
                WeLogger.i(TAG, "suppressing moments red-dot cache update for secret friend $username")
                result = null
            }
        } else {
            WeLogger.w(TAG, "updateSyncDataCache 未解析, 发现页红点不过滤")
        }

        installSnsInlineSecretFilter(methodSnsInfoToSnsStruct)
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    /**
     * 时间线主信息流：与 rewriteMomentsFeedSql 同款注入（密友名单）。
     * 与「隐藏联系人」同开时对方可能已注入自己的 NOT IN 子句——此时把密友名单**合并**进
     * 已有子句（而非跳过），两份名单都会被过滤；个人主页 (userName=) 跳过。
     */
    override fun onQuery(sql: String): String? {
        if (SecretFriendState.isTemporarilyShown()) return null
        val secrets = SecretFriendState.getWxIds()
        if (secrets.isEmpty()) return null
        if (!sql.contains("from SnsInfo", ignoreCase = true)) return null
        if (sql.contains("SnsInfo.userName=", ignoreCase = false)) return null

        val list = secrets.joinToString(",") { "'${it.replace("'", "''")}'" }
        val filter = " AND SnsInfo.userName NOT IN ($list) "

        // 已有注入（隐藏联系人或自己早前的重写）：把名单合并进该子句
        val injected = Regex("SnsInfo\\.userName\\s+not\\s+in\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE).find(sql)
        if (injected != null) {
            val merged = injected.groupValues[1].split(',')
                .map { it.trim().trim('\'') }
                .filter { it.isNotEmpty() }
                .toSet() + secrets
            val mergedList = merged.joinToString(",") { "'${it.replace("'", "''")}'" }
            return sql.replaceRange(injected.range, "SnsInfo.userName NOT IN ($mergedList)")
        }

        return when {
            sql.contains(FEED_MARKER_RAW) ->
                sql.replaceFirst(FEED_MARKER_RAW, FEED_MARKER_RAW + filter)
            sql.contains(FEED_MARKER_ENHANCED) ->
                sql.replaceFirst(FEED_MARKER_ENHANCED, FEED_MARKER_ENHANCED + filter)
            else -> null
        }
    }
}

// ─────────────────────────── 8. 朋友圈互动隐藏 ───────────────────────────

/**
 * 朋友圈互动隐藏（备份版内联过滤独立开关）：密友在别人帖子下的点赞/评论不再显示。
 * 与「朋友圈隐藏」同开时，后者已把条目滤掉，本开关的 hook 命中后幂等无副作用。
 */
@Feature(
    name = "朋友圈互动隐藏",
    categories = ["密友功能"],
    description = "独立开关：密友的点赞/评论在共同好友的帖子下不显示（与朋友圈隐藏可叠加，重复开启无副作用）"
)
object HideMomentsInteraction : SwitchFeature(), IResolveDex {

    private const val TAG = "HideMomentsInteraction"

    /** 同 HideMoments：SnsUtil.snsInfoToSnsStruct 渲染咽喉点。 */
    private val methodSnsInfoToSnsStruct by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")
        }
    }

    override fun onEnable() {
        installSnsInlineSecretFilter(methodSnsInfoToSnsStruct)
    }
}

// ─────────────────────────── 9. 分组图标隐藏 ───────────────────────────

/**
 * 分组图标隐藏（浮云「隐藏分组图标」语义；无稳定字段/视图锚点 → 结构式实现，allowFailure）。
 *
 * 实现走数据层：朋友圈渲染咽喉点 snsInfoToSnsStruct 之后，对携带分组可见信息的 SnsObject
 * 在**克隆体**上把分组相关字段抹除（SnsObject 的分组字段每版重命名，按字段名包含 "group"
 * 反射探测，String 置空、Int 置 0）；克隆理由同互动过滤——SnsInfoStorageLogic 缓存同一
 * 实例，原地改动会写回持久层。探测不到任何分组字段时该帖直接放行，整段行为退化为无操作。
 */
@Feature(
    name = "分组图标隐藏",
    categories = ["密友功能"],
    description = "隐藏朋友圈帖子上的「分组可见」图标元素（结构式实现，微信版本字段漂移时自动跳过）"
)
object HideMomentsGroupIcon : SwitchFeature(), IResolveDex {

    private const val TAG = "HideMomentsGroupIcon"

    private val methodSnsInfoToSnsStruct by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")
        }
    }

    override fun onEnable() {
        if (methodSnsInfoToSnsStruct.isPlaceholder) {
            WeLogger.w(TAG, "snsInfoToSnsStruct 未解析, 分组图标不过滤")
            return
        }

        val parseFromMethod = runCatching {
            SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
        }.getOrElse {
            WeLogger.w(TAG, "failed to resolve SnsObject.parseFrom; group icon hiding unavailable", it)
            return
        }

        methodSnsInfoToSnsStruct.hookBefore {
            if (SecretFriendState.isTemporarilyShown()) return@hookBefore
            val original = args.getOrNull(1) as? SnsObject ?: return@hookBefore

            val groupFields = original.reflekt().fields {
                name { it.contains("group", ignoreCase = true) }
            }.map { it.self.makeAccessible() }
            if (groupFields.isEmpty()) return@hookBefore

            val hasGroupMark = groupFields.any { field ->
                when (val value = runCatching { field.get(original) }.getOrNull()) {
                    null -> false
                    is String -> value.isNotEmpty()
                    is Number -> value.toInt() != 0
                    else -> false
                }
            }
            if (!hasGroupMark) return@hookBefore

            // 克隆上抹除（原地改动会写回 SnsInfoStorageLogic 持久缓存）
            val clone = SnsObject().also { parseFromMethod.invoke(it, original.toByteArray()) }
            groupFields.forEach { field ->
                when (field.type) {
                    BString -> field.set(clone, "")
                    Int::class.javaPrimitiveType, Int::class.java -> field.setInt(clone, 0)
                    else -> {}
                }
            }
            WeLogger.d(TAG, "stripped group visibility marker from a moments post")
            args[1] = clone
        }
    }
}
