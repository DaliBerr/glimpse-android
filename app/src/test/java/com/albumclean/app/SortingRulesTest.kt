package com.albumclean.app

import com.albumclean.app.data.*
import org.junit.Assert.*
import org.junit.Test

class SortingRulesTest {
    private fun photo(key: String) = Photo(key, "content://images/$key", "$key.jpg", 1500, 4000, 3000, 0, 0, "相机", "image/jpeg")

    @Test fun fourDirectionsFollowTheRequestedMapping() {
        assertEquals(Choice.DELETE, swipeChoice(-100f, 0f, 80f))
        assertEquals(Choice.DELETE, swipeChoice(0f, 100f, 80f))
        assertEquals(Choice.KEEP, swipeChoice(100f, 0f, 80f))
        assertEquals(Choice.KEEP, swipeChoice(0f, -100f, 80f))
    }
    @Test fun shortGesturesDoNotChangeDecisions() {
        assertNull(swipeChoice(79f, -79f, 80f))
        assertNull(swipeChoice(0f, 0f, 80f))
    }
    @Test fun diagonalUsesTheDominantDirection() {
        assertEquals(Choice.DELETE, swipeChoice(30f, 100f, 80f))
        assertEquals(Choice.KEEP, swipeChoice(100f, 90f, 80f))
        assertEquals(Choice.KEEP, swipeChoice(-90f, -110f, 80f))
    }
    @Test fun processedPhotosNeverReenterTheDeck() {
        val photos = (1..50).map { photo(it.toString()) }
        val decisions = listOf(Decision(photos[0], Choice.KEEP, 1), Decision(photos[1], Choice.DELETE, 2), Decision(photos[2], Choice.DELETED, 3))
        val deck = buildDeck(photos.map { it.key }, photos, decisions)
        assertEquals(47, deck.size)
        assertEquals(47, deck.toSet().size)
        assertFalse(deck.any { it in setOf("1", "2", "3") })
    }
    @Test fun refreshKeepsTheCurrentCardAndAddsNewPhotosOnce() {
        val deck = buildDeck(listOf("b", "a", "a", "gone"), listOf(photo("a"), photo("b"), photo("c")), emptyList())
        assertEquals(listOf("b", "a", "c"), deck)
    }
    @Test fun undoMakesTheExactPhotoNextAgain() {
        val photos = listOf(photo("a"), photo("b"), photo("c"))
        assertEquals(listOf("a", "c"), buildDeck(listOf("a", "c"), photos, listOf(Decision(photo("b"), Choice.DELETE, 1))))
    }
    @Test fun unknownMetadataIsExplicitAndUnitsAreBinary() {
        assertEquals("添加时间", photo("a").dateLabel)
        assertEquals("时间未记录", photo("a").dateText)
        assertEquals("4000 × 3000", photo("a").dimensions)
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
    }
    @Test fun exifTimeHandlesTimezoneAndMissingDates() {
        assertEquals(0L, parseExifTime(null, null))
        assertEquals(0L, parseExifTime("0000:00:00 00:00:00", null))
        assertEquals(parseExifTime("2024:10:04 14:32:00", "+02:00"), parseExifTime("2024:10:04 12:32:00", "+00:00"))
        assertTrue(parseExifTime("2024:10:04 14:32:00", "+02:00") > 0L)
    }
}
