package io.github.gccammelli.meowstache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/*
 * Instant notifications (GitHub #4): a live connection reports robot changes as they happen, and each meaningful
 * change runs the usual alert check. These rules keep that gentle on Whisker's servers; the platform code only
 * holds the connection and follows them.
 */

/** The robot fields worth an alert check when they change. Others (Wi-Fi strength, timers, counters) never trigger one. */
val LIVE_FIELDS = listOf("robotStatus", "robotCycleStatus", "robotCycleState", "catDetect", "isDFIFull", "isOnline", "unitPowerType", "hopperStatus")

/** Remembers each robot's [LIVE_FIELDS]; [changed] is true when an update differs from the last one seen. */
class LiveFilter {
    private val last = mutableMapOf<String, String>()

    fun changed(robots: List<JsonObject>): Boolean {
        var any = false
        for (r in robots) {
            val serial = r["serial"]?.str() ?: continue
            val key = LIVE_FIELDS.joinToString("|") { r[it]?.str().orEmpty() }
            val before = last.put(serial, key)
            if (before != null && before != key) any = true // the first sight of a robot is just its starting point
        }
        return any
    }
}

/** At most one alert check per [gapMs], however many changes arrive: [waitMs] says how long until the next may run. */
class CheckPacer(private val gapMs: Long = 120_000, private val now: () -> Long = ::nowMs) {
    private var lastRun = Long.MIN_VALUE / 2

    fun waitMs(): Long = (lastRun + gapMs - now()).coerceAtLeast(0)
    fun ran() { lastRun = now() }
}

/** Waits between reconnects: 30 s doubling to 30 min, back to 30 s after a connection that lasted 10 minutes. Never 0. */
class Backoff(private val minMs: Long = 30_000, private val maxMs: Long = 30 * 60_000L) {
    private var wait = minMs

    fun next(lastedMs: Long): Long {
        if (lastedMs >= 10 * 60_000L) wait = minMs
        val w = wait
        wait = (wait * 2).coerceAtMost(maxMs)
        return w
    }
}

/**
 * Holds the live connection for as long as the caller runs it: [onAlive] on every message (keep-alives too),
 * [onChange] when a robot's state really changed. Reconnects only after [Backoff]'s wait, never right away.
 */
suspend fun liveLoop(api: Api, onAlive: () -> Unit, onChange: () -> Unit, backoff: Backoff = Backoff()) {
    val filter = LiveFilter()
    while (true) {
        val started = nowMs()
        try {
            api.liveRobots(onAlive) { robots -> if (filter.changed(robots)) onChange() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { // offline, signed out, server closed it: wait and try again
        }
        delay(backoff.next(nowMs() - started))
    }
}
