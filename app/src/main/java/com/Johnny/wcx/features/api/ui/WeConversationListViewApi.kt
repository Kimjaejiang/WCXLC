package com.Johnny.wcx.features.api.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.BaseAdapter
import android.widget.ListView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isGone
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.ui.utils.findViewByChildIndexes
import com.Johnny.wcx.utils.HookParam
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.runOnUiThread
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.luckypray.dexkit.DexKitBridge

@Feature(
    name = "会话列表 View 绑定监听服务",
    categories = ["API"],
    description = "提供会话列表 View 绑定监听能力"
)
object WeConversationListViewApi : ApiFeature(), IResolveDex {

    data class BindContext(
        val position: Int,
        val itemCount: Int,
        val previousConversation: Any?,
        val nextConversation: Any?,
    )

    fun interface IBindViewListener {
        fun onBind(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam, row: View, conversation: Any, context: BindContext)
    }

    private const val TAG = "WeConversationListViewApi"

    private val listeners = CopyOnWriteArrayList<IBindViewListener>()
    private var latestAdapter: WeakReference<BaseAdapter>? = null
    private var latestListView: WeakReference<ListView>? = null

    private val methodLegacyGetView by dexMethod(allowFailure = true) {
        // 8.0.78: 不要再限制 searchPackages("com.tencent.mm.ui.conversation")。
        // 两个会话列表适配器都被混淆搬出了那个包：
        //   legacy -> jo5.e（持 "MicroMsg.ConversationWithCacheAdapter" 日志标签）
        //   mvvm   -> jo5.y0（getView 在它身上）
        // 加了包限制就恒落空 —— 注意上面 methodMvvmGetView 从没加过包限制，
        // 所以它一直命中，这正是差异所在。
        // 两个特征串在 8.0.78 都还在（dexdump 实证），按签名+串定位即可。
        // 放宽后不会误伤：hookBinding 里用 `thisObject as? BaseAdapter` 过滤，
        // 非适配器的同名调用会被挡掉（见下方 hookBinding）。
        matcher {
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType = "android.view.View"
            usingEqStrings(
                "MicroMsg.ConversationWithCacheAdapter",
                "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
            )
        }
    }
    private val methodMvvmGetView by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings(
                    "MicroMsg.ConversationAdapter.MvvmConversationAdapter",
                    "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
                )
            }
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType = "android.view.View"
        }
    }

    override fun onEnable() {
        val before = bindCallCount
        hookBinding(methodLegacyGetView)
        hookBinding(methodMvvmGetView)
        WeLogger.i(
            TAG,
            "onEnable 完成: legacyPlaceholder=${methodLegacyGetView.isPlaceholder}, " +
                "mvvmPlaceholder=${methodMvvmGetView.isPlaceholder}, calls=$before"
        )
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodMvvmGetView.findInline(dexKit)
        methodLegacyGetView.findInline(dexKit)

        // 8.0.78 实机核对（全部 17 个 dex 逐个搜字符串）：
        // legacy 适配器的那个 getView 已不存在 —— 微信完成了会话列表的 mvvm 重构。
        // 完整 dup 日志 "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d"
        // 全包只出现在 jo5.y0.getView 一处，而它持有的是 MvvmConversationAdapter 标签
        // （已由上面的 methodMvvmGetView 命中）；
        // 持 "MicroMsg.ConversationWithCacheAdapter" 标签的 jo5.e 是 abstract 基类，没有 getView。
        // matcher 要求两个串同处一类，在当前版本下无解 —— 不是 matcher 写错，是目标已被移除。
        //
        // 功能本身不受影响：mvvm 锚点覆盖了唯一的 getView，hookBinding 的两个入口
        // 只要有一个命中，监听就能装上。所以这里只影响上报口径，标为刻意缺席。
        if (methodLegacyGetView.isPlaceholder) {
            methodLegacyGetView.setPlaceholderDescriptor(
                reason = "8.0.78 会话列表已全面 mvvm 化，legacy getView 已从宿主移除（由 methodMvvmGetView 覆盖）"
            )
        }
    }

    fun addListener(listener: IBindViewListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: IBindViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(TAG, "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}")
    }

    fun refresh() {
        runOnUiThread {
            val adapter = latestAdapter?.get() ?: return@runOnUiThread
            val listView = latestListView?.get()
            if (listView != null && listView.adapter !== adapter) return@runOnUiThread
            dividerCoordinator.applyListView(listView)
            adapter.notifyDataSetChanged()
        }
    }

    fun setDividerHidden(owner: Any, hidden: Boolean) {
        dividerCoordinator.setHidden(owner, hidden)
        refresh()
    }

    fun setRowDividerHidden(owner: Any, row: View, hidden: Boolean) {
        dividerCoordinator.setRowHidden(owner, row, hidden)
        dividerCoordinator.apply(row, latestListView?.get())
    }

    fun removeDividerOwner(owner: Any) {
        dividerCoordinator.removeOwner(owner)
        refresh()
    }

    /** hook 回调被触发次数，用于区分「没装上」与「装上了但未被调用」 */
    @Volatile
    private var bindCallCount = 0

    private fun hookBinding(method: DexMethodDelegate) {
        if (method.isPlaceholder) {
            WeLogger.i(TAG, "hookBinding: ${method.key} 未解析成功，跳过（不参与绑定）")
            return
        }
        method.hookAfter {
            // 首次回调打一条，兼作「hook 真的挂上了」的运行时证据（不刷屏）
            if (bindCallCount == 0) {
                WeLogger.i(TAG, "hook 首次回调: ${method.key}，绑定链路已生效")
            }
            bindCallCount++
            // 8.0.78: the same matcher can hit a method whose receiver is not the
            // adapter (observed on LauncherUI); skip those calls instead of throwing
            // ClassCastException, which used to abort the whole binding hook action.
            val row = result as? View ?: return@hookAfter
            val adapter = thisObject as? BaseAdapter ?: return@hookAfter
            val position = args[0] as Int
            val conversation = adapter.getItem(position)!!
            val bindContext = BindContext(
                position = position,
                itemCount = adapter.count,
                previousConversation = if (position > 0) adapter.getItem(position - 1) else null,
                nextConversation = if (position + 1 < adapter.count) adapter.getItem(position + 1) else null,
            )
            if (latestAdapter?.get() !== adapter) latestAdapter = WeakReference(adapter)
            (args[2] as? ListView)?.let { listView ->
                if (latestListView?.get() !== listView) latestListView = WeakReference(listView)
            }

            for (listener in listeners) {
                try {
                    listener.onBind(this, row, conversation, bindContext)
                } catch (error: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", error)
                }
            }
            dividerCoordinator.apply(row, latestListView?.get())
        }
    }

    @Suppress("ClassName")
    private object dividerCoordinator {
        private data class RowDividerState(val originalVisibility: Int)
        private data class ListDividerState(
            val originalDivider: Drawable?,
            val originalDividerHeight: Int,
            val moduleDivider: ColorDrawable,
        )

        private val hiddenOwners = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
        )
        private val rowStates = WeakHashMap<View, RowDividerState>()
        private val rowHiddenOwners = WeakHashMap<View, MutableSet<Any>>()
        private val listStates = WeakHashMap<ListView, ListDividerState>()

        fun setHidden(owner: Any, hidden: Boolean) {
            if (hidden) hiddenOwners.add(owner) else hiddenOwners.remove(owner)
        }

        fun setRowHidden(owner: Any, row: View, hidden: Boolean) {
            val owners = rowHiddenOwners[row]
            if (hidden) {
                (owners ?: Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also {
                    rowHiddenOwners[row] = it
                }).add(owner)
            } else {
                owners?.remove(owner)
                if (owners != null && owners.isEmpty()) rowHiddenOwners.remove(row)
            }
        }

        fun removeOwner(owner: Any) {
            hiddenOwners.remove(owner)
            val iterator = rowHiddenOwners.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.value.remove(owner)
                if (entry.value.isEmpty()) iterator.remove()
            }
        }

        fun apply(row: View, listView: ListView?) {
            applyRowDivider(row)
            applyListView(listView)
        }

        fun applyListView(listView: ListView?) {
            listView ?: return
            if (hiddenOwners.isNotEmpty()) {
                val state = listStates.getOrPut(listView) {
                    ListDividerState(listView.divider, listView.dividerHeight, Color.TRANSPARENT.toDrawable())
                }
                if (listView.divider !== state.moduleDivider) listView.divider = state.moduleDivider
                if (listView.dividerHeight != 0) listView.dividerHeight = 0
            } else {
                val state = listStates.remove(listView) ?: return
                if (listView.divider === state.moduleDivider) {
                    listView.divider = state.originalDivider
                    listView.dividerHeight = state.originalDividerHeight
                }
            }
        }

        private fun applyRowDivider(row: View) {
            val divider: View = row.findViewByChildIndexes<View>(0, 1, 1, 1)
                ?: row.findViewByChildIndexes<View>(0, 1, 1)
                ?: return
            if (isHidden(row)) {
                rowStates.getOrPut(divider) { RowDividerState(divider.visibility) }
                if (divider.visibility != View.GONE) divider.visibility = View.GONE
            } else {
                val state = rowStates.remove(divider) ?: return
                if (divider.isGone) divider.visibility = state.originalVisibility
            }
        }

        private fun isHidden(row: View): Boolean =
            hiddenOwners.isNotEmpty() || rowHiddenOwners[row]?.isNotEmpty() == true
    }
}
