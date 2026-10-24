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

    @Test fun significantYesterdayPatternsProduceNamedAlertsAndCanBeDisabled() {
        val pet=Pet(buildJsonObject { put("petId","cat"); put("name","Mochi") })
        val yesterday=kotlinx.datetime.LocalDate.now().minusDays(1)
        val readings=(0..28).flatMap { days ->
            val noon=yesterday.minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()+12*3_600_000
            List(if(days==0) 8 else 2) { i -> Reading(noon+i*60_000,if(days==0) 12.0 else 10.0) }
        }
        val state=AppState(pets=listOf(pet),weights=mapOf(pet.id to readings))
        val settings=AlertSettings(patternsEnabled=true,recapEnabled=false,noVisitEnabled=false,tooManyEnabled=false)
        val alerts=allAlerts(state,settings)
        assertEquals(setOf("Mochi: unusual visits","Mochi: unusual weight"),alerts.map { it.title }.toSet())
        assertTrue(alerts.all { it.petId==pet.id && it.key.startsWith("pattern:cat:$yesterday:") })
        assertTrue(alerts.first { it.title.endsWith("visits") }.body.contains("4X from 2 to 8"))
        assertTrue(allAlerts(state,settings.copy(patternsEnabled=false)).isEmpty())
    }

    @Test fun visitSnapshotsPreserveUnknownWeightAndUnassignedPetWithoutInventingValues() {
        val now=nowMs()
        val robot=Robot(buildJsonObject { put("serial","r"); put("name","Kitchen") })
        val pet=Pet(buildJsonObject { put("petId","cat"); put("name","Mochi") })
        val state=AppState(robots=listOf(robot),pets=listOf(pet),weights=mapOf(pet.id to listOf(Reading(now,10.0))),
            acts=mapOf(robot.serial to listOf(Act(now,"catWeight","10.0"),Act(now-180_000,"catWeight","unknown"),Act(now-200_000,"catWeight"))))
        val original=Repo.state.value
        try {
            Repo.state.value=state
            val visits=robot.snapshot(state).visits
            assertEquals("Mochi",visits.first().pet); assertNotNull(visits.first().weight)
            assertNull(visits.last().pet); assertNull(visits.last().weight); assertNull(visits.last().minutes)
        } finally { Repo.state.value=original }
    }
}
