package org.isoron.uhabits.sync

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.Identity
import org.isoron.uhabits.HabitsApplication

class DriveSyncActivity : AppCompatActivity() {
    private val sync get() = (application as HabitsApplication).driveSync!!
    private lateinit var status: TextView
    private val refresh: () -> Unit = { status.text = "${sync.account()}\n${sync.status}\n${sync.pendingCount()} pending changes" }
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
        status = TextView(this).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); sync.addListener(refresh) }
    override fun onPause() { sync.removeListener(refresh); super.onPause() }
}
