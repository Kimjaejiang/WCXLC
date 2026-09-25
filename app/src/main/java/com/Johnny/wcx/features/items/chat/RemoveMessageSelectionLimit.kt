package com.Johnny.wcx.features.items.chat

import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import org.luckypray.dexkit.DexKitBridge
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookDirectly
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.utils.reflection.bool
import com.Johnny.wcx.utils.reflection.int
import com.Johnny.wcx.utils.reflection.void
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

@Feature(
    name = "解除消息多选数量限制",
    categories = ["聊天"],
    description = "解除聊天界面消息多选至多只能选择 100 条的限制"
)
object RemoveMessageSelectionLimit : SwitchFeature(), IResolveDex {

    private const val SELECTION_LIMIT = 100

    // 不要写 declaredClass(WeMessageApi.classChattingDataAdapter.clazz)：
    // 那个委托属于 WeMessageApi，它的内联解析时机与本体不保证先后。
    // resolveAllDex 会先跑本 feature 的 resolveInlineDex（逐个 findInline），
    // 此时若 classChattingDataAdapter 还没解析，读 .clazz 就会抛
    // "Class not found for key: WeMessageApi:classChattingDataAdapter"，
    // 两个锚点当场降级为 placeholder。改成用类自身的字符串特征定位，
    // 自包含、无跨 feature 顺序依赖。
    //
    // 特征取自 com.tencent.mm.ui.chatting.adapter.k（8.0.78 实证）：
    //   e1() 中 Log.i("MicroMsg.ChattingDataAdapterV3", "[handleMsgChange] isLockNotify:...")
    private val classChattingDataAdapter by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingDataAdapterV3",
                "[handleMsgChange] isLockNotify:",
            )
        }
    }

    private val methodToggleMessageSelection by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classChattingDataAdapter.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes("${PackageNames.WECHAT}.plugin.msg.MsgIdTalker")
            returnType(bool)
        }
    }

    private val methodGetSelectedMessageCount by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classChattingDataAdapter.clazz)
            addUsingField {
                type(CopyOnWriteArraySet::class.java)
            }
            paramCount(0)
            returnType(int)
        }
    }

    private val classChatItemQuickSelect by dexClass {
        searchPackages("${PackageNames.WECHAT}.ui.chatting.component")
        matcher {
            usingEqStrings(
                "MicroMsg.ChatItemQuickSelectComponent",
                "initViews: chattingQuickSelectRootUp="
            )
        }
    }

    private val methodSetQuickSelectViewEnabled1 by dexMethod(
        allowMultiple = true,
        allowFailure = true,
        resultIndex = 0
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val methodSetQuickSelectViewEnabled2 by dexMethod(
        allowMultiple = true,
        allowFailure = true,
        resultIndex = 1
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val selectedMessagesField: Field by lazy {
        methodToggleMessageSelection.method.declaringClass.declaredFields.single {
            it.type == CopyOnWriteArraySet::class.java
        }.makeAccessible()
    }

    private data class TemporarilyRemovedSelections(
        val selectedMessages: CopyOnWriteArraySet<Any>,
        val removed: List<Any>
    )

    private val selectedMessageCountOverride = ThreadLocal<Int>()

    override fun resolveDex(dexKit: DexKitBridge) {
        // 先把类解出来，后面四个方法的 matcher 都要读它的 .clazz。
        // 在同一个 resolveDex 里显式排序，不再依赖跨 feature 的解析时机。
        classChattingDataAdapter.findInline(dexKit)

        listOf(
            methodToggleMessageSelection,
            methodGetSelectedMessageCount,
            methodSetQuickSelectViewEnabled1,
            methodSetQuickSelectViewEnabled2,
        ).forEach { it.findInline(dexKit) }
    }

    // 本地 Xposed 桥的 MethodHookParam.extra 为 val+Bundle（非 fork 的 Any），
    // 临时状态改用 ThreadLocal 传递（与 selectedMessageCountOverride 同风格）
    private val tempRemovedSelections = ThreadLocal<TemporarilyRemovedSelections?>()

    override fun onEnable() {
        // 这两个锚点才是本功能的实际依赖：少了它们，下面 methodGetSelectedMessageCount
        // 的 hookBefore 与末尾 methodToggleMessageSelection 的 hookDirectly 会经
        // .method -> error() 抛出。BaseFeature.enable 的 runCatching 会吞掉异常并把
        // isActive 置回 false —— 表现是「开关打开了但功能没生效」，没有任何用户可见
        // 提示，所以这里必须自己判、自己说明原因。
        //
        // 注意不要改回判 classChattingDataAdapter：它 8.0.78 下是命中的
        // (com.tencent.mm.ui.chatting.adapter.k)，判它等于守卫恒不触发。
        if (methodGetSelectedMessageCount.isPlaceholder ||
            methodToggleMessageSelection.isPlaceholder
        ) {
            WeLogger.w(
                "RemoveMessageSelectionLimit",
                "消息多选相关锚点未匹配，本功能在当前微信版本不可用（不会生效）"
            )
            return
        }
        listOf(
            methodSetQuickSelectViewEnabled1,
            methodSetQuickSelectViewEnabled2
        ).forEach {
            it.hookBefore {
                args[0] = true
            }
        }

        methodGetSelectedMessageCount.hookBefore {
            selectedMessageCountOverride.get()?.let {
                result = it
            }
        }

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val adapter = param.thisObject ?: return
                val message = param.args[0] ?: return
                @Suppress("UNCHECKED_CAST")
                val selectedMessages = selectedMessagesField.get(adapter) as CopyOnWriteArraySet<Any>
                if (message in selectedMessages || selectedMessages.size < SELECTION_LIMIT) return

                // Let WeChat run its original add and UI refresh path with 99 existing selections.
                val removed = selectedMessages.take(selectedMessages.size - SELECTION_LIMIT + 1)
                selectedMessages.removeAll(removed.toSet())
                tempRemovedSelections.set(TemporarilyRemovedSelections(selectedMessages, removed))
                selectedMessageCountOverride.set(selectedMessages.size + removed.size + 1)
            }

            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val state = tempRemovedSelections.get() ?: return
                val remainingAndNew = state.selectedMessages.toList()
                state.selectedMessages.clear()
                state.selectedMessages.addAll(state.removed)
                state.selectedMessages.addAll(remainingAndNew)
                selectedMessageCountOverride.remove()
            }
        }

        registerUnhook(methodToggleMessageSelection.method.hookDirectly(hook))
    }
}
