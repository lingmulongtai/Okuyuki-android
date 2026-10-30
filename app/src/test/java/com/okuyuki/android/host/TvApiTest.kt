package com.okuyuki.android.host

import com.okuyuki.protocol.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class TvApiTest {
    private val noPhotos = object : HostPhotoSource {
        override suspend fun photos() = emptyList<Photo>()
        override suspend fun thumbnail(id: String) = null
        override suspend fun original(id: String): Original? = null
    }

    /** A phone with photos 1 to 130, newest first, whose stills are ready at once. */
    private class FakeMedia(size: Int = 100) : TvMediaSource {
        val items = (130 downTo 1).map { Item("$it", "photo", "IMG_$it.jpg", it * 1000L, 4000, 3000, 1000, "image/jpeg") }
        val still = ByteArray(size) { it.toByte() }
        @Volatile var asked: Capabilities? = null
        @Volatile var itemChecks = 0

        override suspend fun page(filter: String, cursor: String?): ItemPage? {
            val start = if (cursor == null) 0 else cursor.toIntOrNull() ?: return null
            val page = items.drop(start).take(Protocol.TV_PAGE_SIZE)
            val end = start + page.size
            return ItemPage(page, end.toString().takeIf { end < items.size }, items.size)
        }
        override suspend fun item(id: String): Item? { itemChecks++; return items.firstOrNull { it.id == id } }
        override suspend fun thumbnail(id: String) = item(id)?.let { byteArrayOf(1, 2, 3) }
        override suspend fun display(id: String) = item(id)?.let { byteArrayOf(4, 5, 6) }
        override suspend fun prepare(id: String, target: String, capabilities: Capabilities?): Prepared? {
            item(id) ?: return null
            asked = capabilities
            return Prepared("ready", width = 3840, height = 2160, mime = "video/mp4", codecs = "hvc1.1.6.L153.B0")
        }
        override suspend fun file(id: String, target: String, capabilities: Capabilities?) =
            item(id)?.let { MediaFile(still.size.toLong(), "video/mp4") { still.inputStream() } }
    }

    private fun approve(authority: PairingAuthority): String {
        val request = PairRequest("tv-1", "LG C5", clientKind = TV)
        val pending = authority.beginOrPoll(request)
        val peer = authority.prepareApproval(pending.requestId)!!
        assertEquals(TV, peer.kind)
        authority.setPeers(listOf(peer))
        assertTrue(authority.completeApproval(pending.requestId, peer))
        return authority.beginOrPoll(request.copy(requestId = pending.requestId)).token!!
    }

    private fun ApplicationTestBuilder.host(tv: TvHost) = application {
        photoApi(noPhotos, tv.authority, { Hello("phone", "Galaxy", "alpha", 0, protocolVersions = Protocol.VERSIONS) },
            MutableSharedFlow(), tv = tv)
    }

    private suspend fun ApplicationTestBuilder.session(token: String): String {
        val response = client.post("/v2/session") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return Protocol.json.decodeFromString<MediaKey>(response.bodyAsText()).key
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
        assertEquals(HttpStatusCode.OK, status)
        return Protocol.json.decodeFromString(bodyAsText())
    }

    /** The next event of [type], skipping heartbeats and anything else in between. */
    private suspend fun DefaultClientWebSocketSession.next(type: String): Event = withTimeout(5000) {
        var event: Event
        do {
            val frame = incoming.receive()
            event = if (frame is Frame.Text) Protocol.json.decodeFromString(frame.readText()) else Event("")
        } while (event.type != type)
        event
    }

    @Test fun `every answer allows the TV's file origin and nothing is cached`() = testApplication {
        host(TvHost(PairingAuthority(), FakeMedia(), MutableSharedFlow()))
        val hello = client.get("/v1/hello")
        assertEquals("*", hello.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("no-store", hello.headers[HttpHeaders.CacheControl])
        assertEquals(listOf(1, 2), hello.decode<Hello>().protocolVersions)
        val preflight = client.options("/v2/k/anything/library") {
            header(HttpHeaders.Origin, "null")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization,range")
            header("Access-Control-Request-Private-Network", "true")
        }
        assertEquals(HttpStatusCode.NoContent, preflight.status)
        assertEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", preflight.headers["Access-Control-Allow-Private-Network"])
        val allowed = preflight.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().split(',').map { it.trim() }
        assertTrue(allowed.toString(), allowed.containsAll(listOf("Authorization", "Content-Type", "Range")))
        val refused = client.get("/v2/k/nope/items/1")
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("*", refused.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(Protocol.json.decodeFromString<ApiError>(refused.bodyAsText()).message.isNotBlank())
    }

    @Test fun `an approved TV trades its token for a media key that opens the library`() = testApplication {
        val authority = PairingAuthority()
        host(TvHost(authority, FakeMedia(), MutableSharedFlow()))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v2/session").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v2/session") { bearerAuth("x".repeat(43)) }.status)
        val key = session(approve(authority))
        assertEquals(22, key.length)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/k/${key}x/library").status)

        val first = client.get("/v2/k/$key/library").decode<ItemPage>()
        assertEquals(60, first.items.size); assertEquals(130, first.total); assertEquals("130", first.items.first().id)
        val second = client.get("/v2/k/$key/library?cursor=${first.next}").decode<ItemPage>()
        val last = client.get("/v2/k/$key/library?filter=photo&cursor=${second.next}").decode<ItemPage>()
        assertEquals(10, last.items.size); assertNull(last.next)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v2/k/$key/library?filter=everything").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v2/k/$key/library?cursor=moved").status)

        assertEquals("7", client.get("/v2/k/$key/items/7").decode<Item>().id)
        assertEquals(HttpStatusCode.NotFound, client.get("/v2/k/$key/items/999").status)
        val thumb = client.get("/v2/k/$key/items/7/thumb")
        assertEquals("image/jpeg", thumb.headers[HttpHeaders.ContentType])
        assertArrayEquals(byteArrayOf(1, 2, 3), thumb.readRawBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), client.get("/v2/k/$key/items/7/display").readRawBytes())
        assertEquals(HttpStatusCode.NotFound, client.get("/v2/k/$key/items/999/display").status)
    }

    @Test fun `prepare uses what the TV reported and points at a still served in ranges`() = testApplication {
        val authority = PairingAuthority()
        val media = FakeMedia()
        host(TvHost(authority, media, MutableSharedFlow()))
        val key = session(approve(authority))
        val capabilities = Capabilities(Screen(1920, 1080, 3840, 2160), listOf("hevc-main10", "hevc-main"), listOf("hdr10", "hlg"))
        // The TV sends text/plain so that the browser skips the preflight.
        val reported = client.post("/v2/k/$key/capabilities") {
            contentType(ContentType.Text.Plain); setBody(Protocol.json.encodeToString(capabilities))
        }
        assertEquals(HttpStatusCode.NoContent, reported.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v2/k/$key/capabilities") { setBody("{") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v2/k/$key/items/7/prepare?target=movie").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v2/k/$key/items/999/prepare?target=still").status)

        val prepared = client.post("/v2/k/$key/items/7/prepare?target=still").decode<Prepared>()
        assertEquals(capabilities, media.asked)
        assertEquals("/v2/k/$key/items/7/still", prepared.url)
        val partial = client.get(prepared.url!!) { header(HttpHeaders.Range, "bytes=10-19") }
        assertEquals(HttpStatusCode.PartialContent, partial.status)
        assertEquals("bytes 10-19/100", partial.headers[HttpHeaders.ContentRange])
        assertEquals("video/mp4", partial.headers[HttpHeaders.ContentType])
        assertArrayEquals(media.still.copyOfRange(10, 20), partial.readRawBytes())
        val whole = client.get("/v2/k/$key/items/7/video")
        assertEquals(HttpStatusCode.OK, whole.status)
        assertEquals("bytes", whole.headers[HttpHeaders.AcceptRanges])
        assertArrayEquals(media.still, whole.readRawBytes())
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable,
            client.get("/v2/k/$key/items/7/still") { header(HttpHeaders.Range, "bytes=100-") }.status)
    }

    @Test fun `the event socket relays events and heartbeats and closes when the TV is removed`() = testApplication {
        val authority = PairingAuthority()
        val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
        val viewing = CompletableDeferred<String?>()
        val counts = CopyOnWriteArrayList<Int>()
        host(TvHost(authority, FakeMedia(), events, heartbeatMillis = 100, connected = { counts += it }, viewing = { viewing.complete(it) }))
        val key = session(approve(authority))
        val sockets = createClient { install(WebSockets) }

        // A browser cannot see why a handshake failed, so a bad key gets a policy close it can read.
        sockets.webSocket("/v2/k/wrong/events") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, withTimeout(5000) { closeReason.await() }?.code)
        }
        sockets.webSocket("/v2/k/$key/events") {
            withTimeout(5000) { events.subscriptionCount.first { it > 0 } }
            events.emit(Event("prepared", "7", "still", "ready"))
            assertEquals(Event("prepared", "7", "still", "ready"), next("prepared"))
            next("heartbeat")
            send(Protocol.json.encodeToString(Event("viewing", "7")))
            assertEquals("7", withTimeout(5000) { viewing.await() })
            assertEquals(listOf(1), counts)

            authority.setPeers(emptyList())
            withTimeout(5000) { for (frame in incoming) Unit }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, withTimeout(5000) { closeReason.await() }?.code)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/k/$key/library").status)
    }

    @Test fun `the host moves to a free port when the fixed one is taken`() {
        ServerSocket(0, 50, InetAddress.getByName("0.0.0.0")).use { taken ->
            val server = startHost(listOf(taken.localPort, 0)) { routing { get("/") { call.respondText("ok") } } }
            try {
                val port = runBlocking { server.engine.resolvedConnectors().first().port }
                assertNotEquals(taken.localPort, port)
                assertTrue(port > 0)
            } finally {
                server.stop(0, 0)
            }
        }
    }

    @Test fun `a revoked transfer does not write the next chunk`() = runBlocking {
        var permitted = true
        var written = 0
        val input = object : java.io.ByteArrayInputStream(ByteArray(100)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                permitted = false
                return count
            }
        }
        val result = runCatching { copyAllowed(input, 100, { permitted }) { _, count -> written += count } }
        assertTrue(result.isFailure)
        assertEquals(0, written)
    }

    @Test fun `a fast video transfer does not reopen the source for every chunk`() = testApplication {
        val authority = PairingAuthority()
        val media = FakeMedia(1024 * 1024)
        host(TvHost(authority, media, MutableSharedFlow()))
        val key = session(approve(authority))
        assertEquals(media.still.size, client.get("/v2/k/$key/items/7/video").readRawBytes().size)
        assertTrue("source checks: ${media.itemChecks}", media.itemChecks < 10)
    }
}
