package com.Johnny.wcx.features.items.secret_friend

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import com.Johnny.wcx.features.api.ui.WeConversationContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.VisibilityOffIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import dev.ujhhgtg.reflekt.reflekt

/**
 * 添加密友组 28 / 29：会话列表长按添加、通讯录长按添加。
 * 菜单文字共用 [SecretFriendState.addMenuText]。
 */
private const val TAG_ADD = "SecretFriend.Add"

// ─────────────────────── 28. 会话列表长按添加 ───────────────────────

/**
 * 会话列表长按添加（浮云「会话列表长按添加」语义）：主页会话长按菜单追加「加入密友」。
 * 实现走工程已验证的 [WeConversationContextMenuApi]（ConversationLongClickListener 锚点），
 * talker 直接来自菜单上下文，注入与点击都由该 API 完整托管。
 */
@Feature(
    name = "会话列表长按添加",
    categories = ["密友功能"],
    description = "主页会话列表长按菜单追加「加入密友/取消密友」，菜单文字可在「菜单显示文字」自定义"
)
object LongPressAddFromChat : SwitchFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    private const val TAG = "LongPressAddFromChat"

    private val MENU_ID = "secret_friend_conv_add".hashCode()

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            MENU_ID,
            SecretFriendState.addMenuText,
            VisibilityOffIcon,
            { context, _ ->
                val talker = context.talker
                talker.isNotEmpty() && !talker.endsWith("@chatroom")
            },
        ) { context ->
            toggleSecret(context.activity, context.talker)
        }
    )
}

/**
 * 密友名单增删（两个添加开关共用）：已在名单内则移除，否则加入。
 * 显式用户操作，直接 Toast（需要操作反馈，不受「操作提示」总开关影响）。
 */
internal fun toggleSecret(activity: Activity, wxId: String) {
    if (wxId.isEmpty()) return
    // 增删名单走「存储名单」：主控开关关闭时也应能维护名单（下次开启直接生效）
    if (SecretFriendState.isStoredSecret(wxId)) {
        SecretFriendState.setWxIds(SecretFriendState.getStoredWxIds() - wxId)
        showToast(activity, "${SecretFriendState.toastRemoved}：$wxId")
        WeLogger.i(TAG_ADD, "removed $wxId from secret list via long-press menu")
    } else {
        SecretFriendState.setWxIds(SecretFriendState.getStoredWxIds() + wxId)
        showToast(activity, "${SecretFriendState.toastAdded}：$wxId")
        WeLogger.i(TAG_ADD, "added $wxId to secret list via long-press menu")
    }
}

// ─────────────── 29. 通讯录长按添加 ───────────────

/**
 * 通讯录长按添加（浮云「通讯录长按添加」语义）。
 *
 * 浮云实现 hook 混淆 Fragment 的 onCreateContextMenu/onMMMenuItemSelected（每版漂移）。
 * 本工程改走**框架层**稳定点：包装 `AdapterView.setOnItemLongClickListener`——通讯录
 * 初始化列表时必然经过它。长按联系人行时弹自绘菜单（加入密友 / 原生长按菜单 / 取消）：
 * - 行 wxid 从 AdapterView.getItemAtPosition(position) 的行数据 field_username 提取；
 * - 会话行（持有 field_conversationTime）不劫持，仍走「会话列表长按添加」的原生菜单；
 * - 「原生长按菜单」选项回调微信原监听器，原生功能（备注/星标/删除）不受损。
 */
@Feature(
    name = "通讯录长按添加",
    categories = ["密友功能"],
    description = "通讯录列表长按弹出「加入密友」菜单（含原生长按菜单入口，不损失微信原有功能）；菜单文字同上可自定义"
)
object LongPressAddFromContacts : SwitchFeature() {

    private const val TAG = "LongPressAddFromContacts"

    override fun onEnable() {
        AdapterView::class.reflekt()
            .firstMethod {
                name = "setOnItemLongClickListener"
                parameterCount(1)
            }
            .hookAfter {
                val original = args.getOrNull(0) ?: return@hookAfter
                args[0] = LongClickWrapper(original)
            }
    }

    /** 行数据是否为会话行（rconversation 模型带 conversationTime 字段）。 */
    private fun isConversationRow(row: Any): Boolean = row.reflekt()
        .firstFieldOrNull { name = "field_conversationTime" } != null

    /** 行数据的 wxid（field_username 含父类查找）。 */
    private fun extractRowWxId(row: Any): String? {
        val username = row.reflekt()
            .firstFieldOrNull {
                name = "field_username"
                superclass()
            }?.get() as? String
        return username?.takeIf { it.isNotEmpty() && !it.endsWith("@chatroom") && !it.endsWith("@app") }
    }

    private class LongClickWrapper(private val original: Any) : AdapterView.OnItemLongClickListener {
        override fun onItemLongClick(parent: AdapterView<*>, view: View, position: Int, id: Long): Boolean {
            val row = parent.getItemAtPosition(position) ?: run {
                return invokeOriginal(parent, view, position, id)
            }
            if (isConversationRow(row)) {
                // 会话列表: 不劫持, 原生菜单里已有「会话列表长按添加」的菜单项
                return invokeOriginal(parent, view, position, id)
            }
            val wxId = extractRowWxId(row) ?: run {
                return invokeOriginal(parent, view, position, id)
            }

            val activity = view.context as? Activity ?: run {
                return invokeOriginal(parent, view, position, id)
            }
            showAddDialog(activity, wxId, original, parent, view, position, id)
            return true
        }

        private fun invokeOriginal(parent: AdapterView<*>, view: View, position: Int, id: Long): Boolean = runCatching {
            (original as AdapterView.OnItemLongClickListener)
                .onItemLongClick(parent, view, position, id)
        }.getOrDefault(false)
    }

    private fun showAddDialog(
        activity: Activity,
        wxId: String,
        original: Any,
        parent: AdapterView<*>,
        view: View,
        position: Int,
        id: Long,
    ) {
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text(wxId) },
                text = {
                    Column {
                        Text(
                            if (SecretFriendState.isStoredSecret(wxId)) "该联系人已在密友名单内。"
                            else "把该联系人加入密友名单？"
                        )
                        // 原生长按菜单入口：恢复微信自带的联系人长按功能
                        TextButton(onClick = {
                            onDismiss()
                            runCatching {
                                (original as AdapterView.OnItemLongClickListener)
                                    .onItemLongClick(parent, view, position, id)
                            }.onFailure { WeLogger.w(TAG, "invoke original long-click failed", it) }
                        }) { Text("打开原生长按菜单") }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    TextButton(onClick = {
                        toggleSecret(activity, wxId)
                        onDismiss()
                    }) { Text(if (SecretFriendState.isStoredSecret(wxId)) "取消密友" else SecretFriendState.addMenuText) }
                },
            )
        }
    }
}
