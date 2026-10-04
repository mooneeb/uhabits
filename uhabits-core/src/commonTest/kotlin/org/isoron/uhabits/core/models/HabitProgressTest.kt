package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitProgressTest {
    private val day = LocalDate(2026, 10, 1)

    @Test
    fun preservesOriginalOutcomesAndEntryNotes() {
        val progress = HabitProgress.evaluate(
            frequency = Frequency.DAILY,
            entries = listOf(
                Entry(day, Entry.YES_MANUAL, "A dated note"),
                Entry(day.plus(1), Entry.NO),
                Entry(day.plus(2), Entry.SKIP)
            ),
            from = day,
            to = day.plus(3)
        )

        assertEquals(listOf(-1, 3, 0, 2), progress.originalEntries.map { it.value })
        assertEquals("A dated note", progress.originalEntries.last().notes)
        assertEquals(listOf(1, 1), progress.streaks.map { it.length })
        assertTrue(kotlin.math.abs(progress.scores.last().value - 0.051922) < 0.000001)
    }

    @Test
    fun viewingALaterIntervalRetainsScoreFromEarlierEntries() {
        val progress = HabitProgress.evaluate(
            frequency = Frequency.DAILY,
            entries = listOf(Entry(day, Entry.YES_MANUAL), Entry(day.plus(1), Entry.YES_MANUAL)),
            from = day.plus(1),
            to = day.plus(1)
        )
        assertTrue(kotlin.math.abs(progress.scores.single().value - 0.101149) < 0.000001)
    }
}
