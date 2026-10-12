package com.okuyuki.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FormatTest {
    @Test fun `a Latin name keeps a space before Japanese`() {
        assertEquals("Quest がつなごうとしています", named("Quest", "がつなごうとしています"))
        assertEquals("テレビがつなごうとしています", named("テレビ", "がつなごうとしています"))
    }

    @Test fun `days read the same as on Quest`() {
        val today = LocalDate.of(2026, 10, 12)
        assertEquals("今日", dayLabel(today, today))
        assertEquals("昨日", dayLabel(today.minusDays(1), today))
        assertEquals("10月9日（金）", dayLabel(LocalDate.of(2026, 10, 9), today))
        assertEquals("2025年12月31日（水）", dayLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test fun `durations and sizes are written for reading`() {
        assertEquals("0:07", durationLabel(7_400))
        assertEquals("1:02:03", durationLabel(3_723_000))
        assertEquals("7 MB", sizeLabel(6_880_000))
        assertEquals("1.0 GB", sizeLabel(1L shl 30))
    }
}
