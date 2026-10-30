package com.okuyuki.android.host

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

data class HostState(val running: Boolean = false, val port: Int = 0, val addresses: List<String> = emptyList(),
    val clients: Int = 0, val viewingId: String? = null, val error: Int? = null, val sessionId: String? = null)
internal fun acceptsPanoramaShare(sessionId: String?, state: HostState) =
    sessionId != null && state.running && state.sessionId == sessionId
data class SelectedPanoramaPhoto(val share: PanoramaPhotoShare, val uri: Uri)
data class SelectedPanoramaVideo(val share: PanoramaVideoShare, val uri: Uri, val motionBytes: ByteArray? = null)

object HostRuntime {
    val state = MutableStateFlow(HostState())
    val authority = PairingAuthority()
    val pairingMutex = Mutex()
    val panoramaPhoto = MutableStateFlow<SelectedPanoramaPhoto?>(null)
    val panoramaVideo = MutableStateFlow<SelectedPanoramaVideo?>(null)
}

class HostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as OkuyukiApplication
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private var lastClientAt = System.currentTimeMillis()
    private var announced = false
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        updateNotificationChannel()
        startForeground(1, notification(getString(R.string.notify_waiting)))
        HostRuntime.state.value = HostState(running = true, sessionId = UUID.randomUUID().toString())
        app.library.refresh()
        scope.launch {
            app.database.dao().observePeers().collect {
                HostRuntime.pairingMutex.withLock { refreshPeers() }
            }
        }
        scope.launch {
            combine(HostRuntime.authority.pending, HostRuntime.panoramaPhoto, HostRuntime.panoramaVideo) { requests, photo, video ->
                if (requests.isNotEmpty()) R.string.notify_code else if (video != null) R.string.notify_video_sharing else if (photo != null) R.string.notify_sharing else R.string.notify_waiting
            }.collect { message ->
                getSystemService(NotificationManager::class.java).notify(1,
                    notification(getString(message)))
            }
        }
        scope.launch {
            try {
                val preferences = getSharedPreferences("host", MODE_PRIVATE)
                val deviceId = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }
                val source = androidSource()
                HostRuntime.pairingMutex.withLock { refreshPeers() }
                val engine = embeddedServer(CIO, host = "0.0.0.0", port = 0) {
                    photoApi(source, HostRuntime.authority,
                        { Hello(deviceId, android.os.Build.MODEL, BuildConfig.VERSION_NAME, source.photos().size) }, events,
                        connected = { count ->
                            lastClientAt = System.currentTimeMillis()
                            HostRuntime.state.update { it.copy(clients = count, viewingId = if (count == 0) null else it.viewingId) }
                        }, viewing = { id -> HostRuntime.state.update { it.copy(viewingId = id) } },
                        panorama = panoramaSource(), video = videoSource())
                }
                server = engine
                withContext(Dispatchers.IO) { engine.start(wait = false) }
                val port = engine.engine.resolvedConnectors().first().port
                HostRuntime.state.update { it.copy(port = port, addresses = addresses().map { address -> "$address:$port" }) }
                advertise(port)
                launch { app.library.photos.drop(1).collect { events.emit(Event("library_changed")) } }
                launch { HostRuntime.panoramaPhoto.collect { events.emit(Event("panorama_changed")) } }
                launch { HostRuntime.panoramaVideo.collect { events.emit(Event("panorama_video_changed")) } }
                while (isActive) {
                    delay(30_000)
                    if (HostRuntime.state.value.clients > 0) lastClientAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - lastClientAt > 10 * 60_000) { stopSelf(); break }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { HostRuntime.state.value = HostState(error = R.string.host_wifi_error); stopSelf() }
        }
    }

    private fun panoramaSource() = object : PanoramaPhotoSource {
        override suspend fun current() = HostRuntime.panoramaPhoto.value?.share
        override suspend fun open(id: String): java.io.InputStream? {
            val selected = HostRuntime.panoramaPhoto.value?.takeIf { it.share.id == id } ?: return null
            return try { contentResolver.openInputStream(selected.uri) }
            catch (_: SecurityException) {
                HostRuntime.panoramaPhoto.compareAndSet(selected, null)
                null
            } catch (_: java.io.FileNotFoundException) {
                HostRuntime.panoramaPhoto.compareAndSet(selected, null)
                null
            }
        }
    }

    private fun videoSource() = object : PanoramaVideoSource {
        override suspend fun current() = HostRuntime.panoramaVideo.value?.share
        override suspend fun motion(id: String): ByteArray? = HostRuntime.panoramaVideo.value
            ?.takeIf { it.share.id == id }?.motionBytes
        override suspend fun open(id: String, offset: Long): java.io.InputStream? {
            val selected = HostRuntime.panoramaVideo.value?.takeIf { it.share.id == id } ?: return null
            return try {
                val asset = contentResolver.openAssetFileDescriptor(selected.uri, "r") ?: return null
                try {
                    val input = asset.createInputStream()
                    try { input.channel.position(Math.addExact(asset.startOffset, offset)); input }
                    catch (error: Exception) { input.close(); throw error }
                } catch (error: Exception) { asset.close(); throw error }
            } catch (_: SecurityException) {
                HostRuntime.panoramaVideo.compareAndSet(selected, null); null
            } catch (_: java.io.IOException) {
                HostRuntime.panoramaVideo.compareAndSet(selected, null); null
            }
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
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (destroyed) runCatching { nsd.unregisterService(this) } else announced = true
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (!destroyed) HostRuntime.state.update { it.copy(error = R.string.host_discovery_error) }
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
            .setContentText(message).setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.notify_stop), stop).build()
    }
    private fun updateNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("host", getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannel()
        val message = if (HostRuntime.authority.pending.value.isNotEmpty()) R.string.notify_code
            else if (HostRuntime.panoramaVideo.value != null) R.string.notify_video_sharing
            else if (HostRuntime.panoramaPhoto.value != null) R.string.notify_sharing else R.string.notify_waiting
        getSystemService(NotificationManager::class.java).notify(1, notification(getString(message)))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true
        HostRuntime.panoramaPhoto.value = null
        HostRuntime.panoramaVideo.value = null
        scope.cancel()
        if (announced) registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        server?.stop(0, 300)
        HostRuntime.authority.clear()
        HostRuntime.state.update { HostState(error = it.error) }
        super.onDestroy()
    }
    private suspend fun refreshPeers() {
        HostRuntime.authority.setPeers(app.database.dao().peers().map { ApprovedPeer(it.clientId, it.name, it.tokenHash) })
    }
    private fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress ?: "" }
}
