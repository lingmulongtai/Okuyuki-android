package com.okuyuki.android.host

import com.okuyuki.protocol.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class PhotoApiTest {
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
