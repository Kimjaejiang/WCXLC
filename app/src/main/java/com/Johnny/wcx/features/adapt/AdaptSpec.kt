package com.Johnny.wcx.features.adapt

import org.luckypray.dexkit.query.FindMethod

/**
 * 一个微信版本的锚点适配。
 *
 * ## 定位
 *
 * 这里只描述**怎么找**（matcher 约束）。**找到的结果**（`Lcom/…;->a()Z` 这种）
 * 由真机跑一遍 DexKit 才知道，导出后成为云端补丁。
 *
 * 完整链路：
 *
 * ```
 * ① 写 v8079/V8_0_79.kt        （怎么找）      ← 你写这个
 * ② 装到装了微信 8.0.79 的真机跑一次
 * ③ 导出解析结果               （找到的结果）   ← exportPatchManifest
 * ④ 推到云端
 * ⑤ 用户下载补丁
 * ```
 *
 * 第 ② 步绕不过去：服务端算不出微信的混淆类名，只有真机能。
 *
 * ## 只写漂移的锚点
 *
 * 没漂移的**不要写**。运行时先查本版本，查不到就退回功能内联的默认 matcher。
 * 所以这里应当只包含「和当前代码里的默认写法不一样」的锚点 —— 少写是常态。
 */
interface AdaptSpec {

    /**
     * 适用哪个微信版本。[com.Johnny.wcx.utils.HostInfo.versionCode] 与之精确比对。
     *
     * 用 `Long` 是为了与 `HostInfo.versionCode`（`packageInfo.longVersionCode`）
     * 直接比对 —— 中间过一道 `Int` 转换只会多一个可能出错的地方。
     */
    val wxVersionCode: Long

    /**
     * 本版本的锚点。
     *
     * 懒求值 —— 大多数启动路径不会走到这里，别在类加载时就构造一堆 matcher。
     */
    fun anchors(): Map<String, AnchorSpec>
}

/**
 * 单个锚点的适配说明。
 *
 * 两种形态，二选一：
 *
 * - [Matcher]：给出 matcher 约束，运行时用 DexKit 去找
 * - [Absent]：本版本**找不到**这个锚点，显式声明
 */
sealed interface AnchorSpec {

    /**
     * 有 matcher 约束，运行时去找。
     *
     * [apply] 的类型与 [com.Johnny.wcx.dexkit.dsl.dexMethod] 的 `block` 完全一致
     * （`FindMethod.() -> Unit`）—— 两处共用同一套约束写法，不需要额外的转换层。
     * 这样版本文件里能写的约束，和功能文件里默认 matcher 能写的，**保证一致**。
     */
    class Matcher(val apply: FindMethod.() -> Unit) : AnchorSpec

    /**
     * 本版本刻意作废该锚点。
     *
     * 微信删掉特征串、或该功能在本版本换了实现路径时用。
     *
     * **必须显式声明**，不能靠「找不到」自然发生 —— 否则会被
     * FeatureHealth 记成锚点失效，成为永远修不好的假问题。
     * 这与 [com.Johnny.wcx.dexkit.dsl.BaseDexDelegate.intentionallyAbsent]
     * 是同一套语义。
     */
    data class Absent(val reason: String) : AnchorSpec
}
