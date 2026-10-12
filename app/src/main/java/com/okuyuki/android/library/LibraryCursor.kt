package com.okuyuki.android.library

import java.util.Base64

/**
 * A TV's paging cursor: the last item's takenAt and id. Pages are keyset pages, so they stay
 * consistent while photos are added or removed between requests.
 */
data class LibraryCursor(val takenAt: Long, val id: Long) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$takenAt:$id".toByteArray())

    companion object {
        val START = LibraryCursor(Long.MAX_VALUE, Long.MAX_VALUE)

        fun decode(text: String): LibraryCursor? = runCatching {
            val parts = String(Base64.getUrlDecoder().decode(text)).split(':')
            require(parts.size == 2)
            LibraryCursor(parts[0].toLong(), parts[1].toLong())
        }.getOrNull()
    }
}
