package com.Johnny.wcx.features.adapt

import org.luckypray.dexkit.query.FindMethod

/**
 * 版本文件里声明锚点的 builder。
 *
 * 用法（见 [com.Johnny.wcx.features.adapt.v8078.V8_0_78]）：
 *
 * ```kotlin
 * override fun anchors() = anchorsOf {
 *     method("ForceTabletMode:methodIsTablet") {
 *         matcher {
 *             declaredClass = "com.tencent.mm.ui.g9"
 *             name = "a"
 *             returnType = "boolean"
 *         }
 *     }
 *     absent("SomeFeature:methodGone", "8.0.78 起该特征串已删除")
 * }
 * ```
 *
 * `method { }` 的 receiver 是 DexKit 的 `FindMethod`，`matcher { }` 里是 `MethodMatcher`
 * —— 与功能文件里内联 matcher 的写法**完全一致**，复制粘贴即可，不需要学新语法。
 */
class AnchorsBuilder internal constructor() {

    internal val result = LinkedHashMap<String, AnchorSpec>()

    /**
     * 声明一个锚点在**本版本**怎么找。
     *
     * [key] 必须与 dex 委托的 key 完全一致（`"${类简名}:${属性名}"`）。
     * 写错了不会报错，只是永远匹配不上 —— 所以 key 只从导出的清单里抄，
     * 不要手打。
     */
    fun method(key: String, block: FindMethod.() -> Unit) {
        result[key] = AnchorSpec.Matcher(block)
    }

    /**
     * 声明一个锚点在**本版本**刻意作废。
     *
     * 用于「微信把这个特征串删了」或「本版本该功能走另一条实现路径」。
     * 好处是它不再计入 FeatureHealth 的锚点失效清单 ——
     * 否则那会是个永远修不好的假问题，淹没真正的故障。
     */
    fun absent(key: String, reason: String) {
        result[key] = AnchorSpec.Absent(reason)
    }
}

/** [AdaptSpec.anchors] 的入口。 */
fun anchorsOf(block: AnchorsBuilder.() -> Unit): Map<String, AnchorSpec> =
    AnchorsBuilder().apply(block).result
