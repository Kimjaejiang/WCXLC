package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Error
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Report
import com.composables.icons.materialsymbols.outlined.Warning
import com.Johnny.wcx.features.core.FeatureHealth
import com.Johnny.wcx.features.core.FeaturesLoader
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.formatEpoch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SELF_CHECK_TAG = "FeatureSelfCheck"

/**
 * 模块健康自检的展示行。
 *
 * 与 [FeatureHealth.Entry] 的区别：这是给用户看的「一行结论」，
 * 把内部状态翻译成状态词和档位色，不暴露 SKIPPED_INCOMPLETE_CACHE 这类术语。
 */
private data class CheckRow(
    val name: String,
    val detail: String,
    val grade: FeatureHealth.Grade,
    /**
     * 行内标记。为空时用 [grade] 的档位标签。
     *
     * 「已挂钩但未被调用」用这个走灰色「待验证」，与真正的故障区分开 ——
     * 它既可能是微信改了调用路径，也可能只是本次还没用到。
     */
    val tag: String? = null,
)

/** 行标记色：档位色优先，[CheckRow.tag] 存在时统一走中性灰。 */
private fun tagTint(row: CheckRow, colors: GradeColors): Color =
    if (row.tag != null) colors.neutral else gradeColor(row.grade, colors)

/** 把内部状态翻译成用户能看懂的一句话。 */
private fun statusText(entry: FeatureHealth.Entry): String = when {
    entry.status == FeatureHealth.Status.FAILED ->
        "启动异常${entry.detail?.let { ": $it" } ?: ""}"

    entry.status == FeatureHealth.Status.ENABLE_FAILED ->
        "开关已打开，但功能未生效"

    entry.status == FeatureHealth.Status.SKIPPED_INCOMPLETE_CACHE ->
        "适配数据缺失，下次启动自动修复"

    entry.status == FeatureHealth.Status.SKIPPED_CACHE_FAILED ->
        "适配数据损坏，已加入修复队列"

    entry.hasMissingAnchorsWhileDisabled ->
        "未启用；启用后有 ${entry.missingAnchors.size} 处适配点会失效"

    entry.hasMissingAnchors ->
        "部分适配点未命中（${entry.missingAnchors.size} 处）"

    // 「未被调用」不作故障判定：拿不到实时计数时说明本进程读不到，
    // 拿得到也只能说明「本次还没用到」。两种都不算异常。
    entry.hasIdleHooks ->
        "已挂钩 ${entry.installedHooks} 处，本次尚未被调用"

    entry.installedHooks > 0 ->
        "已挂钩 ${entry.installedHooks} 处，已调用 ${entry.hookCalls} 次"

    else -> "无挂钩，纯配置/界面功能"
}

private fun gradeLabel(grade: FeatureHealth.Grade): String = when (grade) {
    FeatureHealth.Grade.OK -> "正常"
    FeatureHealth.Grade.DEGRADED -> "异常"
    FeatureHealth.Grade.FAILED -> "失败"
}

private fun gradeColor(grade: FeatureHealth.Grade, scheme: GradeColors): Color = when (grade) {
    FeatureHealth.Grade.OK -> scheme.ok
    FeatureHealth.Grade.DEGRADED -> scheme.warn
    FeatureHealth.Grade.FAILED -> scheme.error
}

/** 三档状态色 + 中性灰。集中在一处，避免各处硬编码色值漂移。 */
private data class GradeColors(
    val ok: Color,
    val warn: Color,
    val error: Color,
    val neutral: Color,
)

private val DEFAULT_GRADE_COLORS = GradeColors(
    ok = Color(0xFF2E7D32),
    warn = Color(0xFFEF6C00),
    error = Color(0xFFC62828),
    neutral = Color(0xFF757575),
)

/**
 * 模块自检页。
 *
 * 数据来自 [FeatureHealth]：加载期由 FeaturesLoader 写入快照，
 * 这里按下「开始检测」时回读一次当前的 hook 计数（调用数会随使用增长，
 * 快照那一刻的数字没有意义）。
 *
 * 之所以要手动触发而不是进来就跑：这个页面本身跑在微信进程内，
 * 采集必须遍历全部功能实例，开页面就跑会让进入设置页变慢。
 */
@Composable
internal fun ModuleSelfCheckContent(
    context: android.content.Context,
    contentPadding: PaddingValues,
) {
    val scope = rememberCoroutineScope()

    // null 表示还没检测过 —— 与「检测过但没问题」区分开，否则空白页看起来像坏了。
    var rows by remember { mutableStateOf<List<CheckRow>?>(null) }
    var summary by remember { mutableStateOf<FeatureHealth.Summary?>(null) }
    var running by remember { mutableStateOf(false) }
    // 本进程拿不到实时 hook 计数时为 false：此时「未被调用」不具参考性。
    var liveCountsAvailable by remember { mutableStateOf(true) }
    // 本次快照的采集时刻。0 表示加载尚未完成。
    var snapshotAt by remember { mutableStateOf(0L) }

    // 环境信息在重组间保持稳定，取一次即可。
    val wechatVersion = remember { safeGetWeChatVersionInfo(context) }
    val lspEnv = remember { detectOrReadLspEnvironment(context) }
    val lspApi = remember { safeGetLspApiVersion() }

    fun runCheck() {
        scope.launch {
            running = true
            val (built, sum, liveOk) = withContext(Dispatchers.Default) {
                runCatching {
                    // 先重采一次：启动期发布的快照可能赶在锚点解析完成前，
                    // 直接读它会报出一批「已解析好」的假异常。
                    FeaturesLoader.recheckHealth()
                    val all = FeaturesProvider.ALL_HOOK_ITEMS
                    val canRead = FeatureHealth.canReadLiveCounts(all)
                    val live = FeatureHealth.liveSnapshot(all)
                    val s = FeatureHealth.Summary.of(live)
                    // 问题排前面，正常殿后，方便一眼看到要处理的东西。
                    val sorted = live.sortedWith(
                        compareBy({ it.grade.ordinal * -1 }, { it.name }),
                    )
                    val r = sorted.map { e ->
                        CheckRow(
                            name = e.name,
                            detail = statusText(e),
                            grade = e.grade,
                            // 这些都不算故障，给中性标记，不归入异常：
                            // 未启用功能的锚点落空是隐患，挂钩未调用是本次还没用到。
                            tag = when {
                                e.hasMissingAnchorsWhileDisabled -> "未启用"
                                e.hasIdleHooks -> "待验证"
                                else -> null
                            },
                        )
                    }
                    Triple(r, s, canRead)
                }.onFailure {
                    WeLogger.e(SELF_CHECK_TAG, "self-check failed", it)
                }.getOrNull()
                    ?: Triple(emptyList<CheckRow>(), FeatureHealth.Summary(0, 0, 0, 0), false)
            }
            rows = built
            summary = sum
            liveCountsAvailable = liveOk
            snapshotAt = FeatureHealth.loadFinishedAt()
            running = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "run") {
            Column(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
                Button(
                    onClick = {
                        if (running) return@Button
                        runCheck()
                        scope.launch { showToastSuspend("检测完成") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Play_arrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "检测中…" else "开始检测")
                }
            }
        }

        summary?.let { s ->
            item(key = "summary") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GradeTile("正常", s.ok, DEFAULT_GRADE_COLORS.ok, Modifier.weight(1f))
                    GradeTile("异常", s.degraded, DEFAULT_GRADE_COLORS.warn, Modifier.weight(1f))
                    GradeTile("失败", s.failed, DEFAULT_GRADE_COLORS.error, Modifier.weight(1f))
                }
            }
            if (!liveCountsAvailable) {
                item(key = "no-live") {
                    HintCard(
                        "当前进程读不到挂钩调用数（设置页与微信主进程不同），" +
                                "因此「未被调用」一类判定已忽略，下面的结果只反映加载故障与适配点缺失。",
                    )
                }
            }
        }

        item(key = "env") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    SectionTitle("环境信息")
                    Spacer(Modifier.height(8.dp))
                    EnvRow("运行框架", lspEnv)
                    EnvRow("框架版本", lspApi)
                    EnvRow("微信版本", wechatVersion ?: "未检测到")
                    EnvRow("模块版本", formatLocalVersion())
                }
            }
        }

        if (snapshotAt > 0L) {
            item(key = "snapshot-time") {
                HintCard(
                    "结果基于 ${formatEpoch(snapshotAt, includeDate = true)} 的加载快照。" +
                            "微信刚启动时部分适配点尚未解析完，数字会偏高，稍后重新检测即可稳定。",
                )
            }
        }

        val current = rows
        if (current == null) {
            item(key = "hint") {
                HintCard("点上方「开始检测」查看各功能的挂钩状态")
            }
        } else if (current.isEmpty()) {
            item(key = "empty") { HintCard("没有可检测的功能，模块可能尚未加载完成") }
        } else {
            item(key = "hook-title") {
                Column(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp)) {
                    SectionTitle("功能挂钩状态")
                    Text(
                        text = "共 ${current.size} 项，问题项排在最前",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            items(items = current, key = { it.name }) { row ->
                HookRow(row)
            }
        }

        item(key = "self-check-bottom") { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// 显式导入 items 扩展，避免与上面的 Row 命名冲突时歧义。

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun GradeTile(label: String, count: Int, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = count.toString(),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
    }
}

@Composable
private fun EnvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HookRow(row: CheckRow) {
    val tint = tagTint(row, DEFAULT_GRADE_COLORS)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (row.grade) {
                    FeatureHealth.Grade.OK -> MaterialSymbols.Outlined.Check_circle
                    FeatureHealth.Grade.DEGRADED -> MaterialSymbols.Outlined.Warning
                    FeatureHealth.Grade.FAILED -> MaterialSymbols.Outlined.Error
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.detail,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tint)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = row.tag ?: gradeLabel(row.grade),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Report,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
