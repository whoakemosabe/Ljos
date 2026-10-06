package app.ljos.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ljos.data.Repo
import app.ljos.widget.Widgets
import java.util.concurrent.TimeUnit

/** Runs every ~15 minutes: fetch feeds, fire alerts, redraw widgets. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repo(applicationContext)
        repo.refresh()
        val inp = repo.inputs()
        try { Alerts.check(applicationContext, inp) } catch (e: Exception) { }
        try { Widgets.updateAll(applicationContext) } catch (e: Exception) { }
        return Result.success()
    }
}

object Scheduler {
    private const val PERIODIC = "ljos-refresh"

    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
