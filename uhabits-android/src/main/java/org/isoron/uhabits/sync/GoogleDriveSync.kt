package org.isoron.uhabits.sync

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.isoron.platform.time.computeToday
import org.isoron.platform.time.getToday
import org.isoron.platform.time.setToday
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.database.HabitRepository
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Direct Drive synchronization; authorization remains with Google Play services. */
class GoogleDriveSync(private val application: HabitsApplication) : Application.ActivityLifecycleCallbacks {
    private val component = application.component
    private val store = component.changeStore
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val preferences = application.getSharedPreferences("loop-drive-config", 0)
    private var scheduled: ScheduledFuture<*>? = null
    private var activeToken: String? = null
    private var rejectedToken: String? = null
    private var authorizing = false
    private var failures = 0

    @Volatile private var visible = 0

    @Volatile private var transport: DriveWorkspaceTransport? = null

    @Volatile var status = "Local device · connect Google Drive"
        private set
    val workspace: String get() = preferences.getString("workspace", "default")!!

    init {
        store.onLocalChange = {
            updateStatus("Saved on device · pending upload")
            requestSync()
            if (store.history().accountId != "unbound" && !preferences.getBoolean("disconnected", false)) DriveSyncJobService.schedule(application, true)
        }
        component.commandRunner.addListener(object : org.isoron.uhabits.core.commands.CommandRunner.Listener {
            override fun onCommandFinished(command: org.isoron.uhabits.core.commands.Command) {}
            override fun onCommandFailed(command: org.isoron.uhabits.core.commands.Command, message: String) {
                refreshNativeModels()
                updateStatus(message)
                android.widget.Toast.makeText(application, message, android.widget.Toast.LENGTH_LONG).show()
            }
        })
        application.registerActivityLifecycleCallbacks(this)
        val connectivity = application.getSystemService(ConnectivityManager::class.java)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { requestSync(0) }
            }
        )
        executor.scheduleAtFixedRate({ if (visible > 0) synchronize() }, 30, 30, TimeUnit.SECONDS)
        if (store.history().accountId != "unbound") {
            updateStatus("Reconnect required · saved data available")
            if (!preferences.getBoolean("disconnected", false)) DriveSyncJobService.schedule(application, store.pendingCount() > 0)
        }
    }

    fun configureTestWorkspace(run: String) {
        if (BuildConfig.DEBUG && workspace == run) return
        require(BuildConfig.DEBUG && Regex("[a-f0-9-]{36}").matches(run) && store.history().changes.isEmpty()) { "Test workspace can only be selected on an empty debug installation" }
        check(preferences.edit().putString("workspace", run).commit()) { "Could not save test configuration" }
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener); listener() }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    fun pendingCount() = store.pendingCount()
    fun account() = preferences.getString("email", "Not connected")!!
    fun history() = store.history()

    fun resolve(key: String, value: kotlinx.serialization.json.JsonElement, revisions: Set<String>) = localAction { store.resolve(key, value, revisions) }
    fun restore(uuid: String) = localAction { store.restore(uuid) }
    fun purge(uuid: String) = localAction { store.purge(uuid) }

    private fun localAction(action: () -> Unit) {
        executor.execute {
            try { action(); refreshNativeModels(); updateStatus("Saved on device · pending upload"); requestSync(0) } catch (error: Exception) { updateStatus(error.message ?: "Local save failed") }
        }
    }

    fun disconnect() {
        executor.execute {
            transport = null; activeToken = null; rejectedToken = null
            DriveSyncJobService.cancel(application)
            if (!preferences.edit().putBoolean("disconnected", true).commit()) {
                updateStatus("Could not save disconnect preference")
            } else {
                updateStatus("Disconnected · saved data and pending edits remain bound to this account")
            }
        }
    }

    fun authorize(activity: Activity?, resolution: ((PendingIntent) -> Unit)? = null) {
        if (authorizing) return
        authorizing = true
        val client = Identity.getAuthorizationClient(activity ?: application)
        fun request() {
            val authorization = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
            client.authorize(authorization).addOnSuccessListener { response ->
                authorizing = false
                if (response.hasResolution()) {
                    updateStatus("Reconnect required · authorize Google Drive")
                    if (resolution != null) resolution(requireNotNull(response.pendingIntent))
                } else {
                    val token = response.accessToken
                    if (token == null) updateStatus("Reconnect required · no authorization token") else acceptToken(token)
                }
            }.addOnFailureListener {
                authorizing = false
                updateStatus("Reconnect required · Google authorization unavailable")
            }
        }
        val rejected = rejectedToken
        if (rejected != null) {
            client.clearToken(ClearTokenRequest.builder().setToken(rejected).build()).addOnSuccessListener {
                rejectedToken = null
                request()
            }.addOnFailureListener {
                authorizing = false
                updateStatus("Reconnect required · could not clear expired authorization")
            }
        } else {
            request()
        }
    }

    fun acceptToken(token: String) {
        activeToken = token
        executor.execute {
            try {
                val candidate = DriveWorkspaceTransport(token, workspace)
                candidate.connect()
                store.connectAccount(candidate.accountId)
                check(preferences.edit().putString("email", candidate.email).putBoolean("disconnected", false).commit()) { "Could not save account configuration" }
                transport = candidate
                DriveSyncJobService.schedule(application, store.pendingCount() > 0)
                updateStatus("Connected · synchronizing")
                synchronize()
            } catch (error: Exception) { failed(error) }
        }
    }

    fun requestSync(delay: Long = 400) {
        synchronized(this) {
            scheduled?.cancel(false)
            scheduled = executor.schedule({ synchronize() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun synchronize(): Boolean {
        val drive = transport ?: return false
        updateStatus("Synchronizing · saved on device")
        try {
            val purging = store.history().purgedHabits.isNotEmpty()
            val (incoming, accepted) = drive.discover(if (purging) emptySet() else store.knownFiles(), purging)
            if (incoming.isNotEmpty()) {
                store.mergeBatch(incoming, accepted)
                refreshNativeModels()
            }
            val knownSettings = store.history().changes.flatMap { it.edits.keys }.toSet()
            if ("setting:dayStart" !in knownSettings) store.updateSetting("dayStart", component.preferences.midnightDelayHours)
            if ("setting:weekStart" !in knownSettings) store.updateSetting("weekStart", component.preferences.firstWeekday.daysSinceSunday + 1)
            if (store.pendingCount() > 0) {
                val revision = drive.publish(store.history(), store.deviceId)
                store.acknowledge(revision)
            }
            if (purging) drive.scrubPurged(store.history())
            failures = 0
            val history = store.history()
            val conflicts = history.conflicts().size
            updateStatus(if (conflicts > 0) "Synchronized · $conflicts competing values preserved" else if (store.pendingCount() > 0) "Saved on device · pending upload" else "Synchronized with Drive")
            return true
        } catch (error: Exception) { failed(error); return false }
    }

    /** A job can request silent authorization; interactive consent never launches here. */
    fun synchronizeInBackground(finished: (Boolean) -> Unit) {
        if (preferences.getBoolean("disconnected", false) || store.history().accountId == "unbound") {
            main.post { finished(false) }
            return
        }
        main.post {
            Identity.getAuthorizationClient(application).authorize(
                AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
            ).addOnSuccessListener { response ->
                val token = response.accessToken
                if (response.hasResolution() || token == null) {
                    updateStatus("Reconnect required · background changes remain saved on device")
                    finished(false)
                } else {
                    executor.execute {
                        var retry = false
                        try {
                            if (!preferences.getBoolean("disconnected", false)) {
                                val candidate = DriveWorkspaceTransport(token, workspace)
                                candidate.connect()
                                store.connectAccount(candidate.accountId)
                                activeToken = token
                                transport = candidate
                                retry = !synchronize() && transport != null
                            }
                        } catch (error: Exception) {
                            failed(error)
                            retry = error !is WorkspaceAuthorizationExpired && error !is IllegalArgumentException
                        }
                        main.post { finished(retry) }
                    }
                }
            }.addOnFailureListener {
                updateStatus("Background sync deferred · return to Loop to reconnect")
                finished(true)
            }
        }
    }

    private fun failed(error: Exception) {
        if (error is WorkspaceAuthorizationExpired) {
            rejectedToken = activeToken
            activeToken = null
            transport = null
            updateStatus(error.message!!)
        } else {
            updateStatus(error.message ?: "Sync failed · saved changes remain pending")
            if (transport != null && visible > 0) requestSync(minOf(60000L, 1000L shl minOf(++failures, 6)))
        }
    }

    fun refreshNativeModels() {
        main.post {
            val records = HabitRepository(component.db).findAll().associateBy { it.uuid }
            val list = component.habitList
            setToday(computeToday(component.preferences.midnightDelayHours, 0))
            for (habit in list) {
                val record = records[habit.uuid]
                if (record == null || record.reminderHour == null || record.archived != 0) {
                    component.notificationTray.cancel(habit)
                    application.getSystemService(android.app.AlarmManager::class.java).cancel(component.pendingIntentFactory.showReminder(habit, null, 0))
                }
                record?.let { SQLiteHabitList.copyTo(it, habit) }
                (habit.originalEntries as? SQLiteEntryList)?.invalidate()
                habit.recompute()
                habit.observable.notifyListeners()
            }
            (list as? SQLiteHabitList)?.reload()
            for (habit in list) habit.recompute()
            list.observable.notifyListeners()
            component.reminderScheduler.scheduleAll()
            component.widgetUpdater.updateWidgets()
        }
    }

    private fun updateStatus(value: String) { status = value; main.post { listeners.forEach { it() } } }

    override fun onActivityResumed(activity: Activity) {
        visible++
        if (getToday() != computeToday(component.preferences.midnightDelayHours, 0)) {
            refreshNativeModels()
            updateStatus(status)
        }
        if (transport == null && store.history().accountId != "unbound" && !preferences.getBoolean("disconnected", false)) authorize(null)
        requestSync(0)
    }
    override fun onActivityPaused(activity: Activity) { visible = maxOf(0, visible - 1) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object { const val SCOPE = "https://www.googleapis.com/auth/drive.appdata" }
}
