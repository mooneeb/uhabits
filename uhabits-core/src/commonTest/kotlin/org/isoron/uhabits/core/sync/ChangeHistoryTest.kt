package org.isoron.uhabits.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChangeHistoryTest {
    private val name = "habit:0123456789abcdef0123456789abcdef:name"
    private val question = "habit:0123456789abcdef0123456789abcdef:question"

    @Test
    fun browserRoundTripOfRedactedNativePackPreservesSurvivingTracking() {
        val uuid = "0123456789abcdef0123456789abcdef"
        val tracking = "habit:abcdef0123456789abcdef0123456789:tracking"
        val value = Json.parseToJsonElement("""{"type":1,"freqNum":1,"freqDen":1,"targetValue":1.0,"targetType":0,"unit":"km"}""")
        val native = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Private"), tracking to value))
        val purged = native.edit("browser", "delete", mapOf("habit:$uuid:deleted" to JsonPrimitive(true)))
            .purge("browser", "purge", uuid)
        val pack = DrivePack.create(purged, "android", "test")!!.encode()
        val browserRoundTrip = pack.replace("\"targetValue\":1.0", "\"targetValue\":1")
        val reconciled = native.merge(DrivePack.decode(browserRoundTrip, "account", "test").history)
        assertEquals(null, reconciled.value(name))
        assertEquals(TrackingSettings.fromJson(value), TrackingSettings.fromJson(reconciled.value(tracking)!!))
        assertEquals(reconciled, reconciled.merge(native))
        val changedTarget = browserRoundTrip.replace("\"targetValue\":1", "\"targetValue\":2")
        assertFailsWith<IllegalArgumentException> { native.merge(DrivePack.decode(changedTarget, "account", "test").history) }
    }

    @Test
    fun equivalentConcurrentTrackingNumbersNeedNoConflictResolution() {
        val tracking = "habit:0123456789abcdef0123456789abcdef:tracking"
        val value = Json.parseToJsonElement("""{"type":1,"freqNum":1,"freqDen":1,"targetValue":1.0,"targetType":0,"unit":"km"}""")
        val integer = Json.parseToJsonElement(value.toString().replace("\"targetValue\":1.0", "\"targetValue\":1"))
        val native = ChangeHistory("account").edit("android", "native", mapOf(tracking to value))
        val browser = ChangeHistory("account").edit("browser", "web", mapOf(tracking to integer))
        assertEquals(emptyMap(), native.merge(browser).conflicts())
    }

    @Test
    fun explicitResolutionSupersedesObservedVersionsButPreservesLateCompetingEdits() {
        val base = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val a = base.edit("android", "a", mapOf(name to JsonPrimitive("Morning")))
        val b = base.edit("browser", "b", mapOf(name to JsonPrimitive("Evening")))
        val merged = a.merge(b)
        val resolved = merged.resolve("android", "choice", name, JsonPrimitive("Morning"), setOf("a", "b"))
        assertEquals(JsonPrimitive("Morning"), ChangeHistory.decode(resolved.encode()).value(name))
        val late = base.edit("tablet", "late", mapOf(name to JsonPrimitive("Afternoon")))
        assertEquals(setOf(JsonPrimitive("Morning"), JsonPrimitive("Afternoon")), resolved.merge(late).candidates(name).map { it.value }.toSet())
        assertFailsWith<IllegalArgumentException> {
            merged.merge(late).resolve("android", "stale-choice", name, JsonPrimitive("Morning"), setOf("a", "b"))
        }
    }

    @Test
    fun purgeRemovesRecoveryPayloadAndRejectsStaleIdentityAfterRestart() {
        val uuid = "0123456789abcdef0123456789abcdef"
        val entry = "entry:$uuid:2026-10-01"
        val base = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Private habit"), entry to Json.parseToJsonElement("""{"value":2,"notes":"Private note"}""")))
        val deleted = base.edit("android", "delete", mapOf("habit:$uuid:deleted" to JsonPrimitive(true)))
        val restored = deleted.restore("android", "restore", uuid)
        assertEquals(JsonPrimitive("Private habit"), restored.value(name))
        assertEquals(JsonPrimitive(false), restored.value("habit:$uuid:deleted"))
        val purged = ChangeHistory.decode(deleted.purge("android", "purge", uuid).encode())
        val stale = base.edit("browser", "stale", mapOf(name to JsonPrimitive("Resurrected")))
        assertEquals(null, purged.merge(stale).value(name))
        assertEquals(null, stale.merge(purged).value(entry))
        assertEquals(false, purged.merge(stale).encode().contains("Private"))
        assertEquals(false, purged.merge(stale).encode().contains("Resurrected"))
        assertFailsWith<IllegalArgumentException> { purged.edit("browser", "reuse", mapOf(name to JsonPrimitive("Reuse"))) }
        assertFailsWith<IllegalArgumentException> { purged.restore("android", "restore-purged", uuid) }
        assertEquals(JsonPrimitive("New habit"), purged.edit("android", "new", mapOf("habit:abcdef0123456789abcdef0123456789:name" to JsonPrimitive("New habit"))).value("habit:abcdef0123456789abcdef0123456789:name"))
    }

    @Test
    fun devicePackagesRetainPurgeBarriersAndCausalProgressAfterDuplicateDelivery() {
        val uuid = "0123456789abcdef0123456789abcdef"
        val base = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val b = base.edit("browser", "question", mapOf(question to JsonPrimitive("Private question")))
        val deleted = b.edit("android", "delete", mapOf("habit:$uuid:deleted" to JsonPrimitive(true)))
        val purged = deleted.purge("android", "purge", uuid)
        val androidPack = DrivePack.create(purged, "android", "test")!!
        val browserPack = DrivePack.create(purged, "browser", "test")!!
        val replica = ChangeHistory("account").merge(DrivePack.decode(browserPack.encode(), "account", "test").history)
            .merge(DrivePack.decode(androidPack.encode(), "account", "test").history).merge(base).merge(b)
        assertEquals(purged.clock, replica.clock)
        assertEquals(null, replica.value(name))
        assertEquals(false, replica.encode().contains("Private question"))
    }

    @Test
    fun identicalConcurrentValuesAndSequentialCorrectionsNeedNoResolution() {
        val base = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val merged = base.edit("android", "a", mapOf(name to JsonPrimitive("Morning")))
            .merge(base.edit("browser", "b", mapOf(name to JsonPrimitive("Morning"))))
        assertEquals(emptyMap(), merged.conflicts())
        val corrected = merged.edit("browser", "correction", mapOf(name to JsonPrimitive("Evening")))
        assertEquals(JsonPrimitive("Evening"), corrected.merge(base).value(name))
        assertEquals(emptyMap(), corrected.conflicts())
    }

    @Test
    fun deletionResolutionRetainsAllHistoryAndPurgeWaitsForEveryConflict() {
        val uuid = "0123456789abcdef0123456789abcdef"
        val deleted = "habit:$uuid:deleted"
        val base = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val a = base.edit("android", "delete", mapOf(deleted to JsonPrimitive(true)))
        val b = base.edit("browser", "rename", mapOf(name to JsonPrimitive("Keep me")))
        val merged = a.merge(b)
        assertFailsWith<IllegalArgumentException> { merged.purge("android", "premature", uuid) }
        val choice = merged.resolve("android", "resolve-deletion", deleted, JsonPrimitive(true), merged.candidates(deleted).map { it.changeId }.toSet())
        assertEquals(JsonPrimitive("Keep me"), choice.value(name))
        assertEquals(JsonPrimitive("Keep me"), choice.restore("browser", "restore", uuid).value(name))
        val names = choice.merge(base.edit("tablet", "late-name", mapOf(name to JsonPrimitive("Other"))))
        assertFailsWith<IllegalArgumentException> { names.purge("android", "with-name-conflict", uuid) }
    }

    @Test
    fun preservesCompetingHabitNamesAndMergesIndependentQuestions() {
        val initial = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val android = initial.edit("android", "rename-android", mapOf(name to JsonPrimitive("Morning walk")))
        val browser = initial.edit("browser", "rename-browser", mapOf(name to JsonPrimitive("Evening walk"), question to JsonPrimitive("Did you walk?")))
        val merged = android.merge(browser)
        assertEquals(setOf(JsonPrimitive("Morning walk"), JsonPrimitive("Evening walk")), merged.candidates(name).map { it.value }.toSet())
        assertEquals(JsonPrimitive("Did you walk?"), merged.value(question))
        assertEquals(merged, browser.merge(android).merge(android))
    }

    @Test
    fun preservesDeletionVersusConcurrentEntryEditing() {
        val initial = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val deleted = "habit:0123456789abcdef0123456789abcdef:deleted"
        val deletion = initial.edit("android", "delete", mapOf(deleted to JsonPrimitive(true)))
        val entry = "entry:0123456789abcdef0123456789abcdef:2026-10-01"
        val editing = initial.edit("browser", "entry", mapOf(entry to Json.parseToJsonElement("""{"value":2,"notes":"Keep this"}""")))
        val merged = deletion.merge(editing)
        assertEquals(setOf(JsonPrimitive(true), JsonPrimitive(false)), merged.candidates(deleted).map { it.value }.toSet())
        assertEquals(null, merged.value(deleted))
        assertEquals(merged, editing.merge(deletion))
        assertFailsWith<IllegalArgumentException> { merged.edit("browser", "ordinary-edit", mapOf(name to JsonPrimitive("Silent restoration"))) }
    }

    @Test
    fun ordinaryEditsCannotResolveCompetingValues() {
        val initial = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val merged = initial.edit("android", "a", mapOf(name to JsonPrimitive("Morning")))
            .merge(initial.edit("browser", "b", mapOf(name to JsonPrimitive("Evening"))))
        assertFailsWith<IllegalArgumentException> { merged.edit("android", "overwrite", mapOf(name to JsonPrimitive("Morning"))) }
        assertEquals(2, merged.candidates(name).size)
        assertEquals(JsonPrimitive("Independent"), merged.edit("android", "question", mapOf(question to JsonPrimitive("Independent"))).value(question))
    }

    @Test
    fun waitsForEarlierChangesWhenDiscoveryIsReordered() {
        val first = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val second = first.edit("android", "rename", mapOf(name to JsonPrimitive("Morning walk")))
        val deliveredLastFirst = ChangeHistory("account", listOf(second.changes.last()))
        assertEquals(null, deliveredLastFirst.value(name))
        assertEquals(JsonPrimitive("Morning walk"), deliveredLastFirst.merge(first).value(name))
    }

    @Test
    fun rejectsUnsupportedHistoryBeforeApplyingAnyChange() {
        val initial = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val unsupported = ChangeHistory("account", listOf(HabitChange("future", "browser", 1, emptyMap(), mapOf(question to JsonPrimitive("Future format")))), schema = 3)
        assertFailsWith<IllegalArgumentException> { initial.merge(unsupported) }
        assertEquals(JsonPrimitive("Walk"), initial.value(name))
        assertEquals(null, initial.value(question))
    }

    @Test
    fun rejectsIncoherentFrequencyAndTargetTogether() {
        val initial = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        val invalidTracking = Json.parseToJsonElement("""{"type":1,"freqNum":2,"freqDen":1,"targetValue":10.0,"targetType":0,"unit":"km"}""")
        assertFailsWith<IllegalArgumentException> {
            initial.edit("browser", "invalid-tracking", mapOf(name to JsonPrimitive("Changed"), "habit:0123456789abcdef0123456789abcdef:tracking" to invalidTracking))
        }
        assertEquals(JsonPrimitive("Walk"), initial.value(name))
    }

    @Test
    fun serializedHistoryKeepsDatedNumericNotesAndCompetingValues() {
        val entry = "entry:0123456789abcdef0123456789abcdef:2026-10-01"
        val android = ChangeHistory("account").edit("android", "record-android", mapOf(entry to Json.parseToJsonElement("""{"value":12345,"notes":"Android — آزمائش"}""")))
        val browser = ChangeHistory("account").edit("browser", "record-browser", mapOf(entry to Json.parseToJsonElement("""{"value":6789,"notes":"Browser note"}""")))
        val merged = android.merge(browser)
        val reopened = ChangeHistory.decode(merged.encode())
        assertEquals(merged.candidates(entry), reopened.candidates(entry))
        assertEquals(2, reopened.candidates(entry).size)
        assertEquals(null, reopened.value(entry))
    }

    @Test
    fun rejectsAnInvalidHabitDateAndDeviceSequenceReuse() {
        val history = ChangeHistory("account").edit("android", "create", mapOf(name to JsonPrimitive("Walk")))
        assertFailsWith<IllegalArgumentException> {
            history.edit("browser", "invalid-date", mapOf("entry:0123456789abcdef0123456789abcdef:2026-02-30" to Json.parseToJsonElement("""{"value":2,"notes":"Wrong date"}""")))
        }
        val fork = history.copy(changes = history.changes.map { it.copy(id = "reused-sequence", edits = mapOf(name to JsonPrimitive("Different"))) })
        assertFailsWith<IllegalArgumentException> { history.merge(fork) }
        assertEquals(JsonPrimitive("Walk"), history.value(name))
    }
}
