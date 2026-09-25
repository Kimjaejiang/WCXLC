package com.Johnny.wcx.features.items.secret_friend

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import com.Johnny.wcx.utils.WeLogger

/**
 * 主页标题手势的**唯一分发点**。
 *
 * ## 为什么需要它
 *
 * 「临时显示/恢复隐藏」原先有三个互相独立的实现挂在同一个标题 View 上：
 * - `HideContacts` 的「三击标题」（`setOnClickListener`）
 * - 密友的「多击标题解除」（`setOnClickListener`）
 * - 密友的「长按标题解除」（`setOnTouchListener`）
 *
 * `View` 只保留**最后一个** `OnClickListener`，所以前两个在同一台设备上必定有一个
 * 永远不生效 —— 谁后挂谁赢，且顺序取决于功能启用顺序，用户看到的是「时灵时不灵」。
 * 这正是「无法顺利切出隐藏和显示」的一个直接原因。
 *
 * 现在三个功能都不再自己挂监听，而是把「配置」交给本对象；由本对象在 `onResume`
 * 时**一次性**装配：
 * - 单击计数（多击）与长按共用一个 `OnTouchListener`，按 `ACTION_DOWN` 起算；
 * - 长按优先于多击：长按触发后，本次抬手不再计入点击计数；
 * - 点击计数与长按判定参数各自实时读取开关对应的 pref，改设置无需重挂。
 *
 * ## 与各功能的开关关系
 *
 * 每个手势源有自己的开关（`MultiClickTitleUnlock` / `LongPressTitleUnlock` /
 * `HideContacts.tripleClickTitle`）。本对象只做分发，不判断开关归属——
 * 各功能在 [TitleGestureConfig] 里声明自己是否启用即可。
 */
object SecretFriendTitleGesture {

    private const val TAG = "SecretFriendTitleGesture"

    private val handler = Handler(Looper.getMainLooper())
    private var attachedTo: View? = null
    private var warnedMissing = false

    /**
     * 各手势源的配置。由功能模块在 `onEnable` 中注册。
     *
     * 返回 `null` 表示该源当前不可用（开关关闭或参数无效）。参数在每次手势发生时
     * 实时求值，因此用户改了设置立即生效，不需要重新挂监听。
     */
    interface Source {
        /** 用于日志与排查的来源名。 */
        val name: String

        /**
         * 单击计数式触发（多击标题）。返回 [ClickSpec] 表示启用；
         * 返回 null 表示本源不参与或当前关闭。
         */
        fun clickSpec(): ClickSpec?

        /** 长按式触发。返回毫秒阈值表示启用；返回 null 表示不参与或当前关闭。 */
        fun longPressMs(): Long?

        /** 多击条件满足时调用。 */
        fun onClickTriggered()

        /** 长按条件满足时调用。 */
        fun onLongPressTriggered()
    }

    /**
     * 多击配置。[count] 为所需连续点击次数，[windowMs] 为相邻两次点击的最大间隔。
     */
    data class ClickSpec(val count: Int, val windowMs: Long)

    private val sources = LinkedHashSet<Source>()

    fun register(source: Source) {
        sources.add(source)
    }

    fun unregister(source: Source) {
        sources.remove(source)
    }

    /**
     * 在主页 `onResume` 后装配。可安全重复调用——同一 View 只挂一次，
     * 重复调用仅刷新内部状态（`View` 的 setter 本身也是替换语义）。
     *
     * ## 为什么要重试
     *
     * 早期实现只 `root.post` 一次。实测（日志 `home title view not found`）每次都失败：
     * `onResume` 返回时布局尚未完成，标题 TextView 的 `height` 仍为 0，
     * 而 [SecretFriendState.findHomeTitleTextView] 要求 `height > 0` 且能取到全局可见矩形，
     * 于是必然判为「找不到」；`onResume` 又不重发，监听器就永远装不上——
     * 表现为长按/多击标题完全无反应。
     *
     * 改成短间隔轮询：布局就绪通常只差一两帧，[ATTACH_RETRY_TIMES] 次足够，
     * 且成功即停，不会持续占用主线程。
     */
    fun attach(activity: android.app.Activity) {
        val root = activity.window?.decorView ?: return
        attemptAttach(root, ATTACH_RETRY_TIMES)
    }

    /**
     * 尝试装配。[remaining] 为剩余重试次数，递减到 0 即放弃。
     *
     * 失败时不置 [warnedMissing] 之外的任何状态，也不清除 [attachedTo]——
     * 已经在别的 Activity 实例上挂好监听器时，新 Activity 找不到标题不该把它拆掉。
     */
    private fun attemptAttach(root: View, remaining: Int) {
        root.post {
            val title = SecretFriendState.findHomeTitleTextView(root)
            if (title == null) {
                if (remaining > 0) {
                    handler.postDelayed({ attemptAttach(root, remaining - 1) }, ATTACH_RETRY_DELAY_MS)
                } else if (!warnedMissing) {
                    warnedMissing = true
                    WeLogger.w(TAG, "home title view not found after $ATTACH_RETRY_TIMES attempts; title gestures not attached")
                }
                return@post
            }
            if (sources.isEmpty()) return@post

            // TextView 默认不可点击时，触摸流会被父级接管，自计时无法工作；
            // 显式置为可点击/可长按，保证 ACTION_DOWN..UP 完整到达本监听器。
            title.isClickable = true
            title.isLongClickable = true

            if (attachedTo !== title) {
                attachedTo = title
                installListener(title)
                WeLogger.i(TAG, "title gesture dispatcher attached")
            }
        }
    }

    /**
     * 装配统一的触摸监听器。
     *
     * 采用 `OnTouchListener` 而非 `OnClickListener`：只有触摸流能同时表达
     * 「连续 N 次点击」与「按住 M 毫秒」，否则又要回到两个监听器互相覆盖的老问题。
     * 注意**不消费**事件（返回 false），以免影响标题自身的原生长按行为。
     */
    private fun installListener(title: TextView) {
        val slop = ViewConfiguration.get(title.context).scaledTouchSlop
        var clickCount = 0
        var lastClickAt = 0L
        var longPressFired = false
        var downAt = 0L

        val longPressRunnable = Runnable {
            val threshold = longestActiveLongPressMs() ?: return@Runnable
            longPressFired = true
            WeLogger.i(TAG, "title long-press triggered after ${threshold}ms")
            // 复制集合后再遍历：回调里可能注册/注销
            sources.toList().forEach { runCatching { it.onLongPressTriggered() } }
        }

        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = System.currentTimeMillis()
                    longPressFired = false
                    longestActiveLongPressMs()?.let {
                        handler.postDelayed(longPressRunnable, it)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    // 手指滑出标题区域即取消长按（自计时版本必须自己判越界）
                    if (event.x < -slop || event.y < -slop ||
                        event.x > view.width + slop || event.y > view.height + slop
                    ) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    val heldMs = System.currentTimeMillis() - downAt
                    // 长按已触发，或按住时间超过点击阈值：本次抬手不计入点击计数
                    if (longPressFired || heldMs > MAX_TAP_MS) {
                        longPressFired = false
                        return@setOnTouchListener false
                    }
                    val now = System.currentTimeMillis()
                    val spec = shortestWindowClickSpec()
                    if (spec == null) {
                        clickCount = 0
                        return@setOnTouchListener false
                    }
                    // 距离上一次点击超出窗口，重新起算
                    if (clickCount == 0 || now - lastClickAt > spec.windowMs) {
                        clickCount = 1
                    } else {
                        clickCount++
                    }
                    lastClickAt = now
                    if (clickCount >= spec.count) {
                        clickCount = 0
                        WeLogger.i(TAG, "title multi-click (${spec.count}) triggered")
                        sources.toList().forEach { runCatching { it.onClickTriggered() } }
                    } else {
                        WeLogger.d(TAG, "title click $clickCount/${spec.count} (window=${spec.windowMs}ms)")
                    }
                    // 不消费：标题的原生行为（如双击回顶）保持可用
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    longPressFired = false
                    clickCount = 0
                }
            }
            false
        }
    }

    /** 所有已启用来源中最长的长按阈值（多个同时启用时取最长，避免误触发）。 */
    private fun longestActiveLongPressMs(): Long? =
        sources.toList().mapNotNull { runCatching { it.longPressMs() }.getOrNull() }.maxOrNull()

    /**
     * 所有已启用来源中最短的点击窗口、以及对应的点击次数。
     *
     * 多个来源同时启用时取「最容易触发」的一组：窗口最短的那个通常次数也最少
     * （如三击 500ms vs 双击 1000ms），取最易触发符合用户「我明明点了」的预期。
     */
    private fun shortestWindowClickSpec(): ClickSpec? =
        sources.toList()
            .mapNotNull { runCatching { it.clickSpec() }.getOrNull() }
            .minByOrNull { it.windowMs }

    /** 按住超过这个时长视为「长按」，不再计入点击。 */
    private const val MAX_TAP_MS = 500L

    /** 装配重试次数与间隔（见 [attach]）。10 × 100ms = 1s 上限，足够覆盖首帧布局。 */
    private const val ATTACH_RETRY_TIMES = 10
    private const val ATTACH_RETRY_DELAY_MS = 100L

    /** 供功能关闭时清理（例如全部手势源都关了）。 */
    fun reset() {
        attachedTo?.setOnTouchListener(null)
        attachedTo = null
    }
}
