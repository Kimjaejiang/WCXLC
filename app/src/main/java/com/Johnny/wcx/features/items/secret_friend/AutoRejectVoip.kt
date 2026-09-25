package com.Johnny.wcx.features.items.secret_friend

import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption

/**
 * 自动拒绝音视频通话（从「隐藏联系人」弹窗上提为独立开关）。
 *
 * ## 为什么上提
 *
 * 本项原先是 [com.Johnny.wcx.features.items.contacts.HideContacts] 点击弹窗里的一个子项。
 * 但它的实现（[com.Johnny.wcx.features.items.contacts.hidecontacts] 的 voip 拒接逻辑）
 * 与「隐藏联系人」的其他子项并无耦合——它只是「隐藏来电」的一个附加动作，
 * 而隐藏来电本身是密友体系的隐藏面。入口藏在另一个功能的弹窗里，
 * 用户既难发现，也无法与密友的其他开关并列比较。
 *
 * ## 实现归属（重要）
 *
 * **本 object 只承载「开关 + pref」，不承载拒接逻辑。** 实际的拒接动作在
 * `HideContactsVoip.kt` 的 `methodVoipMpLaunchIncomingCard.hookAfter` 内，
 * 由 `HideContacts.autoRejectVoipEnabled` 读取判定。
 *
 * 这样拆分是刻意的：voip 的拒接 hook 挂在 [HideContacts] 的启用生命周期里，
 * 与「隐藏来电横幅/铃声/通知」共享同一批 dex 锚点，把它们拆成两个独立 hook 组
 * 会让「只开自动拒接、不开隐藏联系人」时的行为变得难以推理（没有隐藏名单的拒接
 * 是无意义的）。因此保持单一 hook 组，只把**开关**提出来。
 *
 * ## pref 键保持不变
 *
 * 继续使用 `hide_auto_reject`（原 [HideContacts] 的键），这样已配置过的用户
 * 在升级后开关状态不丢。
 */
@Feature(
    name = "自动拒绝音视频通话",
    categories = ["密友功能"],
    description = "隐藏联系人来电时立即向对方发送拒接；关闭时仅隐藏来电，对方会一直响到超时"
)
object AutoRejectVoip : SwitchFeature() {

    /**
     * 实际开关状态。键 `hide_auto_reject` 与原「隐藏联系人」弹窗共用，
     * 保证升级后不丢设置；[com.Johnny.wcx.features.items.contacts.HideContacts]
     * 的 voip 拒接逻辑读同一个键。
     *
     * 命名注意：不能叫 `enabled` —— [SwitchFeature] 的 `isEnabled` 在 JVM 上就是
     * `setEnabled(Z)V`，同名会撞成 accidental override。
     */
    var autoReject by prefOption("hide_auto_reject", false)
}
