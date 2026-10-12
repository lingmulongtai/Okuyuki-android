package com.okuyuki.android.host

import com.okuyuki.protocol.PairRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PairingAuthorityTest {
    @Test fun `photos stay private until explicit durable approval`() {
        val authority = PairingAuthority()
        val request = PairRequest("quest", "Quest 3S")
        val pending = authority.beginOrPoll(request)
        assertEquals(4, pending.code.length)
        assertNull(pending.token)
        assertFalse(authority.authorized("Bearer ${pending.code}"))
        val peer = authority.prepareApproval(pending.requestId)!!
        assertFalse(authority.completeApproval(pending.requestId, peer))
        authority.setPeers(listOf(peer))
        assertTrue(authority.completeApproval(pending.requestId, peer))
        val approved = authority.beginOrPoll(request.copy(requestId = pending.requestId))
        assertTrue(authority.authorized("Bearer ${approved.token}"))
        assertFalse(authority.authorized("Bearer ${approved.token}x"))
        authority.setPeers(emptyList())
        assertFalse(authority.authorized("Bearer ${approved.token}"))
    }
    @Test fun `stopping host removes all in-memory authority`() {
        val authority = PairingAuthority()
        val pending = authority.beginOrPoll(PairRequest("quest", "Quest"))
        val peer = authority.prepareApproval(pending.requestId)!!
        authority.setPeers(listOf(peer)); authority.completeApproval(pending.requestId, peer)
        val token = authority.beginOrPoll(PairRequest("quest", "Quest", pending.requestId)).token!!
        authority.clear()
        assertFalse(authority.authorized("Bearer $token"))
        assertTrue(authority.pending.value.isEmpty())
    }

    @Test fun `a request without its requestId never joins a pending one`() {
        val authority = PairingAuthority()
        val first = authority.beginOrPoll(PairRequest("quest", "Quest"))
        // Someone who learned the clientId gets a request (and code) of their own, not the pending one.
        val second = authority.beginOrPoll(PairRequest("quest", "Quest"))
        assertNotEquals(first.requestId, second.requestId)
        assertEquals("expired", authority.beginOrPoll(PairRequest("quest", "Quest", first.requestId)).state)
        assertEquals(listOf(second.requestId), authority.pending.value.map { it.requestId })
        val peer = authority.prepareApproval(second.requestId)!!
        authority.setPeers(listOf(peer))
        assertTrue(authority.completeApproval(second.requestId, peer))
        val third = authority.beginOrPoll(PairRequest("quest", "Quest"))
        assertNotEquals(second.requestId, third.requestId)
        assertEquals("pending", third.state)
        assertNull(third.token)
        assertNotNull(authority.beginOrPoll(PairRequest("quest", "Quest", second.requestId)).token)
    }

    @Test fun `requests expire and cannot be claimed by another client`() {
        var now = 0L
        val authority = PairingAuthority { now }
        val pending = authority.beginOrPoll(PairRequest("quest", "Quest"))
        assertEquals("expired", authority.beginOrPoll(PairRequest("intruder", "Other", pending.requestId)).state)
        now = 120_000
        assertNull(authority.prepareApproval(pending.requestId))
        assertEquals("expired", authority.beginOrPoll(PairRequest("quest", "Quest", pending.requestId)).state)
        assertTrue(authority.pending.value.isEmpty())
    }

    @Test fun `media keys last until they expire, the TV is removed or the host stops`() {
        var now = 0L
        val authority = PairingAuthority { now }
        assertNull(authority.openSession(null))
        assertNull(authority.openSession("Bearer not-a-token-of-anyone"))
        val token = approveTv(authority)
        val changes = authority.changes.value
        val first = authority.openSession("Bearer $token")!!
        assertEquals(PairingAuthority.KEY_LIFETIME_MS, first.expiresAt)
        assertEquals(16, Base64.getUrlDecoder().decode(first.key).size)
        now = 1000
        val second = authority.openSession("Bearer $token")!!
        assertNotEquals(first.key, second.key)
        // An older key keeps working until it expires, so a video that is playing does not stop.
        assertEquals(TV, authority.peerFor(first.key)?.kind)
        assertNull(authority.peerFor(null)); assertNull(authority.peerFor("${first.key}x"))
        now = PairingAuthority.KEY_LIFETIME_MS
        assertNull(authority.peerFor(first.key))
        assertNotNull(authority.peerFor(second.key))
        authority.setPeers(emptyList())
        assertNull(authority.peerFor(second.key))
        assertTrue(authority.changes.value > changes)

        val again = approveTv(authority)
        val third = authority.openSession("Bearer $again")!!
        authority.clear()
        assertNull(authority.peerFor(third.key))
        assertNull(authority.openSession("Bearer $again"))
    }

    private fun approveTv(authority: PairingAuthority): String {
        val request = PairRequest("tv", "LG C5", clientKind = TV)
        val pending = authority.beginOrPoll(request)
        val peer = authority.prepareApproval(pending.requestId)!!
        authority.setPeers(listOf(peer))
        assertTrue(authority.completeApproval(pending.requestId, peer))
        return authority.beginOrPoll(request.copy(requestId = pending.requestId)).token!!
    }
}
