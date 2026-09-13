package com.Johnny.wcx.features.items.system

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.hot.HotUpdateManager
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 热更新入口（微信内）。
 *
 * 无开关、常驻可点：点击后检查远端 manifest，有更新则下载校验，成功后提示重启微信生效。
 */
@Feature(
    name = "热更新",
    categories = ["系统与隐私"],
    description = "检查并安装外部插件 APK，更新逻辑无需重装模块，安装后重启微信生效"
)
object HotUpdateFeature : ClickableFeature() {

    override val alwaysEnabled = true

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            HotUpdateDialog()
        }
    }
}

/** 对话框状态机：Idle → Checking → Available → Downloading → Ready，任一失败进 Failed。 */
private sealed interface HotState {
    data object Idle : HotState
    data object Checking : HotState
    data object UpToDate : HotState
    data class Available(val manifest: com.Johnny.wcx.hot.HotManifest) : HotState
    data class Downloading(val done: Long, val total: Long, val manifest: com.Johnny.wcx.hot.HotManifest) : HotState
    data class Ready(val version: String, val fileName: String) : HotState
    data class Failed(val reason: String) : HotState
}

@Composable
private fun com.Johnny.wcx.ui.utils.ShowComposeDialogScope.HotUpdateDialog() {
    var state by remember { mutableStateOf<HotState>(HotState.Idle) }
    val scope = remember { CoroutineScope(Dispatchers.Main) }

    fun check() {
        state = HotState.Checking
        scope.launch {
            state = when (val r = HotUpdateManager.checkRemote()) {
                is HotUpdateManager.CheckResult.UpToDate -> HotState.UpToDate
                is HotUpdateManager.CheckResult.UpdateAvailable -> HotState.Available(r.manifest)
                is HotUpdateManager.CheckResult.ShellTooOld ->
                    HotState.Failed(
                        "插件要求微信 ≥ ${r.requiredName.ifBlank { "versionCode ${r.required}" }}" +
                            "，当前 ${r.currentName.ifBlank { "versionCode ${r.current}" }}，请先升级微信"
                    )
                is HotUpdateManager.CheckResult.Error -> HotState.Failed(r.message)
            }
        }
    }

    fun install(manifest: com.Johnny.wcx.hot.HotManifest) {
        state = HotState.Downloading(0L, 0L, manifest)
        scope.launch {
            val result = HotUpdateManager.downloadAndPrepare(manifest) { done, total ->
                state = HotState.Downloading(done, total, manifest)
            }
            state = result.fold(
                onSuccess = { file ->
                    // 清掉除本次以外残留的旧插件包，避免 hot/ 目录无限膨胀
                    withContext(Dispatchers.IO) { HotUpdateManager.cleanupOldApks(file.name) }
                    HotState.Ready(manifest.version, file.name)
                },
                onFailure = { HotState.Failed(it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    LaunchedEffect(Unit) {
        val installed = HotUpdateManager.installedManifest()
        if (installed == null) check()
    }

    val installedVersion = remember { HotUpdateManager.installedManifest()?.version }

    AlertDialogContent(
        title = { Text("热更新") },
        text = {
            DefaultColumn {
                val current = installedVersion
                Text(
                    if (current == null) "当前未安装插件，运行的是模块内置逻辑。"
                    else "当前已安装插件版本：$current"
                )

                when (val s = state) {
                    is HotState.Idle ->
                        Text("点击「检查更新」从远端获取最新插件。")

                    is HotState.Checking -> {
                        Text("正在检查更新…")
                        LinearWavyProgressIndicator()
                    }

                    is HotState.UpToDate -> Text("已是最新版本，无需更新。")

                    is HotState.Available -> {
                        Text("发现新版本 ${s.manifest.version}")
                        if (s.manifest.changelog.isNotBlank()) {
                            Text("更新内容：\n${s.manifest.changelog}")
                        }
                        Text(
                            "插件体积 ${s.manifest.fileName}；下载后会校验 SHA-256 与签名，" +
                                    "与模块签名不一致将拒绝安装。"
                        )
                    }

                    is HotState.Downloading -> {
                        Text("正在下载并校验…")
                        if (s.total > 0) {
                            LinearWavyProgressIndicator(
                                progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }
                            )
                            Text("${s.done / 1024} / ${s.total / 1024} KB")
                        } else {
                            LinearWavyProgressIndicator()
                            Text("已下载 ${s.done / 1024} KB")
                        }
                    }

                    is HotState.Ready -> {
                        Text("插件 ${s.version} 已就绪（${s.fileName}）。\n重启微信后生效。")
                    }

                    is HotState.Failed -> Text("失败：${s.reason}")
                }
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text("关闭") }
        },
        confirmButton = {
            when (val s = state) {
                is HotState.Available -> {
                    Button(onClick = { install(s.manifest) }) { Text("下载并安装") }
                }

                is HotState.Ready -> {
                    Button(onClick = {
                        HotUpdateManager.restartHost { ok, msg ->
                            if (!ok) showToast("重启失败：${msg ?: "未知错误"}")
                        }
                    }) { Text("重启微信生效") }
                }

                is HotState.Checking, is HotState.Downloading -> {
                    Button(onClick = {}, enabled = false) { Text("请稍候") }
                }

                is HotState.Failed -> {
                    Button(onClick = { check() }) { Text("重试") }
                }

                else -> {
                    Button(onClick = { check() }) { Text("检查更新") }
                }
            }
        }
    )
}
