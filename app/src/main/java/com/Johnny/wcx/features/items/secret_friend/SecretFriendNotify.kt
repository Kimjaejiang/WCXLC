package com.Johnny.wcx.features.items.secret_friend

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull

/**
 * 消息与通知组 18–22：密友消息震动、微信团队提醒、底栏字体加粗、圆点提示、拦截扫码登录。
 *
 * 前四个开关都监听 message 表插入（IInsertListener）判密友来消息；UI 类的操作均为
 * 结构式实现（MainTabUI doOnCreate 锚点为工程已验证点，view 查找全部空安全）。
 */
// ─────────────────────────── 18. 密友消息震动 ───────────────────────────

/**
 * 密友消息震动（浮云语义）：隐藏态收到密友消息时震动提示（强度可调，支持测试）。
 * 点击本体发一次测试震动。主进程 DB hook：后台 :push 进程入库时本开关不感知
 * （与隐藏联系人通知链路同限制），微信回到前台主页时会话列表仍保持隐藏。
 */
@Feature(
    name = "密友消息震动",
    categories = ["密友功能"],
    description = "隐藏状态下收到密友消息时震动提示（强度可调）；点击本开关发送一次测试震动"
)
object VibrateOnSecretMsg : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "VibrateOnSecretMsg"

    /** 震动强度 1–5（映射震动时长）。 */
    var vibrateStrength by prefOption("secret_friend_vibrate_strength", 3)

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onInsert(table: String, values: android.content.ContentValues) {
        val talker = secretIncomingTalker(table, values) ?: return
        if (SecretFriendState.isTemporarilyShown()) return
        WeLogger.d(TAG, "secret message arrived from $talker, vibrating")
        vibrateNow(vibrateStrength)
    }

    /** 点击本体 = 测试震动。 */
    override fun onClick(context: androidx.activity.ComponentActivity) {
        vibrateNow(vibrateStrength)
        WeLogger.d(TAG, "test vibration sent")
    }

    private fun vibrateNow(strength: Int) {
        runCatching {
            val vibrator = HostInfo.application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                ?: return
            if (!vibrator.hasVibrator()) return
            val clamped = strength.coerceIn(1, 5)
            val pattern = longArrayOf(0, 80L * clamped, 100, 80L * clamped)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { WeLogger.e(TAG, "vibrate failed", it) }
    }
}

// ─────────────────────────── 19. 微信团队提醒 ───────────────────────────

/**
 * 微信团队提醒（MaskWechat 提醒语义 / 浮云「微信团队提醒」）：密友来消息时以「微信团队」
 * 名义插入一条 SYSTEM 消息（type 10000），用户点进微信团队会话即可看到"谁发来了消息"。
 */
@Feature(
    name = "微信团队提醒",
    categories = ["密友功能"],
    description = "密友来消息时在「微信团队」会话插入一条提醒（不显示是谁，只提示有密友消息），点击进入微信团队会话查看"
)
object TeamNotify : SwitchFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "TeamNotify"

    private const val TEAM_TALKER = "weixin"
    private const val TYPE_SYSTEM_MSG = 10000

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun onInsert(table: String, values: android.content.ContentValues) {
        val talker = secretIncomingTalker(table, values) ?: return
        if (SecretFriendState.isTemporarilyShown()) return

        val displayName = fetchDisplayName(talker)
        val content = if (displayName.isNullOrEmpty()) {
            "你的密友发来一条新消息，请查看"
        } else {
            "你的密友「$displayName」发来一条新消息，请查看"
        }
        com.Johnny.wcx.features.api.core.WeMessageApi.createSimpleMsgInfoAndInsert(
            TYPE_SYSTEM_MSG, TEAM_TALKER, content, System.currentTimeMillis() / 1000
        )
        WeLogger.d(TAG, "team notify inserted for secret message from $talker")
    }

    /** 密友的备注/昵称（查 rcontact 单行，失败返回 null，提示退化为不含名字的文案）。 */
    private fun fetchDisplayName(wxId: String): String? = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT conRemark, nickname FROM rcontact WHERE username = ?",
            arrayOf(wxId)
        ).firstOrNull()?.let { row ->
            (row["conRemark"] as? String)?.takeIf { it.isNotEmpty() }
                ?: (row["nickname"] as? String)?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}

// ─────────────────────────── 20. 底栏字体加粗 ───────────────────────────

/**
 * 底栏字体加粗（浮云语义）：有密友未读时底部导航「微信」文字加粗（强度可调）。
 * - MainTabUI doOnCreate 后收集底栏 tab TextView（工程已验证锚点）；
 * - LauncherUI onResume 清除加粗（回到微信即视为已读）；
 * - 密友消息入库时对「微信」tab 应用加粗。
 */
@Feature(
    name = "底栏字体加粗",
    categories = ["密友功能"],
    description = "收到密友消息时底部导航栏「微信」文字加粗提醒（强度可调），回到主页自动恢复"
)
object BottomBarBold : SwitchFeature() {

    private const val TAG = "BottomBarBold"

    /** 加粗强度 1–5（映射 Paint 描边宽度）。 */
    var boldStrength by prefOption("secret_friend_bold_strength", 3)

    /** 底栏 tab 文字 → TextView（doOnCreate 后收集）。 */
    private var tabViews: Map<String, TextView> = emptyMap()

    private val secretMsgListener = WeDatabaseListenerApi.IInsertListener { table, values ->
        val talker = secretIncomingTalker(table, values) ?: return@IInsertListener
        if (SecretFriendState.isTemporarilyShown()) return@IInsertListener
        WeLogger.d(TAG, "secret message from $talker, bolding bottom tab")
        runOnUiThread { setBold(true) }
    }

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            // doOnCreate 的 thisObject 是 MainTabUI 容器而非 Activity（同 HideContacts），
            // 需从其 Activity 类型字段解出主页 Activity
            val activity = thisObject?.reflekt()
                ?.firstFieldOrNull { type { Activity::class.java.isAssignableFrom(it) } }
                ?.get() as? Activity ?: return@hookAfter
            val root = activity.window?.decorView ?: return@hookAfter
            collectTabViews(root)
        }

        // 回到主页（LauncherUI resume）即视为已读，清除加粗
        LauncherUI::class.reflekt()
            .firstMethod {
                name = "onResume"
                superclass()
            }
            .hookAfter {
                setBold(false)
            }

        WeDatabaseListenerApi.addListener(secretMsgListener)
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(secretMsgListener) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    private fun collectTabViews(root: View) {
        val wanted = setOf("微信", "通讯录", "发现", "我")
        val found = mutableMapOf<String, TextView>()
        fun walk(view: View, depth: Int) {
            if (depth > 18) return
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (text in wanted && text !in found) found[text] = view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i) ?: return, depth + 1)
            }
        }
        walk(root, 0)
        tabViews = found
        if (found.isEmpty()) WeLogger.w(TAG, "bottom tab TextViews not found; bold prompt unavailable")
    }

    private fun setBold(bold: Boolean) {
        val view = tabViews["微信"] ?: return
        view.paint.isFakeBoldText = bold
        view.paint.strokeWidth = if (bold) boldStrength.coerceIn(1, 5) * 0.4f else 0f
        view.invalidate()
    }
}

// ─────────────────────────── 21. 圆点提示 ───────────────────────────

/**
 * 圆点提示（浮云语义）：有密友未读时搜索框旁显示红点（大小可调）。
 * MainTabUI doOnCreate 后在 decorView 上叠一个红点 View，锚定「搜索」入口的位置
 * （OnLayoutChangeListener 跟随布局），密友消息 → 显示，LauncherUI resume → 隐藏。
 */
@Feature(
    name = "圆点提示",
    categories = ["密友功能"],
    description = "有密友未读消息时搜索框旁显示一个红点（大小可调），回到主页自动消失"
)
object RedDotPrompt : SwitchFeature() {

    private const val TAG = "RedDotPrompt"

    /** 红点直径 4–12 dp。 */
    var dotSizeDp by prefOption("secret_friend_dot_size", 6)

    private var dotView: View? = null
    private var anchorView: View? = null

    private val secretMsgListener = WeDatabaseListenerApi.IInsertListener { table, values ->
        val talker = secretIncomingTalker(table, values) ?: return@IInsertListener
        if (SecretFriendState.isTemporarilyShown()) return@IInsertListener
        runOnUiThread { dotView?.visibility = View.VISIBLE }
    }

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            // doOnCreate 的 thisObject 是 MainTabUI 容器而非 Activity（同 HideContacts），
            // 需从其 Activity 类型字段解出主页 Activity
            val activity = thisObject?.reflekt()
                ?.firstFieldOrNull { type { Activity::class.java.isAssignableFrom(it) } }
                ?.get() as? Activity ?: return@hookAfter
            runOnUiThread { attachDot(activity) }
        }

        LauncherUI::class.reflekt()
            .firstMethod {
                name = "onResume"
                superclass()
            }
            .hookAfter {
                runOnUiThread { dotView?.visibility = View.INVISIBLE }
            }

        WeDatabaseListenerApi.addListener(secretMsgListener)
    }

    override fun onDisable() {
        runCatching { WeDatabaseListenerApi.removeListener(secretMsgListener) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    /** 找搜索入口 → 在 decorView 上叠红点（找不到锚点则整段跳过）。 */
    private fun attachDot(activity: Activity) {
        if (dotView != null) return
        val root = activity.window?.decorView as? ViewGroup ?: return

        val anchor = findViewByText(root, "搜索") ?: return
        anchorView = anchor

        val dot = View(activity)
        val sizePx = (dotSizeDp.coerceIn(4, 12) * root.resources.displayMetrics.density).toInt()
        val params = ViewGroup.LayoutParams(sizePx, sizePx)
        dot.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFA5151.toInt())
        }
        dot.visibility = View.INVISIBLE
        root.addView(dot, params)
        dotView = dot

        fun reposition() {
            val view = dotView ?: return
            val anchorNow = anchorView ?: return
            if (!anchorNow.isAttachedToWindow) return
            val anchorLoc = IntArray(2).also { anchorNow.getLocationOnScreen(it) }
            val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
            val x = anchorLoc[0] - rootLoc[0] + anchorNow.width - view.layoutParams.width
            val y = anchorLoc[1] - rootLoc[1] - view.layoutParams.height / 2
            view.translationX = x.toFloat()
            view.translationY = y.toFloat()
        }

        anchor.viewTreeObserver.addOnGlobalLayoutListener { reposition() }
        reposition()
        WeLogger.d(TAG, "red dot attached near search entry")
    }

    private fun findViewByText(root: View, text: String): View? {
        if (root is TextView && root.text?.toString().orEmpty() == text) return root
        if (root is TextView && (root.hint?.toString().orEmpty()) == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewByText(root.getChildAt(i) ?: continue, text)?.let { return it }
            }
        }
        return null
    }
}

// ─────────────────────────── 22. 拦截扫码登录 ───────────────────────────

/**
 * 拦截扫码登录（浮云 blockScanLogin 语义）：拦截「扫码登录确认」页
 * （ExtDeviceWXLoginUI，扫码把当前账号登录到 PC/网页/其他设备的确认弹窗），
 * 页面 onCreate 后立即 finish——防止密友隐藏期间他人借手机扫码确认，把密友消息
 * 同步到别的设备上查看。开关开启即全程拦截（临时显示态同样拦截，与浮云一致）。
 *
 * ExtDeviceWXLoginUI 是微信长期未混淆的稳定类名（webwx 包）；类缺失/方法漂移时
 * onEnable 安全跳过，仅日志提示。
 */
@Feature(
    name = "拦截扫码登录",
    categories = ["密友功能"],
    description = "拦截「扫码登录确认」弹窗：他人扫码无法把当前账号登录到电脑等设备，防止密友消息在别处可见"
)
object BlockScanLogin : SwitchFeature() {

    private const val TAG = "BlockScanLogin"

    /** 扫码登录确认页（微信 webwx 包稳定类名，浮云同款拦截点）。 */
    private const val EXT_DEVICE_LOGIN_UI = "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI"

    override fun onEnable() {
        val loginUi = EXT_DEVICE_LOGIN_UI.toClassOrNull() ?: run {
            WeLogger.w(TAG, "ExtDeviceWXLoginUI not found; scan-login blocking unavailable")
            return
        }
        val onCreate = loginUi.reflekt().firstMethodOrNull {
            name = "onCreate"
            parameters(Bundle::class)
        }
        if (onCreate == null) {
            WeLogger.w(TAG, "ExtDeviceWXLoginUI.onCreate not resolved; scan-login blocking unavailable")
            return
        }
        onCreate.hookAfter {
            val activity = thisObject as? Activity ?: return@hookAfter
            WeLogger.i(TAG, "blocked scan login confirm dialog")
            runCatching { activity.finish() }
        }
    }
}
