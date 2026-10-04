package org.isoron.uhabits.sync

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.concurrent.Executors

/** Debug-only native half of the real Drive feasibility gate, separate from habit storage. */
class DriveIntegrationActivity : AppCompatActivity() {
    private lateinit var runId: EditText
    private lateinit var amount: EditText
    private lateinit var notes: EditText
    private lateinit var status: TextView
    private lateinit var evidence: TextView
    private lateinit var connect: Button
    private lateinit var publish: Button
    private lateinit var discover: Button
    private var gate: DriveIntegrationGate? = null
    private var activeToken: String? = null
    private var rejectedToken: String? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            status.text = "Authorization cancelled. Connect again."
        } else {
            try {
                val response = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
                acceptToken(requireNotNull(response.accessToken))
            } catch (error: Exception) {
                status.text = "Authorization unavailable. Check Google client configuration and connect again."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        content.addView(TextView(this).apply { text = "Loop · Drive integration gate\nSynthetic samples only; issue #2 is not yet complete." })
        fun input(label: String, value: String): EditText {
            content.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                contentDescription = label
                content.addView(this)
            }
        }
        runId = input("Test run UUID", "").apply { isSingleLine = true }
        amount = input("Numeric amount", "12.345").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        notes = input("Entry notes", "Synthetic Android notes — آزمائش")
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            content.addView(this)
        }
        connect = button("Connect Google Drive", ::authorize)
        publish = button("Publish Android sample") {
            val run = runId.text.toString().trim()
            val note = notes.text.toString()
            val value = amount.text.toString().toBigDecimalOrNull()
            val millis = try {
                value?.movePointRight(3)?.intValueExact()
            } catch (error: ArithmeticException) {
                null
            }
            if (millis == null || millis < 0) {
                status.text = "Enter a non-negative amount with at most three decimal places."
            } else {
                perform {
                    requireNotNull(gate).publish(run, millis, note)
                    "Android sample accepted by Drive. Discover this run in the browser."
                }
            }
        }
        discover = button("Discover this run") {
            val run = runId.text.toString().trim()
            perform { requireNotNull(gate).discover(run).toString(2) }
        }
        status = TextView(this).apply {
            text = "Live environment unavailable: connect Google Drive."
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            content.addView(this)
        }
        evidence = TextView(this).apply { setTextIsSelectable(true); content.addView(this) }
        setContentView(ScrollView(this).apply { addView(content) })
        updateButtons(false)
    }

    private fun authorize() {
        gate = null
        updateButtons(true)
        val rejected = rejectedToken
        if (rejected != null) {
            Identity.getAuthorizationClient(this)
                .clearToken(ClearTokenRequest.builder().setToken(rejected).build())
                .addOnSuccessListener {
                    rejectedToken = null
                    requestAuthorization()
                }
                .addOnFailureListener {
                    status.text = "Could not clear Google's rejected token. Connect again to retry."
                    updateButtons(false)
                }
        } else {
            requestAuthorization()
        }
    }

    private fun requestAuthorization() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveIntegrationGate.SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { response ->
                updateButtons(false)
                if (response.hasResolution()) {
                    val intent = requireNotNull(response.pendingIntent).intentSender
                    authorization.launch(IntentSenderRequest.Builder(intent).build())
                } else {
                    val token = response.accessToken
                    if (token == null) status.text = "Authorization returned no access token." else acceptToken(token)
                }
            }
            .addOnFailureListener {
                status.text = "Authorization unavailable. Check Google Play services, package name and SHA-1 registration."
                updateButtons(false)
            }
    }

    private fun acceptToken(token: String) = perform {
        activeToken = token
        val candidate = DriveIntegrationGate(token)
        val account = candidate.connect()
        gate = candidate
        "Connected as $account. Ready for the synthetic test run."
    }

    private fun updateButtons(busy: Boolean) {
        connect.isEnabled = !busy
        publish.isEnabled = !busy && gate != null
        discover.isEnabled = !busy && gate != null
    }

    private fun perform(work: () -> String) {
        updateButtons(true)
        status.text = "Contacting Google Drive…"
        executor.execute {
            val result = try {
                work()
            } catch (error: Exception) {
                if (error is DriveReconnectRequired) {
                    rejectedToken = activeToken
                    gate = null
                }
                error.message ?: "Integration request failed. Reconnect and retry."
            }
            runOnUiThread {
                if (!isDestroyed) {
                    evidence.text = result
                    status.text = "Request finished. Review the result below."
                    updateButtons(false)
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
