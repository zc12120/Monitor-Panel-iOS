package com.khixang.panel.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Editor(title: String, busy: Boolean, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if(!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        BackHandler(busy) { }
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { CenterAlignedTopAppBar(title = { Text(t(title)) }, navigationIcon = { IconButton(onClick = close, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,t("关闭")) } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { padding ->
            CompositionLocalProvider(LocalFormEnabled provides !busy) {
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
            }
        }
    }
}
@Composable fun ToggleRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(t(label),Modifier.weight(1f)); Switch(checked,change,enabled = LocalFormEnabled.current) }
}
@Composable fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected,onClick); Text(t(label)) }
}
@Composable fun PanelsScreen(state: PanelState, store: PanelStore, navigate: (String) -> Unit) {
    var editing by remember { mutableStateOf<Panel?>(null) }; var showEditor by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Panel?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Hint("连接") }
        items(state.panels,key = { it.id }) { panel -> GroupCard {
            Row(Modifier.fillMaxWidth().clickable { store.select(panel.id) },verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(panel.name,style = MaterialTheme.typography.titleMedium); Hint(panel.address) }
                if(panel.id == state.selected) Icon(Icons.Outlined.CheckCircle,t("已选择"),tint = MaterialTheme.colorScheme.primary)
            }
            Row { TextButton(onClick = { editing = panel; showEditor = true }) { Text(t("编辑")) }; TextButton(onClick = { removing = panel }) { Text(t("移除"),color = LossRed) } }
        } }
        item { GroupCard { TextButton(onClick = { editing = null; showEditor = true }) { Icon(Icons.Outlined.Add,null); Text(t("添加面板")) } } }
        item { Hint("应用"); Spacer(Modifier.height(8.dp)); GroupCard { TextButton(onClick = { navigate("appearance") }) { Text(t("外观")) }; HorizontalDivider(); TextButton(onClick = { navigate("about") }) { Text(t("关于")) } } }
        item { ErrorText(error) }
    }
    if(showEditor) PanelEditor(editing,store) { showEditor = false }
    removing?.let { panel -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(t("确认移除")) }, text = { Text(panel.name+"\n"+t("仅删除本机连接及凭据，不删除服务器。")) }, confirmButton = { TextButton(onClick = { try { store.remove(panel) } catch(e: Exception) { error = safeError(e) }; removing = null }) { Text(t("确认移除"),color = LossRed) } }, dismissButton = { TextButton(onClick = { removing = null }) { Text(t("取消")) } }) }
}
@Composable fun PanelEditor(existing: Panel?, store: PanelStore, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }; var address by rememberSaveable { mutableStateOf(existing?.address.orEmpty()) }
    // Credentials are intentionally not saved to the Android instance-state Bundle.
    var key by remember { mutableStateOf("") }; var backend by rememberSaveable { mutableStateOf(existing?.backend ?: Backend.KOMARI) }
    var allow by rememberSaveable { mutableStateOf(existing?.allowHTTP ?: false) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Editor(if(existing == null) "添加面板" else "编辑面板",busy,close) {
        GroupCard { SectionTitle("后端"); Backend.entries.forEach { kind -> Choice(kind.title,backend == kind) { if(!busy) backend = kind } }
            Hint(when(backend) { Backend.KOMARI -> "使用 Komari 管理员 API key。"; Backend.NEZHA -> "使用 PAT 或 JWT。只读 PAT 需要 inventory:read 与 server:read 权限。"; Backend.NEZHA_V0 -> "使用旧版 API Token，认证头不含 Bearer 前缀。"; Backend.DSTATUS -> "DStatus 使用匿名公开只读 API，无需 API key。" }) }
        GroupCard { SectionTitle("连接信息"); Field("面板名称",name,{ if(!busy) name = it }); Field("https://你的面板地址",address,{ if(!busy) address = it }); if(backend != Backend.DSTATUS) Field(if(existing == null) "API key" else "新 API key（留空保持原 key）",key,{ if(!busy) key = it },secret = true) }
        GroupCard { ToggleRow("允许不安全 HTTP",allow) { if(!busy) allow = it }; if(allow) ErrorText(t("HTTP 会明文传输管理员 API key，仅在受信任网络使用。")) }
        ErrorText(error)
        Button(onClick = { scope.launch { busy = true; error = null; try {
            val p = Panel(id = existing?.id ?: java.util.UUID.randomUUID().toString(),name = name.trim(),address = address.trim(),backend = backend,allowHTTP = allow)
            store.savePanel(p,key); key = ""; close()
        } catch(e: CancellationException) { throw e } catch(e: Exception) { error = safeError(e) } finally { busy = false } } },enabled = !busy && name.isNotBlank() && address.isNotBlank() && (backend == Backend.DSTATUS || existing != null || key.isNotEmpty()),modifier = Modifier.fillMaxWidth()) { if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth = 2.dp) else Text(t("验证并保存")) }
    }
}
@Composable fun DashboardSettings(state: PanelState, hidden: Boolean, close: () -> Unit, save: (DashboardPreferences) -> Unit) {
    val nodes = state.preferences.ordered(state.nodes)
    val currentState by rememberUpdatedState(state)
    val currentSave by rememberUpdatedState(save)
    Editor(if(hidden) "隐藏节点" else "自定义排序",false,close) {
        Hint(if(hidden) "隐藏的节点仅影响本机显示。" else "拖动右侧手柄调整顺序，仅保存在本机当前面板。隐藏的节点也保留排序位置。")
        if(hidden) TextButton(onClick = { save(state.preferences.copy(hidden = emptySet())) }) { Text(t("恢复全部节点")) }
        GroupCard { nodes.forEachIndexed { index,node ->
            val id = node["uuid"].str
            if(hidden) ToggleRow(display(node["name"]),id in state.preferences.hidden) { checked -> save(state.preferences.copy(hidden = if(checked) state.preferences.hidden + id else state.preferences.hidden - id)) }
            else {
                fun move(to: Int) { val ids = nodes.map { it["uuid"].str }.toMutableList(); ids.add(to,ids.removeAt(index)); save(state.preferences.copy(order = ids)) }
                key(id) { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(display(node["name"]),Modifier.weight(1f))
                    IconButton(onClick = { move(index-1) },enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp,t("上移")) }
                    IconButton(onClick = { move(index+1) },enabled = index < nodes.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown,t("下移")) }
                    Icon(Icons.Outlined.DragHandle,t("自定义排序"),Modifier.size(48.dp).padding(12.dp).pointerInput(id) {
                        var distance = 0f
                        detectDragGesturesAfterLongPress(onDragStart = { distance = 0f },onDrag = { change, amount ->
                            change.consume(); distance += amount.y
                            val step = 60.dp.toPx()
                            if(kotlin.math.abs(distance) > step) {
                                val list = currentState.preferences.ordered(currentState.nodes).map { it["uuid"].str }.toMutableList()
                                val from = list.indexOf(id); val to = (from + if(distance > 0) 1 else -1).coerceIn(0,list.lastIndex)
                                if(from >= 0 && from != to) { list.add(to,list.removeAt(from)); currentSave(currentState.preferences.copy(order = list)) }
                                distance = 0f
                            }
                        })
                    })
                } }
            }
        } }
        Button(close,Modifier.fillMaxWidth()) { Text(t("完成")) }
    }
}
@Composable fun AppearanceScreen(state: PanelState, mode: (String) -> Unit, accent: (String) -> Unit) {
    var custom by rememberSaveable(state.accent) { mutableStateOf(state.accent) }; var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard { SectionTitle("主题"); listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach { (id,label) -> Choice(label,state.appearance == id) { mode(id) } } }
        GroupCard { SectionTitle("强调色"); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("#007AFF","#34C759","#FF9500","#FF3B30","#AF52DE","#00C7BE").forEach { hex -> Box(Modifier.size(36.dp).background(Color(android.graphics.Color.parseColor(hex)),CircleShape).border(if(state.accent == hex) 3.dp else 0.dp,MaterialTheme.colorScheme.onSurface,CircleShape).clickable { accent(hex) }) } }
            Field("HEX，例如 #007AFF",custom,{ custom = it; error = null }); ErrorText(error)
            Button(onClick = { val hex = "#"+custom.removePrefix("#"); if(hex.matches(Regex("#[0-9A-Fa-f]{6}"))) accent(hex.uppercase()) else error = t("请输入 6 位 HEX 颜色值。") }) { Text(t("完成")) }
        }
    }
}
@Composable fun AboutScreen() {
    val uri = LocalUriHandler.current; val context = LocalContext.current
    var notices by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GroupCard { Column(Modifier.fillMaxWidth(),horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(10.dp)) { Image(painterResource(R.drawable.abouticon),null,Modifier.size(88.dp)); Text("Monitor Panel",style = MaterialTheme.typography.titleLarge); Hint("${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}") }; TextButton(onClick = { uri.openUri("https://github.com/zc12120/Monitor-Panel-iOS") }) { Text(t("GitHub 仓库")) } }
        GroupCard { SectionTitle("安全与兼容"); Hint("API key 使用 Android Keystore 加密保存在本机"); Hint("默认仅允许 HTTPS；HTTP 需逐个面板明确允许。证书必须有效，不绕过 TLS 校验。API key 为管理员权限，请妥善保护。") }
        GroupCard { SectionTitle("开源组件与致谢"); Hint("Android · Kotlin · Jetpack Compose · OkHttp · kotlinx.coroutines · kotlinx.serialization"); TextButton(onClick = { notices = true }) { Text(t("图标与数据许可")) }
            listOf("iOS 原项目" to "https://github.com/Likhixang/Monitor-Panel-iOS","Komari · MIT" to "https://github.com/komari-monitor/komari","哪吒 · Apache-2.0" to "https://github.com/nezhahq/nezha","Komari API 文档" to "https://komari-document.pages.dev/en/dev/api","哪吒 API 文档" to "https://nezha.wiki/guide/api.html","DStatus API 文档" to "https://docs.vps.mom/public-api").forEach { (label,url) -> TextButton(onClick = { uri.openUri(url) }) { Text(t(label)) } }
        }
    }
    if(notices) Editor("图标与数据许可",false,{ notices = false }) { Text(remember { context.assets.open("ThirdPartyNotices.txt").bufferedReader().use { it.readText() } }) }
}
