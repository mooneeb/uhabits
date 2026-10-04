package org.isoron.uhabits.web

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.isoron.platform.io.ZipReader
import org.isoron.uhabits.core.sync.ChangeHistory
import org.isoron.uhabits.core.sync.TrackingBackup
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortabilityTest {
    @Test
    fun failedExportDoesNotPreventAnotherExport() = runTest {
        main()
        assertFailsWith<Exception> {
            window.asDynamic().loopExportCSV("invalid history").unsafeCast<Promise<dynamic>>().await()
        }
        val uuid = "0123456789abcdef0123456789abcdef"
        val history = ChangeHistory("account").edit(
            "web",
            "create",
            mapOf(
                "habit:$uuid:name" to JsonPrimitive("Walk"),
                "entry:$uuid:2026-10-01" to Json.parseToJsonElement("""{"value":12345,"notes":"Export note"}""")
            )
        )
        val result = window.asDynamic().loopExportCSV(history.encode()).unsafeCast<Promise<dynamic>>().await()
        val bytes = ByteArray(result.length as Int) { index -> (result[index] as Number).toByte() }
        val entries = ZipReader(bytes).entries().associate { it.name to it.content }
        assertTrue(entries.getValue("Original entries.csv").contains("2026-10-01,12345,Export note"))
        assertEquals(history, TrackingBackup.decode(entries.getValue("Full backup.loop.json")).history)
    }
}
