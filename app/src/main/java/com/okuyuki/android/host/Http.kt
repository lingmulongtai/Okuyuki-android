package com.okuyuki.android.host

import com.okuyuki.protocol.ApiError
import com.okuyuki.protocol.ByteRange
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The TV app runs from `file://` in the TV's browser, so every response allows any origin, and the
 * private-network preflight is answered too. Nothing is cached: photos must not stay on the TV's disk.
 */
val HostHeaders = createApplicationPlugin("HostHeaders") {
    onCall { call ->
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header("Access-Control-Allow-Private-Network", "true")
        call.response.header(HttpHeaders.AccessControlExposeHeaders, "Content-Length, Content-Range, Accept-Ranges")
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }
}

/** Answers a CORS preflight for any route. */
suspend fun ApplicationCall.respondPreflight() {
    response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, HEAD, OPTIONS")
    response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type, Range")
    response.header(HttpHeaders.AccessControlMaxAge, "600")
    respond(HttpStatusCode.NoContent)
}

/** Reads a small request body as text whatever its content type, or answers 413 and returns null. */
suspend fun ApplicationCall.receiveSmallText(limit: Int = 4096): String? {
    if ((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > limit) {
        respond(HttpStatusCode.PayloadTooLarge, ApiError("要求が大きすぎます。")); return null
    }
    val input = receiveChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val count = input.readAvailable(buffer)
        if (count < 0) break
        if (bytes.size() + count > limit) { respond(HttpStatusCode.PayloadTooLarge, ApiError("要求が大きすぎます。")); return null }
        bytes.write(buffer, 0, count)
    }
    return bytes.toString("UTF-8")
}

/** Streams [size] bytes from [open], honouring a single `Range` the way video players ask for it. */
suspend fun ApplicationCall.respondRanged(size: Long, type: ContentType, open: () -> InputStream) {
    val header = request.headers[HttpHeaders.Range]
    val range = ByteRange.parse(header, size)
    response.header(HttpHeaders.AcceptRanges, "bytes")
    if (range == null) {
        response.header(HttpHeaders.ContentRange, "bytes */$size")
        respond(HttpStatusCode.RequestedRangeNotSatisfiable); return
    }
    if (header != null) response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
    respond(object : OutgoingContent.WriteChannelContent() {
        override val contentType = type
        override val contentLength = range.length
        override val status = if (header == null) HttpStatusCode.OK else HttpStatusCode.PartialContent
        override suspend fun writeTo(channel: ByteWriteChannel) = withContext(Dispatchers.IO) {
            open().use { input ->
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
                    check(count > 0) { "File changed while reading" }
                    channel.writeFully(buffer, 0, count); remaining -= count
                }
            }
        }
    })
}

/** Starts the host on [ports] in order; the first that binds wins, and 0 lets the system choose. */
fun startHost(ports: List<Int>, module: Application.() -> Unit): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    var failure: Exception? = null
    for (port in ports) {
        val server = embeddedServer(CIO, host = "0.0.0.0", port = port) { module() }
        try {
            server.start(wait = false)
            return server
        } catch (error: Exception) {
            failure = error
            runCatching { server.stop(0, 0) }
        }
    }
    throw failure ?: IllegalArgumentException("No port to start on")
}
