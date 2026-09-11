package com.Johnny.wcx.features.items.secret_friend

import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

/**
 * 密友消息通知隐藏（浮云「隐藏密友消息通知」语义）：密友被隐藏期间自动设置「消息免打扰」。
 *
 * 稳妥实现：不做字段重写（rcontact 的 mute 位由服务器 oplog 同步，查询期重写会在
 * 会话设置页露馅），而是走微信原生免打扰通道 [WeConversationApi.setDnd]——开启本开关
 * 或名单新增成员时，对尚未来打扰的密友逐个 setDnd(true)。与「隐藏联系人」同一条
 * 已验证通道（OpenImOpLogLogic oplog，服务器同步）。
 *
 * 关闭开关**不回滚**免打扰（与隐藏联系人一致：无法区分用户自设的免打扰，回滚会误恢复）。
 */
@Feature(
    name = "密友消息通知隐藏",
    categories = ["密友功能"],
    description = "名单内成员自动设置消息免打扰，不再弹通知/横幅/角标提醒；关闭开关不自动恢复已设置的免打扰"
)
object MuteSecretFriend : SwitchFeature() {

    private const val TAG = "MuteSecretFriend"

    private val listChangedListener = { ensureAllSecretsMuted() }

    override fun onEnable() {
        SecretFriendState.addOnListChanged(listChangedListener)
        ensureAllSecretsMuted()
    }

    override fun onDisable() {
        SecretFriendState.removeOnListChanged(listChangedListener)
    }

    /** 对名单内尚未免打扰的密友逐个开启（幂等，名单变动时只补齐增量）。 */
    private fun ensureAllSecretsMuted() {
        for (wxId in SecretFriendState.getWxIds()) {
            if (WeConversationApi.isDnd(wxId)) continue
            WeLogger.d(TAG, "auto muting secret friend $wxId")
            runCatching { WeConversationApi.setDnd(wxId, true) }
                .onFailure { WeLogger.w(TAG, "failed to mute secret friend $wxId", it) }
        }
    }
}
