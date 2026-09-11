package com.Johnny.wcx.features.items.secret_friend

import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import androidx.compose.runtime.Composable
import com.Johnny.wcx.ui.content.m3.TextFieldDialogWidget
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import java.lang.ref.WeakReference

/**
 * 临时解除与恢复组 24–27：多击标题解除、长按标题解除、锁屏隐藏、离开对话/离开微信隐藏。
 *
 * 解除统一走 [SecretFriendState.tempShowForMinutes]（默认 30 分钟，到期自动恢复）；
 * 恢复统一走 [SecretFriendState.tempOff]。标题定位采用浮云实测方案（见
 * [SecretFriendState.findHomeTitleTextView]）：LauncherUI onResume 后 decorView.post 定位
 * 顶部 25% 区域内文本恰为「微信」的 TextView，直接挂 listener（View 自身接收触摸，
 * 规避澎湃 OS 全面屏手势吞事件；重复 set 是替换语义，不叠加）。
 */
// ─────────────────────────── 24. 多击标题解除 ───────────────────────────

/**
 * 多击标题解除（浮云「多击标题解除」语义）：连续点击主页标题 N 次（可调，默认 3），
 * 每次点击间隔不超过 M 毫秒（可调，默认 1000）→ 临时解除隐藏 30 分钟。
 */
@Feature(
    name = "多击标题解除",
    categories = ["密友功能"],
    description = "连续点击微信主页标题可临时解除隐藏 30 分钟（点击次数与时间窗口可调）"
)
object MultiClickTitleUnlock : SwitchFeature() {

    private const val TAG = "MultiClickTitleUnlock"

    /** 触发所需连续点击次数（默认 3）。 */
    var clickCount by prefOption("secret_friend_unlock_click_count", 3)

    /** 计次窗口毫秒数（默认 1000）。 */
    var clickWindowMs by prefOption("secret_friend_unlock_click_window_ms", 1000)

    private var clickCounter = 0
    private var lastClickAt = 0L
    private var warnedTitleMissing = false

    override fun onEnable() {
        // LauncherUI.onResume 后标题布局已就绪; 与 HideContacts 的三击标题互不干扰
        // (本开关挂 setOnClickListener, HideContacts 亦挂同名监听, 后启用的替换前者——
        //  两个功能都开时以最后启用者为准, 界面上有各自开关说明)
        val resumeHook = runCatching {
            LauncherUI::class.reflekt()
                .firstMethod {
                    name = "onResume"
                    superclass()
                }
        }.getOrElse {
            WeLogger.e(TAG, "hook LauncherUI.onResume failed; multi-click unlock unavailable", it)
            return
        }
        resumeHook.hookAfter {
                val activity = thisObject as? android.app.Activity ?: return@hookAfter
                val root = activity.window?.decorView ?: return@hookAfter
                root.post {
                    val title = SecretFriendState.findHomeTitleTextView(root)
                    if (title == null) {
                        if (!warnedTitleMissing) {
                            warnedTitleMissing = true
                            WeLogger.w(TAG, "home title view not found; multi-click unlock not attached")
                        }
                        return@post
                    }
                    WeLogger.i(TAG, "multi-click unlock attached to ${title.javaClass.name}")
                    title.setOnClickListener { view ->
                        if (SecretFriendState.isEmpty()) return@setOnClickListener
                        val now = System.currentTimeMillis()
                        if (now - lastClickAt > clickWindowMs.coerceAtLeast(200)) clickCounter = 1
                        else clickCounter++
                        lastClickAt = now
                        if (clickCounter >= clickCount.coerceAtLeast(2)) {
                            clickCounter = 0
                            WeLogger.i(TAG, "title multi-click unlock triggered")
                            SecretFriendState.tempShowForMinutes(view.context)
                        }
                    }
                }
            }
    }
}

// ─────────────────────────── 25. 长按标题解除 ───────────────────────────

/**
 * 长按标题解除（旧版 SecretFriendTempShow 逻辑 / 浮云「长按标题解除」语义）：
 * 长按主页标题 [longPressMs] 毫秒（默认 800）→ 切换临时显示。
 * 用 setOnTouchListener 自计时（系统 OnLongClickListener 写死 ~500ms，无法自定义时长；
 * 且澎湃 OS 的 ACTION_CANCEL 会杀掉 View 内部的长按 postDelayed）。
 */
@Feature(
    name = "长按标题解除",
    categories = ["密友功能"],
    description = "长按微信主页标题切换临时显示/恢复隐藏（触发时长可调，默认 800 毫秒）"
)
object LongPressTitleUnlock : SwitchFeature() {

    private const val TAG = "LongPressTitleUnlock"

    /** 长按触发时长毫秒（默认 800）。 */
    var longPressMs by prefOption("secret_friend_long_press_ms", 800)

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var warnedTitleMissing = false

    private val triggerRunnable = Runnable {
        if (!pending) return@Runnable
        pending = false
        if (SecretFriendState.isTemporarilyShown()) {
            WeLogger.i(TAG, "title long-press: restoring hidden state")
            SecretFriendState.tempOff()
        } else {
            WeLogger.i(TAG, "title long-press unlock triggered")
            SecretFriendState.tempShowForMinutes()
        }
    }

    override fun onEnable() {
        val resumeHook = runCatching {
            LauncherUI::class.reflekt()
                .firstMethod {
                    name = "onResume"
                    superclass()
                }
        }.getOrElse {
            WeLogger.e(TAG, "hook LauncherUI.onResume failed; long-press unlock unavailable", it)
            return
        }
        resumeHook.hookAfter {
                val activity = thisObject as? android.app.Activity ?: return@hookAfter
                val root = activity.window?.decorView ?: return@hookAfter
                root.post {
                    val title = SecretFriendState.findHomeTitleTextView(root)
                    if (title == null) {
                        if (!warnedTitleMissing) {
                            warnedTitleMissing = true
                            WeLogger.w(TAG, "home title view not found; long-press unlock not attached")
                        }
                        return@post
                    }
                    // TextView 不可点击时触摸流（MOVE/UP）会被父级接管，自计时无法正常工作，
                    // 必须显式置为可点击/可长按（重复 onResume 挂监听是替换语义，不叠加）
                    title.isClickable = true
                    title.isLongClickable = true
                    val touchSlop = ViewConfigurationCompat.scaledTouchSlop(title)
                    title.setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                pending = true
                                handler.postDelayed(triggerRunnable, longPressMs.coerceIn(300, 5000).toLong())
                            }

                            MotionEvent.ACTION_MOVE -> {
                                // 手指滑出标题区域即取消（自计时版本需要自己判越界）
                                if (event.x < -touchSlop || event.y < -touchSlop ||
                                    event.x > view.width + touchSlop || event.y > view.height + touchSlop
                                ) {
                                    pending = false
                                    handler.removeCallbacks(triggerRunnable)
                                }
                            }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                pending = false
                                handler.removeCallbacks(triggerRunnable)
                            }
                        }
                        false // 不消费: 正常点击派发不受影响, 与「多击标题解除」可同时启用
                    }
                }
            }
    }

    @Composable
    override fun Ui() {
        TextFieldDialogWidget(
            title = "长按触发时长(毫秒)",
            value = longPressMs.toString(),
            onValueChange = { raw ->
                raw.filter { it.isDigit() }.take(5).toIntOrNull()?.let { longPressMs = it }
            },
            dialogTitle = "长按触发时长（毫秒，300–5000）",
            confirmLabel = "确定",
            dismissLabel = "取消",
        )
    }
}

/** ViewConfiguration 取值的小包装（避免直接依赖平台类名）。 */
private object ViewConfigurationCompat {
    fun scaledTouchSlop(view: View): Int = runCatching {
        android.view.ViewConfiguration.get(view.context).scaledTouchSlop
    }.getOrDefault(24)
}

// ─────────────────────────── 26. 锁屏隐藏 ───────────────────────────

/**
 * 锁屏隐藏（浮云「锁屏隐藏」语义）：锁屏（SCREEN_OFF）广播 → 立即 tempOff()；
 * 若正处于密友对话内，同时关闭该对话窗口（finish ChattingUI）。
 * Receiver 动态注册于 onEnable，随 onDisable 注销。
 */
@Feature(
    name = "锁屏隐藏",
    categories = ["密友功能"],
    description = "锁屏后立即恢复密友隐藏；若正处在密友对话内会自动关闭对话窗口"
)
object AutoRestoreLockScreen : SwitchFeature() {

    private const val TAG = "AutoRestoreLockScreen"

    /** 当前打开的 ChattingUI（onResume 捕获，onPause 清除）。 */
    private var chattingUiRef: WeakReference<ChattingUI>? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            WeLogger.i(TAG, "screen off, restoring hidden state")
            SecretFriendState.tempOff()
            // 密友对话内锁屏 → 关闭对话窗口（返回主页时列表已恢复隐藏）
            chattingUiRef?.get()?.let { activity ->
                val wxId = activity.intent?.getStringExtra("Chat_User")
                if (SecretFriendState.isSecret(wxId)) {
                    WeLogger.i(TAG, "finishing secret chat window on screen off")
                    runCatching { activity.finish() }
                        .onFailure { WeLogger.w(TAG, "finish ChattingUI failed", it) }
                }
            }
        }
    }

    override fun onEnable() {
        runCatching {
            HostInfo.application.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }.onFailure { WeLogger.e(TAG, "register screen-off receiver failed", it) }

        // ChattingUI 不一定声明 onResume/onPause（版本相关），解析失败会整体中断 onEnable
        // 导致广播都不注册——必须逐个容错，声明找不到时回退父类查找
        val resumeMethod = runCatching {
            ChattingUI::class.reflekt().firstMethodOrNull { name = "onResume" }
                ?: ChattingUI::class.reflekt().firstMethodOrNull { name = "onResume"; superclass(true) }
        }.getOrNull()
        if (resumeMethod != null) {
            resumeMethod.hookAfter { chattingUiRef = WeakReference(thisObject as? ChattingUI) }
        } else {
            WeLogger.w(TAG, "ChattingUI.onResume not resolvable; auto-close chat on lock screen disabled")
        }

        val pauseMethod = runCatching {
            ChattingUI::class.reflekt().firstMethodOrNull { name = "onPause" }
                ?: ChattingUI::class.reflekt().firstMethodOrNull { name = "onPause"; superclass(true) }
        }.getOrNull()
        if (pauseMethod != null) {
            pauseMethod.hookAfter { chattingUiRef = null }
        } else {
            WeLogger.w(TAG, "ChattingUI.onPause not resolvable; stale chat window tracking possible")
        }
    }

    override fun onDisable() {
        runCatching { HostInfo.application.unregisterReceiver(screenOffReceiver) }
            .onFailure { WeLogger.w(TAG, "unregisterReceiver failed", it) }
    }
}

// ─────────────────── 27. 离开对话 / 离开微信隐藏 ───────────────────

/**
 * 离开对话/离开微信隐藏（浮云 rehideOnLeaveChat + rehideOnLeaveApp 合并开关）：
 * - 离开任意对话（ChattingUI.onPause）→ tempOff()；
 * - 离开主页（LauncherUI.onPause）→ tempOff()；
 * - 微信失焦（LauncherUI.onWindowFocusChanged(false)，即按 HOME 切走）→ tempOff()。
 * 临时显示期间进入过的密友对话，返回主页时会话列表已恢复隐藏，无需额外 finish。
 */
@Feature(
    name = "离开对话/离开微信隐藏",
    categories = ["密友功能"],
    description = "退出对话、离开主页或按 HOME 切走微信时立即恢复密友隐藏（临时解除的自动恢复）"
)
object AutoRestoreOnLeave : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoRestoreOnLeave"

    /** LauncherUI.onWindowFocusChanged(boolean)（allowFailure：未声明时跳过，onPause 已兜底）。 */
    private val methodLauncherFocusChanged by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUI"
            name = "onWindowFocusChanged"
            paramCount = 1
        }
    }

    override fun onEnable() {
        // 离开任意对话即恢复隐藏
        ChattingUI::class.reflekt()
            .firstMethod { name = "onPause" }
            .hookAfter {
                if (SecretFriendState.isTemporarilyShown()) {
                    WeLogger.d(TAG, "leaving conversation, restoring hidden state")
                    SecretFriendState.tempOff()
                }
            }

        // 离开主页 / 微信失焦即恢复隐藏
        LauncherUI::class.reflekt()
            .firstMethod {
                name = "onPause"
                superclass()
            }
            .hookAfter {
                if (SecretFriendState.isTemporarilyShown()) {
                    WeLogger.d(TAG, "leaving home screen, restoring hidden state")
                    SecretFriendState.tempOff()
                }
            }

        if (!methodLauncherFocusChanged.isPlaceholder) {
            methodLauncherFocusChanged.hookAfter {
                val hasFocus = args.getOrNull(0) as? Boolean ?: return@hookAfter
                if (!hasFocus && SecretFriendState.isTemporarilyShown()) {
                    WeLogger.d(TAG, "WeChat lost window focus, restoring hidden state")
                    SecretFriendState.tempOff()
                }
            }
        }
    }
}
