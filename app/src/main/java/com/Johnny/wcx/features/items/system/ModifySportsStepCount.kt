package com.Johnny.wcx.features.items.system

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.utils.createInstance
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast

@Feature(name = "修改运动步数", categories = ["系统与隐私"], description = "修改微信获取到的或手动上传运动步数")
object ModifySportsStepCount : ClickableFeature(), IResolveDex {

    enum class PassiveMode { FIXED, MULTIPLIER }

    private val methodGetSteps by dexMethod {
        searchPackages("com.tencent.mm.plugin.sport.model")
        matcher {
            usingEqStrings("MicroMsg.Sport.DeviceStepManager", "get today step from %s todayStep %d")
        }
    }
    private val methodUploadSteps by dexMethod {
        searchPackages("com.tencent.mm.plugin.sport.model")
        matcher {
            usingEqStrings("MicroMsg.Sport.DeviceStepManager", "update device Step time: %s stepCount: %s")
        }
    }

    override fun onEnable() {
        methodGetSteps.hookAfter {
            try {
                // 仅当原方法返回 Long 时才设置 result，避免对非 Long 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Long::class.javaPrimitiveType || returnType == java.lang.Long::class.java) {
                        val original = result as Long
                        result = when (passiveMode) {
                            PassiveMode.FIXED -> {
                                val fixed = passiveValue
                                if (fixed < 0L) return@hookAfter
                                fixed
                            }
                            // 倍率支持小数（如 1.5 倍）：小数倍率乘完取整，结果仍是合法步数
                            PassiveMode.MULTIPLIER -> {
                                val multiplier = effectiveMultiplier
                                if (multiplier < 0f) return@hookAfter
                                (original * multiplier).toLong()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }
    }

    private var passiveModeStr by prefOption("step_passive_mode", PassiveMode.FIXED.name)
    private var passiveMode: PassiveMode
        get() = runCatching { PassiveMode.valueOf(passiveModeStr) }.getOrDefault(PassiveMode.FIXED)
        set(v) {
            passiveModeStr = v.name
        }

    /** 固定模式步数（整数） */
    private var passiveValue by prefOption("step_passive_value", -1L)

    /** 倍率模式倍率（支持小数，如 1.5）；负数表示未设置 */
    private var passiveMultiplier by prefOption(KEY_MULTIPLIER, -1f)

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var modeState by remember { mutableStateOf(passiveMode) }
            var passiveInput by remember { mutableStateOf(initialPassiveInput(passiveMode)) }
            var activeInput by remember { mutableStateOf("") }
            val activeIsEmpty = activeInput.isEmpty()

            AlertDialogContent(
                title = { Text("修改运动步数") },
                text = {
                    DefaultColumn {
                        // 被动模式: 固定 / 倍率
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("被动上传模式", modifier = Modifier.weight(1f))
                            SingleChoiceSegmentedButtonRow {
                                PassiveMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = modeState == mode,
                                        // 两个模式各自存一份值：切模式时载入该模式已保存的值（固定=整数，倍率=小数）
                                        onClick = {
                                            modeState = mode
                                            passiveInput = initialPassiveInput(mode)
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index, PassiveMode.entries.size
                                        )
                                    ) {
                                        Text(if (mode == PassiveMode.FIXED) "固定" else "倍率")
                                    }
                                }
                            }
                        }

                        // 被动值
                        TextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = passiveInput,
                            onValueChange = {
                                // 倍率模式允许小数；固定模式是步数，只接受整数
                                passiveInput = if (modeState == PassiveMode.MULTIPLIER) {
                                    sanitizeDecimal(it)
                                } else {
                                    it.filter { c -> c.isDigit() }.trim()
                                }
                            },
                            label = {
                                Text(
                                    if (modeState == PassiveMode.MULTIPLIER) "被动上传倍率 (支持小数, 如 1.5)"
                                    else "被动上传固定步数 (整数)"
                                )
                            }
                        )

                        // 主动值 + 立即上传
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                modifier = Modifier.weight(1f),
                                value = activeInput,
                                onValueChange = {
                                    activeInput = it.filter { c -> c.isDigit() }.trim()
                                },
                                label = { Text("主动上传值") },
                            )
                            Button(
                                enabled = !activeIsEmpty,
                                onClick = {
                                    val count = activeInput.toLongOrNull() ?: run {
                                        showToast("格式不正确!")
                                        return@Button
                                    }
                                    val sportsMan =
                                        methodUploadSteps.method.declaringClass.createInstance()
                                    val ok =
                                        methodUploadSteps.method.invoke(sportsMan, count) as Boolean
                                    showToast(context, "已上传! 返回结果: ${if (ok) "成功" else "失败"}")
                                }
                            ) {
                                Text("上传")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        passiveMode = modeState
                        when (modeState) {
                            PassiveMode.FIXED -> passiveValue = passiveInput.toLongOrNull() ?: -1L
                            // 倍率支持小数；格式不合法按未设置处理
                            PassiveMode.MULTIPLIER -> passiveMultiplier = passiveInput.toFloatOrNull() ?: -1f
                        }
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }

    private const val KEY_MULTIPLIER = "step_passive_multiplier"

    /**
     * 生效倍率：优先取新版小数倍率；旧版把倍率存在整数 pref 里，
     * 仅当新版 key 从未写入过、且当前保存的模式确实是倍率时才回退读取，
     * 避免把固定步数（如 20000）误当成倍率。
     */
    private val effectiveMultiplier: Float
        get() {
            if (passiveMultiplier >= 0f || WePrefs.containsKey(KEY_MULTIPLIER)) return passiveMultiplier
            return if (passiveMode == PassiveMode.MULTIPLIER && passiveValue >= 0L) passiveValue.toFloat() else -1f
        }

    /** 只保留数字与至多一个小数点（输入 ".5" 补成 "0.5"），供倍率输入使用。 */
    private fun sanitizeDecimal(raw: String): String {
        val sb = StringBuilder()
        var dotUsed = false
        for (c in raw) {
            if (c.isDigit()) sb.append(c)
            else if (c == '.' && !dotUsed) {
                if (sb.isEmpty()) sb.append('0')
                dotUsed = true
                sb.append('.')
            }
        }
        return sb.toString()
    }

    /** 输入框初值：按模式读取各自保存的值（固定=整数，倍率=小数）。 */
    private fun initialPassiveInput(mode: PassiveMode): String = when (mode) {
        PassiveMode.FIXED -> if (passiveValue >= 0L) passiveValue.toString() else ""
        PassiveMode.MULTIPLIER -> {
            val m = effectiveMultiplier
            when {
                m < 0f -> ""
                m == m.toLong().toFloat() -> m.toLong().toString()
                else -> m.toString()
            }
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
