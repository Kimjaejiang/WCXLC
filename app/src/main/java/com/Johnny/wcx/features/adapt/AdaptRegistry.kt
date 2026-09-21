package com.Johnny.wcx.features.adapt

import com.Johnny.wcx.features.adapt.v8078.V8_0_78
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger

/**
 * 按当前微信版本挑出该用的 [AdaptSpec]。
 *
 * ## 版本匹配是精确的，不是区间
 *
 * 8.0.78 的适配只在 8.0.78 上用。微信 8.0.79 上**不会**套用 8.0.78 的适配 ——
 * 因为混淆类名一版一变，跨版本套用等于把锚点指向错误的类。
 *
 * 这也正是你要的行为：**没推 8.0.79 的适配，8.0.79 上就显示不适配**，
 * 而不是拿着旧锚点瞎猜。
 *
 * ## 版本目录怎么加
 *
 * 1. 建 `features/adapt/v8079/V8_0_79.kt`，实现 [AdaptSpec]
 * 2. 在下面的 [REGISTRY] 里加一行
 *
 * 删掉一行 + 删掉目录 = 放弃该版本的适配。
 */
object AdaptRegistry {

    private const val TAG = "AdaptRegistry"

    /**
     * 所有已适配的微信版本。
     *
     * 显式登记而不是反射扫描 —— 清单一眼可见，而且**删掉一行就真的没了**，
     * 不会因为某个文件还在就意外生效。
     */
    private val REGISTRY: Map<Long, () -> AdaptSpec> = mapOf(
        3180L to { V8_0_78 },
    )

    /**
     * 当前微信版本对应的适配；没有则 null。
     *
     * 调用方拿到 null 应当走功能内联的默认 matcher —— 那些是「当前最新」的写法，
     * 对**最新**的微信版本是对的。对旧的微信版本，内联 matcher 大概率已经漂移，
     * 此时锚点会自然落空，并体现在健康检查里。
     */
    @Volatile
    private var resolved: AdaptSpec? = null

    @Volatile
    private var resolvedOnce = false

    fun current(): AdaptSpec? {
        if (resolvedOnce) return resolved
        synchronized(this) {
            if (resolvedOnce) return resolved

            val wxVer = HostInfo.versionCode
            val factory: (() -> AdaptSpec)? = REGISTRY[wxVer]
            val spec: AdaptSpec? = factory?.invoke()

            if (spec == null) {
                val known = REGISTRY.keys.sorted()
                WeLogger.i(
                    TAG, "微信 $wxVer 无版本适配文件，" +
                            "走功能内联 matcher（已适配版本：$known）"
                )
            } else {
                WeLogger.i(TAG, "微信 $wxVer 命中版本适配 ${spec::class.simpleName}")
            }

            resolved = spec
            resolvedOnce = true
            return spec
        }
    }

    /**
     * 取某锚点在当前版本的适配；没有则 null（调用方走内联默认）。
     */
    fun anchorOf(key: String): AnchorSpec? = current()?.anchors()?.get(key)
}
