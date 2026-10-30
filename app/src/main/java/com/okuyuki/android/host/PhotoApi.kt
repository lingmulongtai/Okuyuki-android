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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class Original(val size: Long, val open: () -> InputStream)
interface HostPhotoSource {
    suspend fun photos(): List<Photo>
    suspend fun thumbnail(id: String): ByteArray?
    suspend fun original(id: String): Original?
}

/** Only the item explicitly selected for this session is available here. */
interface PanoramaPhotoSource {
    suspend fun current(): PanoramaPhotoShare?
    suspend fun open(id: String): InputStream?
}

/** One seekable video explicitly shared for the current session. */
interface PanoramaVideoSource {
    suspend fun current(): PanoramaVideoShare?
    suspend fun open(id: String, offset: Long): InputStream?
    suspend fun motion(id: String): ByteArray? = null
}

internal suspend fun copyPanorama(input: InputStream, output: OutputStream, size: Long, allowed: suspend () -> Boolean) = coroutineScope {
    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    fun closeInput() { if (closed.compareAndSet(false, true)) runCatching { input.close() } }
    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            while (isActive) {
                delay(250)
                if (!allowed()) break
            }
        } finally { closeInput() }
    }
    try {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            check(allowed()) { "Share ended" }
            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            check(count > 0) { "Shared image changed" }
            currentCoroutineContext().ensureActive()
            check(allowed()) { "Share ended" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    } finally {
        watcher.cancel()
        closeInput()
    }
}

fun Application.photoApi(
    source: HostPhotoSource,
    authority: PairingAuthority,
    hello: suspend () -> Hello,
    events: Flow<Event>,
    connected: (Int) -> Unit = {},
    viewing: (String?) -> Unit = {},
    panorama: PanoramaPhotoSource? = null,
    video: PanoramaVideoSource? = null,
) {
    install(ContentNegotiation) { json(Protocol.json) }
    install(WebSockets) { maxFrameSize = 8192; pingPeriodMillis = 15_000; timeoutMillis = 30_000 }
    val active = java.util.concurrent.atomic.AtomicInteger(0)
    val panoramaTransfers = Semaphore(3)
    val videoTransfers = Semaphore(3)
    val motionTransfers = Semaphore(2)
    suspend fun permitted(call: ApplicationCall): Boolean {
        if (authority.authorized(call.request.headers[HttpHeaders.Authorization])) return true
        call.respond(HttpStatusCode.Unauthorized, ApiError("スマホ側で接続を許可してください"))
        return false
    }
    routing {
        get("/v1/hello") { call.respond(hello()) }
        post("/v1/pair") {
            if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > 4096) {
                call.respond(HttpStatusCode.PayloadTooLarge); return@post
            }
            try {
                val input = call.receiveChannel()
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.readAvailable(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 4096) { call.respond(HttpStatusCode.PayloadTooLarge); return@post }
                    bytes.write(buffer, 0, count)
                }
                call.respond(authority.beginOrPoll(Protocol.json.decodeFromString<PairRequest>(bytes.toString("UTF-8"))))
            }
            catch (_: Exception) { call.respond(HttpStatusCode.BadRequest, ApiError("接続要求を確認できませんでした")) }
        }
        get("/v1/library") {
            if (!permitted(call)) return@get
            val photos = source.photos()
            val after = call.request.queryParameters["after"]
            val offset = if (after == null) 0 else photos.indexOfFirst { it.id == after }.let { index ->
                if (index < 0) { call.respond(HttpStatusCode.BadRequest, ApiError("一覧が変わりました。更新してください")); return@get }
                index + 1
            }
            val page = photos.drop(offset).take(Protocol.PAGE_SIZE)
            call.respond(LibraryPage(page, page.lastOrNull()?.id?.takeIf { offset + page.size < photos.size }))
        }
        get("/v2/panorama/photo") {
            if (!permitted(call)) return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val item = panorama?.current()?.takeIf { it.isValid() }
            if (item == null) call.respond(HttpStatusCode.NoContent) else call.respond(item)
        }
        get("/v2/panorama/photo/{id}") {
            if (!permitted(call)) return@get
            val source = panorama ?: run { call.respond(HttpStatusCode.NotFound); return@get }
            val item = source.current()?.takeIf { it.isValid() && it.id == call.parameters["id"] }
                ?: run { call.respond(HttpStatusCode.NotFound); return@get }
            val authorization = call.request.headers[HttpHeaders.Authorization]
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.ContentLength, item.sizeBytes.toString())
            call.respondOutputStream(ContentType.Application.OctetStream) {
                withContext(Dispatchers.IO) {
                    panoramaTransfers.withPermit {
                        check(authority.authorized(authorization) && source.current() == item) { "Share ended" }
                        val input = source.open(item.id) ?: throw java.io.FileNotFoundException()
                        copyPanorama(input, this@respondOutputStream, item.sizeBytes) {
                            authority.authorized(authorization) && source.current() == item
                        }
                    }
                }
            }
        }
        for (prefix in listOf("/v2", "/v3")) {
            val legacy = prefix == "/v2"
            get("$prefix/panorama/video") {
                if (!permitted(call)) return@get
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val item = video?.current()?.takeIf { it.isValid() && (!legacy || it.format == PanoramaVideoFormat.EQUIRECTANGULAR_MP4) }
                if (item == null) call.respond(HttpStatusCode.NoContent) else call.respond(item)
            }
            get("$prefix/panorama/video/{id}") {
                if (!permitted(call)) return@get
                val source = video ?: run { call.respond(HttpStatusCode.NotFound); return@get }
                val item = source.current()?.takeIf { it.isValid() && it.id == call.parameters["id"] && (!legacy || it.format == PanoramaVideoFormat.EQUIRECTANGULAR_MP4) }
                    ?: run { call.respond(HttpStatusCode.NotFound); return@get }
                val authorization = call.request.headers[HttpHeaders.Authorization]
                val header = call.request.headers[HttpHeaders.Range]
                val range = ByteRange.parse(header, item.sizeBytes)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                if (range == null) {
                    call.response.header(HttpHeaders.ContentRange, "bytes */${item.sizeBytes}")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable); return@get
                }
                if (header != null) call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${item.sizeBytes}")
                call.response.header(HttpHeaders.ContentLength, range.length.toString())
                call.respondOutputStream(ContentType.Video.MP4, if (header == null) HttpStatusCode.OK else HttpStatusCode.PartialContent) {
                    withContext(Dispatchers.IO) {
                        videoTransfers.withPermit {
                            check(authority.authorized(authorization) && source.current() == item) { "Share ended" }
                            val input = source.open(item.id, range.first) ?: throw java.io.FileNotFoundException()
                            copyPanorama(input, this@respondOutputStream, range.length) {
                                authority.authorized(authorization) && source.current() == item
                            }
                        }
                    }
                }
            }
        }
        get("/v3/panorama/video/{id}/motion") {
            if (!permitted(call)) return@get
            val source = video ?: run { call.respond(HttpStatusCode.NotFound); return@get }
            val item = source.current()?.takeIf {
                it.isValid() && it.id == call.parameters["id"] && it.format != PanoramaVideoFormat.EQUIRECTANGULAR_MP4
            } ?: run { call.respond(HttpStatusCode.NotFound); return@get }
            val bytes = source.motion(item.id)
                ?.takeIf { it.size in (OsmoMotionCodec.HEADER_BYTES + OsmoMotionCodec.FRAME_BYTES)..OsmoMotionCodec.MAX_BYTES }
                ?: run { call.respond(HttpStatusCode.NotFound); return@get }
            val authorization = call.request.headers[HttpHeaders.Authorization]
            if (!authority.authorized(authorization) || source.current() != item) {
                call.respond(HttpStatusCode.NotFound); return@get
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.ContentLength, bytes.size.toString())
            call.respondOutputStream(ContentType.Application.OctetStream) {
                withContext(Dispatchers.IO) {
                    motionTransfers.withPermit {
                        copyPanorama(bytes.inputStream(), this@respondOutputStream, bytes.size.toLong()) {
                            authority.authorized(authorization) && source.current() == item
                        }
                    }
                }
            }
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
            val header = call.request.headers[HttpHeaders.Range]
            val range = ByteRange.parse(header, original.size)
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            if (range == null) {
                call.response.header(HttpHeaders.ContentRange, "bytes */${original.size}")
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable); return@get
            }
            if (header != null) call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${original.size}")
            call.response.header(HttpHeaders.ContentLength, range.length.toString())
            call.respondOutputStream(ContentType.parse("image/heic"), if (header == null) HttpStatusCode.OK else HttpStatusCode.PartialContent) {
                withContext(Dispatchers.IO) {
                    original.open().use { input ->
                        var skip = range.first
                        while (skip > 0) {
                            val skipped = input.skip(skip)
                            if (skipped == 0L) { check(input.read() != -1); skip-- } else skip -= skipped
                        }
                        var remaining = range.length
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            ensureActive()
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            check(count > 0) { "Original changed while reading" }
                            write(buffer, 0, count); remaining -= count
                        }
                    }
                }
            }
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
    }
}
