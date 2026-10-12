package com.okuyuki.android.host

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Size
import androidx.core.app.NotificationCompat
import com.okuyuki.android.*
import com.okuyuki.android.tv.AndroidTvMedia
import com.okuyuki.protocol.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/** [clients] counts Quest connections and [tvClients] TV connections. */
data class HostState(val running: Boolean = false, val port: Int = 0, val addresses: List<String> = emptyList(),
    val clients: Int = 0, val tvClients: Int = 0, val viewingId: String? = null, val error: String? = null,
    val deviceId: String = "")

object HostRuntime {
    val state = MutableStateFlow(HostState())
    val authority = PairingAuthority()
    val pairingMutex = Mutex()
    /** Events for TVs: library changes, finished stills and videos, photos picked on the phone, shutdown. */
    val tvEvents = MutableSharedFlow<Event>(extraBufferCapacity = 32)

    /** Shows a photo or video on every connected TV. */
    fun showOnTv(id: Long) = tvEvents.tryEmit(Event("show", id.toString()))
}

class HostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as OkuyukiApplication
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var tvMedia: AndroidTvMedia? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val questEvents = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private var lastClientAt = System.currentTimeMillis()
    private var announced = false
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("host", "Quest・テレビとの接続", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification(WAITING))
        HostRuntime.state.value = HostState(running = true)
        app.library.refresh()
        scope.launch {
            app.database.dao().observePeers().collect {
                HostRuntime.pairingMutex.withLock { refreshPeers() }
            }
        }
        scope.launch {
            HostRuntime.authority.pending.collect { requests ->
                getSystemService(NotificationManager::class.java).notify(1,
                    notification(if (requests.isEmpty()) WAITING else "接続番号を確認してください"))
            }
        }
        scope.launch {
            try {
                val preferences = getSharedPreferences("host", MODE_PRIVATE)
                val deviceId = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }
                val source = androidSource()
                val media = AndroidTvMedia(applicationContext, app.library, HostRuntime.tvEvents, scope).also { tvMedia = it }
                val tv = TvHost(HostRuntime.authority, media, HostRuntime.tvEvents,
                    connected = { count ->
                        lastClientAt = System.currentTimeMillis()
                        HostRuntime.state.update { it.copy(tvClients = count, viewingId = if (count == 0 && it.clients == 0) null else it.viewingId) }
                    }, viewing = { id -> HostRuntime.state.update { it.copy(viewingId = id) } })
                HostRuntime.pairingMutex.withLock { refreshPeers() }
                val engine = withContext(Dispatchers.IO) {
                    // A fixed port lets a TV come back to the address it saved; any free port is the fallback.
                    startHost(listOf(Protocol.PORT, 0)) {
                        photoApi(source, HostRuntime.authority,
                            { Hello(deviceId, android.os.Build.MODEL, BuildConfig.VERSION_NAME, source.photos().size, protocolVersions = Protocol.VERSIONS) },
                            questEvents,
                            connected = { count ->
                                lastClientAt = System.currentTimeMillis()
                                HostRuntime.state.update { it.copy(clients = count, viewingId = if (count == 0 && it.tvClients == 0) null else it.viewingId) }
                            }, viewing = { id -> HostRuntime.state.update { it.copy(viewingId = id) } }, tv = tv)
                    }
                }
                server = engine
                val port = engine.engine.resolvedConnectors().first().port
                HostRuntime.state.update { it.copy(port = port, deviceId = deviceId, addresses = addresses().map { address -> "$address:$port" }) }
                advertise(port)
                launch { app.library.photos.drop(1).distinctUntilChanged().collect { questEvents.emit(Event("library_changed")) } }
                launch { app.library.changes.collect { HostRuntime.tvEvents.emit(Event("library_changed")) } }
                while (isActive) {
                    delay(30_000)
                    val state = HostRuntime.state.value
                    if (state.clients > 0 || state.tvClients > 0) lastClientAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - lastClientAt > 10 * 60_000) { stopSelf(); break }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { HostRuntime.state.value = HostState(error = "接続を開始できませんでした。Wi-Fiを確認してください"); stopSelf() }
        }
    }

    private fun androidSource(): HostPhotoSource {
        val permits = Semaphore(3)
        return object : HostPhotoSource {
            override suspend fun photos(): List<Photo> = if (!app.library.hasPermission()) emptyList() else
                app.database.dao().stereoPhotos().map { it.photo() }
            override suspend fun thumbnail(id: String): ByteArray? = permits.withPermit {
                withContext(Dispatchers.IO) {
                    if (!app.library.hasPermission()) return@withContext null
                    val photo = id.toLongOrNull()?.let { app.database.dao().find(it) } ?: return@withContext null
                    runCatching {
                        val bitmap = contentResolver.loadThumbnail(app.library.uri(photo.id), Size(512, 512), null)
                        try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }
                        finally { bitmap.recycle() }
                    }.getOrNull()
                }
            }
            override suspend fun original(id: String): Original? {
                if (!app.library.hasPermission()) return null
                val photo = id.toLongOrNull()?.let { app.database.dao().find(it) } ?: return null
                return Original(photo.sizeBytes) { contentResolver.openInputStream(app.library.uri(photo.id)) ?: throw java.io.FileNotFoundException() }
            }
        }
    }

    private fun advertise(port: Int) {
        val nsd = getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (destroyed) runCatching { nsd.unregisterService(this) } else announced = true
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (!destroyed) HostRuntime.state.update { it.copy(error = "自動検出を開始できませんでした。Questで表示されたアドレスを入力してください") }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) { announced = false }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(NsdServiceInfo().apply { serviceName = "Okuyuki ${android.os.Build.MODEL}"; serviceType = Protocol.SERVICE_TYPE; this.port = port }, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HostService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "host").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Okuyuki")
            .setContentText(message).setContentIntent(open).setOngoing(true).addAction(0, "接続をオフ", stop).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true
        // TVs say "turned off on the phone" instead of a lost connection; the grace period lets it go out.
        HostRuntime.tvEvents.tryEmit(Event("session_closed"))
        scope.cancel()
        if (announced) registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        server?.stop(300, 600)
        tvMedia?.close()
        HostRuntime.authority.clear()
        HostRuntime.state.update { HostState(error = it.error) }
        super.onDestroy()
    }
    private suspend fun refreshPeers() {
        HostRuntime.authority.setPeers(app.database.dao().peers().map { ApprovedPeer(it.clientId, it.name, it.tokenHash, it.kind) })
    }
    private fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress ?: "" }

    private companion object {
        const val WAITING = "Quest・テレビを待っています"
    }
}
