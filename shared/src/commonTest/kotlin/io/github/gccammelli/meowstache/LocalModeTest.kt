package io.github.gccammelli.meowstache

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalModeTest {
    private val day = 86_400_000L

    /** Entity ids as a real Home Assistant named the mock firmware's (second robot: "_2"). */
    private val robot2 = buildJsonObject {
        put("id", "dev2"); put("name", "Bobbie"); put("model", "Litter Robot 4"); put("sw", "2026.10.0 (ESPHome 2026.9.1)")
        put("entities", JsonArray(listOf(
            "sensor.litter_robot_4_status_2", "sensor.litter_robot_4_hopper_status_2", "sensor.litter_robot_4_last_cat_weight_2",
            "switch.litter_robot_4_hopper_2", "switch.litter_robot_4_power_2", "sensor.litter_robot_4_power_type_2",
            "button.litter_robot_4_reset_2", "button.litter_robot_4_cat_1_tracker_reset_2", "number.litter_robot_4_set_cat_1_weight_2",
            "sensor.litter_robot_4_cat_1_weight_2", "sensor.litter_robot_4_cat_1_visits_2", "switch.litter_robot_4_monday_sleep_schedule_2",
        ).map(::JsonPrimitive)))
    }

    @Test
    fun entitiesGetTheirRoles() {
        val r = haRobot(robot2)
        assertEquals("sensor.litter_robot_4_status_2", r.roles["sensor.status"])
        assertEquals("sensor.litter_robot_4_hopper_status_2", r.roles["sensor.hopper_status"])
        assertEquals("switch.litter_robot_4_hopper_2", r.roles["switch.hopper"])
        assertEquals("button.litter_robot_4_reset_2", r.roles["button.reset"]) // not the cat tracker's reset
        assertEquals("switch.litter_robot_4_monday_sleep_schedule_2", r.roles["switch.monday_sleep_schedule"])
        assertEquals(mapOf(1 to ("sensor.litter_robot_4_cat_1_weight_2" to "sensor.litter_robot_4_cat_1_visits_2")), r.cats)
    }

    @Test
    fun catNamesComeFromTheRenamedEntities() {
        assertEquals("Mochi", haCatName("Mochi Weight", "Robbie", 1))
        assertEquals("Cat 3", haCatName("Robbie Cat 3 Weight", "Robbie", 3))
        assertEquals("Cat 2", haCatName(null, "Robbie", 2))
        assertEquals("ha-mr-whiskers", haPetId("Mr. Whiskers"))
    }

    @Test
    fun historyBecomesActivityAndReadings() {
        val t = 1_700_000_000_000L
        val status = listOf(HaPoint(t, "Ready"), HaPoint(t + 1000, "Cat detected"), HaPoint(t + 60_000, "Waiting to cycle"),
            HaPoint(t + 480_000, "Clean cycle in progress"), HaPoint(t + 630_000, "Ready"), HaPoint(t + 700_000, "Ready"))
        val weight = listOf(HaPoint(t - day, "11.2"), HaPoint(t + 40_000, "5.2"), HaPoint(t + 50_000, "unavailable"), HaPoint(t + 90_000, "5.2"))
        val acts = haActs(status, weight, listOf(HaPoint(t, "off"), HaPoint(t + 640_000, "on")), "kg")
        assertEquals(listOf("DFIFullFlagOn", "robotCycleStatusIdle", "robotCycleStatusDump", "catWeight", "robotStatusCatDetect"), acts.map { it.value })
        assertEquals("11.46", acts.single { it.value == "catWeight" }.actionValue) // kg → lb, the gap isn't a new weighing

        // Visits counter: +1 is a visit (weighed just before), a reset to 0 or an outage isn't.
        val visits = listOf(HaPoint(t - day, "4"), HaPoint(t + 40_100, "5"), HaPoint(t + 50_000, "unavailable"), HaPoint(t + 90_000, "5"), HaPoint(t + 2 * day, "0"))
        assertEquals(listOf(Reading(t + 40_000, 11.46)), haCatReadings(visits, weight, "kg"))
    }

    @Test
    fun commandsBecomeServiceCalls() {
        val r = Robot(haRobotJson(haRobot(robot2), emptyMap()))
        val (domain, service, data) = haCommand(r, "powerOff", null)
        assertEquals(Triple("switch", "turn_off", "switch.litter_robot_4_power_2"), Triple(domain, service, data.s("entity_id")))
        assertEquals("switch.litter_robot_4_hopper_2", haCommand(r, "hopperOn", null).third.s("entity_id"))
        assertFailsWith<ApiException> { haCommand(r, "keyPadLockOutOn", null) } // this robot has no lockout switch
        assertFailsWith<ApiException> { haCommand(r, "factoryReset", null) }
    }

    @Test
    fun demoHomeAssistantRoundTrip() = runTest {
        val now = 1_790_000_000_000L
        val zone = ZoneId.of("UTC")
        val demo = demoState(now, zone)
        val ha = LocalHa(FakeHomeAssistant(now, zone))
        val snap = ha.load(now - 500 * day)

        assertEquals(listOf("Robbie", "Bobbie"), snap.robots.map { it.name })
        assertEquals(listOf("Mochi", "Luna"), snap.pets.map { it.name }) // Luna is on both robots: one pet
        // Every demo weighing comes back, attributed by the firmware's cat tracker to the right cat.
        assertEquals(demo.weights.getValue("demo-mochi").size, snap.weights.getValue("ha-mochi").size)
        assertEquals(demo.weights.getValue("demo-luna").size, snap.weights.getValue("ha-luna").size)
        assertEquals(demo.weights.values.sumOf { it.size }, snap.acts.values.sumOf { a -> a.count { it.value == "catWeight" } })
        assertTrue(snap.acts.values.all { a -> a.any { it.value == "catWeight" } }) // both robots have visits

        val robbie = snap.robots.first()
        assertEquals("Ready" to 0xFF2E9BF0, robbie.status)
        assertEquals(7, robbie.waitMinutes)
        assertTrue(robbie.sleepEnabled)
        assertEquals("2026.10.0", robbie.firmware)

        // Visit durations come out of the rebuilt activity like the cloud's.
        val st = AppState(robots = snap.robots, pets = snap.pets, weights = snap.weights, acts = snap.acts)
        val d = st.durations("ha-mochi")
        assertTrue(d.size > snap.weights.getValue("ha-mochi").size * 9 / 10)
        val odd = { m: Map<Long, Double> -> m.values.count { it !in 3.0..7.5 } } // the demo data has a few overlapping visits
        assertTrue(odd(d) <= odd(demo.durations("demo-mochi")))
    }

    @Test
    fun demoCommandsChangeTheRobot() = runTest {
        val ha = LocalHa(FakeHomeAssistant())
        val robbie = ha.load(nowMs() - day).robots.first()
        assertEquals("AUTO", robbie.nightLightMode)
        ha.command(robbie, "nightLightModeOff", null)
        ha.command(robbie, "setClumpTime", "{\"clumpTime\": 15}")
        ha.command(robbie, "cleanCycle", null)
        val after = ha.load(nowMs() - day).robots.first()
        assertEquals("OFF", after.nightLightMode)
        assertEquals(15, after.waitMinutes)
        assertEquals("Cycling", after.status.first)
    }
}
