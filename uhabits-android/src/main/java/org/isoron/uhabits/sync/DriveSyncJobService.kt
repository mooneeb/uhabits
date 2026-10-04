package org.isoron.uhabits.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.isoron.uhabits.HabitsApplication

/** OS-constrained, network-required synchronization. Google consent stays in the foreground. */
class DriveSyncJobService : JobService() {
    private val running = mutableSetOf<JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        val sync = (application as HabitsApplication).driveSync ?: return false
        running.add(params)
        sync.synchronizeInBackground { retry ->
            if (running.remove(params)) {
                jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params)
        // Committed history and unacknowledged work remain safe even if the OS interrupts delivery.
        return true
    }

    companion object {
        private const val PERIODIC = 7401
        private const val PENDING = 7402

        fun schedule(context: Context, pending: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, DriveSyncJobService::class.java)
            val periodic = JobInfo.Builder(PERIODIC, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .build()
            if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(periodic)
            if (pending && scheduler.getPendingJob(PENDING) == null) {
                scheduler.schedule(
                    JobInfo.Builder(PENDING, component)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build()
                )
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).apply { cancel(PERIODIC); cancel(PENDING) }
        }
    }
}
