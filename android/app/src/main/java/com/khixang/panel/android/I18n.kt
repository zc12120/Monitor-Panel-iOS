package com.khixang.panel.android

import android.content.Context
import java.util.Locale

object I18n {
    var catalogs: J = Empty
    var countries: J = Empty
    fun initialize(context: Context) {
        catalogs = parse(context.assets.open("i18n.json").bufferedReader().use { it.readText() })
        countries = parse(context.assets.open("country-codes.json").bufferedReader().use { it.readText() })
    }
}
fun t(key: String, vararg args: Any): String {
    val locale = Locale.getDefault()
    val language = when(locale.language) {
        "zh" -> if(locale.script == "Hant" || locale.country in listOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
        "ja" -> "ja"; "ko" -> "ko"; else -> "en"
    }
    val template = I18n.catalogs[language][key].str.ifEmpty { key }
    if (args.isEmpty()) return template
    return runCatching { String.format(locale, template.replace("%@", "%s").replace("%lld", "%d").replace("%llu", "%d"), *args) }.getOrDefault(template)
}

fun countryCode(raw: String): String? {
    val points = raw.trim().codePoints().toArray()
    var code = if(points.size == 2 && points.all { it in 0x1F1E6..0x1F1FF }) points.map { (it - 0x1F1E6 + 65).toChar() }.joinToString("") else raw.trim().uppercase(Locale.ROOT)
    if(points.firstOrNull() == 0x1F3F4 && points.lastOrNull() == 0xE007F) {
        val tag = points.drop(1).dropLast(1).map { (it - 0xE0000).toChar() }.joinToString("")
        code = mapOf("gbeng" to "GB-ENG", "gbsct" to "GB-SCT", "gbwls" to "GB-WLS")[tag] ?: return null
    }
    code = mapOf("UK" to "GB", "EL" to "GR", "USA" to "US", "UAE" to "AE", "XKX" to "XK", "KOS" to "XK", "AC" to "SH-AC", "TA" to "SH-TA", "ENG" to "GB-ENG", "SCT" to "GB-SCT", "WLS" to "GB-WLS", "UNITED KINGDOM" to "GB", "UNITED STATES" to "US", "HONG KONG" to "HK", "MACAU" to "MO")[code] ?: I18n.countries[code].str.ifEmpty { code }
    return code.takeIf { it.matches(Regex("[A-Z]{2,5}(-[A-Z]{2,3})?")) }
}
fun osAsset(node: J): String? {
    val rules = listOf(
        "raspbian|raspberry[ -]?pi[ -]?os" to "raspbian", "linux[ -]?mint|mint" to "mint", "manjaro" to "manjaro",
        "ubuntu|kubuntu|lubuntu|xubuntu" to "ubuntu", "debian" to "debian", "cent[ -]?os" to "centos", "rocky(?:[ -]?linux)?" to "rocky",
        "alma(?:[ -]?linux)?" to "almalinux", "oracle(?:[ -]?linux)?|ol" to "oracle", "amazon(?:[ -]?linux)?|amzn" to "amazon",
        "red[ -]?hat(?:[ -]?enterprise[ -]?linux)?|rhel" to "rhel", "fedora" to "fedora", "arch(?:[ -]?linux)?" to "arch",
        "alpine(?:[ -]?linux)?" to "alpine", "open[ -]?suse(?:[ -]?(?:leap|tumbleweed))?" to "opensuse", "suse|sles|sled" to "suse",
        "gentoo" to "gentoo", "kali(?:[ -]?linux)?" to "kali", "nix[ -]?os" to "nixos", "void(?:[ -]?linux)?" to "void",
        "free[ -]?bsd" to "freebsd", "open[ -]?bsd" to "openbsd", "net[ -]?bsd" to "netbsd",
        "windows|win32|win64|winnt|microsoft[ -]?windows" to "windows", "mac[ -]?os(?:[ -]?x)?|os[ -]?x|darwin" to "macos")
    fun find(text: String, pairs: List<Pair<String,String>>) = pairs.firstOrNull { Regex("(?i)(?<![a-z])(?:${it.first})(?![a-z])").containsMatchIn(text) }?.second
    val values = listOf("distro", "distribution", "platform", "os").map { node[it].str }
    for (text in values) find(text, rules)?.let { return "os_$it" }
    for (text in values + node["kernel_version"].str) find(text, rules.takeLast(5) + ("linux" to "linux"))?.let { return "os_$it" }
    return null
}
