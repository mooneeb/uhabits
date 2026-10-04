package org.isoron.uhabits.core.sync

import kotlinx.coroutines.test.runTest
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackingImportReaderTest : BaseUnitTest() {
    @Test
    fun numericCsvKeepsEarlierOneUnitEntriesWhenLaterRowsIdentifyTheHabitAsNumeric() = runTest {
        val file = copyResourceToTempFile("habitbull.csv")
        try {
            file.writeString("HabitName,HabitDescription,HabitCategory,Date,Value,Notes\nAmount,Description,Category,2026-10-01,1,One unit\nAmount,Description,Category,2026-10-02,12,Twelve units")
            val source = TrackingImportReader(databaseOpener(), fileOpener, commandRunner).read(file, "numeric").history
            val uuid = source.keys().first { it.endsWith(":name") }.split(':')[1]
            assertEquals(RecordedEntry(1000, "One unit"), RegisterValues.entry(source.value("entry:$uuid:2026-10-01")!!))
            assertEquals(RecordedEntry(12000, "Twelve units"), RegisterValues.entry(source.value("entry:$uuid:2026-10-02")!!))
        } finally { file.delete() }
    }

    @Test
    fun supportedExternalImportsPreserveDefinitionsAndOriginalEntriesWithoutTouchingTheActiveTracker() = runTest {
        val reader = TrackingImportReader(databaseOpener(), fileOpener, commandRunner)
        for ((filename, count) in listOf("loop.db" to 9, "rewire.db" to 3, "tickmate.db" to 3, "habitbull.csv" to 4, "habitbull3.csv" to 2)) {
            val file = copyResourceToTempFile(filename)
            try {
                val source = reader.read(file, "fixture")
                val restored = source.restoreInto(ChangeHistory("account"), "restore-$count", "web")
                assertEquals(count, restored.keys().filter { it.endsWith(":name") }.size)
                assertTrue(restored.keys().any { it.startsWith("entry:") })
                assertEquals(0, habitList.size())
                if (filename == "habitbull3.csv") {
                    val numeric = restored.keys().first { it.endsWith(":name") && RegisterValues.text(restored.value(it)!!) == "Pushups" }.split(':')[1]
                    assertEquals(RecordedEntry(30000, ""), RegisterValues.entry(restored.value("entry:$numeric:2021-09-01")!!))
                    assertEquals(1, TrackingSettings.fromJson(restored.value("habit:$numeric:tracking")!!).type)
                }
            } finally { file.delete() }
        }
    }

    @Test
    fun quotedCsvKeepsMultilineDatedNotesAndRejectsUnterminatedQuotes() = runTest {
        val file = copyResourceToTempFile("habitbull.csv")
        try {
            val header = "HabitName,HabitDescription,HabitCategory,Date,Value,Notes\n"
            file.writeString(header + "Walk,Description,Category,2026-10-01,1,\"First line\nSecond line, with comma\"")
            val source = TrackingImportReader(databaseOpener(), fileOpener, commandRunner).read(file, "multiline").history
            val entry = source.keys().first { it.startsWith("entry:") }
            assertEquals("First line\nSecond line, with comma", RegisterValues.entry(source.value(entry)!!).notes)
            file.writeString(header + "Walk,Description,Category,2026-10-01,1,\"unterminated")
            assertFailsWith<IllegalArgumentException> {
                TrackingImportReader(databaseOpener(), fileOpener, commandRunner).read(file, "malformed")
            }
        } finally { file.delete() }
    }

    @Test
    fun malformedCsvRejectsTheWholeImportIncludingInvalidAmountsAndDates() = runTest {
        val file = copyResourceToTempFile("habitbull.csv")
        try {
            for (row in listOf("Bad,Description,Category,2026-02-30,1,note", "Bad,Description,Category,2026-10-01,not-a-number,note", "Bad,short,row")) {
                file.writeString("HabitName,HabitDescription,HabitCategory,Date,Value,Notes\nValid,Description,Category,2026-10-01,1,note\n$row")
                assertFailsWith<IllegalArgumentException> {
                    TrackingImportReader(databaseOpener(), fileOpener, commandRunner).read(file, "invalid")
                }
                assertEquals(0, habitList.size())
            }
        } finally { file.delete() }
    }
}
