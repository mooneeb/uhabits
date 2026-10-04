package org.isoron.uhabits.sync

import android.net.Uri
import org.isoron.uhabits.core.sync.ChangeHistory
import org.isoron.uhabits.core.sync.DrivePack
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class WorkspaceAuthorizationExpired : Exception("Reconnect required. Google authorization expired.")

class DriveWorkspaceTransport(private val token: String, private val workspace: String) {
    var accountId: String = ""
        private set
    var email: String = ""
        private set

    fun connect() {
        val user = JSONObject(request("https://www.googleapis.com/drive/v3/about?fields=user(permissionId,emailAddress)")).getJSONObject("user")
        accountId = user.getString("permissionId")
        require(accountId.isNotBlank()) { "Drive did not return an account identity" }
        email = user.optString("emailAddress", accountId)
    }

    fun discover(known: Set<String>): Pair<List<ChangeHistory>, Set<String>> {
        val files = mutableListOf<JSONObject>()
        var pageToken = ""
        do {
            val uri = Uri.parse("https://www.googleapis.com/drive/v3/files").buildUpon()
                .appendQueryParameter("spaces", "appDataFolder").appendQueryParameter("pageSize", "1000")
                .appendQueryParameter("fields", "nextPageToken,incompleteSearch,files(id,name,mimeType,appProperties)")
                .appendQueryParameter("q", "trashed = false and appProperties has { key='namespace' and value='${DrivePack.NAMESPACE}' } and appProperties has { key='workspace' and value='$workspace' }")
            if (pageToken.isNotEmpty()) uri.appendQueryParameter("pageToken", pageToken)
            val page = JSONObject(request(uri.build().toString()))
            require(!page.optBoolean("incompleteSearch")) { "Incomplete discovery. Saved changes remain pending." }
            val listed = page.getJSONArray("files")
            for (index in 0 until listed.length()) files.add(listed.getJSONObject(index))
            pageToken = page.optString("nextPageToken")
        } while (pageToken.isNotEmpty())
        val latest = mutableMapOf<String, MutableList<JSONObject>>()
        for (file in files) {
            val properties = file.getJSONObject("appProperties")
            require(properties.get("namespace") == DrivePack.NAMESPACE && properties.get("workspace") == workspace && file.get("mimeType") == "application/json") { "Invalid workspace metadata" }
            val device = properties.get("device") as? String ?: throw IllegalArgumentException("Invalid device identity")
            val revisionText = properties.get("revision") as? String ?: throw IllegalArgumentException("Invalid device revision")
            val revision = revisionText.toIntOrNull()
            require(Regex("[a-zA-Z0-9_-]{1,128}").matches(device) && revision != null && revision > 0 && revisionText == revision.toString()) { "Invalid device revision" }
            val previous = latest[device]
            val previousRevision = previous?.first()?.getJSONObject("appProperties")?.getString("revision")?.toInt() ?: 0
            if (revision > previousRevision) latest[device] = mutableListOf(file) else if (revision == previousRevision) previous!!.add(file)
        }
        val incoming = mutableListOf<ChangeHistory>()
        val accepted = mutableSetOf<String>()
        for (file in latest.values.flatten()) {
            val id = file.getString("id")
            if (id in known) continue
            val content = request("https://www.googleapis.com/drive/v3/files/${Uri.encode(id)}?alt=media")
            val pack = DrivePack.decode(content, accountId, workspace)
            val properties = file.getJSONObject("appProperties")
            require(
                pack.deviceId == properties.getString("device") && pack.revision.toString() == properties.getString("revision") &&
                    file.getString("name") == "${DrivePack.NAMESPACE}-$workspace-${pack.deviceId}-${pack.revision}.json"
            ) { "Package metadata mismatch" }
            incoming.add(pack.history)
            accepted.add(id)
        }
        return incoming to accepted
    }

    fun publish(history: ChangeHistory, device: String): Int {
        val pack = DrivePack.create(history, device, workspace) ?: return 0
        val content = pack.encode()
        require(content.toByteArray(Charsets.UTF_8).size <= 10000000) { "History exceeds the supported package size. Changes remain saved locally." }
        val properties = JSONObject().put("namespace", DrivePack.NAMESPACE).put("workspace", workspace).put("device", device).put("revision", pack.revision.toString())
        val metadata = JSONObject().put("name", "${DrivePack.NAMESPACE}-$workspace-$device-${pack.revision}.json")
            .put("mimeType", "application/json").put("parents", JSONArray().put("appDataFolder")).put("appProperties", properties)
        val boundary = "loop_${UUID.randomUUID()}"
        val body = "--$boundary\r\nContent-Type: application/json\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n$content\r\n--$boundary--\r\n"
        request("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", "POST", body, "multipart/related; boundary=$boundary")
        return pack.revision
    }

    private fun request(url: String, method: String = "GET", body: String? = null, contentType: String = "application/json"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode == 401) throw WorkspaceAuthorizationExpired()
            require(connection.responseCode in 200..299) { "Drive is unavailable (${connection.responseCode}). Saved changes remain pending." }
            return connection.inputStream.use { stream ->
                val bytes = stream.readBytesLimited(10000000)
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= max) { "Workspace package exceeds the supported size" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
