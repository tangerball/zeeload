package com.zeeload.carappcenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 车机端统一视觉语言：三块页面（应用中心 / 动态拦截 / 取得报告）共用同一套色板与组件，
 * 避免各页风格不一致。改这里一处，三个页面同时生效。
 */
object CarPalette {
    val bg = Color(0xFF0A0E13)
    val card = Color(0xFF141B24)
    val cardSoft = Color(0xFF161B22)
    val line = Color(0xFF232C38)
    val text = Color(0xFFE8EEF6)
    val textStrong = Color(0xFFF2F6FA)
    val dim = Color(0xFF8FA0B3)
    val dimmer = Color(0xFF56657A)
    val accent = Color(0xFF2EA043)
    val accentSoft = Color(0xFF3FB950)
    val info = Color(0xFF79C0FF)
    val warn = Color(0xFFD29922)
    val danger = Color(0xFFF85149)
    val mono = FontFamily.Monospace
}

enum class Tone { Neutral, Info, Ok, Warn, Danger }

fun Tone.color(): Color = when (this) {
    Tone.Neutral -> CarPalette.dim
    Tone.Info -> CarPalette.info
    Tone.Ok -> CarPalette.accentSoft
    Tone.Warn -> CarPalette.warn
    Tone.Danger -> CarPalette.danger
}

/** 页面标题区：大标题 + 一行说明，三个页面一致。 */
@Composable
fun PageHeader(title: String, subtitle: String = "", trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = CarPalette.textStrong, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = CarPalette.dim,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        trailing()
    }
}

/** 卡片容器：所有内容块都用它，圆角/描边/内边距统一。 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    title: String = "",
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CarPalette.card)
            .border(1.dp, CarPalette.line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title.isNotBlank()) {
            Column {
                Text(title, color = CarPalette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = CarPalette.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        content()
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CarPalette.accent,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF2B3442),
            disabledContentColor = Color(0xFF8494A8),
        ),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CarPalette.text),
    ) { Text(text, fontSize = 16.sp, maxLines = 1) }
}

@Composable
fun CarField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 15.sp) },
        singleLine = singleLine,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = CarPalette.text,
            fontSize = if (mono) 16.sp else 17.sp,
            fontFamily = if (mono) CarPalette.mono else FontFamily.Default,
        ),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CarPalette.bg,
            unfocusedContainerColor = CarPalette.bg,
            focusedTextColor = CarPalette.text,
            unfocusedTextColor = CarPalette.text,
            focusedLabelColor = CarPalette.info,
            unfocusedLabelColor = CarPalette.dim,
            focusedIndicatorColor = CarPalette.info,
            unfocusedIndicatorColor = CarPalette.line,
            cursorColor = CarPalette.info,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp),
    )
}

/** 小徽标：状态、来源、系统应用之类。 */
@Composable
fun Pill(text: String, tone: Tone = Tone.Neutral, mono: Boolean = false) {
    Text(
        text,
        color = tone.color(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = if (mono) CarPalette.mono else FontFamily.Default,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.color().copy(alpha = 0.12f))
            .border(1.dp, tone.color().copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** 键值行：左说明右值，用于详情卡。 */
@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = CarPalette.dim, fontSize = 15.sp)
        Text(
            value,
            color = CarPalette.text,
            fontSize = if (mono) 14.sp else 15.sp,
            fontFamily = if (mono) CarPalette.mono else FontFamily.Default,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** 底部状态条：三页共用的「当前在做什么」。 */
@Composable
fun StatusStrip(text: String, tone: Tone = Tone.Neutral, detail: String = "") {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CarPalette.card)
            .border(1.dp, CarPalette.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text, color = tone.color(), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (detail.isNotBlank()) {
            Text(detail, color = CarPalette.dimmer, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** 侧边导航项：选中态用绿色竖条 + 高亮文字，未选中保持安静。 */
@Composable
fun NavItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CarPalette.card else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) CarPalette.accent else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = if (selected) CarPalette.textStrong else CarPalette.dim,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
