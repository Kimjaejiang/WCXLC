package com.Johnny.wcx.features.items.secret_friend

import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast

/**
 * 密友名单管理 = 密友功能**主控开关** + 名单编辑入口。
 *
 * - 开关关闭（主控关）：所有依赖名单的密友隐藏/拦截功能立即失效（名单视为空），
 *   被删除的会话行恢复显示；**名单本身保留**，重新打开无需重新勾选。
 * - 开关打开（主控开）：按存储名单 + 各功能自身开关恢复生效。
 *
 * 名单数据读写走 [SecretFriendState]（getStoredWxIds/setWxIds 不受主控影响），
 * 各隐藏功能消费 [SecretFriendState.getWxIds]（主控关闭时返回空 → 整体放行）。
 */
@Feature(
    name = "密友名单管理",
    categories = ["密友功能"],
    description = "密友功能总开关 + 名单编辑：关闭时密友功能整体失效并恢复显示（名单保留，重开即恢复）；开启时按名单生效"
)
object SecretFriendManager : ClickableFeature() {

    private const val TAG = "SecretFriendManager"

    /** 默认开启：从未操作过该开关的用户，密友功能照常按各自开关工作。 */
    override val defaultEnabled: Boolean = true

    override fun onClick(context: ComponentActivity) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        showComposeDialog(context) {
            ContactsSelector(
                title = "选择密友",
                contacts = regularContacts,
                // 显示真实存储名单（即使主控当前关闭也能看到已有勾选）
                initialSelectedWxIds = SecretFriendState.getStoredWxIds(),
                onDismiss = onDismiss,
            ) {
                SecretFriendState.setWxIds(it)
                // 名单变化后对账：恢复被删行（移出名单的密友），仍在名单中的重新隐藏
                HideConversations.reconcileOnListChange()
                showToast("密友名单已更新（${it.size} 人）")
                onDismiss()
            }
        }
    }

    override fun onEnable() {
        // 主控开启：连带启用 [HideContacts]。
        //
        // HideContacts 已不再作为列表项展示（见 FeaturesPager.HIDDEN_ITEM_NAMES），它承担的
        // 8 个隐藏面（通话/摇一摇/角标计数/拍一拍/收藏/视频号点赞/群成员列表/微信运动）
        // 随之并入密友总控。
        //
        // 注意这两行的**适用时机只是运行期切换**：启动路径由 HideContacts.shouldEnableOnStartup
        // 自行读主控 pref 决定，不依赖这里。原因是 isEnabled 的 setter 带
        // `if (_isEnabled == value) return` 短路——startup() 已把 _isEnabled 置为 pref 值，
        // 若把它当作唯一启用入口，主控赋 true 时会因值相同而跳过 enable()，hook 永不安装
        // （已实际导致「长按/点按/命令」三入口同时失效）。
        if (!HideContacts.isEnabled) HideContacts.isEnabled = true

        // 主页会话隐藏开着时，把关闭期间恢复显示的密友会话行重新隐藏
        if (HideConversations.isEnabled) {
            HideConversations.removeSecretRows()
        }
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "master enabled")
    }

    override fun onDisable() {
        // 主控关闭：连带停掉 HideContacts 的 8 个隐藏面。
        //
        // 先关 HideContacts 再 reconciliation：HideContacts.onDisable 会清临时显示标记并
        // reload，之后 reconcileOnListChange 才在「名单已因主控关闭而视为空」的前提下恢复会话行。
        if (HideContacts.isEnabled) HideContacts.isEnabled = false

        // 主控关闭：不动名单存储，仅恢复被删除的会话行并放行所有过滤
        // （此时 getWxIds 已因主控关闭返回空，reconcile 内 removeSecretRows 为空操作）
        HideConversations.reconcileOnListChange()
        WeLogger.i(TAG, "master disabled: hiding disabled, rows restored, list kept")
        showToast("密友功能已关闭，密友已恢复显示；名单已保留")
    }
}
