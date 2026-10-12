package com.okuyuki.android.ui

import com.okuyuki.android.library.IndexedMedia
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthDay = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)
private val yearMonthDay = DateTimeFormatter.ofPattern("yyyy年M月d日（E）", Locale.JAPANESE)
private val clock = DateTimeFormatter.ofPattern("H:mm", Locale.JAPANESE)

fun localDay(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

/** 今日, 昨日, 10月9日（木）, or with the year when it is not this year. */
fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "今日"
    today.minusDays(1) -> "昨日"
    else -> (if (day.year == today.year) monthDay else yearMonthDay).format(day)
}

fun dateTimeLabel(time: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val moment = Instant.ofEpochMilli(time).atZone(zone)
    return "${dayLabel(moment.toLocalDate())} ${clock.format(moment)}"
}

fun durationLabel(ms: Long?): String {
    val seconds = ((ms ?: 0) / 1000).toInt()
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes.coerceAtLeast(1024) / 1024.0)
}

/** 8K, 4K, 1080p or 720p for videos; megapixels for photos. */
fun resolutionLabel(media: IndexedMedia): String {
    val long = maxOf(media.width, media.height)
    val short = minOf(media.width, media.height)
    if (!media.isVideo) {
        val megapixels = media.width.toLong() * media.height / 1_000_000.0
        return if (megapixels >= 1) "%.0f MP".format(megapixels) else "${media.width}×${media.height}"
    }
    return when {
        long >= 7680 -> "8K"
        long >= 3840 -> "4K"
        short >= 1080 -> "1080p"
        short >= 720 -> "720p"
        else -> "${short}p"
    }
}

val HDR_LABELS = mapOf("ultrahdr" to "Ultra HDR", "hdr10" to "HDR10", "hdr10plus" to "HDR10+", "hlg" to "HLG")

fun formatLabel(mime: String): String = when (val subtype = mime.substringAfter('/').lowercase()) {
    "quicktime" -> "MOV"
    "x-matroska" -> "MKV"
    "3gpp" -> "3GP"
    else -> subtype.uppercase()
}
