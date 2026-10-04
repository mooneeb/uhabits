package org.isoron.uhabits.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.isoron.platform.Synchronized
import org.isoron.platform.io.Database
import org.isoron.platform.io.query
import org.isoron.platform.io.querySingle
import org.isoron.platform.io.run
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.database.EntryRepository
import org.isoron.uhabits.core.database.HabitData
import org.isoron.uhabits.core.database.HabitRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Native mutations and their causal changes share the habit database transaction. */
@OptIn(ExperimentalUuidApi::class)
class SQLiteChangeStore(private val db: Database) {
    var onLocalChange: (() -> Unit)? = null
    init {
        db.run("CREATE TABLE IF NOT EXISTS LoopSyncState (id INTEGER PRIMARY KEY, device TEXT NOT NULL, history TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0)")
        db.run("CREATE TABLE IF NOT EXISTS LoopSyncFiles (id TEXT PRIMARY KEY)")
        db.run("INSERT OR IGNORE INTO LoopSyncState(id, device, history) VALUES (1, ?, ?)") {
            bindText(1, Uuid.random().toHexString())
            bindText(2, ChangeHistory("unbound").encode())
        }
    }

    val deviceId: String
        get() = db.querySingle("SELECT device FROM LoopSyncState WHERE id=1") { it.getText(0) }!!

    @Synchronized
    fun history(): ChangeHistory = ChangeHistory.decode(db.querySingle("SELECT history FROM LoopSyncState WHERE id=1") { it.getText(0) }!!)

    @Synchronized
    fun pendingCount(): Int {
        val acknowledged = db.querySingle("SELECT acknowledged FROM LoopSyncState WHERE id=1") { it.getInt(0) }!!
        return history().changes.count { it.deviceId == deviceId && it.sequence > acknowledged }
    }

    @Synchronized
    fun connectAccount(accountId: String) = atomic {
        require(accountId.isNotBlank() && accountId != "unbound") { "Missing Google account identity" }
        val current = history()
        require(current.accountId == "unbound" || current.accountId == accountId) { "This device's data belongs to another Google account" }
        persist(current.copy(accountId = accountId))
    }

    @Synchronized
    fun merge(incoming: ChangeHistory) = mergeBatch(listOf(incoming), emptySet())

    @Synchronized
    fun mergeBatch(incoming: List<ChangeHistory>, fileIds: Set<String>) = atomic {
        val next = incoming.fold(history()) { current, history -> current.merge(history) }
        applyToNative(next)
        persist(next)
        for (id in fileIds) db.run("INSERT OR IGNORE INTO LoopSyncFiles(id) VALUES (?)") { bindText(1, id) }
    }

    @Synchronized
    fun knownFiles(): Set<String> {
        val result = mutableSetOf<String>()
        db.query("SELECT id FROM LoopSyncFiles") { result.add(it.getText(0)) }
        return result
    }

    @Synchronized
    fun acknowledge(sequence: Int) {
        require(sequence in 0..(history().clock[deviceId] ?: 0)) { "Cannot acknowledge an unsaved change" }
        db.run("UPDATE LoopSyncState SET acknowledged=MAX(acknowledged, ?) WHERE id=1") { bindInt(1, sequence) }
    }

    @Synchronized
    fun updateSetting(key: String, value: Int) = atomic {
        val register = "setting:$key"
        val current = history()
        if (current.value(register) != JsonPrimitive(value)) append(mapOf(register to JsonPrimitive(value)))
    }

    @Synchronized
    fun resolve(key: String, value: JsonElement, revisions: Set<String>) = saveLocal {
        it.resolve(deviceId, Uuid.random().toHexString(), key, value, revisions)
    }

    @Synchronized
    fun restore(uuid: String) = saveLocal { it.restore(deviceId, Uuid.random().toHexString(), uuid) }

    @Synchronized
    fun purge(uuid: String) = saveLocal { it.purge(deviceId, Uuid.random().toHexString(), uuid) }

    @Synchronized
    fun importBackup(backup: TrackingBackup) = saveLocal {
        backup.restoreInto(it, Uuid.random().toHexString(), deviceId)
    }

    private fun saveLocal(change: (ChangeHistory) -> ChangeHistory) {
        atomic {
            val next = change(history())
            applyToNative(next)
            persist(next)
        }
        onLocalChange?.invoke()
    }

    private fun applyToNative(history: ChangeHistory) {
        val repository = HabitRepository(db)
        val entries = EntryRepository(db)
        val existing = repository.findAll().associateBy { it.uuid }.toMutableMap()
        for (uuid in history.purgedHabits) {
            existing.remove(uuid)?.id?.let { id -> entries.deleteByHabitId(id); repository.delete(id) }
        }
        val keys = history.changes.flatMap { it.edits.keys }.toSet()
        val ids = keys.filter { it.startsWith("habit:") }.map { it.split(':')[1] }.toSet()
        for (uuid in ids) {
            val prefix = "habit:$uuid:"
            fun value(field: String) = history.value(prefix + field)
            val deleted = (value("deleted") as? JsonPrimitive)?.boolean ?: false
            if (deleted) {
                existing[uuid]?.id?.let { id -> entries.deleteByHabitId(id); repository.delete(id) }
                existing.remove(uuid)
                continue
            }
            val previous = existing[uuid]
            if (previous == null && (value("name") == null || value("tracking") == null)) continue
            val habit = previous?.copy() ?: HabitData(uuid = uuid)
            value("name")?.let { habit.name = RegisterValues.text(it) }
            value("question")?.let { habit.question = RegisterValues.text(it) }
            value("description")?.let { habit.description = RegisterValues.text(it) }
            value("color")?.let { habit.color = RegisterValues.number(it) }
            value("position")?.let { habit.position = RegisterValues.number(it) }
            value("archived")?.let { habit.archived = if ((it as JsonPrimitive).boolean) 1 else 0 }
            value("tracking")?.let {
                val tracking = TrackingSettings.fromJson(it)
                habit.type = tracking.type
                habit.freqNum = tracking.freqNum
                habit.freqDen = tracking.freqDen
                habit.targetValue = tracking.targetValue
                habit.targetType = tracking.targetType
                habit.unit = tracking.unit
            }
            value("reminder")?.let {
                if (it == JsonNull) {
                    habit.reminderHour = null
                    habit.reminderMin = null
                    habit.reminderDays = 0
                } else {
                    val reminder = Json.decodeFromJsonElement<ReminderSettings>(it)
                    habit.reminderHour = reminder.hour
                    habit.reminderMin = reminder.minute
                    habit.reminderDays = reminder.days
                }
            }
            if (previous == null) habit.id = repository.insert(habit) else if (habit != previous) repository.update(habit)
            existing[uuid] = habit
        }
        for ((uuid, habit) in existing) {
            val known = entries.findAllByHabitId(habit.id!!).associateBy { LocalDate.fromUnixTime(it.timestamp).toCSVString() }
            for (key in keys.filter { it.startsWith("entry:$uuid:") }) {
                val value = history.value(key) ?: continue
                val record = RegisterValues.entry(value)
                val date = key.split(':')[2]
                val previous = known[date]
                if (previous?.value != record.value || previous.notes != record.notes) {
                    entries.replace(org.isoron.uhabits.core.database.EntryData(habitId = habit.id, timestamp = RegisterValues.date(date).unixTime, value = record.value, notes = record.notes))
                }
            }
        }
    }

    @Synchronized
    fun <T> captureHabits(mutation: () -> T): T = atomic {
        val repository = HabitRepository(db)
        val before = repository.findAll().associateBy { it.uuid }
        val result = mutation()
        val after = repository.findAll().associateBy { it.uuid }
        val edits = mutableMapOf<String, JsonElement>()
        for ((id, habit) in after) {
            require(id != null) { "Habit requires a stable identity" }
            val previous = before[id]?.let { habitFields(it) } ?: emptyMap()
            for ((key, value) in habitFields(habit)) if (previous[key] != value) edits[key] = value
        }
        for (id in before.keys - after.keys) edits["habit:$id:deleted"] = JsonPrimitive(true)
        append(edits)
        result
    }

    @Synchronized
    fun <T> captureEntries(habitId: Long, mutation: () -> T): T = atomic {
        val uuid = db.querySingle("SELECT uuid FROM Habits WHERE id=?", habitId.toString()) { it.getText(0) }
            ?: throw IllegalArgumentException("Entry requires an existing habit")
        val repository = EntryRepository(db)
        fun entries() = repository.findAllByHabitId(habitId).associate { entry ->
            "entry:$uuid:${LocalDate.fromUnixTime(entry.timestamp).toCSVString()}" to Json.encodeToJsonElement(RecordedEntry(entry.value, entry.notes))
        }
        val before = entries()
        val result = mutation()
        val after = entries()
        val edits = after.filter { (key, value) -> before[key] != value }.toMutableMap()
        for (key in before.keys - after.keys) edits[key] = Json.encodeToJsonElement(RecordedEntry(-1, ""))
        append(edits)
        result
    }

    private fun append(edits: Map<String, JsonElement>) {
        if (edits.isEmpty()) return
        val next = history().edit(deviceId, Uuid.random().toHexString(), edits)
        persist(next)
        onLocalChange?.invoke()
    }

    private fun persist(history: ChangeHistory) = db.run("UPDATE LoopSyncState SET history=? WHERE id=1") { bindText(1, history.encode()) }

    private fun <T> atomic(action: () -> T): T {
        db.run("SAVEPOINT loop_sync_mutation")
        try {
            val result = action()
            db.run("RELEASE loop_sync_mutation")
            return result
        } catch (error: Throwable) {
            db.run("ROLLBACK TO loop_sync_mutation")
            db.run("RELEASE loop_sync_mutation")
            throw error
        }
    }

    companion object {
        fun habitFields(habit: HabitData): Map<String, JsonElement> {
            val prefix = "habit:${habit.uuid}:"
            return mapOf(
                "${prefix}name" to JsonPrimitive(habit.name),
                "${prefix}question" to JsonPrimitive(habit.question),
                "${prefix}description" to JsonPrimitive(habit.description),
                "${prefix}color" to JsonPrimitive(habit.color),
                "${prefix}position" to JsonPrimitive(habit.position),
                "${prefix}archived" to JsonPrimitive(habit.archived != 0),
                "${prefix}deleted" to JsonPrimitive(false),
                "${prefix}tracking" to Json.encodeToJsonElement(TrackingSettings(habit.type, habit.freqNum, habit.freqDen, habit.targetValue, habit.targetType, habit.unit)),
                "${prefix}reminder" to if (habit.reminderHour != null && habit.reminderMin != null) {
                    Json.encodeToJsonElement(ReminderSettings(habit.reminderHour!!, habit.reminderMin!!, habit.reminderDays))
                } else {
                    JsonNull
                }
            )
        }
    }
}
