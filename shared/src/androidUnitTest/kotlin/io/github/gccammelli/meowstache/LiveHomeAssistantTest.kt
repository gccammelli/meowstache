package io.github.gccammelli.meowstache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
        val snap = ha.load(nowMs() - 86_400_000L)
        snap.robots.forEach { println("robot ${it.name} ${it.status.first} drawer ${it.wastePercent}% litter ${it.litterLabel} delay ${it.waitMinutes} fw ${it.firmware}") }
        snap.pets.forEach { p -> println("pet ${p.name} ${p.referenceLbs} lb, ${snap.weights[p.id].orEmpty().size} weighings: ${snap.weights[p.id].orEmpty().takeLast(3)}") }
        snap.acts.forEach { (id, a) -> println("acts $id: ${a.groupingBy { it.value }.eachCount()}") }
        assertTrue(snap.robots.size == 2)
        assertTrue(snap.pets.map { it.name }.containsAll(listOf("Mochi", "Luna", "Pixel")))

        // A command goes through Home Assistant to the mock firmware and back.
        val robbie = snap.robots.first { it.name == "Robbie" }
        val target = if (robbie.nightLightMode == "OFF") "nightLightModeAuto" else "nightLightModeOff"
        ha.command(robbie, target, null)
        Thread.sleep(1500) // real time: runTest skips delays
        val after = ha.load(nowMs() - 60_000L).robots.first { it.name == "Robbie" }
        println("night light ${robbie.nightLightMode} → ${after.nightLightMode}")
        assertTrue(after.nightLightMode != robbie.nightLightMode)
    }
}
