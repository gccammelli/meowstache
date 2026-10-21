package io.github.gccammelli.meowstache

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class AlertIntegrationTest {
    @Test fun emptyHomeProducesNoPetAlertsOrRecap() {
        assertTrue(allAlerts(AppState(), AlertSettings(recapEnabled = true, recapHour = 0, patternsEnabled = true)).isEmpty())
    }

    @Test fun optedInRecapIncludesEveryPetWithoutInventingPatterns() {
        val pets = listOf("Mochi", "Luna").map { name -> Pet(buildJsonObject { put("petId", name); put("name", name) }) }
        val state = AppState(pets = pets)
        val alerts = allAlerts(state, AlertSettings(recapEnabled = true, recapHour = 0, patternsEnabled = true))
        val recap = alerts.single { it.key.startsWith("recap:") }
        assertTrue(recap.body.contains("Mochi"))
        assertTrue(recap.body.contains("Luna"))
        assertFalse(alerts.any { it.key.startsWith("pattern:") })
    }

    @Test fun robotSnapshotOnlyUsesRecentWeighings() {
        val now = nowMs()
        val robot = Robot(buildJsonObject { put("serial", "robot"); put("name", "Kitchen"); put("isOnline", true); put("cleanCycleWaitTime", 7) })
        val acts = listOf(Act(now - 1000, "catWeight", "10"), Act(now - 7 * 3600000, "catWeight", "9"), Act(now, "cleanCycleComplete"))
        val snapshot = robot.snapshot(AppState(robots = listOf(robot), acts = mapOf("robot" to acts)), "new-firmware")
        assertEquals("robot", snapshot.serial)
        assertEquals(1, snapshot.visits.size)
        assertEquals(now - 1000, snapshot.visits.single().ts)
    }
}
