package org.isoron.uhabits.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Physical packaging retains logical change identities and causal context unchanged. */
@Serializable
data class DrivePack(
    val namespace: String,
    val workspaceId: String,
    val deviceId: String,
    val revision: Int,
    val history: ChangeHistory,
    val schema: Int = 2
) {
    fun encode(): String = codec.encodeToString(this)

    fun validate(accountId: String, workspace: String): DrivePack {
        require(schema in 1..3 && namespace == NAMESPACE && workspaceId == workspace) { "Unsupported workspace package. Update both Loop clients before synchronizing." }
        require(history.accountId == accountId) { "Package belongs to another Google account" }
        require(Regex("[a-zA-Z0-9_-]{1,128}").matches(deviceId) && Regex("[a-zA-Z0-9_-]{1,128}").matches(workspaceId)) { "Invalid workspace identity" }
        require(revision > 0 && (schema == 3 || history.changes.all { it.deviceId == deviceId })) { "Invalid device package" }
        val validated = ChangeHistory(accountId).merge(history)
        require(validated.changes.filter { it.deviceId == deviceId }.map { it.sequence } == (1..revision).toList()) { "Incomplete device history" }
        if (schema == 3) {
            require(validated.changes.all { it.sequence <= (validated.clock[it.deviceId] ?: 0) }) {
                "Imported package has missing causal changes. Re-export a complete backup."
            }
        }
        return copy(history = validated)
    }

    companion object {
        const val NAMESPACE = "loop-workspace-v1"
        private val codec = Json { encodeDefaults = true }

        fun create(history: ChangeHistory, deviceId: String, workspace: String): DrivePack? {
            val changes = history.changes.filter { it.deviceId == deviceId }
            if (changes.isEmpty()) return null
            // Imports introduce causal sources without an online originating device.
            // Carry the full dependency closure in the importing device's package.
            val imported = changes.any { it.id.startsWith("import:") }
            return DrivePack(
                NAMESPACE,
                workspace,
                deviceId,
                changes.maxOf { it.sequence },
                if (imported) history else history.copy(changes = changes),
                if (imported) 3 else 2
            ).validate(history.accountId, workspace)
        }

        fun decode(content: String, accountId: String, workspace: String): DrivePack {
            require(content.length <= 10000000) { "Workspace package is too large" }
            return codec.decodeFromString<DrivePack>(content).validate(accountId, workspace)
        }

        fun payloadHabits(content: String, accountId: String, workspace: String): Set<String> {
            decode(content, accountId, workspace)
            val raw = codec.decodeFromString<DrivePack>(content)
            return raw.history.changes.flatMap { it.edits.keys }.filter {
                it.startsWith("habit:") || it.startsWith("entry:")
            }.map { it.split(':')[1] }.toSet()
        }
    }
}
