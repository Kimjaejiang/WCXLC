package com.Johnny.wcx.ui.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * 与 WeKit 同名 ListItem（其依赖特定 material3 版本签名的 MaterialListItem，
 * 本模块不连带其签名，改为手写等价组合）。
 */
@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    headlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    onClick: (() -> Unit)? = null,
    checked: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    shapes: androidx.compose.material3.ListItemShapes? = null,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
            .clickable(enabled = enabled, onClick = onClick ?: {}),
        verticalAlignment = verticalAlignment,
    ) {
        /*
         * 本替身原先完全忽略 colors：既不提供内容色，Material 3 的 Text/Icon 就只能落到 M3
         * LocalContentColor 的默认值 Color.Black —— 明暗两种模式下文字图标都是纯黑，看起来像
         * 「下级行没跟随上级做反色」。这里按 M3 ListItem 的语义补上各槽位内容色：
         * 标题=contentColor、摘要=supportingContentColor、前后图标=各自的 icon 色。
         * 底色仍不主动绘制（保持透明，直接露出外层 miuix 卡片），避免在 miuix 页面出现色块。
         */
        CompositionLocalProvider(LocalContentColor provides colors.leadingContentColor) {
            leadingContent?.invoke()
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
                overlineContent?.invoke()
                if (headlineContent != null) {
                    headlineContent()
                } else {
                    content?.invoke()
                }
            }
            CompositionLocalProvider(LocalContentColor provides colors.supportingContentColor) {
                supportingContent?.invoke()
            }
        }
        CompositionLocalProvider(LocalContentColor provides colors.trailingContentColor) {
            trailingContent?.invoke()
        }
    }
}
