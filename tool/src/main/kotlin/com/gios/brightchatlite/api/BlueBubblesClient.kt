package com.gios.brightchatlite.api

import com.gios.brightchatlite.api.BlueBubblesJson.str
import com.gios.brightchatlite.model.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Carries the HTTP status so callers can treat 401/403 as a bad password. */
class ApiException(val code: Int, message: String) : IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

data class ServerInfo(val privateApiReady: Boolean, val serverVersion: String)

/**
 * BlueBubbles Server REST client over ktor (OkHttp engine). Auth is the server password
 * passed as the `password` query parameter on every call, exactly as BrightChat's
 * `BlueBubblesApi` does. Every endpoint and body here is one BrightChat already calls; see
 * the README for the list.
 */
class BlueBubblesClient(baseUrl: String, private val password: String) {

    private val base = baseUrl.trimEnd('/')

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun url(path: String, query: String? = null): String = buildString {
        append(base).append(path)
        append("?password=").append(enc(password))
        if (query != null) append('&').append(query)
    }

    private suspend fun call(method: HttpMethod, path: String, body: JsonObject? = null, query: String? = null, what: String): String {
        val resp = http.request(url(path, query)) {
            this.method = method
            header("Accept", "application/json")
            if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val text = resp.bodyAsText()
        val code = resp.status.value
        if (code !in 200..299) throw ApiException(code, "$what failed ($code)")
        return text
    }

    /** `GET /api/v1/server/info`. Throws on a bad password or an unreachable server. */
    suspend fun serverInfo(): ServerInfo {
        val text = call(HttpMethod.Get, "/api/v1/server/info", what = "server info")
        val data = BlueBubblesJson.dataObject(text)
        return ServerInfo(
            privateApiReady = BlueBubblesJson.privateApiReady(data),
            serverVersion = data?.str("server_version").orEmpty(),
        )
    }

    /**
     * `POST /api/v1/message/query`, newest first, with the chat, participants and
     * attachment metadata embedded. One page of the sweep the conversation list is built from.
     */
    suspend fun messageSweep(limit: Int, offset: Int = 0): List<JsonObject> {
        val body = buildJsonObject {
            put("limit", limit.coerceAtMost(MAX_QUERY_LIMIT))
            put("offset", offset)
            put("with", JsonArray(listOf(JsonPrimitive("chats"), JsonPrimitive("chats.participants"), JsonPrimitive("attachment"))))
            put("sort", "DESC")
        }
        return BlueBubblesJson.dataArray(call(HttpMethod.Post, "/api/v1/message/query", body, what = "message/query"))
    }

    /** `GET /api/v1/chat/:guid/message`, newest first. */
    suspend fun messages(chatGuid: String, limit: Int = 50, offset: Int = 0): List<ChatMessage> {
        val query = "with=handle,attachment&sort=DESC&limit=$limit&offset=$offset"
        val text = call(HttpMethod.Get, "/api/v1/chat/${enc(chatGuid)}/message", query = query, what = "message query")
        return BlueBubblesJson.dataArray(text).map { BlueBubblesJson.parseMessage(it, chatGuid) }
    }

    /**
     * `POST /api/v1/message/text`. [method] is `private-api` when the server's Private API
     * is live, else `apple-script`. The client [tempGuid] is echoed back so the optimistic
     * row can be reconciled.
     */
    suspend fun sendText(chatGuid: String, text: String, tempGuid: String, method: String): ChatMessage {
        val body = buildJsonObject {
            put("chatGuid", chatGuid)
            put("tempGuid", tempGuid)
            put("message", text)
            put("method", method)
        }
        val resp = call(HttpMethod.Post, "/api/v1/message/text", body, what = "send")
        return BlueBubblesJson.dataObject(resp)?.let { BlueBubblesJson.parseMessage(it, chatGuid) }
            ?: ChatMessage(tempGuid, text, System.currentTimeMillis(), fromMe = true, sender = null, chatGuid = chatGuid)
    }

    /** `POST /api/v1/chat/:guid/read`. Private API only; callers gate on [ServerInfo]. */
    suspend fun markRead(chatGuid: String) {
        call(HttpMethod.Post, "/api/v1/chat/${enc(chatGuid)}/read", what = "mark read")
    }

    /**
     * `POST /api/v1/chat/new`: creates a 1:1 chat by sending its first message, over
     * AppleScript as BrightChat does for a single address. Returns the new chat's guid.
     */
    suspend fun newChat(address: String, text: String): String {
        val body = buildJsonObject {
            put("addresses", JsonArray(listOf(JsonPrimitive(address))))
            put("message", text)
            put("service", "iMessage")
            put("method", "apple-script")
        }
        val resp = call(HttpMethod.Post, "/api/v1/chat/new", body, what = "new chat")
        return BlueBubblesJson.dataObject(resp)?.str("guid")?.takeIf { it.isNotBlank() }
            ?: throw IOException("new chat: no guid returned")
    }

    /** `GET /api/v1/contact`: the Mac's address book as (address, name) pairs. */
    suspend fun contacts(): List<Pair<String, String>> {
        val text = call(HttpMethod.Get, "/api/v1/contact", what = "contact")
        return BlueBubblesJson.contacts(BlueBubblesJson.dataArray(text))
    }

    /**
     * `GET /api/v1/attachment/:guid/download`: the raw bytes. Refuses anything over
     * [maxBytes] when the server states a length, so a video cannot exhaust memory.
     */
    suspend fun downloadAttachment(guid: String, maxBytes: Long): ByteArray {
        val resp = http.request(url("/api/v1/attachment/${enc(guid)}/download")) { method = HttpMethod.Get }
        val code = resp.status.value
        if (code !in 200..299) throw ApiException(code, "attachment download failed ($code)")
        val length = resp.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > maxBytes) throw IOException("attachment too large ($length bytes)")
        return resp.readRawBytes()
    }

    companion object {
        /** The server rejects larger limits (`max:1000`). */
        const val MAX_QUERY_LIMIT = 1000

        /** One client for the process: OkHttp pools connections across calls. */
        private val http: HttpClient by lazy {
            HttpClient(OkHttp) {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                    requestTimeoutMillis = 60_000
                }
            }
        }
    }
}
