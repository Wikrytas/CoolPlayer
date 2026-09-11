package com.wikrytas.coolplayer

import com.wikrytas.coolplayer.ui.screens.parseLrcContent
import com.wikrytas.coolplayer.ui.screens.parsePlainLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {

    @Test
    fun lrcParsedWithTimestamps() {
        val lines = parseLrcContent("[00:12.00]Привет\n[01:04.50]Мир")
        assertEquals(2, lines.size)
        assertEquals(12_000L, lines[0].timeMs)
        assertEquals("Привет", lines[0].text)
        assertEquals(64_500L, lines[1].timeMs)
        assertEquals("Мир", lines[1].text)
    }

    @Test
    fun lrcSkipsMalformedLines() {
        val lines = parseLrcContent("мусор без таймкода\n[00:05.00]Норм\n[xx]битый таймкод")
        assertEquals(1, lines.size)
        assertEquals(5_000L, lines[0].timeMs)
        assertEquals("Норм", lines[0].text)
    }

    @Test
    fun lrcEmptyContentGivesEmptyList() {
        assertTrue(parseLrcContent("").isEmpty())
        assertTrue(parseLrcContent("   \n  \n").isEmpty())
    }

    @Test
    fun plainLyricsKeepOrderAndNonDecreasingTime() {
        val lines = parsePlainLyrics("Строка один\nСтрока два\nСтрока три")
        assertEquals(3, lines.size)
        assertEquals("Строка один", lines[0].text)
        assertEquals("Строка два", lines[1].text)
        assertEquals("Строка три", lines[2].text)
        assertTrue(lines[0].timeMs <= lines[1].timeMs)
        assertTrue(lines[1].timeMs <= lines[2].timeMs)
    }

    @Test
    fun plainLyricsSkipBlankLines() {
        val lines = parsePlainLyrics("Первая\n\n   \nВторая")
        assertEquals(2, lines.size)
        assertEquals("Первая", lines[0].text)
        assertEquals("Вторая", lines[1].text)
    }
}