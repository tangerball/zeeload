package com.zeeload.carappcenter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 车机顶栏返回条：很多车机没有系统返回键/手势，所以每个二级页面都自己给一个「← 返回」。
 */
@Composable
fun BackBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton("← 返回", Modifier.widthIn(min = 132.dp)) { onBack() }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, color = CarPalette.textStrong, fontSize = 26.sp)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = CarPalette.dim, fontSize = 13.sp)
            }
        }
    }
}
