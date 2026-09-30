package com.khixang.panel.android

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiError(val text: String, val detail: Int? = null): IOException(text)
fun safeError(e: Throwable): String = when(e) {
    is ApiError -> t(e.text) + (e.detail?.let { " ($it)" } ?: "")
    is java.net.SocketTimeoutException, is TimeoutCancellationException -> t("服务器响应超时。")
    else -> t("无法安全连接服务器，请检查地址、网络与证书。")
}
interface PanelApi { suspend fun snapshot(): Snapshot; suspend fun refresh(nodes: List<J>): Snapshot }
class Api(val panel: Panel, private val key: String, client: OkHttpClient? = null): PanelApi {
    private val base = validateAddress(panel.address, panel.allowHTTP)
    private val http = (client?.newBuilder() ?: OkHttpClient.Builder())
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .cache(null).cookieJar(CookieJar.NO_COOKIES).build()
    init { if (panel.backend != Backend.DSTATUS && (key.isEmpty() || key.any { it.code !in 33..126 })) throw ApiError("API key 为空或包含不支持的字符。") }

    fun request(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/" + path)
        query.forEach { (name, value) -> url.addQueryParameter(name, value) }
        return Request.Builder().url(url.build()).header("Accept", "application/json").apply {
            if (panel.backend != Backend.DSTATUS) header("Authorization", if (panel.backend == Backend.NEZHA_V0) key else "Bearer $key")
        }
    }
    private suspend fun execute(request: Request): J = suspendCancellableCoroutine { c ->
        val call = http.newCall(request)
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val parsed = response.use {
                        if (!it.isSuccessful) throw ApiError("服务器返回 HTTP 错误。", it.code)
                        val body = it.body ?: throw ApiError("服务器返回了无效响应。")
                        if (body.contentLength() > LIMIT) throw ApiError("服务器返回了无效响应。")
                        // Bound the stream before allocating/decoding, including chunked responses.
                        val source = body.source()
                        source.request(LIMIT + 1)
                        if (source.buffer.size > LIMIT) throw ApiError("服务器返回了无效响应。")
                        runCatching { parse(source.readUtf8()) }.getOrElse { throw ApiError("服务器返回了无效响应。") }
                    }
                    if (c.isActive) c.resume(parsed)
                } catch (e: Exception) { if (c.isActive) c.resumeWithException(e) }
            }
        })
    }
    suspend fun rpc(method: String, params: J = Empty): J {
        val id = UUID.randomUUID().toString()
        val body = json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params)
        val response = execute(request("api/rpc2").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        return rpcResult(response, id)
    }
    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): J {
        val j = execute(request(path, query).build())
        if (j["success"] == JsonPrimitive(false) || (panel.backend == Backend.NEZHA_V0 && j["code"] != JsonNull && j["code"].num != 0.0)) throw ApiError("服务器返回了无效响应。")
        return j
    }
    private suspend fun nezhaStream(): J = withTimeout(20_000) {
        suspendCancellableCoroutine { c ->
            val socket = http.newWebSocket(request("api/v1/ws/server").build(), object: WebSocketListener() {
                fun received(ws: WebSocket, text: String) {
                    try {
                        if (text.toByteArray().size > LIMIT) throw ApiError("服务器返回了无效响应。")
                        val j = parse(text)
                        if (j["servers"] !is JsonArray) throw ApiError("服务器返回了无效响应。")
                        if (c.isActive) c.resume(j)
                    } catch (e: Exception) { if (c.isActive) c.resumeWithException(ApiError("服务器返回了无效响应。")) }
                    finally { ws.cancel() }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = received(webSocket, text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = received(webSocket, bytes.utf8())
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); if (c.isActive) c.resumeWithException(t) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (c.isActive) c.resumeWithException(ApiError("服务器返回了无效响应。")) }
            })
            c.invokeOnCancellation { socket.cancel() }
        }
    }
    override suspend fun snapshot(): Snapshot = when(panel.backend) {
        Backend.KOMARI -> {
            val nodes = rpc("admin:listClients")
            if (nodes !is JsonArray) throw ApiError("服务器返回了无效响应。")
            Snapshot(nodes.arr.sortedWith(compareByDescending<J> { it["weight"].num ?: 0.0 }.thenBy { it["name"].str }), rpc("common:getNodesLatestStatus"))
        }
        Backend.NEZHA -> normalizeNezha(nezhaStream(), false)
        Backend.NEZHA_V0 -> {
            val j = get("api/v1/server/details")
            if (j["result"] !is JsonArray) throw ApiError("服务器返回了无效响应。")
            normalizeNezha(j, true)
        }
        Backend.DSTATUS -> coroutineScope {
            val live = async { get("api/allnode_status") }; val inventory = async { get("api/servers") }
            val a = live.await(); val b = inventory.await()
            if (a["data"] !is JsonObject || b["data"] !is JsonArray) throw ApiError("服务器返回了无效响应。")
            normalizeDStatus(a, b)
        }
    }
    override suspend fun refresh(nodes: List<J>): Snapshot = if (panel.backend == Backend.KOMARI) Snapshot(nodes, rpc("common:getNodesLatestStatus")) else snapshot()
    suspend fun history(id: String, field: String, hours: Int): List<J> {
        if (id.isEmpty() || '/' in id || id in listOf(".", "..")) throw ApiError("RPC 参数必须为对象或数组。")
        val escaped = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val start = Instant.now().minusSeconds(hours * 3600L)
        return when(panel.backend) {
            Backend.KOMARI -> rpc("public:getRecordsByUUID", json("uuid" to id, "hours" to hours.toString(), "load_type" to "all"))["records"].arr
            Backend.NEZHA_V0 -> if (field != "latency") emptyList() else get("api/v1/monitor/$escaped")["result"].arr.flatMap { m ->
                m["created_at"].arr.mapIndexedNotNull { i, at ->
                    val n = at.num ?: return@mapIndexedNotNull null
                    m["avg_delay"].arr.getOrNull(i)?.let { json("time" to Instant.ofEpochSecond(n.toLong()).toString(), "latency" to it, "series" to m["monitor_name"]) }
                }
            }
            Backend.NEZHA -> {
                val metric = mapOf("ram" to "memory", "net_in" to "net_in_speed", "net_out" to "net_out_speed")[field] ?: field
                get("api/v1/server/$escaped/metrics", mapOf("metric" to metric, "period" to "1d"))["data"]["data_points"].arr.mapNotNull { p ->
                    p["ts"].num?.let { json("time" to Instant.ofEpochMilli(it.toLong()).toString(), field to p["value"]) }
                }
            }
            Backend.DSTATUS -> if (field == "disk") emptyList() else {
                val metric = mapOf("ram" to "mem", "net_in" to "ibw", "net_out" to "obw")[field] ?: field
                val data = get("api/stats/$escaped/bandwidth/history", mapOf("range" to if(hours == 1) "1h" else "24h", "fields" to metric))["data"]
                data["timestamps"].arr.mapIndexedNotNull { i, at ->
                    val n = at.num ?: return@mapIndexedNotNull null
                    val v = data[metric].arr.getOrNull(i) ?: return@mapIndexedNotNull null
                    json("time" to Instant.ofEpochMilli(n.toLong()).toString(), field to if (field == "cpu") value(v.num?.times(100)) else v)
                }
            }
        }.filter { (time(it["time"]) ?: Instant.MIN) >= start && it[field].num != null }.sortedBy { time(it["time"]) }
    }
    companion object {
        const val LIMIT = 8L * 1024 * 1024
        fun validateAddress(raw: String, allowHTTP: Boolean): HttpUrl {
            fun invalid(): Nothing = throw ApiError("请输入完整面板 URL，不允许包含账号密码、查询参数或片段。")
            if (raw.isBlank() || raw.any { it.isWhitespace() || it.isISOControl() } || '\\' in raw) invalid()
            val uri = runCatching { URI(raw) }.getOrElse { invalid() }
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.scheme?.lowercase() !in listOf("http", "https")) invalid()
            if (uri.rawPath.orEmpty().contains(Regex("%2f|%5c", RegexOption.IGNORE_CASE)) || uri.path.orEmpty().split('/').any { it == "." || it == ".." } || uri.path.orEmpty().any { it.isISOControl() }) invalid()
            val url = raw.toHttpUrlOrNull() ?: invalid()
            if (!url.isHttps && !allowHTTP) throw ApiError("默认要求 HTTPS。仅在受信任网络明确开启不安全 HTTP。")
            return url
        }
        fun rpcResult(j: J, id: String): J {
            if (j !is JsonObject || j["jsonrpc"] != JsonPrimitive("2.0")) throw ApiError("服务器返回了无效响应。")
            if (j["id"] != JsonPrimitive(id)) throw ApiError("RPC 响应与请求不匹配。")
            val error = j.obj["error"] ?: JsonNull
            if (error != JsonNull) {
                val code = error["code"].num
                if ((j.obj["result"] ?: JsonNull) != JsonNull || code == null || code % 1 != 0.0 || code !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() || j["error"]["message"] !is JsonPrimitive || !(j["error"]["message"] as JsonPrimitive).isString) throw ApiError("服务器返回了无效响应。")
                throw ApiError("RPC 请求失败", code.toInt())
            }
            return j.obj["result"] ?: throw ApiError("服务器返回了无效响应。")
        }
    }
}

fun normalizeNezha(j: J, legacy: Boolean, now: Instant = Instant.now()): Snapshot {
    val states = mutableMapOf<String, J>()
    val nodes = j[if (legacy) "result" else "servers"].arr.map { r ->
        val id = r["id"].str; val h = r["host"]; val s = r[if(legacy) "status" else "state"]
        fun host(a: String, b: String) = h[if(legacy) b else a]
        fun state(a: String, b: String) = s[if(legacy) b else a]
        val at = if(legacy) r["last_active"].num?.let { Instant.ofEpochSecond(it.toLong()) } else time(r["last_active"])
        val age = at?.let { java.time.Duration.between(it, now).seconds }
        states[id] = json("online" to age?.let { it >= -60 && it < 15 }, "cpu" to state("cpu", "CPU"), "ram" to state("mem_used", "MemUsed"), "disk" to state("disk_used", "DiskUsed"), "load1" to state("load_1", "Load1"), "load5" to state("load_5", "Load5"), "load15" to state("load_15", "Load15"), "net_in" to state("net_in_speed", "NetInSpeed"), "net_out" to state("net_out_speed", "NetOutSpeed"), "net_total_down" to state("net_in_transfer", "NetInTransfer"), "net_total_up" to state("net_out_transfer", "NetOutTransfer"), "uptime" to state("uptime", "Uptime"))
        json("uuid" to id, "name" to r["name"], "group" to r["tag"], "os" to host("platform", "Platform"), "arch" to host("arch", "Arch"), "region" to if(legacy) host("country_code", "CountryCode") else r["country_code"], "mem_total" to host("mem_total", "MemTotal"), "disk_total" to host("disk_total", "DiskTotal"), "public_remark" to r["public_note"], "cpu_name" to host("cpu", "CPU").arr.firstOrNull())
    }
    return Snapshot(nodes, JsonObject(states))
}
fun normalizeDStatus(j: J, inventory: J): Snapshot {
    val info = inventory["data"].arr.associateBy { it["id"].str }
    val order = (j["order"].arr.map { it.str } + j["data"].obj.keys.sorted()).distinct()
    val states = mutableMapOf<String, J>()
    val nodes = order.mapNotNull { id ->
        val r = j["data"][id]; if (r == JsonNull) return@mapNotNull null
        val s = r["stat"]; val h = info[id] ?: JsonNull; val traffic = r["traffic_stats"]
        states[id] = json("online" to if (s["offline"] == JsonNull) null else !s["offline"].bool, "cpu" to s["cpu"]["multi"].num?.times(100), "ram" to s["mem"]["virtual"]["used"], "net_in" to s["net"]["delta"]["in"], "net_out" to s["net"]["delta"]["out"], "net_total_down" to s["net"]["total"]["in"], "net_total_up" to s["net"]["total"]["out"], "traffic_used" to traffic["used"], "traffic_unlimited" to traffic["unlimited"])
        json("uuid" to id, "name" to r["name"], "region" to h["data"]["location"]["code"].takeUnless { it == JsonNull }.let { it ?: h["data"]["metadata"]["region"] }, "group" to h["group_ids"].arr.joinToString(" · ") { it.str }, "mem_total" to s["mem"]["virtual"]["total"], "traffic_limit" to traffic["limit"])
    }
    return Snapshot(nodes, JsonObject(states))
}
