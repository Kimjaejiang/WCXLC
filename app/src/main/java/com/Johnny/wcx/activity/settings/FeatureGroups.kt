package com.Johnny.wcx.activity.settings

import com.Johnny.wcx.features.core.BaseFeature

/**
 * 密友功能等分类页内的**展示分组**。
 *
 * ## 为什么在 UI 层分组，而不是给 `@Feature` 加字段
 *
 * 「密友功能」分类下平铺着 28 个开关，其中不少是同一件事的不同侧面
 * （例如「三击/多击/长按标题」都是临时显示，「朋友圈隐藏」与「朋友圈互动隐藏」
 * 是包含关系）。平铺的列表既看不出层次，也让用户以为每个开关都同等独立。
 *
 * 分组信息纯粹是**展示顺序**，不影响任何启用逻辑，也不参与运行时判断，
 * 所以放在 UI 层维护即可；若加进 `@Feature`，需要改注解处理器、
 * 重新生成 `FeaturesProvider`，代价与收益不成比例。
 *
 * ## 维护约定
 *
 * - 键是功能的 `name`（即 `@Feature(name = ...)`，也是开关的 WePrefs 键）。
 * - **未列出的功能归入「其他」**，排在最后——新增功能忘记登记不会消失，
 *   只是暂时落在末尾。
 * - [GROUP_ORDER] 决定分组先后；组内保持 `FeaturesProvider` 的原有顺序
 *   （该顺序已按 SwitchFeature → ClickableFeature → 其他 排好，稳定可预期）。
 */
internal object FeatureGroups {

    /** 分组标题，按期望的展示顺序排列。 */
    private val GROUP_ORDER = listOf(
        "名单与总控",
        "隐藏：会话与通讯录",
        "隐藏：朋友圈",
        "隐藏：其他界面",
        "显示与恢复",
        "提醒与提示",
    )

    /**
     * 功能名 → 分组标题。
     *
     * 分组依据是「用户想做什么」，不是「代码怎么实现」：
     * - 名单与总控：决定「谁被隐藏」以及全局生效与否；
     * - 隐藏：会话与通讯录：最常用的隐藏面；
     * - 隐藏：朋友圈：动态与互动；
     * - 隐藏：其他界面：搜索、发现页、存储空间等零散入口；
     * - 显示与恢复：所有「临时显示」的触发方式（互相替代，故归一组）；
     * - 提醒与提示：通知/震动/角标/Toast 等反馈类。
     */
    private val GROUP_OF: Map<String, String> = mapOf(
        // ── 名单与总控 ──
        "密友名单管理" to "名单与总控",
        "隐藏联系人" to "名单与总控",
        "会话列表长按添加" to "名单与总控",
        "通讯录长按添加" to "名单与总控",

        // ── 隐藏：会话与通讯录 ──
        "主页会话隐藏" to "隐藏：会话与通讯录",
        "通讯录隐藏" to "隐藏：会话与通讯录",
        "标签内隐藏" to "隐藏：会话与通讯录",
        "禁止进入聊天" to "隐藏：会话与通讯录",
        "禁止查看资料" to "隐藏：会话与通讯录",
        "锁屏隐藏" to "隐藏：会话与通讯录",

        // ── 隐藏：朋友圈 ──
        "朋友圈隐藏" to "隐藏：朋友圈",
        "朋友圈互动隐藏" to "隐藏：朋友圈",
        "隐藏我的朋友圈" to "隐藏：朋友圈",
        "已隐藏朋友圈管理" to "隐藏：朋友圈",
        "分组图标隐藏" to "隐藏：朋友圈",

        // ── 隐藏：其他界面 ──
        "主页搜索隐藏" to "隐藏：其他界面",
        "发现页朋友圈入口隐藏" to "隐藏：其他界面",
        "发现页入口隐藏" to "隐藏：其他界面",
        "状态页隐藏" to "隐藏：其他界面",
        "隐藏最近转发" to "隐藏：其他界面",
        "存储空间聊天记录隐藏" to "隐藏：其他界面",
        "存储空间缓存隐藏" to "隐藏：其他界面",

        // ── 显示与恢复 ──
        "多击标题解除" to "显示与恢复",
        "长按标题解除" to "显示与恢复",
        "临时解除指令" to "显示与恢复",
        "离开对话/离开微信隐藏" to "显示与恢复",

        // ── 提醒与提示 ──
        "密友消息通知隐藏" to "提醒与提示",
        "密友消息震动" to "提醒与提示",
        "微信团队提醒" to "提醒与提示",
        "底栏字体加粗" to "提醒与提示",
        "圆点提示" to "提醒与提示",
        "操作提示" to "提醒与提示",
        "菜单显示文字" to "提醒与提示",
        "拦截扫码登录" to "提醒与提示",
    )

    /** 未登记功能所属的兜底分组。 */
    private const val FALLBACK_GROUP = "其他"

    /**
     * 把功能列表按 [GROUP_OF] 分段。
     *
     * 返回「组标题 → 该组功能」的有序列表；组标题为 `null` 表示该段不需要标题
     * （目前用于兜底：若映射表为空或全部未命中，退回平铺渲染）。
     *
     * 空组不会出现在结果里，因此分类页只显示实际有内容的分组。
     */
    fun group(items: List<BaseFeature>): List<Pair<String?, List<BaseFeature>>> {
        if (items.isEmpty()) return emptyList()

        val byGroup = items.groupBy { GROUP_OF[it.name] ?: FALLBACK_GROUP }

        val ordered = buildList {
            for (title in GROUP_ORDER) {
                byGroup[title]?.let { add(title to it) }
            }
            // 未登记的收在最后，仍然可见
            byGroup[FALLBACK_GROUP]?.let { add(FALLBACK_GROUP to it) }
        }

        // 完全没有命中映射（例如新分类尚未登记）时退回单段无标题列表，
        // 避免出现一个只有「其他」的分组标题
        if (ordered.isEmpty() || (ordered.size == 1 && ordered[0].first == FALLBACK_GROUP)) {
            return listOf(null to items)
        }
        return ordered
    }
}
