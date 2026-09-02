@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.utils

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import java.lang.reflect.Executable

typealias HookAction = XC_MethodHook.MethodHookParam.() -> Unit

// ---- WeKit-compat (IHookBridge) surface used by merged official features ----
typealias HookParam = com.Johnny.wcx.loader.abc.IHookBridge.IMemberHookParam

typealias HookHandle = com.Johnny.wcx.loader.abc.IHookBridge.MemberUnhookHandle

abstract class HookCallback(val priority: Int = 50) : com.Johnny.wcx.loader.abc.IHookBridge.IMemberHookCallback {
    protected open fun beforeHookedMethod(param: HookParam) {}
    protected open fun afterHookedMethod(param: HookParam) {}
    override fun beforeHookedMember(param: HookParam) = beforeHookedMethod(param)
    override fun afterHookedMember(param: HookParam) = afterHookedMethod(param)
}

val currentHookBridge: com.Johnny.wcx.loader.abc.IHookBridge
    get() = checkNotNull(com.Johnny.wcx.loader.startup.StartupInfo.hookBridge) {
        "hook bridge is unavailable in the current loader"
    }

// most extension methods are inside BaseFeature for enabled state checking

inline fun BaseReflectedMethod.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
) = self.hookBeforeDirectly(priority, action)

inline fun Executable.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = XposedBridge.hookMethod(
    this, object : XC_MethodHook(priority) {
        override fun beforeHookedMethod(param: MethodHookParam) {
            action(param)
        }
    }
)

inline fun BaseReflectedMethod.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = self.hookAfterDirectly(priority, action)

inline fun ReflectedConstructor<*>.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = self.hookAfterDirectly(priority, action)

inline fun Executable.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = XposedBridge.hookMethod(
    this, object : XC_MethodHook(priority) {
        override fun afterHookedMethod(param: MethodHookParam) {
            action(param)
        }
    }
)

inline fun BaseReflectedMethod.hookDirectly(
    hook: XC_MethodHook
): XC_MethodHook.Unhook = self.hookDirectly(hook)

inline fun Executable.hookDirectly(
    hook: XC_MethodHook
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, hook)

@Suppress("NOTHING_TO_INLINE")
fun XC_MethodHook.MethodHookParam.invokeOriginal(thisObject: Any? = null, args: Array<Any?>? = null): Any? =
    XposedBridge.invokeOriginalMethod(method, thisObject ?: this.thisObject, args ?: this.args)

// ---- Local IHookBridge extensions (restored from local branch) ----

class OriginalMethodInvoker internal constructor(
    private val hookBridge: com.Johnny.wcx.loader.abc.IHookBridge,
    private val method: java.lang.reflect.Method,
    private val thisObject: Any?,
    private val originalArgs: Array<Any?>
) {
    operator fun invoke(args: Array<Any?>? = null): Any? =
        hookBridge.invokeOriginalMethod(method, thisObject, args ?: originalArgs)
}

/** Xposed-style alias so `method` resolves inside [HookParam] receiver lambdas. */
val HookParam.method: java.lang.reflect.Member get() = member

fun HookParam.captureOriginalMethod(): OriginalMethodInvoker {
    val method = member as? java.lang.reflect.Method
        ?: throw IllegalStateException("invokeOriginalMethod is only supported for methods: $member")
    return OriginalMethodInvoker(currentHookBridge, method, thisObject, args.copyOf())
}

fun HookParam.invokeOriginalMethod(thisObject: Any? = null, args: Array<Any?>? = null): Any? {
    val method = member as? java.lang.reflect.Method
        ?: throw IllegalStateException("invokeOriginalMethod is only supported for methods: $member")
    return currentHookBridge.invokeOriginalMethod(
        method,
        thisObject ?: this.thisObject,
        args ?: this.args
    )
}

/** Xposed-style [captureOriginalMethod] so XC_MethodHook-based hooks can invoke the original. */
fun XC_MethodHook.MethodHookParam.captureOriginalMethod(): OriginalMethodInvoker {
    val m = method as? java.lang.reflect.Method
        ?: throw IllegalStateException("invokeOriginalMethod is only supported for methods: $method")
    return OriginalMethodInvoker(currentHookBridge, m, thisObject, args)
}
