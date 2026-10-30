package com.okuyuki.android

import android.graphics.Bitmap
import android.app.Activity
import android.util.Size
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.android.host.*
import com.okuyuki.android.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhoneApp(app: OkuyukiApplication, requestPhotos: () -> Unit, toggleHost: (Boolean) -> Unit) {
    val updater = rememberAppUpdates()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<IndexedPhoto?>(null) }
    var panoramaVideo by remember { mutableStateOf<android.net.Uri?>(null) }
    var panoramaPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var osmoProxy by remember { mutableStateOf<android.net.Uri?>(null) }
    var osmoVideo by remember { mutableStateOf<android.net.Uri?>(null) }
    var osmoSelectionError by remember { mutableStateOf(false) }
    var showMediaChooser by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var osmoSelection by remember { mutableStateOf<Job?>(null) }
    val choosePanoramaVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { panoramaVideo = it }
    val choosePanoramaPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { panoramaPhoto = it }
    val chooseOsmoProxy = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            osmoSelection?.cancel()
            osmoSelection = scope.launch {
                val name = withContext(Dispatchers.IO) {
                    try {
                        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                val isProxy = name?.endsWith(".lrf", ignoreCase = true) == true
                val isOriginal = name?.endsWith(".osv", ignoreCase = true) == true
                osmoSelectionError = !isProxy && !isOriginal
                if (isProxy) osmoProxy = uri else if (isOriginal) osmoVideo = uri
            }
        }
    }
    val preferences = remember { app.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE) }
    val appearance = remember { app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(appearance.getString("theme", "system") ?: "system") }
    var showGuide by rememberSaveable { mutableStateOf(!preferences.getBoolean("completed", false)) }
    var guidePage by rememberSaveable { mutableIntStateOf(0) }
    val photos by app.library.photos.collectAsStateWithLifecycle(emptyList())
    val libraryState by app.library.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.JAPANESE
    val localeManager = LocalContext.current.getSystemService(LocaleManager::class.java)
    val host by HostRuntime.state.collectAsStateWithLifecycle()
    val sharedPanorama by HostRuntime.panoramaPhoto.collectAsStateWithLifecycle()
    val sharedVideo by HostRuntime.panoramaVideo.collectAsStateWithLifecycle()
    val pending by HostRuntime.authority.pending.collectAsStateWithLifecycle()
    val peers by app.database.dao().observePeers().collectAsStateWithLifecycle(emptyList())
    val darkTheme = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    OkuyukiTheme(dark = darkTheme) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { PhoneNavigation(tab) { tab = it } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PhoneHeader(
                    title = if (tab == 0) "Okuyuki" else if (tab == 1) "Quest" else stringResource(R.string.settings),
                    status = if (host.clients > 0) stringResource(R.string.connected) else if (host.running) stringResource(R.string.waiting) else stringResource(R.string.off),
                    onStatusClick = { tab = 1 },
                )
                host.error?.let { Text(stringResource(it), Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
                pending.forEach { request ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.pair_with, request.name), style = MaterialTheme.typography.titleMedium)
                            Text(request.code, style = MaterialTheme.typography.displaySmall)
                            Text(stringResource(R.string.pair_instruction))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { scope.launch {
                                    HostRuntime.pairingMutex.withLock {
                                        HostRuntime.authority.prepareApproval(request.requestId)?.let { peer ->
                                            val dao = app.database.dao()
                                            val previous = dao.peers().firstOrNull { it.clientId == peer.clientId }
                                            dao.savePeer(PairedPeer(peer.clientId, peer.name, peer.tokenHash))
                                            refreshAuthority(dao)
                                            if (!HostRuntime.authority.completeApproval(request.requestId, peer)) {
                                                if (previous == null) dao.deletePeer(peer.clientId) else dao.savePeer(previous)
                                                refreshAuthority(dao)
                                            }
                                        }
                                    }
                                } }) { Text(stringResource(R.string.allow)) }
                                TextButton(onClick = { HostRuntime.authority.reject(request.requestId) }) { Text(stringResource(R.string.deny)) }
                            }
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        GlassPanel(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.open_360), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.media_card_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FilledTonalButton(onClick = { showMediaChooser = true }) { Text(stringResource(R.string.open_media)) }
                            }
                            if (osmoSelectionError) Text(stringResource(R.string.osmo_choose_error), color = MaterialTheme.colorScheme.error)
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val readable = libraryState == LibraryState.READY || libraryState == LibraryState.LIMITED
                            Text(if (readable) stringResource(R.string.photo_count, photos.size) else stringResource(R.string.photos), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { app.library.refresh() }) { Text(stringResource(R.string.refresh)) }
                        }
                        Text(stringResource(when (libraryState) {
                            LibraryState.CHECKING -> R.string.status_checking
                            LibraryState.PERMISSION_REQUIRED -> R.string.status_permission
                            LibraryState.SCANNING -> R.string.status_scanning
                            LibraryState.READY -> R.string.status_ready
                            LibraryState.LIMITED -> R.string.status_limited
                            LibraryState.ERROR -> R.string.status_error
                        }), Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (libraryState == LibraryState.PERMISSION_REQUIRED) GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.permission_explain), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = requestPhotos) { Text(stringResource(R.string.grant_access)) }
                        } else if (libraryState == LibraryState.READY || libraryState == LibraryState.LIMITED) {
                            if (photos.isEmpty()) GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (libraryState == LibraryState.LIMITED) {
                                    TextButton(onClick = requestPhotos) { Text(stringResource(R.string.change_selection)) }
                                }
                            } else LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                photos.groupBy { date(it.takenAt, locale) }.forEach { (day, group) ->
                                    item(span = { GridItemSpan(3) }) { Text(stringResource(R.string.day_count, day, group.size), Modifier.padding(top = 12.dp, bottom = 8.dp, start = 4.dp), style = MaterialTheme.typography.titleSmall) }
                                    items(group, key = { it.id }) { photo ->
                                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).clickable { detail = photo }) {
                                            PhoneThumbnail(app, photo, Modifier.fillMaxSize())
                                            if (host.viewingId == photo.id.toString()) Surface(Modifier.align(Alignment.BottomStart).padding(6.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                                                Text(stringResource(R.string.viewing_quest), Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassPanel(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.connect_quest), style = MaterialTheme.typography.titleLarge)
                                    Text(stringResource(R.string.same_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = host.running, onCheckedChange = toggleHost)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                            Text(if (host.clients > 0) stringResource(R.string.quest_connected) else if (host.running) stringResource(R.string.quest_waiting) else stringResource(R.string.quest_off), style = MaterialTheme.typography.bodyMedium)
                            if (!app.library.hasPermission()) FilledTonalButton(onClick = requestPhotos) { Text(stringResource(R.string.grant_first)) }
                        }
                        if (sharedPanorama != null || sharedVideo != null) GlassPanel(Modifier.fillMaxWidth()) {
                            if (sharedPanorama != null) {
                                Text(stringResource(R.string.panorama_shared))
                                OutlinedButton(onClick = { HostRuntime.panoramaPhoto.value = null }) { Text(stringResource(R.string.panorama_stop_share)) }
                            }
                            if (sharedVideo != null) {
                                Text(stringResource(R.string.panorama_video_shared))
                                OutlinedButton(onClick = { HostRuntime.panoramaVideo.value = null }) { Text(stringResource(R.string.panorama_video_stop_share)) }
                            }
                        }
                        if (host.addresses.isNotEmpty()) GlassPanel(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.manual_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.manual_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            host.addresses.forEach { Text(it, style = MaterialTheme.typography.titleLarge) }
                        }
                        GlassPanel(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.timeout), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Okuyuki ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                        UpdateSettings(updater)
                        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                        val selectedLanguage = localeManager.applicationLocales[0]?.language ?: ""
                        Column(Modifier.selectableGroup()) {
                            listOf("" to R.string.language_system, "ja" to R.string.language_japanese, "en" to R.string.language_english).forEach { (tag, label) ->
                                Row(Modifier.fillMaxWidth().selectable(selected = selectedLanguage == tag, role = Role.RadioButton, onClick = { localeManager.applicationLocales = LocaleList.forLanguageTags(tag) }), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selectedLanguage == tag, onClick = null)
                                    Text(stringResource(label))
                                }
                            }
                        }
                        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.selectableGroup()) {
                            listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark).forEach { (value, label) ->
                                val select = { theme = value; appearance.edit().putString("theme", value).apply() }
                                Row(Modifier.fillMaxWidth().selectable(selected = theme == value, role = Role.RadioButton, onClick = select), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = theme == value, onClick = null)
                                    Text(stringResource(label))
                                }
                            }
                        }
                        OutlinedButton(onClick = { guidePage = 0; showGuide = true }) { Text(stringResource(R.string.guide_open)) }
                        Text(stringResource(R.string.paired), style = MaterialTheme.typography.titleMedium)
                        if (peers.isEmpty()) Text(stringResource(R.string.no_peers))
                        peers.forEach { peer -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(peer.name, Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { HostRuntime.pairingMutex.withLock {
                                app.database.dao().deletePeer(peer.clientId); refreshAuthority(app.database.dao())
                            } } }) { Text(stringResource(R.string.remove)) }
                        } }
                        OutlinedButton(onClick = requestPhotos) { Text(stringResource(R.string.change_access)) }
                        Text(stringResource(R.string.alpha_notice))
                    }
                }
            }
        }
        UpdatePrompt(updater)
        detail?.let { photo -> AlertDialog(onDismissRequest = { detail = null }, confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.close)) } },
            title = { Text(photo.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneThumbnail(app, photo, Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat().coerceAtLeast(1f) / photo.height.coerceAtLeast(1)))
                Text("${photo.width} × ${photo.height} · HEIC · ${date(photo.takenAt, locale)}")
                Text(stringResource(R.string.detail_hint))
            } }) }
        panoramaVideo?.let { uri -> PanoramaVideo(uri) { panoramaVideo = null } }
        panoramaPhoto?.let { uri -> PanoramaPhoto(uri) { panoramaPhoto = null } }
        osmoProxy?.let { uri -> OsmoProxyPreview(uri) { osmoProxy = null } }
        osmoVideo?.let { uri -> OsmoVideoPreview(uri) { osmoVideo = null } }
        if (showMediaChooser) AlertDialog(
            onDismissRequest = { showMediaChooser = false },
            title = { Text(stringResource(R.string.open_360)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.panorama_video_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { showMediaChooser = false; choosePanoramaPhoto.launch(arrayOf("image/*")) }) {
                    Text(stringResource(R.string.panorama_photo))
                }
                OutlinedButton(onClick = { showMediaChooser = false; choosePanoramaVideo.launch(arrayOf("video/mp4")) }) {
                    Text(stringResource(R.string.panorama_video))
                }
                OutlinedButton(onClick = { showMediaChooser = false; osmoSelectionError = false; chooseOsmoProxy.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.osmo_picker))
                }
            } },
            confirmButton = { TextButton(onClick = { showMediaChooser = false }) { Text(stringResource(R.string.close)) } }
        )
        if (showGuide) {
            val titles = listOf(stringResource(R.string.guide_welcome), stringResource(R.string.guide_photos), stringResource(R.string.connect_quest))
            val descriptions = listOf(
                stringResource(R.string.guide_intro),
                stringResource(R.string.guide_library),
                stringResource(R.string.guide_connect)
            )
            fun finishGuide() {
                preferences.edit().putBoolean("completed", true).apply()
                showGuide = false
            }
            AlertDialog(
                onDismissRequest = {},
                title = { Text(titles[guidePage]) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(descriptions[guidePage])
                    Text("${guidePage + 1} / ${titles.size}", style = MaterialTheme.typography.labelMedium)
                } },
                confirmButton = { TextButton(onClick = {
                    if (guidePage == titles.lastIndex) finishGuide() else guidePage++
                }) { Text(if (guidePage == titles.lastIndex) stringResource(R.string.start) else stringResource(R.string.next)) } },
                dismissButton = { TextButton(onClick = { finishGuide() }) { Text(stringResource(R.string.skip)) } }
            )
        }
    }
}

@Composable private fun PhoneThumbnail(app: OkuyukiApplication, photo: IndexedPhoto, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, photo.id, photo.modified) {
        value = withContext(Dispatchers.IO) { runCatching { app.contentResolver.loadThumbnail(app.library.uri(photo.id), Size(512, 512), null) }.getOrNull() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = photo.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("3D")
    }
}

private fun date(time: Long, locale: Locale) = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMM d E"), locale).format(Date(time))
private suspend fun refreshAuthority(dao: LibraryDao) {
    HostRuntime.authority.setPeers(dao.peers().map { ApprovedPeer(it.clientId, it.name, it.tokenHash) })
}
