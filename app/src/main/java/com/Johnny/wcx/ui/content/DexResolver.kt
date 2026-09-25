package com.Johnny.wcx.ui.content

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dexkit.resolution.resolveAllDex
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.DexKit
import com.Johnny.wcx.utils.restartHost
import com.Johnny.wcx.utils.unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.io.PrintWriter
import java.io.StringWriter

private sealed class ScanProgress {
    data class Start(val displayName: String) : ScanProgress()
    data class Complete(val displayName: String) : ScanProgress()
    data class Failed(val displayName: String, val error: Exception) : ScanProgress()
}

private sealed class ScanResult {
    data class Success(val displayName: String) : ScanResult()
    data class Failed(val displayName: String, val error: Exception) : ScanResult()
}

private sealed class DialogPhase {
    object Idle : DialogPhase()
    object Scanning : DialogPhase()
    data class Done(val failed: List<ScanResult.Failed>) : DialogPhase()
    data class Error(val message: String) : DialogPhase()
}

private val TAG = "DexResolver"

/**
 * 适配全部成功后自动重启前的缓冲时间。
 *
 * 留这几秒是为了让用户看到「适配完成」而不是界面突然消失；
 * 太短会像崩溃，太长就失去了自动重启的意义。
 */
private const val AUTO_RESTART_DELAY_MS = 3_000L

@Composable
fun DexResolver(
    context: Context,
    outdatedItems: List<IResolveDex>,
    scope: CoroutineScope,
    dismiss: () -> Unit
) {
    var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Idle) }
    var currentTask by remember { mutableStateOf("正在适配...") }
    var completed by remember { mutableIntStateOf(0) }
    // 自动重启与手动重启共享的闸门：两者都走这里，避免各触发一次。
    var restartTriggered by remember { mutableStateOf(false) }
    // 自动重启倒计时任务；用户先手动重启/关闭时取消它。
    var autoRestartTimer by remember { mutableStateOf<Job?>(null) }
    val scanResults = remember { mutableStateMapOf<String, ScanResult>() }

    fun updateProgress(progress: ScanProgress) {
        when (progress) {
            is ScanProgress.Complete -> {
                scanResults[progress.displayName] = ScanResult.Success(progress.displayName)
                completed = scanResults.size
                currentTask = "已完成: ${progress.displayName}"
            }

            is ScanProgress.Failed -> {
                scanResults[progress.displayName] = ScanResult.Failed(progress.displayName, progress.error)
                completed = scanResults.size
                currentTask = "失败: ${progress.displayName}"
            }

            else -> {}
        }
    }

    suspend fun scanItem(
        item: IResolveDex,
        dexKit: DexKitBridge,
        progressChannel: Channel<ScanProgress>
    ): ScanResult {
        val displayName = if (item is BaseFeature) item.displayName else unreachable()
        return try {
            progressChannel.send(ScanProgress.Start(displayName))

            item.resolveAllDex(dexKit)

            DexCacheManager.saveItemCache(item)
            progressChannel.send(ScanProgress.Complete(displayName))
            ScanResult.Success(displayName)
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to scan: $displayName", e)
            progressChannel.send(ScanProgress.Failed(displayName, e))
            ScanResult.Failed(displayName, e)
        }
    }

    fun startScanning() {
        phase = DialogPhase.Scanning
        scope.launch {
            try {
                val progressChannel = Channel<ScanProgress>(Channel.UNLIMITED)

                // progress consumer on Main
                launch(Dispatchers.Main) {
                    for (p in progressChannel) updateProgress(p)
                }

                // parallel scan — same flow/buffer/async structure
                val results = outdatedItems.asFlow()
                    .map { item ->
                        async(Dispatchers.IO) {
                            scanItem(
                                item,
                                DexKit,
                                progressChannel
                            )
                        }
                    }
                    .buffer(8)
                    .map { it.await() }
                    .toList()

                progressChannel.close()

                val failed = results.filterIsInstance<ScanResult.Failed>()
                phase = DialogPhase.Done(failed)

                // 适配全部成功时自动重启，免去用户「手动重启 → 才发现没生效 → 再重启」的两轮循环。
                //
                // 有失败项时**不**自动重启：失败详情需要用户看到（并可能复制上报），
                // 自动重启会把错误刷掉。此时仍由用户点「重启微信」。
                if (failed.isEmpty()) {
                    autoRestartTimer = launch {
                        delay(AUTO_RESTART_DELAY_MS)
                        if (!restartTriggered) {
                            restartTriggered = true
                            dismiss()
                            restartHost()
                        }
                    }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "scanning failed", e)
                phase = DialogPhase.Error("扫描过程中发生未知错误: ${e.message}")
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title with icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DEX 缓存更新",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Badge showing count
                if (phase is DialogPhase.Idle || phase is DialogPhase.Scanning) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${outdatedItems.size}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider()

            // Tip text
            val tipText = when (val p = phase) {
                is DialogPhase.Idle ->
                    "检测到 ${outdatedItems.size} 个功能需要更新 DEX 缓存, 开始适配后将自动扫描并更新。" +
                            "若直接关闭对话框, 相关功能将不会被加载"

                is DialogPhase.Scanning -> null
                is DialogPhase.Done ->
                    if (p.failed.isEmpty()) "适配完成! 所有功能已成功更新 DEX 缓存"
                    else "适配完成, 但有 ${p.failed.size} 个功能失败 (不影响其他功能使用)"

                is DialogPhase.Error -> p.message
            }
            if (tipText != null) {
                Text(text = tipText, style = MaterialTheme.typography.bodyMedium)
            }

            // Progress
            AnimatedVisibility(visible = phase is DialogPhase.Scanning) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = currentTask, style = MaterialTheme.typography.bodyMedium)
                    LinearWavyProgressIndicator(
                        progress = { if (outdatedItems.isEmpty()) 0f else completed.toFloat() / outdatedItems.size },
                        modifier = Modifier.fillMaxWidth(),
                        amplitude = { progress ->
                            if (progress == 0f || progress == 1f) {
                                0f
                            } else {
                                1f
                            }
                        }
                    )
                    Text(
                        text = "总进度: $completed/${outdatedItems.size}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) // indeterminate sub-bar
                }
            }

            // Error details (Done with failures)
            val donePhase = phase as? DialogPhase.Done
            AnimatedVisibility(visible = donePhase?.failed?.isNotEmpty() == true) {
                donePhase?.failed?.let { failed ->
                    ErrorDetailsSection(
                        failedResults = failed,
                        onCopy = {
                            val report = buildErrorReport(failed)
                            copyToClipboard(context, report)
                            showToast(context, "已复制")
                        }
                    )
                }
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (phase !is DialogPhase.Scanning) {
                    TextButton(onClick = {
                        // 用户自己关了对话框（可能想先看看别处）：取消自动重启，
                        // 否则倒计时一到会在他不预期的时候把微信重启掉。
                        autoRestartTimer?.cancel()
                        dismiss()
                    }) { Text("关闭") }
                }
                if (phase is DialogPhase.Idle) {
                    Button(onClick = ::startScanning) { Text("开始适配") }
                }
                if (phase is DialogPhase.Done || phase is DialogPhase.Error) {
                    Button(onClick = {
                        autoRestartTimer?.cancel()
                        if (!restartTriggered) {
                            restartTriggered = true
                            dismiss()
                            restartHost()
                        }
                    }) { Text("重启微信") }
                }
            }
        }
    }
}

@Composable
private fun ErrorDetailsSection(
    failedResults: List<ScanResult.Failed>,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val errorText = buildString {
                failedResults.forEachIndexed { i, r ->
                    append("${i + 1}. ${r.displayName}\n")
                    append("   错误: ${r.error.message}\n\n")
                }
            }
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
            )
            TextButton(onClick = onCopy) { Text("复制错误信息") }
        }
    }
}

private fun buildErrorReport(failedResults: List<ScanResult.Failed>) = buildString {
    append("=== WCX Dex 扫描错误报告 ===\n\n")
    failedResults.forEachIndexed { i, r ->
        append("${i + 1}. ${r.displayName}\n")
        append("   错误信息: ${r.error.message}\n")
        append("   堆栈跟踪:\n")
        val sw = StringWriter()
        r.error.printStackTrace(PrintWriter(sw))
        append(sw.toString())
        append("\n\n")
    }
}
