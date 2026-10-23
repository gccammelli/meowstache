package io.github.gccammelli.meowstache

import kotlinx.serialization.json.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HomeAssistantBoundaryTest {
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun robot(values: Map<String, String?>): JsonObject {
        val registry = HaRobot("id", "Robot", "Litter Robot 4", null, null, values.keys.associateWith { it }, emptyMap())
        return haRobotJson(registry, values.filterValues { it != null }.mapValues { buildJsonObject { put("state", it.value) } })
    }

    @Test fun everySupportedControlMapsToItsOwnServiceAndValue() = runTest {
        val r=LocalHa(FakeHomeAssistant()).load(nowMs()-86_400_000).robots.first()
        val controls=listOf(
            Triple("shortResetPress","button.reset","press"),Triple("powerOn","switch.power","turn_on"),
            Triple("powerOff","switch.power","turn_off"),Triple("keyPadLockOutOn","switch.control_panel_lockout","turn_on"),
            Triple("keyPadLockOutOff","switch.control_panel_lockout","turn_off"),Triple("replaceFilter","button.replace_filter","press"),
            Triple("hopperOn","switch.hopper","turn_on"),Triple("hopperOff","switch.hopper","turn_off"))
        for ((command,role,service) in controls) {
            val call=haCommand(r,command,null)
            assertEquals(role.substringBefore('.'),call.first); assertEquals(service,call.second)
            assertEquals(r.raw.o("haEntities")!!.s(role),call.third.s("entity_id"))
        }
        for ((command,option) in listOf("nightLightModeOn" to "On","nightLightModeOff" to "Off","nightLightModeAuto" to "Auto",
            "panelBrightnessLow" to "Low","panelBrightnessMed" to "Medium","panelBrightnessHigh" to "High")) {
            val call=haCommand(r,command,null)
            assertEquals("select" to "select_option",call.first to call.second); assertEquals(option,call.third.s("option"))
        }
        for ((power,level) in listOf(25 to "Low",26 to "Medium",50 to "Medium",51 to "High")) {
            assertEquals(level,haCommand(r,"setNightLightValue","""{"nightLightPower":$power}""").third.s("option"))
        }
        assertEquals("15",haCommand(r,"setClumpTime","""{"clumpTime":15}""").third.s("value"))
        assertFailsWith<ApiException> { haCommand(r,"setClumpTime",null) }
        val legacy=Robot(robot(mapOf("switch.night_light" to "off")))
        assertEquals("turn_on",haCommand(legacy,"nightLightModeOn",null).second)
    }

    @Test fun statusMappingKeepsUnknownAndFaultStatesExplicit() {
        val statuses = listOf("Ready" to "ROBOT_IDLE", "Sleeping" to "ROBOT_IDLE", "Cat detected" to "ROBOT_CAT_DETECT",
            "Waiting to cycle" to "ROBOT_CAT_DETECT_DELAY", "Clean cycle in progress" to "ROBOT_CLEAN", "Cycle interrupted" to "ROBOT_CLEAN",
            "Cycle paused" to "ROBOT_CLEAN", "Emptying litter" to "ROBOT_EMPTY", "Bonnet removed" to "ROBOT_BONNET",
            "Powered off" to "ROBOT_POWER_OFF", "Powering on" to "ROBOT_POWER_UP", "Powering off" to "ROBOT_POWER_DOWN",
            "On battery" to "On battery", "unknown" to "unknown", "unavailable" to "unavailable", null to null)
        for ((status, expected) in statuses) {
            val mapped = robot(mapOf("sensor.status" to status))
            assertEquals(expected, mapped.s("robotStatus"))
            assertEquals(status != null && status !in setOf("unknown", "unavailable"), mapped.b("isOnline"))
        }
        assertEquals("Fault detected", robot(mapOf("sensor.status" to "Fault detected")).s("robotStatus"))
        assertEquals("Fault detected", robot(mapOf("sensor.status" to "Fault detected", "sensor.fault" to "None")).s("robotStatus"))
        assertEquals("Pinch detected", robot(mapOf("sensor.status" to "Fault detected", "sensor.fault" to "Pinch detected")).s("robotStatus"))
        assertEquals("SLEEPING", robot(mapOf("sensor.status" to "Sleeping")).s("sleepStatus"))
        assertEquals("CYCLE_STATE_CAT_DETECT", robot(mapOf("sensor.status" to "Cycle interrupted")).s("robotCycleState"))
        assertEquals("CYCLE_STATE_PAUSE", robot(mapOf("sensor.status" to "Cycle paused")).s("robotCycleState"))
    }

    @Test fun litterBrightnessAndLegacyNightLightUseTheirDefinedThresholds() {
        for ((level, expected) in listOf("0" to "EMPTY", "39" to "EMPTY", "40" to "LOW", "69" to "LOW", "70" to "OPTIMAL")) {
            assertEquals(expected, robot(mapOf("sensor.litter_level" to level)).s("litterLevelState"))
        }
        assertFalse(robot(mapOf("sensor.litter_level" to "unknown")).containsKey("litterLevelPercentage"))
        for ((level, expected) in listOf("Low" to 25, "Medium" to 50, "High" to 100, "unknown" to null)) {
            val r = robot(mapOf("select.night_light_brightness" to level, "select.panel_brightness" to level))
            assertEquals(expected?.toString(), r.s("nightLightBrightness")); assertEquals(expected?.toString(), r.s("panelBrightnessHigh"))
        }
        assertEquals("ON", robot(mapOf("switch.night_light" to "on")).s("nightLightMode"))
        assertEquals("OFF", robot(mapOf("switch.night_light" to "off")).s("nightLightMode"))
        assertEquals("AUTO", robot(mapOf("switch.night_light" to "on", "select.night_light_mode" to "Auto")).s("nightLightMode"))
        assertEquals("OFF", robot(mapOf("switch.power" to "off")).s("unitPowerStatus"))
        assertEquals("ON", robot(emptyMap()).s("unitPowerStatus"))
    }

    @Test fun hopperStatusUsesFirmwareStateAndFallbackSwitchWithoutInventingAHopper() {
        for (status in listOf<String?>(null, "Not connected")) assertFalse(robot(mapOf("sensor.hopper_status" to status)).containsKey("hopperStatus"))
        assertEquals("DISABLED", robot(mapOf("sensor.hopper_status" to "Disabled")).s("hopperStatus"))
        assertTrue(robot(mapOf("sensor.hopper_status" to "Disabled")).b("isHopperRemoved"))
        assertEquals("EMPTY", robot(mapOf("sensor.hopper_status" to "Needs refill")).s("hopperStatus"))
        assertEquals("ENABLED", robot(mapOf("sensor.hopper_status" to "Running")).s("hopperStatus"))
        assertEquals("EMPTY", robot(mapOf("sensor.hopper_status" to "Ready", "binary_sensor.hopper_empty" to "on")).s("hopperStatus"))
        for (missing in listOf("unknown", "unavailable")) {
            assertEquals("ENABLED", robot(mapOf("sensor.hopper_status" to missing, "switch.hopper" to "on")).s("hopperStatus"))
            assertEquals("DISABLED", robot(mapOf("sensor.hopper_status" to missing, "switch.hopper" to "off")).s("hopperStatus"))
        }
    }

    @Test fun malformedSleepTimesAndMissingRegistryDataUseExplicitDefaults() {
        val empty = haRobot(obj("{}"))
        assertEquals("", empty.id); assertEquals("Litter-Robot", empty.name); assertEquals("Litter Robot", empty.model)
        assertTrue(empty.roles.isEmpty()); assertTrue(empty.cats.isEmpty())
        for ((time, minutes) in listOf("bad" to 0, "bad:bad" to 0, "01:02:00" to 62, "01" to 60)) {
            val r = robot(mapOf("time.monday_sleep_time" to time, "time.monday_wake_time" to time, "switch.monday_sleep_schedule" to "on"))
            val day = r.o("weekdaySleepModeEnabled")!!.o("Monday")!!
            assertEquals(minutes, day.i("sleepTime")); assertEquals(minutes, day.i("wakeTime")); assertTrue(day.b("isEnabled"))
        }
        assertEquals("Cat 1", haCatName(" Weight", "Robot", 1))
    }

    @Test fun disabledTrackerRowsPreserveNamesButNeverBecomeLiveWeightEntities() {
        val mapped = haRobot(obj("""{"id":"r","entities":[{},null,42,
            {"entity_id":"sensor.cat_weight","original_name":"Cat 1 Weight","name":"Named Weight","disabled_by":"user"},
            {"entity_id":"sensor.cat_visits","original_name":"Cat 1 Visits","disabled_by":"user"},
            {"entity_id":"number.cat_weight","original_name":"Cat 1 Weight"},
            {"entity_id":"button.cat_reset","original_name":"Cat 1 Tracker Reset"},
            {"entity_id":"sensor.status_old","original_name":"Status","disabled_by":"user"},
            {"entity_id":"sensor.status_live","original_name":"Status"},
            {"entity_id":"switch.unknown","original_name":"Unrelated"}]}"""))
        val cat = mapped.cats.getValue(1)
        assertNull(cat.weight); assertNull(cat.visits); assertEquals("Named Weight", cat.name)
        assertEquals(4, cat.entities.size)
        assertEquals("sensor.status_live", mapped.roles["sensor.status"])
        assertFalse("sensor.status" in mapped.hidden); assertTrue("sensor.cat_weight" in mapped.disabled)
    }

    @Test fun activityTransitionsDoNotDuplicateEventsAndWeightUnitsRemainConsistent() {
        val states = listOf("Ready", "Bonnet removed", "On battery", "Cycle interrupted", "Clean cycle in progress", "Sleeping", "Sleeping")
        val acts = haActs(states.mapIndexed { i, s -> HaPoint(i.toLong(), s) }, emptyList(), listOf(HaPoint(1, "on"), HaPoint(2, "off")), null)
        assertEquals(listOf("robotCycleStatusIdle", "robotCycleStatusDump", "robotCycleStateCatDetect", "powerTypeDC", "bonnetRemovedYes"), acts.map { it.value })
        for ((unit, raw) in listOf("lb" to 10.0, "oz" to 160.0, "g" to 4535.9237, "kg" to 4.5359237)) {
            val weights = listOf(HaPoint(0, "0"), HaPoint(1, "unknown"), HaPoint(2, raw.toString()), HaPoint(3, "0"))
            assertEquals("10.0", haActs(emptyList(), weights, emptyList(), unit).single().actionValue)
            assertEquals(listOf(Reading(2, 10.0)), haCatReadings(listOf(HaPoint(0, "0"), HaPoint(3, "1")), weights.dropLast(1), unit))
        }
        assertTrue(haCatReadings(listOf(HaPoint(0, "0"), HaPoint(1, "1")), emptyList(), null).isEmpty())
    }

    private class Backend : HaBackend {
        override val base = "https://home.example"
        var devices = "[]"
        var entities = "[]"
        var states = "[]"
        val paths = mutableListOf<String>()
        override suspend fun get(path: String): String {
            paths += path
            return when { path == "/api/states" -> states; path.startsWith("/api/history/") -> "[]"; else -> throw ApiException("Missing optional endpoint") }
        }
        override suspend fun post(path: String, body: String) = error("No service command expected")
        override suspend fun ws(commands: List<JsonObject>) = commands.map { command ->
            json.parseToJsonElement(when (command.s("type")) {
                "config/device_registry/list" -> devices
                "config/entity_registry/list" -> entities
                else -> error("No registry mutation expected")
            })
        }
    }

    @Test fun emptyRegistryDoesNotRequestHistoryOrExposeOtherIntegrations() = runTest {
        val backend = Backend()
        backend.devices = """[{"id":"other","model":"Other device"}, {"id":"missing-model"}, {"id":"disabled","model":"Litter Robot 4","disabled_by":"user"}]"""
        val result = LocalHa(backend).load(0)
        assertTrue(result.robots.isEmpty()); assertTrue(result.pets.isEmpty()); assertTrue(result.weights.isEmpty()); assertTrue(result.acts.isEmpty())
        assertEquals(listOf("/api/states"), backend.paths)
        val diagnostics = obj(LocalHa(backend).diagnostics("test"))
        assertNull(diagnostics.s("haVersion")); assertTrue(diagnostics.a("history").isEmpty())
    }

    @Test fun robotWithNoRecognizedLiveRolesRemainsOfflineAndCannotAllocateACat() = runTest {
        val backend = Backend()
        backend.devices = """[{"id":"r","model":"Litter Robot 4"}]"""
        backend.entities = """[{"device_id":"r","platform":"esphome","entity_id":"sensor.unrelated"}]"""
        val ha = LocalHa(backend)
        val result = ha.load(0)
        assertEquals("Litter-Robot", result.robots.single().name)
        assertFalse(result.robots.single().isOnline); assertTrue(result.pets.isEmpty())
        assertEquals(listOf("Litter-Robot"), ha.addPet("New cat", 10.0, setOf("r")))
        assertTrue(backend.paths.none { it.startsWith("/api/history/") })
    }

    @Test fun sharedCatKeepsItsKnownWeightAndRelativePhotoWhenAnotherRobotOnlyReportsVisits() = runTest {
        val backend = Backend()
        backend.devices = """[{"id":"r1","name_by_user":"First","model":"Litter Robot 4"},{"id":"r2","name":"Second","model":"Litter Robot 3"}]"""
        backend.entities = """[
            {"device_id":"r1","platform":"esphome","entity_id":"sensor.r1_weight","original_name":"Cat 1 Weight"},
            {"device_id":"r1","platform":"esphome","entity_id":"sensor.r1_visits","original_name":"Cat 1 Visits"},
            {"device_id":"r2","platform":"esphome","entity_id":"sensor.r2_weight","original_name":"Cat 1 Weight","name":"Shared Weight"},
            {"device_id":"r2","platform":"esphome","entity_id":"sensor.r2_visits","original_name":"Cat 1 Visits"},
            {"device_id":"r2","platform":"esphome","entity_id":"sensor.r2_unused","original_name":"Cat 2 Weight"}
        ]"""
        backend.states = """[
            {"entity_id":"sensor.r1_weight","state":"10","attributes":{"friendly_name":"Shared Weight","entity_picture":"/local/cat.jpg"}},
            {"entity_id":"sensor.r1_visits","state":"1"},
            {"entity_id":"sensor.r2_weight","state":"unknown"}, {"entity_id":"sensor.r2_visits","state":"2"},
            {"entity_id":"sensor.r2_unused","state":"0"}
        ]"""
        val result = LocalHa(backend).load(0)
        val cat = result.pets.single()
        assertEquals("Shared", cat.name); assertEquals(10.0, cat.referenceLbs)
        assertEquals("https://home.example/local/cat.jpg", cat.raw.s("s3ImageURL"))
        assertEquals(2, cat.haSlots().size); assertTrue(result.weights.getValue(cat.id).isEmpty())
    }
}
