package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.Editable
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.graphics.toColorInt
import java.lang.ref.WeakReference
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeXmlParserApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.utils.findViewsWhich
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.idString
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.WeColorField
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.formatEpoch
import kotlin.math.roundToInt

@Feature(name = "防撤回", categories = ["聊天"], description = "阻止撤回消息，原消息保留并标记已撤回")
object AntiMessageRecall : ClickableFeature(), WeXmlParserApi.IAfterParseListener,
    WeChatMessageViewApi.ICreateViewWithListListener {

    private const val TAG = "AntiMessageRecall"

    private const val STYLE_NOTICE = 0
    private const val STYLE_BADGE = 1

    /** 已撤回消息的 msgSvrId 落盘键。落盘才能让重启微信后老消息也补上标签。 */
    private const val KEY_RECALLED_IDS = "recall_recalled_ids"

    /** 落盘上限：超出后只保留最近这些条，避免这个集合无限增长。 */
    private const val MAX_STORED_IDS = 2000

    /** 诊断计数：只用来说明 onCreateView 有没有在跑（每 50 次打一条）。 */
    private var diagCount = 0


    /**
     * 挂在昵称行 TextView 上的 tag key。
     * 用 0x7E00xxxx 段（仓库惯例，见 DisplayGroupMemberRealName 的 VIEW_TAG_SENDER），
     * 避开宿主自己可能占用的 id。
     */
    /** 真机确认：私聊消息正文控件的资源 id 名。 */
    private const val CONTENT_VIEW_ID = "bkl"

    /** 真机确认：头像控件的资源 id 名（ChattingAvatarImageView）。 */
    private const val AVATAR_VIEW_ID = "bk1"

    /** 挂在头像标签 TextView 上的 tag，用来做幂等检查（已贴过就不重复贴）。 */
    private const val TAG_AVATAR_BADGE = 0x7E000004

    private const val TAG_RECALLED = 0x7E000002
    private const val TAG_WATCHER = 0x7E000003

    private var recallOutgoing by prefOption("recall_outgoing", false)

    /** 0 = 旧样式（插入「已阻止」提示消息）；1 = 新样式（原消息保留 + 昵称后标签） */
    private var styleMode by prefOption("recall_style_mode", STYLE_BADGE)

    // --- 旧样式（提示消息）---
    private var pattern by prefOption("recall_pattern", $$"「$sender」尝试撤回上一条消息 (已阻止)")
    private var timeFormat by prefOption("recall_time_format", "yyyy/MM/dd HH:mm:ss")

    // --- 新样式（昵称后标签）---
    private var badgeText by prefOption("recall_badge_text", "已撤回")
    private var badgeTextColor by prefOption("recall_badge_text_color", "#FFFFFFFF")
    private var badgeBgColor by prefOption("recall_badge_bg_color", "#FFFF3B30")

    /** 从旧样式的撤回提示语里抠出「是谁撤回的」。 */
    private val NAME_REGEX = Regex("([\"「])(.*?)([」\"])")

    /**
     * 被撤回消息的 msgSvrId 集合（新样式用它给消息行打角标）。
     *
     * **必须落盘**：原先只用内存 Set，重启微信就清空 —— 于是「重启前撤回过的消息」
     * 永远补不上标签（用户实测反馈：历史撤回的消息没有「已撤回」）。
     * 撤回是低频事件，集合增长很慢；仍设上限防止无限膨胀（见 [trimStoredIds]）。
     */
    /**
     * 被撤回消息的 msgSvrId 集合（新样式用它给消息行打角标）。
     *
     * **内存缓存 + 落盘双写**：
     *  - 落盘：重启微信后老消息仍能补上标签（用户实测反馈：历史撤回的没有标签）
     *  - 内存：消息列表滚动时**每行 bind 都要查集合**，若每次都读 prefs 会明显拖慢滚动；
     *    查内存则是纯 Set 查找，快且稳。
     *
     * 缓存由 [onEnable] 从落盘载入，[rememberRecalledId] 双写。
     */
    @Volatile
    private var recalledCache: MutableSet<String>? = null

    private val recalledMsgSvrIds: MutableSet<String>
        get() {
            recalledCache?.let { return it }
            val loaded = WePrefs.getStringSetOrDef(KEY_RECALLED_IDS, emptySet())
                .toMutableSet()
            recalledCache = loaded
            return loaded
        }

    private fun rememberRecalledId(msgSvrId: String) {
        val cur = recalledMsgSvrIds
        synchronized(cur) {
            if (msgSvrId in cur) return
            cur.add(msgSvrId)
            WePrefs.putStringSet(KEY_RECALLED_IDS, trimStoredIds(cur.toSet()))
        }
    }

    /**
     * 判断某条消息是否被撤回。
     *
     * **双路匹配**：撤回事件里的 id 来自 XML（`newmsgid`，String），
     * 行渲染时拿到的是 `msgInfo.serverId`（Long）。两者格式/前导零/类型若有差异就匹配不上，
     * 所以两个方向都试一次，任一中即命中。
     */
    private fun isRecalled(serverId: Long, msgSvrId: String): Boolean {
        val set = recalledMsgSvrIds
        return msgSvrId in set || serverId.toString() in set
    }

    /**
     * 只保留最近 [MAX_STORED_IDS] 条。
     *
     * 撤回记录只用来给「当前还看得到的消息」打标签，太久远的消息早已滚出聊天记录，
     * 留着只是占空间。集合无序，这里按大小截断，代价可接受。
     */
    private fun trimStoredIds(ids: Set<String>): Set<String> =
        if (ids.size <= MAX_STORED_IDS) ids else ids.toList().takeLast(MAX_STORED_IDS).toSet()

    /**
     * 当前聊天页的消息列表。撤回事件到达时要靠它强制重绑那一行 ——
     * 用 WeakReference 是因为这个列表属于微信的 Activity，模块不该拖住它不让回收。
     *
     * 类型用 ViewGroup 而不是 RecyclerView：模块没有 androidx.recyclerview 依赖，
     * 而且消息列表在 8.0.78 里是混淆类，只当作「有 getAdapter 的容器」用更稳。
     */
    private var activeListRef: WeakReference<ViewGroup>? = null

    private const val TYPE_KEY = $$".sysmsg.$type"

    private val isBadgeStyle get() = styleMode == STYLE_BADGE

    override fun onEnable() {
        // 先把落盘的撤回家集合载入内存缓存，避免每行 bind 都读 prefs。
        recalledCache = WePrefs.getStringSetOrDef(KEY_RECALLED_IDS, emptySet()).toMutableSet()
        WeLogger.i(TAG, "onEnable: style=$styleMode 撤回家集合大小=${recalledCache?.size}")
        WeXmlParserApi.addListener(this)
        WeChatMessageViewApi.addListener(this)
    }


    override fun onDisable() {
        WeXmlParserApi.removeListener(this)
        WeChatMessageViewApi.removeListener(this)
        // 刻意不清 KEY_RECALLED_IDS：落盘就是为了让重启后老消息仍能补上标签，
        // 关掉功能再打开不该把历史撤回记录一起抹掉。
        activeListRef = null
    }

    override fun onParse(param: XC_MethodHook.MethodHookParam, result: MutableMap<String, Any?>) {
        val xmlContent = param.args[0] as? String ?: ""
        val rootTag = param.args[1] as? String ?: ""

        if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) return
        if (result[TYPE_KEY] != "revokemsg") return

        val newMsgId = result[".sysmsg.revokemsg.newmsgid"] as? String ?: return
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT type,content,talker,createTime,lvbuffer,msgId,msgSvrId,isSend FROM message WHERE msgSvrId = ?",
            arrayOf(newMsgId)
        )

        cursor.use { c ->
            if (!c.moveToFirst()) return
            val msgInfo = MessageInfo(WeMessageApi.convertMsgInfoInstanceFromCursor(c))

            // 自己发的消息且未开启「防撤回自己的消息」时，一律放行
            if (msgInfo.isSelfSender && !recallOutgoing) return

            // 清空撤回事件：屏蔽微信原生「xx 撤回了一条消息」提示
            result[TYPE_KEY] = null

            if (isBadgeStyle) {
                // 新样式：原消息留在库里照常渲染，只记下 msgSvrId 供行渲染时打标签。
                // 落盘（不是纯内存），否则重启微信后老消息就补不上标签了。
                rememberRecalledId(newMsgId)
                WeLogger.i(TAG, "blocked revoke (badge), msgSvrId=$newMsgId")
                // 该行已经渲染完了，不重绑就不会再走 onCreateView —— 必须主动刷新
                refreshMessageList()
            } else {
                // 旧样式：额外插入一条系统消息作为拦截提示
                val replaceMsg = result[".sysmsg.revokemsg.replacemsg"] as? String ?: return
                val match = NAME_REGEX.find(replaceMsg)
                val senderName = match?.groupValues?.get(2)
                    ?: if (recallOutgoing) "自己" else return

                val interceptNotice = pattern
                    .replace($$"$sender", senderName)
                    .replace($$"$sendTime", formatEpoch(msgInfo.createTime, timeFormat))
                    .replace($$"$recallTime", formatEpoch(System.currentTimeMillis(), timeFormat))
                    .replace($$"$content", msgInfo.humanReadableRepr)

                // 时间戳用 createTime + 1，比原消息晚 1ms，保证排在原文之后
                WeMessageApi.createSimpleMsgInfoAndInsert(
                    MessageType.SYSTEM.code,
                    msgInfo.talker,
                    interceptNotice,
                    msgInfo.createTime + 1
                )
                WeLogger.i(TAG, "blocked revoke (notice), msgSvrId=$newMsgId")
            }
        }
    }

    override fun onCreateViewWithList(
        param: XC_MethodHook.MethodHookParam, view: View, messageList: ViewGroup
    ) {
        if (!isBadgeStyle) return

        // 先记住这条消息所属的消息列表，再谈别的。
        // 不能放在下面任何 return 之后：serverId<=0 的行（本地/系统消息）会提前返回，
        // 若把记录列表放在其后，这些行一多就永远拿不到 activeListRef，
        // 撤回时就无法重绑列表 —— 表现为私聊标签完全不显示。
        rememberList(messageList)

        // 先清掉本行残留的旧标签。
        //
        // 必须放在下面 serverId 判断**之前**：serverId<=0 的本地/系统消息（时间分割行、
        // “xx 撤回了一条消息”提示等）也会被分配到这个容器，若提前 return，
        // 上一条消息留下的标签就会一直挂在这些行上。
        clearAvatarBadge(view)

        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val msgSvrId = msgInfo.serverId.takeIf { it > 0 }?.toString() ?: return
        val recalled = isRecalled(msgInfo.serverId, msgSvrId)

        // 只对「在撤回集合里」的行打日志，避免每次滚动都刷屏；
        // 同时留一条低频的存在性证据，确认 onCreateView 真的在跑。
        if (recalled) {
            WeLogger.i(TAG, "命中撤回集合: msgSvrId=$msgSvrId group=${msgInfo.isInGroupChat}")
        } else if (diagCount++ % 50 == 0) {
            WeLogger.i(TAG, "onCreateView 活动确认(每50次): msgSvrId=$msgSvrId 集合大小=${recalledMsgSvrIds.size}")
        }

        applyToRow(view, msgInfo, recalled)
    }

    /**
     * 直接使用框架从 ViewHolder 字段里取出的消息列表。
     *
     * **不要**再从 view 往上爬父链找列表：`[onBindView]` 触发时 itemView 还没挂到
     * 列表上，`view.parent` 恒为 null（实测 `parent=null`），爬父链必然失败。
     * 列表只有从 holder 的 `mOwnerRecyclerView` / `m` 字段才拿得到。
     */
    private fun rememberList(messageList: ViewGroup) {
        activeListRef = WeakReference(messageList)
    }

    /**
     * 强制消息列表重绑，让 [applyToRow] 有机会给刚被撤回的那行打上标签。
     *
     * **双次重绑**：微信收到撤回后会异步做若干事（删本地行、刷新气泡状态等），
     * 只绑一次可能被它随后的动作覆盖掉，标签又消失。所以立刻绑一次、
     * 300ms 后再绑一次，覆盖异步窗口。
     *
     * 走 notifyDataSetChanged 而非局部刷新：微信 adapter 是混淆类，
     * 局部刷新的方法名/参数都不可靠；撤回是低频事件，全量刷新代价可接受
     * （仓库既有做法，见 ConversationAggregation）。
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun refreshMessageList() {
        rebindNow()
        // 等宿主异步动作稳定后再绑一次
        val list = activeListRef?.get()
        list?.postDelayed({ rebindNow() }, 300L)
    }

    private fun rebindNow() {
        val list = activeListRef?.get()
        if (list == null) {
            WeLogger.w(TAG, "refreshMessageList: 列表引用为空，跳过重绑")
            return
        }
        list.post {
            runCatching {
                val adapter = findAdapter(list) ?: return@runCatching
                val m = findNoArgMethod(adapter, "notifyDataSetChanged")
                    ?: throw NoSuchMethodException("notifyDataSetChanged on ${adapter.javaClass.name}")
                m.invoke(adapter)
                WeLogger.i(TAG, "消息列表已重绑（打撤回标签）")
            }.onFailure { WeLogger.w(TAG, "重绑消息列表失败: $it") }
        }
    }

    /**
     * 取列表的 adapter。
     *
     * **必须健壮**：`getMethod("getAdapter")` 只找 public 方法且签名精确匹配，
     * 微信的列表类是混淆的，方法可能非 public 或带参数 —— 一旦失败整条重绑链就断，
     * 表现就是「标签不显示」。这里 public → 任意同名无参方法 → 字段 三级兜底。
     */
    private fun findAdapter(list: Any): Any? {
        // 1) 标准 RecyclerView API
        runCatching { list.javaClass.getMethod("getAdapter").invoke(list) }
            .getOrNull()?.let { return it }
        // 2) 任意同名无参方法（可能是非 public）
        runCatching {
            list.javaClass.methods.firstOrNull {
                it.name == "getAdapter" && it.parameterCount == 0
            }?.invoke(list)
        }.getOrNull()?.let { return it }
        // 3) 字段兜底：名字里带 adapter 的字段
        var c: Class<*>? = list.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 6) {
            c.declaredFields.forEach { f ->
                if (f.name.contains("dapter")) {
                    runCatching {
                        f.isAccessible = true
                        return f.get(list)
                    }
                }
            }
            c = c.superclass
        }
        WeLogger.w(TAG, "取 adapter 失败: ${list.javaClass.name}")
        return null
    }

    /** 找无参同名方法（含非 public）。 */
    private fun findNoArgMethod(target: Any, name: String): java.lang.reflect.Method? =
        runCatching { target.javaClass.getMethod(name) }.getOrNull()
            ?: target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }

    /**
     * 在消息行上加「已撤回」标签。
     *
     * **只改 TextView 的文本，不往布局树里 addView。** 微信消息行是 RelativeLayout，
     * 气泡用 toLeftOf/toRightOf 锚定到头像容器；插入新子 View 会破坏锚点，
     * 表现为 RelativeLayout.onMeasure 抛 NPE（`View.getVisibility()` on null）——实测直接闪退。
     * 改文本走的是 TextView 自己的重绘路径，完全不参与布局锚定，安全。
     *
     * 承载位置分两种：
     *  - **群聊**：昵称行 `userTV`。标签与「成员/群主」身份标签同处一个 TextView，
     *    天然同基线同字号（圆角参数也照 [DisplayGroupMemberRoles] 对齐）。
     *  - **私聊**：没有昵称行（`userTV` 为 GONE/空白），改挂到消息内容文本后。
     */
    private fun applyToRow(view: View, msgInfo: MessageInfo, recalled: Boolean) {
        // 注：残留标签的清理已由调用方（onCreateViewWithList）在当前 bind 开头无条件完成，
        // 那里能覆盖 serverId<=0 提前 return 的行，这里不再重复。
        if (!recalled) return

        WeLogger.i(TAG, "打标签: msgSvrId=${msgInfo.serverId} inGroup=${msgInfo.isInGroupChat}")

        // 群聊与私聊统一走**头像上方**标签。
        //
        // 为什么不再写正文/昵称 TextView：
        //  - 文字消息正文是 `MMNeat7extView`，它**不是 TextView**，真正的文本控件藏在
        //    内部字段里（宿主专有、stub 里没有），反射极不稳定；
        //  - 图片/语音/文件等消息的正文控件各不相同，逐个适配没有尽头；
        //  - 昵称行只有群聊有，且不同版本可见性/文本赋值时机不一；
        //  - 头像容器每行都有、id 固定，且与消息类型、群聊私聊都无关。
        attachAvatarBadge(view)
    }

    /**
     * 在**头像上方**贴一个「已撤回」标签。群聊与私聊统一走这条路。
     *
     * 为什么不写正文/昵称 TextView：
     *  - 文字消息正文是 `MMNeat7extView`，它**不是 TextView**，真正的文本控件藏在
     *    内部字段里（宿主专有、stub 里没有），反射极不稳定；
     *  - 图片/语音/文件等消息的正文控件各不相同，逐个适配没有尽头；
     *  - 昵称行只有群聊有，且不同版本可见性/文本赋值时机不一；
     *  - 头像容器每行都有、id 固定，且与消息类型、群聊私聊都无关。
     *
     * 实现方式：往头像的**父容器**（`MaskLayout`）里 `addView` 一个 TextView，
     * 用 `FrameLayout.LayoutParams` 定位到头像**上方**。
     *
     * **只能加到头像的父容器，不能加进消息行根布局** —— 消息行是 RelativeLayout，
     * 气泡靠 toLeftOf/toRightOf 锚定，插入子 View 会破坏锚点，
     * 触发 `RelativeLayout.onMeasure` 的 NPE（`View.getVisibility()` on null，实测闪退）。
     */
    /**
     * 清掉本行残留的撤回标签。
     *
     * RecyclerView 复用行容器时，上一条消息 `addView` 进去的标签会留在容器里，
     * 所以每次 bind 都要先清，否则标签会「粘」在容器上而跟错消息。
     *
     * 直接从整行 view 搜 tag：标签就在本行子树里，不必先定位头像容器，
     * 少一次找头像的遍历（滚动时每行 bind 都要跑，开销敏感）。
     */
    private fun clearAvatarBadge(view: View) {
        val badge = view.findViewWithTag<View>(TAG_AVATAR_BADGE) ?: return
        (badge.parent as? ViewGroup)?.removeView(badge)
    }

    /**
     * 找到本行的头像容器（用来挂标签）。
     *
     * 按 id 名找头像（真机确认 ChattingAvatarImageView 的 id 名是 bk1）。
     * 用运行时 id 名而不是 `it is ChattingAvatarImageView`：模块编译期看到的是 stub，
     * 与宿主真实类 classloader 不同，类型判断可能恒为 false。
     */
    private fun findAvatarContainer(view: View): ViewGroup? {
        val avatar = view.findViewsWhich<View> { it.idString == AVATAR_VIEW_ID }.firstOrNull()
            ?: view.findViewWhich<View> { it.javaClass.simpleName.contains("AvatarImage") }
            ?: return null
        val container = avatar.parent as? ViewGroup ?: return null

        // 容器若有 clipChildren，子 View 超出部分会被裁掉。
        // 头像容器（MaskLayout）实测会裁 —— 标签贴在顶部边缘时上下会被切。
        // 关掉它（只影响这一层，不动消息行根布局），让标签能完整显示。
        container.clipChildren = false
        container.clipToPadding = false
        return container
    }

    private fun attachAvatarBadge(view: View) {
        val container = findAvatarContainer(view) ?: run {
            WeLogger.w(TAG, "头像标签：找不到头像控件或父容器不是 ViewGroup（id=$AVATAR_VIEW_ID）")
            return
        }

        // 幂等：同一行重复 bind 时不要叠一堆标签
        val existing = container.findViewWithTag<View>(TAG_AVATAR_BADGE)
        if (existing != null) {
            if ((existing as? TextView)?.text?.toString() == badgeText) return
            container.removeView(existing)
        }

        val label = TextView(view.context).apply {
            text = badgeText
            setTextColor(badgeTextColor.toColorIntOr(DEFAULT_FG))
            textSize = 10f
            // 角标贴着头像顶部居中，左右不受容器裁剪 —— 所以自身不能有横向 padding
            // 之外的额外宽度约束，且要允许文本完整换行显示。
            setPadding(12, 3, 12, 3)
            includeFontPadding = false
            isSingleLine = true
            gravity = Gravity.CENTER
            tag = TAG_AVATAR_BADGE
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f
                setColor(badgeBgColor.toColorIntOr(DEFAULT_BG))
            }
        }
        container.addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // 贴着头像顶部居中，**完全落在容器内**。
                // 之前用 topMargin=-6 想把标签压到头像上沿之上，结果超出父容器可绘制区域，
                // 被裁掉上下两截（实测截图里文字只剩中间一条）。
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 0
            }
        )
        WeLogger.i(TAG, "头像标签已贴: 容器=${container.javaClass.simpleName}")
    }

    /** 解析颜色，失败时用兜底色（用户可能填了非法色值）。 */
    private fun String.toColorIntOr(fallback: Int): Int =
        runCatching { toColorInt() }.getOrDefault(fallback)

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var styleInput by remember { mutableStateOf(styleMode) }
            var recallOutgoingInput by remember { mutableStateOf(recallOutgoing) }
            var patternInput by remember { mutableStateOf(pattern) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            var badgeTextInput by remember { mutableStateOf(badgeText) }
            var badgeTextColorInput by remember { mutableStateOf(badgeTextColor) }
            var badgeBgColorInput by remember { mutableStateOf(badgeBgColor) }

            val badgeMode = styleInput == STYLE_BADGE

            AlertDialogContent(
                title = { Text("防撤回") },
                text = {
                    // 必须 scrollable：正文区高度上限 400dp（AlertDialogContent 里 heightIn），
                    // 开启新样式时有 3 个输入框（标签文字/背景色/文字色），总高超过上限后
                    // 不滚动就会被底部「确认/取消」按钮栏盖住 —— 表现为最后两个颜色框
                    // 被遮住、看不清。用户实测反馈过。
                    DefaultColumn(scrollable = true) {
                        ListItem(
                            modifier = Modifier.clickable { recallOutgoingInput = !recallOutgoingInput },
                            trailingContent = { Switch(checked = recallOutgoingInput, onCheckedChange = null) },
                            supportingContent = { Text("是否对自己发出的消息也生效") },
                            headlineContent = { Text("防撤回自己的消息") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                styleInput = if (badgeMode) STYLE_NOTICE else STYLE_BADGE
                            },
                            trailingContent = { Switch(checked = badgeMode, onCheckedChange = null) },
                            supportingContent = {
                                Text("开启：保留原消息并在昵称后标记「已撤回」；关闭：插入一条自定义提示消息")
                            },
                            headlineContent = { Text("新样式（原消息保留 + 昵称标签）") },
                        )

                        if (badgeMode) {
                            TextField(
                                value = badgeTextInput,
                                onValueChange = { badgeTextInput = it },
                                label = { Text("标签文字") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            WeColorField(
                                label = "标签背景色",
                                value = badgeBgColorInput,
                                onValueChange = { badgeBgColorInput = it }
                            )
                            WeColorField(
                                label = "标签文字色",
                                value = badgeTextColorInput,
                                onValueChange = { badgeTextColorInput = it }
                            )
                        } else {
                            TextField(
                                value = patternInput,
                                onValueChange = { patternInput = it },
                                label = { Text("提示格式") },
                                supportingText = {
                                    Text($$"可使用占位符 $sender, $sendTime, $recallTime, $content")
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextField(
                                value = timeFormatInput,
                                onValueChange = { timeFormatInput = it },
                                label = { Text("时间格式") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button({
                        styleMode = styleInput
                        recallOutgoing = recallOutgoingInput
                        pattern = patternInput
                        timeFormat = timeFormatInput
                        badgeText = badgeTextInput
                        badgeTextColor = badgeTextColorInput
                        badgeBgColor = badgeBgColorInput
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }

    private const val DEFAULT_BG = 0xFFFF3B30.toInt()
    private const val DEFAULT_FG = 0xFFFFFFFF.toInt()

    /** 与「成员/群主」身份标签完全一致的圆角与内边距，见 [DisplayGroupMemberRoles]。 */
    private const val CORNER_RADIUS = 16f
    private const val PADDING = 10f
}

/**
 * 圆角背景标签。参数与 [DisplayGroupMemberRoles] 内的同名类保持一致，
 * 这样「已撤回」和「成员」标签看起来是同一套视觉。
 */
private class RecallBadgeSpan(
    private val backgroundColor: Int,
    private val textColor: Int,
    private val cornerRadius: Float = 16f,
    private val padding: Float = 10f,
    /** 背景上下外扩量。比左右 padding 小，避免撑高行距。 */
    private val paddingV: Float = 3f
) : ReplacementSpan() {

    /**
     * 返回标签所需宽度。
     *
     * 同时**必须**把 [fm] 的上下边界撑开：昵称行的行高是按普通文字定的，
     * 而圆角背景比文字高一截，不改 fm 的话背景上下会被行高裁掉
     * （表现为「背景色显示不全」，上下各缺一角）。
     */
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val metrics = paint.fontMetricsInt
            // 背景上下各外扩 paddingV，让圆角矩形完整落在行内
            fm.ascent = metrics.ascent - paddingV.toInt()
            fm.descent = metrics.descent + paddingV.toInt()
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        return (paint.measureText(text, start, end) + padding * 2).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = paint.measureText(text, start, end)
        // 背景比文字行高向上、向下各外扩 paddingV，与 getSize 的 fm 调整保持一致。
        // 不要直接用传入的 top/bottom —— 那是行高，会把圆角压扁。
        val rect = RectF(
            x,
            y + paint.fontMetrics.ascent - paddingV,
            x + width + padding * 2,
            y + paint.fontMetrics.descent + paddingV
        )

        paint.color = backgroundColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        paint.color = textColor
        canvas.drawText(text, start, end, x + padding, y.toFloat(), paint)
    }
}
