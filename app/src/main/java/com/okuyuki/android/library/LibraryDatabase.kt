package com.okuyuki.android.library

import android.content.Context
import androidx.room.*
import com.okuyuki.protocol.Photo
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "photos")
data class IndexedPhoto(
    @PrimaryKey val id: Long,
    val modified: Long,
    val stereo: Boolean,
    val name: String,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
) {
    fun photo() = Photo(id.toString(), name, takenAt, width, height, sizeBytes)
}

@Entity(tableName = "peers")
data class PairedPeer(@PrimaryKey val clientId: String, val name: String, val tokenHash: String)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM photos WHERE stereo = 1 ORDER BY takenAt DESC, id DESC")
    fun observePhotos(): Flow<List<IndexedPhoto>>
    @Query("SELECT * FROM photos") suspend fun all(): List<IndexedPhoto>
    @Query("SELECT * FROM photos WHERE id = :id AND stereo = 1") suspend fun find(id: Long): IndexedPhoto?
    @Upsert suspend fun save(photo: IndexedPhoto)
    @Query("DELETE FROM photos WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("SELECT * FROM peers") suspend fun peers(): List<PairedPeer>
    @Query("SELECT * FROM peers") fun observePeers(): Flow<List<PairedPeer>>
    @Upsert suspend fun savePeer(peer: PairedPeer)
    @Query("DELETE FROM peers WHERE clientId = :id") suspend fun deletePeer(id: String)
}

@Database(entities = [IndexedPhoto::class, PairedPeer::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, LibraryDatabase::class.java, "okuyuki.db").build()
    }
}
