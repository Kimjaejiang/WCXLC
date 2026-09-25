package com.Johnny.wcx.features.items.voip

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Modifier

@Feature(name = "移除通话时聊天限制", categories = ["聊天", "音视频通话"], description = "绕过正在通话时聊天限制")
object RemoveLimitsDuringCalls : SwitchFeature(), IResolveDex {

    private const val TAG = "RemoveLimitsDuringCalls"

    override fun onEnable() {
        val anchors = listOf(
            methodIsDuringCall,
            methodIsMultiTalking,
            methodIsCameraUsing,
            methodIsCameraUsing2,
            methodIsVoiceUsing,
            methodIsVoiceUsing2,
            methodCheckAppBrandVoiceUsing,
            methodCheckAppBrandVoiceUsing2,
            methodCheckDeviceUsing,
            methodCheckAudioDeviceUsing,
            methodCheckSpeakerUsing,
            methodCheckAppBrandCameraUsing,
            methodCheckAppBrandCameraUsing2,
            methodCheckAudioOutSupported,
        )

        // 8.0.78 实机核对（解包 base.apk 全部 17 个 dex 逐个搜字符串）：
        // 这几个 check* 方法在 8.0.78 里已被微信**删除**（搜到 0 次），
        // 不是「我们没写对 matcher」。它们保留只为兼容 8.0.77 及更早。
        //
        // 标成 intentionallyAbsent 只影响 FeatureHealth 的上报口径：
        // 否则健康检查会长期挂着 5 条永远修不好的假问题，
        // 把真正的锚点失效淹掉。（isPlaceholder 仍为 true，上面该守的判断不能省。）
        //
        // 注意 checkAppBrandCameraUsing 不在此列 —— 那个方法还存在，
        // 之前解析失败是上游 matcher 的 paramCount 写错了，已修正。
        val absentOn8078 = listOf(
            methodCheckDeviceUsing,
            methodCheckAudioDeviceUsing,
            methodCheckSpeakerUsing,
            methodCheckAppBrandCameraUsing2,
            methodCheckAudioOutSupported,
        )

        // allowFailure 只保证「解析阶段」不中断（降级为 placeholder），它**不保证**后续访问
        // .method 安全 —— DexMethodDelegate.method 对 placeholder 会直接
        // error("Method resolution has failed")，而 hookBefore 内部要读 .method，
        // 于是异常冒到 onEnable，BaseFeature 捕获后把**整个功能**标记为启用失败。
        //
        // 实测踩过：8.0.78 里 DeviceOccupy 的 check* 系列多数已被微信删除，
        // 这样一个坏锚点就把整个功能（包括前面已挂好的 8 个）全废掉了。
        // 所以这里必须自己跳过 placeholder，不能依赖 allowFailure。
        var hooked = 0
        var skipped = 0
        anchors.forEach { anchor ->
            if (anchor.isPlaceholder) {
                skipped++
                // 确实是在 8.0.78 上被微信删掉的那些，才上报为「刻意缺失」。
                // 若一个本应存在（不在 absentOn8078 里）的锚点也落到这里，
                // 说明是 matcher 真的写错了，保持普通 placeholder 让健康检查报出来。
                if (anchor in absentOn8078) {
                    anchor.setPlaceholderDescriptor(
                        "removed by WeChat 8.0.78 (DeviceOccupy check* API)"
                    )
                }
                WeLogger.w(TAG, "跳过未命中锚点 ${anchor.key}（降级为 placeholder）")
                return@forEach
            }
            runCatching {
                anchor.hookBefore {
                    try {
                        // 所有方法均返回 boolean，仅当返回类型匹配时才设置 result = false
                        if (method is java.lang.reflect.Method) {
                            val returnType = (method as java.lang.reflect.Method).returnType
                            if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                                result = false
                            }
                        }
                    } catch (e: Throwable) {
                        // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                    }
                }
            }
                .onSuccess { hooked++ }
                .onFailure { WeLogger.w(TAG, "挂载 ${anchor.key} 失败: ${it.message}") }
        }
        WeLogger.i(TAG, "通话限制绕过已挂载 $hooked 个锚点（跳过 $skipped 个未命中）")
    }

    // 8.0.77 通话栈加固: 全部 dexMethod 使用 allowFailure, 类被移除/混淆时降级为 placeholder,
    // 避免 Dex 扫描阶段抛异常拖垮整个模块启动。
    private val methodIsDuringCall by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                modifiers(Modifier.ABSTRACT)
            }

            modifiers(Modifier.STATIC)
            paramCount = 0
            returnType = "boolean"

            addInvoke {
                declaredClass = "com.tencent.mm.autogen.events.MultiTalkActionEvent"
            }
        }
    }
    private val methodIsMultiTalking by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isMultiTalking")
            paramCount = 1
        }
    }

    private val methodIsCameraUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "")
        }
    }
    private val methodIsCameraUsing2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isCameraUsing", "isLiving %b isAnchor %b isAudioMicing %s isVideoMicing %s")
        }
    }
    private val methodIsVoiceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 1
        }
    }
    private val methodIsVoiceUsing2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "isVoiceUsing")
            paramCount = 2
        }
    }
    private val methodCheckAppBrandVoiceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 1
        }
    }
    private val methodCheckAppBrandVoiceUsing2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            paramCount = 2
        }
    }

    // Additional device occupancy checks that may block voice message playback during calls.
    // These cover methods beyond the core set above that some WeChat versions use.
    private val methodCheckDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkDeviceUsing")
            returnType = "boolean"
        }
    }
    private val methodCheckAudioDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAudioDeviceUsing")
            returnType = "boolean"
        }
    }
    private val methodCheckSpeakerUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkSpeakerUsing")
            returnType = "boolean"
        }
    }

    // 8.0.78 实机核对（解包 base.apk 全部 17 个 dex 逐个搜字符串）：
    // DeviceOccupy 类下 check* 系列已大多被微信删除，只剩下面这个还存在。
    // 删掉的（搜 0 次，matcher 保留只会得到 placeholder）：
    //   checkDeviceUsing / checkAudioDeviceUsing / checkSpeakerUsing /
    //   checkAppBrandCameraUsingAndShowToast
    // 这些锚点仍然留着，是为了兼容 8.0.77 及更早的宿主；
    // 在 8.0.78 上它们会降级为 placeholder，由 onEnable 统一跳过。
    private val methodCheckAppBrandCameraUsing by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings(
                "MicroMsg.DeviceOccupy",
                "checkAppBrandCameraUsing isVoiceUsing:%b, isCameraUsing:%b"
            )
            // 0 个参数：反编译 pq.b（8.0.78 的 DeviceOccupy）确认该日志所在方法是
            // public static boolean a()，两个 %b 是传给 Log.i 的实参，不是方法参数。
            // 上游曾按「两个 %b ⇒ 两个参数」写成 paramCount = 2，导致永远匹配不到。
            paramCount = 0
            returnType = "boolean"
        }
    }

    private val methodCheckAppBrandCameraUsing2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings(
                "MicroMsg.DeviceOccupy",
                "checkAppBrandCameraUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b"
            )
            paramCount = 2
            returnType = "boolean"
        }
    }

    private val methodCheckAudioOutSupported by dexMethod(allowFailure = true) {
        matcher {
            // 上游这里只写了 usingEqStrings("checkAudioOutSupported")，没限定类，
            // 于是它会在整个 dex 范围里找，且缺了 DeviceOccupy 那条日志串的约束，
            // 实机表现为解析失败。补上和其它锚点一致的类限定。
            declaredClass(methodIsDuringCall.method.declaringClass)
            usingEqStrings("MicroMsg.DeviceOccupy", "checkAudioOutSupported")
            returnType = "boolean"
        }
    }
}
