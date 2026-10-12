package com.okuyuki.android.library

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.okuyuki.protocol.Item
import com.okuyuki.protocol.Photo
import kotlinx.coroutines.flow.Flow

/** A photo or video from MediaStore. Images and videos share MediaStore's ID space, so [id] is unique across both. */
@Entity(tableName = "photos", indices = [Index(value = ["takenAt", "id"])])
data class IndexedMedia(
    @PrimaryKey val id: Long,
    val modified: Long,
    val stereo: Boolean,
    val name: String,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    @ColumnInfo(defaultValue = PHOTO) val kind: String = PHOTO,
    @ColumnInfo(defaultValue = "image/heic") val mime: String = "image/heic",
    val durationMs: Long? = null,
    @ColumnInfo(defaultValue = "none") val hdr: String = "none",
    @ColumnInfo(defaultValue = "0") val orientation: Int = 0,
) {
    val isVideo: Boolean get() = kind == VIDEO

    fun photo() = Photo(id.toString(), name, takenAt, width, height, sizeBytes)

    /** The item as a TV sees it: width and height as displayed, after rotation. */
    fun item(): Item {
        val turned = orientation % 180 != 0
        return Item(id.toString(), kind, name, takenAt, if (turned) height else width, if (turned) width else height,
            sizeBytes, mime, durationMs.takeIf { isVideo }, stereo, hdr)
    }

    companion object {
        const val PHOTO = "photo"
        const val VIDEO = "video"
    }
}

/** [kind] is `headset` (Quest) or `tv`. */
@Entity(tableName = "peers")
data class PairedPeer(
    @PrimaryKey val clientId: String,
    val name: String,
    val tokenHash: String,
    @ColumnInfo(defaultValue = "headset") val kind: String = "headset",
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM photos WHERE stereo = 1 ORDER BY takenAt DESC, id DESC")
    fun observePhotos(): Flow<List<IndexedMedia>>
    @Query("SELECT * FROM photos ORDER BY takenAt DESC, id DESC")
    fun observeAll(): Flow<List<IndexedMedia>>
    @Query("SELECT * FROM photos WHERE stereo = 1 ORDER BY takenAt DESC, id DESC") suspend fun stereoPhotos(): List<IndexedMedia>
    @Query("SELECT * FROM photos") suspend fun all(): List<IndexedMedia>
    @Query("SELECT * FROM photos WHERE id = :id AND stereo = 1") suspend fun find(id: Long): IndexedMedia?
    @Query("SELECT * FROM photos WHERE id = :id") suspend fun findAny(id: Long): IndexedMedia?
    /** Newest first, strictly after the item at ([takenAt], [id]); [kind] null means photos and videos. */
    @Query("""SELECT * FROM photos WHERE (:kind IS NULL OR kind = :kind) AND (:stereo = 0 OR stereo = 1)
        AND (takenAt < :takenAt OR (takenAt = :takenAt AND id < :id)) ORDER BY takenAt DESC, id DESC LIMIT :limit""")
    suspend fun page(kind: String?, stereo: Boolean, takenAt: Long, id: Long, limit: Int): List<IndexedMedia>
    @Query("SELECT COUNT(*) FROM photos WHERE (:kind IS NULL OR kind = :kind) AND (:stereo = 0 OR stereo = 1)")
    suspend fun count(kind: String?, stereo: Boolean): Int
    @Upsert suspend fun save(photo: IndexedMedia)
    @Upsert suspend fun saveAll(items: List<IndexedMedia>)
    @Query("DELETE FROM photos WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("SELECT * FROM peers") suspend fun peers(): List<PairedPeer>
    @Query("SELECT * FROM peers") fun observePeers(): Flow<List<PairedPeer>>
    @Upsert suspend fun savePeer(peer: PairedPeer)
    @Query("DELETE FROM peers WHERE clientId = :id") suspend fun deletePeer(id: String)
}

@Database(entities = [IndexedMedia::class, PairedPeer::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        /** v2 indexes every photo and video for TVs, not only 3D photos, and remembers which peers are TVs. */
        val MIGRATION_1_2_SQL = listOf(
            "ALTER TABLE `photos` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'photo'",
            "ALTER TABLE `photos` ADD COLUMN `mime` TEXT NOT NULL DEFAULT 'image/heic'",
            "ALTER TABLE `photos` ADD COLUMN `durationMs` INTEGER",
            "ALTER TABLE `photos` ADD COLUMN `hdr` TEXT NOT NULL DEFAULT 'none'",
            "ALTER TABLE `photos` ADD COLUMN `orientation` INTEGER NOT NULL DEFAULT 0",
            "CREATE INDEX IF NOT EXISTS `index_photos_takenAt_id` ON `photos` (`takenAt`, `id`)",
            // v1 rows were read before these columns existed; the next scan reads them again.
            "UPDATE `photos` SET `modified` = -1",
            "ALTER TABLE `peers` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'headset'",
        )
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = MIGRATION_1_2_SQL.forEach(db::execSQL)
        }
        fun create(context: Context) = Room.databaseBuilder(context, LibraryDatabase::class.java, "okuyuki.db")
            .addMigrations(MIGRATION_1_2).build()
    }
}
