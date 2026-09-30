package com.khixang.panel.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull

@Composable fun NodeDetail(state: PanelState, node: J, api: Api?) {
    val status = state.statuses[node["uuid"].str]; val m = if(state.fresh) status else JsonNull
    var hours by rememberSaveable(node["uuid"].str) { mutableIntStateOf(6) }; var field by rememberSaveable(node["uuid"].str) { mutableStateOf("cpu") }
    var records by remember { mutableStateOf<List<J>>(emptyList()) }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(api,node["uuid"].str,hours,field,refresh) {
        busy = true; error = null; records = emptyList()
        try { records = api?.history(node["uuid"].str,field,hours).orEmpty() }
        catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) }
        finally { busy = false }
    }
    val ram = status["ram_total"].num ?: node["mem_total"].num
    val disk = status["disk_total"].num ?: node["disk_total"].num
    val traffic = trafficUsed(node,m)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard {
            NodeHeader(node,status,state.fresh)
            if(node["cpu_name"].str.isNotEmpty()) Inset { Text(node["cpu_name"].str,fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Inset(Modifier.weight(1f)) { Hint(display(node["os"])) }; Inset(Modifier.weight(1f)) { Hint(display(node["arch"])) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Inset(Modifier.weight(1f)) { Text(t("负载")+"  "+(m["load1"].num ?: m["load"].num)?.let { "%.2f".format(it) }.orEmpty().ifEmpty { "—" },fontSize = 11.sp) }
                Inset(Modifier.weight(1f)) { Text(t("规格")+"  "+bytes(ram)+" · "+bytes(disk),fontSize = 11.sp) }
            }
            Inset {
                Hint("流量配额"); Meter(ratio(traffic,node["traffic_limit"].num),AppleBlue)
                Text(bytes(traffic)+" / "+if(m["traffic_unlimited"].bool) t("不限额") else bytes(node["traffic_limit"].num),fontSize = 11.sp)
                Text(t("上行")+" "+bytes(m["net_total_up"].num)+" · "+t("下行")+" "+bytes(m["net_total_down"].num),fontSize = 11.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Inset(Modifier.weight(1f)) { Gauge("CPU",percent(m["cpu"].num),"",m["cpu"].num?.div(100),OnlineGreen,large = true) }
                Inset(Modifier.weight(1f)) { Gauge("内存",percent(ratio(m["ram"].num,ram)?.times(100)),bytes(m["ram"].num)+" / "+bytes(ram),ratio(m["ram"].num,ram),MemoryTeal,large = true) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Inset(Modifier.weight(1f)) { Gauge("磁盘",percent(ratio(m["disk"].num,disk)?.times(100)),bytes(m["disk"].num)+" / "+bytes(disk),ratio(m["disk"].num,disk),OfflineOrange,large = true) }
                Inset(Modifier.weight(1f)) { Hint("实时网络"); Text("↑ "+speed(m["net_out"].num),color = OnlineGreen); Text("↓ "+speed(m["net_in"].num),color = AppleBlue) }
            }
            Inset { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Quality(PingSummary.from(m["ping"]),false,Modifier.weight(1f)); Quality(PingSummary.from(m["ping"]),true,Modifier.weight(1f)) } }
        }
        GroupCard {
            SectionTitle("历史指标")
            Surface(shape = RoundedCornerShape(24.dp),color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(3.dp)) { listOf(1,6,24).forEach { h -> TextButton(onClick = { hours = h },modifier = Modifier.weight(1f),shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if(h == hours) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,contentColor = MaterialTheme.colorScheme.onSurface)) { Text(t("%lld 小时",h)) } } }
            }
            var expanded by remember { mutableStateOf(false) }
            val percentRAM = state.panel?.backend in listOf(Backend.NEZHA,Backend.DSTATUS)
            val metrics = listOf("cpu" to "CPU %","ram" to if(percentRAM) "内存 %" else "内存 GiB","disk" to if(state.panel?.backend == Backend.NEZHA) "磁盘 %" else "磁盘 GiB","net_in" to "下载 MiB/s","net_out" to "上传 MiB/s") + if(state.panel?.backend == Backend.NEZHA_V0) listOf("latency" to "延迟 ms") else emptyList()
            Box { TextButton(onClick = { expanded = true }) { Text(t(metrics.first { it.first == field }.second)+" ⌄") }; DropdownMenu(expanded,{ expanded = false }) { metrics.forEach { (id,title) -> DropdownMenuItem(text = { Text(t(title)) },onClick = { field = id; expanded = false }) } } }
            when { busy -> CircularProgressIndicator(); error != null -> ErrorText(error); records.isEmpty() -> EmptyState("暂无历史记录"); else -> HistoryChart(records,field,state.panel?.backend) }
            TextButton(onClick = { refresh++ },enabled = !busy) { Text(t("刷新")) }
        }
        GroupCard { SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("uuid" to "UUID","ipv4" to "IPv4","ipv6" to "IPv6","os" to "操作系统","cpu_name" to "CPU 型号","arch" to "架构","kernel_version" to "内核版本","tags" to "标签","public_remark" to "公开备注").forEach { (key,title) -> Row(Modifier.fillMaxWidth()) { Text(t(title),Modifier.weight(1f),color = MaterialTheme.colorScheme.onSurfaceVariant); Text(display(node[key]),Modifier.weight(1.4f)) } }
        } } }
        if(m["ping"].obj.isNotEmpty()) GroupCard { SectionTitle("Ping · 最近 1 小时"); m["ping"].obj.toSortedMap().values.forEach { p -> Row(Modifier.fillMaxWidth()) { Text(display(p["name"]),Modifier.weight(1f)); Text((p["latest"].num?.let { if(it < 0) t("丢包") else "%.0f ms".format(it) } ?: "—")+" · "+percent(p["loss"].num)+" "+t("丢包")) } } }
    }
}
@Composable fun Inset(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(10.dp)).padding(10.dp),verticalArrangement = Arrangement.spacedBy(6.dp),content = content)
}
fun chartValue(record: J, field: String, backend: Backend?): Double? {
    val v = record[field].num ?: return null
    return when {
        backend == Backend.NEZHA && field in listOf("ram","disk") || backend == Backend.DSTATUS && field == "ram" -> v
        field in listOf("ram","disk") -> v / 1_073_741_824
        field in listOf("net_in","net_out") -> v / 1_048_576
        else -> v
    }
}
@Composable fun HistoryChart(records: List<J>, field: String, backend: Backend?) {
    val points = records.mapNotNull { r -> val x = time(r["time"])?.toEpochMilli()?.toDouble() ?: return@mapNotNull null; chartValue(r,field,backend)?.let { Triple(x,it,r["series"].str) } }
    if(points.isEmpty()) { EmptyState("暂无历史记录"); return }
    val minX = points.minOf { it.first }; val range = (points.maxOf { it.first }-minX).coerceAtLeast(1.0)
    val maxY = points.maxOf { it.second }.coerceAtLeast(1.0)
    val colors = listOf(MaterialTheme.colorScheme.primary,OnlineGreen,OfflineOrange,MemoryTeal,LossRed)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = t("历史指标")+" · ${points.size} · "+"%.2f–%.2f".format(points.minOf { it.second },points.maxOf { it.second })
    Text("%.2f".format(maxY),fontSize = 11.sp,color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = description }) {
        repeat(5) { i -> val y = size.height*i/4; drawLine(grid,Offset(0f,y),Offset(size.width,y),1f) }
        points.groupBy { it.third }.values.forEachIndexed { index,series ->
            val path = Path()
            series.sortedBy { it.first }.forEachIndexed { i,p -> val x = ((p.first-minX)/range*size.width).toFloat(); val y = (size.height*(1-p.second/maxY)).toFloat(); if(i == 0) path.moveTo(x,y) else path.lineTo(x,y); if(series.size == 1) drawCircle(colors[index%colors.size],4f,Offset(x,y)) }
            drawPath(path,colors[index%colors.size],style = Stroke(2.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) { val format = java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()); Text(format.format(java.util.Date(minX.toLong())),fontSize = 11.sp); Text(format.format(java.util.Date(points.maxOf { it.first }.toLong())),fontSize = 11.sp) }
    points.map { it.third }.distinct().filter { it.isNotEmpty() }.forEachIndexed { i,name -> Text(name,fontSize = 11.sp,color = colors[i%colors.size]) }
}
@Composable fun NodeEditor(original: J, api: Api, close: () -> Unit, refresh: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(original["name"].str) }; var group by rememberSaveable { mutableStateOf(original["group"].str) }; var tags by rememberSaveable { mutableStateOf(original["tags"].str) }; var remark by rememberSaveable { mutableStateOf(original["public_remark"].str) }; var hidden by rememberSaveable { mutableStateOf(original["hidden"].bool) }
    var busy by remember { mutableStateOf(false) }; var submitted by rememberSaveable { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    Editor("编辑节点",busy,close) {
        GroupCard { Field("名称",name,{ name = it }); Field("分组",group,{ group = it }); Field("标签（分号分隔）",tags,{ tags = it }); Field("公开备注",remark,{ remark = it },multiline = true); ToggleRow("隐藏节点",hidden) { hidden = it } }
        ErrorText(error); if(submitted) Hint("请求已提交，请关闭并刷新核对，勿重复提交。")
        Button(onClick = { scope.launch { busy = true; error = null; try {
            val fields = json("uuid" to original["uuid"],"name" to name,"group" to group,"tags" to tags,"public_remark" to remark,"hidden" to hidden)
            submitted = true; api.rpc("admin:editClient",fields)
            verify(api.rpc("admin:getClient",json("uuid" to original["uuid"])),fields.obj.filterKeys { it != "uuid" })
            refresh(); close()
        } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } } },enabled = !busy && !submitted && name.isNotBlank(),modifier = Modifier.fillMaxWidth()) { Text(t("保存")) }
    }
}
