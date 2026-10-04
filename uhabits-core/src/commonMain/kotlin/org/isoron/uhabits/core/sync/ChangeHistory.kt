package org.isoron.uhabits.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class HabitChange(
    val id: String,
    val deviceId: String,
    val sequence: Int,
    val observed: Map<String, Int>,
    val edits: Map<String, JsonElement>
)

class PendingConflictException(message: String) : IllegalArgumentException(message)

data class Revision(val changeId: String, val value: JsonElement)

/** Immutable causal history. Persist the returned history together with the local mutation. */
@Serializable
data class ChangeHistory(
    val accountId: String,
    val changes: List<HabitChange> = emptyList(),
    val schema: Int = 2,
    val purgedHabits: Set<String> = emptySet()
) {
    fun encode(): String = Json.encodeToString(this)

    fun edit(deviceId: String, id: String, edits: Map<String, JsonElement>): ChangeHistory {
        val affected = edits.keys.mapNotNull { entity(it) }.toSet()
        require(affected.none { it in purgedHabits }) { "This habit was permanently purged" }
        val protectedKeys = edits.keys + affected.map { "habit:$it:deleted" } +
            edits.filter { (key, value) -> key.endsWith(":deleted") && value == JsonPrimitive(true) }
                .keys.flatMap { key -> changes.flatMap { it.edits.keys }.filter { entity(it) == entity(key) } }
        if (protectedKeys.any { candidates(it).map { revision -> revision.value }.distinct().size > 1 }) {
            throw PendingConflictException("Competing revisions are preserved. Resolve the conflict before editing this value.")
        }
        require(affected.none { value("habit:$it:deleted") == JsonPrimitive(true) && edits["habit:$it:deleted"] != JsonPrimitive(true) }) {
            "This habit is deleted. Explicit restoration is required before editing it."
        }
        return append(deviceId, id, edits)
    }

    fun resolve(deviceId: String, id: String, key: String, value: JsonElement, revisions: Set<String>): ChangeHistory {
        require(entity(key) !in purgedHabits) { "This habit was permanently purged" }
        require(candidates(key).map { it.changeId }.toSet() == revisions && revisions.isNotEmpty()) {
            "The competing revisions changed. Review them again before resolving."
        }
        val uuid = entity(key)
        val lifecycle = uuid?.let { "habit:$it:deleted" }
        require(key == lifecycle || lifecycle == null || candidates(lifecycle).map { it.value }.distinct().size <= 1) {
            "Resolve the deletion conflict first."
        }
        val edits = mutableMapOf(key to value)
        if (lifecycle != null && key != lifecycle && this.value(lifecycle) == JsonPrimitive(true)) {
            edits[lifecycle] = JsonPrimitive(true)
        }
        return append(deviceId, id, edits)
    }

    fun restore(deviceId: String, id: String, uuid: String): ChangeHistory {
        require(uuid !in purgedHabits) { "This habit was permanently purged" }
        require(value("habit:$uuid:deleted") == JsonPrimitive(true)) { "Only a deleted habit can be restored" }
        return append(deviceId, id, mapOf("habit:$uuid:deleted" to JsonPrimitive(false)))
    }

    fun purge(deviceId: String, id: String, uuid: String): ChangeHistory {
        require(value("habit:$uuid:deleted") == JsonPrimitive(true)) { "Only a deleted habit can be purged" }
        require(conflicts().keys.none { entity(it) == uuid }) { "Resolve this habit's conflicts before purging" }
        val next = append(deviceId, id, mapOf("habit:$uuid:deleted" to JsonPrimitive(true)))
        return ChangeHistory(accountId).merge(next.copy(purgedHabits = purgedHabits + uuid))
    }

    fun conflicts(): Map<String, List<Revision>> = keys().associateWith { candidates(it) }
        .filterValues { revisions -> revisions.map { it.value }.distinct().size > 1 }

    fun keys(): Set<String> = changes.flatMap { it.edits.keys }.toSet() +
        changes.flatMap { it.edits.keys }.mapNotNull { entity(it) }.map { "habit:$it:deleted" }

    private fun append(deviceId: String, id: String, edits: Map<String, JsonElement>): ChangeHistory {
        val observed = clock
        require(changes.filter { it.deviceId == deviceId }.all { it.sequence <= (observed[deviceId] ?: 0) }) {
            "Earlier changes from this device must be discovered before editing"
        }
        val next = HabitChange(id, deviceId, (observed[deviceId] ?: 0) + 1, observed, edits)
        return merge(ChangeHistory(accountId, listOf(next)))
    }

    fun merge(other: ChangeHistory): ChangeHistory {
        require(schema in 1..2 && other.schema in 1..2) { "Unsupported synchronization format" }
        require(accountId == other.accountId) { "Changes belong to another Google account" }
        require((schema != 1 || purgedHabits.isEmpty()) && (other.schema != 1 || other.purgedHabits.isEmpty())) { "Invalid purge format" }
        val purged = purgedHabits + other.purgedHabits
        for (uuid in purged) RegisterValues.validate("habit:$uuid:deleted", JsonPrimitive(true))
        for (change in changes + other.changes) {
            require(change.id.isNotBlank() && change.id.length <= 256 && Regex("[a-zA-Z0-9_-]{1,128}").matches(change.deviceId)) { "Invalid change identity" }
            require(change.sequence > 0 && (change.observed[change.deviceId] ?: 0) == change.sequence - 1 && change.observed.values.all { it > 0 }) { "Invalid causal context" }
            // Version 2 retains empty causal envelopes after removing purged payloads.
            require(change.edits.isNotEmpty() || purged.isNotEmpty()) { "Empty change" }
            for ((key, value) in change.edits) {
                RegisterValues.validate(key, value)
            }
        }
        val combined = (changes + other.changes).map { change ->
            change.copy(
                edits = change.edits.filterKeys { entity(it) !in purged }.mapValues { (key, value) ->
                    // JSON browser round-trips turn integral Double values such as 1.0
                    // into 1. Compare each tracking group in this runtime's domain format.
                    if (key.startsWith("habit:") && key.endsWith(":tracking")) {
                        Json.encodeToJsonElement(TrackingSettings.fromJson(value))
                    } else {
                        value
                    }
                }
            )
        }.groupBy { it.id }.map { (_, versions) ->
            require(versions.distinct().size == 1) { "An operation identity was reused with different content" }
            versions.first()
        }.sortedWith(compareBy({ it.deviceId }, { it.sequence }, { it.id }))
        require(combined.groupBy { it.deviceId to it.sequence }.values.all { it.size == 1 }) { "A device sequence was reused" }
        return copy(changes = combined, schema = 2, purgedHabits = purged)
    }

    fun candidates(key: String): List<Revision> {
        // Any habit/entry mutation also asserts that the habit remains alive. This
        // makes deletion compete with concurrent edits even when fields differ.
        val lifecycle = key.startsWith("habit:") && key.endsWith(":deleted")
        val uuid = if (lifecycle) entity(key) else null
        val writers = availableChanges().filter { change ->
            key in change.edits || lifecycle && change.edits.keys.any { entity(it) == uuid }
        }
        return writers.filter { candidate ->
            writers.none { other -> (other.observed[candidate.deviceId] ?: 0) >= candidate.sequence }
        }.map { Revision(it.id, it.edits[key] ?: JsonPrimitive(false)) }
    }

    fun value(key: String): JsonElement? = candidates(key).map { it.value }.distinct().singleOrNull()

    val clock: Map<String, Int>
        get() = availableChanges().groupBy { it.deviceId }.mapValues { (_, values) -> values.maxOf { it.sequence } }

    private fun availableChanges(): List<HabitChange> {
        val clock = mutableMapOf<String, Int>()
        val ready = mutableListOf<HabitChange>()
        val waiting = changes.toMutableList()
        do {
            var advanced = false
            val iterator = waiting.iterator()
            while (iterator.hasNext()) {
                val change = iterator.next()
                if (change.sequence != (clock[change.deviceId] ?: 0) + 1 ||
                    change.observed.any { (device, sequence) -> sequence > (clock[device] ?: 0) }
                ) {
                    continue
                }
                ready.add(change)
                clock[change.deviceId] = change.sequence
                iterator.remove()
                advanced = true
            }
        } while (advanced)
        return ready
    }

    private fun entity(key: String): String? =
        if (key.startsWith("habit:") || key.startsWith("entry:")) key.split(':')[1] else null

    companion object {
        fun decode(content: String): ChangeHistory {
            val history = Json.decodeFromString<ChangeHistory>(content)
            return ChangeHistory(history.accountId).merge(history)
        }
    }
}
