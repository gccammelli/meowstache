package io.github.gccammelli.meowstache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipsTest {
    @Test fun clipNamesAreUtcTimes() {
        assertEquals(1_792_084_173_000L, clipTime("2026-10-15T17-09-33Z.mp4"))
        assertEquals(1_792_084_173_000L, clipTime("media-source://media_source/local/meowstache/2026-10-15T17-09-33Z.MP4"))
        assertNull(clipTime("2026-10-15T17-09-33Z.jpg"))
        assertNull(clipTime("notes.mp4"))
    }

    @Test fun visitsGetTheNearestClipInTheirWindow() {
        val w = 1_792_084_173_000L
        val stream = Clip(w - 80_000, "a") // recorded from the cat detection, before the weighing
        val ring = Clip(w + 4 * 60_000, "b") // Ring's recording, saved a few minutes later
        assertEquals(stream, clipFor(w, listOf(ring, stream)))
        assertEquals(ring, clipFor(w, listOf(ring)))
        assertNull(clipFor(w, listOf(Clip(w - 6 * 60_000, "old"), Clip(w + 11 * 60_000, "next"))))
    }
}
