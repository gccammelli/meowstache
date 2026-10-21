package io.github.gccammelli.meowstache

import kotlinx.serialization.json.*
import kotlin.test.*

class ModelCoverageTest {
    private fun robot(vararg fields: Pair<String, JsonElement>) = Robot(JsonObject(mapOf(
        "isOnline" to JsonPrimitive(true), "unitPowerStatus" to JsonPrimitive("ON"),
    ) + fields))

    @Test fun statusPreservesUnknownStatesAndHonoursPriority() {
        val cases = mapOf("ROBOT_IDLE" to "Ready", "ROBOT_CAT_DETECT" to "Cat Detected",
            "ROBOT_CAT_DETECT_DELAY" to "Cat Sensor Timing", "ROBOT_CLEAN" to "Cycling",
            "ROBOT_FIND_DUMP" to "Cycling", "ROBOT_EMPTY" to "Empty Cycle", "ROBOT_BONNET" to "Bonnet Removed",
            "ROBOT_POWER_DOWN" to "Powering Down", "ROBOT_POWER_OFF" to "Power Off", "ROBOT_POWER_UP" to "Powering Up",
            "NEW_FIRMWARE_STATE" to "NEW_FIRMWARE_STATE")
        cases.forEach { (state, label) -> assertEquals(label, robot("robotStatus" to JsonPrimitive(state)).status.first) }
        assertEquals("Unknown", robot().status.first)
        assertEquals("Offline", robot("isOnline" to JsonPrimitive(false), "robotStatus" to JsonPrimitive("ROBOT_CLEAN")).status.first)
        assertEquals("Power Off", robot("unitPowerStatus" to JsonPrimitive("OFF"), "robotCycleState" to JsonPrimitive("CYCLE_STATE_PAUSE")).status.first)
        assertEquals("Paused", robot("robotCycleState" to JsonPrimitive("CYCLE_STATE_PAUSE")).status.first)
        assertEquals("Cat Sensor Interrupted", robot("robotCycleState" to JsonPrimitive("CYCLE_STATE_CAT_DETECT")).status.first)
        assertEquals("Drawer Full", robot("robotStatus" to JsonPrimitive("ROBOT_IDLE"), "isDFIFull" to JsonPrimitive(true)).status.first)
        assertEquals("Cat Detected", robot("robotStatus" to JsonPrimitive("ROBOT_IDLE"), "displayCode" to JsonPrimitive("DC_CAT_DETECT"), "isDFIFull" to JsonPrimitive(true)).status.first)
    }

    @Test fun robotDetailsFormatFirmwareAndSleepSchedules() {
        val r = robot("espFirmware" to JsonPrimitive("1.1.84"), "laserBoardFirmwareVersion" to JsonPrimitive("5.0.2.1"), "picFirmwareVersion" to JsonPrimitive("10500.3072.2.93"),
            "model" to JsonPrimitive("Litter Robot 4"), "setupDateTime" to JsonPrimitive("2026-01-01T00:00:00Z"), "lastSeen" to JsonPrimitive("broken"),
            "weekdaySleepModeEnabled" to buildJsonObject { put("Monday", buildJsonObject { put("isEnabled", true) }); put("Tuesday", JsonNull) })
        assertEquals("1184.5021.293", r.firmware)
        assertEquals("Litter-Robot 4", r.model)
        assertTrue(r.sleepEnabled)
        assertNotNull(r.setup); assertNull(r.lastSeen)
        assertEquals("local-1", robot("haFirmware" to JsonPrimitive("local-1")).firmware)
        assertEquals("?.?.?", robot().firmware)
        assertFalse(robot().sleepEnabled)
        assertEquals(7, robot().waitMinutes)
        mapOf("OPTIMAL" to "Optimal", "OVERFILL" to "Overfilled", "REFILL" to "Refill", "LOW" to "Low", "EMPTY" to "Empty", "unknown" to "—").forEach { (code, label) ->
            assertEquals(label, robot("litterLevelState" to JsonPrimitive(code)).litterLabel)
        }
    }

    @Test fun petProfileHandlesMissingAndMalformedOptionalFields() {
        fun pet(vararg fields: Pair<String, JsonElement>) = Pet(JsonObject(fields.toMap()))
        val empty = pet()
        assertEquals("Unknown", empty.breed); assertEquals("—", empty.fixedLabel)
        assertEquals("", empty.genderLabel); assertNull(empty.environment); assertNull(empty.diet)
        assertEquals("Healthy - No Issues", empty.health)
        assertNull(pet("birthday" to JsonPrimitive("not-a-date")).birthday)
        assertNull(pet("adoptionDate" to JsonPrimitive("2026-02-30")).adoption)
        assertEquals("British Shorthair", pet("breeds" to JsonArray(listOf(JsonPrimitive("BRITISH_SHORTHAIR")))).breed)
        assertEquals("Mixed-breed", pet("breeds" to JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B")))).breed)
        for ((gender, label, fixed) in listOf(Triple("MALE", "Male", "Neutered Male"), Triple("FEMALE", "Female", "Spayed Female"))) {
            assertEquals(label, pet("gender" to JsonPrimitive(gender)).genderLabel)
            assertEquals(label, pet("gender" to JsonPrimitive(gender)).fixedLabel)
            assertEquals(fixed, pet("gender" to JsonPrimitive(gender), "isFixed" to JsonPrimitive(true)).fixedLabel)
        }
        mapOf("INDOOR" to "Indoor", "OUTDOOR" to "Outdoor", "BOTH" to "Indoor/Outdoor").forEach { (code, label) -> assertEquals(label, pet("environmentType" to JsonPrimitive(code)).environment) }
        mapOf("WET_FOOD" to "Wet Food", "DRY_FOOD" to "Dry Food", "BOTH" to "Wet & Dry Food").forEach { (code, label) -> assertEquals(label, pet("diet" to JsonPrimitive(code)).diet) }
        assertEquals("Kidney disease", pet("healthConcerns" to JsonArray(listOf(JsonPrimitive("KIDNEY_DISEASE")))).health)
        val p = pet("weightHistory" to json.parseToJsonElement("""[{"timestamp":"2026-01-01T00:00:00Z","weight":10},{"timestamp":"bad","weight":2},{"weight":3},{"timestamp":"2026-01-01T00:00:00Z"}]"""))
        assertEquals(listOf(Reading(1767225600000L, 10.0)), p.embeddedWeights)
    }

    @Test fun timestampsAcceptEveryApiFormatWithoutInventingInvalidDates() {
        val epoch = 1767225600000L
        for (s in listOf(" 1767225600 ", "1767225600000", "2026-01-01T00:00:00Z", "2026-01-01T01:00:00+01:00", "2026-01-01 00:00:00", "2026-01-01T00:00:00")) assertEquals(epoch, parseTs(s), s)
        assertEquals(epoch + 123, parseTs("2026-01-01 00:00:00.123"))
        for (s in listOf("", "nonsense", "2026-02-30T00:00:00Z")) assertNull(parseTs(s), s)
    }
}
