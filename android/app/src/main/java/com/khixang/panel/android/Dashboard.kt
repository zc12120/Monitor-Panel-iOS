package com.khixang.panel.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonNull

@Composable fun EmptyState(title: String, detail: String = "") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Dns, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(t(title), style = MaterialTheme.typography.titleMedium)
        if(detail.isNotEmpty()) Hint(detail)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Dashboard(state: PanelState, refresh: () -> Unit, open: (J) -> Unit) {
    var search by rememberSaveable(state.selected) { mutableStateOf("") }
    var onlineOnly by rememberSaveable(state.selected) { mutableStateOf(false) }
    val nodes = state.preferences.visible(state.nodes).filter { n ->
        (!onlineOnly || state.statuses[n["uuid"].str]["online"].bool) && (search.isEmpty() || listOf("name", "group", "tags", "ipv4").any { n[it].str.contains(search,true) })
    }
    val online = state.nodes.count { state.statuses[it["uuid"].str]["online"].bool }
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = refresh) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text(t("搜索节点名、分组或 IP")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if(search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, t("清除搜索")) } }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.panel?.name ?: "Monitor Panel", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).background(if(state.fresh) OnlineGreen else OfflineOrange, CircleShape))
                            Text(t(if(state.fresh) "每 5 秒刷新 · 运行正常" else "等待连接或未就绪"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FilledTonalIconButton(refresh, enabled = !state.loading, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)) { Icon(Icons.Outlined.Refresh, t("刷新")) }
                }
            }
            if(state.nodes.isNotEmpty()) item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("全部",state.nodes.size,MaterialTheme.colorScheme.onSurface),Triple("在线",online,OnlineGreen),Triple("离线",state.nodes.size-online,OfflineOrange)).forEach { (name,count,color) ->
                            Text(t(name), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("$count", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
                        }
                        Spacer(Modifier.weight(1f))
                        Column {
                            Text("↑ " + bytes(state.nodes.sumOf { (state.statuses[it["uuid"].str]["net_out"].num ?: 0.0).coerceAtLeast(0.0) }) + "/s", fontSize = 11.sp, color = OnlineGreen)
                            Text("↓ " + bytes(state.nodes.sumOf { (state.statuses[it["uuid"].str]["net_in"].num ?: 0.0).coerceAtLeast(0.0) }) + "/s", fontSize = 11.sp, color = AppleBlue)
                        }
                    }
                }
            }
            item { TextButton(onClick = { onlineOnly = !onlineOnly }) { Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t(if(onlineOnly) "仅看在线 (%lld)" else "全部节点 (%lld)", if(onlineOnly) online else state.nodes.size)) } }
            state.error?.let { item { ErrorText(it) } }
            if(nodes.isEmpty() && !state.loading) item { EmptyState(if(state.panels.isEmpty()) "添加你的第一个面板" else "暂无匹配节点", if(state.nodes.isEmpty()) "在「面板」页选择后端并输入面板地址与凭据。" else "试试清除搜索、切换在线筛选，或通过右上角「隐藏节点」恢复显示。") }
            items(nodes, key = { it["uuid"].str }) { node -> NodeCard(node, state.statuses[node["uuid"].str], state.fresh, { open(node) }) }
        }
    }
}

@Composable fun Identity(node: J) {
    val code = listOf("region", "country_code", "country").mapNotNull { countryCode(node[it].str) }.firstOrNull()
    val flag = IdentityResources.flags[code] ?: 0
    val osName = osAsset(node)
    val os = IdentityResources.operatingSystems[osName] ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if(flag != 0) Image(painterResource(flag), code, Modifier.size(24.dp,18.dp).border(.5.dp,MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))) else Icon(Icons.Outlined.Language, t("未知地区"), Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if(os != 0) Image(painterResource(os), osName?.removePrefix("os_"), Modifier.size(21.dp)) else Icon(Icons.Outlined.Dns, t("未知操作系统"), Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun NodeHeader(node: J, status: J, fresh: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        val color = if(!fresh) MaterialTheme.colorScheme.onSurfaceVariant else if(status["online"].bool) OnlineGreen else OfflineOrange
        Box(Modifier.size(14.dp).background(color.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).background(color, CircleShape)) }
        Row(Modifier.weight(1f),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(display(node["name"]), Modifier.weight(1f, fill = false), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if(node["group"].str.isNotEmpty()) Text(node["group"].str, Modifier.widthIn(max = 90.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Identity(node)
    }
}
@Composable fun Meter(fraction: Double?, tint: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
        if(fraction != null) Box(Modifier.width(maxWidth * fraction.coerceIn(0.0,1.0).toFloat()).fillMaxHeight().background(tint, CircleShape))
    }
}
@Composable fun Gauge(name: String, number: String, detail: String, fraction: Double?, color: Color, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.height(18.dp),verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(when(name) { "CPU" -> Icons.Outlined.Memory; "内存" -> Icons.Outlined.DeveloperBoard; "磁盘" -> Icons.Outlined.Storage; else -> Icons.Outlined.SwapVert },null,Modifier.size(12.dp),tint = color)
            Text(t(name),fontSize = if(large) 12.sp else 11.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(number, modifier = Modifier.height(if(large) 34.dp else 24.dp), fontSize = if(large) 24.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Meter(fraction, color)
        if(detail.isNotEmpty()) Text(detail,fontSize = 9.sp,color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable fun Quality(summary: PingSummary, loss: Boolean, modifier: Modifier = Modifier) {
    val number = if(loss) summary.loss?.let { "%.0f%%".format(it) } ?: "—" else summary.latency?.let { "%.0f ms".format(it) } ?: if(summary.allTimedOut) t("超时") else "—"
    val tint = if(summary.allTimedOut || (summary.latency ?: 0.0) > 150) LossRed else if((summary.latency ?: 0.0) > 80) OfflineOrange else OnlineGreen
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(t(if(loss) "丢包" else "延迟") + " " + number, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(8) { index -> Box(Modifier.weight(1f).height(8.dp).background(if(summary.allTimedOut || index >= 8 - kotlin.math.ceil((summary.loss ?: 0.0) / 100 * 8)) LossRed else if(summary.latency != null) tint else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))) }
        }
    }
}
@Composable fun NodeCard(node: J, status: J, fresh: Boolean, open: () -> Unit) {
    val m = if(fresh) status else JsonNull
    val ram = status["ram_total"].num ?: node["mem_total"].num
    val disk = status["disk_total"].num ?: node["disk_total"].num
    val traffic = trafficUsed(node,m)
    Surface(onClick = open, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NodeHeader(node,status,fresh)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gauge("CPU",percent(m["cpu"].num), listOf("load1","load5","load15").joinToString(", ") { key -> (if(key == "load1") m[key].num ?: m["load"].num else m[key].num)?.let { "%.2f".format(it) } ?: "—" },m["cpu"].num?.div(100),OnlineGreen,Modifier.weight(1f))
                Gauge("内存",percent(ratio(m["ram"].num,ram)?.times(100)),bytes(m["ram"].num)+" / "+bytes(ram),ratio(m["ram"].num,ram),MemoryTeal,Modifier.weight(1f))
                Gauge("磁盘",percent(ratio(m["disk"].num,disk)?.times(100)),bytes(m["disk"].num)+" / "+bytes(disk),ratio(m["disk"].num,disk),OfflineOrange,Modifier.weight(1f))
                Gauge("流量",bytes(traffic),if(m["traffic_unlimited"].bool) t("不限额") else bytes(traffic)+" / "+bytes(node["traffic_limit"].num),ratio(traffic,node["traffic_limit"].num),AppleBlue,Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Text("↑ "+speed(m["net_out"].num),fontSize = 10.sp,color = OnlineGreen); Text("↓ "+speed(m["net_in"].num),fontSize = 10.sp,color = AppleBlue) }
                Column(Modifier.weight(1f)) { Text("⊙ "+bytes(m["net_total_up"].num),fontSize = 10.sp,color = MaterialTheme.colorScheme.onSurfaceVariant); Text("⊙ "+bytes(m["net_total_down"].num),fontSize = 10.sp,color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Quality(PingSummary.from(m["ping"]),false,Modifier.weight(1f)); Quality(PingSummary.from(m["ping"]),true,Modifier.weight(1f))
            }
        }
    }
}
