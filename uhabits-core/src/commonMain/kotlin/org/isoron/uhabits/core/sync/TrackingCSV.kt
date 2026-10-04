package org.isoron.uhabits.core.sync

import org.isoron.platform.io.ZipWriter
import org.isoron.platform.io.csvLine

/** Original registers, including recovery records; computed outcomes never replace entries. */
object TrackingCSV {
    suspend fun export(history: ChangeHistory): ByteArray {
        val zip = ZipWriter()
        val definitions = StringBuilder(csvLine(arrayOf("Habit UUID", "Field", "Value (JSON)")))
        val entries = StringBuilder(csvLine(arrayOf("Habit UUID", "Date", "Raw value", "Entry notes")))
        val conflicts = StringBuilder(csvLine(arrayOf("Register", "Revision", "Value (JSON)")))
        val competing = history.conflicts()
        for (key in history.keys().sorted()) {
            val value = history.value(key)
            if (key.startsWith("habit:") && value != null) {
                val parts = key.split(':')
                definitions.append(csvLine(arrayOf(parts[1], parts[2], value.toString())))
            } else if (key.startsWith("entry:") && value != null) {
                val parts = key.split(':')
                val entry = RegisterValues.entry(value)
                entries.append(csvLine(arrayOf(parts[1], parts[2], entry.value.toString(), entry.notes)))
            }
            if (key in competing) {
                for (revision in history.candidates(key)) {
                    conflicts.append(csvLine(arrayOf(key, revision.changeId, revision.value.toString())))
                }
            }
        }
        zip.addEntry("Habits.csv", definitions.toString())
        zip.addEntry("Original entries.csv", entries.toString())
        zip.addEntry("Competing revisions.csv", conflicts.toString())
        zip.addEntry("Full backup.loop.json", TrackingBackup(history).encode())
        return zip.toBytes()
    }
}
