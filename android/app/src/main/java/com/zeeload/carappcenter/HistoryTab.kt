package com.zeeload.carappcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeload.carappcenter.ui.Card
import com.zeeload.carappcenter.ui.CarPalette
import com.zeeload.carappcenter.ui.PageHeader
import com.zeeload.carappcenter.ui.Pill
import com.zeeload.carappcenter.ui.Tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 安装历史：只留一行一条（包名 / vc / 通道 / 结果），够查问题就行。 */
@Composable
fun ColumnScope.HistoryScreen(records: List<InstallRecord>) {
    PageHeader(
        title = "安装历史",
        subtitle = "按地址安装（via=direct）· 商店安装（via=market），最近 200 条",
    ) { Pill("${records.size} 条记录", Tone.Info) }
    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))

    Card(title = "记录", subtitle = if (records.isEmpty()) "还没有记录" else "按时间倒序") {
        LazyColumn(Modifier.fillMaxWidth()) {
            items(records, key = { it.id }) { record ->
                val market = record.result.contains("via=market")
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                record.packageName,
                                color = CarPalette.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Pill("vc=${record.versionCode}", Tone.Neutral)
                            Pill(if (market) "via=market" else "via=direct", if (market) Tone.Ok else Tone.Info)
                        }
                        Text(
                            SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(record.at)) +
                                " · " + record.result,
                            color = CarPalette.dim,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(
                    Modifier.fillMaxWidth().height(1.dp).background(CarPalette.line)
                )
            }
        }
    }
}
