package com.Johnny.wcx.features.items.system

import android.content.Context
import android.widget.Button
import androidx.compose.material3.Text
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog

@Feature(name = "强制平板模式", categories = ["系统与隐私"], description = "让微信将当前设备识别为平板")
object ForceTabletMode : SwitchFeature(), IResolveDex {

    private val methodIsTablet by dexMethod {
        matcher {
            // 8.0.78: 原 usingEqStrings("Lenovo TB-9707F", "eebbk") 恒落空
            // —— 它要求两个串同处一个方法，而 "eebbk" 已搬到调用方
            // com.tencent.mm.ui.gk;->K2 的设备信息表里，只剩 "Lenovo TB-9707F"
            // 留在 com.tencent.mm.ui.g9;->a:()Z。
            //
            // 这个锚点是微信平板判定链的 isP8Pad 分支。判定总入口是
            // com.tencent.mm.ui.gk;->C(Lou5/w0;)Z，逐项检查并缓存到 gk.f：
            //   gk.R()         折叠屏   -> false
            //   gk.S() + zf5/b.c()     华为   "inTabletEnv, isHWTablet, ..."
            //   gk.U() + lp/e0.b()     荣耀   "inTabletEnv, isHonorTablet, ..."
            //   gk.Y() + zf5/d.h()     小米   "inTabletEnv, isMiTablet, ..."
            //   gk.l0() + lp/e0.h()    vivo   "inTabletEnv, isVIVOTablet, ..."
            //   gk.c0() + lp/e0.e()/d() OPPO  "inTabletEnv, isOppoTablet, ..."
            //   g9.a()                 Lenovo "inTabletEnv, isP8Pad, return true"
            //   lp/e0.o == "eebbk"     步步高
            // （dexdump 扫全部 17 个 dex 实证；g9 类内只有 a() 这一个方法。）
            // 注意别只扫一两个 dex 就下结论：g9.a() 另有 gk.K2 一处调用是 JSAPI
            // 设备上报，真正关键的是上面 gk.C() 这条链，它在 classes15.dex。
            declaredClass = "com.tencent.mm.ui.g9"
            name = "a"
            returnType = "boolean"
        }
    }
    private val methodIsTablet2 by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.UIUtils", "isRoyoleFoldableDevice!!!")
        }
    }
    private val methodOtherDeviceLoginButtonIsVisible by dexMethod {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
        }
    }

    override fun onEnable() {
        // 守卫：锚点解析失败时 .method 会直接 error()，而 BaseFeature.enable 的
        // runCatching 会吞掉异常并把 isActive 置回 false —— 表现是「开关打开了但
        // 强制平板模式没生效」，用户侧没有任何提示，所以这里自己判、自己说明。
        // (8.0.78 下 matcher 已改为直指 g9.a()，正常情况下应当命中；
        //  这条守卫是防微信后续版本再次漂移。)
        if (methodIsTablet.isPlaceholder) {
            WeLogger.w(
                "ForceTabletMode",
                "平板设备判定锚点 (com.tencent.mm.ui.g9->a) 未匹配，该子分支跳过；其余分支正常"
            )
        } else methodIsTablet.hookBefore {
            try {
                // 仅当原方法返回 boolean 时才设置 result = true，避免对非 boolean 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                val returnType = (method as java.lang.reflect.Method).returnType
                if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                    result = true
                }
            }
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }

        methodIsTablet2.hookBefore {
            try {
                if (method is java.lang.reflect.Method) {
                val returnType = (method as java.lang.reflect.Method).returnType
                if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                    result = true
                }
            }
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }

        methodOtherDeviceLoginButtonIsVisible.hookBefore {
            try {
                val view = args[0] as? Button? ?: return@hookBefore
                if (view.isGone) view.isVisible = true
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }

        "com.tencent.mm.plugin.account.ui.LoginHistoryUI".toClass().reflekt().firstMethod("initView").hookAfter {
            val btn = thisObject.reflekt().firstField {
                type = Button::class
            }.get()!! as Button
            btn.isVisible = true
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
