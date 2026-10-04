package org.isoron.uhabits.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** A portable causal backup, restored as an intentional change rather than a reset. */
@Serializable
data class TrackingBackup(val history: ChangeHistory, val format: String = "loop-tracking-backup", val version: Int = 1) {
    fun encode(): String = codec.encodeToString(this)

    fun restoreInto(current: ChangeHistory, restoreId: String, deviceId: String = "import-$restoreId"): ChangeHistory {
        require(format == "loop-tracking-backup" && version == 1) { "Unsupported backup version. Update Loop before importing this file." }
        val source = decode(encode()).history.let {
            if (it.accountId == "unbound") it.copy(accountId = current.accountId) else it
        }
        val merged = current.merge(source)
        val edits = source.keys().mapNotNull { key -> source.value(key)?.let { key to it } }.toMap()
            .filterKeys { key ->
                val uuid = if (key.startsWith("habit:") || key.startsWith("entry:")) key.split(':')[1] else null
                uuid !in merged.purgedHabits && (uuid == null || current.value("habit:$uuid:deleted") != JsonPrimitive(true))
            }
        if (edits.isEmpty()) return merged.edit(deviceId, "import:$restoreId", emptyMap())
        val operation = HabitChange("restore:$restoreId", "restore-$restoreId", 1, source.clock, edits)
        // The restore observes the backup, so newer competing work remains visible.
        // A local causal envelope makes this restore part of the durable upload queue.
        return merged.merge(ChangeHistory(current.accountId, listOf(operation)))
            .edit(deviceId, "import:$restoreId", emptyMap())
    }

    companion object {
        private val codec = Json { encodeDefaults = true }
        fun decode(content: String): TrackingBackup {
            require(content.length <= 10000000) { "Backup is too large" }
            val backup = try {
                codec.decodeFromString<TrackingBackup>(content)
            } catch (error: SerializationException) {
                throw IllegalArgumentException("This backup is malformed. Export a fresh full backup from its original app before importing.", error)
            }
            require(backup.format == "loop-tracking-backup" && backup.version == 1) { "Unsupported backup version. Update Loop before importing this file." }
            val history = ChangeHistory.decode(backup.history.encode())
            require(history.changes.all { it.sequence <= (history.clock[it.deviceId] ?: 0) }) {
                "Backup has missing causal changes. Export a complete backup from its original device."
            }
            return backup.copy(history = history)
        }
    }
}
