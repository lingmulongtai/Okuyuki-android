package com.okuyuki.android.library

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64

class LibraryTest {
    @Test fun `cursors survive the round trip and others are refused`() {
        val cursor = LibraryCursor(1_700_000_000_000, 42)
        assertEquals(cursor, LibraryCursor.decode(cursor.encode()))
        assertFalse(cursor.encode().any { it in "+/=" })
        assertNull(LibraryCursor.decode("not a cursor"))
        assertNull(LibraryCursor.decode(Base64.getUrlEncoder().encodeToString("1:2:3".toByteArray())))
        assertNull(LibraryCursor.decode(Base64.getUrlEncoder().encodeToString("a:2".toByteArray())))
    }

    @Test fun `gain map metadata marks Ultra HDR photos`() {
        val xmp = """<x:xmpmeta><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/" hdrgm:Version="1.0"/></x:xmpmeta>"""
        assertTrue(HdrMarkers.hasGainMap(ByteArray(1000) + xmp.toByteArray()))
        assertTrue(HdrMarkers.hasGainMap("urn:iso:std:iso:ts:21496:-1".toByteArray()))
        assertFalse(HdrMarkers.hasGainMap(ByteArray(4096) { (it % 251).toByte() }))
        assertFalse(HdrMarkers.hasGainMap(ByteArray(0)))
    }

    @Test fun `TVs see sizes after rotation and durations only for videos`() {
        val photo = IndexedMedia(1, 10, false, "a.jpg", 5, 4000, 3000, 100, orientation = 90)
        assertEquals(3000, photo.item().width)
        assertEquals(4000, photo.item().height)
        assertNull(photo.copy(durationMs = 5).item().durationMs)
        val video = IndexedMedia(2, 10, false, "b.mp4", 5, 3840, 2160, 100, IndexedMedia.VIDEO, "video/mp4", 12_000, "hlg")
        assertEquals(12_000L, video.item().durationMs)
        assertEquals("hlg", video.item().hdr)
        assertEquals("video", video.item().kind)
        assertEquals("2", video.item().id)
    }

    @Test fun `version 1 databases migrate to exactly the version 2 schema`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, 1)
            migrated.createStatement().use {
                it.execute("INSERT INTO photos VALUES (7, 100, 1, 'IMG_7.HEIC', 5, 4000, 3000, 1000)")
                it.execute("INSERT INTO peers VALUES ('quest', 'Quest 3', 'hash')")
                LibraryDatabase.MIGRATION_1_2_SQL.forEach(it::execute)
            }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, 2)
                for (table in listOf("photos", "peers")) assertEquals(describe(fresh, table), describe(migrated, table))
            }
            migrated.createStatement().use { statement ->
                statement.executeQuery("SELECT kind, mime, durationMs, hdr, orientation, modified FROM photos").use { row ->
                    assertTrue(row.next())
                    assertEquals("photo", row.getString(1))
                    assertEquals("image/heic", row.getString(2))
                    assertNull(row.getObject(3))
                    assertEquals("none", row.getString(4))
                    assertEquals(0, row.getInt(5))
                    // The next scan reads the photo again and fills in the new columns.
                    assertEquals(-1L, row.getLong(6))
                }
                statement.executeQuery("SELECT kind FROM peers").use { row ->
                    assertTrue(row.next())
                    assertEquals("headset", row.getString(1))
                }
            }
        }
    }

    /** Creates the tables and indices of a schema Room exported. */
    private fun create(connection: Connection, version: Int) {
        val schema = File("schemas/com.okuyuki.android.library.LibraryDatabase/$version.json")
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        connection.createStatement().use { statement ->
            for (entity in entities.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                statement.execute(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach {
                    statement.execute(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    /** Columns with their type, nullability, default and key, then indices; what Room checks when it opens a database. */
    private fun describe(connection: Connection, table: String): List<String> = connection.createStatement().use { statement ->
        val columns = statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
            buildList {
                while (rows.next()) add(listOf(rows.getString("name"), rows.getString("type"), rows.getInt("notnull"),
                    rows.getString("dflt_value"), rows.getInt("pk")).joinToString())
            }
        }
        val indices = statement.executeQuery("PRAGMA index_list(`$table`)").use { rows ->
            buildList { while (rows.next()) add(rows.getString("name") to rows.getInt("unique")) }
        }.filterNot { it.first.startsWith("sqlite_autoindex") }.map { (name, unique) ->
            val indexed = statement.executeQuery("PRAGMA index_info(`$name`)").use { rows ->
                buildList { while (rows.next()) add(rows.getString("name")) }
            }
            "index $name unique=$unique $indexed"
        }
        columns.sorted() + indices.sorted()
    }
}
