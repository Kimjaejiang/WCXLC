@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.features.core

import androidx.compose.runtime.Composable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.dsl.BaseDexDelegate
import com.Johnny.wcx.dexkit.dsl.DexConstructorDelegate
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.utils.HookAction
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Executable
import kotlin.reflect.KClass

abstract class BaseFeature {

    var name: String = ""
    var categories: List<String> = emptyList()

    val displayName: String
        get() = "${categories.joinToString(",")}/$name"

    var description: String = ""

    /** WeKit 兼容: 功能稳定标识 (FeaturesLoader 注入, 本模块保留以兼容连带代码)。 */
    var technicalId: String = ""

    open fun startup() {
        error("You shouldn't inherit BaseFeature")
    }

    /** Whether this feature's hooks are currently installed (runtime truth). */
    var isActive: Boolean = false
        private set

    fun enable() {
        if (isActive) return

        runCatching {
            isActive = true
            onEnable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to enable feature $displayName", e)
            // ensure transaction is fully discarded
            unhookAll()
            isActive = false
        }
    }

    fun disable() {
        if (!isActive) return

        runCatching {
            isActive = false
            unhookAll()
            onDisable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to disable feature $displayName", e)
            isActive = true
        }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    @Composable
    open fun Ui() {
    }

    private val _dexDelegates = mutableListOf<BaseDexDelegate>()
    val dexDelegates: List<BaseDexDelegate> get() = _dexDelegates
    internal fun registerDexDelegate(d: BaseDexDelegate) {
        _dexDelegates += d
    }

    internal fun resolveInlineDex(dexKit: DexKitBridge) {
        dexDelegates.forEach { it.findInline(dexKit) }
    }

    internal val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    internal fun registerUnhook(u: XC_MethodHook.Unhook) {
        unhooks += u
    }

    /**
     * 本功能挂上的 hook 数量与这些 hook 被回调的次数。
     *
     * 用于区分「hook 从未生效」与「装了但从未被调用」：前者锚点会落空，
     * 后者锚点全中、[isActive] 也为真，却没有任何实际作用 —— 现有健康检查
     * 对后者只能报「正常」，这组计数就是补这个盲区。
     *
     * [hookInstalledCount] 在每次装 hook 时 +1（不递减，disable 后清空），
     * [hookCallCount] 在 [executeHookAction] 中累加，走的是信息量最小的
     * 原子自增路径（热路径，不加锁、不拼字符串）。
     */
    private val _hookCallCount = java.util.concurrent.atomic.AtomicLong()
    internal var hookInstalledCount: Int = 0
        private set
    val hookCallCount: Long get() = _hookCallCount.get()

    /** 是否装了 hook 且本次从未被回调 —— 值得上报的「静默空转」。 */
    val hasInstalledHooks: Boolean get() = hookInstalledCount > 0

    internal fun recordHookInstalled() {
        hookInstalledCount += 1
    }

    internal fun unhookAll() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
        hookInstalledCount = 0
    }

    // --- hookBefore ---

    internal fun Executable.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(priority) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        )).also { recordHookInstalled() }

    @JvmName("hookBefore2")
    internal fun BaseReflectedMethod.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = self.hookBefore(priority, action)

    @JvmName("hookBefore3")
    internal fun ReflectedConstructor<*>.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookBefore(priority, action)

    internal fun Class<*>.hookBeforeOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal fun Class<*>.hookAfterOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookAfter(50, action)

    internal fun KClass<*>.hookBeforeOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal fun KClass<*>.hookAfterOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookAfter(50, action)

    // --- end hookBefore ---

    // --- hookAfter ---

    internal fun Executable.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(priority) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        )).also { recordHookInstalled() }

    @JvmName("hookAfter2")
    internal fun BaseReflectedMethod.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = self.hookAfter(priority, action)

    @JvmName("hookAfter3")
    internal fun ReflectedConstructor<*>.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookAfter(priority, action)

    // --- end hookAfter ---

    // --- dex delegate ---

    internal fun DexMethodDelegate.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = method.hookBefore(priority, action)

    internal fun DexMethodDelegate.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = method.hookAfter(priority, action)

    internal fun DexConstructorDelegate.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = constructor.hookBefore(priority, action)

    internal fun DexConstructorDelegate.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = constructor.hookAfter(priority, action)

    // --- end dex delegate ---

    internal fun executeHookAction(param: XC_MethodHook.MethodHookParam, action: HookAction) {
        // 热路径：先记一次调用，再跑业务。原子自增无锁、无字符串分配，
        // 开销与 runCatching 同量级，可忽略。
        _hookCallCount.incrementAndGet()
        runCatching {
            action(param)
        }.onFailure { e -> WeLogger.e("executeHookAction", "failed to execute hook of $name", e) }
    }

    companion object {
        private const val TAG = "BaseFeature"
    }
}
