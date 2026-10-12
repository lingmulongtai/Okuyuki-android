package com.okuyuki.android.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** An LG TV on the network. [id] is the UUID it announces, which stays the same when its address changes. */
data class FoundTv(val id: String, val name: String, val address: String)

/** A launch that failed for a reason the user can act on; [message] is shown as is. */
class LaunchException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** SSDP discovery of LG webOS TVs (the "second screen" service). */
object Ssdp {
    const val SERVICE = "urn:lge-com:service:webos-second-screen:1"

    val request: ByteArray = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\nST: $SERVICE\r\n\r\n").toByteArray()

    /** The headers of a positive SSDP answer, with lower-case names; null for anything else. */
    fun parse(text: String): Map<String, String>? {
        val lines = text.split("\r\n", "\n")
        if (!lines.first().startsWith("HTTP/1.1 200")) return null
        return lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
    }

    /** The device UUID from a USN such as `uuid:1234::urn:lge-com:service:webos-second-screen:1`. */
    fun uuid(usn: String?): String? = usn?.removePrefix("uuid:")?.substringBefore("::")?.trim()?.takeIf { it.isNotEmpty() }

    fun friendlyName(xml: String): String? =
        Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

/** The LG "SSAP" messages that register this phone with the TV and launch the TV app. */
object Ssap {
    const val APP_ID = "com.okuyuki.tv"
    const val REGISTER = "register_0"
    const val LAUNCH = "launch_1"

    /** Asks for the launch permission. Without [clientKey] the TV shows a prompt; with it, the TV knows this phone. */
    fun register(clientKey: String?): String = buildJsonObject {
        put("type", "register")
        put("id", REGISTER)
        putJsonObject("payload") {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            if (clientKey != null) put("client-key", clientKey)
            putJsonObject("manifest") {
                put("manifestVersion", 1)
                put("appVersion", "1.0")
                put("appId", "com.okuyuki.android")
                put("vendorId", "com.okuyuki")
                putJsonObject("localizedAppNames") { put("", "Okuyuki") }
                putJsonObject("localizedVendorNames") { put("", "Okuyuki") }
                putJsonArray("permissions") { add("LAUNCH"); add("LAUNCH_WEBAPP"); add("APP_TO_APP") }
            }
        }
    }.toString()

    fun launch(params: JsonObject): String = buildJsonObject {
        put("type", "request")
        put("id", LAUNCH)
        put("uri", "ssap://system.launcher/launch")
        putJsonObject("payload") {
            put("id", APP_ID)
            put("params", params)
        }
    }.toString()

    /** One message from the TV: its `type`, `id`, error text and payload. */
    data class Reply(val type: String, val id: String?, val error: String?, val payload: JsonObject?) {
        val prompting get() = payload?.get("pairingType")?.jsonPrimitive?.contentOrNull == "PROMPT"
        val clientKey get() = payload?.get("client-key")?.jsonPrimitive?.contentOrNull
        val succeeded get() = payload?.get("returnValue")?.jsonPrimitive?.booleanOrNull == true
        /** The TV's reason for a failure, from `error` or the payload's `errorText`. */
        val problem get() = error ?: payload?.get("errorText")?.jsonPrimitive?.contentOrNull
        /** Whether a failed launch says the app is not installed. */
        val missingApp get() = problem.orEmpty().lowercase().let { text -> MISSING_APP.any { it in text } }
    }

    private val MISSING_APP = listOf("404", "not found", "not exist", "no such", "can't find", "cannot find")

    fun reply(text: String): Reply? = runCatching {
        val json = Json.parseToJsonElement(text).jsonObject
        Reply(json["type"]?.jsonPrimitive?.content ?: return null, json["id"]?.jsonPrimitive?.contentOrNull,
            json["error"]?.jsonPrimitive?.contentOrNull, json["payload"] as? JsonObject)
    }.getOrNull()
}

/**
 * Finds LG TVs and opens Okuyuki TV on one of them, the way LG's own remote apps do: SSDP to find it,
 * then the TV's SSAP WebSocket to launch the app with the phone's address. The TV's certificate is
 * self-signed, so the first one seen is remembered and required from then on.
 */
class TvLauncher(private val context: Context, private val credentials: TvCredentials) {
    suspend fun discover(timeoutMillis: Long = 3000): List<FoundTv> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("okuyuki-ssdp")?.apply { setReferenceCounted(false); acquire() }
        try {
            val found = LinkedHashMap<String, Pair<String, String?>>()
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(Ssdp.request, Ssdp.request.size, group, 1900)) }
                val buffer = ByteArray(4096)
                val end = SystemClock.elapsedRealtime() + timeoutMillis
                while (SystemClock.elapsedRealtime() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val headers = Ssdp.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                    if (headers["st"] != Ssdp.SERVICE) continue
                    val id = Ssdp.uuid(headers["usn"]) ?: continue
                    found[id] = (packet.address.hostAddress ?: continue) to headers["location"]
                }
            }
            found.map { (id, value) -> FoundTv(id, name(value.second) ?: "LG テレビ（${value.first}）", value.first) }
        } finally {
            lock?.release()
        }
    }

    private fun name(location: String?): String? = runCatching {
        val connection = URL(location ?: return null).openConnection() as HttpURLConnection
        connection.connectTimeout = 1500
        connection.readTimeout = 1500
        try { Ssdp.friendlyName(connection.inputStream.bufferedReader().use { it.readText() }) } finally { connection.disconnect() }
    }.getOrNull()

    /** The TV's socket did not open at this address and port. */
    private class Unreachable(cause: Throwable) : Exception(cause)

    /** Opens Okuyuki TV on [tv] with [params]; [onPrompt] runs when the TV asks its owner to allow this phone. */
    suspend fun launch(tv: FoundTv, params: JsonObject, onPrompt: () -> Unit) {
        try {
            connect(tv, "wss://${tv.address}:3001", onPrompt, params)
        } catch (secure: Unreachable) {
            // TVs from before 2022 listen without TLS on port 3000. A TV that has answered over TLS before
            // never gets the client key in the clear, so blocking port 3001 cannot downgrade it.
            if (credentials.pin(tv.id) != null) throw LaunchException(UNREACHABLE, secure.cause)
            try {
                connect(tv, "ws://${tv.address}:3000", onPrompt, params)
            } catch (_: Unreachable) {
                throw LaunchException(UNREACHABLE, secure.cause)
            }
        }
    }

    private suspend fun connect(tv: FoundTv, url: String, onPrompt: () -> Unit, params: JsonObject) {
        val trust = PinningTrustManager(credentials.pin(tv.id))
        val client = OkHttpClient.Builder()
            .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory, trust)
            // The certificate names no address; the remembered fingerprint is what identifies the TV.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
        val opened = CompletableDeferred<Unit>()
        val messages = Channel<String>(Channel.UNLIMITED)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { messages.trySend(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                messages.close(t)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
        })
        try {
            val failure = try {
                if (withTimeoutOrNull(8000) { opened.await() } == null) SocketTimeoutException("No answer from the TV") else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { error }
            if (failure != null) {
                if (trust.mismatch) throw LaunchException("テレビの証明書が前回と違います。設定でこのテレビの登録を解除してから、もう一度試してください", failure)
                throw Unreachable(failure)
            }
            socket.send(Ssap.register(credentials.clientKey(tv.id)))
            withTimeoutOrNull(90_000) {
                while (true) {
                    val reply = Ssap.reply(messages.receive()) ?: continue
                    if (reply.id != Ssap.REGISTER) continue
                    when (reply.type) {
                        "response" -> if (reply.prompting) onPrompt()
                        "registered" -> {
                            reply.clientKey?.let { credentials.saveClientKey(tv.id, it) }
                            trust.seen?.let { credentials.savePin(tv.id, it) }
                            break
                        }
                        "error" -> throw LaunchException("テレビで許可されませんでした。もう一度試して、テレビの画面で「許可」を選んでください")
                    }
                }
            } ?: throw LaunchException("テレビで許可されませんでした。もう一度試して、テレビの画面で「許可」を選んでください")
            socket.send(Ssap.launch(params))
            withTimeoutOrNull(15_000) {
                while (true) {
                    val reply = Ssap.reply(messages.receive()) ?: continue
                    if (reply.id != Ssap.LAUNCH) continue
                    if (reply.type == "response" && reply.succeeded) break
                    val problem = reply.problem
                    throw LaunchException(when {
                        reply.missingApp -> "テレビに Okuyuki TV が入っていないようです。先にテレビにインストールしてください"
                        problem.isNullOrBlank() -> "テレビで Okuyuki TV を開けませんでした"
                        else -> "テレビで Okuyuki TV を開けませんでした（$problem）"
                    })
                }
            } ?: throw LaunchException("テレビから返事がありませんでした。もう一度試してください")
        } catch (closed: ClosedReceiveChannelException) {
            throw LaunchException("テレビとの接続が切れました。もう一度試してください", closed)
        } finally {
            socket.close(1000, null)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        const val UNREACHABLE = "テレビに接続できませんでした。テレビの電源が入っていて、同じ Wi-Fi にあるか確認してください"
    }

    /**
     * Accepts the TV's self-signed certificate the first time and only that certificate afterwards.
     * LG TVs present a certificate no authority signed, so the platform's checks would refuse every TV.
     */
    @SuppressLint("CustomX509TrustManager")
    private class PinningTrustManager(private val expected: String?) : X509TrustManager {
        @Volatile var seen: String? = null
        @Volatile var mismatch = false

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded).joinToString("") { "%02x".format(it) }
            if (expected != null && expected != fingerprint) {
                mismatch = true
                throw CertificateException("The TV's certificate changed")
            }
            seen = fingerprint
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("Not a server")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
