package com.khixang.panel.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.initialize(this)
        enableEdgeToEdge()
        setContent { val store: PanelStore = viewModel(); MonitorApp(store) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppFrame(title: String, tab: Int = 0, onTab: (Int) -> Unit = {}, back: (() -> Unit)? = null,
                         actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        CenterAlignedTopAppBar(title = { Text(title,style = MaterialTheme.typography.titleMedium) },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { if(back != null) IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,t("返回")) } },actions = actions)
    }, bottomBar = {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 36.dp,vertical = 10.dp),contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(36.dp),color = MaterialTheme.colorScheme.surface.copy(alpha = .97f)) {
                Row(Modifier.widthIn(max = 420.dp).padding(6.dp)) {
                    listOf(Icons.Outlined.Dashboard,Icons.Outlined.Tune,Icons.Outlined.Dns).forEachIndexed { i,icon ->
                        TextButton(onClick = { onTab(i) },modifier = Modifier.weight(1f),shape = RoundedCornerShape(30.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = if(i == tab) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = if(i == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Icon(icon,null,Modifier.size(26.dp)); Text(t(listOf("总览","管理","面板")[i]),style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MonitorApp(store: PanelStore) {
    val state by store.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var screen by rememberSaveable { mutableStateOf("") }
    var showSort by rememberSaveable { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var editNode by remember { mutableStateOf<J?>(null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            store.poll()
            while(isActive) { delay(5_000); store.poll() }
        }
    }
    LaunchedEffect(state.selected) { screen = ""; showSort = false; showHidden = false; editNode = null }
    BackHandler(screen.isNotEmpty()) { screen = if(screen.startsWith("task/")) "commands" else "" }
    MonitorTheme(state.appearance, state.accent) {
        val node = if(screen.startsWith("node/")) state.nodes.find { it["uuid"].str == screen.removePrefix("node/") } else null
        val api = remember(state.panel,state.connectionRevision) { runCatching { if(state.panel != null) store.api() else null }.getOrNull() }
        val title = when {
            node != null -> node["name"].str
            screen == "appearance" -> t("外观"); screen == "about" -> t("关于")
            screen == "ping" -> t("Ping 任务"); screen == "load" -> t("负载通知"); screen == "offline" -> t("离线通知")
            screen == "commands" -> t("远程命令"); screen == "logs" -> t("审计日志"); screen.startsWith("task/") -> t("任务结果")
            else -> t(listOf("总览", "管理", "面板")[tab])
        }
        AppFrame(title, tab, { tab = it; screen = "" },
            back = if(screen.isEmpty()) null else ({ screen = if(screen.startsWith("task/")) "commands" else "" }),
            actions = {
                if(tab == 0 && screen.isEmpty()) {
                    IconButton(onClick = { showSort = true }) { Icon(Icons.Outlined.SwapVert, t("自定义排序"), tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { showHidden = true }) { Icon(Icons.Outlined.VisibilityOff, t("隐藏节点"), tint = MaterialTheme.colorScheme.primary) }
                }
                if(node != null && state.panel?.backend == Backend.KOMARI) IconButton(onClick = { editNode = node }) { Icon(Icons.Outlined.Edit, t("编辑"), tint = MaterialTheme.colorScheme.primary) }
            }) {
                when {
                    node != null -> NodeDetail(state, node, api)
                    screen == "appearance" -> AppearanceScreen(state, store::appearance, store::accent)
                    screen == "about" -> AboutScreen()
                    screen in listOf("ping", "load", "offline", "commands", "logs") || screen.startsWith("task/") -> {
                        if(api != null && state.panel?.backend == Backend.KOMARI) key(state.selected) { ManagementScreen(screen, api, state.nodes) { screen = it } }
                        else EmptyState("尚未连接", "Komari 支持管理；哪吒与 DStatus 当前提供只读监控。")
                    }
                    tab == 0 -> Dashboard(state, store::reload) { screen = "node/${it["uuid"].str}" }
                    tab == 1 -> if(state.panel?.backend == Backend.KOMARI && state.fresh) ManagementMenu { screen = it }
                        else EmptyState("尚未连接", "Komari 支持管理；哪吒与 DStatus 当前提供只读监控。")
                    else -> PanelsScreen(state, store) { screen = it }
                }
        }
        if(showSort || showHidden) DashboardSettings(state, showHidden, { showSort = false; showHidden = false }, store::dashboard)
        editNode?.let { original -> if(api != null) NodeEditor(original, api, { editNode = null }, store::reload) }
    }
}
