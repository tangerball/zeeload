package com.zeeload.carappcenter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * 车机字号档位：车机屏幕远、观看距离长，标准手机字号在车上会「很小」。
 * 这里用 Density.fontScale 统一放大所有 sp 文字（组件不用逐个改），
 * 同时把按钮最小高度、卡片内边距按同一比例放大，避免「字大了、按钮还是小」。
 */
enum class UiScale(val value: Float, val label: String, val hint: String) {
    Standard(1.00f, "标准", "与手机一致，适合近距离看"),
    Large(1.15f, "大", "车机默认推荐"),
    Larger(1.30f, "特大", "远距离/大屏车机"),
    Huge(1.45f, "超大", "后排或强光下"),
    ;

    companion object {
        fun of(value: Float): UiScale =
            entries.minByOrNull { kotlin.math.abs(it.value - value) } ?: Large
    }
}

val LocalCarScale = staticCompositionLocalOf { UiScale.Large }

/** 三页统一入口：套主题 + 按档位放大字号。 */
@Composable
fun CarTheme(scale: UiScale = UiScale.Large, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalCarScale provides scale,
        LocalDensity provides Density(density.density, density.fontScale * scale.value),
    ) {
        MaterialTheme(colorScheme = darkColorScheme(background = CarPalette.bg, surface = CarPalette.card)) {
            Surface(Modifier.fillMaxSize(), color = CarPalette.bg) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

/** 按档位放大的尺寸（按钮高度、卡片内边距、间距）。 */
@Composable
fun carDp(base: Int): Int {
    val scale = LocalCarScale.current.value
    return (base * (0.85f + 0.15f * scale)).toInt()
}

@Composable
fun carPadding(base: Int) = carDp(base).dp
