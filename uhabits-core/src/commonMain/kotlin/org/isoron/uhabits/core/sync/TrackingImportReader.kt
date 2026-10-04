package org.isoron.uhabits.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.isoron.platform.io.Database
import org.isoron.platform.io.DatabaseOpener
import org.isoron.platform.io.FileOpener
import org.isoron.platform.io.UserFile
import org.isoron.platform.io.getVersion
import org.isoron.platform.io.query
import org.isoron.platform.io.querySingle
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.io.GenericImporter
import org.isoron.uhabits.core.io.HabitBullCSVImporter
import org.isoron.uhabits.core.io.LoopDBImporter
import org.isoron.uhabits.core.io.RewireDBImporter
import org.isoron.uhabits.core.io.StandardLogging
import org.isoron.uhabits.core.io.TickmateDBImporter
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.utils.isSQLite3File

/** Parses into an isolated tracker; callers commit only the fully validated result. */
class TrackingImportReader(
    private val opener: DatabaseOpener,
    private val files: FileOpener,
    private val runner: CommandRunner
) {
    suspend fun read(file: UserFile, importId: String): TrackingBackup {
        require(file.readBytes(10000001).size <= 10000000) { "Import is too large (maximum 10 MB)." }
        if (!isSQLite3File(file) && file.readBytes(256).decodeToString().trimStart().startsWith("{")) {
            return TrackingBackup.decode(file.readBytes(10000000).decodeToString())
        }
        if (isSQLite3File(file)) {
            val db = opener.open(file.pathString)
            try {
                require(db.getVersion() <= DATABASE_VERSION) { "This database needs a newer Loop version. Update before importing." }
                require(db.querySingle("PRAGMA integrity_check") { it.getText(0) } == "ok") {
                    "The import database is damaged. Export a fresh backup from its original app."
                }
                val hasHistory = db.querySingle("SELECT name FROM sqlite_master WHERE name='LoopSyncState'") { it.getText(0) } != null
                if (hasHistory) {
                    val content = db.querySingle("SELECT history FROM LoopSyncState WHERE id=1") { it.getText(0) }
                        ?: throw IllegalArgumentException("Backup synchronization state is missing.")
                    return TrackingBackup.decode(TrackingBackup(ChangeHistory.decode(content)).encode())
                }
                validateSourceRows(db)
            } finally { db.close() }
        }
        val factory = MemoryModelFactory()
        val habits = factory.buildHabitList()
        val logging = StandardLogging()
        val importer = GenericImporter(
            LoopDBImporter(habits, factory, opener, runner, logging, files),
            RewireDBImporter(habits, factory, opener),
            TickmateDBImporter(habits, factory, opener),
            HabitBullCSVImporter(habits, factory, logging)
        )
        require(importer.canHandle(file)) { "Unrecognized import. Choose a Loop backup, Rewire/Tickmate database or HabitBull CSV." }
        importer.importHabitsFromFile(file)
        val edits = mutableMapOf<String, JsonElement>()
        for ((position, habit) in habits.withIndex()) {
            require(habit.uuid != null && habit.uuid!!.isNotBlank()) { "Imported habit identity is missing." }
            edits.putAll(SQLiteChangeStore.habitFields(SQLiteHabitList.copyFrom(habit).copy(position = position)))
            for (entry in habit.originalEntries.getKnown()) {
                edits["entry:${habit.uuid}:${entry.date.toCSVString()}"] = Json.encodeToJsonElement(RecordedEntry(entry.value, entry.notes))
            }
        }
        require(edits.isNotEmpty()) { "The import contains no habits." }
        return TrackingBackup(ChangeHistory("unbound").edit("source-$importId", "source:$importId", edits))
    }

    private fun validateSourceRows(db: Database) {
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'") { tables.add(it.getText(0).lowercase()) }
        if ("habits" in tables && "repetitions" in tables) {
            require(db.querySingle("SELECT COUNT(*) FROM Repetitions WHERE habit IS NULL OR timestamp IS NULL OR habit NOT IN (SELECT id FROM Habits)") { it.getInt(0) } == 0) {
                "Loop backup contains an entry with a missing habit or date. Repair the backup before importing."
            }
            require(db.querySingle("SELECT COUNT(*) FROM Habits WHERE name IS NULL OR freq_num IS NULL OR freq_den IS NULL OR freq_num <= 0 OR freq_den <= 0 OR archived NOT IN (0,1) OR (reminder_hour IS NULL) != (reminder_min IS NULL)") { it.getInt(0) } == 0) {
                "Loop backup contains an invalid habit definition. Repair the backup before importing."
            }
        }
    }
}
