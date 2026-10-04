package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate

/** A bounded, platform-independent progress view derived from original habit entries. */
data class HabitProgress(
    val originalEntries: List<Entry>,
    val computedEntries: List<Entry>,
    val scores: List<Score>,
    val streaks: List<Streak>
) {
    companion object {
        fun evaluate(
            frequency: Frequency,
            entries: List<Entry>,
            from: LocalDate,
            to: LocalDate,
            isNumerical: Boolean = false,
            targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
            targetValue: Double = 0.0
        ): HabitProgress {
            require(from <= to) { "Progress interval must be ordered" }
            require(frequency.numerator > 0 && frequency.numerator <= frequency.denominator) {
                "Frequency must be between zero and one"
            }
            require(targetValue.isFinite() && targetValue >= 0) { "Target must be finite and non-negative" }
            val original = EntryList()
            entries.forEach { original.add(it) }
            val computed = EntryList()
            computed.recomputeFrom(original, frequency, isNumerical)
            val scores = ScoreList()
            val scoreStart = minOf(from, computed.getKnown().lastOrNull()?.date ?: from)
            scores.recompute(frequency, isNumerical, targetType, targetValue, computed, scoreStart, to)
            val streaks = StreakList()
            streaks.recompute(computed, from, to, isNumerical, targetValue, targetType)
            return HabitProgress(
                original.getByInterval(from, to),
                computed.getByInterval(from, to),
                scores.getByInterval(from, to),
                streaks.getBest(Int.MAX_VALUE)
            )
        }
    }
}
