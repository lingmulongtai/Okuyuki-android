package com.okuyuki.android.host

import com.okuyuki.protocol.PairRequest
import com.okuyuki.protocol.PairResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class PendingPeer(val requestId: String, val clientId: String, val name: String, val code: String)
data class ApprovedPeer(val clientId: String, val name: String, val tokenHash: String)

class PairingAuthority(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Request(val peer: PendingPeer, val token: String, val createdAt: Long, var state: String = "pending")
    private val random = SecureRandom()
    private val requests = mutableMapOf<String, Request>()
    private var hashes = listOf<String>()
    private val _pending = MutableStateFlow<List<PendingPeer>>(emptyList())
    val pending = _pending.asStateFlow()

    @Synchronized fun setPeers(peers: List<ApprovedPeer>) { hashes = peers.map { it.tokenHash } }

    @Synchronized fun beginOrPoll(input: PairRequest): PairResponse {
        require(input.clientId.length in 1..80 && input.clientName.length in 1..80) { "Invalid client identity" }
        expire()
        val requestId = input.requestId
        val existing = requestId?.let { requests[it]?.takeIf { request -> request.peer.clientId == input.clientId } }
        if (requestId != null && existing == null) return PairResponse(requestId, "", "expired")
        val request = existing ?: requests.values.firstOrNull { it.peer.clientId == input.clientId && it.state == "pending" } ?: run {
            require(requests.size < 8) { "Too many pairing requests. Try again later." }
            val id = UUID.randomUUID().toString()
            val code = random.nextInt(10000).toString().padStart(4, '0')
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            Request(PendingPeer(id, input.clientId, input.clientName, code), token, clock()).also { requests[id] = it }
        }
        publish()
        return PairResponse(request.peer.requestId, request.peer.code, request.state, request.token.takeIf { request.state == "approved" })
    }

    @Synchronized fun prepareApproval(id: String): ApprovedPeer? {
        expire()
        return requests[id]?.takeIf { it.state == "pending" }?.let { ApprovedPeer(it.peer.clientId, it.peer.name, hash(it.token)) }
    }

    @Synchronized fun completeApproval(id: String, peer: ApprovedPeer) {
        expire()
        requests[id]?.takeIf { it.peer.clientId == peer.clientId && it.state == "pending" }?.let {
            hashes = hashes + peer.tokenHash
            it.state = "approved"
        }
        publish()
    }

    @Synchronized fun reject(id: String) { requests[id]?.state = "rejected"; publish() }

    @Synchronized fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Bearer ") || header.length !in 20..128) return false
        val candidate = hash(header.removePrefix("Bearer ")).toByteArray()
        return hashes.any { MessageDigest.isEqual(it.toByteArray(), candidate) }
    }

    @Synchronized fun clear() { requests.clear(); publish() }
    private fun expire() { requests.entries.removeAll { clock() - it.value.createdAt >= 120_000 }; publish() }
    private fun publish() { _pending.value = requests.values.filter { it.state == "pending" }.map { it.peer } }
    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
