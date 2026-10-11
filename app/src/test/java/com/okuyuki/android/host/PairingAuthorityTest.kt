package com.okuyuki.android.host

import com.okuyuki.protocol.PairRequest
import org.junit.Assert.*
import org.junit.Test

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
}
