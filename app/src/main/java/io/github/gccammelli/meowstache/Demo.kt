package io.github.gccammelli.meowstache

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sin
import kotlin.random.Random

const val DEMO_EMAIL = "demo@meowstache.app"

private data class DemoCat(val id: String, val name: String, val lbs: Double, val visits: IntRange, val minutes: ClosedFloatingPointRange<Double>)

/**
 * Made-up robot, cats and ~14 months of history for "Try the demo", so the app can be explored
 * (and screenshotted) without an account or anyone's real data. Seeded, so it's the same every launch;
 * dates are relative to [now], so the data always ends today.
 */
fun demoState(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): AppState {
    val rnd = Random(7)
    val cats = listOf(
        DemoCat("demo-mochi", "Mochi", 11.4, 2..4, 3.5..6.5),
        DemoCat("demo-luna", "Luna", 8.6, 2..3, 2.5..4.5),
    )
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val start = today.minusDays(425)
    val serial = "LR4DEMO0001"
    val weights = cats.associate { it.id to mutableListOf<Reading>() }
    val acts = mutableListOf<Act>()
    var day = start
    while (!day.isAfter(today)) {
        val n = day.toEpochDay() - start.toEpochDay()
        val busy = mutableListOf<LongRange>() // keep the two cats out of the globe at the same time
        for (c in cats) {
            // A spike in Luna's visits 9 days ago gives the Patterns card something to show.
            val count = if (c.name == "Luna" && day == today.minusDays(9)) 7 else c.visits.random(rnd)
            repeat(count) {
                val hour = listOf(6, 7, 8, 12, 15, 18, 20, 21, 22, 23).random(rnd)
                var t0 = day.atTime(hour, rnd.nextInt(60), rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
                while (busy.any { t0 in it }) t0 += 20 * 60_000L
                if (t0 > now - 30 * 60_000L) return@repeat
                val minutes = rnd.nextDouble(c.minutes.start, c.minutes.endInclusive)
                val leave = t0 + (minutes * 60_000).toLong()
                val dump = leave + 7 * 60_000L
                busy += t0 - 60_000L..dump + 5 * 60_000L
                // Slow seasonal drift plus scale noise.
                val lbs = c.lbs + 0.35 * sin(n / 55.0 + c.lbs) + 0.0004 * n * (if (c.name == "Mochi") 1 else -1) + rnd.nextDouble(-0.15, 0.15)
                val r = Math.round(lbs * 100) / 100.0
                weights.getValue(c.id) += Reading(t0 + 40_000, r)
                acts += Act(t0, "robotStatusCatDetect")
                acts += Act(t0 + 40_000, "catWeight", "$r")
                acts += Act(dump, "robotCycleStatusDump")
                acts += Act(dump + 150_000, "robotCycleStatusIdle")
                if (rnd.nextInt(30) == 0) acts += Act(dump + 200_000, "litterHopperDispensed")
            }
        }
        day = day.plusDays(1)
    }
    val cycles = acts.count { it.value == "robotCycleStatusIdle" }
    return AppState(
        firstName = "Alex",
        robots = listOf(Robot(demoRobot(serial, start.atStartOfDay(zone).toInstant(), now, cycles))),
        pets = cats.mapIndexed { i, c -> Pet(demoPet(c, i, weights.getValue(c.id).lastOrNull())) },
        weights = weights.mapValues { (_, l) -> l.sortedBy { it.ts } },
        acts = mapOf(serial to acts.sortedByDescending { it.ts }),
        updatedAt = now,
    )
}

private fun demoRobot(serial: String, setup: Instant, now: Long, cycles: Int): JsonObject = buildJsonObject {
    put("unitId", "demo"); put("serial", serial); put("name", "Robbie")
    put("isOnline", true); put("unitPowerStatus", "ON"); put("robotStatus", "ROBOT_IDLE"); put("displayCode", "DC_MODE_IDLE")
    put("DFILevelPercent", 58); put("isDFIFull", false); put("litterLevelState", "OPTIMAL")
    put("isKeypadLockout", false); put("nightLightMode", "AUTO"); put("nightLightBrightness", 50); put("panelBrightnessHigh", 25)
    put("cleanCycleWaitTime", 7); put("scoopsSavedCount", cycles); put("odometerCleanCycles", cycles)
    put("hopperStatus", "ENABLED"); put("isHopperRemoved", false); put("wifiRssi", -55); put("surfaceType", "TILE")
    put("setupDateTime", setup.toString()); put("lastSeen", Instant.ofEpochMilli(now).toString()); put("sleepStatus", "WAKE")
    put("espFirmware", "1.1.84"); put("picFirmwareVersion", "10500.3072.2.93"); put("laserBoardFirmwareVersion", "5.0.2.1")
    putJsonObject("weekdaySleepModeEnabled") {
        listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").forEach { d ->
            putJsonObject(d) { put("sleepTime", 0); put("wakeTime", 0); put("isEnabled", false) }
        }
    }
}

private fun demoPet(c: DemoCat, i: Int, last: Reading?): JsonObject = buildJsonObject {
    put("petId", c.id); put("name", c.name); put("type", "CAT")
    put("s3ImageURL", "asset://demo/${c.name.lowercase()}.jpg") // AI-generated cats (thesecatsdonotexist.com), bundled
    put("gender", if (i == 0) "MALE" else "FEMALE"); put("isFixed", true)
    put("weight", c.lbs); last?.let { put("lastWeightReading", it.lbs) }
    put("birthday", if (i == 0) "2022-05-14" else "2023-03-02"); put("adoptionDate", if (i == 0) "2022-08-01" else "2023-06-10")
    putJsonArray("breeds") { add(kotlinx.serialization.json.JsonPrimitive(if (i == 0) "BRITISH_SHORTHAIR" else "DOMESTIC_SHORTHAIR")) }
    put("diet", if (i == 0) "DRY_FOOD" else "BOTH"); put("environmentType", "INDOOR")
    putJsonArray("healthConcerns") {}
}

