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
    val schema: Int = 1
) {
    fun encode(): String = codec.encodeToString(this)

    fun validate(accountId: String, workspace: String): DrivePack {
        require(schema == 1 && namespace == NAMESPACE && workspaceId == workspace) { "Unsupported workspace package" }
        require(history.accountId == accountId) { "Package belongs to another Google account" }
        require(Regex("[a-zA-Z0-9_-]{1,128}").matches(deviceId) && Regex("[a-zA-Z0-9_-]{1,128}").matches(workspaceId)) { "Invalid workspace identity" }
        require(revision > 0 && history.changes.all { it.deviceId == deviceId }) { "Invalid device package" }
        val validated = ChangeHistory(accountId).merge(history)
        require(validated.changes.map { it.sequence } == (1..revision).toList()) { "Incomplete device history" }
        return copy(history = validated)
    }

    companion object {
        const val NAMESPACE = "loop-workspace-v1"
        private val codec = Json { encodeDefaults = true }

        fun create(history: ChangeHistory, deviceId: String, workspace: String): DrivePack? {
            val changes = history.changes.filter { it.deviceId == deviceId }
            if (changes.isEmpty()) return null
            return DrivePack(NAMESPACE, workspace, deviceId, changes.maxOf { it.sequence }, ChangeHistory(history.accountId, changes)).validate(history.accountId, workspace)
        }

        fun decode(content: String, accountId: String, workspace: String): DrivePack {
            require(content.length <= 10000000) { "Workspace package is too large" }
            return codec.decodeFromString<DrivePack>(content).validate(accountId, workspace)
        }
    }
}
