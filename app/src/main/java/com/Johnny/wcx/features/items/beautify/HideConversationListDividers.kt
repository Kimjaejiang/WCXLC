package com.Johnny.wcx.features.items.beautify

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListView
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XposedHelpers

@Feature(name = "隐藏对话列表分割线", categories = ["聊天", "界面美化"], description = "隐藏对话列表（含归拢文件夹）里的分割线")
object HideConversationListDividers : SwitchFeature(), IResolveDex {

    private const val TAG = "HideDivider"

    /** 主页对话列表（ListView 适配器）。 */
    private val methodConversationWithCacheAdapterGetView by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            usingEqStrings("MicroMsg.ConversationWithCacheAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
        }
    }

    private val methodMvvmConversationAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.ConversationAdapter.MvvmConversationAdapter", "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d")
            }
            name = "getView"
        }
    }

    /** androidx RecyclerView 的 item 绑定（归拢文件夹等页面）。 */
    private val methodRecyclerOnBind by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "onBindViewHolder"
            paramTypes("androidx.recyclerview.widget.RecyclerView\$ViewHolder", "int")
        }
    }

    /** support v7 RecyclerView 的 item 绑定。 */
    private val methodSupportRecyclerOnBind by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "onBindViewHolder"
            paramTypes("android.support.v7.widget.RecyclerView\$ViewHolder", "int")
        }
    }

    /** 所有 ListView Adapter 的 getView（覆盖文件夹内等全部 ListView 页面）。 */
    private val methodAllListViewGetView by dexMethod(allowFailure = true, allowMultiple = true) {
        matcher {
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
        }
    }

    /** 所有 RecyclerView（含微信子类）挂载 item 时必经的基类 final 方法，兜底覆盖。 */
    private val methodDispatchChildAttached by dexMethod(allowFailure = true) {
        searchPackages("androidx.recyclerview.widget")
        matcher {
            name = "dispatchChildAttached"
            paramTypes("android.view.View")
        }
    }

    override fun onEnable() {
        WeLogger.i(TAG, "onEnable: cacheAdapter=${!methodConversationWithCacheAdapterGetView.isPlaceholder}, mvvmAdapter=${!methodMvvmConversationAdapterGetView.isPlaceholder}, rvOnBind=${!methodRecyclerOnBind.isPlaceholder}, supportRvOnBind=${!methodSupportRecyclerOnBind.isPlaceholder}, allGetView=${!methodAllListViewGetView.isPlaceholder}, dispatchChildAttached=${!methodDispatchChildAttached.isPlaceholder}")

        if (!methodConversationWithCacheAdapterGetView.isPlaceholder) {
            methodConversationWithCacheAdapterGetView.hookAfter {
                handleItemView(result as? View)
            }
        }

        if (!methodMvvmConversationAdapterGetView.isPlaceholder) {
            methodMvvmConversationAdapterGetView.hookAfter {
                handleItemView(result as? View)
            }
        }

        if (!methodAllListViewGetView.isPlaceholder) {
            methodAllListViewGetView.hookAfter {
                handleItemView(result as? View)
            }
        }

        if (!methodRecyclerOnBind.isPlaceholder) {
            methodRecyclerOnBind.hookAfter {
                handleHolder(args?.getOrNull(0))
            }
        }

        if (!methodSupportRecyclerOnBind.isPlaceholder) {
            methodSupportRecyclerOnBind.hookAfter {
                handleHolder(args?.getOrNull(0))
            }
        }

        if (!methodDispatchChildAttached.isPlaceholder) {
            methodDispatchChildAttached.hookAfter {
                handleItemView(args?.getOrNull(0) as? View)
            }
        }

        // 系统级：所有 View 挂载（addViewInLayout / addView）都检查是否分割线。
        // 不按 LayoutParams 过滤（item 内动态 add 的线是普通 LayoutParams），按 View 特征预判后延迟检查。
        runCatching {
            XposedHelpers.findAndHookMethod(
                ViewGroup::class.java,
                "addViewInLayout",
                View::class.java, Int::class.javaPrimitiveType,
                ViewGroup.LayoutParams::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val child = param.args?.getOrNull(0) as? View ?: return
                        maybeDivider(child)
                    }
                }
            )
            WeLogger.i(TAG, "system addViewInLayout hooked")
        }.onFailure { WeLogger.w(TAG, "hook addViewInLayout failed: $it") }

        // 动态 addView 生成的线（onBind 里 new View addView）
        runCatching {
            XposedHelpers.findAndHookMethod(
                ViewGroup::class.java,
                "addView",
                View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val child = param.args?.getOrNull(0) as? View ?: return
                        maybeDivider(child)
                    }
                }
            )
            WeLogger.i(TAG, "system addView hooked")
        }.onFailure { WeLogger.w(TAG, "hook addView failed: $it") }

        // 防恢复：微信把已隐藏分割线的 visibility 改回 VISIBLE 时强制保持 GONE。
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.getTag(LOCK_TAG) != null && param.args?.getOrNull(0) != View.GONE) {
                            WeLogger.i(TAG, "restore-block: ${v.javaClass.simpleName}@${v.width}x${v.height}")
                            param.result = null
                        }
                    }
                }
            )
            WeLogger.i(TAG, "system setVisibility hooked")
        }.onFailure { WeLogger.w(TAG, "hook setVisibility failed: $it") }

        // ListView.divider 绘制的 item 间分割线（非 View）：把 dividerHeight 恒置 0。
        runCatching {
            XposedHelpers.findAndHookMethod(
                ListView::class.java,
                "setDividerHeight",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (param.args?.getOrNull(0) != 0) {
                            WeLogger.i(TAG, "dividerHeight-block: ${param.thisObject?.javaClass?.simpleName} h=${param.args?.getOrNull(0)}")
                            param.args[0] = 0
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                ListView::class.java,
                "setDivider",
                android.graphics.drawable.Drawable::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        (param.thisObject as? ListView)?.dividerHeight = 0
                    }
                }
            )
            de.robv.android.xposed.XposedBridge.hookAllConstructors(
                ListView::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val lv = param.thisObject as? ListView ?: return
                        lv.dividerHeight = 0
                        lv.divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    }
                }
            )
            WeLogger.i(TAG, "system listview divider hooked")
        }.onFailure { WeLogger.w(TAG, "hook listview divider failed: $it") }

        // item 背景描边剥离：归拢/通讯录/设置 的 item 根 background drawable 自带底部描边（非子 View）。
        // 只处理列表 item 根（layoutParams 为 RV/ListView 类型），递归清除 GradientDrawable stroke / 1~6px 线层。
        runCatching {
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    maybeStripItemBackground(v)
                }
            }
            XposedHelpers.findAndHookMethod(View::class.java, "setBackground", android.graphics.drawable.Drawable::class.java, hook)
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundResource", Int::class.javaPrimitiveType, hook)
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", android.graphics.drawable.Drawable::class.java, hook)
            WeLogger.i(TAG, "system setBackground hooked")
        }.onFailure { WeLogger.w(TAG, "hook setBackground failed: $it") }

        // 上屏兜底：item 可能在离屏预构建时挂载（挂载时未 attach，post 检查被 isAttachedToWindow 跳过），
        // 之后再 attach 上屏时补查一次，防止漏掉分割线。
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.getTag(SCAN_TAG) != null || v.getTag(LOCK_TAG) != null) return
                        if (v is ViewGroup && v.childCount > 0) return
                        scanDividerAfterLayout(v, 0)
                    }
                }
            )
            WeLogger.i(TAG, "system onAttachedToWindow hooked")
        }.onFailure { WeLogger.w(TAG, "hook onAttachedToWindow failed: $it") }

        // 识别页面：记录当前 resumed Activity——用于限定处理范围（文件夹/搜索），避免误伤放弃的页面。
        runCatching {
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val a = param.thisObject as? android.app.Activity ?: return
                        currentActivity = a.javaClass.name
                    }
                }
            )
            WeLogger.i(TAG, "activity onResume hooked")
        }.onFailure { WeLogger.w(TAG, "hook onResume failed: $it") }

        // 最终兜底：拦截"线 View"的绘制（高度 1~6px 全宽、非内容容器）——不依赖 GONE/识别机制，
        // 对 attach 阶段漏检（初始 GONE/未布局）或列表级动态出现的线 View 直接不绘制。
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "draw",
                android.graphics.Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.getTag(LOCK_TAG) != null) return
                        if (v is ViewGroup && v.childCount > 0) return
                        if (v.height !in 1..6) return
                        val parent = v.parent as? View ?: return
                        if (v.width < parent.width / 3) return
                        param.result = null
                        WeLogger.i(TAG, "draw-block: ${v.javaClass.simpleName}@${v.width}x${v.height}")
                    }
                }
            )
            WeLogger.i(TAG, "system draw hooked")
        }.onFailure { WeLogger.w(TAG, "hook draw failed: $it") }

        // 注：微信 8.0.77 的 RecyclerView 无 ItemDecoration 内部类（R8 已删除），分割线全部是 item 内 View，
        // 由下方 addViewInLayout 尺寸识别处理。无需装饰器 hook。
    }

    private fun handleHolder(holder: Any?) {
        if (holder == null) return
        val itemView = runCatching { holder.javaClass.getField("itemView").get(holder) as? View }.getOrNull() ?: return
        handleItemView(itemView)
    }

    private fun handleItemView(itemView: View?) {
        if (itemView !is ViewGroup) return
        // item 挂载/绑定时可能尚未测量（height=0），延迟到布局完成后查找分割线
        itemView.post {
            if (!itemView.isAttachedToWindow) return@post
            val divider = findDivider(itemView)
            if (divider != null) {
                hideAndLock(divider)
            }
        }
    }

    // tag key 校验：新版 Android 要求 (key >>> 24) >= 2（0x01000000 系统段也会被拒），
    // 且不能与微信业务冲突（微信资源是 0x7f 段）——用 0x02000000 段（合法且微信不会用）。
    private val LOCK_TAG = 0x02000000
    private val BG_TAG = 0x02000001
    private val SCAN_TAG = 0x02000002

    /** 当前前台 Activity 类名（onResume 记录）——用于限定背景替换/裁剪的页面范围。 */
    @Volatile
    private var currentActivity: String? = null

    /** 延迟到布局完成后按尺寸判定是否为分割线。高度仍为 0（尚未布局）时挂布局监听补查，避免误标记漏检。 */
    private fun scanDividerAfterLayout(v: View, retry: Int) {
        v.post {
            if (!v.isAttachedToWindow) return@post
            val parent = v.parent as? View ?: return@post
            val h = v.height
            if (h in 1..6 && v.width >= parent.width / 3) {
                hideAndLock(v)
            } else if (h == 0 && retry < 2) {
                scanDividerAfterLayout(v, retry + 1)
            } else {
                v.setTag(SCAN_TAG, true)
            }
        }
    }

    /** 预判：延迟到布局完成后按尺寸确认（高度 1~6px、宽度 ≥ 父宽 1/3）并锁定隐藏。
     *  不依赖 background/类名：微信分割线可能是自定义 View（onDraw 画线、无背景）或空 LinearLayout。 */
    private fun maybeDivider(child: View) {
        if (child is ViewGroup && child.childCount > 0) return  // 有内容的容器不是线
        child.post {
            if (!child.isAttachedToWindow) return@post
            val parent = child.parent as? View ?: return@post
            if (child.height in 1..6 && child.width >= parent.width / 3) {
                hideAndLock(child)
            }
        }
    }

    /** 隐藏并锁定：GONE + tag 标记 + OnGlobalLayoutListener 持续强制（对抗微信 bind/post 恢复可见性）。 */
    private fun hideAndLock(divider: View) {
        if (divider.getTag(LOCK_TAG) != null) return
        divider.setTag(LOCK_TAG, true)
        divider.isGone = true
        val loc = IntArray(2)
        divider.getLocationOnScreen(loc)
        WeLogger.i(TAG, "hide+lock: ${divider.javaClass.simpleName}@(${loc[0]},${loc[1]}) ${divider.width}x${divider.height}")
        divider.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (divider.visibility != View.GONE) {
                    divider.visibility = View.GONE
                }
            }
        })
    }

    /** 延迟到挂载后，处理所有可能的列表 item 容器（宽 > 300、高 > 40）——
     *  按当前 Activity 限定处理范围：
     *    ConvBoxServiceConversationUI（归拢文件夹）→ 9-patch 背景替换为页面同色（不裁剪）
     *    FTSMainUI（搜索）→ 裁剪底部 1px
     *    其余（LauncherUI 各 tab、设置等）→ 不处理 */
    private fun maybeStripItemBackground(v: View) {
        if (v.getTag(BG_TAG) != null) return
        v.post {
            if (!v.isAttachedToWindow) return@post
            if (v.height < 40 || v.width < 300) return@post
            val act = currentActivity ?: return@post
            when {
                act.endsWith("ConvBoxServiceConversationUI") -> {
                    // 文件夹：只处理全宽容容器（item 根）——排除头像区等子容器，避免误替换成灰底
                    val screenW = android.content.res.Resources.getSystem().displayMetrics.widthPixels
                    if (v.width < screenW - 50) return@post
                    stripItemBackgroundReplace(v)
                }
                act.endsWith("FTSMainUI") -> clipItemBottom(v)
                else -> return@post
            }
        }
    }

    /** 文件夹：背景含 9-patch → 替换该层为页面同色（保留 StateList 结构，不裁剪）。 */
    private fun stripItemBackgroundReplace(v: View) {
        if (v.getTag(BG_TAG) != null) return
        v.setTag(BG_TAG, true)
        val bg = v.background ?: return
        WeLogger.i(TAG, "bg: ${v.javaClass.simpleName} bg=${bg.javaClass.name}")
        if (bg is android.graphics.drawable.NinePatchDrawable) {
            val c = findReplaceColor(v)
            v.background = android.graphics.drawable.ColorDrawable(c)
            WeLogger.i(TAG, "bg-replace: ${v.javaClass.simpleName} NinePatch→Color(${Integer.toHexString(c)})")
        } else if (bg is android.graphics.drawable.StateListDrawable) {
            var hasNine = false
            for (i in 0 until bg.stateCount) {
                if (bg.getStateDrawable(i) is android.graphics.drawable.NinePatchDrawable) { hasNine = true; break }
            }
            if (hasNine) {
                val c = findReplaceColor(v)
                val setter = runCatching {
                    android.graphics.drawable.StateListDrawable::class.java.getDeclaredMethod(
                        "setStateDrawable",
                        Int::class.javaPrimitiveType,
                        android.graphics.drawable.Drawable::class.java
                    ).apply { isAccessible = true }
                }.getOrNull()
                for (i in 0 until bg.stateCount) {
                    val d = bg.getStateDrawable(i)
                    if (d is android.graphics.drawable.NinePatchDrawable) {
                        runCatching { setter?.invoke(bg, i, android.graphics.drawable.ColorDrawable(c)) }.onFailure { }
                    }
                }
                WeLogger.i(TAG, "bg-replace-sld: ${v.javaClass.simpleName} StateList NinePatch→Color(${Integer.toHexString(c)})")
            }
        }
    }

    /** 搜索：裁剪 item 底部 1px（覆盖 9-patch/View 线）。 */
    private fun clipItemBottom(v: View) {
        if (v.getTag(BG_TAG) != null) return
        v.setTag(BG_TAG, true)
        val w = v.width
        val h = v.height
        if (w > 0 && h > 1) {
            v.clipBounds = android.graphics.Rect(0, 0, w, h - 1)
            WeLogger.i(TAG, "clip: ${v.javaClass.simpleName} ${w}x${h}")
        }
    }

    /** 背景亮度（0-255）。 */
    private fun brightness(c: Int): Float =
        0.299f * android.graphics.Color.red(c) +
            0.587f * android.graphics.Color.green(c) +
            0.114f * android.graphics.Color.blue(c)

    /** 取替换背景色：只取深色背景（亮度 < 0x40），浅色/中灰跳过——亮色模式兜底白色，深色模式取深灰背景。 */
    private fun findReplaceColor(v: View): Int {
        val rvc = runCatching { (v.rootView.background as? android.graphics.drawable.ColorDrawable)?.color }.getOrNull()
        if (rvc != null && rvc != 0 && brightness(rvc) < 0x40) return rvc
        var p = v.parent as? View
        while (p != null) {
            val pb = p.background
            when (pb) {
                is android.graphics.drawable.ColorDrawable ->
                    if (pb.color != 0 && brightness(pb.color) < 0x40) return pb.color
                is android.graphics.drawable.GradientDrawable -> {
                    val c = runCatching {
                        android.graphics.drawable.GradientDrawable::class.java
                            .getMethod("getColor").invoke(pb) as? Int
                    }.getOrNull()
                    if (c != null && c != 0 && brightness(c) < 0x40) return c
                }
            }
            p = p.parent as? View
        }
        val night = (v.context?.resources?.configuration?.uiMode
            ?.and(android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            ?.let { it == android.content.res.Configuration.UI_MODE_NIGHT_YES }) == true
        return if (night) 0xFF1C1C1C.toInt() else android.graphics.Color.WHITE
    }

    /** BFS 查找分割线：高度 1~6px 且宽度 ≥ 父宽 1/3 的横向线条 View（8.0.77 实际为 1030x1/1272x1）。 */
    private fun findDivider(root: ViewGroup): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v !== root && v.height in 1..6 && v.width >= root.width / 3 && v.visibility != View.GONE) {
                return v
            }
            if (v is ViewGroup) {
                val group: ViewGroup = v
                for (i in 0 until group.childCount) {
                    queue.addLast(group.getChildAt(i))
                }
            }
        }
        return null
    }
}
