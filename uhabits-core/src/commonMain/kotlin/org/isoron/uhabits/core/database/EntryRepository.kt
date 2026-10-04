package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.sync.SQLiteChangeStore

data class EntryData(
    var id: Long? = null,
    var habitId: Long? = null,
    var timestamp: Long = 0,
    var value: Int = 0,
    var notes: String = ""
)

class EntryRepository(private val db: Database, private val changeStore: SQLiteChangeStore? = null) {
    private val findAllByHabitStmt by lazy {
        db.prepareStatement(
            "SELECT id, habit, timestamp, value, notes FROM Repetitions WHERE habit = ? ORDER BY timestamp DESC"
        )
    }

    private val insertStmt by lazy {
        db.prepareStatement(
            "INSERT INTO Repetitions(habit, timestamp, value, notes) VALUES (?, ?, ?, ?)"
        )
    }

    private val deleteByHabitAndTimestampStmt by lazy {
        db.prepareStatement("DELETE FROM Repetitions WHERE habit = ? AND timestamp = ?")
    }

    private val deleteByHabitStmt by lazy {
        db.prepareStatement("DELETE FROM Repetitions WHERE habit = ?")
    }

    fun findAllByHabitId(habitId: Long): List<EntryData> {
        findAllByHabitStmt.reset()
        findAllByHabitStmt.bindLong(1, habitId)
        val results = mutableListOf<EntryData>()
        while (findAllByHabitStmt.step() == StepResult.ROW) {
            results.add(
                EntryData(
                    id = findAllByHabitStmt.getLong(0),
                    habitId = findAllByHabitStmt.getLong(1),
                    timestamp = findAllByHabitStmt.getLong(2),
                    value = findAllByHabitStmt.getInt(3),
                    notes = findAllByHabitStmt.getTextOrNull(4) ?: ""
                )
            )
        }
        return results
    }

    fun insert(data: EntryData): Long = mutate(data.habitId!!) { insertRaw(data) }

    private fun insertRaw(data: EntryData): Long {
        insertStmt.reset()
        insertStmt.bindLong(1, data.habitId!!)
        insertStmt.bindLong(2, data.timestamp)
        insertStmt.bindInt(3, data.value)
        insertStmt.bindText(4, data.notes)
        insertStmt.step()
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun deleteByHabitIdAndTimestamp(habitId: Long, timestamp: Long) = mutate(habitId) { deleteRaw(habitId, timestamp) }

    private fun deleteRaw(habitId: Long, timestamp: Long) {
        deleteByHabitAndTimestampStmt.reset()
        deleteByHabitAndTimestampStmt.bindLong(1, habitId)
        deleteByHabitAndTimestampStmt.bindLong(2, timestamp)
        deleteByHabitAndTimestampStmt.step()
    }

    fun deleteByHabitId(habitId: Long) = mutate(habitId) {
        deleteByHabitStmt.reset()
        deleteByHabitStmt.bindLong(1, habitId)
        deleteByHabitStmt.step()
    }

    fun execSQL(sql: String) = db.run(sql)

    fun replace(data: EntryData): Long = mutate(data.habitId!!) {
        db.run("SAVEPOINT loop_entry_replace")
        try {
            deleteRaw(data.habitId!!, data.timestamp)
            val id = insertRaw(data)
            db.run("RELEASE loop_entry_replace")
            id
        } catch (error: Throwable) {
            db.run("ROLLBACK TO loop_entry_replace")
            db.run("RELEASE loop_entry_replace")
            throw error
        }
    }

    private fun <T> mutate(habitId: Long, action: () -> T): T = changeStore?.captureEntries(habitId, action) ?: action()
}
