package com.Johnny.wcx.features.adapt.v8078

import com.Johnny.wcx.features.adapt.AdaptSpec
import com.Johnny.wcx.features.adapt.anchorsOf

/**
 * 微信 8.0.78 (3180) 的锚点适配。
 *
 * ## 这个文件是什么
 *
 * 「怎么在微信 8.0.78 的 dex 里找到某个方法」的**完整描述**。
 * 一个微信版本一个文件，`v8079/`、`v8080/` 依次类推。
 *
 * **删掉一个版本目录 = 放弃对该版本的适配** —— 表现为该微信版本上
 * 显示「不适配」，而不是崩溃或静默失效。
 *
 * ## 为什么要独立成文件
 *
 * 以前版本知识写在功能文件的注释里（「8.0.78: 原 usingEqStrings 恒落空……」），
 * 微信一更新就得改注释，**旧版本的适配随之消失**。
 * 现在每个版本一份文件，互不覆盖。
 *
 * ## 只写漂移的功能
 *
 * 没漂移的功能**不用出现在这里**。运行时会先查本版本的适配，
 * 查不到就退回功能文件里内联的 matcher（那是「当前最新」的写法）。
 * 所以这里只写**和默认不一样**的那些。
 *
 * ## 每个锚点要么给 matcher，要么显式作废
 *
 * 微信删掉某个特征串时，那个锚点在**本版本**就是找不到的。
 * 这种情况必须显式写 `absent()`，否则会被 FeatureHealth 记成
 * 「锚点失效」，变成一个永远修不好的假问题，淹没真正的故障。
 */
object V8_0_78 : AdaptSpec {

    override val wxVersionCode = 3180L

    /**
     * 「强制平板模式」。
     *
     * 这个功能在 8.0.78 有实打实的漂移，正好当样板：
     *
     * 原 matcher 是 `usingEqStrings("Lenovo TB-9707F", "eebbk")` —— 恒落空。
     * 它要求两个串同处一个方法，而 8.0.78 里 `"eebbk"` 已搬到调用方
     * `com.tencent.mm.ui.gk;->K2` 的设备信息表里，只剩 `"Lenovo TB-9707F"`
     * 留在 `com.tencent.mm.ui.g9;->a:()Z`。
     *
     * 这个锚点是微信平板判定链的 isP8Pad 分支。判定总入口是
     * `com.tencent.mm.ui.gk;->C(Lou5/w0;)Z`，逐项检查并缓存到 `gk.f`：
     *
     * ```
     * gk.R()                    折叠屏  -> false
     * gk.S() + zf5/b.c()        华为    "inTabletEnv, isHWTablet, ..."
     * gk.U() + lp/e0.b()        荣耀    "inTabletEnv, isHonorTablet, ..."
     * gk.Y() + zf5/d.h()        小米    "inTabletEnv, isMiTablet, ..."
     * gk.l0() + lp/e0.h()       vivo    "inTabletEnv, isVIVOTablet, ..."
     * gk.c0() + lp/e0.e()/d()   OPPO    "inTabletEnv, isOppoTablet, ..."
     * g9.a()                    Lenovo  "inTabletEnv, isP8Pad, return true"
     * lp/e0.o == "eebbk"        步步高
     * ```
     *
     * dexdump 扫全 17 个 dex 实证；`g9` 类内只有 `a()` 这一个方法。
     * 注意别只扫一两个 dex 就下结论 —— `g9.a()` 另有 `gk.K2` 一处调用是
     * JSAPI 设备上报，真正关键的是上面 `gk.C()` 这条链，它在 `classes15.dex`。
     */
    override fun anchors() = anchorsOf {

        method("ForceTabletMode:methodIsTablet") {
            matcher {
                declaredClass = "com.tencent.mm.ui.g9"
                name = "a"
                returnType = "boolean"
            }
        }

        method("ForceTabletMode:methodIsTablet2") {
            matcher {
                usingEqStrings("MicroMsg.UIUtils", "isRoyoleFoldableDevice!!!")
            }
        }

        method("ForceTabletMode:methodOtherDeviceLoginButtonIsVisible") {
            matcher {
                usingEqStrings("loginAsOtherDeviceBtn")
            }
        }
    }
}
