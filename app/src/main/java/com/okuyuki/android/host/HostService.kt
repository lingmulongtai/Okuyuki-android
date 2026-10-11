package com.okuyuki.android.host

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Size
import androidx.core.app.NotificationCompat
import com.okuyuki.android.*
import com.okuyuki.android.library.PairedPeer
import com.okuyuki.protocol.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

data class HostState(val running: Boolean = false, val port: Int = 0, val addresses: List<String> = emptyList(),
    val clients: Int = 0, val viewingId: String? = null, val error: String? = null)

object HostRuntime {
    val state = MutableStateFlow(HostState())
    val authority = PairingAuthority()
}

class HostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as OkuyukiApplication
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private var lastClientAt = System.currentTimeMillis()
    private var announced = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("host", "Questとの接続", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("Questを待っています"))
        HostRuntime.state.value = HostState(running = true)
        app.library.refresh()
        scope.launch {
            app.database.dao().observePeers().collect { peers ->
                HostRuntime.authority.setPeers(peers.map { ApprovedPeer(it.clientId, it.name, it.tokenHash) })
            }
        }
        scope.launch {
            HostRuntime.authority.pending.collect { requests ->
                getSystemService(NotificationManager::class.java).notify(1,
                    notification(if (requests.isEmpty()) "Questを待っています" else "接続番号を確認してください"))
            }
        }
        scope.launch {
            try {
                val preferences = getSharedPreferences("host", MODE_PRIVATE)
                val deviceId = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }
                val source = androidSource()
                val engine = embeddedServer(CIO, host = "0.0.0.0", port = 0) {
                    photoApi(source, HostRuntime.authority,
                        { Hello(deviceId, android.os.Build.MODEL, BuildConfig.VERSION_NAME, source.photos().size) }, events,
                        connected = { count ->
                            lastClientAt = System.currentTimeMillis()
                            HostRuntime.state.update { it.copy(clients = count, viewingId = if (count == 0) null else it.viewingId) }
                        }, viewing = { id -> HostRuntime.state.update { it.copy(viewingId = id) } })
                }
                server = engine
                withContext(Dispatchers.IO) { engine.start(wait = false) }
                val port = engine.engine.resolvedConnectors().first().port
                HostRuntime.state.update { it.copy(port = port, addresses = addresses().map { address -> "$address:$port" }) }
                advertise(port)
                launch { app.library.photos.drop(1).collect { events.emit(Event("library_changed")) } }
                while (isActive) {
                    delay(30_000)
                    if (HostRuntime.state.value.clients > 0) lastClientAt = System.currentTimeMillis()
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
                app.database.dao().all().filter { it.stereo }.sortedWith(compareByDescending<com.okuyuki.android.library.IndexedPhoto> { it.takenAt }.thenByDescending { it.id }).map { it.photo() }
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
            override fun onServiceRegistered(info: NsdServiceInfo) { announced = true }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                HostRuntime.state.update { it.copy(error = "自動検出を開始できませんでした。Questで表示されたアドレスを入力してください") }
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
        scope.cancel()
        if (announced) registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        server?.stop(0, 300)
        HostRuntime.authority.clear()
        HostRuntime.state.update { HostState(error = it.error) }
        super.onDestroy()
    }
    private fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress ?: "" }
}
