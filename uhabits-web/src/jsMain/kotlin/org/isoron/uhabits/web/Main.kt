package org.isoron.uhabits.web

import kotlinx.browser.window
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitProgress

fun main() {
    // A small JS-facing seam: the browser uses the same calculations as Android.
    window.asDynamic().loopProbe = { amountMillis: Int, notes: String ->
        require(amountMillis >= 0) { "Numeric amount must be non-negative" }
        val date = LocalDate(2026, 10, 1)
        val progress = HabitProgress.evaluate(
            frequency = Frequency.DAILY,
            entries = listOf(Entry(date, amountMillis, notes)),
            from = date,
            to = date,
            isNumerical = true,
            targetValue = 10.0
        )
        val result = js("({})")
        result.date = progress.originalEntries.single().date.toCSVString()
        result.amountMillis = progress.originalEntries.single().value
        result.notes = progress.originalEntries.single().notes
        result.score = progress.scores.single().value
        result.streakLength = progress.streaks.firstOrNull()?.length ?: 0
        JSON.stringify(result)
    }
    window.dispatchEvent(js("new Event('loop-core-ready')"))
}
