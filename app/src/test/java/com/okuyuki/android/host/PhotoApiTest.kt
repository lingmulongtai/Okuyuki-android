package com.okuyuki.android.host

import com.okuyuki.protocol.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.async
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class PhotoApiTest {
    @Test fun `revocation closes a provider stalled in read`() = kotlinx.coroutines.runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("unexpected single byte read")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return -1
            }
            override fun close() { released.countDown() }
        }
        val output = java.io.ByteArrayOutputStream()
        val transfer = async(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { copyPanorama(input, output, 100) { allowed.get() } }
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        allowed.set(false)
        assertTrue(kotlinx.coroutines.withTimeout(3000) { transfer.await() }.isFailure)
        assertEquals(0, output.size())
    }
    @Test fun `revoked share closes input before writing an in flight chunk`() = kotlinx.coroutines.runBlocking {
        var allowed = true
        var closed = false
        val input = object : java.io.ByteArrayInputStream(ByteArray(100) { 42 }) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                allowed = false
                return count
            }
            override fun close() { closed = true; super.close() }
        }
        val output = java.io.ByteArrayOutputStream()
        try {
            copyPanorama(input, output, 100) { allowed }
            fail("revoked share must stop")
        } catch (_: IllegalStateException) { }
        assertTrue(closed)
        assertEquals(0, output.size())
    }
    @Test fun `cancelled share closes input before writing an in flight chunk`() = kotlinx.coroutines.runBlocking {
        val job = kotlinx.coroutines.Job()
        var closed = false
        val input = object : java.io.ByteArrayInputStream(ByteArray(100) { 42 }) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                job.cancel()
                return count
            }
            override fun close() { closed = true; super.close() }
        }
        val output = java.io.ByteArrayOutputStream()
        try {
            kotlinx.coroutines.withContext(job) { copyPanorama(input, output, 100) { true } }
            fail("cancelled share must stop")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(closed)
        assertEquals(0, output.size())
    }
    @Test fun `panorama photo requires approval and never enters the stereo library`() = testApplication {
        val authority = PairingAuthority()
        val bytes = ByteArray(128) { it.toByte() }
        var shared: PanoramaPhotoShare? = PanoramaPhotoShare("0123456789abcdef0123456789abcdef", bytes.size.toLong())
        val panorama = object : PanoramaPhotoSource {
            override suspend fun current() = shared
            override suspend fun open(id: String) = if (id == shared?.id) bytes.inputStream() else null
        }
        val source = object : HostPhotoSource {
            override suspend fun photos() = emptyList<Photo>()
            override suspend fun thumbnail(id: String) = null
            override suspend fun original(id: String): Original? = null
        }
        application { photoApi(source, authority, { Hello("phone", "Galaxy", "alpha", 0) }, MutableSharedFlow(), panorama = panorama) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/panorama/photo").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/panorama/photo/${shared!!.id}").status)
        val request = PairRequest("quest", "Quest")
        val pending = authority.beginOrPoll(request)
        val peer = authority.prepareApproval(pending.requestId)!!
        authority.setPeers(listOf(peer))
        assertTrue(authority.completeApproval(pending.requestId, peer))
        val token = authority.beginOrPoll(request.copy(requestId = pending.requestId)).token!!
        val current = client.get("/v2/panorama/photo") { bearerAuth(token) }
        assertEquals(shared, Protocol.json.decodeFromString<PanoramaPhotoShare>(current.bodyAsText()))
        val response = client.get("/v2/panorama/photo/${shared!!.id}") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertArrayEquals(bytes, response.readRawBytes())
        val library = client.get("/v1/library") { bearerAuth(token) }
        assertTrue(Protocol.json.decodeFromString<LibraryPage>(library.bodyAsText()).items.isEmpty())
        val oldId = shared!!.id
        shared = null
        assertEquals(HttpStatusCode.NoContent, client.get("/v2/panorama/photo") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v2/panorama/photo/$oldId") { bearerAuth(token) }.status)
        authority.setPeers(emptyList())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/panorama/photo") { bearerAuth(token) }.status)
    }
    @Test fun `video ranges require approval and stop when sharing ends`() = testApplication {
        val authority = PairingAuthority()
        val bytes = ByteArray(100) { it.toByte() }
        var shared: PanoramaVideoShare? = PanoramaVideoShare("0123456789abcdef0123456789abcdef", bytes.size.toLong())
        val video = object : PanoramaVideoSource {
            override suspend fun current() = shared
            override suspend fun open(id: String, offset: Long) =
                if (id == shared?.id) bytes.copyOfRange(offset.toInt(), bytes.size).inputStream() else null
        }
        val photos = object : HostPhotoSource {
            override suspend fun photos() = emptyList<Photo>()
            override suspend fun thumbnail(id: String) = null
            override suspend fun original(id: String): Original? = null
        }
        application { photoApi(photos, authority, { Hello("phone", "Galaxy", "alpha", 0) }, MutableSharedFlow(), video = video) }
        val path = "/v2/panorama/video/${shared!!.id}"
        assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        val request = PairRequest("quest", "Quest")
        val pending = authority.beginOrPoll(request)
        val peer = authority.prepareApproval(pending.requestId)!!
        authority.setPeers(listOf(peer))
        assertTrue(authority.completeApproval(pending.requestId, peer))
        val token = authority.beginOrPoll(request.copy(requestId = pending.requestId)).token!!
        val metadata = client.get("/v2/panorama/video") { bearerAuth(token) }
        assertEquals(shared, Protocol.json.decodeFromString<PanoramaVideoShare>(metadata.bodyAsText()))
        val part = client.get(path) { bearerAuth(token); header(HttpHeaders.Range, "bytes=10-19") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("bytes 10-19/100", part.headers[HttpHeaders.ContentRange])
        assertEquals("no-store", part.headers[HttpHeaders.CacheControl])
        assertArrayEquals(bytes.copyOfRange(10, 20), part.readRawBytes())
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable,
            client.get(path) { bearerAuth(token); header(HttpHeaders.Range, "bytes=100-") }.status)
        val lens = PanoramaLensCalibration(listOf(1200f, 1200f, 1920f, 1920f), List(5) { 0f }, listOf(1f, 0f, 0f, 0f))
        shared = shared!!.copy(format = PanoramaVideoFormat.OSMO_LRF, calibration = PanoramaStereoCalibration(lens, lens))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v3/panorama/video").status)
        assertEquals(HttpStatusCode.NoContent, client.get("/v2/panorama/video") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(token) }.status)
        val modern = client.get("/v3/panorama/video") { bearerAuth(token) }
        assertEquals(shared, Protocol.json.decodeFromString<PanoramaVideoShare>(modern.bodyAsText()))
        val rawPart = client.get(path.replace("/v2/", "/v3/")) { bearerAuth(token); header(HttpHeaders.Range, "bytes=10-19") }
        assertEquals(HttpStatusCode.PartialContent, rawPart.status)
        assertArrayEquals(bytes.copyOfRange(10, 20), rawPart.readRawBytes())
        shared = null
        assertEquals(HttpStatusCode.NoContent, client.get("/v2/panorama/video") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(token) }.status)
        authority.setPeers(emptyList())
        assertEquals(HttpStatusCode.Unauthorized, client.get(path) { bearerAuth(token) }.status)
    }
    @Test fun `pairing rejects oversized streamed bodies without a declared length`() = testApplication {
        val source = object : HostPhotoSource {
            override suspend fun photos() = emptyList<Photo>()
            override suspend fun thumbnail(id: String) = null
            override suspend fun original(id: String): Original? = null
        }
        val authority = PairingAuthority()
        application { photoApi(source, authority, { Hello("phone", "Galaxy", "alpha", 0) }, MutableSharedFlow()) }
        val response = client.post("/v1/pair") {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(ByteArray(4097) { 32 }) }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(authority.pending.value.isEmpty())
    }

    @Test fun `approval gates library and byte ranges preserve exact originals`() = testApplication {
        val authority = PairingAuthority()
        val bytes = ByteArray(100) { it.toByte() }
        val source = object : HostPhotoSource {
            override suspend fun photos() = (1..60).map { Photo("$it", "photo.heic", 100, 4000, 3000, 100) }
            override suspend fun thumbnail(id: String) = null
            override suspend fun original(id: String) = if (id == "1") Original(100) { bytes.inputStream() } else null
        }
        application { photoApi(source, authority, { Hello("phone", "Galaxy", "alpha", 60) }, MutableSharedFlow()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/library").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/items/1/original").status)
        val pair = PairRequest("quest", "Quest")
        val pending = Protocol.json.decodeFromString<PairResponse>(client.post("/v1/pair") {
            contentType(ContentType.Application.Json); setBody(Protocol.json.encodeToString(pair))
        }.bodyAsText())
        assertNull(pending.token)
        val peer = authority.prepareApproval(pending.requestId)!!
        authority.setPeers(listOf(peer))
        authority.completeApproval(pending.requestId, peer)
        val token = authority.beginOrPoll(pair.copy(requestId = pending.requestId)).token!!
        val page = Protocol.json.decodeFromString<LibraryPage>(client.get("/v1/library") { bearerAuth(token) }.bodyAsText())
        assertEquals(48, page.items.size); assertEquals("48", page.next)
        val next = Protocol.json.decodeFromString<LibraryPage>(client.get("/v1/library?after=48") { bearerAuth(token) }.bodyAsText())
        assertEquals(12, next.items.size); assertNull(next.next)
        val partial = client.get("/v1/items/1/original") { bearerAuth(token); header(HttpHeaders.Range, "bytes=10-19") }
        assertEquals(HttpStatusCode.PartialContent, partial.status)
        assertEquals("bytes 10-19/100", partial.headers[HttpHeaders.ContentRange])
        assertArrayEquals(bytes.copyOfRange(10, 20), partial.readRawBytes())
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable,
            client.get("/v1/items/1/original") { bearerAuth(token); header(HttpHeaders.Range, "bytes=100-") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/items/999/original") { bearerAuth(token) }.status)
    }
}
