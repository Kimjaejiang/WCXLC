package com.Johnny.wcx.utils.strings

import com.Johnny.wcx.constants.Preferences


private val MAP_REGEX = Regex("\\[[^]]+]")

private val RICH_CONTENT_MAP = mapOf(
    // 键必须与微信通知正文里的占位符完全一致（含方括号）；未命中的标签会被原样
    // 输出，通知栏就会出现一对方括号文字。取值一律用图案，不要再映射成 "[xxx]"。
    "[图片]" to "\uD83D\uDDBC\uFE0F",
    "[视频]" to "\uD83C\uDFA5",
    "[小视频]" to "\uD83C\uDFAC",
    "[文件]" to "\uD83D\uDCC1",
    "[语音]" to "\uD83D\uDDE3\uFE0F",
    "[位置]" to "\uD83D\uDDFA\uFE0F",
    "[红包]" to "\uD83E\uDDE7",
    "[转账]" to "\uD83D\uDCB5",
    "[链接]" to "\uD83D\uDD17",
    "[名片]" to "\uD83D\uDC64",
    "[音乐]" to "\uD83C\uDFB5",
    "[视频号视频]" to "\uD83D\uDCF9",
    // 贴纸类消息的占位符（type=47 表情、1048625 搜狗表情）：真实图案是 wxgf/emoji
    // 资源，通知栏无法渲染，统一用做鬼脸图案代替。
    "[表情]" to "\uD83E\uDD2A",
    "[动画表情]" to "\uD83E\uDD2A",
    "[贴纸表情]" to "\uD83E\uDD2A",
    "[搜狗表情]" to "\uD83E\uDD2A",
)

fun String.replaceRichContent(): String {
    return MAP_REGEX.replace(this) { matchResult ->
        RICH_CONTENT_MAP[matchResult.value] ?: matchResult.value
    }
}

private val EMOJI_MAP = mapOf(
    "[微笑]" to "🙂",
    "[撇嘴]" to "😕",
    "[色]" to "😍",
    "[发呆]" to "😳",
    "[得意]" to "😎",
    "[流泪]" to "😭",
    "[害羞]" to "😊",
    "[闭嘴]" to "🤐",
    "[睡]" to "😴",
    "[大哭]" to "😫",
    "[尴尬]" to "😅",
    "[发怒]" to "😡",
    "[调皮]" to "😜",
    "[呲牙]" to "😁",
    "[惊讶]" to "😱",
    "[难过]" to "🙁",
    "[囧]" to "😨",
    "[抓狂]" to "😫",
    "[吐]" to "🤮",
    "[偷笑]" to "🤭",
    "[愉快]" to "😊",
    "[白眼]" to "🙄",
    "[傲慢]" to "😏",
    "[困]" to "🥱",
    "[惊恐]" to "😨",
    "[憨笑]" to "😃",
    "[悠闲]" to "☕",
    "[咒骂]" to "🤬",
    "[疑问]" to "❓",
    "[嘘]" to "🤫",
    "[晕]" to "😵",
    "[衰]" to "☹️",
    "[骷髅]" to "💀",
    "[敲打]" to "🔨",
    "[再见]" to "👋",
    "[擦汗]" to "😓",
    "[抠鼻]" to "👃",
    "[鼓掌]" to "👏",
    "[坏笑]" to "😏",
    "[右哼哼]" to "😒",
    "[鄙视]" to "🙄",
    "[委屈]" to "🥺",
    "[快哭了]" to "😭",
    "[阴险]" to "😈",
    "[亲亲]" to "😘",
    "[可怜]" to "🥺",
    "[笑脸]" to "😄",
    "[生病]" to "😷",
    "[脸红]" to "😳",
    "[破涕为笑]" to "😂",
    "[恐惧]" to "😨",
    "[失望]" to "😞",
    "[无语]" to "😶",
    "[嘿哈]" to "🕺",
    "[捂脸]" to "🤦",
    "[奸笑]" to "😏",
    "[机智]" to "😏",
    "[皱眉]" to "😟",
    "[耶]" to "✌️",
    "[吃瓜]" to "🍉",
    "[加油]" to "💪",
    "[汗]" to "😓",
    "[天啊]" to "😱",
    "[Emm]" to "🤔",
    "[社会社会]" to "🤝",
    "[旺柴]" to "\uD83D\uDC36",
    "[好的]" to "👌",
    "[打脸]" to "🖐️",
    "[哇]" to "🤩",
    "[翻白眼]" to "🙄",
    "[666]" to "🤙",
    "[让我看看]" to "🫣",
    "[叹气]" to "😮‍💨",
    "[苦涩]" to "😭",
    "[嘴唇]" to "👄",
    "[爱心]" to "❤️",
    "[心碎]" to "💔",
    "[拥抱]" to "🤗",
    "[强]" to "👍",
    "[弱]" to "👎",
    "[握手]" to "🤝",
    "[胜利]" to "✌️",
    "[抱拳]" to "🙏",
    "[勾引]" to "☝️",
    "[拳头]" to "👊",
    "[OK]" to "👌",
    "[合十]" to "🙏",
    "[啤酒]" to "🍺",
    "[咖啡]" to "☕",
    "[蛋糕]" to "🎂",
    "[玫瑰]" to "🌹",
    "[凋谢]" to "🥀",
    "[菜刀]" to "🔪",
    "[炸弹]" to "💣",
    "[便便]" to "💩",
    "[月亮]" to "🌙",
    "[太阳]" to "☀️",
    "[庆祝]" to "🎉",
    "[礼物]" to "🎁",
    "[红包]" to "🧧",
    "[發]" to "🀅",
    "[福]" to "🧧",
    "[烟花]" to "🎆",
    "[爆竹]" to "🧨",
    "[猪头]" to "🐷",
    "[跳跳]" to "💃",
    "[发抖]" to "🫨",
    "[转圈]" to "🌀",

    // ── 输入法 emoji（非微信表情）────────────────────────────────────
    //
    // 微信会把昵称/正文里的 emoji 也转成 `[名称]`，名字取自媒体 emoji 的官方中文名，
    // 而不是微信表情包。这张表只能按实测补充：微信的名表既不在 dex（UTF-8 精确字节
    // 搜索 0 命中）也不在 files/public/emoji 的 xml 里，无法离线取得权威全表。
    //
    // 实测来源：群名「18🈲🐭」在通知里显示为「18[表情][老鼠]」——
    // `[表情]` 是微信对**无法命名**的 emoji 的通用兜底（见 RICH_CONTENT_MAP），
    // `[老鼠]` 则是识别出的名字。故：命中不到名字的会退化成 `[表情]`，
    // 补齐后即可正确显示。
    "[老鼠]" to "🐭",
    "[禁止]" to "🚫",
    "[一百]" to "💯",
    "[火]" to "🔥",
    "[中指]" to "🖕",
    "[眼睛]" to "👀",
    "[幽灵]" to "👻",
    "[外星人]" to "👽",
    "[机器人]" to "🤖",
    "[猫]" to "🐱",
    "[狗]" to "🐶",
    "[猪]" to "🐷",
    "[猴]" to "🐵",
    "[鸡]" to "🐔",
    "[蛇]" to "🐍",
    "[鱼]" to "🐟",
    "[花]" to "🌸",
    "[草]" to "🌿",
    "[树]" to "🌳",
    "[星星]" to "⭐",
    "[心]" to "❤️",
    "[闪电]" to "⚡",
    "[雪]" to "❄️",
    "[雨]" to "🌧️",
    "[云]" to "☁️",
    "[时钟]" to "🕐",
    "[钱]" to "💰",
    "[锤子]" to "🔨",
    "[钥匙]" to "🔑",
    "[锁]" to "🔒",
    "[铅笔]" to "✏️"
)

fun String.replaceEmojis(): String {
    return MAP_REGEX.replace(this) { matchResult ->
        EMOJI_MAP[matchResult.value] ?: matchResult.value
    }
}

private val WXID_PREFIX_REGEX = Regex("""^wxid_[^:]+:\n(.*)$""", setOf(RegexOption.DOT_MATCHES_ALL))
private val GENERIC_PREFIX_REGEX = Regex("""^[^:]+:\n(.*)$""", setOf(RegexOption.DOT_MATCHES_ALL))

fun String.stripWxId(): String {
    val regex = if (Preferences.matchGenericWxIdExp) GENERIC_PREFIX_REGEX else WXID_PREFIX_REGEX
    val match = regex.find(this)
    return match?.groupValues?.get(1) ?: this
}

// =========================================================================
// 安全文本截取工具函数 — 杜绝越界崩溃
// =========================================================================

/**
 * 安全截取字符串子串，自动进行边界校验。
 * 若 [start] 或 [end] 超出字符串长度，自动修正为合法范围，不会抛出异常。
 * @return 截取后的子串，若 start >= length 则返回空字符串
 */
fun String.safeSubstring(start: Int, end: Int = length): String {
    if (start >= length) return ""
    val safeEnd = end.coerceAtMost(length)
    if (safeEnd <= start) return ""
    return substring(start, safeEnd)
}

/**
 * 安全截取字符串子串，若 [start] 超出范围则返回空字符串。
 */
fun String.safeSubstring(range: IntRange): String {
    return safeSubstring(range.first, range.last + 1)
}
