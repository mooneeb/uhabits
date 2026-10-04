package org.isoron.uhabits.sync

import android.net.Uri
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.HabitProgress
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs

class DriveReconnectRequired : Exception("Reconnect required: Google authorization expired.")

/** An isolated development probe, not the proposed production synchronization protocol. */
class DriveIntegrationGate(private val token: String) {
    private var accountId = ""
    private var requests = 0

    fun connect(): String {
        val about = JSONObject(request("https://www.googleapis.com/drive/v3/about?fields=user(permissionId,emailAddress)"))
        val user = about.getJSONObject("user")
        accountId = user.getString("permissionId")
        require(accountId.isNotBlank()) { "Drive did not return an account identity" }
        return user.optString("emailAddress", accountId)
    }

    fun publish(runId: String, amountMillis: Int, notes: String) {
        validateRun(runId)
        val operationId = UUID.randomUUID().toString()
        val record = JSONObject()
            .put("schema", 1).put("namespace", NAMESPACE).put("runId", runId)
            .put("operationId", operationId).put("producer", "android").put("accountId", accountId)
            .put("amountMillis", amountMillis).put("notes", notes).put("progress", progress(amountMillis, notes))
        validateRecord(record, runId)
        val metadata = JSONObject().put("name", "$NAMESPACE-$runId-$operationId.json")
            .put("mimeType", "application/json").put("parents", JSONArray().put("appDataFolder"))
            .put("appProperties", JSONObject().put("namespace", NAMESPACE).put("runId", runId))
        val boundary = "loop_${UUID.randomUUID()}"
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$record\r\n--$boundary--\r\n"
        request(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            "POST",
            body,
            "multipart/related; boundary=$boundary"
        )
    }

    fun discover(runId: String): JSONObject {
        validateRun(runId)
        val started = System.nanoTime()
        requests = 0
        var pages = 0
        var downloadedBytes = 0
        var pageToken = ""
        val records = JSONArray()
        val producers = mutableSetOf<String>()
        do {
            val uri = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
                .appendQueryParameter("spaces", "appDataFolder")
                .appendQueryParameter("pageSize", "2")
                .appendQueryParameter("fields", "nextPageToken,incompleteSearch,files(id,name,mimeType,appProperties)")
                .appendQueryParameter(
                    "q",
                    "trashed = false and appProperties has { key='namespace' and value='$NAMESPACE' } " +
                        "and appProperties has { key='runId' and value='$runId' }"
                )
            if (pageToken.isNotEmpty()) uri.appendQueryParameter("pageToken", pageToken)
            val page = JSONObject(request(uri.build().toString()))
            pages++
            require(!page.optBoolean("incompleteSearch")) { "Incomplete Drive discovery; retry" }
            val files = page.getJSONArray("files")
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val properties = file.getJSONObject("appProperties")
                require(properties.getString("namespace") == NAMESPACE && properties.getString("runId") == runId)
                require(file.getString("mimeType") == "application/json")
                val content = request("https://www.googleapis.com/drive/v3/files/${Uri.encode(file.getString("id"))}?alt=media")
                downloadedBytes += content.toByteArray(Charsets.UTF_8).size
                val record = JSONObject(content)
                validateRecord(record, runId)
                require(file.getString("name") == "$NAMESPACE-$runId-${record.getString("operationId")}.json")
                producers.add(record.getString("producer"))
                records.put(record)
            }
            pageToken = page.optString("nextPageToken")
        } while (pageToken.isNotEmpty())
        return JSONObject().put("records", records).put(
            "metrics",
            JSONObject().put("requests", requests).put("pages", pages).put("files", records.length())
                .put("downloadedBytes", downloadedBytes).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
        ).put("result", if (producers.containsAll(listOf("android", "browser"))) "Both clients' samples verified" else "Gate pending: publish on both clients")
    }

    private fun progress(amountMillis: Int, notes: String): JSONObject {
        require(amountMillis >= 0)
        val date = LocalDate(2026, 10, 1)
        val progress = HabitProgress.evaluate(
            Frequency.DAILY,
            listOf(Entry(date, amountMillis, notes)),
            date,
            date,
            isNumerical = true,
            targetValue = 10.0
        )
        return JSONObject().put("date", progress.originalEntries.single().date.toCSVString())
            .put("amountMillis", progress.originalEntries.single().value)
            .put("notes", progress.originalEntries.single().notes)
            .put("score", progress.scores.single().value)
            .put("streakLength", progress.streaks.firstOrNull()?.length ?: 0)
    }

    private fun validateRun(runId: String) {
        require(UUID_PATTERN.matches(runId)) { "Copy the test run UUID from the browser" }
        require(accountId.isNotBlank()) { "Connect a Google account first" }
    }

    private fun validateRecord(record: JSONObject, runId: String) {
        require(record.getInt("schema") == 1 && record.getString("namespace") == NAMESPACE)
        require(record.getString("runId") == runId && record.getString("accountId") == accountId)
        require(UUID_PATTERN.matches(record.getString("operationId")))
        require(record.getString("producer") in listOf("android", "browser"))
        val rawAmount = record.get("amountMillis")
        require(rawAmount is Number && rawAmount.toDouble() == rawAmount.toInt().toDouble() && rawAmount.toInt() >= 0)
        val notes = record.getString("notes")
        require(notes.length <= 10000)
        val expected = progress(rawAmount.toInt(), notes)
        val actual = record.getJSONObject("progress")
        for (field in listOf("date", "amountMillis", "notes", "streakLength")) {
            require(actual.get(field) == expected.get(field)) { "Android/browser core progress mismatch: $field" }
        }
        val score = actual.getDouble("score")
        require(score.isFinite() && abs(score - expected.getDouble("score")) <= 1e-10) { "Android/browser score mismatch" }
    }

    private fun request(url: String, method: String = "GET", body: String? = null, contentType: String? = null): String {
        requests++
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", requireNotNull(contentType))
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode == 401) throw DriveReconnectRequired()
            check(connection.responseCode in 200..299) { "Drive request failed (${connection.responseCode}); retry this run" }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val NAMESPACE = "loop-drive-integration-v1"
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
