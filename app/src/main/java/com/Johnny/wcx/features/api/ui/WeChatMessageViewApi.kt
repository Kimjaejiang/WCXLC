package com.Johnny.wcx.features.api.ui

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "消息 View 创建监听服务", categories = ["API"], description = "提供消息 View 创建监听能力")
object WeChatMessageViewApi : ApiFeature(), IResolveDex {

    fun interface ICreateViewListener {
        fun onCreateView(
            param: XC_MethodHook.MethodHookParam, view: View
        )
    }

    /**
     * 带消息列表的监听器。
     *
     * 消息列表**不能**从 itemView 往上爬父链拿：`[onBindView]` 触发时 itemView
     * 还没挂到列表上，`view.parent` 恒为 null（实测确认）。
     * 但 ViewHolder 自己的字段里就存着列表（`mOwnerRecyclerView` / `m`），
     * 所以由这里直接取出来交给监听器，比父链可靠得多。
     */
    fun interface ICreateViewWithListListener {
        fun onCreateViewWithList(
            param: XC_MethodHook.MethodHookParam, view: View, messageList: ViewGroup
        )
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()
    private val withListListeners = CopyOnWriteArrayList<ICreateViewWithListListener>()

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun addListener(listener: ICreateViewWithListListener) {
        if (!withListListeners.contains(listener)) {
            withListListeners.add(listener)
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    fun removeListener(listener: ICreateViewWithListListener) {
        val removed = withListListeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current withList count: ${withListListeners.size}"
        )
    }

    private const val TAG = "WeChatMessageViewApi"

    private val methodChatItemOnBindView by dexMethod {
        matcher {
            usingStrings(
                "MicroMsg.MvvmChattingItem",
                "[onBindView]"
            )
        }
    }

    override fun onEnable() {
        val m = runCatching { methodChatItemOnBindView.method }.getOrNull()
        if (m == null) {
            WeLogger.w(TAG, "probe: MvvmChattingItem onBindView method NOT FOUND - hide strategy will never fire")
            return
        }
        WeLogger.i(TAG, "probe: armed onBindView method=" + m.declaringClass.name + " params=" + m.parameterCount)
        m.hookAfter {
            val holder = args[0]
            val view = candidateViewFrom(holder) ?: return@hookAfter

            // 诊断：确认拿到的 view 是不是真正的根 itemView。
            // 判据：根 itemView 挂到列表上后 parent 不应为 null；
            // 若 parent 恒为 null，说明取错了字段（拿到的大概率是 holder 里的子 View）。
            if (diagCount++ < 3) {
                WeLogger.w(
                    TAG,
                    "DIAG holder=${holder.javaClass.name} viewCls=${view.javaClass.name} " +
                        "id=${view.id} parent=${view.parent?.javaClass?.name ?: "null"} " +
                        "holderViewFields=" + holderFields(holder)
                )
            }

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }

            // 把消息列表一并交给需要的监听器（父链拿不到，只能从 holder 字段取）。
            if (withListListeners.isNotEmpty()) {
                val list = messageListFrom(holder)
                if (list != null) {
                    for (listener in withListListeners) {
                        try {
                            listener.onCreateViewWithList(this, view, list)
                        } catch (ex: Exception) {
                            WeLogger.e(TAG, "withList listener ${listener.javaClass.name} threw", ex)
                        }
                    }
                }
            }
        }
    }

    private var diagCount = 0

    /** 列出 holder 里所有 View 类型字段（名=类），用来确认哪个才是根 itemView。 */
    private fun holderFields(holder: Any): String = buildString {
        var c: Class<*>? = holder.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 6) {
            c.declaredFields.forEach { f ->
                if (View::class.java.isAssignableFrom(f.type)) {
                    val v = runCatching { f.isAccessible = true; f.get(holder) as? View }.getOrNull()
                    append(f.name).append('=').append(v?.javaClass?.simpleName ?: "null").append(' ')
                }
            }
            c = c.superclass
        }
    }

    /**
     * 从 ViewHolder 字段里取消息列表。
     *
     * 实测 holder 字段形态：`m`、`mOwnerRecyclerView` 都是消息列表
     * （`ScrollControlRecyclerView`），而 `itemView` 是整行根视图。
     * 优先取名明确的 `mOwnerRecyclerView`，再退到 `m`。
     */
    private fun messageListFrom(holder: Any): ViewGroup? {
        val prefer = listOf("mOwnerRecyclerView", "m", "mRecyclerView")
        var c: Class<*>? = holder.javaClass
        var depth = 0
        val viewFields = mutableMapOf<String, ViewGroup>()
        while (c != null && c != Any::class.java && depth++ < 6) {
            c.declaredFields.forEach { f ->
                if (ViewGroup::class.java.isAssignableFrom(f.type)) {
                    val v = runCatching { f.isAccessible = true; f.get(holder) as? ViewGroup }.getOrNull()
                    if (v != null) viewFields[f.name] = v
                }
            }
            c = c.superclass
        }
        for (name in prefer) viewFields[name]?.let { return it }
        return null
    }

    /**
     * 从 ViewHolder 里取**根 itemView**。
     *
     * 优先按字段名 `itemView` 取（RecyclerView 惯例，OKK 也这么做）；
     * 取不到再退回「第一个 View 类型字段」。
     *
     * 之前只按「第一个 View 字段」取，可能拿到 holder 里的**子 View**，
     * 其父链结构与真实 itemView 不同，导致后续按父链找消息列表全部失败。
     */
    private fun candidateViewFrom(holder: Any): View? {
        var c: Class<*>? = holder.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 6) {
            val f = c.declaredFields.firstOrNull {
                it.name == "itemView" && View::class.java.isAssignableFrom(it.type)
            }
            if (f != null) {
                return runCatching { f.isAccessible = true; f.get(holder) as? View }.getOrNull()
            }
            c = c.superclass
        }
        return runCatching {
            holder.reflekt().firstField { type = View::class; superclass() }.get() as? View
        }.getOrNull()
    }

    fun getChattingContextFromParam(param: XC_MethodHook.MethodHookParam): Any {
        return param.thisObject.reflekt()
            .firstField { type = WeMessageApi.classChattingContext.clazz }
            .get()!!
    }

    fun getMsgInfoFromParam(param: XC_MethodHook.MethodHookParam): MessageInfo {
        val chattingDataAdapter = param.thisObject.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get()!!
        val msgId = param.args[2] as Int
        val msgInfo = chattingDataAdapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(msgId)!!
        return MessageInfo(msgInfo)
    }
}
