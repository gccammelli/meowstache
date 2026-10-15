package io.github.gccammelli.meowstache

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val CHANNEL = "pet_alerts"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(androidStorage(this))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pet alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (Repo.canCheckAlerts) AlertWorker.schedule(this)
    }
}

/** Checks the pet alert rules every 30 minutes and posts each new alert once. */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Repo.init(androidStorage(applicationContext))
        if (!Repo.canCheckAlerts) return Result.success()
        val s = Repo.alerts.value
        val t = s.robot
        if (!s.noVisitEnabled && !s.tooManyEnabled && s.targets.isEmpty() && !s.patternsEnabled && !s.recapEnabled && !t.usage && !t.errors && !t.updates)
            return Result.success()
        // Full refresh: recaps and patterns need fresh activity for visit durations (incremental, so cheap).
        Repo.refresh(full = true)
        val st = Repo.state.value
        if (st.error != null) return Result.retry()
        val alerts = allAlerts(st, s).toMutableList()
        if (t.updates) for (r in st.robots) {
            val latest = Repo.latestFirmware(r) ?: continue
            if ("Update available" in latest) alerts += robotAlerts(System.currentTimeMillis(), r.snapshot(st, latest.lines().first()), t.copy(usage = false, errors = false))
        }
        notifyNew(applicationContext, alerts)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("alerts", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun notifyNew(ctx: Context, alerts: List<PetAlert>) {
            val fresh = Repo.takeFresh(alerts)
            if (fresh.isEmpty()) return
            val canPost = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            for (a in fresh) {
                if (!canPost) continue
                NotificationManagerCompat.from(ctx).notify(a.key.hashCode(),
                    NotificationCompat.Builder(ctx, CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_fg)
                        .setContentTitle(a.title)
                        .setContentText(a.body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build())
            }
        }
    }
}
