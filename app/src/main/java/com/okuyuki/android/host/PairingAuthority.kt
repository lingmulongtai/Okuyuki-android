package com.okuyuki.android.host

import com.okuyuki.protocol.MediaKey
import com.okuyuki.protocol.PairRequest
import com.okuyuki.protocol.PairResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** [kind] is `headset` (Quest) or `tv`, as the device said when it asked. */
data class PendingPeer(val requestId: String, val clientId: String, val name: String, val code: String, val kind: String = HEADSET)
data class ApprovedPeer(val clientId: String, val name: String, val tokenHash: String, val kind: String = HEADSET)

const val HEADSET = "headset"
const val TV = "tv"

class PairingAuthority(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Request(val peer: PendingPeer, val token: String, val createdAt: Long, var state: String = "pending")
    /** A media key (protocol v2) stands for the approved token it was opened with, until it expires. */
    private data class Grant(val tokenHash: String, val expiresAt: Long)
    private val random = SecureRandom()
    private val requests = mutableMapOf<String, Request>()
    private var peers = listOf<ApprovedPeer>()
    private val grants = mutableMapOf<String, Grant>()
    private val _pending = MutableStateFlow<List<PendingPeer>>(emptyList())
    val pending = _pending.asStateFlow()
    private val _changes = MutableStateFlow(0)
    /** Changes whenever approvals change, so open event sockets can check their key again. */
    val changes = _changes.asStateFlow()

    @Synchronized fun setPeers(peers: List<ApprovedPeer>) {
        this.peers = peers
        val approved = peers.mapTo(HashSet()) { it.tokenHash }
        grants.values.removeAll { it.tokenHash !in approved }
        _changes.value++
    }

    @Synchronized fun beginOrPoll(input: PairRequest): PairResponse {
        require(input.clientId.length in 1..80 && input.clientName.length in 1..80) { "Invalid client identity" }
        expire()
        val requestId = input.requestId
        val request = if (requestId != null) {
            requests[requestId]?.takeIf { it.peer.clientId == input.clientId } ?: return PairResponse(requestId, "", "expired")
        } else {
            // Only the requestId reaches an existing request, so knowing a clientId never yields its code or token.
            // A device that starts over replaces its own pending request instead of stacking cards on the phone.
            requests.values.removeAll { it.peer.clientId == input.clientId && it.state == "pending" }
            require(requests.size < 8) { "Too many pairing requests. Try again later." }
            val id = UUID.randomUUID().toString()
            val code = random.nextInt(10000).toString().padStart(4, '0')
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            val kind = if (input.clientKind == TV) TV else HEADSET
            Request(PendingPeer(id, input.clientId, input.clientName, code, kind), token, clock()).also { requests[id] = it }
        }
        publish()
        return PairResponse(request.peer.requestId, request.peer.code, request.state, request.token.takeIf { request.state == "approved" })
    }

    @Synchronized fun prepareApproval(id: String): ApprovedPeer? {
        expire()
        return requests[id]?.takeIf { it.state == "pending" }?.let { ApprovedPeer(it.peer.clientId, it.peer.name, hash(it.token), it.peer.kind) }
    }

    @Synchronized fun completeApproval(id: String, peer: ApprovedPeer): Boolean {
        expire()
        val request = requests[id]?.takeIf { it.peer.clientId == peer.clientId && it.state == "pending" && peer.tokenHash == hash(it.token) }
        if (request != null && peers.any { it.tokenHash == peer.tokenHash }) {
            request.state = "approved"
            publish()
            return true
        }
        publish()
        return false
    }

    @Synchronized fun reject(id: String) { requests[id]?.state = "rejected"; publish() }

    @Synchronized fun authorized(header: String?): Boolean = approvedBy(header) != null

    /** Opens a media session for an approved token: a 128-bit key the TV puts in its URLs instead of a header. */
    @Synchronized fun openSession(header: String?, lifetimeMillis: Long = KEY_LIFETIME_MS): MediaKey? {
        val peer = approvedBy(header)?.takeIf { it.kind == TV } ?: return null
        val now = clock()
        grants.values.removeAll { it.expiresAt <= now }
        if (grants.size >= MAX_KEYS) grants.entries.minByOrNull { it.value.expiresAt }?.let { grants.remove(it.key) }
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        return MediaKey(key, now + lifetimeMillis).also { grants[key] = Grant(peer.tokenHash, it.expiresAt) }
    }

    /** The peer a media key belongs to, while the key is unexpired and the peer is still approved. */
    @Synchronized fun peerFor(key: String?): ApprovedPeer? {
        val grant = key?.let(grants::get) ?: return null
        val peer = peers.firstOrNull { it.tokenHash == grant.tokenHash }
        if (peer == null || clock() >= grant.expiresAt) { grants.remove(key); return null }
        return peer
    }

    private fun approvedBy(header: String?): ApprovedPeer? {
        if (header == null || !header.startsWith("Bearer ") || header.length !in 20..128) return null
        val candidate = hash(header.removePrefix("Bearer ")).toByteArray()
        return peers.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), candidate) }
    }

    @Synchronized fun clear() { requests.clear(); peers = emptyList(); grants.clear(); _changes.value++; publish() }
    private fun expire() { requests.entries.removeAll { clock() - it.value.createdAt >= 120_000 }; publish() }
    private fun publish() { _pending.value = requests.values.filter { it.state == "pending" }.map { it.peer } }
    companion object {
        const val KEY_LIFETIME_MS = 12 * 60 * 60 * 1000L
        private const val MAX_KEYS = 64
    }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
