package com.khixang.panel.android

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

typealias J = JsonElement
val Empty = JsonObject(emptyMap())
operator fun J?.get(key: String): J = (this as? JsonObject)?.get(key) ?: JsonNull
val J?.obj: Map<String, J> get() = (this as? JsonObject) ?: emptyMap()
val J?.arr: List<J> get() = (this as? JsonArray) ?: emptyList()
val J?.str: String get() = (this as? JsonPrimitive)?.contentOrNull ?: ""
val J?.num: Double? get() = (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
val J?.bool: Boolean get() = (this as? JsonPrimitive)?.booleanOrNull == true
fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { it.first to value(it.second) })
fun value(v: Any?): J = when(v) {
    null -> JsonNull; is J -> v; is String -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v); is Iterable<*> -> JsonArray(v.map(::value)); else -> error("Unsupported JSON type")
}
fun parse(text: String): J = Json.parseToJsonElement(text)
// Swift JSON stores all numbers as Double; 80 and 80.0 must read back equally.
fun jsonEqual(a: J, b: J): Boolean = when {
    a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a.num != null && b.num != null -> a.num == b.num
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key,v) -> jsonEqual(v,b.getValue(key)) }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEqual(a[it],b[it]) }
    else -> a == b
}
fun display(v: J): String = v.str.ifEmpty { "—" }
fun percent(v: Double?): String = v?.let { String.format(Locale.getDefault(), "%.1f%%", it) } ?: "—"
fun bytes(v: Double?): String {
    if (v == null || !v.isFinite()) return "—"
    var size = v.coerceAtLeast(0.0); var unit = 0
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return String.format(Locale.getDefault(), if (unit == 0) "%.0f %s" else "%.1f %s", size, units[unit])
}
fun speed(v: Double?) = if(v == null) "—" else bytes(v) + "/s"
fun time(value: J): Instant? = runCatching { Instant.parse(value.str) }.getOrNull()
    ?: runCatching { OffsetDateTime.parse(value.str).toInstant() }.getOrNull()
fun ratio(used: Double?, total: Double?): Double? = if (used != null && total != null && total > 0) used / total else null
fun trafficUsed(node: J, status: J): Double? {
    status["traffic_used"].num?.let { return it }
    val up = status["net_total_up"].num ?: return null
    val down = status["net_total_down"].num ?: return null
    return when(node["traffic_limit_type"].str) { "up" -> up; "down" -> down; "min" -> minOf(up, down); "max" -> maxOf(up, down); "sum" -> up + down; else -> null }
}
data class PingSummary(val latency: Double?, val loss: Double?, val allTimedOut: Boolean) {
    companion object {
        fun from(targets: J): PingSummary {
            val samples = targets.obj.values.mapNotNull { it["latest"].num }
            val successes = samples.filter { it >= 0 }
            val losses = targets.obj.values.mapNotNull { it["loss"].num?.takeIf { n -> n in 0.0..100.0 } }
            fun mean(values: List<Double>) = values.takeIf { it.isNotEmpty() }?.sumOf { it / values.size }
            return PingSummary(mean(successes), mean(losses), successes.isEmpty() && samples.any { it < 0 })
        }
    }
}
enum class Backend(val title: String) { KOMARI("Komari"), NEZHA("哪吒 V1"), NEZHA_V0("哪吒 V0"), DSTATUS("DStatus") }
data class Panel(val id: String = UUID.randomUUID().toString(), val name: String, val address: String,
                 val backend: Backend = Backend.KOMARI, val allowHTTP: Boolean = false) {
    fun encoded() = json("id" to id, "name" to name, "address" to address, "backend" to backend.name, "allowHTTP" to allowHTTP)
    companion object { fun decode(j: J) = Panel(j["id"].str, j["name"].str, j["address"].str, Backend.valueOf(j["backend"].str), j["allowHTTP"].bool) }
}
data class Snapshot(val nodes: List<J>, val statuses: J)
data class DashboardPreferences(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
    fun ordered(nodes: List<J>): List<J> {
        val ranks = order.distinct().withIndex().associate { it.value to it.index }
        return nodes.distinctBy { it["uuid"].str }.sortedBy { ranks[it["uuid"].str] ?: Int.MAX_VALUE }
    }
    fun visible(nodes: List<J>) = ordered(nodes).filter { it["uuid"].str !in hidden }
}
