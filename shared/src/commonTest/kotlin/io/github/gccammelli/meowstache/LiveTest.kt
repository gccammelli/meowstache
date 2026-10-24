package io.github.gccammelli.meowstache

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Instant notifications (GitHub #4) must stay gentle on Whisker's servers: these pin the limits. */
class LiveTest {
    private fun robot(status: String, wifi: Int) = buildJsonObject { put("serial", "LR4X"); put("robotStatus", status); put("wifiRssi", wifi) }

    @Test fun onlyMeaningfulChangesTriggerAChecks() {
        val f = LiveFilter()
        assertFalse(f.changed(listOf(robot("ROBOT_IDLE", -50)))) // first sight
        assertFalse(f.changed(listOf(robot("ROBOT_IDLE", -61)))) // Wi-Fi strength only
        assertTrue(f.changed(listOf(robot("ROBOT_CAT_DETECT", -61))))
        assertFalse(f.changed(listOf(robot("ROBOT_CAT_DETECT", -58))))
        assertFalse(f.changed(listOf<JsonObject>()))
    }

    @Test fun checksAreAtLeastTwoMinutesApart() {
        var t = 1_000_000L
        val p = CheckPacer(now = { t })
        assertEquals(0, p.waitMs()); p.ran()
        t += 30_000; assertEquals(90_000, p.waitMs())
        t += 90_000; assertEquals(0, p.waitMs())
    }

    @Test fun reconnectsBackOffAndNeverRetryAtOnce() {
        val b = Backoff()
        val waits = List(8) { b.next(lastedMs = 5_000) } // the connection keeps failing quickly
        assertEquals(listOf(30_000L, 60_000, 120_000, 240_000, 480_000, 960_000, 1_800_000, 1_800_000), waits)
        assertEquals(30_000, b.next(lastedMs = 3_600_000)) // after a long healthy connection: start small again
        assertTrue(waits.all { it >= 30_000 })
    }

    @Test fun requestGuardCapsEveryBurst() {
        var t = 0L
        val g = RequestGuard(perMinute = 60, perHour = 400, now = { t })
        repeat(60) { assertTrue(g.allow()) }
        assertFalse(g.allow()) // 61st in a minute
        t += 60_000
        repeat(60) { assertTrue(g.allow()) }
        // An hour's budget, whatever the pace (after the minute of the last burst).
        t += 50_000
        repeat(400 - 120) { t += 10_000; assertTrue(g.allow()) }
        t += 10_000; assertFalse(g.allow())
        t += 3_600_000
        assertTrue(g.allow()) // an hour later, free again
    }
    @Test fun malformedRobotIdentifiersAndUnknownFieldsNeverTriggerAnAlertCheck() {
        val filter=LiveFilter()
        val unknown=kotlinx.serialization.json.JsonObject(emptyMap())
        assertFalse(filter.changed(listOf(unknown,buildJsonObject { put("serial",kotlinx.serialization.json.JsonNull) })))
        assertFalse(filter.changed(listOf(robot("ROBOT_IDLE",-50))))
        assertFalse(filter.changed(listOf(buildJsonObject { put("serial","LR4X"); put("robotStatus","ROBOT_IDLE"); put("isOnline",kotlinx.serialization.json.JsonObject(emptyMap())) })))
        assertTrue(filter.changed(listOf(robot("ROBOT_CLEAN",-50))))
    }

}
