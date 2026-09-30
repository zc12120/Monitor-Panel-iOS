package com.khixang.panel.android

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun setup() { Locale.setDefault(Locale.SIMPLIFIED_CHINESE); I18n.initialize(ApplicationProvider.getApplicationContext()) }
    private val nodes = listOf(
        json("uuid" to "sg","name" to "Singapore 01","group" to "Asia","os" to "Ubuntu 24.04","arch" to "amd64","region" to "SG","cpu_name" to "AMD EPYC 7B13","mem_total" to 2147483648L,"disk_total" to 42949672960L,"traffic_limit" to 1099511627776L,"traffic_limit_type" to "sum","ipv4" to "192.0.2.10"),
        json("uuid" to "jp","name" to "Tokyo 02","group" to "Asia","os" to "Debian 12","region" to "JP","mem_total" to 1073741824L,"disk_total" to 21474836480L,"traffic_limit_type" to "sum"),
        json("uuid" to "us","name" to "San Francisco 03","group" to "US West","os" to "Rocky Linux 9","region" to "US","mem_total" to 4294967296L,"disk_total" to 85899345920L))
    private val state = PanelState(panels = listOf(Panel(id = "sample",name = "Komari",address = "https://monitor.example.com")),selected = "sample",nodes = nodes,fresh = true,
        statuses = json("sg" to json("online" to true,"cpu" to 18.7,"ram" to 720000000,"disk" to 9400000000L,"load1" to 0.18,"load5" to 0.25,"load15" to 0.32,"net_in" to 2516582,"net_out" to 734003,"net_total_up" to 9100000000L,"net_total_down" to 27300000000L,"ping" to json("1" to json("name" to "Tokyo","latest" to 38,"loss" to 0))),
            "jp" to json("online" to true,"cpu" to 5.2,"ram" to 264000000,"disk" to 6100000000L,"net_in" to 43102,"net_out" to 12800,"ping" to json("1" to json("latest" to 62,"loss" to 0))),
            "us" to json("online" to false)))
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val target = File("build/screenshots/$name.png"); target.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    @Test fun overviewMatchesOriginalAndSearchFilters() {
        compose.setContent { MonitorTheme("light") { AppFrame(t("总览")) { Dashboard(state,{}, {}) } } }
        compose.onNodeWithText("Singapore 01").assertIsDisplayed()
        screenshot("overview-light")
        compose.onNodeWithText(t("搜索节点名、分组或 IP")).performTextInput("Tokyo")
        compose.onNodeWithText("Singapore 01").assertDoesNotExist(); compose.onNodeWithText("Tokyo 02").assertIsDisplayed()
    }
    @Test fun unknownDataAndDarkModeRender() {
        compose.setContent { MonitorTheme("dark") { AppFrame(t("总览")) { Dashboard(state.copy(fresh = false),{}, {}) } } }
        compose.onNodeWithText(t("等待连接或未就绪")).assertIsDisplayed()
        screenshot("overview-dark-stale")
    }
    @Test fun detailShowsSpecsAndHistoryEmptyState() {
        compose.setContent { MonitorTheme("light") { AppFrame("Singapore 01",back = {}) { NodeDetail(state,nodes.first(),null) } } }
        compose.onAllNodesWithText("AMD EPYC 7B13")[0].assertIsDisplayed()
        screenshot("node-detail")
        compose.onNodeWithText(t("历史指标")).performScrollTo().assertIsDisplayed()
    }
    @Test fun firstLaunchExplainsPanelSetup() {
        compose.setContent { MonitorTheme("light") { AppFrame(t("总览")) { Dashboard(PanelState(),{}, {}) } } }
        compose.onNodeWithText(t("添加你的第一个面板")).assertIsDisplayed()
        screenshot("first-launch")
    }
    @Test fun managementKeepsOriginalDestinations() {
        var route = ""
        compose.setContent { MonitorTheme("light") { AppFrame(t("管理"),tab = 1) { ManagementMenu { route = it } } } }
        compose.onNodeWithText(t("远程命令与结果")).performClick()
        org.junit.Assert.assertEquals("commands",route)
        screenshot("management")
    }
    @Test fun appStartsAndOpensOriginalPanelSetupFlow() {
        val store = PanelStore(ApplicationProvider.getApplicationContext())
        compose.setContent { MonitorApp(store) }
        compose.onNodeWithText(t("添加你的第一个面板")).assertIsDisplayed()
        compose.onNodeWithText(t("面板")).performClick()
        compose.onNodeWithText(t("添加面板")).performClick()
        compose.onNodeWithText("DStatus").performClick()
        compose.onNodeWithText(t("DStatus 使用匿名公开只读 API，无需 API key。")).assertIsDisplayed()
        compose.onNodeWithText(t("验证并保存")).performScrollTo().assertIsNotEnabled()
    }
}
