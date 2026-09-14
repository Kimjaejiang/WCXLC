package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookAfterDirectly
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.android.showToast
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.lang.reflect.Method

/**
 * 「新的朋友」虚拟项。
 *
 * 在微信「通讯录 → 新的朋友」列表里注入若干条**纯显示层**的虚拟好友申请，
 * 按「近三天 / 三天前」分组呈现。长按标题栏右侧的「添加朋友」按钮可配置。
 *
 * ── 实现要点（微信 8.0.78 / versionCode 3180 实机验证）──────────────────────
 * - 页面:       com.tencent.mm.plugin.subapp.ui.friend.FMessageConversationUI
 * - 适配器:     Activity 的 `e` 字段，类型 qn4.x1
 * - 行对象:     com.tencent.mm.storage.l7，业务字段继承自父类 f3
 * - 分组依据:   field_lastModifiedTime（long，毫秒）—— 微信按它分「近三天 / 三天前」
 * - 长按入口:   标题栏 Toolbar 内 TextView id=fu（文案「添加朋友」，longClickable=true）
 *
 * ── 为什么这样注入 ──────────────────────────────────────────────────────────
 * 列表**不走 SQLite**（曾用探针实测：进入页面时完全没有 fmessage_conversation 查询），
 * 因此不改数据库、也不改写 SQL，而是 hook 适配器的 getCount()/getItem()：
 * getCount() 多报 N 行，getItem() 把越界下标映射到我们构造的虚拟行。
 * 微信的渲染路径完全不变，它拿到的仍是一个「正常」的行对象。
 *
 * 虚拟行本身**不是凭空 new 的**——行对象是数据库实体（父类 IAutoDBItem），
 * 字段极多且互相引用，凭空构造几乎必然在渲染时 NPE。这里改为克隆列表里的
 * 一个真实行，再只改我们关心的字段，其余字段保持合法值。
 *
 * 若列表为空（没有任何真实好友申请），就没有模板可用，此时不注入——
 * 这比注入一个字段残缺、可能让微信崩溃的对象要安全。
 */
@Feature(
    name = "新的朋友虚拟项",
    categories = ["联系人与群组"],
    description = "在「新的朋友」列表注入虚拟条目，按近三天/三天前分组；长按标题栏「添加朋友」配置"
)
object VirtualNewFriends : ClickableFeature() {

    /** 默认开启——用户要的是装完就能用；仍可在模块设置里关掉。 */
    override val defaultEnabled: Boolean = true
    private const val TAG = "VirtualNewFriends"

    private const val UI_CLASS = "com.tencent.mm.plugin.subapp.ui.friend.FMessageConversationUI"
    private const val ADAPTER_FIELD_TYPE = "qn4.x1"

    /** 标题栏内「添加朋友」按钮的资源 id 名。找不到时本功能静默降级（不注入、无入口）。 */
    private const val ANCHOR_VIEW_ID_NAME = "fu"
    private const val TOOLBAR_VIEW_ID_NAME = "ef"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 验证语长度超过这个值就认为在行上会被截断，点击时弹全文。
     *
     * 行上留给验证语的宽度大约能放下 12~14 个汉字，取保守值：
     * 宁可多弹一次，也不要把本来被截断的文本漏掉。
     */
    private const val VERIFY_TRUNCATE_HINT = 12

    /** 把行里所有 TextView 的 id 名、文本与尺寸打出来，用于校正文本覆盖的目标。 */
    private fun dumpTextViews(root: View, position: Int) {
        val out = ArrayList<android.widget.TextView>()
        fun walk(v: View) {
            if (v is android.widget.TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        val desc = out.joinToString(" | ") { tv ->
            val idName = runCatching { tv.resources.getResourceEntryName(tv.id) }.getOrNull() ?: "-"
            "$idName('${tv.text}')"
        }
        WeLogger.i(TAG, "getView pos=$position TextViews=[$desc]")
    }

    /** 诊断开关：打印真实行的全部字符串字段，用于校正字段名。定位完置回 false。 */
    private const val DUMP_ROW_FIELDS = true

    /**
     * 把覆盖的昵称 / 验证语·申请语写到行的 TextView 上。
     *
     * 行布局实测（8.0.78）四个关键 TextView：
     *   g9y = 分组标题（近三天 / 三天前），g_4 = 昵称，
     *   g_0 = 验证语·申请语（同一位，两种场景共用）
     * 按 id 名精确定位——按字号或长度猜会把「展开」「等待验证」当成正文。
     *
     * 验证语和申请语是同一个控件，到底显示哪个由**行本身**决定：
     * 原生文本以「我: 」开头说明是我加别人（应由申请语接管），否则是别人加我（用验证语）。
     */
    private fun applyCustomText(
        root: View,
        talker: String,
        override: VirtualNewFriendsConfig.Override,
    ) {
        val nicknameTv = findTextView(root, TEXT_ID_NICKNAME)
        val contentTv = findTextView(root, TEXT_ID_VERIFY)
        if (nicknameTv == null && contentTv == null) return

        val raw = synchronized(rawTexts) {
            rawTexts.getOrPut(talker) {
                RawText(
                    nickname = nicknameTv?.text?.toString().orEmpty(),
                    content = contentTv?.text?.toString().orEmpty(),
                )
            }
        }

        // 昵称：覆盖为空时回写原值，避免行复用后残留上一行的名字。
        if (nicknameTv != null) {
            nicknameTv.text = override.nickname.ifBlank { raw.nickname }
        }

        if (contentTv != null) {
            // 显示验证语还是申请语，取决于这行属于哪种场景：
            //  - 虚拟项：由配置里的 isMine 指定；
            //  - 真实项（isMine == null）：看**原生**文本，微信会在「我加别人」的文案前加「我: 」。
            val mine = override.isMine ?: raw.content.startsWith(APPLY_PREFIX)
            val content = if (mine) override.applyContent else override.verifyContent
            contentTv.text = content.ifBlank { raw.content }
        }
    }

    /** 按资源 id 名在行里找 TextView。 */
    private fun findTextView(root: View, idName: String): android.widget.TextView? {
        var found: android.widget.TextView? = null
        fun walk(v: View) {
            if (found != null) return
            if (v is android.widget.TextView && resourceEntryName(v) == idName) {
                found = v
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

    /** 按 talker 缓存的、未经我们修改的原生文本。 */
    private data class RawText(val nickname: String, val content: String)

    private val rawTexts = HashMap<String, RawText>()

    /** 行里昵称 TextView 的 id 名（实测 8.0.78）。 */
    private const val TEXT_ID_NICKNAME = "g_4"

    /** 行里验证语·申请语 TextView 的 id 名（两种场景共用这一个控件）。 */
    private const val TEXT_ID_VERIFY = "g_0"

    /** 微信在「我加别人」的文案前加的前缀，用来区分行属于哪种场景。 */
    private const val APPLY_PREFIX = "我: "

    /** 行里头像 ImageView 的资源 id 名（实测 8.0.78：cgi 是头像，hgx 是右侧图标）。 */
    private const val AVATAR_VIEW_ID_NAME = "cgi"

    /** ImageView 上的 tag，记录当前虚拟/覆盖项应当显示的自定义头像属于哪个 talker。 */
    private val AVATAR_TAG = "wcx_virtual_avatar".hashCode()

    /** TextView 上的 tag，保存该控件的**原生文本**，用作覆盖时的基准。 */
    private val TEXT_TAG_RAW = "wcx_virtual_text_raw".hashCode()

    /** TextView 上的 tag，标记这个控件的文本已被我们改过。 */
    private val TEXT_TAG_OURS = "wcx_virtual_text_ours".hashCode()

    /** 记录 ImageView 上一次实际绑定的图片路径，避免重复解码。 */
    private val AVATAR_TAG_BOUND_PATH = "wcx_virtual_avatar_path".hashCode()

    /** 标记这个 ImageView 当前是被我们改过的。 */
    private val AVATAR_TAG_HAS_CUSTOM = "wcx_virtual_avatar_has".hashCode()

    /** 重入保护：防止我们自己设图时触发 setImageDrawable hook。 */
    private val inCustomAvatar = ThreadLocal.withInitial { false }

    /** 需要在每次渲染时读到的当前配置。由用户操作后写入。 */
    @Volatile
    private var config: VirtualNewFriendsConfig.Config = VirtualNewFriendsConfig.Config()

    /** 适配器上的两个关键方法，注入时要用。 */
    private var getCountMethod: Method? = null
    private var getItemMethod: Method? = null

    /** 作为克隆来源的真实行。为空表示当前列表里没有可用模板。 */
    private var templateRow: Any? = null

    /** 已安装 hook 的适配器实例，避免重复安装。 */
    private var hookedAdapter: Any? = null

    /**
     * 已安装 hook 的适配器**类**。
     *
     * hook 是挂在方法上的，同一个类只能装一次——重进页面时微信可能新建一个适配器
     * 实例，类还是同一个，此时不能再装一遍，否则 getCount 会被叠加多次。
     */
    private var hookedAdapterClass: Class<*>? = null

    /**
     * 正在从适配器上抓克隆模板，或读取真实行原值。
     *
     * 这些内部读取会反射调用 getItem，而 getItem 已被我们 hook——不加这个标志
     * 就会递归调用自身，轻则行为不可预测，重则让 getItem 返回 null 使微信崩溃。
     */
    @Volatile
    private var isGrabbingTemplate = false

    override fun onEnable() {
        config = VirtualNewFriendsConfig.load()
        WeLogger.i(
            TAG,
            "enabled: recent=${config.recent.size} older=${config.older.size} " +
                "olderAsRecent=${config.olderAsRecent} overrides=${config.overrides.size}"
        )

        hookPage()
        // 反覆盖 hook 与页面无关，全局只需装一次：
        // 微信的头像加载在任意页面都可能发生。
        hookImageDrawableOverwrite()
    }

    // ── Hook 安装 ─────────────────────────────────────────────────────────────

    private fun hookPage() {
        runCatching {
            UI_CLASS.toClass().reflekt()
                .firstMethodOrNull { name == "initView" }
                ?.hookAfter {
                    val activity = thisObject as? Activity ?: return@hookAfter
                    // 布局尚未完成，等一帧再挂长按监听并安装适配器 hook。
                    activity.window?.decorView?.post {
                        runCatching { installLongPress(activity) }
                            .onFailure { WeLogger.e(TAG, "installLongPress failed", it) }
                        runCatching { installAdapterHooks(activity) }
                            .onFailure { WeLogger.e(TAG, "installAdapterHooks failed", it) }
                    }
                }
            WeLogger.i(TAG, "page hook installed")
        }.onFailure { WeLogger.e(TAG, "hookPage failed", it) }

        installClickIntercept()
    }

    /**
     * 拦截虚拟行的点击。
     *
     * 挂在 AdapterView.performItemClick 上：这是所有 ListView 行点击的公共入口。
     * 真实行直接放过，只有 talker 带虚拟前缀的行被吞掉并弹提示。
     *
     * 之所以要拦：虚拟行不在微信数据库里，点进去后各种详情页会拿 talker 去查数据，
     * 查不到轻则空白、重则异常。
     */
    private fun installClickIntercept() {
        runCatching {
            val perform = android.widget.AdapterView::class.java.getMethod(
                "performItemClick",
                View::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            perform.hookBeforeDirectly {
                val av = thisObject as? android.widget.AdapterView<*> ?: return@hookBeforeDirectly
                // 只管「新的朋友」那个列表，其它界面的行点击一律不干涉。
                val ctx = av.context
                if (ctx?.javaClass?.name != UI_CLASS) return@hookBeforeDirectly

                val position = args[1] as? Int ?: return@hookBeforeDirectly
                val adapter = av.adapter ?: return@hookBeforeDirectly
                if (position < 0 || position >= adapter.count) return@hookBeforeDirectly

                val item = adapter.getItem(position) ?: return@hookBeforeDirectly
                val talker = readTalker(item)
                if (!isVirtualTalker(talker)) return@hookBeforeDirectly

                WeLogger.i(TAG, "虚拟行被点击 pos=$position talker=$talker")
                // 验证语过长时在行上显示不全，点一下把全文弹出来。短的话什么都不做。
                showVerifyContentIfTruncated(ctx, talker)
                // performItemClick 返回 boolean：必须回 boolean。
                // 设 null 会让 Xposed 把 null 拆箱成基本类型而抛异常，直接闪退。
                // 返回 false 同时告诉 AdapterView「这次点击已被处理」。
                result = false
            }
            WeLogger.i(TAG, "点击拦截已安装")
        }.onFailure { WeLogger.e(TAG, "installClickIntercept failed", it) }
    }

    /**
     * 点击虚拟行时，若验证语被截断则弹全文。
     *
     * 微信的行布局只有一行位置放验证语，长文本会被 ellipsize 掉。
     * 用对话框把全文展示出来；短文本没什么可展开的，就不弹。
     */
    private fun showVerifyContentIfTruncated(context: android.content.Context, talker: String?) {
        val itemId = talker?.removePrefix(VirtualNewFriendsConfig.TALKER_PREFIX) ?: return
        val item = VirtualNewFriendsConfig.findById(config, itemId)?.first ?: return
        val text = item.verifyContent.ifBlank { "我是${item.nickname}" }

        // 与行上能显示的宽度粗略比较：一行大概能放下这么多字。
        if (text.length <= VERIFY_TRUNCATE_HINT) return

        runCatching {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text("验证语") },
                    text = {
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = {
                        TextButton(onDismiss) { Text("关闭") }
                    }
                )
            }
        }.onFailure { WeLogger.w(TAG, "展开验证语失败: ${it.message}") }
    }

    // ── 单项编辑器（长按头像）────────────────────────────────────────────────

    /**
     * 长按头像弹出的单项编辑界面：改这一项的**头像 / 昵称 / 验证语**。
     *
     * 虚拟项和真实好友申请走同一套编辑流程：
     * - 虚拟项的初始值来自它的定义；
     * - 真实项的初始值优先取已有的覆盖，没有则从行对象上读原值。
     *
     * 真实项改完只写入覆盖表，**不碰微信数据库**——列表和详情页都在渲染时查这张表。
     */
    private fun showItemEditorDialog(context: android.content.Context, talker: String) {
        val virtual = isVirtualTalker(talker)
        val currentOverride = VirtualNewFriendsConfig.findOverride(config, talker)

        // 这一项属于哪种场景，决定了弹窗里只显示哪一个输入框：
        //   别人加我 → 验证语；我加别人 → 申请语。
        // 虚拟项看配置里的 isMine；真实项看行上原生文案有没有「我: 」前缀
        // （getView 渲染时把原生文本缓存在 rawTexts 里）。
        val isMine = currentOverride?.isMine
            ?: synchronized(rawTexts) { rawTexts[talker] }
                ?.content
                ?.startsWith(APPLY_PREFIX)
            ?: false

        // 真实项且还没改过时，从行对象上把原值读出来当初始值，
        // 这样用户打开编辑器看到的是他当前看到的内容，而不是空框。
        val originNickname: String
        val originVerify: String
        val originApply: String
        if (virtual) {
            originNickname = ""
            originVerify = ""
            originApply = ""
        } else {
            val row = findRowByTalker(talker)
            originNickname = row?.let { readStringField(it, "field_contentNickname") }
                ?: row?.let { readStringField(it, "field_displayName") }.orEmpty()
            // 两种场景的文案在行对象上是不同字段，但界面只有一位，
            // 取不到具体字段时用缓存的原生文本（已去掉「我: 」前缀）。
            val raw = synchronized(rawTexts) { rawTexts[talker] }
            val rawContent = raw?.content.orEmpty().removePrefix(APPLY_PREFIX)
            originVerify = row?.let { readStringField(it, "field_contentVerifyContent") }
                .takeIf { !it.isNullOrBlank() }
                ?: rawContent.takeIf { !isMine }.orEmpty()
            originApply = row?.let { readStringField(it, "field_contentApplyContent") }
                .takeIf { !it.isNullOrBlank() }
                ?: rawContent.takeIf { isMine }.orEmpty()
        }

        val initialNickname = currentOverride?.nickname?.takeIf { it.isNotBlank() } ?: originNickname
        val initialVerify = currentOverride?.verifyContent?.takeIf { it.isNotBlank() } ?: originVerify
        val initialApply = currentOverride?.applyContent?.takeIf { it.isNotBlank() } ?: originApply
        val initialAvatar = currentOverride?.avatarPath.orEmpty()

        showComposeDialog(context) {
            var nickname by remember { mutableStateOf(initialNickname) }
            var verifyContent by remember { mutableStateOf(initialVerify) }
            var applyContent by remember { mutableStateOf(initialApply) }
            var avatarPath by remember { mutableStateOf(initialAvatar) }
            var mineState by remember { mutableStateOf(isMine) }

            AlertDialogContent(
                title = { Text(if (virtual) "编辑虚拟项" else "编辑好友申请") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = {
                                onDismiss()
                                selectAvatarImage(context, talker)
                            }) { Text(if (avatarPath.isBlank()) "选择头像" else "更换头像") }

                            if (avatarPath.isNotBlank()) {
                                TextButton(onClick = {
                                    avatarPath = ""
                                    updateItemField(talker) { it.copy(avatarPath = "") }
                                }) { Text("清除头像") }
                            }
                        }

                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("昵称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 虚拟项可以自己选场景（真实项的场景由它本身决定，不能改）。
                        if (virtual) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (isMine) "场景：我加别人" else "场景：别人加我",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(onClick = {
                                    mineState = !mineState
                                    updateItemField(talker) { it.copy(isMine = mineState) }
                                }) { Text("切换") }
                            }
                        }

                        // 只显示这一类场景真正对应的那个框：
                        // 别人加我 用验证语，我加别人 用申请语。
                        // 两个都摆出来只会让人改错地方（改了另一个，界面上没变化）。
                        if (mineState) {
                            OutlinedTextField(
                                value = applyContent,
                                onValueChange = { applyContent = it },
                                label = { Text("申请语（我加别人）") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = verifyContent,
                                onValueChange = { verifyContent = it },
                                label = { Text("验证语（别人加我）") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text(
                            "头像从相册选，只保存在模块配置里，不上传也不写入微信数据库。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        updateItem(talker, nickname, verifyContent, applyContent)
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    /**
     * 写入一项的昵称、验证语与申请语。
     *
     * 虚拟项写回它的定义，真实项写入覆盖表。两者都不碰微信数据库。
     */
    private fun updateItem(
        talker: String,
        nickname: String,
        verifyContent: String,
        applyContent: String,
    ) {
        val cfg = config
        val updated = if (isVirtualTalker(talker)) {
            val id = talker.removePrefix(VirtualNewFriendsConfig.TALKER_PREFIX)
            fun applyTo(item: VirtualNewFriendsConfig.Item) = item.copy(
                nickname = nickname,
                verifyContent = verifyContent,
                applyContent = applyContent,
            )
            cfg.copy(
                recent = cfg.recent.map { if (it.id == id) applyTo(it) else it },
                older = cfg.older.map { if (it.id == id) applyTo(it) else it },
            )
        } else {
            val existing = cfg.overrides[talker] ?: VirtualNewFriendsConfig.Override()
            cfg.copy(
                overrides = cfg.overrides + (talker to existing.copy(
                    nickname = nickname,
                    verifyContent = verifyContent,
                    applyContent = applyContent,
                ))
            )
        }

        // 同 updateItemField：save 会规范化，必须拿返回值回写，否则内存与磁盘不一致。
        config = VirtualNewFriendsConfig.save(updated)
        notifyListChanged()
    }

    /** 单独改某一项的某个字段（头像用）。 */
    private fun updateItemField(
        talker: String,
        transform: (VirtualNewFriendsConfig.Override) -> VirtualNewFriendsConfig.Override,
    ) {
        val cfg = config
        val updated = if (isVirtualTalker(talker)) {
            val id = talker.removePrefix(VirtualNewFriendsConfig.TALKER_PREFIX)
            fun applyTo(item: VirtualNewFriendsConfig.Item): VirtualNewFriendsConfig.Item {
                val ov = transform(
                    VirtualNewFriendsConfig.Override(
                        nickname = item.nickname,
                        verifyContent = item.verifyContent,
                        applyContent = item.applyContent,
                        avatarPath = item.avatarPath,
                        isMine = item.isMine,
                    )
                )
                // 所有可编辑字段都要回写：只回写其中一部分会把别的修改丢掉。
                return item.copy(
                    nickname = ov.nickname,
                    verifyContent = ov.verifyContent,
                    applyContent = ov.applyContent,
                    avatarPath = ov.avatarPath,
                    // isMine 可能为 null（外部覆盖表用它表示“不指定”），
                    // 虚拟项必须落成具体布尔值，否则场景会丢。
                    isMine = ov.isMine ?: item.isMine,
                )
            }
            cfg.copy(
                recent = cfg.recent.map { if (it.id == id) applyTo(it) else it },
                older = cfg.older.map { if (it.id == id) applyTo(it) else it },
            )
        } else {
            val existing = cfg.overrides[talker] ?: VirtualNewFriendsConfig.Override()
            cfg.copy(overrides = cfg.overrides + (talker to transform(existing)))
        }

        // 必须用 save 的返回值：它内部会做一次规范化，
        // 拿规范化前的对象会让内存缓存与持久化数据不一致，表现为改了又复原。
        config = VirtualNewFriendsConfig.save(updated)
        notifyListChanged()
    }

    /**
     * 从相册选头像。
     *
     * 复用 CustomLocalFriendAvatars 的做法：借一个透传 Activity 拿到
     * registerForActivityResult（微信自己的 Activity 上拿不到），选完把图
     * 复制进模块私有目录——相册返回的 content:// 授权在部分 provider 上重启后会失效。
     */
    private fun selectAvatarImage(context: android.content.Context, talker: String) {
        runCatching {
            com.Johnny.wcx.activity.TransparentActivity.launch(context) {                val launcher = registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    finish()
                    if (uri == null) return@registerForActivityResult

                    val saved = persistAvatarFile(uri.toString())
                    updateItemField(talker) { it.copy(avatarPath = saved) }
                    WeLogger.i(TAG, "头像已保存: talker=$talker path=$saved")
                }
                launcher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }
        }.onFailure { WeLogger.e(TAG, "打开相册失败", it) }
    }

    /**
     * 把相册图片复制到模块私有目录，返回重启后仍可读的 file:// 路径。
     * 复制失败时回退原 URI。
     */
    private fun persistAvatarFile(uriString: String): String {
        if (uriString.startsWith("file://") || uriString.startsWith("/")) return uriString
        return runCatching {
            val ctx = com.Johnny.wcx.utils.HostInfo.application
            val input = ctx.contentResolver.openInputStream(android.net.Uri.parse(uriString))
                ?: return@runCatching uriString

            val dir = java.io.File(ctx.filesDir, "virtual_avatars").apply { mkdirs() }
            val target = java.io.File(dir, "v_${System.currentTimeMillis()}.jpg")
            input.use { ins ->
                target.outputStream().use { outs -> ins.copyTo(outs) }
            }
            target.absolutePath
        }.getOrDefault(uriString)
    }

    /** 从行对象读 talker。字段在各版本可能有差异，所以沿继承链找。 */
    private fun readTalker(row: Any): String? {
        var cls: Class<*>? = row.javaClass
        while (cls != null && cls != Any::class.java) {
            val f = cls.declaredFields.firstOrNull { it.name == "field_talker" }
            if (f != null) {
                f.isAccessible = true
                return runCatching { f.get(row) as? String }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * 把标题栏「添加朋友」按钮的长按事件替换成我们自己的，并返回 true 吞掉事件
     * ——不返回 true 的话长按会继续传给微信自身的处理逻辑。
     */
    private fun installLongPress(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val anchor = findByViewIdName(decor, ANCHOR_VIEW_ID_NAME)

        if (anchor == null) {
            WeLogger.w(TAG, "未找到「添加朋友」按钮(id=$ANCHOR_VIEW_ID_NAME)，长按入口不可用")
            return
        }

        anchor.setOnLongClickListener {
            WeLogger.i(TAG, "长按「添加朋友」被触发")
            runCatching { showConfigDialog(activity) }
                .onFailure { WeLogger.e(TAG, "showConfigDialog failed", it) }
            true
        }
        // 部分机型的 View 默认 longClickable=false，需要显式打开才会收到长按回调。
        anchor.isLongClickable = true

        WeLogger.i(TAG, "长按入口已挂载：${anchor.javaClass.name}")
    }

    /** 挂 getCount/getItem 两个 hook——这是虚拟行进入列表的唯一通道。 */
    private fun installAdapterHooks(activity: Activity) {
        val adapter = runCatching {
            activity.reflekt()
                .firstFieldOrNull { type { it.name == ADAPTER_FIELD_TYPE } }
                ?.get()
        }.getOrNull()

        if (adapter == null) {
            WeLogger.w(TAG, "未找到适配器字段($ADAPTER_FIELD_TYPE)")
            return
        }

        val adapterClass = adapter.javaClass
        val newGetCount = adapterClass.methods.firstOrNull { it.name == "getCount" && it.parameterCount == 0 }
        val newGetItem = adapterClass.methods.firstOrNull { it.name == "getItem" && it.parameterCount == 1 }

        if (newGetCount == null || newGetItem == null) {
            WeLogger.w(TAG, "适配器上没有 getCount/getItem")
            return
        }

        // 适配器类可能被微信重建（重进页面），但 hook 是挂在**方法**上的，
        // 同一个类只能装一次，否则 getCount 会被叠加多次。
        if (hookedAdapterClass === adapterClass && getItemMethod != null) {
            hookedAdapter = adapter
            grabTemplate(adapter)
            return
        }

        // 必须在装 hook 之前抓模板：此时 getCount/getItem 还是原生的，读到的都是真实行。
        // 若放到装 hook 之后，hook 会在抓模板时递归触发自身，导致模板永远为空。
        getCountMethod = newGetCount
        getItemMethod = newGetItem
        grabTemplate(adapter)

        hookedAdapter = adapter
        hookedAdapterClass = adapterClass

        runCatching {
            getCountMethod!!.hookAfter {
                val real = result as? Int ?: return@hookAfter
                // 只有在能造出虚拟行时才扩展计数。
                // 否则微信会去取那些多出来的下标，而 getItem 无法应付（原生会抛越界，
                // 我们返回 null 它也会在渲染时崩）——两边都不行，所以必须从计数上就拦住。
                if (extraRowCount() == 0 || templateRow == null) return@hookAfter
                result = real + extraRowCount()
            }

            getItemMethod!!.hookBefore {
                // 抓模板期间放行，否则会自己调自己。
                if (isGrabbingTemplate) return@hookBefore

                val pos = args[0] as? Int ?: return@hookBefore
                val cfg = config
                // 没有模板就造不出行；此时 getCount 也没多报，不会有越界下标进来。
                if (templateRow == null) return@hookBefore

                // 真实行保持原样；只有越界的下标才映射到虚拟行。
                val realCount = runCatching {
                    (thisObject as android.widget.ListAdapter).count - extraRowCount()
                }.getOrDefault(0)
                if (pos < realCount) {
                    // 真实行：有覆盖就换成改过的副本，否则完全不干预。
                    applyOverrideToRealRow(pos, realCount)?.let { result = it }
                    return@hookBefore
                }

                val index = pos - realCount
                val row = buildVirtualRow(index)
                if (row == null) {
                    // 走到这里说明状态不一致（能报总数却造不出行）。记下来，
                    // 下一帧会重新抓模板；这里保守地回退计数而不返回 null。
                    WeLogger.w(TAG, "getItem($pos) 无法构造虚拟行，退回真实行")
                    return@hookBefore
                }
                result = row
            }

            WeLogger.i(TAG, "适配器 hook 已安装：${adapterClass.name}")
        }.onFailure { WeLogger.e(TAG, "installAdapterHooks failed", it) }

        installGetViewHook(adapterClass)
    }

    /**
     * hook getView，给虚拟行的**头像**挂长按监听。
     *
     * 为什么必须在 getView 里做：行的 View 是复用的，每次渲染都要重新判定
     * 「这一行是不是虚拟行」，并把监听器重新指向正确的虚拟项；清掉非虚拟行的监听，
     * 否则复用到真实行时会把头像长按带到真实行上。
     */
    private fun installGetViewHook(adapterClass: Class<*>) {
        val getView = adapterClass.methods.firstOrNull {
            it.name == "getView" && it.parameterCount == 3
        } ?: run {
            WeLogger.w(TAG, "适配器上没有 getView，头像长按不可用")
            return
        }

        runCatching {
            getView.hookAfter {
                val position = args[0] as? Int ?: return@hookAfter
                val rowView = result as? View ?: return@hookAfter

                val avatar = findAvatarView(rowView)
                if (avatar == null) {
                    WeLogger.d(TAG, "getView pos=$position 未找到头像 View")
                    return@hookAfter
                }

                // 行 View 会被复用，每次渲染都要重新绑定，并拿掉上一行留下的监听。
                val talker = readTalkerFromAdapterPosition(position) ?: run {
                    clearAvatarHook(avatar, rowView)
                    return@hookAfter
                }

                // 虚拟项和真实项走同一套：都能长按头像编辑，都能套自定义头像。
                // 只挂在头像上：挂在整行会抢掉微信自己的点击处理，
                // 结果就是真实项点不进详情页了。
                val longPress = android.view.View.OnLongClickListener {
                    WeLogger.i(TAG, "长按头像: talker=$talker")
                    showItemEditorDialog(rowView.context, talker)
                    true
                }
                avatar.isLongClickable = true
                avatar.setOnLongClickListener(longPress)

                dumpTextViews(rowView, position)

                val ov = VirtualNewFriendsConfig.findOverride(config, talker)
                WeLogger.i(
                    TAG,
                    "getView pos=$position talker=$talker override=${ov != null} " +
                        "avatar='${ov?.avatarPath.orEmpty().substringAfterLast('/')}'"
                )
                applyCustomAvatar(avatar, talker)

                // 文本也在这里改，不依赖改行对象：
                // 微信的 getView 不一定会调 getItem，改行对象很可能根本没生效。
                if (ov != null) applyCustomText(rowView, talker, ov)
            }
            WeLogger.i(TAG, "getView hook 已安装（头像长按 + 自定义头像）")

            // hook 是在 initView 之后才装的，首屏那些行已经渲染完了，
            // 不会再走 getView——不主动刷一次的话，重启微信后真实项的自定义头像不显示。
            notifyListChanged()
        }.onFailure { WeLogger.e(TAG, "installGetViewHook failed", it) }
    }

    /** 把头像恢复成微信原样：清掉监听、tag 和我们设的图。 */
    private fun clearAvatarHook(avatar: android.widget.ImageView, rowView: View?) {
        avatar.setOnLongClickListener(null)
        avatar.isLongClickable = false
        // 三个 tag 一起清：只清 AVATAR_TAG 的话，路径缓存还在，
        // 行 View 被复用到另一个 talker 时可能因为“路径没变”而跳过设图。
        avatar.setTag(AVATAR_TAG, null)
        avatar.setTag(AVATAR_TAG_BOUND_PATH, null)
        avatar.setTag(AVATAR_TAG_HAS_CUSTOM, null)
        // appliedAvatarBitmap 同样要失效：行被复用时微信会先把当前项的头像设上来，
        // 而我们记着的“已设过哪张图”还指向上一项。若之后又轮到同一个来源，
        // 幂等判断会误以为“已经是这张图了”而跳过设图，屏幕上留着的却是别人的头像。
        appliedAvatarBitmap.remove(avatar)
    }

    /**
     * 从适配器当前位置读 talker。
     *
     * 这里必须走**带 hook** 的 getItem：虚拟行是 hook 造出来的，
     * 放行的话越界下标会落到原生实现上，拿不到虚拟行、也就拿不到 talker。
     */
    private fun readTalkerFromAdapterPosition(position: Int): String? {
        val adapter = hookedAdapter ?: return null
        val row = runCatching { getItemMethod?.invoke(adapter, position) }.getOrNull() ?: return null
        return readTalker(row)
    }

    /**
     * 在行视图里找头像 ImageView。
     *
     * 「新的朋友」行里没有别的图片控件，所以取第一个 ImageView 即可。
     */
    /**
     * 在行视图里找头像 ImageView。
     *
     * 实测行布局里有三个 ImageView：cgi(122x122) 是头像，hgx(49x49) 是右侧图标，
     * 另一个无名 122x122 是遮罩层。优先按 id 名 cgi 定位，拿不到再退到尺寸最大的，
     * 最后兜底取第一个——比单纯取第一个可靠得多。
     */
    private fun findAvatarView(root: View): android.widget.ImageView? {
        val all = collectImageViews(root)
        if (all.isEmpty()) return null

        all.firstOrNull { resourceEntryName(it) == AVATAR_VIEW_ID_NAME }?.let { return it }
        return all.maxByOrNull { viewSize(it) } ?: all.first()
    }

    private fun viewSize(v: View): Int {
        val lp = v.layoutParams
        val w = if (lp != null && lp.width > 0) lp.width else v.width
        val h = if (lp != null && lp.height > 0) lp.height else v.height
        return if (w > 0 && h > 0) w * h else 0
    }

    private fun collectImageViews(root: View): List<android.widget.ImageView> {
        val out = ArrayList<android.widget.ImageView>()
        fun walk(v: View) {
            if (v is android.widget.ImageView) out += v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    /** 把行里所有 ImageView 的 id 名与尺寸打出来，用于按需校正头像定位。 */
    private fun dumpImageViews(root: View, position: Int) {
        val all = collectImageViews(root)
        val desc = all.joinToString(", ") { iv ->
            val idName = runCatching { iv.resources.getResourceEntryName(iv.id) }.getOrNull() ?: "-"
            val lp = iv.layoutParams
            "$idName(lp=${lp?.width}x${lp?.height},real=${iv.width}x${iv.height})"
        }
        WeLogger.i(TAG, "getView pos=$position ImageViews=[$desc]")
    }

    /**
     * 把用户选的头像盖到 ImageView 上。
     *
     * 打完就设 tag，微信的异步头像加载器稍后可能又把默认头像设回来，
     * 所以在 applyCustomAvatar 后还要用 post 补一次。
     */
    private fun applyCustomAvatar(avatar: android.widget.ImageView, talker: String) {
        val override = VirtualNewFriendsConfig.findOverride(config, talker)
        val path = override?.avatarPath.orEmpty()

        // 把「这个 ImageView 当前应该显示哪张自定义头像」记在 tag 上。
        // 微信的异步头像加载器稍后会用 setImageDrawable 把默认头像设回来，
        // 我们的 setImageDrawable hook 靠这个 tag 识别并重新盖回去。
        avatar.setTag(AVATAR_TAG, if (path.isBlank()) null else talker)

        if (path.isBlank()) {
            if (avatar.getTag(AVATAR_TAG_HAS_CUSTOM) != null) {
                avatar.setTag(AVATAR_TAG_HAS_CUSTOM, null)
                avatar.setImageDrawable(null)
            }
            return
        }

        // 同一张图已经绑过了就不重复解码，避免滑动时闪。
        if (avatar.getTag(AVATAR_TAG_BOUND_PATH) == path) return
        avatar.setTag(AVATAR_TAG_BOUND_PATH, path)
        avatar.setTag(AVATAR_TAG_HAS_CUSTOM, "1")

        if (!loadAvatarFromPath(avatar, path)) clearAvatarHook(avatar, null)
    }

    /** 解码失败的路径及失败时间，防止渲染路径上反复重试把主线程拖死。 */
    private val failedAvatarPaths = HashMap<String, Long>()
    private const val AVATAR_FAIL_RETRY_MS = 60_000L


    /**
     * 把本地图片文件设到 ImageView 上。
     *
     * 返回是否成功。**失败时必须让调用方摘掉 tag**（见 hookImageDrawableOverwrite）：
     * 那个 hook 的命中条件就是 ImageView 上的 [AVATAR_TAG]，只要 tag 还在，
     * 就会反复「设图 → 触发 setImageDrawable → hook → 再设图」。
     * 换机/恢复备份后头像文件路径失效时，这个循环会把主线程占满，
     * 表现为进入「新的朋友」页面卡死。
     */
    /**
     * 已解码的头像位图缓存，键是文件路径。
     *
     * 两个作用：一是避免同一张图在每轮渲染里反复解码；二是让
     * 「这个 View 上已经是这张图了」可以用引用相等来判断 ——
     * 那是断开 hook 抖动的依据（见 [loadAvatarFromPath]）。
     */
    private val avatarBitmapCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    /** 记录每个 ImageView 上实际设过的位图，用于跳过重复设图。 */
    private val appliedAvatarBitmap =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<android.widget.ImageView, android.graphics.Bitmap>())

    /**
     * 把本地图片文件设到 ImageView 上。
     *
     * 返回是否成功。**失败时必须让调用方摘掉 tag**（见 [hookImageDrawableOverwrite]）：
     * 那个 hook 的命中条件就是 ImageView 上的 [AVATAR_TAG]，只要 tag 还在，
     * 就会反复「设图 → 触发 setImageDrawable → hook → 再设图」。
     */
    private fun loadAvatarFromPath(avatar: android.widget.ImageView, path: String): Boolean {
        val lastFail = synchronized(failedAvatarPaths) { failedAvatarPaths[path] }
        if (lastFail != null && System.currentTimeMillis() - lastFail < AVATAR_FAIL_RETRY_MS) {
            return false
        }
        return runCatching {
            val file = java.io.File(path)
            if (!file.exists()) {
                WeLogger.w(TAG, "自定义头像文件不存在: $path")
                markAvatarPathFailed(path)
                return@runCatching false
            }
            val bmp = avatarBitmapCache[path]?.takeIf { !it.isRecycled }
                ?: decodeAvatarSampled(path, 512)?.also { decoded ->
                    // 限制缓存规模：列表页头像数量有限，但用户可能换很多次图。
                    if (avatarBitmapCache.size < 32) avatarBitmapCache[path] = decoded
                }
            if (bmp == null) {
                markAvatarPathFailed(path)
                return@runCatching false
            }

            // 已经是这张图就别再设一遍：setImageDrawable 会触发重绘，
            // 重绘又把 hook 叫回来，是抖动的根源。
            if (appliedAvatarBitmap[avatar] === bmp) return@runCatching true

            avatar.scaleType = android.widget.ImageView.ScaleType.FIT_XY
            avatar.setImageBitmap(bmp)
            appliedAvatarBitmap[avatar] = bmp
            avatar.invalidate()
            synchronized(failedAvatarPaths) { failedAvatarPaths.remove(path) }
            true
        }.onFailure {
            WeLogger.w(TAG, "设置自定义头像失败: ${it.message}")
            markAvatarPathFailed(path)
        }.getOrDefault(false)
    }
    private fun markAvatarPathFailed(path: String) {
        synchronized(failedAvatarPaths) { failedAvatarPaths[path] = System.currentTimeMillis() }
    }

    /**
     * 按目标尺寸采样解码，别把整张原图读进内存。
     *
     * 原先直接 decodeFile 全尺寸解码：手机相册里的照片动辄 4000×3000，
     * 展开后是约 48MB 的位图，而这个函数跑在列表渲染路径上 ——
     * 几行下来就够 OOM 或让主线程卡住。
     */
    private fun decodeAvatarSampled(path: String, target: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sample = 1
        var w = srcW
        var h = srcH
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeFile(path, options)
    }

    /**
     * 防住微信异步头像加载器把我们的自定义头像盖掉。
     *
     * 微信加载头像是异步的，会在 getView 之后很晚才调 setImageDrawable，
     * 单纯的 postDelayed 补图漏不了这个窗口。这里直接 hook 系统 ImageView
     * 的 setImageDrawable：只要这个 ImageView 带着「应显示某自定义头像」的 tag，
     * 就把它重新盖回去。
     */
    private fun hookImageDrawableOverwrite() {
        runCatching {
            android.widget.ImageView::class.java.declaredMethods
                .filter { it.name == "setImageDrawable" && it.parameterTypes.size == 1 }
                .forEach { m ->
                    m.isAccessible = true
                    m.hookAfterDirectly {
                        val imageView = thisObject as? android.widget.ImageView ?: return@hookAfterDirectly
                        // 是我们自己设的图，不要再次触发。
                        if (inCustomAvatar.get()) return@hookAfterDirectly

                        val talker = imageView.getTag(AVATAR_TAG) as? String ?: return@hookAfterDirectly
                        val override = VirtualNewFriendsConfig.findOverride(config, talker) ?: return@hookAfterDirectly
                        val path = override.avatarPath
                        if (path.isBlank()) return@hookAfterDirectly

                        inCustomAvatar.set(true)
                        try {
                            if (!loadAvatarFromPath(imageView, path)) clearAvatarHook(imageView, null)
                        } finally {
                            inCustomAvatar.set(false)
                        }
                    }
                }
            WeLogger.i(TAG, "setImageDrawable 反覆盖 hook 已安装")
        }.onFailure { WeLogger.e(TAG, "hookImageDrawableOverwrite failed", it) }
    }

    /**
     * 给真实行套用显示覆盖（昵称/验证语）。
     *
     * 没配覆盖时直接返回，不做任何多余工作——这是绝大多数行的情况。
     *
     * 有覆盖时代价优化：克隆行对象再改，绝不直接改微信返回的那个实例，
     * 否则会污染微信内存中的数据（它可能还会拿这些数据干别的事）。
     */
    private fun applyOverrideToRealRow(pos: Int, realCount: Int): Any? {
        val cfg = config
        if (cfg.overrides.isEmpty()) return null
        if (pos < 0 || pos >= realCount) return null

        val original = readRawItem(pos) ?: return null
        val talker = readTalker(original) ?: return null
        val override = cfg.overrides[talker] ?: return null

        if (DUMP_ROW_FIELDS) dumpRowFields(original, talker)

        val row = cloneRow(original) ?: return null
        if (override.nickname.isNotBlank()) {
            setField(row, "field_contentNickname", override.nickname)
            setField(row, "field_displayName", override.nickname)
        }
        if (override.verifyContent.isNotBlank()) {
            setField(row, "field_contentVerifyContent", override.verifyContent)
        }
        return row
    }

    /** 把行对象里所有 String 类型的字段名和值打出来，用于校正字段名。 */
    private fun dumpRowFields(row: Any, talker: String) {
        val sb = StringBuilder()
        var cls: Class<*>? = row.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { f ->
                if (f.type == String::class.java) {
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(row) as? String
                        if (!v.isNullOrBlank()) sb.append("${f.name}='$v' ")
                    }
                }
            }
            cls = cls.superclass
        }
        WeLogger.i(TAG, "行字段[$talker]: $sb")
    }

    /**
     * 不带 hook 地读一行适配器数据。
     *
     * 直接反射调 getItem 会被我们自己的 getItem hook 拦下，导致递归。
     * 这里临时置位 isGrabbingTemplate 让 hook 放行，读完恢复。
     */
    private fun readRawItem(position: Int): Any? {
        val adapter = hookedAdapter ?: return null
        val was = isGrabbingTemplate
        isGrabbingTemplate = true
        try {
            return runCatching { getItemMethod?.invoke(adapter, position) }.getOrNull()
        } finally {
            isGrabbingTemplate = was
        }
    }

    /** 在适配器里找到 talker 对应的行对象（用于读原值）。 */
    private fun findRowByTalker(talker: String): Any? {
        val adapter = hookedAdapter ?: return null
        val realCount = runCatching {
            (adapter as android.widget.ListAdapter).count - extraRowCount()
        }.getOrDefault(0)

        for (i in 0 until realCount.coerceAtLeast(0)) {
            val row = readRawItem(i) ?: continue
            if (readTalker(row) == talker) return row
        }
        return null
    }

    /** 沿继承链读一个 String 字段。 */
    private fun readStringField(target: Any, name: String): String? {
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Any::class.java) {
            val f = cls.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return runCatching { f.get(target) as? String }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    /** 当前配置需要的额外行数。 */
    private fun extraRowCount(): Int {
        val cfg = config
        return cfg.recent.size + cfg.older.size
    }

    /**
     * 从一个真实行上取克隆模板。
     *
     * 在 hook 已安装时也可以用：[isGrabbingTemplate] 会让 hook 直接放行，
     * 因此这里读到的 count/item 都是未被改造的原生值。
     */
    private fun grabTemplate(adapter: Any) {
        isGrabbingTemplate = true
        try {
            val count = runCatching { (adapter as android.widget.ListAdapter).count }.getOrDefault(0)
            val found = (0 until count.coerceAtLeast(0))
                .mapNotNull { i -> runCatching { getItemMethod?.invoke(adapter, i) }.getOrNull() }
                .firstOrNull { it != null }

            if (found == null) {
                // 重进页面时列表可能还没加载完，此时不能把之前拓好的模板冲掉——
                // 模板一旦丢了就永远注入不了，而列子只是在晚一帧才会出现。
                WeLogger.i(
                    TAG,
                    if (templateRow == null) "列表中没有真实行，暂不注入（缺少克隆模板）"
                    else "本次未取到行，保留已有模板"
                )
                return
            }

            templateRow = found
            WeLogger.i(TAG, "模板行: ${found.javaClass.name}")
        } finally {
            isGrabbingTemplate = false
        }
    }

    /** 兼容旧调用名。 */
    private fun refreshTemplate(adapter: Any) = grabTemplate(adapter)

    /**
     * 配置变更后让列表重新拉取数据。
     *
     * 不调这个的话，getCount/getItem 虽然已经按新配置工作，但微信的 ListView 已经
     * 缓存了旧的 item 数量，不会主动重新问一遍适配器——必须显式通知它失效。
     */
    private fun notifyListChanged() {
        val adapter = hookedAdapter ?: run {
            WeLogger.w(TAG, "无法刷新：还没挂上适配器")
            return
        }
        runCatching {
            adapter.javaClass.methods
                .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
                ?.invoke(adapter)
            WeLogger.i(TAG, "已通知列表刷新")
        }.onFailure { WeLogger.w(TAG, "notifyDataSetChanged 失败: ${it.message}") }
    }

    // ── 虚拟行构造 ────────────────────────────────────────────────────────────

    private fun buildVirtualRow(index: Int): Any? {
        val cfg = config
        val template = templateRow ?: return null

        val recentCount = cfg.recent.size
        val isRecent = index < recentCount
        val itemIndex = if (isRecent) index else index - recentCount
        val item = (if (isRecent) cfg.recent.getOrNull(itemIndex) else cfg.older.getOrNull(itemIndex))
            ?: return null

        return runCatching {
            val row = cloneRow(template) ?: return@runCatching null

            val seq = itemIndex + 1
            // talker 用 item.id 而不是下标：用户改昵称/增删项后下标会变，
            // 但 id 不变，这样长按头像才能稳定地回指到具体某一项。
            // 兜底值与 Config.normalize 的生成规则保持一致（r1/r2、o1/o2）。
            val itemId = item.id.ifBlank { if (isRecent) "r$seq" else "o$seq" }
            val talker = "${VirtualNewFriendsConfig.TALKER_PREFIX}$itemId"

            val now = System.currentTimeMillis()
            // 「三天前」默认放到 5 天前；开了「显示为近三天」就挪进近三天区间（1 天前）。
            val olderOffsetDays = if (cfg.olderAsRecent) 1L else 5L
            val time = if (isRecent) now - DAY_MS else now - olderOffsetDays * DAY_MS

            val nickname = item.nickname.ifBlank { "虚拟用户$seq" }
            // 行上「验证语 / 申请语」是同一个显示位，两种场景共用一个控件。
            // 微信在「我加别人」的文案前会加上「我: 」，虚拟行也照这个约定写，
            // 这样它和真实行在界面上完全一致，场景判断也能统一走原生文本。
            val body = if (item.isMine) {
                item.applyContent.ifBlank { "我是$nickname" }.let { "$APPLY_PREFIX$it" }
            } else {
                item.verifyContent.ifBlank { "我是$nickname" }
            }
            val verify = body

            setField(row, "field_talker", talker)
            setField(row, "field_contentFromUsername", talker)
            setField(row, "field_displayName", nickname)
            setField(row, "field_contentNickname", nickname)
            setField(row, "field_contentVerifyContent", verify)
            setField(row, "field_lastModifiedTime", time)

            row
        }.onFailure { WeLogger.e(TAG, "buildVirtualRow($index) failed", it) }.getOrNull()
    }

    /**
     * 克隆模板行。优先使用 clone()（行对象通常实现 Cloneable）；
     * 否则退回到「无参构造 + 逐字段复制」。
     *
     * 之所以克隆而不是 new：行对象字段极多且互相引用，凭空构造会让渲染路径读到
     * null 而崩溃。克隆保留了所有我们没显式修改的字段。
     */
    private fun cloneRow(src: Any): Any? {
        if (src is Cloneable) {
            runCatching {
                src.javaClass.getMethod("clone").also { it.isAccessible = true }.invoke(src)
            }.getOrNull()?.let { return it }
        }

        return runCatching {
            val ctor = src.javaClass.getDeclaredConstructor()
            ctor.isAccessible = true
            val dst = ctor.newInstance()
            var cls: Class<*>? = src.javaClass
            while (cls != null && cls != Any::class.java) {
                cls.declaredFields.forEach { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(src)
                        // 只复制值语义安全的字段；集合/回调等共享引用保持为 null，
                        // 避免把模板行的观察者重复注册到虚拟行上。
                        if (v == null || v is String || v is Number || v is Boolean) f.set(dst, v)
                    }
                }
                cls = cls.superclass
            }
            dst
        }.onFailure { WeLogger.e(TAG, "cloneRow failed", it) }.getOrNull()
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Any::class.java) {
            val f = cls.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                runCatching { f.set(target, value) }
                    .onFailure { WeLogger.w(TAG, "setField($name) 失败: ${it.message}") }
                return
            }
            cls = cls.superclass
        }
        // 微信不同版本字段名可能变化，缺字段不算致命——记录下来即可。
        WeLogger.d(TAG, "字段 $name 不存在于 ${target.javaClass.name}")
    }

    private fun findByViewIdName(root: ViewGroup, idName: String): View? {
        if (resourceEntryName(root) == idName) return root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (resourceEntryName(child) == idName) return child
            if (child is ViewGroup) findByViewIdName(child, idName)?.let { return it }
        }
        return null
    }

    private fun resourceEntryName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    // ── 设置界面 ──────────────────────────────────────────────────────────────

    /**
     * 长按「添加朋友」弹出的配置界面。
     *
     * 用可增删的列表编辑每一项的昵称与验证语；保存后立即刷新列表。
     */
    private fun showConfigDialog(context: android.content.Context) {
        showComposeDialog(context) {
            val initial = VirtualNewFriendsConfig.copyOf(config)

            val recentItems = remember { mutableStateListOf(*initial.recent.toTypedArray()) }
            val olderItems = remember { mutableStateListOf(*initial.older.toTypedArray()) }
            var olderAsRecent by remember { mutableStateOf(initial.olderAsRecent) }

            AlertDialogContent(
                title = { Text("新的朋友虚拟项") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 开关放最上面，一眼就能看到。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("把「三天前」显示为「近三天」")
                            Switch(checked = olderAsRecent, onCheckedChange = { olderAsRecent = it })
                        }

                        Divider()

                        ItemGroup(
                            title = "近三天（${recentItems.size} 个）",
                            items = recentItems,
                            otherGroup = olderItems,
                            defaultNamePrefix = "虚拟用户",
                        )

                        Divider()

                        ItemGroup(
                            title = "三天前（${olderItems.size} 个）",
                            items = olderItems,
                            otherGroup = recentItems,
                            defaultNamePrefix = "旧虚拟用户",
                        )

                        Text(
                            "虚拟项只在本机显示，不写入微信数据库，也不会真的发出好友申请。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        // 必须把原有的 overrides 带上：这个弹窗只编辑虚拟项，
                        // 重建 Config 而不带 overrides 会把真实项的昵称/验证语/头像全部抹掉。
                        val cfg = VirtualNewFriendsConfig.Config(
                            recent = recentItems.toList(),
                            older = olderItems.toList(),
                            olderAsRecent = olderAsRecent,
                            overrides = config.overrides,
                        )
                        // save 返回的是规范化后的配置（可能重新分配了 id），
                        // 必须用它回写内存，否则列表上的 talker 会和配置对不上。
                        config = VirtualNewFriendsConfig.save(cfg)
                        notifyListChanged()
                        showToast(
                            context,
                            "已保存，共 ${config.recent.size + config.older.size} 个虚拟项"
                        )
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    /** 一组虚拟项：标题 + 可增删的编辑列表。 */
    @androidx.compose.runtime.Composable
    private fun ItemGroup(
        title: String,
        items: androidx.compose.runtime.snapshots.SnapshotStateList<VirtualNewFriendsConfig.Item>,
        otherGroup: List<VirtualNewFriendsConfig.Item>,
        defaultNamePrefix: String,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)

        if (items.isEmpty()) {
            Text(
                "暂无，点下方按钮添加",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        items.forEachIndexed { index, item ->
            // 每项占两行：第一行昵称，第二行验证语。窄屏下并排两个输入框会看不清。
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${index + 1}.", modifier = Modifier.padding(end = 6.dp))
                    OutlinedTextField(
                        value = item.nickname,
                        onValueChange = { items[index] = item.copy(nickname = it) },
                        label = { Text("昵称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { items.removeAt(index) }) { Text("删除") }
                }
                OutlinedTextField(
                    value = item.verifyContent,
                    onValueChange = { items[index] = item.copy(verifyContent = it) },
                    label = { Text("验证语（留空则自动生成）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 4.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                // 必须带上 id：虚拟行的 talker 是由 id 拼出来的，
                // 没有 id 的话行上的 talker 和配置里的项对不上，长按编辑就不生效。
                // 两个分组要一起传，否则近三天和三天前会各自从 v1 开始、id 撞车。
                items.add(
                    VirtualNewFriendsConfig.Item(
                        id = VirtualNewFriendsConfig.newId(items, otherGroup),
                        nickname = "$defaultNamePrefix${items.size + 1}",
                    )
                )
            }) { Text("+1") }

            OutlinedButton(onClick = {
                repeat(3) { n ->
                    items.add(
                        VirtualNewFriendsConfig.Item(
                            id = VirtualNewFriendsConfig.newId(items, otherGroup),
                            nickname = "$defaultNamePrefix${items.size + 1 + n}",
                        )
                    )
                }
            }) { Text("+3") }

            if (items.isNotEmpty()) {
                TextButton(onClick = { items.clear() }) { Text("清空") }
            }
        }
    }

    // ── 点击拦截 ──────────────────────────────────────────────────────────────

    /**
     * 判断某一行是否为我们的虚拟行——被点击时用来拦截。
     * 由列表的点击处理路径调用；行对象的 talker 字段是唯一可靠的标识。
     */
    fun isVirtualTalker(talker: String?): Boolean =
        talker?.startsWith(VirtualNewFriendsConfig.TALKER_PREFIX) == true

    override fun onClick(context: ComponentActivity) {
        // 设置入口有两个：长按「添加朋友」按钮，以及模块设置里点这一项。
        runCatching { showConfigDialog(context) }
            .onFailure { WeLogger.e(TAG, "onClick 打开设置失败", it) }
    }

    override fun onDisable() {
        WeLogger.i(TAG, "disabled（hook 不摘除）")
    }
}
