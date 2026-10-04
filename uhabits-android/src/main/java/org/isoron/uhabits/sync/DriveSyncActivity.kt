package org.isoron.uhabits.sync

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.serialization.json.JsonPrimitive
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.sync.RegisterValues

class DriveSyncActivity : AppCompatActivity() {
    private val sync get() = (application as HabitsApplication).driveSync!!
    private lateinit var status: TextView
    private lateinit var recovery: LinearLayout
    private var displayedHistory: String? = null
    private val refresh: () -> Unit = {
        status.text = "${sync.account()}\n${sync.status}\n${sync.pendingCount()} pending changes"
        val history = sync.history()
        if (displayedHistory != history.encode()) {
            displayedHistory = history.encode()
            recovery.removeAllViews()
            recovery.addView(TextView(this).apply { text = "Competing revisions"; textSize = 20f })
            for ((key, revisions) in history.conflicts()) {
                recovery.addView(TextView(this).apply { text = key })
                for (revision in revisions.distinctBy { it.value }) {
                    recovery.addView(TextView(this).apply { text = revision.value.toString() })
                    recovery.addView(
                        Button(this).apply {
                            text = "Keep this version"
                            setOnClickListener {
                                AlertDialog.Builder(this@DriveSyncActivity).setTitle("Resolve competing revisions?")
                                    .setMessage("$key\n${revision.value}")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Keep this version") { _, _ -> sync.resolve(key, revision.value, revisions.map { it.changeId }.toSet()) }.show()
                            }
                        }
                    )
                }
            }
            recovery.addView(TextView(this).apply { text = "Deleted habit recovery"; textSize = 20f })
            val ids = history.keys().filter { it.startsWith("habit:") }.map { it.split(':')[1] }.toSet()
            for (uuid in ids.filter { history.value("habit:$it:deleted") == JsonPrimitive(true) }) {
                val name = history.value("habit:$uuid:name")?.let { RegisterValues.text(it) } ?: uuid
                recovery.addView(
                    Button(this).apply {
                        text = "Inspect deleted habit: $name"
                        setOnClickListener {
                            val retained = history.keys().filter { it.startsWith("habit:$uuid:") || it.startsWith("entry:$uuid:") }
                                .joinToString("\n") { "$it\n${history.value(it) ?: "Competing revisions preserved"}" }
                            AlertDialog.Builder(this@DriveSyncActivity).setTitle(name).setMessage(retained)
                                .setNegativeButton("Close", null).setPositiveButton("Restore habit") { _, _ -> sync.restore(uuid) }.show()
                        }
                    }
                )
                recovery.addView(
                    Button(this).apply {
                        text = "Purge permanently: $name"
                        setOnClickListener {
                            AlertDialog.Builder(this@DriveSyncActivity).setTitle("Permanently purge $name?")
                                .setMessage("This removes its definition and history. It cannot be restored. Resolve any conflicts first.")
                                .setNegativeButton("Cancel", null).setPositiveButton("Purge permanently") { _, _ -> sync.purge(uuid) }.show()
                        }
                    }
                )
            }
        }
    }
    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                val response = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                sync.acceptToken(requireNotNull(response.accessToken))
            } catch (error: Exception) { status.text = "Google authorization was unavailable. Connect again." }
        } else {
            status.text = "Google authorization cancelled. Saved data remains on this device."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        content.addView(TextView(this).apply { text = "Google Drive synchronization"; textSize = 24f })
        content.addView(TextView(this).apply { text = "Habits save on this device and synchronize directly through your private Google Drive application data. An upload does not mean every other device has downloaded it yet." })
        content.addView(
            Button(this).apply {
                text = "Connect Google Drive"
                setOnClickListener { sync.authorize(this@DriveSyncActivity) { intent -> authorization.launch(IntentSenderRequest.Builder(intent.intentSender).build()) } }
            }
        )
        content.addView(Button(this).apply { text = "Sync now"; setOnClickListener { sync.requestSync(0) } })
        content.addView(
            Button(this).apply {
                text = "Disconnect Google Drive"
                setOnClickListener {
                    AlertDialog.Builder(this@DriveSyncActivity).setTitle("Disconnect Google Drive?")
                        .setMessage("${sync.pendingCount()} pending changes. Saved habits and pending changes remain on this device, bound to this Google account. Reconnect the same account to upload them.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Disconnect") { _, _ -> sync.disconnect() }.show()
                }
            }
        )
        status = TextView(this).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        recovery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(recovery)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); sync.addListener(refresh) }
    override fun onPause() { sync.removeListener(refresh); super.onPause() }
}
