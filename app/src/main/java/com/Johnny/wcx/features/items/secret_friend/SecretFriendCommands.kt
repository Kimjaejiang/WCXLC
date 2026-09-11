package com.Johnny.wcx.features.items.secret_friend

import android.app.Activity
import androidx.compose.runtime.Composable
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.m3.TextFieldDialogWidget
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * 指令自定义组 31：临时解除指令（浮云 SearchCommandPluginPart 语义）。
 *
 * 拦截点：主页顶部搜索框（FTSBaseMainUI）的逐字搜索回调——`void f(String query)`。
 * 回调方法名每版漂移，且 8.0.77 上该类有多个 `void (String)` 方法（DexKit 取首个
 * 曾命中错误方法导致指令无效），故改为运行时按形状全量 hook：
 * 类名稳定（FTSBaseMainUI）+ 单 String 入参 + void 返回；指令比对不匹配的 hook
 * 直接返回，误挂无副作用。指令命中即临时解除并退出搜索页。
 */
private const val TAG_COMMANDS = "SecretFriend.Commands"

/** 主页 FTS 搜索 UI（稳定类名，浮云同款拦截点）。 */
private const val FTS_MAIN_SEARCH_UI = "com.tencent.mm.plugin.fts.ui.FTSBaseMainUI"

// ─────────────────────────── 31. 临时解除指令 ───────────────────────────

/**
 * 临时解除指令：在主页搜索框输入指令（默认 `#mm#`）→ 临时解除隐藏
 * （[SecretFriendState.tempShowForMinutes]，时长可在本行下方调整，到期自动恢复），
 * 并退出搜索页。
 */
@Feature(
    name = "临时解除指令",
    categories = ["密友功能"],
    description = "在主页搜索框输入自定义指令（默认 #mm#）临时解除隐藏；指令文字与解除时长在本行下方修改"
)
object SearchCommandTempUnhide : SwitchFeature() {

    private const val TAG = TAG_COMMANDS

    /** 临时解除的搜索指令（默认 #mm#，可自定义）。 */
    var tempUnhideCommand by prefOption("secret_friend_cmd_temp_unhide", "#mm#")

    override fun onEnable() {
        val ftsClass = runCatching { FTS_MAIN_SEARCH_UI.toClass() }.getOrNull()
        if (ftsClass == null) {
            WeLogger.w(TAG, "FTSBaseMainUI not found; temp-unhide command unavailable")
            return
        }

        val methods = runCatching {
            ftsClass.reflekt().methods {
                parameters(String::class)
                returnType(Void.TYPE)
            }
        }.getOrDefault(emptyList())

        if (methods.isEmpty()) {
            WeLogger.w(TAG, "no void(String) method on FTSBaseMainUI; temp-unhide command unavailable")
            return
        }

        methods.forEach { method ->
            method.hookBefore {
                val query = args.getOrNull(0) as? String ?: return@hookBefore
                if (query != tempUnhideCommand) return@hookBefore
                val activity = thisObject as? Activity ?: return@hookBefore
                WeLogger.i(TAG, "temp-unhide search command matched")
                SecretFriendState.tempShowForMinutes(activity)
                runCatching { activity.finish() }
            }
        }
        WeLogger.i(TAG, "temp-unhide command hooks installed on ${methods.size} void(String) methods")
    }

    @Composable
    override fun Ui() {
        TextFieldDialogWidget(
            title = "临时解除指令",
            value = tempUnhideCommand,
            onValueChange = { tempUnhideCommand = it },
            dialogTitle = "临时解除指令文字",
            confirmLabel = "确定",
            dismissLabel = "取消",
        )
        TextFieldDialogWidget(
            title = "临时显示时长(分钟)",
            value = SecretFriendState.tempShowMinutes.toString(),
            onValueChange = { raw ->
                raw.filter { it.isDigit() }.take(4).toIntOrNull()?.let {
                    SecretFriendState.tempShowMinutes = it
                }
            },
            dialogTitle = "临时显示时长（分钟，1–1440）",
            confirmLabel = "确定",
            dismissLabel = "取消",
        )
    }
}
