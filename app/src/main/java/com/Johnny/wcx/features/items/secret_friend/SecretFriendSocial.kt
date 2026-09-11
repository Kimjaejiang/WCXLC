package com.Johnny.wcx.features.items.secret_friend

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeMomentsContextMenuApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.VisibilityOffIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast

/**
 * 社交隐藏组 15 / 16 / 17：隐藏我的朋友圈（含已隐藏列表管理）、发现页朋友圈入口、发现页入口。
 */

// ─────────────────────────── 15. 隐藏我的朋友圈 ───────────────────────────

/**
 * 隐藏我的朋友圈（浮云语义）：长按相册页/时间线任意一条朋友圈加入隐藏，feed 查询层按
 * snsId 过滤（独立存储键，与密友名单无关）。入口复用工程「朋友圈菜单增强扩展」——
 * TimelineOnCreateContextMenuListener 同时覆盖时间线与相册页列表。
 */
@Feature(
    name = "隐藏我的朋友圈",
    categories = ["密友功能"],
    description = "长按相册页任意一条朋友圈加入隐藏（自己的或好友的均可），时间线/相册页不再显示；配合「已隐藏朋友圈管理」移除"
)
object HideMyMoments : SwitchFeature(), IResolveDex, WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideMyMoments"

    /** 已隐藏朋友圈 snsId 列表（独立存储键）。 */
    private const val KEY_HIDDEN_MOMENTS = "my_moments_hidden"

    private val MENU_ID = "my_moments_hide".hashCode()

    fun getHiddenMoments(): Set<String> = WePrefs.getStringSetOrDef(KEY_HIDDEN_MOMENTS, emptySet())

    fun addHiddenMoment(snsId: Long) {
        if (snsId == 0L) return
        WePrefs.putStringSet(KEY_HIDDEN_MOMENTS, getHiddenMoments() + snsId.toString())
        WeLogger.i(TAG, "moment $snsId added to hidden list")
    }

    fun removeHiddenMoment(snsId: String) {
        WePrefs.putStringSet(KEY_HIDDEN_MOMENTS, getHiddenMoments() - snsId)
    }

    fun clearHiddenMoments() {
        WePrefs.putStringSet(KEY_HIDDEN_MOMENTS, emptySet())
    }

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        runCatching { WeDatabaseListenerApi.removeListener(this) }
            .onFailure { WeLogger.e(TAG, "removeListener failed", it) }
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> = listOf(
        WeMomentsContextMenuApi.MenuItem(
            MENU_ID,
            "加入朋友圈隐藏",
            VisibilityOffIcon,
            { context, _ -> context.snsInfo != null },
        ) { context ->
            val snsId = context.snsInfo?.reflekt()
                ?.firstFieldOrNull {
                    name = "field_snsId"
                    superclass()
                }?.get()?.let { it as? Number }?.toLong() ?: 0L
            if (snsId == 0L) {
                WeLogger.w(TAG, "failed to resolve snsId from snsInfo=${context.snsInfo?.javaClass?.name}")
                showToast(context.activity, "该朋友圈暂不支持加入隐藏")
                return@MenuItem
            }
            addHiddenMoment(snsId)
            SecretFriendState.showToastIfEnabled(context.activity, SecretFriendState.toastMomentHidden)
        }
    )

    /**
     * 时间线主信息流按 snsId 注入 `SnsInfo.snsId NOT IN (...)`（复用朋友圈 feed 的两种
     * 标记形态；个人主页 userName= 查询跳过，否则相册页整页变空）。
     */
    override fun onQuery(sql: String): String? {
        val hidden = getHiddenMoments()
        if (hidden.isEmpty()) return null
        if (!sql.contains("from SnsInfo", ignoreCase = true)) return null
        if (sql.contains("SnsInfo.userName=", ignoreCase = false)) return null
        if (sql.contains("SnsInfo.snsId NOT IN", ignoreCase = true)) return null

        val snsIds = hidden.mapNotNull { it.toLongOrNull() }
        if (snsIds.size != hidden.size) return null
        val list = snsIds.joinToString(",")
        val filter = " AND SnsInfo.snsId NOT IN ($list) "

        return when {
            sql.contains("(sourceType & 2 != 0 )") ->
                sql.replaceFirst("(sourceType & 2 != 0 )", "(sourceType & 2 != 0 )" + filter)
            sql.contains("(1=1)") ->
                sql.replaceFirst("(1=1)", "(1=1)" + filter)
            else -> null
        }
    }
}

/** 管理已隐藏的朋友圈列表（浮云「管理已隐藏列表」入口）。 */
@Feature(
    name = "已隐藏朋友圈管理",
    categories = ["密友功能"],
    description = "查看通过「隐藏我的朋友圈」加入的条目，逐条移除或一键清空"
)
object ManageHiddenMoments : ClickableFeature() {

    private const val TAG = "ManageHiddenMoments"

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var hidden by remember { mutableStateOf(HideMyMoments.getHiddenMoments().sorted()) }

            AlertDialogContent(
                title = { Text("已隐藏的朋友圈（${hidden.size}）") },
                text = {
                    if (hidden.isEmpty()) {
                        Text("暂无隐藏条目。长按相册页/时间线的朋友圈即可加入隐藏。")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            items(hidden, key = { it }) { snsId ->
                                ListItem(
                                    headlineContent = { Text("朋友圈 snsId: $snsId") },
                                    trailingContent = {
                                        M3TextButton(onClick = {
                                            HideMyMoments.removeHiddenMoment(snsId)
                                            hidden = hidden - snsId
                                        }) { Text("移除") }
                                    }
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
                confirmButton = {
                    if (hidden.isNotEmpty()) {
                        TextButton(onClick = {
                            HideMyMoments.clearHiddenMoments()
                            hidden = emptyList()
                        }) { Text("清空") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("确定") }
                    }
                }
            )
        }
    }
}

// ─────────────── 16 / 17. 发现页朋友圈入口 / 发现页入口 ───────────────

/**
 * 发现页入口隐藏的共用实现（结构式 view 树过滤，allowFailure）：
 * DiscoverUI.onCreate/onResume 后在 view 树里按行标题文本定位列表行并隐藏。
 * DiscoverUI 类名每版可能漂移 → dexMethod allowFailure，解析失败整段跳过。
 * 行定位/隐藏全部空安全，页面重建时重复执行幂等。
 */
private fun hideDiscoverRowsByTitle(activity: Activity, titles: Set<String>) {
    val root = activity.window?.decorView ?: return
    // post 到下一帧执行：onCreate 时机列表行尚未绑定
    root.post {
        hideRowsMatching(root, titles)
    }
}

private fun hideRowsMatching(view: View, titles: Set<String>) {
    if (view is TextView && view.text?.toString().orEmpty() in titles) {
        // 沿父链找到列表行容器（RecyclerView/ListView/GridView 的直接子 view）后整行隐藏；
        // 行内可能有多处命中标题的子 view，重复隐藏幂等
        var row: View = view
        var parent = row.parent
        while (parent is ViewGroup && parent !is androidx.recyclerview.widget.RecyclerView &&
            parent !is android.widget.ListView && parent !is android.widget.GridView
        ) {
            row = parent
            parent = row.parent
        }
        row.visibility = View.GONE
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            view.getChildAt(i)?.let { hideRowsMatching(it, titles) }
        }
    }
}

/**
 * 发现页朋友圈入口隐藏（浮云语义）：发现页列表中的「朋友圈」入口行隐藏。
 * DiscoverUI 类名漂移 → dexMethod allowFailure + isPlaceholder 守卫，解析失败整段跳过。
 */
@Feature(
    name = "发现页朋友圈入口隐藏",
    categories = ["密友功能"],
    description = "隐藏发现页中的「朋友圈」入口行（结构式实现，微信版本类名变化时自动跳过）"
)
object HideDiscoverMoments : SwitchFeature(), IResolveDex {

    private const val TAG = "HideDiscoverMoments"

    private val methodDiscoverUiOnCreate by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.discover.DiscoverUI"
            name = "onCreate"
            paramCount = 1
        }
    }

    private val methodDiscoverUiOnResume by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.discover.DiscoverUI"
            name = "onResume"
            paramCount = 0
        }
    }

    override fun onEnable() {
        if (methodDiscoverUiOnCreate.isPlaceholder) {
            WeLogger.w(TAG, "DiscoverUI 未解析, 发现页入口不过滤")
            return
        }
        val rowTitles = setOf("朋友圈")
        methodDiscoverUiOnCreate.hookAfter {
            hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
        }
        // onResume 重走一遍：页面重建后行会重新绑定
        methodDiscoverUiOnResume.hookAfter {
            hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
        }
    }
}

/**
 * 发现页入口隐藏（浮云语义）：发现页列表中的视频号 / 看一看 / 小程序入口行隐藏。
 * 实现同 HideDiscoverMoments。
 */
@Feature(
    name = "发现页入口隐藏",
    categories = ["密友功能"],
    description = "隐藏发现页中的视频号 / 看一看 / 小程序入口行（结构式实现，微信版本类名变化时自动跳过）"
)
object HideDiscoverEntries : SwitchFeature(), IResolveDex {

    private const val TAG = "HideDiscoverEntries"

    private val methodDiscoverUiOnCreate by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.discover.DiscoverUI"
            name = "onCreate"
            paramCount = 1
        }
    }

    private val methodDiscoverUiOnResume by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.discover.DiscoverUI"
            name = "onResume"
            paramCount = 0
        }
    }

    override fun onEnable() {
        if (methodDiscoverUiOnCreate.isPlaceholder) {
            WeLogger.w(TAG, "DiscoverUI 未解析, 发现页入口不过滤")
            return
        }
        val rowTitles = setOf("视频号", "看一看", "小程序")
        methodDiscoverUiOnCreate.hookAfter {
            hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
        }
        methodDiscoverUiOnResume.hookAfter {
            hideDiscoverRowsByTitle(thisObject as Activity, rowTitles)
        }
    }
}
