package com.Johnny.wcx.features.items.payment

import android.app.Activity
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "分裂群组假红包", categories = ["红包与支付"], description = "在分裂群组产生的假群中发送红包(假红包)\n仅对分裂假群(@@chatroom)生效, 不影响真实私聊/群聊发红包")
object AllowPrivateChatReceiveOutgoingRedPackets : SwitchFeature() {

    override fun onEnable() {
        listOf(
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyPrepareUI",
            "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewPrepareUI"
        ).forEach {
            it.toClass().hookBeforeOnCreate {
                val activity = thisObject as Activity
                val chatUser = activity.intent.getStringExtra("Chat_User")
                // 仅分裂群组产生的假群(@@chatroom)注入 key_type=1, 使假红包可发送;
                // 真实私聊/群聊不注入, 避免发红包「请求不成功」
                if (chatUser == null || !chatUser.contains("@chatroom")) return@hookBeforeOnCreate
                activity.intent.putExtra("key_type", 1)
            }
        }
    }
}
