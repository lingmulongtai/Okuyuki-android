package com.okuyuki.android.host

import com.okuyuki.protocol.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.InputStream

data class Original(val size: Long, val open: () -> InputStream)
interface HostPhotoSource {
    suspend fun photos(): List<Photo>
    suspend fun thumbnail(id: String): ByteArray?
    suspend fun original(id: String): Original?
}

fun Application.photoApi(
    source: HostPhotoSource,
    authority: PairingAuthority,
    hello: suspend () -> Hello,
    events: Flow<Event>,
    connected: (Int) -> Unit = {},
    viewing: (String?) -> Unit = {},
    tv: TvHost? = null,
) {
    install(HostHeaders)
    install(ContentNegotiation) { json(Protocol.json) }
    install(WebSockets) { maxFrameSize = 8192; pingPeriodMillis = 15_000; timeoutMillis = 30_000 }
    val active = java.util.concurrent.atomic.AtomicInteger(0)
    suspend fun permitted(call: ApplicationCall): Boolean {
        if (authority.authorized(call.request.headers[HttpHeaders.Authorization])) return true
        call.respond(HttpStatusCode.Unauthorized, ApiError("スマホで接続を許可してください。"))
        return false
    }
    routing {
        options("{...}") { call.respondPreflight() }
        get("/v1/hello") { call.respond(hello()) }
        post("/v1/pair") {
            try {
                val text = call.receiveSmallText() ?: return@post
                call.respond(authority.beginOrPoll(Protocol.json.decodeFromString<PairRequest>(text)))
            }
            catch (_: Exception) { call.respond(HttpStatusCode.BadRequest, ApiError("接続要求を確認できませんでした。")) }
        }
        get("/v1/library") {
            if (!permitted(call)) return@get
            val photos = source.photos()
            val after = call.request.queryParameters["after"]
            val offset = if (after == null) 0 else photos.indexOfFirst { it.id == after }.let { index ->
                if (index < 0) { call.respond(HttpStatusCode.BadRequest, ApiError("一覧が変わりました。更新してください。")); return@get }
                index + 1
            }
            val page = photos.drop(offset).take(Protocol.PAGE_SIZE)
            call.respond(LibraryPage(page, page.lastOrNull()?.id?.takeIf { offset + page.size < photos.size }))
        }
        get("/v1/items/{id}/thumb") {
            if (!permitted(call)) return@get
            val id = call.parameters["id"] ?: return@get
            val bytes = source.thumbnail(id)
            if (bytes == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(bytes, ContentType.Image.JPEG)
        }
        get("/v1/items/{id}/original") {
            if (!permitted(call)) return@get
            val original = source.original(call.parameters["id"] ?: "")
            if (original == null) { call.respond(HttpStatusCode.NotFound); return@get }
            if (original.size !in 1..Protocol.MAX_ORIGINAL_BYTES) { call.respond(HttpStatusCode.PayloadTooLarge); return@get }
            call.respondRanged(original.size, ContentType.parse("image/heic"), original.open)
        }
        webSocket("/v1/events") {
            val authorization = call.request.headers[HttpHeaders.Authorization]
            if (!authority.authorized(authorization)) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Pairing required")); return@webSocket }
            connected(active.incrementAndGet())
            val sender = launch { events.collect { send(Protocol.json.encodeToString(it)) } }
            try {
                for (frame in incoming) {
                    if (!authority.authorized(authorization)) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Pairing revoked")); break }
                    if (frame is Frame.Text) {
                        val event = runCatching { Protocol.json.decodeFromString<Event>(frame.readText()) }.getOrNull() ?: continue
                        if (event.type == "viewing") viewing(event.itemId)
                    }
                }
            } finally { sender.cancel(); connected(active.decrementAndGet()) }
        }
        if (tv != null) tvApi(tv)
    }
}
