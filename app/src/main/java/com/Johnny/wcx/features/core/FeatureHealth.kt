package com.Johnny.wcx.features.core

import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 功能健康状况记录器。
 *
 * 解决的问题：功能**静默不生效**时，用户和开发者都无从判断原因。
 * 典型场景是 [FeaturesLoader] 里的
 * `skipping <name> — incomplete cache, awaiting re-resolution` ——
 * 功能被跳过、日志里有但看不出来、表现为「设置保存了但不生效」。
 *
 * 记录的是 **[BaseFeature.isActive] 之外的信息** —— 即「这个功能为什么没生效」，
 * 而不是「它现在有没有生效」。后者可以直接读 [BaseFeature.isActive]。
 *
 * 注意本对象只做记录，不做任何修复；修复是 [FeaturesLoader] 与 DexResolver 的事。
 */
object FeatureHealth {

    private const val TAG = "FeatureHealth"

    /** 一个功能在本次启动中的加载结局。 */
    enum class Status {
        /** 正常启动，是否真的挂上 hook 另看 [BaseFeature.isActive]。 */
        LOADED,

        /** 用户没开这个功能。属正常状态，不是故障。 */
        DISABLED,

        /**
         * 该功能在当前进程不该加载（例如只跑主进程的功能遇到了 :push 进程）。
         * 属正常状态。
         */
        SKIPPED_PROCESS,

        /**
         * DEX 缓存不完整，本次启动被跳过，等 DexKit 重新解析后下次启动生效。
         * **这是「设置保存了但不生效」最常见的原因。**
         */
        SKIPPED_INCOMPLETE_CACHE,

        /** 缓存文件损坏或缺失，已加入重解析队列。 */
        SKIPPED_CACHE_FAILED,

        /** 启动过程中抛异常。 */
        FAILED,
    }

    /** 单个功能的健康记录。 */
    data class Entry(
        val name: String,
        val displayName: String,
        val categories: List<String>,
        val status: Status,
        val detail: String? = null,
    ) {
        val isProblem: Boolean
            get() = status == Status.SKIPPED_INCOMPLETE_CACHE ||
                    status == Status.SKIPPED_CACHE_FAILED ||
                    status == Status.FAILED
    }

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    private var loadFinishedAt: Long = 0L

    /**
     * 本次进程内 [publish] 被调用的次数。
     *
     * 实测一次微信启动中会加载多次（每次幂等，不影响功能），
     * 但「最后一次未必最有代表性」，记录次数便于排查「为何清单不稳定」。
     */
    @Volatile
    private var loadCount: Int = 0

    /** 本次启动的功能健康快照。 */
    fun snapshot(): List<Entry> = entries

    /** 本次进程内加载次数。 */
    fun loadCount(): Int = loadCount

    /** 加载完成的时间戳，0 表示尚未完成加载。 */
    fun loadFinishedAt(): Long = loadFinishedAt

    /** 是否有任何功能处于故障状态。 */
    fun hasProblems(): Boolean = entries.any { it.isProblem }

    fun problemEntries(): List<Entry> = entries.filter { it.isProblem }

    /**
     * 由 [FeaturesLoader] 在加载结束后一次性写入。
     *
     * 之所以整批写入而不是逐个上报，是因为加载顺序与 DEX 修复是异步交错的，
     * 整批写入能保证快照反映的是「本轮加载的最终结论」。
     */
    fun publish(list: List<Entry>) {
        entries = list
        loadFinishedAt = System.currentTimeMillis()
        loadCount += 1

        val problems = list.filter { it.isProblem }
        if (problems.isEmpty()) {
            WeLogger.i(TAG, "健康检查: ${list.size} 个功能全部正常")
        } else {
            WeLogger.w(
                TAG,
                "健康检查: ${list.size} 个功能中 ${problems.size} 个未生效 —— " +
                        problems.joinToString("; ") { "${it.name}(${it.status})" }
            )
        }
    }

    /** 供诊断页复制/导出的一段纯文本报告。 */
    fun buildReport(): String {
        val sb = StringBuilder()
        val time = if (loadFinishedAt == 0L) "未完成加载"
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(loadFinishedAt))

        sb.appendLine("WCXLC 兼容性诊断")
        sb.appendLine("加载完成时间: $time")
        sb.appendLine("本次进程内加载次数: $loadCount")
        sb.appendLine("进程: ${if (TargetProcesses.isInMain) "主进程" else "非主进程"}")
        sb.appendLine()

        val list = entries
        if (list.isEmpty()) {
            sb.appendLine("（无记录 —— 模块可能尚未加载完成）")
            return sb.toString().trimEnd()
        }

        val problems = list.filter { it.isProblem }
        val okCount = list.count { it.status == Status.LOADED }
        val offCount = list.count {
            it.status == Status.DISABLED || it.status == Status.SKIPPED_PROCESS
        }
        sb.appendLine("汇总: 正常 $okCount / 未生效 ${problems.size} / 未启用 $offCount / 共 ${list.size}")
        sb.appendLine()

        if (problems.isNotEmpty()) {
            sb.appendLine("── 未生效 ──")
            problems.forEach { sb.appendLine("• ${it.name} [${it.status}]${it.detail?.let { d -> " $d" } ?: ""}") }
            sb.appendLine()
        }

        sb.appendLine("── 全部功能 ──")
        list.sortedWith(compareBy({ !it.isProblem }, { it.name })).forEach {
            sb.appendLine("${if (it.isProblem) "✗" else "✓"} ${it.name} — ${it.status}")
        }
        return sb.toString().trimEnd()
    }
}
