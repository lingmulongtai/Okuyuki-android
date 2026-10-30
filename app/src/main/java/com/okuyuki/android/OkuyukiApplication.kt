package com.okuyuki.android

import android.app.Application
import com.okuyuki.android.library.LibraryDatabase
import com.okuyuki.android.library.PhotoLibrary
import com.okuyuki.android.tv.TvRemote
import kotlinx.coroutines.*

class OkuyukiApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { LibraryDatabase.create(this) }
    val library by lazy { PhotoLibrary(this, database.dao(), scope) }
    val tvRemote by lazy { TvRemote(this, scope) }
    override fun onCreate() { super.onCreate(); library.refresh() }
}
