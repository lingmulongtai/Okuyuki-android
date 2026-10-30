package com.okuyuki.android.host

import com.okuyuki.protocol.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A file the phone serves to the TV: a prepared still or video, or an original video. */
data class MediaFile(val size: Long, val mime: String, val open: () -> InputStream)

/** What the TV routes need from the phone. Production reads MediaStore; tests use fakes. */
interface TvMediaSource {
    /** One page of [filter] (`all`, `photo`, `video` or `stereo`) after [cursor], or null if the cursor is not one this phone made. */
    suspend fun page(filter: String, cursor: String?): ItemPage?
    suspend fun item(id: String): Item?
    suspend fun thumbnail(id: String): ByteArray?
    suspend fun display(id: String): ByteArray?
    /** Starts or checks the [target] (`still` or `video`) for a TV with [capabilities]; null if the item is gone. */
    suspend fun prepare(id: String, target: String, capabilities: Capabilities?): Prepared?
    /** The [target] file once [prepare] says it is ready. */
    suspend fun file(id: String, target: String, capabilities: Capabilities?): MediaFile?
}

/** Protocol v2 for TVs, next to v1 for Quest. See docs/PROTOCOL.md. */
class TvHost(
    val authority: PairingAuthority,
    val media: TvMediaSource,
    val events: Flow<Event>,
    val heartbeatMillis: Long = 15_000,
    val connected: (Int) -> Unit = {},
    val viewing: (String?) -> Unit = {},
) {
    /** What each TV reported, by the token hash of its approval. */
    internal val capabilities = ConcurrentHashMap<String, Capabilities>()
    internal val active = AtomicInteger(0)
}

private val FILTERS = setOf("all", "photo", "video", "stereo")
private val TARGETS = setOf("still", "video")
private const val KEY_INVALID = "このテレビの接続が切れました。もう一度つないでください。"
private const val ITEM_GONE = "この写真や動画はスマホから削除されました。"

fun Route.tvApi(tv: TvHost) {
    post("/v2/session") {
        val key = tv.authority.openSession(call.request.headers[HttpHeaders.Authorization])
        if (key == null) call.respond(HttpStatusCode.Unauthorized, ApiError("このテレビは許可されていません。スマホでもう一度許可してください。"))
        else call.respond(key)
    }
    route("/v2/k/{key}") {
        suspend fun RoutingContext.peer(): ApprovedPeer? = tv.authority.peerFor(call.parameters["key"]).also {
            if (it == null) call.respond(HttpStatusCode.Unauthorized, ApiError(KEY_INVALID))
        }
        suspend fun RoutingContext.item(): Item? = tv.media.item(call.parameters["id"] ?: "").also {
            if (it == null) call.respond(HttpStatusCode.NotFound, ApiError(ITEM_GONE))
        }
        post("/capabilities") {
            val peer = peer() ?: return@post
            val text = call.receiveSmallText(8192) ?: return@post
            val capabilities = runCatching { Protocol.json.decodeFromString<Capabilities>(text) }.getOrNull()
            if (capabilities == null) { call.respond(HttpStatusCode.BadRequest, ApiError("テレビの情報を読み取れませんでした")); return@post }
            tv.capabilities[peer.tokenHash] = capabilities
            call.respond(HttpStatusCode.NoContent)
        }
        get("/library") {
            peer() ?: return@get
            val filter = call.request.queryParameters["filter"] ?: "all"
            if (filter !in FILTERS) { call.respond(HttpStatusCode.BadRequest, ApiError("filter が正しくありません")); return@get }
            val page = tv.media.page(filter, call.request.queryParameters["cursor"])
            if (page == null) call.respond(HttpStatusCode.BadRequest, ApiError("一覧が変わりました。更新してください"))
            else call.respond(page)
        }
        get("/items/{id}") {
            peer() ?: return@get
            call.respond(item() ?: return@get)
        }
        get("/items/{id}/thumb") {
            peer() ?: return@get
            val bytes = tv.media.thumbnail(call.parameters["id"] ?: "")
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ApiError(ITEM_GONE)) else call.respondBytes(bytes, ContentType.Image.JPEG)
        }
        get("/items/{id}/display") {
            peer() ?: return@get
            val bytes = tv.media.display(call.parameters["id"] ?: "")
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ApiError(ITEM_GONE)) else call.respondBytes(bytes, ContentType.Image.JPEG)
        }
        post("/items/{id}/prepare") {
            val peer = peer() ?: return@post
            val id = call.parameters["id"] ?: ""
            val target = call.request.queryParameters["target"]
            if (target !in TARGETS) { call.respond(HttpStatusCode.BadRequest, ApiError("target は still か video です")); return@post }
            val prepared = tv.media.prepare(id, target!!, tv.capabilities[peer.tokenHash])
            if (prepared == null) { call.respond(HttpStatusCode.NotFound, ApiError(ITEM_GONE)); return@post }
            val key = call.parameters["key"]!!
            call.respond(if (prepared.state == "ready") prepared.copy(url = "/v2/k/$key/items/${id.encodeURLPathPart()}/$target") else prepared)
        }
        for (target in TARGETS) get("/items/{id}/$target") {
            val peer = peer() ?: return@get
            val file = tv.media.file(call.parameters["id"] ?: "", target, tv.capabilities[peer.tokenHash])
            if (file == null) { call.respond(HttpStatusCode.NotFound, ApiError("まだ準備ができていません")); return@get }
            call.respondRanged(file.size, ContentType.parse(file.mime), file.open)
        }
        webSocket("/events") {
            val key = call.parameters["key"]
            // A refused handshake is invisible to browser code, so a bad key is a policy close instead.
            if (tv.authority.peerFor(key) == null) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid key")); return@webSocket }
            tv.connected(tv.active.incrementAndGet())
            val sender = launch { tv.events.collect { send(Protocol.json.encodeToString(it)) } }
            val heartbeat = launch {
                while (isActive) {
                    delay(tv.heartbeatMillis)
                    if (tv.authority.peerFor(key) == null) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "expired")); break }
                    send(Protocol.json.encodeToString(Event("heartbeat")))
                }
            }
            val revocation = launch {
                tv.authority.changes.collect {
                    if (tv.authority.peerFor(key) == null) close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val event = runCatching { Protocol.json.decodeFromString<Event>(frame.readText()) }.getOrNull() ?: continue
                    if (event.type == "viewing") tv.viewing(event.itemId)
                }
            } finally {
                sender.cancel(); heartbeat.cancel(); revocation.cancel()
                tv.connected(tv.active.decrementAndGet())
            }
        }
    }
}
