package com.Johnny.wcx.features.items.payment

import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "允许领取私聊红包", categories = ["红包与支付"], description = "允许打开私聊中自己发出的红包")
object AllowPrivateChatReceiveOutgoingRedPackets : SwitchFeature() {

    override fun onEnable() {
        // 原实现 hook 发红包界面(LuckyMoneyPrepareUI/LuckyMoneyNewPrepareUI)强制 key_type=1，
        // 会导致私聊发红包提示「请求不成功」（个人会话发不出去），已停用。
        // 恢复此功能需在红包详情/领取层(如 LuckyMoneyDetailUI)放行自己发出的红包，而非修改发送类型。
    }
}
