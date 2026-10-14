package io.github.gccammelli.meowstache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Local mode against a real Home Assistant with the mock robots of tools/local-mock (opt in):
 *   MEOWSTACHE_HA_URL=http://localhost:8123 MEOWSTACHE_HA_TOKEN=… ./gradlew :shared:testDebugUnitTest --tests '*LiveHomeAssistant*'
 */
class LiveHomeAssistantTest {
    @Test
    fun readsTheMockRobots() = runTest(timeout = 2.minutes) {
        val url = System.getenv("MEOWSTACHE_HA_URL") ?: return@runTest
        val ha = LocalHa(HaHttp(url, System.getenv("MEOWSTACHE_HA_TOKEN")!!))
        val snap = ha.load(nowMs() - 365 * 86_400_000L) // as the app does on first connect
        snap.robots.forEach { println("robot ${it.name} ${it.status.first} drawer ${it.wastePercent}% litter ${it.litterLabel} delay ${it.waitMinutes} fw ${it.firmware}") }
        snap.pets.forEach { p -> println("pet ${p.name} ${p.referenceLbs} lb, ${snap.weights[p.id].orEmpty().size} weighings: ${snap.weights[p.id].orEmpty().takeLast(3)}") }
        snap.acts.forEach { (id, a) -> println("acts $id: ${a.groupingBy { it.value }.eachCount()}") }
        assertTrue(snap.robots.size == 2)
        assertTrue(snap.pets.map { it.name }.containsAll(listOf("Mochi", "Luna", "Pixel")))

        // The diagnostics export against a real Home Assistant.
        val diag = ha.diagnostics("live")
        println("diagnostics ${diag.length} bytes, version " + Regex("\"haVersion\":\"([^\"]+)").find(diag)?.groupValues?.get(1))
        assertTrue("\"original_name\":\"Cat 1 Weight\"" in diag && System.getenv("MEOWSTACHE_HA_TOKEN")!! !in diag)
        snap.robots.forEach { println("hidden ${it.name}: ${it.haHidden()} sleep override ${it.has("switch.sleep_override")}") }

        // A command goes through Home Assistant to the mock firmware and back.
        val robbie = snap.robots.first { it.name == "Robbie" }
        val target = if (robbie.nightLightMode == "OFF") "nightLightModeAuto" else "nightLightModeOff"
        ha.command(robbie, target, null)
        Thread.sleep(1500) // real time: runTest skips delays
        val after = ha.load(nowMs() - 60_000L).robots.first { it.name == "Robbie" }
        println("night light ${robbie.nightLightMode} → ${after.nightLightMode}")
        assertTrue(after.nightLightMode != robbie.nightLightMode)

        // Sleep schedule, firmware switch, robot rename (and back).
        ha.setSleep(robbie, "Saturday", true, 23 * 60 + 15, 7 * 60)
        ha.call(robbie, "switch.auto_cycle", "turn_off")
        ha.renameRobot(robbie, "Robbie Two")
        Thread.sleep(1500)
        var r2 = ha.load(nowMs() - 60_000L).robots.first { it.serial == robbie.serial }
        val sat = r2.raw.o("weekdaySleepModeEnabled")!!.o("Saturday")!!
        println("saturday ${sat}, auto cycle ${r2.haState("switch.auto_cycle")}, name ${r2.name}")
        assertTrue(sat.b("isEnabled") && sat.i("sleepTime") == 1395 && sat.i("wakeTime") == 420)
        assertTrue(r2.haState("switch.auto_cycle") == "off" && r2.name == "Robbie Two")
        ha.call(r2, "switch.auto_cycle", "turn_on"); ha.renameRobot(r2, "Robbie")

        // Add a cat on a slot that is disabled, as the firmware ships it: enabled, named, reloaded, weighed.
        val reg = (ha.backend.ws(listOf(buildJsonObject { put("type", "config/entity_registry/list") })).first() as kotlinx.serialization.json.JsonArray)
            .map { it as kotlinx.serialization.json.JsonObject }.filter { it.s("device_id") == robbie.serial }.mapNotNull { it.s("entity_id") }
        val slot3 = reg.filter { "cat_3_" in it }
        reg.filter { it.startsWith("button.") && ("cat_3_tracker_reset" in it || "cat_4_tracker_reset" in it) }.forEach { // leftovers of an earlier run
            runCatching { ha.backend.post("/api/services/button/press", "{\"entity_id\": \"$it\"}") }
        }
        ha.backend.ws(slot3.map { buildJsonObject { put("type", "config/entity_registry/update"); put("entity_id", it); put("disabled_by", "user") } })
        ha.backend.post("/api/config/config_entries/entry/${robbie.raw.s("haEntry")}/reload", "{}")
        Thread.sleep(5000)
        // addPet waits for the reload with delay(); run it in real time (runTest would skip the waits)
        println("full: " + kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ha.addPet("Ziggy", 7.7, setOf(robbie.serial)) })
        val ziggy = ha.load(nowMs() - 60_000L).pets.firstOrNull { it.name == "Ziggy" }
        println("ziggy ${ziggy?.referenceLbs} slots ${ziggy?.haSlots()?.map { it.second }}")
        assertTrue(kotlin.math.abs((ziggy?.referenceLbs ?: 0.0) - 7.7) < 0.01) // the firmware keeps a float
        ha.renamePet(ziggy!!, "Zig")
        Thread.sleep(1500)
        val zig = ha.load(nowMs() - 60_000L).pets.first { it.name == "Zig" }
        ha.resetTracker(zig) // free the slot again
        Thread.sleep(1500)
        assertTrue(ha.load(nowMs() - 60_000L).pets.none { it.name == "Zig" })
    }

    /** A regular (non-admin) Home Assistant user: reading and controls work, registry edits say they need an admin. */
    @Test
    fun nonAdminUser() = runTest(timeout = 2.minutes) {
        val url = System.getenv("MEOWSTACHE_HA_URL") ?: return@runTest
        val token = System.getenv("MEOWSTACHE_HA_USER_TOKEN") ?: return@runTest
        val ha = LocalHa(HaHttp(url, token))
        val snap = ha.load(nowMs() - 86_400_000L)
        println("non-admin: robots ${snap.robots.map { it.name }}, pets ${snap.pets.map { it.name }}")
        assertTrue(snap.robots.size == 2 && snap.pets.isNotEmpty())
        val robbie = snap.robots.first { it.name == "Robbie" }
        ha.command(robbie, "nightLightModeAuto", null)
        val e = runCatching { ha.renameRobot(robbie, "Nope") }.exceptionOrNull()
        println("non-admin rename: ${e?.message}")
        assertTrue(e?.message?.contains("administrator") == true)
    }
}
