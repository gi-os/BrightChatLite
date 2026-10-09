package com.gios.brightchatlite.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A server address and password read off a setup QR code. */
data class ServerSetup(val url: String, val password: String)

/**
 * Reads a BlueBubbles server setup out of a scanned QR code.
 *
 * Accepted shapes:
 * - The BlueBubbles Server's own "connect a client" QR: a JSON array whose first element is
 *   the password and second the server URL (`["pw","https://host",...]`; later elements
 *   carry Firebase config this tool does not use).
 * - A JSON object with `url`/`serverUrl`/`address` and `password`/`guid`.
 * - A URL carrying the password as `?password=` (or `?guid=`), the form BlueBubbles' own API
 *   uses for auth, for example `http://100.64.0.2:1234/?password=pw`.
 *
 * Returns null for anything else.
 */
object SetupQr {

    private val json = Json { isLenient = true }

    fun parse(raw: String): ServerSetup? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("[")) return fromArray(text)
        if (text.startsWith("{")) return fromObject(text)
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            return fromUrl(text)
        }
        return null
    }

    private fun fromArray(text: String): ServerSetup? {
        val arr = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        val strings = arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        val password = strings.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val url = strings.getOrNull(1)?.let(::normalizeUrl) ?: return null
        return ServerSetup(url, password)
    }

    private fun fromObject(text: String): ServerSetup? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun field(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            (obj[n] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        val url = field("url", "serverUrl", "server_url", "address", "server")?.let(::normalizeUrl) ?: return null
        val password = field("password", "guid", "pass") ?: return null
        return ServerSetup(url, password)
    }

    private fun fromUrl(text: String): ServerSetup? {
        val base = text.substringBefore('?').substringBefore('#')
        val query = text.substringAfter('?', "").substringBefore('#')
        val password = query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq).lowercase() to pair.substring(eq + 1)
        }.firstOrNull { it.first == "password" || it.first == "guid" }?.second
            ?.let { decode(it) }
            ?.takeIf { it.isNotBlank() } ?: return null
        val url = normalizeUrl(base) ?: return null
        return ServerSetup(url, password)
    }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * Cleans a typed or scanned server address: trims it, adds `http://` when no scheme is
     * given (Tailscale and LAN addresses are usually plain HTTP), and drops trailing slashes
     * and a trailing `/api/v1`. Null when it is not an address at all.
     */
    fun normalizeUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = "http://$s"
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        s = scheme + "://" + s.substringAfter("://")
        s = s.trimEnd('/')
        if (s.endsWith("/api/v1", ignoreCase = true)) s = s.dropLast("/api/v1".length).trimEnd('/')
        val host = s.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isBlank()) return null
        return s
    }
}
