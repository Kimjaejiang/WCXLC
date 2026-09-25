package com.Johnny.wcx.features.items.secret_friend

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.features.items.contacts.hidecontacts.showSchedulesDialog

/**
 * 定时显示/隐藏（从「隐藏联系人」弹窗上提为独立开关）。
 *
 * ## 为什么上提
 *
 * 本项原先是 `HideContacts` 点击弹窗里的一个子项，但它的作用对象是**密友的临时显示
 * 状态**（[SecretFriendState.KEY_TEMP_UNTIL]）——「到点自动临时显示或恢复隐藏」，
 * 与「隐藏联系人」这个名字所暗示的「维护隐藏名单」并无关系。把它留在那个弹窗里，
 * 用户不会想到「定时」属于密友体系。
 *
 * ## 实现归属
 *
 * 配置对话框是 `hidecontacts/HideContactsScheduleUi.kt` 里的
 * `HideContacts.showSchedulesDialog`（内部只依赖 context 与该包的
 * `HideContactsSchedule` 调度器，**不读取隐藏名单**），故本开关只承载
 * 「入口 + 启用状态」，不搬动已稳定运行的调度实现。
 *
 * 注意调度器的安装仍由 `HideContacts.installSchedules()` 在其启用生命周期内完成
 * ——两个功能共用同一份调度实现，只是入口位置不同，不重复安装 alarm。
 */
@Feature(
    name = "定时显示/隐藏",
    categories = ["密友功能"],
    description = "到点自动临时显示或恢复隐藏，可配置每周重复或单次；不会改动隐藏名单"
)
object HideSchedules : SwitchFeature() {

    /**
     * 打开定时配置对话框（默认开关关闭时也可配置，便于先排计划后启用）。
     *
     * 参数取 [Context] 而不是 [ComponentActivity]：底层的 `showSchedulesDialog` 本来就只要
     * Context，收窄类型只会逼调用方做 `as? ComponentActivity` 转换 —— 而 Compose 里的
     * `LocalContext.current` 未必就是 Activity 本身（可能是包装过的 Context），转换失败时
     * `?.let` 会静默跳过，用户点上去毫无反应。
     */
    fun openConfig(context: Context) {
        // showSchedulesDialog 是 HideContacts 的**成员**扩展函数（接收者是 HideContacts，
        // 参数才是 Context），故必须写成 HideContacts.showSchedulesDialog(context)。
        HideContacts.showSchedulesDialog(context)
    }

    @Composable
    override fun Ui() {
        val context = LocalContext.current
        ListItem(
            modifier = Modifier.clickable { openConfig(context) },
            supportingContent = { Text("点击配置定时计划：到点自动临时显示或恢复隐藏") },
            headlineContent = { Text("配置定时计划") },
        )
    }
}
