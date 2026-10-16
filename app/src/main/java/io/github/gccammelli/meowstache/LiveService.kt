package io.github.gccammelli.meowstache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LIVE_CHANNEL = "live"

/**
 * Instant notifications (GitHub #4): holds Whisker's live robot connection and runs the alert check when a robot's
 * state really changes. Gentle on Whisker's servers by construction: one connection, reconnects that back off
 * ([Backoff]), at most one check per 2 minutes ([CheckPacer]), only for meaningful changes ([LiveFilter]), all
 * under [Api.guard]. Android requires the permanent (silent) notification for a connection kept in the background.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pacer = CheckPacer()
    private var pendingCheck: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(androidStorage(this))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(LIVE_CHANNEL, "Instant notifications connection", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note: Notification = NotificationCompat.Builder(this, LIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Listening for your robot")
            .setContentText("For instant notifications. Turn off in Notifications settings.")
            .setContentIntent(open).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        ServiceCompat.startForeground(this, 1, note,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        scope.launch { listen() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        aliveAt = 0
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun listen() =
        liveLoop(Repo.api, onAlive = {
            if (aliveAt == 0L) { // first connection since the service started: catch up on anything missed meanwhile
                android.util.Log.i("Meowstache", "live connection up")
                requestCheck()
            }
            aliveAt = System.currentTimeMillis()
        }, onChange = {
            android.util.Log.i("Meowstache", "robot changed: alert check soon")
            requestCheck()
        })

    /** One check soon; changes arriving before it runs are folded into it. */
    private fun requestCheck() {
        if (pendingCheck?.isActive == true) return
        pendingCheck = scope.launch {
            delay(pacer.waitMs() + 5_000) // a few seconds for the activity log to catch up with the robot
            pacer.ran()
            runCatching { checkAlerts(this@LiveService) }
        }
    }

    companion object {
        @Volatile private var aliveAt = 0L

        /** True while the live connection is up (the 30-minute check then has nothing to do). */
        fun connected() = System.currentTimeMillis() - aliveAt < 10 * 60_000L

        val enabled get() = Repo.instantNotifications

        /** Starts or stops the service to match the setting (call from the foreground: Android limits background starts). */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, LiveService::class.java)
            if (enabled && Repo.api.isLoggedIn && !Repo.demo) {
                runCatching { ctx.startForegroundService(intent) }.onFailure { android.util.Log.w("Meowstache", "live service didn't start", it) }
            }
            else ctx.stopService(intent)
        }
    }
}

/** Brings instant notifications back after a reboot or an app update. */
class LiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(androidStorage(ctx))
        LiveService.sync(ctx)
    }
}
