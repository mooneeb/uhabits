package org.isoron.uhabits.core.io

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.run
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.sync.TrackingImportReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MalformedDatabaseImportTest : BaseUnitTest() {
    @Test
    fun invalidSourceRowsRejectDatabaseImportsBeforeAnyActiveChanges() = runTest {
        for ((filename, corruption) in listOf(
            "loop.db" to "UPDATE Repetitions SET timestamp=NULL WHERE id=(SELECT MIN(id) FROM Repetitions)",
            "tickmate.db" to "UPDATE ticks SET year=2026, month=1, day=30",
            "rewire.db" to "UPDATE checkins SET date='20260230' WHERE type=2"
        )) {
            val file = copyResourceToTempFile(filename)
            try {
                val db = databaseOpener().open(file.pathString)
                try { db.run(corruption) } finally { db.close() }
                assertFailsWith<IllegalArgumentException>(filename) {
                    TrackingImportReader(databaseOpener(), fileOpener, commandRunner).read(file, "invalid-source")
                }
                assertEquals(0, habitList.size())
            } finally { file.delete() }
        }
    }
}
