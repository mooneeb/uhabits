package org.isoron.uhabits.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackingBackupTest {
    private val uuid = "0123456789abcdef0123456789abcdef"
    private val name = "habit:$uuid:name"
    private val entry = "entry:$uuid:2026-10-01"

    @Test
    fun olderRestorePreservesNewerIndependentWorkAndCompetingValues() {
        val old = ChangeHistory("account").edit("phone", "create", mapOf(name to JsonPrimitive("Walk")))
        val backup = TrackingBackup.decode(TrackingBackup(old).encode())
        val current = old.edit("phone", "newer", mapOf(name to JsonPrimitive("Run"), entry to Json.parseToJsonElement("""{"value":12345,"notes":"Newer note"}""")))
        val restored = backup.restoreInto(current, "restore")
        assertEquals(setOf(JsonPrimitive("Walk"), JsonPrimitive("Run")), restored.candidates(name).map { it.value }.toSet())
        assertEquals(RecordedEntry(12345, "Newer note"), RegisterValues.entry(restored.value(entry)!!))
        assertEquals(restored, restored.merge(restored))
    }

    @Test
    fun importedHistoryReachesANewReplicaThroughTheImportingDevicesPublishedPackage() {
        val source = ChangeHistory("account").edit("phone", "create", mapOf(name to JsonPrimitive("Walk"), entry to Json.parseToJsonElement("""{"value":12345,"notes":"Imported note"}""")))
        val restored = TrackingBackup(source).restoreInto(ChangeHistory("account"), "restored", "web")
        val pack = DrivePack.create(restored, "web", "default")!!
        val receiving = ChangeHistory("account").merge(DrivePack.decode(pack.encode(), "account", "default").history)
        assertEquals(JsonPrimitive("Walk"), receiving.value(name))
        assertEquals(RecordedEntry(12345, "Imported note"), RegisterValues.entry(receiving.value(entry)!!))
    }

    @Test
    fun restoresRespectDeletionAndPurgeBarriersAndRetainRecoveryHistory() {
        val old = ChangeHistory("account").edit("phone", "create", mapOf(name to JsonPrimitive("Walk")))
        val backup = TrackingBackup(old)
        val deleted = old.edit("phone", "delete", mapOf("habit:$uuid:deleted" to JsonPrimitive(true)))
        val recovered = backup.restoreInto(deleted, "restore")
        assertEquals(JsonPrimitive(true), recovered.value("habit:$uuid:deleted"))
        assertEquals(JsonPrimitive("Walk"), recovered.value(name))
        val purged = deleted.purge("phone", "purge", uuid)
        val afterRestore = backup.restoreInto(purged, "restore-purged")
        assertTrue(uuid in afterRestore.purgedHabits)
        assertEquals(null, afterRestore.value(name))
        val receivingPurge = TrackingBackup(purged).restoreInto(old, "receive-purge", "web")
        assertTrue((receivingPurge.clock["web"] ?: 0) > 0)
        assertEquals(null, receivingPurge.value(name))
    }

    @Test
    fun malformedBackupGivesRecoveryInstructions() {
        for (content in listOf("{}", """{"format":"loop-tracking-backup","schema":999}""")) {
            val error = assertFailsWith<IllegalArgumentException> { TrackingBackup.decode(content) }
            assertEquals("This backup is malformed. Export a fresh full backup from its original app before importing.", error.message)
        }
    }

    @Test
    fun incompleteOrUnsupportedBackupsAreRejectedBeforeRestore() {
        val base = ChangeHistory("account").edit("phone", "create", mapOf(name to JsonPrimitive("Walk")))
        val second = base.edit("phone", "rename", mapOf(name to JsonPrimitive("Run")))
        assertFailsWith<IllegalArgumentException> {
            TrackingBackup.decode(TrackingBackup(second.copy(changes = second.changes.drop(1))).encode())
        }
        assertFailsWith<IllegalArgumentException> { TrackingBackup.decode(TrackingBackup(base, version = 99).encode()) }
        assertEquals(JsonPrimitive("Walk"), base.value(name))
    }
}
