package com.zeeload.carappcenter.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 大屏（HD）适配：按可用宽度分三档，给出侧栏宽度、页边距、卡片间距、内容最大宽度、
 * 以及「主区域能放几列」。
 *
 * 车机分辨率差异极大（1280×720 常见 dp 画布 1280×720；1920×1080@160dpi 则是 1920×1080 dp），
 * 固定 dp 宽度在小画布合适、在大画布上会显得又窄又挤，所以统一走这份规格。
 */
enum class SizeClass { Compact, Medium, Expanded }

data class LayoutSpec(
    val sizeClass: SizeClass,
    val sidebarWidth: Dp,
    val pagePadding: Dp,
    val gap: Dp,
    /** 内容最大宽度；超过就居中，避免 2560 宽屏上文本拉成一条线 */
    val contentMaxWidth: Dp,
    /** 主区域卡片列数：1（紧凑）/ 2（常规）/ 3（HD） */
    val columns: Int,
    /** 大屏上额外放大一点的字号系数（与用户字号档位相乘） */
    val fontBoost: Float,
) {
    val isExpanded: Boolean get() = sizeClass == SizeClass.Expanded
}

fun layoutFor(width: Dp, height: Dp = 720.dp): LayoutSpec = when {
    width >= 1500.dp -> LayoutSpec(
        sizeClass = SizeClass.Expanded,
        sidebarWidth = 380.dp,
        pagePadding = 34.dp,
        gap = 24.dp,
        contentMaxWidth = 1680.dp,
        columns = 3,
        fontBoost = 1.18f,
    )

    width >= 980.dp -> LayoutSpec(
        sizeClass = SizeClass.Medium,
        sidebarWidth = 296.dp,
        pagePadding = 22.dp,
        gap = 16.dp,
        contentMaxWidth = 1400.dp,
        columns = 2,
        fontBoost = 1.0f,
    )

    else -> LayoutSpec(
        sizeClass = SizeClass.Compact,
        sidebarWidth = 240.dp,
        pagePadding = 14.dp,
        gap = 12.dp,
        contentMaxWidth = 900.dp,
        columns = 1,
        fontBoost = 1.0f,
    )
}
