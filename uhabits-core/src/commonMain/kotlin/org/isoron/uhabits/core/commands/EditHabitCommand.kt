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
package org.isoron.uhabits.core.commands

import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitNotFoundException

data class EditHabitCommand(
    val habitList: HabitList,
    val habitId: Long,
    val modified: Habit,
    val baseline: Habit? = null
) : Command {
    override fun run() {
        val habit = habitList.getById(habitId) ?: throw HabitNotFoundException()
        val previous = org.isoron.uhabits.core.models.memory.MemoryModelFactory().buildHabit().apply { copyFrom(habit) }
        if (baseline == null) {
            habit.copyFrom(modified)
        } else {
            if (modified.name != baseline.name) habit.name = modified.name
            if (modified.question != baseline.question) habit.question = modified.question
            if (modified.description != baseline.description) habit.description = modified.description
            if (modified.color != baseline.color) habit.color = modified.color
            if (modified.reminder != baseline.reminder) habit.reminder = modified.reminder
            if (modified.isArchived != baseline.isArchived) habit.isArchived = modified.isArchived
            if (modified.position != baseline.position) habit.position = modified.position
            // Frequency, type, unit and numeric target form one coherent register.
            if (modified.frequency != baseline.frequency || modified.type != baseline.type ||
                modified.targetValue != baseline.targetValue || modified.targetType != baseline.targetType ||
                modified.unit != baseline.unit
            ) {
                habit.frequency = modified.frequency
                habit.type = modified.type
                habit.targetValue = modified.targetValue
                habit.targetType = modified.targetType
                habit.unit = modified.unit
            }
        }
        try {
            habitList.update(habit)
        } catch (error: Throwable) {
            habit.copyFrom(previous)
            throw error
        }
        habit.observable.notifyListeners()
        habit.recompute()
        habitList.resort()
    }
}
