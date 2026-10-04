package org.isoron.uhabits.web

import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitProgress
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.sync.ChangeHistory
import org.isoron.uhabits.core.sync.DrivePack
import org.isoron.uhabits.core.sync.RegisterValues
import org.isoron.uhabits.core.sync.ReminderSettings
import org.isoron.uhabits.core.sync.TrackingSettings
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardPresenter
import org.isoron.uhabits.core.ui.views.LightTheme

fun main() {
    window.asDynamic().loopHistory = { account: String -> ChangeHistory(account).encode() }
    window.asDynamic().loopEdit = { content: String, device: String, id: String, edits: String ->
        val patch = Json.parseToJsonElement(edits) as JsonObject
        ChangeHistory.decode(content).edit(device, id, patch).encode()
    }
    window.asDynamic().loopMerge = { local: String, remote: String -> ChangeHistory.decode(local).merge(ChangeHistory.decode(remote)).encode() }
    window.asDynamic().loopResolve = { content: String, device: String, id: String, key: String, value: String, revisions: String ->
        ChangeHistory.decode(content).resolve(device, id, key, Json.parseToJsonElement(value), Json.decodeFromString<Set<String>>(revisions)).encode()
    }
    window.asDynamic().loopRestore = { content: String, device: String, id: String, uuid: String -> ChangeHistory.decode(content).restore(device, id, uuid).encode() }
    window.asDynamic().loopPurge = { content: String, device: String, id: String, uuid: String -> ChangeHistory.decode(content).purge(device, id, uuid).encode() }
    window.asDynamic().loopBindAccount = { content: String, account: String ->
        val history = ChangeHistory.decode(content)
        require(history.accountId == "unbound" || history.accountId == account) { "Stored habits belong to another Google account" }
        history.copy(accountId = account).encode()
    }
    window.asDynamic().loopPack = { content: String, device: String, workspace: String ->
        DrivePack.create(ChangeHistory.decode(content), device, workspace)?.encode()
    }
    window.asDynamic().loopDecodePack = { content: String, account: String, workspace: String -> DrivePack.decode(content, account, workspace).encode() }
    window.asDynamic().loopPayloadHabits = { content: String, account: String, workspace: String -> Json.encodeToString(DrivePack.payloadHabits(content, account, workspace)) }
    window.asDynamic().loopView = { content: String, today: String, period: Int -> view(ChangeHistory.decode(content), today, period) }
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

private fun objectValue(): dynamic = js("({})")

private fun view(history: ChangeHistory, today: String, period: Int): String {
    require(period in 0..4)
    val date = RegisterValues.date(today)
    setToday(date)
    val result = objectValue()
    val keys = history.keys()
    val weekStart = history.value("setting:weekStart")?.let { RegisterValues.number(it) } ?: 1
    result.weekStart = weekStart
    result.dayStart = history.value("setting:dayStart")?.let { RegisterValues.number(it) } ?: 0
    val conflicts = objectValue()
    for (key in keys) {
        val candidates = history.candidates(key)
        if (candidates.map { it.value }.distinct().size > 1) {
            conflicts[key] = candidates.map { revision ->
                val item = objectValue()
                item.id = revision.changeId
                item.value = JSON.parse<dynamic>(revision.value.toString())
                item
            }.toTypedArray()
        }
    }
    result.conflicts = conflicts
    val factory = MemoryModelFactory()
    result.habits = keys.filter { it.startsWith("habit:") }.map { it.split(':')[1] }.distinct().mapNotNull { uuid ->
        fun value(field: String) = history.value("habit:$uuid:$field")
        val trackingJson = value("tracking")
        val tracking = trackingJson?.let { TrackingSettings.fromJson(it) }
        val item = objectValue()
        item.uuid = uuid
        item.deleted = (value("deleted") as? JsonPrimitive)?.booleanOrNull == true
        item.name = value("name")?.let { RegisterValues.text(it) } ?: "Habit with competing names"
        item.question = value("question")?.let { RegisterValues.text(it) } ?: ""
        item.description = value("description")?.let { RegisterValues.text(it) } ?: ""
        item.color = value("color")?.let { RegisterValues.number(it) } ?: 8
        item.position = value("position")?.let { RegisterValues.number(it) } ?: 0
        item.archived = (value("archived") as? JsonPrimitive)?.booleanOrNull ?: false
        item.tracking = trackingJson?.let { JSON.parse<dynamic>(it.toString()) }
        item.reminder = value("reminder")?.let { JSON.parse<dynamic>(it.toString()) }
        if (tracking == null) return@mapNotNull item
        val habit = factory.buildHabit()
        val data = HabitData(
            uuid = uuid, name = item.name as String, question = item.question as String,
            description = item.description as String, color = item.color as Int, position = item.position as Int,
            archived = if (item.archived as Boolean) 1 else 0, type = tracking.type,
            freqNum = tracking.freqNum, freqDen = tracking.freqDen, targetValue = tracking.targetValue,
            targetType = tracking.targetType, unit = tracking.unit
        )
        val reminder = value("reminder")
        if (reminder != null && reminder is JsonObject) {
            val setting = Json.decodeFromJsonElement<ReminderSettings>(reminder)
            data.reminderHour = setting.hour
            data.reminderMin = setting.minute
            data.reminderDays = setting.days
        }
        SQLiteHabitList.copyTo(data, habit)
        for (key in keys.filter { it.startsWith("entry:$uuid:") }) {
            val entry = history.value(key)?.let { RegisterValues.entry(it) } ?: continue
            habit.originalEntries.add(Entry(RegisterValues.date(key.split(':')[2]), entry.value, entry.notes))
        }
        habit.recompute()
        val oldest = minOf(date.minus(364), habit.originalEntries.getKnown().lastOrNull()?.date ?: date)
        item.history = habit.originalEntries.getByInterval(oldest, date).map { entry ->
            val record = objectValue()
            record.date = entry.date.toCSVString()
            record.recorded = "entry:$uuid:${entry.date.toCSVString()}" in keys
            record.value = entry.value
            record.notes = entry.notes
            record.computed = habit.computedEntries.get(entry.date).value
            record.score = habit.scores.get(entry.date).value
            record
        }.toTypedArray()
        item.completed = habit.isCompletedToday()
        item.score = habit.scores.get(date).value
        item.streaks = habit.streaks.getBest(20).map { streak ->
            val record = objectValue()
            record.start = streak.start.toCSVString()
            record.end = streak.end.toCSVString()
            record.length = streak.length
            record
        }.toTypedArray()
        item.scores = ScoreCardPresenter.buildState(habit, weekStart, period, LightTheme()).scores.map { score ->
            val record = objectValue()
            record.date = score.date.toCSVString()
            record.value = score.value
            record
        }.toTypedArray()
        item.bars = BarCardPresenter.buildState(habit, weekStart, period, maxOf(0, period - 1), LightTheme()).entries.map { entry ->
            val record = objectValue()
            record.date = entry.date.toCSVString()
            record.recorded = "entry:$uuid:${entry.date.toCSVString()}" in keys
            record.value = entry.value / 1000.0
            record
        }.toTypedArray()
        val targets = TargetCardPresenter.buildState(habit, weekStart, LightTheme())
        item.targets = targets.values.mapIndexed { index, amount ->
            val record = objectValue()
            record.days = targets.intervals[index]
            record.value = amount
            record.target = targets.targets[index]
            record
        }.toTypedArray()
        item.weekdays = habit.originalEntries.computeWeekdayFrequency(habit.isNumerical).entries.sortedByDescending { it.key }.map { (month, counts) ->
            val record = objectValue()
            record.month = month.toCSVString()
            record.values = counts
            record
        }.toTypedArray()
        item
    }.toTypedArray()
    return JSON.stringify(result)
}
