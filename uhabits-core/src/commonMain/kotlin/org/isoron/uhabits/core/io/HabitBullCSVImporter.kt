/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.io

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.io.UserFile
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory

/**
 * Class that imports data from HabitBull CSV files.
 */
@Inject
class HabitBullCSVImporter(
    private val habitList: HabitList,
    private val modelFactory: ModelFactory,
    logging: Logging
) : AbstractImporter() {

    private val logger = logging.getLogger("HabitBullCSVImporter")

    override suspend fun canHandle(file: UserFile): Boolean {
        return try {
            val lines = file.lines()
            if (lines.isEmpty()) return false
            lines[0].startsWith("HabitName,HabitDescription,HabitCategory")
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun importHabitsFromFile(file: UserFile) {
        val rows = parseRows(file.lines().joinToString("\n"))
        val numericNames = rows.drop(1).filter { it.size == 6 && it[0].isNotBlank() && parseInt(it[4]) > 1 }.map { it[0] }.toSet()
        val map = HashMap<String, Habit>()
        for (cols in rows) {
            if (cols == listOf("")) continue
            require(cols.size == 6) { "Invalid HabitBull CSV row. Expected six fields." }
            val name = cols[0]
            if (name == "HabitName") continue
            require(name.isNotBlank()) { "HabitBull habit name is missing." }
            val description = cols[1]
            val date = parseDate(cols[3])
            var h = map[name]
            if (h == null) {
                h = modelFactory.buildHabit()
                h.name = name
                h.description = description
                h.frequency = Frequency.DAILY
                if (name in numericNames) h.type = HabitType.NUMERICAL
                habitList.add(h)
                map[name] = h
                logger.info("Creating habit: $name")
            }
            val notes = cols[5]
            val value = parseInt(cols[4])
            if (h.type == HabitType.NUMERICAL) {
                h.originalEntries.add(Entry(date, amountMillis(value), notes))
                continue
            }
            h.originalEntries.add(Entry(date, if (value == 0) Entry.NO else Entry.YES_MANUAL, notes))
        }

        map.forEach { (_, habit) -> habit.recompute() }
    }

    private fun parseRows(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var index = 0
        fun finishField() { row.add(field.toString()); field.clear(); closed = false }
        fun finishRow() { finishField(); rows.add(row.toList()); row.clear() }
        while (index < content.length) {
            val char = content[index]
            if (quoted) {
                if (char == '"') {
                    if (content.getOrNull(index + 1) == '"') { field.append('"'); index++ } else { quoted = false; closed = true }
                } else {
                    field.append(char)
                }
            } else {
                when (char) {
                    '"' -> { require(field.isEmpty() && !closed) { "Malformed CSV quotation." }; quoted = true }
                    ',' -> finishField()
                    '\n', '\r' -> {
                        finishRow()
                        if (char == '\r' && content.getOrNull(index + 1) == '\n') index++
                    }
                    else -> { require(!closed) { "Unexpected text after CSV quotation." }; field.append(char) }
                }
            }
            index++
        }
        require(!quoted) { "Unterminated CSV quotation. Check the import file." }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        return rows
    }

    private fun parseDate(rawValue: String): LocalDate {
        val parts = if (rawValue.contains("/")) {
            val date = rawValue.split("/")
            require(date.size == 3) { "Invalid HabitBull date: $rawValue" }
            listOf(date[2], date[0], date[1])
        } else {
            rawValue.split("-")
        }
        require(parts.size == 3) { "Invalid HabitBull date: $rawValue" }
        val normalized = parts.map { it.toInt() }
        return org.isoron.uhabits.core.sync.RegisterValues.date(
            "${normalized[0]}-${normalized[1].toString().padStart(2, '0')}-${normalized[2].toString().padStart(2, '0')}"
        )
    }

    private fun parseInt(rawValue: String): Int = rawValue.toIntOrNull()?.also {
        require(it >= 0) { "HabitBull values must be non-negative." }
    } ?: throw IllegalArgumentException("Invalid HabitBull value: $rawValue")

    private fun amountMillis(value: Int): Int {
        require(value <= Int.MAX_VALUE / 1000) { "HabitBull amount is too large." }
        return value * 1000
    }
}
