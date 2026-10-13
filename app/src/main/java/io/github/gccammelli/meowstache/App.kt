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
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private const val CHANNEL = "pet_alerts"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pet alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        if (Repo.api.isLoggedIn) AlertWorker.schedule(this)
    }
}

/** Checks the pet alert rules every 30 minutes and posts each new alert once. */
/** Every alert rule: the original pet alerts plus the premium-style recap and pattern alerts. */
fun allAlerts(st: AppState, s: AlertSettings, now: Long = System.currentTimeMillis()): List<PetAlert> {
    val fmt: (Double) -> String = { Repo.weight(it) }
    val out = evaluateAlerts(now, st.pets.map { it.id to it.name }, st.history, s, fmt).toMutableList()
    val today = LocalDate.now(); val y = today.minusDays(1)
    if (s.recapEnabled && LocalTime.now().hour >= s.recapHour && st.pets.isNotEmpty()) {
        val lines = st.pets.map { recapLine(it.name, dayStats(st.history[it.id].orEmpty(), st.durations(it.id), y), fmt) }
        out += PetAlert("recap:$today", "", "Daily recap · ${y.dayOfMonth}/${y.monthValue}", lines.joinToString("\n"))
    }
    if (s.patternsEnabled) for (p in st.pets) {
        detectPatterns(st.history[p.id].orEmpty(), st.durations(p.id), y).filter { it.significant }.forEach {
            out += PetAlert("pattern:${p.id}:$y:${it.metric}", p.id, "${p.name}: unusual ${it.metric.label.lowercase()}", it.text(fmt))
        }
    }
    out += st.robots.flatMap { r -> robotAlerts(now, r.snapshot(st), s.robot) }
    return out
}

fun Robot.snapshot(st: AppState, firmwareUpdate: String? = null) =
    RobotSnapshot(serial, name, isOnline, lastSeen, litterState, setup, st.acts[serial].orEmpty(), firmwareUpdate)

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        if (!Repo.api.isLoggedIn) return Result.success()
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
            val sent = Repo.sentAlertKeys()
            val fresh = alerts.filter { it.key !in sent }
            if (fresh.isEmpty()) return
            Repo.markSent(fresh.map { it.key })
            val canPost = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            for (a in fresh) {
                Repo.addMessage(Message(System.currentTimeMillis(), a.title, a.body))
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
