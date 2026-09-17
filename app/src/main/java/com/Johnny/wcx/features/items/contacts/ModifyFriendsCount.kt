package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewParent
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.BaseSupportingWidget
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.content.m3.SwitchWidget
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger

@Feature(
    name = "修改好友数量",
    categories = ["联系人与群组"],
    description = "修改联系人页显示的好友数量，不会改变实际联系人数据"
)
object ModifyFriendsCount : ClickableFeature() {

    private const val TAG = "ModifyFriendsCount"
    private const val HIDE = -1
    private val FRIEND_COUNT_REGEX = Regex("\\d+(?=个朋友)")

    private var count by prefOption("modify_friends_count", 10)

    override fun onEnable() {
        // 必须精确挑到 setText(CharSequence)。
        //
        // 原先写的是 firstMethod { name = "setText"; parameterCount = 1 }，只数参数个数。
        // 但 TextView 同时有 setText(CharSequence) 与 setText(int)（资源 id 版本），
        // 反射顺序不保证，一旦取到 setText(int)，下面 args[0] as? CharSequence 恒为 null，
        // 回调每次都在第一行 return —— 设置里改完保存了、值也写进 prefs 了，界面上却纹丝不动。
        val setTextMethod = TextView::class.java.declaredMethods.firstOrNull {
            it.name == "setText" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == CharSequence::class.java
        }
        if (setTextMethod == null) {
            WeLogger.w(TAG, "setText(CharSequence) not found, feature disabled")
            return
        }
        setTextMethod.isAccessible = true

        setTextMethod.hookBeforeDirectly {
            val text = args[0] as? CharSequence ?: return@hookBeforeDirectly
            if (!FRIEND_COUNT_REGEX.containsMatchIn(text)) return@hookBeforeDirectly
            val view = thisObject as TextView
            // 只处理联系人页顶部那个「N个朋友」。
            //
            // 原先判断的是 activity 类名前缀 com.tencent.mm.ui.contact，但 8.0.78 起
            // 联系人页已并入 LauncherUI 的 tab，运行时不存在任何该前缀的 activity，
            // 条件恒为 false —— 设置里改完保存了、值也写进 prefs 了，界面却纹丝不动。
            //
            // 改为顺着 View 父链找 ContactCountView：它就是联系人页承载这个数字的容器。
            // 这样既能精确定位，又不会误伤资料页/搜索结果/群成员列表里同名的「N个朋友」。
            if (!isInsideContactCountView(view)) return@hookBeforeDirectly

            if (count == HIDE) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                args[0] = FRIEND_COUNT_REGEX.replaceFirst(text.toString(), count.toString())
            }
        }
        WeLogger.i(TAG, "setText(CharSequence) hook registered")
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var hide by remember { mutableStateOf(count == HIDE) }
            var displayCount by remember { mutableStateOf(if (count == HIDE) "0" else count.toString()) }

            AlertDialogContent(
                title = { Text("修改好友数量") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "隐藏好友数量",
                                checked = hide,
                                onCheckedChange = { hide = it },
                            )
                        }
                        item {
                            BaseSupportingWidget(
                                title = "显示数量",
                                enabled = !hide,
                            ) {
                                OutlinedTextField(
                                    value = displayCount,
                                    onValueChange = {
                                        displayCount = it.filter(Char::isDigit).take(7)
                                    },
                                    enabled = !hide,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        count = if (hide) HIDE else displayCount.toIntOrNull() ?: 0
                        WeLogger.i(TAG, "friend count display set to ${if (hide) "hidden" else count}")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * view 是否位于联系人页的 ContactCountView 之内。
 *
 * 实测父链（微信 8.0.78）：
 *   TextView < FrameLayout < ContactCountView < WxRecyclerView < ...
 * ContactCountView 是联系人页承载「N 个朋友」的容器，用它定位比判断 activity 可靠 ——
 * 联系人页已并入 LauncherUI，没有独立的 activity 类名可用。
 */
private fun isInsideContactCountView(view: View): Boolean {
    var parent: ViewParent? = view.parent
    var depth = 0
    while (parent != null && depth < 6) {
        if (parent.javaClass.name == "com.tencent.mm.ui.contact.ContactCountView") return true
        parent = (parent as? View)?.parent
        depth++
    }
    return false
}
