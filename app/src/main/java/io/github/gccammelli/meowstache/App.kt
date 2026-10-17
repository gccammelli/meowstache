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

/** One alert check: refresh (incremental), evaluate every rule, post what's new. False if the refresh failed. */
suspend fun checkAlerts(ctx: Context): Boolean {
    val s = Repo.alerts.value
    val t = s.robot
    if (!s.noVisitEnabled && !s.tooManyEnabled && s.targets.isEmpty() && !s.patternsEnabled && !s.recapEnabled && !t.usage && !t.errors && !t.updates)
        return true
    // Full refresh: recaps and patterns need fresh activity for visit durations (incremental, so cheap).
    Repo.refresh(full = true)
    val st = Repo.state.value
    if (st.error != null) return false
    val alerts = allAlerts(st, s).toMutableList()
    if (t.updates && Repo.firmwareCheckDue()) for (r in st.robots) {
        val latest = Repo.latestFirmware(r) ?: continue
        if ("Update available" in latest) alerts += robotAlerts(System.currentTimeMillis(), r.snapshot(st, latest.lines().first()), t.copy(usage = false, errors = false))
    }
    AlertWorker.notifyNew(ctx, alerts)
    return true
}

/** Checks the pet alert rules every 30 minutes and posts each new alert once. */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Repo.init(androidStorage(applicationContext))
        if (!Repo.canCheckAlerts) return Result.success()
        if (LiveService.connected()) return Result.success() // instant notifications are on and connected: nothing to poll
        return if (checkAlerts(applicationContext)) Result.success() else Result.retry()
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
            val nm = NotificationManagerCompat.from(ctx)
            for (a in fresh) {
                if (!canPost) continue
                val id = a.slot.hashCode()
                // Updates only rewrite a notification that's still showing; a dismissed one stays dismissed.
                if (a.update && nm.activeNotifications.none { it.id == id }) continue
                nm.notify(id,
                    NotificationCompat.Builder(ctx, CHANNEL)
                        .setOnlyAlertOnce(true)
                        .setSilent(a.update)
                        .setSmallIcon(R.drawable.ic_notification)
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
