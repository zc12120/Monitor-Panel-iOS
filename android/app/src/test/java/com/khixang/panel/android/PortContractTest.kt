package com.khixang.panel.android

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit

class PortContractTest {
    @Test fun addressesRequireOptInAndRejectCredentialOrTraversalForms() {
        listOf("http://example.com","https://u:p@example.com","https://example.com?a=b","https://example.com/#key","https://example.com/a/../b","https://example.com/%2e%2e/x","https://example.com/a%2Fb","https://example.com\\@evil.test","https://example.com/a%0Ab"," https://example.com").forEach { url ->
            assertThrows(ApiError::class.java) { Api.validateAddress(url,false) }
        }
        assertEquals("/panel/",Api.validateAddress("https://example.com/panel/",false).encodedPath)
        assertEquals("http",Api.validateAddress("http://example.com",true).scheme)
    }
    @Test fun rpcEnvelopeMatchesOriginalNullAndErrorContracts() {
        assertEquals(JsonNull,Api.rpcResult(parse("""{"jsonrpc":"2.0","id":"one","result":null,"error":null}"""),"one"))
        assertThrows(ApiError::class.java) { Api.rpcResult(parse("""{"jsonrpc":"2.0","id":"two","result":[]}"""),"one") }
        assertThrows(ApiError::class.java) { Api.rpcResult(parse("""{"jsonrpc":"2.0","id":"one"}"""),"one") }
        val error = assertThrows(ApiError::class.java) { Api.rpcResult(parse("""{"jsonrpc":"2.0","id":"one","error":{"code":-32601,"message":"SECRET-ECHO"}}"""),"one") }
        assertEquals(-32601,error.detail); assertFalse(safeError(error).contains("SECRET"))
        assertThrows(ApiError::class.java) { Api.rpcResult(parse("""{"jsonrpc":"2.0","id":"one","result":1,"error":{"code":-1,"message":"bad"}}"""),"one") }
    }
    @Test fun authPrefixesDeploymentPrefixAndNoRedirects() = runTest {
        val loopback = InetAddress.getLoopbackAddress()
        val host = requireNotNull(loopback.hostAddress)
        val server = MockWebServer(); server.start(loopback,0)
        try {
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val id = parse(request.body.readUtf8())["id"]
                    return MockResponse().setBody(json("jsonrpc" to "2.0","id" to id,"result" to emptyList<J>()).toString())
                }
            }
            val address = server.url("/prefix").newBuilder().host(host).build().toString()
            val api = Api(Panel(name = "Test",address = address,allowHTTP = true),"dummy-key",OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build())
            api.rpc("admin:listClients")
            val request = server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/rpc2",request.path); assertEquals("Bearer dummy-key",request.getHeader("Authorization"))
            val legacy = Api(Panel(name = "V0",address = "https://example.com",backend = Backend.NEZHA_V0),"dummy-key")
            assertEquals("dummy-key",legacy.request("api/v1/server/details").build().header("Authorization"))
            val public = Api(Panel(name = "DStatus",address = "https://example.com",backend = Backend.DSTATUS),"ignored")
            assertNull(public.request("api/servers").build().header("Authorization"))
            server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302).setHeader("Location",server.url("/stolen")) }
            try { api.rpc("admin:listClients"); fail("Redirect accepted") } catch(e: ApiError) { assertEquals(302,e.detail) }
            assertEquals(2,server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun cancelledTransportDoesNotBecomeCredentialBearingError() = runTest {
        val loopback = InetAddress.getLoopbackAddress()
        val host = requireNotNull(loopback.hostAddress)
        val server = MockWebServer(); server.start(loopback,0)
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val address = server.url("/").newBuilder().host(host).build().toString()
            val api = Api(Panel(name = "Test",address = address,allowHTTP = true),"dummy-key",OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build())
            val job = launch(Dispatchers.Default) { api.rpc("common:getNodesLatestStatus") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            job.cancelAndJoin(); assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
    @Test fun nezhaVersionsNormalizeMetricsAndUnknownStatus() {
        val modern = parse("""{"servers":[{"id":12,"name":"node","last_active":"2026-09-30T00:00:00Z","country_code":"JP","host":{"mem_total":1024,"disk_total":2048},"state":{"cpu":12.5,"mem_used":512,"net_out_speed":99}}]}""")
        val snapshot = normalizeNezha(modern,false,Instant.parse("2026-09-30T00:00:05Z"))
        assertEquals("12",snapshot.nodes.first()["uuid"].str); assertEquals(true,snapshot.statuses["12"]["online"].bool)
        assertEquals(512.0,snapshot.statuses["12"]["ram"].num!!,0.0); assertEquals(99.0,snapshot.statuses["12"]["net_out"].num!!,0.0)
        val legacy = normalizeNezha(parse("""{"result":[{"id":1,"host":{"CountryCode":"US","MemTotal":4096},"status":{"CPU":7}}]}"""),true)
        assertEquals(JsonNull,legacy.statuses["1"]["online"]); assertEquals(7.0,legacy.statuses["1"]["cpu"].num!!,0.0)
    }
    @Test fun dstatusNullsUnitsOrderAndUnlimitedStayIntact() {
        val s = normalizeDStatus(parse("""{"order":["b","b","a"],"data":{"a":{"name":"A","stat":{"cpu":{"multi":0.3},"mem":{"virtual":{"used":25,"total":100}}},"traffic_stats":{"used":150,"unlimited":true}},"b":{"name":"B","stat":{"offline":true}}}}"""),parse("""{"data":[{"id":"a","data":{"location":{"code":"SG"}}}]}"""))
        assertEquals(listOf("b","a"),s.nodes.map { it["uuid"].str }); assertEquals(30.0,s.statuses["a"]["cpu"].num!!,0.0001)
        assertEquals(JsonNull,s.statuses["a"]["online"]); assertEquals(JsonNull,s.statuses["b"]["cpu"]); assertTrue(s.statuses["a"]["traffic_unlimited"].bool)
    }
    @Test fun telemetryDoesNotTurnUnknownIntoZeroAndPingIsEqualWeight() {
        assertEquals("—",bytes(null)); assertNull(ratio(10.0,0.0)); assertNull(trafficUsed(Empty,json("net_total_up" to 100,"net_total_down" to 200)))
        assertEquals(300.0,trafficUsed(json("traffic_limit_type" to "sum"),json("net_total_up" to 100,"net_total_down" to 200))!!,0.0)
        val summary = PingSummary.from(parse("""{"a":{"latest":30,"loss":0},"b":{"latest":90,"loss":10},"c":{"latest":-1,"loss":100},"d":{"loss":999}}"""))
        assertEquals(60.0,summary.latency!!,0.0); assertEquals(110.0/3,summary.loss!!,0.0001); assertFalse(summary.allTimedOut)
        assertTrue(PingSummary.from(parse("""{"a":{"latest":-1}}""")).allTimedOut)
    }
    @Test fun preferencesPreserveBackendOrderNewNodesAndHiddenNodes() {
        val nodes = listOf("a","b","c").map { json("uuid" to it) }
        val prefs = DashboardPreferences(listOf("missing","b","b"),setOf("a"))
        assertEquals(listOf("b","a","c"),prefs.ordered(nodes).map { it["uuid"].str })
        assertEquals(listOf("b","c"),prefs.visible(nodes).map { it["uuid"].str })
    }
    @Test fun managementPreservesUnknownFieldsAndRefusesConcurrentEdits() {
        val old = json("id" to 1,"name" to "before","clients" to listOf("a","b"),"future" to "keep")
        val updated = mergeRule(old,old,mapOf("name" to value("after")))
        assertEquals("keep",updated["future"].str)
        assertThrows(ApiError::class.java) { mergeRule(json("name" to "someone else"),old,mapOf("name" to value("after"))) }
        verify(json("clients" to listOf("b","a")),mapOf("clients" to value(listOf("a","b"))))
        verify(parse("""{"threshold":80,"interval":60.0}"""),json("threshold" to 80.0,"interval" to 60).obj)
        assertThrows(ApiError::class.java) { verify(json("enable" to false),mapOf("enable" to value(true))) }
    }
    @Test fun connectionGateRejectsDelayedCompletionsFromPreviousPanel() = runTest {
        val delayed = CompletableDeferred<Snapshot>(); val applied = mutableListOf<String>()
        val gate = ConnectionGate { panel -> object: PanelApi {
            override suspend fun snapshot() = if(panel.name == "old") delayed.await() else Snapshot(listOf(json("name" to panel.name)),Empty)
            override suspend fun refresh(nodes: List<J>) = snapshot()
        } }
        val old = launch(start = CoroutineStart.UNDISPATCHED) { gate.load(Panel(name = "old",address = "https://a.test")) { applied += "old" } }
        gate.invalidate(); gate.load(Panel(name = "new",address = "https://b.test")) { applied += it.nodes.first()["name"].str }
        delayed.complete(Snapshot(emptyList(),Empty)); old.join()
        assertEquals(listOf("new"),applied)
    }
    @Test fun connectionGateIgnoresLateFailureAfterSwitchingAwayAndBack() = runTest {
        val delayed = CompletableDeferred<Snapshot>()
        var calls = 0
        val gate = ConnectionGate { object: PanelApi {
            override suspend fun snapshot() = if(++calls == 1) delayed.await() else Snapshot(emptyList(),Empty)
            override suspend fun refresh(nodes: List<J>) = snapshot()
        } }
        val panel = Panel(name = "A",address = "https://a.test")
        val old = launch(start = CoroutineStart.UNDISPATCHED) { gate.load(panel) { fail("Old data applied") } }
        gate.invalidate(); gate.load(panel) {}
        delayed.completeExceptionally(ApiError("late error")); old.join()
        assertFalse(old.isCancelled)
    }
}
