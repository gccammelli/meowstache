package io.github.gccammelli.meowstache

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

const val LBS_TO_KG = 0.45359237

/** One SmartScale reading. Each reading is also one recorded pet visit. */
@Serializable
data class Reading(val ts: Long, val lbs: Double)

/** One raw Litter-Robot 4 activity row. */
@Serializable
data class Act(val ts: Long, val value: String, val actionValue: String? = null, val valueString: String? = null, val stateString: String? = null)

data class Robot(val raw: JsonObject) {
    val id get() = raw.s("unitId").orEmpty()
    val serial get() = raw.s("serial").orEmpty()
    val name get() = raw.s("name") ?: "Litter-Robot"
    /** "Litter-Robot 4" from the cloud; local mode reports whatever model the ESPHome firmware runs on. */
    val model get() = raw.s("model")?.replace("Litter Robot", "Litter-Robot") ?: "Litter-Robot 4"
    val isOnline get() = raw.b("isOnline")
    val isOn get() = raw.s("unitPowerStatus") == "ON"
    val wastePercent get() = raw.d("DFILevelPercent")?.toInt() ?: 0
    val isDrawerFull get() = raw.b("isDFIFull")
    val litterState get() = raw.s("litterLevelState")
    val panelLocked get() = raw.b("isKeypadLockout")
    val nightLightMode get() = raw.s("nightLightMode") ?: "OFF"
    val waitMinutes get() = raw.i("cleanCycleWaitTime") ?: 7
    val scoopsSaved get() = raw.i("scoopsSavedCount") ?: 0
    val hopperStatus get() = raw.s("hopperStatus")
    val hopperRemoved get() = raw.b("isHopperRemoved")
    val wifiRssi get() = raw.i("wifiRssi")
    val surface get() = raw.s("surfaceType")
    val setup: Long? get() = raw.s("setupDateTime")?.let(::parseTs)
    val lastSeen: Long? get() = raw.s("lastSeen")?.let(::parseTs)
    val sleepEnabled: Boolean
        get() = raw.o("weekdaySleepModeEnabled")?.values?.any { (it as? JsonObject)?.b("isEnabled") == true } == true
    /** The official app's compact version, e.g. ESP 1.1.84 + TOF 5.0.2.1 + PIC 10500.3072.2.93 → "1184.5021.293". */
    val firmware: String
        get() = raw.s("haFirmware") ?: listOf(
            raw.s("espFirmware")?.replace(".", ""),
            raw.s("laserBoardFirmwareVersion")?.replace(".", ""),
            raw.s("picFirmwareVersion")?.split(".")?.takeLast(2)?.joinToString(""),
        ).joinToString(".") { it ?: "?" }

    /** Official-app-style status label and dot colour (as ARGB). */
    val status: Pair<String, Long>
        get() {
            val blue = 0xFF2E9BF0; val yellow = 0xFFFBC02D; val red = 0xFFE5534B; val grey = 0xFF9E9E9E
            if (!isOnline) return "Offline" to grey
            if (!isOn) return "Power Off" to grey
            when (raw.s("robotCycleState")) {
                "CYCLE_STATE_CAT_DETECT" -> return "Cat Sensor Interrupted" to yellow
                "CYCLE_STATE_PAUSE" -> return "Paused" to yellow
            }
            return when (raw.s("robotStatus")) {
                "ROBOT_IDLE" -> when {
                    raw.s("displayCode") == "DC_CAT_DETECT" -> "Cat Detected" to yellow
                    isDrawerFull -> "Drawer Full" to red
                    else -> "Ready" to blue
                }
                "ROBOT_CAT_DETECT" -> "Cat Detected" to yellow
                "ROBOT_CAT_DETECT_DELAY" -> "Cat Sensor Timing" to yellow
                "ROBOT_CLEAN", "ROBOT_FIND_DUMP" -> "Cycling" to yellow
                "ROBOT_EMPTY" -> "Empty Cycle" to yellow
                "ROBOT_BONNET" -> "Bonnet Removed" to red
                "ROBOT_POWER_DOWN" -> "Powering Down" to grey
                "ROBOT_POWER_OFF" -> "Power Off" to grey
                "ROBOT_POWER_UP" -> "Powering Up" to yellow
                else -> (raw.s("robotStatus") ?: "Unknown") to grey
            }
        }

    val litterLabel
        get() = when (litterState) {
            "OPTIMAL" -> "Optimal"; "OVERFILL" -> "Overfilled"; "REFILL" -> "Refill"
            "LOW" -> "Low"; "EMPTY" -> "Empty"; else -> "—"
        }
}

data class Pet(val raw: JsonObject) {
    val id get() = raw.s("petId").orEmpty()
    val name get() = raw.s("name").orEmpty()
    val gender get() = raw.s("gender")
    val isFixed get() = raw.b("isFixed")
    val referenceLbs get() = raw.d("weight")
    val lastLbs get() = raw.d("lastWeightReading")
    val image get() = raw.s("s3ImageURL")
    val birthday: LocalDate? get() = raw.s("birthday")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    val adoption: LocalDate? get() = raw.s("adoptionDate")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    val breed: String
        get() {
            val b = raw.strings("breeds")
            return when {
                b.isEmpty() -> "Unknown"
                b.size > 1 -> "Mixed-breed"
                else -> b[0].replace("_", " ").lowercase().split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            }
        }
    val environment
        get() = when (raw.s("environmentType")) { "INDOOR" -> "Indoor"; "OUTDOOR" -> "Outdoor"; "BOTH" -> "Indoor/Outdoor"; else -> null }
    val genderLabel get() = when (gender) { "MALE" -> "Male"; "FEMALE" -> "Female"; else -> "" }
    val fixedLabel
        get() = when (gender) {
            "MALE" -> if (isFixed) "Neutered Male" else "Male"
            "FEMALE" -> if (isFixed) "Spayed Female" else "Female"
            else -> "—"
        }
    val diet get() = when (raw.s("diet")) { "WET_FOOD" -> "Wet Food"; "DRY_FOOD" -> "Dry Food"; "BOTH" -> "Wet & Dry Food"; else -> null }
    val health: String
        get() {
            val c = raw.strings("healthConcerns")
            return if (c.isEmpty()) "Healthy - No Issues" else c.joinToString { it.replace("_", " ").lowercase().replaceFirstChar(Char::uppercase) }
        }
    val embeddedWeights: List<Reading>
        get() = raw.a("weightHistory").mapNotNull { r ->
            val ts = r.s("timestamp")?.let(::parseTs) ?: return@mapNotNull null
            Reading(ts, r.d("weight") ?: return@mapNotNull null)
        }
}

/** Parses the assorted timestamp formats the APIs return; zone-less values are UTC. Returns epoch millis. */
fun parseTs(s: String): Long? {
    val t = s.trim()
    t.toLongOrNull()?.let { return if (it < 10_000_000_000) it * 1000 else it }
    runCatching { return Instant.parse(t).toEpochMilliseconds() } // ISO with Z or an offset
    runCatching { return LocalDateTime.parse(t.replace(' ', 'T')).toInstant(TimeZone.UTC).toEpochMilliseconds() } // zone-less, optional fraction
    return null
}

const val LR4_MODEL = """{
    unitId name serial userId espFirmware picFirmwareVersion laserBoardFirmwareVersion wifiRssi
    unitPowerType catWeight displayCode unitTimezone cleanCycleWaitTime isKeypadLockout
    nightLightMode nightLightBrightness panelBrightnessHigh
    weekdaySleepModeEnabled {
        Sunday { sleepTime wakeTime isEnabled } Monday { sleepTime wakeTime isEnabled }
        Tuesday { sleepTime wakeTime isEnabled } Wednesday { sleepTime wakeTime isEnabled }
        Thursday { sleepTime wakeTime isEnabled } Friday { sleepTime wakeTime isEnabled }
        Saturday { sleepTime wakeTime isEnabled }
    }
    unitPowerStatus sleepStatus robotStatus globeMotorFaultStatus catDetect isBonnetRemoved
    odometerCleanCycles DFINumberOfCycles DFILevelPercent isDFIFull robotCycleStatus robotCycleState
    isOnline isOnboarded lastSeen setupDateTime firmwareUpdateStatus surfaceType hopperStatus
    scoopsSavedCount isHopperRemoved litterLevelPercentage litterLevelState
}"""

const val PET_MODEL = """{
    petId userId createdAt name type gender weight weightLastUpdated lastWeightReading breeds age
    birthday adoptionDate s3ImageURL diet isFixed environmentType healthConcerns isHealthy isActive
    weightIdFeatureEnabled weightHistory { weight timestamp }
}"""
