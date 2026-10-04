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
        val unsupported = ChangeHistory("account", listOf(HabitChange("future", "browser", 1, emptyMap(), mapOf(question to JsonPrimitive("Future format")))), schema = 2)
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
