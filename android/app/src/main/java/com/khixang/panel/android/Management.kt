package com.khixang.panel.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

fun verify(actual: J, fields: Map<String,J>) {
    for((key,value) in fields) {
        val matches = if(key == "clients") actual[key].arr.map { it.str }.toSet() == value.arr.map { it.str }.toSet() else jsonEqual(actual[key],value)
        if(!matches) throw ApiError("服务器回读内容不一致，请刷新后检查。")
    }
}
fun mergeRule(current: J, original: J, fields: Map<String,J>): J {
    for(key in fields.keys) if(!jsonEqual(current[key],original[key])) throw ApiError("配置已被其他人修改，请关闭并重新编辑。")
    return JsonObject(current.obj + fields)
}
enum class Rule(val title: String,val list: String,val add: String,val edit: String,val delete: String,val wrapper: String) {
    PING("Ping 任务","admin:getAllPingTasks","admin:addPingTask","admin:editPingTask","admin:deletePingTask","tasks"),
    LOAD("负载通知","admin:getAllLoadNotifications","admin:addLoadNotification","admin:editLoadNotification","admin:deleteLoadNotification","notifications")
}
@Composable fun ManagementMenu(navigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Hint("监测与通知"); GroupCard { listOf("ping" to "Ping 任务","load" to "负载通知","offline" to "离线通知").forEach { (id,label) -> TextButton(onClick = { navigate(id) },modifier = Modifier.fillMaxWidth()) { Text(t(label),Modifier.weight(1f)); Text("›") } } }
        Hint("运维"); GroupCard { listOf("commands" to "远程命令与结果","logs" to "审计日志").forEach { (id,label) -> TextButton(onClick = { navigate(id) },modifier = Modifier.fillMaxWidth()) { Text(t(label),Modifier.weight(1f)); Text("›") } } }
    }
}
@Composable fun ManagementScreen(screen: String, api: Api, nodes: List<J>, navigate: (String) -> Unit) {
    when {
        screen == "ping" -> RulesScreen(Rule.PING,api,nodes)
        screen == "load" -> RulesScreen(Rule.LOAD,api,nodes)
        screen == "offline" -> OfflineScreen(api,nodes)
        screen == "commands" -> CommandsScreen(api,nodes,navigate)
        screen == "logs" -> LogsScreen(api)
        screen.startsWith("task/") -> TaskScreen(api,nodes,screen.removePrefix("task/"))
    }
}
@Composable fun ManagementColumn(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp),content = content) }
@Composable fun NodePicker(nodes: List<J>, selected: Set<String>, set: (Set<String>) -> Unit, enabled: Boolean = true) {
    GroupCard {
        Text(t("选择节点（%lld）",selected.size),style = MaterialTheme.typography.titleMedium)
        if(nodes.isEmpty()) Hint("没有可选节点，请返回节点页刷新。")
        val all = nodes.filter { it["uuid"].str.isNotEmpty() }.associateBy { it["uuid"].str }
        (all.keys+selected).forEach { id -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in selected,{ if(it) set(selected+id) else set(selected-id) },enabled = enabled); Column { Text(all[id]?.get("name")?.str?.ifEmpty { id } ?: id); Hint(id) } } }
    }
}
fun nodeName(id: String,nodes: List<J>) = nodes.find { it["uuid"].str == id }?.get("name")?.str?.ifEmpty { id } ?: id

@Composable fun RulesScreen(rule: Rule,api: Api,nodes: List<J>) {
    var rows by remember(rule) { mutableStateOf<List<J>>(emptyList()) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }; var edit by remember { mutableStateOf<J?>(null) }; var editor by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf<J?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api,rule,reload) { busy = true; error = null; try { rows = api.rpc(rule.list).arr } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } }
    ManagementColumn {
        Row { TextButton(onClick = { edit = null; editor = true },enabled = !busy) { Text(t("新增")) }; TextButton(onClick = { reload++ },enabled = !busy) { Text(t("刷新")) } }
        if(rule == Rule.LOAD) Hint("适用于提供负载通知接口的 Komari 1.5.x；1.6 主线已移除此接口，若不支持将显示服务器错误。")
        ErrorText(error); if(busy) CircularProgressIndicator() else if(rows.isEmpty() && error == null) EmptyState("暂无配置")
        rows.forEach { row -> GroupCard {
            Text(display(row["name"]),style = MaterialTheme.typography.titleMedium)
            Hint(if(rule == Rule.PING) "${display(row["type"])} · ${display(row["target"])} · ${display(row["interval"])} s" else "${display(row["metric"])} · ${display(row["threshold"])} · ${display(row["ratio"])} · ${display(row["interval"])} min")
            Hint(row["clients"].arr.joinToString("、") { nodeName(it.str,nodes) })
            Row { TextButton(onClick = { edit = row; editor = true },enabled = !busy) { Text(t("编辑")) }; TextButton(onClick = { deleting = row },enabled = !busy) { Text(t("删除"),color = LossRed) } }
        } }
    }
    if(editor) RuleEditor(rule,api,nodes,edit) { editor = false; reload++ }
    deleting?.let { row -> AlertDialog(onDismissRequest = { if(!busy) deleting = null },title = { Text(t("确认删除")) },text = { Text(display(row["name"])) },confirmButton = { TextButton(enabled = !busy,onClick = { deleting = null; scope.launch { busy = true; error = null; try { api.rpc(rule.delete,json("id" to listOf(row["id"]))); rows = api.rpc(rule.list).arr; if(rows.any { it["id"] == row["id"] }) throw ApiError("删除回读失败，配置仍存在。") } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } } }) { Text(t("确认删除"),color = LossRed) } },dismissButton = { TextButton(onClick = { deleting = null },enabled = !busy) { Text(t("取消")) } }) }
}
@Composable fun RuleEditor(rule: Rule,api: Api,nodes: List<J>,original: J?,close: () -> Unit) {
    val old = original ?: Empty
    var name by rememberSaveable { mutableStateOf(old["name"].str) }; var target by rememberSaveable { mutableStateOf(old["target"].str) }; var type by rememberSaveable { mutableStateOf(old["type"].str.ifEmpty { "icmp" }) }
    var metric by rememberSaveable { mutableStateOf(old["metric"].str.ifEmpty { "cpu" }) }; var interval by rememberSaveable { mutableStateOf(old["interval"].str.ifEmpty { if(rule == Rule.PING) "60" else "15" }) }
    var threshold by rememberSaveable { mutableStateOf(old["threshold"].str.ifEmpty { "80" }) }; var ratio by rememberSaveable { mutableStateOf(old["ratio"].str.ifEmpty { "0.8" }) }; var defaultOn by rememberSaveable { mutableStateOf(old["default_on"].bool) }
    var selected by remember { mutableStateOf(old["clients"].arr.map { it.str }.toSet()) }; var busy by remember { mutableStateOf(false) }; var submitted by rememberSaveable { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    Editor(rule.title,busy,close) {
        GroupCard {
            Field("名称",name,{ name = it })
            if(rule == Rule.PING) {
                Row { listOf("icmp","tcp","http").forEach { p -> FilterChip(selected = type == p,onClick = { type = p },enabled = !busy,label = { Text(p.uppercase()) }) } }
                Field("目标（主机、主机:端口或 URL）",target,{ target = it }); Field("间隔（秒）",interval,{ interval = it }); ToggleRow("新加入节点默认开启",defaultOn) { defaultOn = it }; Hint("此开关不影响已有节点；请显式选择已有节点。")
            } else { Field("指标（如 cpu、ram、load）",metric,{ metric = it }); Field("阈值（资源占用百分比）",threshold,{ threshold = it }); Field("达标时间比（大于 0，最多 1）",ratio,{ ratio = it }); Field("监测窗口（1–240 分钟）",interval,{ interval = it }) }
        }
        NodePicker(nodes,selected,{ selected = it },!busy); ErrorText(error)
        if(submitted) Hint("请求已提交。若回读失败，请关闭并刷新列表核对；不要重复提交。")
        Button(onClick = {
            val period = interval.toIntOrNull(); val limit = threshold.toDoubleOrNull(); val proportion = ratio.toDoubleOrNull()
            if(period == null || period <= 0) { error = t("间隔必须为正整数。"); return@Button }
            if(rule == Rule.PING && (name.isBlank() || target.isBlank() || type.isBlank() || (!defaultOn && selected.isEmpty()))) { error = t("填写名称和目标，并选择节点或开启新节点默认监测。"); return@Button }
            if(rule == Rule.LOAD && (selected.isEmpty() || metric.isBlank() || period > 240 || limit == null || !limit.isFinite() || limit <= 0 || proportion == null || !proportion.isFinite() || proportion <= 0 || proportion > 1)) { error = t("请选择节点、填写指标及正阈值；比例应大于 0 且不超过 1，窗口不超过 240 分钟。"); return@Button }
            val fields = json("name" to name,"clients" to selected.sorted(),"interval" to period).obj + if(rule == Rule.PING) json("target" to target,"type" to type,"default_on" to defaultOn).obj else json("metric" to metric,"threshold" to limit,"ratio" to proportion).obj
            scope.launch { busy = true; error = null; try {
                val id: J
                if(original != null) {
                    val current = api.rpc(rule.list).arr.find { it["id"] == old["id"] } ?: throw ApiError("此配置已不存在，请关闭并刷新。")
                    val merged = mergeRule(current,original,fields)
                    submitted = true; api.rpc(rule.edit,json(rule.wrapper to listOf(merged))); id = current["id"]
                } else { submitted = true; id = api.rpc(rule.add,JsonObject(fields))["task_id"] }
                val actual = api.rpc(rule.list).arr.find { it["id"] == id } ?: throw ApiError("请求已提交，但无法找到回读配置。")
                verify(actual,fields); close()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } }
        },enabled = !busy && !submitted,modifier = Modifier.fillMaxWidth()) { Text(t("保存并核对")) }
    }
}

@Composable fun OfflineScreen(api: Api,nodes: List<J>) {
    var rows by remember { mutableStateOf<List<J>>(emptyList()) }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var reload by remember { mutableIntStateOf(0) }; var editing by remember { mutableStateOf<J?>(null) }
    LaunchedEffect(api,reload) { busy = true; error = null; try { rows = api.rpc("admin:listOfflineNotifications").arr } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } }
    ManagementColumn { TextButton(onClick = { reload++ },enabled = !busy) { Text(t("刷新")) }; ErrorText(error); if(busy) CircularProgressIndicator()
        if(nodes.isEmpty()) Hint("没有可选节点，请返回节点页刷新。")
        nodes.forEach { node -> val row = rows.find { it["client"] == node["uuid"] }; GroupCard { TextButton(onClick = { editing = row ?: json("client" to node["uuid"]) },enabled = !busy) { Column(Modifier.fillMaxWidth()) { Text(display(node["name"])); Hint(row?.let { (if(it["enable"].bool) t("是") else t("否"))+" · ${it["grace_period"].str} s" } ?: "— · 尚未配置") } } } }
    }
    editing?.let { row -> OfflineEditor(api,nodes,row) { editing = null; reload++ } }
}
@Composable fun OfflineEditor(api: Api,nodes: List<J>,original: J,close: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(original["enable"].bool) }; var grace by rememberSaveable { mutableStateOf(original["grace_period"].str.ifEmpty { "180" }) }; var busy by remember { mutableStateOf(false) }; var submitted by rememberSaveable { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    Editor("编辑离线通知",busy,close) {
        GroupCard { Text(nodeName(original["client"].str,nodes),style = MaterialTheme.typography.titleMedium); ToggleRow("启用离线通知",enabled) { enabled = it }; Field("宽限期（秒）",grace,{ grace = it }); Hint(t("上次通知")+" · "+display(original["last_notified"])) }
        ErrorText(error); if(submitted) Hint("请求已提交，请关闭并刷新核对，勿重复提交。")
        Button(onClick = {
            val seconds = grace.toIntOrNull(); if(seconds == null || seconds <= 0) { error = t("宽限期必须是正整数。"); return@Button }
            scope.launch { busy = true; error = null; try {
                val current = api.rpc("admin:listOfflineNotifications").arr.find { it["client"] == original["client"] }
                if(current != null && listOf("enable","grace_period").any { current[it] != original[it] } || current == null && "enable" in original.obj) throw ApiError("此节点配置已更改，请关闭并重新编辑。")
                val fields = json("enable" to enabled,"grace_period" to seconds)
                val payload = JsonObject((current ?: json("client" to original["client"])).obj + fields.obj)
                submitted = true; api.rpc("admin:editOfflineNotification",JsonArray(listOf(payload)))
                val actual = api.rpc("admin:listOfflineNotifications").arr.find { it["client"] == original["client"] } ?: throw ApiError("请求已提交，但未找到回读配置。")
                verify(actual,fields.obj); close()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } }
        },enabled = !busy && !submitted,modifier = Modifier.fillMaxWidth()) { Text(t("保存并核对")) }
    }
}

@Composable fun CommandsScreen(api: Api,nodes: List<J>,navigate: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }; var selected by remember { mutableStateOf(emptySet<String>()) }; var tasks by remember { mutableStateOf<List<J>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var reload by remember { mutableIntStateOf(0) }; var confirmation by remember { mutableStateOf(false) }
    var executed by rememberSaveable { mutableStateOf(false) }; var executionID by rememberSaveable { mutableStateOf("") }; var delivery by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    LaunchedEffect(api,reload) { busy = true; error = null; try { tasks = api.rpc("admin:getTasks").arr } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } }
    ManagementColumn {
        GroupCard {
            SectionTitle("远程执行"); Hint("命令将以 Agent 的权限运行。请确认目标节点与命令，不可撤销。")
            Field("远程命令",command,{ if(!busy && !executed) command = it },multiline = true)
            Button(onClick = { confirmation = true },enabled = !busy && !executed && selected.isNotEmpty() && command.isNotBlank()) { Text(t("执行命令")) }
            if(executed) { Hint("本次命令已提交，不会自动重试。结果可能仍在等待节点回报。"); TextButton(onClick = { command = ""; executed = false; executionID = ""; delivery = "" },enabled = !busy) { Text(t("准备新命令")) } }
            if(executionID.isNotEmpty()) SelectionContainer { Text(t("任务 ID")+" · "+executionID) }
            if(delivery.isNotEmpty()) Hint(delivery)
            ErrorText(error); if(busy) CircularProgressIndicator()
        }
        NodePicker(nodes,selected,{ selected = it },!busy && !executed)
        Row { SectionTitle("任务与结果"); Spacer(Modifier.weight(1f)); TextButton(onClick = { reload++ },enabled = !busy) { Text(t("刷新")) } }
        if(tasks.isEmpty() && !busy) Hint("暂无任务，或尚未成功加载。")
        tasks.forEach { task -> GroupCard { TextButton(onClick = { navigate("task/${task["task_id"].str}") }) { Column(Modifier.fillMaxWidth()) { Text(display(task["command"]),fontFamily = FontFamily.Monospace,maxLines = 2); Hint(task["task_id"].str+" · ${task["results"].arr.size}") } } } }
    }
    if(confirmation) AlertDialog(onDismissRequest = { confirmation = false },title = { Text(t("在所选 %lld 个节点执行命令？",selected.size)) },text = { Text(command+"\n\n"+selected.sorted().joinToString("、") { nodeName(it,nodes) }) },confirmButton = { TextButton(onClick = {
        confirmation = false; if(busy || executed) return@TextButton
        val targets = selected.sorted(); val confirmedCommand = command
        executed = true; busy = true
        scope.launch { error = null; try {
            val fields = json("command" to confirmedCommand,"clients" to targets)
            val response = api.rpc("admin:exec",fields); executionID = response["task_id"].str
            delivery = t("即时派发：%lld · 排队：%lld",response["clients"].arr.size,response["queued_clients"].arr.size)
            if(executionID.isEmpty()) throw ApiError("服务器未返回任务 ID，请刷新任务列表核对，不要重复执行。")
            verify(api.rpc("admin:getTaskById",json("task_id" to executionID)),fields.obj)
            tasks = api.rpc("admin:getTasks").arr
        } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e)+"\n"+t("执行状态可能不确定，请刷新任务列表核对；不会自动重试。") } finally { busy = false } }
    }) { Text(t("确认执行"),color = LossRed) } },dismissButton = { TextButton(onClick = { confirmation = false }) { Text(t("取消")) } })
}

@Composable fun TaskScreen(api: Api,nodes: List<J>,id: String) {
    var task by remember { mutableStateOf<J>(Empty) }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(api,id,reload) { busy = true; error = null; try { task = api.rpc("admin:getTaskById",json("task_id" to id)) } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } }
    ManagementColumn {
        GroupCard { SectionTitle("任务"); SelectionContainer { Column { Text(id); Text(display(task["command"]),fontFamily = FontFamily.Monospace) } }; ErrorText(error); if(busy) CircularProgressIndicator(); TextButton(onClick = { reload++ },enabled = !busy) { Text(t("刷新")) } }
        SectionTitle("节点执行结果")
        task["clients"].arr.forEach { client -> val result = task["results"].arr.find { it["client"] == client }; GroupCard {
            Text(nodeName(client.str,nodes),style = MaterialTheme.typography.titleMedium)
            if(result == null) Hint("— · 等待结果") else { Text(t("退出码")+" · "+display(result["exit_code"])); Hint(t("完成时间")+" · "+display(result["finished_at"])); SelectionContainer { Text(display(result["result"]),fontFamily = FontFamily.Monospace) } }
        } }
    }
}
@Composable fun LogsScreen(api: Api) {
    var filter by rememberSaveable { mutableStateOf("") }; var applied by remember { mutableStateOf("") }; var page by rememberSaveable { mutableIntStateOf(1) }; var requestedPage by remember { mutableIntStateOf(1) }
    var total by remember { mutableStateOf<Int?>(null) }; var rows by remember { mutableStateOf<List<J>>(emptyList()) }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(api,reload) { busy = true; error = null; try {
        val result = api.rpc("admin:getLogs",json("limit" to "50","page" to requestedPage.toString(),"msg_type" to applied))
        val count = result["total"].num?.takeIf { it >= 0 && it <= Int.MAX_VALUE } ?: throw ApiError("服务器未返回有效日志总数。")
        total = count.toInt(); rows = result["logs"].arr; page = requestedPage
    } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } }
    ManagementColumn {
        GroupCard {
            SectionTitle("筛选与分页"); Field("消息类型（留空为全部，如 info、warn）",filter,{ filter = it })
            TextButton(onClick = { applied = filter; requestedPage = 1; reload++ },enabled = !busy) { Text(t("应用筛选")) }
            Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { requestedPage = page-1; reload++ },enabled = !busy && page > 1) { Text(t("上一页")) }; Text("$page · ${total ?: "—"}",Modifier.weight(1f)); TextButton(onClick = { requestedPage = page+1; reload++ },enabled = !busy && total != null && page*50 < (total ?: 0)) { Text(t("下一页")) } }
            TextButton(onClick = { requestedPage = page; reload++ },enabled = !busy) { Text(t("刷新")) }; ErrorText(error); if(busy) CircularProgressIndicator()
        }
        SectionTitle("审计记录"); if(rows.isEmpty() && total != null) Hint("暂无记录")
        rows.forEach { row -> GroupCard { SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(display(row["message"])); Hint(display(row["msg_type"])+" · "+display(row["time"])); Hint("IP ${display(row["ip"])} · ${display(row["uuid"])}") } } } }
    }
}
