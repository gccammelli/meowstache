package io.github.gccammelli.meowstache

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/*
 * Local mode: Litter-Robots running the community ESPHome firmware
 * (codeberg.org/Joseph-DiGiovanni/esphome-litter-robot), read through the user's own Home Assistant.
 * Home Assistant's entities are turned into the same robot/pet/history shapes the cloud API gives,
 * so every screen works unchanged. Cats are the firmware's cat tracker slots, named by the user in
 * Home Assistant; a cat that uses several robots (same name) becomes one pet.
 */

const val ESPHOME_FIRMWARE_URL = "https://codeberg.org/Joseph-DiGiovanni/esphome-litter-robot"

/** Home Assistant's REST API: real HTTP, or [FakeHomeAssistant] for the local demo. */
interface HaBackend {
    /** Where relative picture paths ("/local/mochi.jpg") live. */
    val base: String
    suspend fun get(path: String): String
    suspend fun post(path: String, body: String): String
    /** Websocket commands in one session (registry edits need it; they also need an admin user's token). Returns each result. */
    suspend fun ws(commands: List<JsonObject>): List<JsonElement?>
}

class HaHttp(url: String, private val token: String) : HaBackend {
    override val base = url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
    private val http = HttpClient {
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
        install(WebSockets)
        expectSuccess = false
    }

    override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
        val out = mutableListOf<JsonElement?>()
        http.webSocket(base.replaceFirst("http", "ws") + "/api/websocket") {
            suspend fun next() = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            next() // auth_required
            send(Frame.Text(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString()))
            if (next().s("type") != "auth_ok") throw AuthException("Home Assistant didn't accept the access token")
            commands.forEachIndexed { i, c ->
                send(Frame.Text(JsonObject(c + ("id" to JsonPrimitive(i + 1))).toString()))
                var m: JsonObject
                do m = next() while (m.s("id") != "${i + 1}")
                if (m.s("success") != "true") throw ApiException(m.o("error")?.s("message")?.let {
                    if ("admin" in it.lowercase() || "unauthorized" in it.lowercase()) "This needs a Home Assistant administrator's token" else it
                } ?: "Home Assistant refused ${c.s("type")}")
                out += m["result"]
            }
        }
        return out
    }

    override suspend fun get(path: String) = check(http.get(base + path) { header("Authorization", "Bearer $token") })
    override suspend fun post(path: String, body: String) = check(http.post(base + path) {
        header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(body)
    })

    private suspend fun check(r: HttpResponse): String {
        val text = r.bodyAsText()
        when (r.status.value) {
            in 200..299 -> return text
            401, 403 -> throw AuthException("Home Assistant didn't accept the access token")
            else -> throw ApiException("Home Assistant: HTTP ${r.status.value} ${text.take(200)}")
        }
    }
}

/** Every Litter-Robot the ESPHome integration knows, with its entities (one call, via a template). */
private const val DEVICES_TEMPLATE = """{%- set ns = namespace(d=[]) -%}
{%- for dev in integration_entities('esphome') | map('device_id') | reject('none') | unique -%}
{%- if 'Litter Robot' in (device_attr(dev, 'model') or '') -%}
{%- set ns.d = ns.d + [{'id': dev, 'name': device_attr(dev, 'name_by_user') or device_attr(dev, 'name'), 'model': device_attr(dev, 'model'), 'sw': device_attr(dev, 'sw_version'), 'entry': device_attr(dev, 'config_entries') | list | first, 'entities': device_entities(dev)}] -%}
{%- endif -%}
{%- endfor -%}
{{ ns.d | tojson }}"""

private val DAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

/** The firmware's entities, keyed "domain.object_id" as Home Assistant names them from the firmware's entity names. */
private val ROLES = listOf(
    "sensor.status", "sensor.waste_drawer_level", "sensor.litter_level", "sensor.last_cat_weight", "sensor.clean_cycles",
    "sensor.hopper_status", "sensor.fault", "sensor.power_type", "sensor.wifi_signal",
    "binary_sensor.waste_drawer_full", "binary_sensor.sleeping", "binary_sensor.hopper_empty",
    "switch.power", "switch.control_panel_lockout", "switch.hopper",
    "select.night_light_mode", "select.night_light_brightness", "select.panel_brightness",
    "number.clean_cycle_delay", "button.manual_cycle", "button.reset", "button.replace_filter", "button.empty_litter",
    "switch.smart_cat_detection", "switch.auto_cycle", "switch.odor_control",
) + DAYS.flatMap { listOf("switch.${it}_sleep_schedule", "time.${it}_sleep_time", "time.${it}_wake_time") }

private val CAT = Regex("""^(sensor|number|button)\.(?:.*_)?cat_(\d+)_([a-z_]+)$""")

/** One cat tracker slot on a robot: its weight and visits sensors, and every entity of the slot (for renaming). */
data class HaCat(val weight: String? = null, val visits: String? = null, val entities: List<String> = emptyList()) {
    fun find(prefix: String, suffix: String) = entities.firstOrNull { it.startsWith(prefix) && it.replace(Regex("_\\d+$"), "").endsWith(suffix) }
}

/** A robot as found in Home Assistant: role → entity id, and cat slot number → its entities. */
data class HaRobot(val id: String, val name: String, val model: String, val sw: String?, val entry: String?,
                   val roles: Map<String, String>, val cats: Map<Int, HaCat>)

/**
 * Sorts a device's entities into roles. Home Assistant derives entity ids from the firmware's entity names
 * ("…_waste_drawer_level", "…_cat_1_weight", "_2" for a second robot); the longest matching role wins, so
 * "…_hopper_status" isn't taken for "…_status".
 * shortcut: entity ids the user renamed by hand aren't recognised; fall back to the entity registry's
 * original names (websocket API) if that turns out to be common.
 */
fun haRobot(device: JsonObject): HaRobot {
    val roles = HashMap<String, String>()
    val cats = HashMap<Int, HaCat>()
    for (e in (device["entities"] as? JsonArray).orEmpty().mapNotNull { it.str() }) {
        val key = e.replace(Regex("_\\d+$"), "")
        val m = CAT.find(key)
        if (m != null) {
            val (domain, n, what) = m.destructured
            val c = cats[n.toInt()] ?: HaCat()
            cats[n.toInt()] = c.copy(
                weight = if (domain == "sensor" && what == "weight") e else c.weight,
                visits = if (domain == "sensor" && what == "visits") e else c.visits,
                entities = c.entities + e,
            )
            continue
        }
        val domain = key.substringBefore('.')
        val obj = key.substringAfter('.')
        ROLES.filter { it.substringBefore('.') == domain && (obj == it.substringAfter('.') || obj.endsWith("_" + it.substringAfter('.'))) }
            .maxByOrNull { it.length }?.let { roles.getOrPut(it) { e } }
    }
    return HaRobot(device.s("id").orEmpty(), device.s("name") ?: "Litter-Robot", device.s("model") ?: "Litter Robot",
        device.s("sw"), device.s("entry"), roles, cats)
}

/** lb per unit, for weights Home Assistant may show converted. */
private fun toLbs(v: Double, unit: String?) = when (unit?.lowercase()) {
    "kg" -> v / LBS_TO_KG; "g" -> v / LBS_TO_KG / 1000; "oz" -> v / 16; else -> v
}

private fun JsonObject.state() = s("state")
private fun JsonObject.attr(k: String) = o("attributes")?.s(k)

/** The robot in the cloud API's shape (see [Robot]); "haEntities" keeps the role → entity map for commands. */
fun haRobotJson(r: HaRobot, states: Map<String, JsonObject>): JsonObject {
    fun st(role: String) = r.roles[role]?.let { states[it] }
    fun v(role: String) = st(role)?.state()
    fun on(role: String) = v(role) == "on"
    val status = v("sensor.status")
    val level = { o: String? -> when (o) { "Low" -> 25; "Medium" -> 50; "High" -> 100; else -> null } }
    return buildJsonObject {
        put("unitId", r.id); put("serial", r.id); put("name", r.name); put("model", r.model)
        put("haFirmware", r.sw?.substringBefore(" ("))
        put("isOnline", status != null && status != "unavailable" && status != "unknown")
        put("unitPowerStatus", if (r.roles["switch.power"] == null || on("switch.power")) "ON" else "OFF")
        when (status) {
            "Ready" -> put("robotStatus", "ROBOT_IDLE")
            "Sleeping" -> { put("robotStatus", "ROBOT_IDLE"); put("sleepStatus", "SLEEPING") }
            "Cat detected" -> put("robotStatus", "ROBOT_CAT_DETECT")
            "Waiting to cycle" -> put("robotStatus", "ROBOT_CAT_DETECT_DELAY")
            "Clean cycle in progress" -> put("robotStatus", "ROBOT_CLEAN")
            "Cycle interrupted" -> { put("robotStatus", "ROBOT_CLEAN"); put("robotCycleState", "CYCLE_STATE_CAT_DETECT") }
            "Emptying litter" -> put("robotStatus", "ROBOT_EMPTY")
            "Bonnet removed" -> put("robotStatus", "ROBOT_BONNET")
            "Powered off" -> put("robotStatus", "ROBOT_POWER_OFF")
            "Powering on" -> put("robotStatus", "ROBOT_POWER_UP")
            "Powering off" -> put("robotStatus", "ROBOT_POWER_DOWN")
            else -> put("robotStatus", status) // "Fault detected", "On battery", …: shown as is
        }
        v("sensor.waste_drawer_level")?.toDoubleOrNull()?.let { put("DFILevelPercent", it) }
        put("isDFIFull", on("binary_sensor.waste_drawer_full"))
        v("sensor.litter_level")?.toDoubleOrNull()?.let { l ->
            put("litterLevelPercentage", l)
            // shortcut: thresholds guessed from the official app's states; tune with real firmware readings.
            put("litterLevelState", when { l >= 70 -> "OPTIMAL"; l >= 40 -> "LOW"; else -> "EMPTY" })
        }
        put("isKeypadLockout", on("switch.control_panel_lockout"))
        v("select.night_light_mode")?.let { put("nightLightMode", it.uppercase()) }
        level(v("select.night_light_brightness"))?.let { put("nightLightBrightness", it) }
        level(v("select.panel_brightness"))?.let { put("panelBrightnessHigh", it) }
        v("number.clean_cycle_delay")?.toDoubleOrNull()?.let { put("cleanCycleWaitTime", it.toInt()) }
        v("sensor.clean_cycles")?.toDoubleOrNull()?.let { put("odometerCleanCycles", it.toInt()); put("scoopsSavedCount", it.toInt()) }
        if (r.roles["switch.hopper"] != null) {
            val enabled = on("switch.hopper")
            put("isHopperRemoved", !enabled)
            put("hopperStatus", when {
                !enabled -> "DISABLED"; on("binary_sensor.hopper_empty") -> "EMPTY"; else -> "ENABLED"
            })
        }
        v("sensor.wifi_signal")?.toDoubleOrNull()?.let { put("wifiRssi", it.toInt()) }
        st("sensor.status")?.s("last_updated")?.let { put("lastSeen", it) }
        putJsonObject("weekdaySleepModeEnabled") {
            DAYS.forEach { d ->
                val min = { role: String -> v(role)?.split(":")?.let { (it[0].toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: 0 }
                putJsonObject(d.replaceFirstChar(Char::uppercase)) {
                    put("sleepTime", min("time.${d}_sleep_time")); put("wakeTime", min("time.${d}_wake_time"))
                    put("isEnabled", on("switch.${d}_sleep_schedule"))
                }
            }
        }
        putJsonObject("haEntities") { r.roles.forEach { (k, e) -> put(k, e) } }
        putJsonObject("haState") { r.roles.forEach { (k, e) -> states[e]?.state()?.let { put(k, it) } } }
        r.entry?.let { put("haEntry", it) }
    }
}

/** A cat's name from its weight sensor: the user renames "Cat 1 Weight" to e.g. "Mochi Weight" (firmware User Guide). */
fun haCatName(friendly: String?, robot: String, slot: Int): String =
    (friendly ?: "Cat $slot Weight").removePrefix("$robot ").removeSuffix(" Weight").trim().ifEmpty { "Cat $slot" }

fun haPetId(name: String) = "ha-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-")

/** One point of an entity's history. */
data class HaPoint(val ts: Long, val state: String)

/**
 * A robot's activity in the cloud API's terms, rebuilt from Home Assistant's history:
 * status changes give cat detected / cycle started (dump) / cycle completed, the last cat weight gives
 * every weighing, the drawer-full sensor gives "drawer full".
 */
fun haActs(status: List<HaPoint>, lastWeight: List<HaPoint>, drawerFull: List<HaPoint>, weightUnit: String?): List<Act> {
    val out = mutableListOf<Act>()
    var prev: String? = null
    for (p in status) {
        if (p.state != prev) when (p.state) {
            "Cat detected" -> out += Act(p.ts, "robotStatusCatDetect")
            "Clean cycle in progress" -> out += Act(p.ts, "robotCycleStatusDump")
            "Ready", "Sleeping" -> if (prev == "Clean cycle in progress") out += Act(p.ts, "robotCycleStatusIdle")
            "Cycle interrupted" -> out += Act(p.ts, "robotCycleStateCatDetect")
            "Bonnet removed" -> out += Act(p.ts, "bonnetRemovedYes")
            "On battery" -> out += Act(p.ts, "powerTypeDC")
        }
        prev = p.state
    }
    numericChanges(lastWeight).forEach { (ts, v, _) ->
        val lbs = toLbs(v, weightUnit)
        if (lbs > 0) out += Act(ts, "catWeight", (kotlin.math.round(lbs * 100) / 100).toString())
    }
    drawerFull.zipWithNext().filter { (a, b) -> a.state == "off" && b.state == "on" }.forEach { (_, b) -> out += Act(b.ts, "DFIFullFlagOn") }
    return out.sortedByDescending { it.ts }
}

/** Numeric values that differ from the previous numeric one (skipping unavailable/unknown gaps): (ts, value, previous). */
private fun numericChanges(points: List<HaPoint>): List<Triple<Long, Double, Double?>> {
    val out = mutableListOf<Triple<Long, Double, Double?>>()
    var last: Double? = null
    var first = true
    for (p in points) {
        val v = p.state.toDoubleOrNull() ?: continue
        if (!first && v != last) out += Triple(p.ts, v, last)
        first = false; last = v
    }
    return out
}

/**
 * A cat's weighings on one robot: every increase of the tracker's visit counter is one visit by that cat,
 * weighed at the robot's last cat weight reported just before (the tracker records right after it).
 * Home Assistant records no new state when a weighing repeats the previous value, so an older weight
 * still counts; the reading then takes the visit's time.
 */
fun haCatReadings(visits: List<HaPoint>, lastWeight: List<HaPoint>, weightUnit: String?): List<Reading> {
    val weights = lastWeight.mapNotNull { p -> p.state.toDoubleOrNull()?.takeIf { it > 0 }?.let { p.ts to toLbs(it, weightUnit) } }
    var used = Long.MIN_VALUE // a weight point belongs to one visit only
    return numericChanges(visits).filter { (_, v, prev) -> prev != null && v > prev }.mapNotNull { (ts, _, _) ->
        val w = weights.lastOrNull { it.first <= ts + 5_000 } ?: return@mapNotNull null
        val fresh = w.first > used && ts - w.first < 10 * 60_000
        if (fresh) used = w.first
        Reading(if (fresh) w.first else ts, kotlin.math.round(w.second * 100) / 100)
    }
}

private fun parseHistory(text: String): Map<String, List<HaPoint>> =
    json.parseToJsonElement(text).jsonArray.mapNotNull { list ->
        val rows = list.jsonArray.map { it.jsonObject }
        val id = rows.firstOrNull()?.s("entity_id") ?: return@mapNotNull null
        id to rows.mapNotNull { r -> HaPoint(r.s("last_changed")?.let(::parseTs) ?: return@mapNotNull null, r.s("state") ?: return@mapNotNull null) }
    }.toMap()

/** What one local refresh found, ready to merge into [AppState]. */
data class HaSnapshot(val robots: List<Robot>, val pets: List<Pet>, val weights: Map<String, List<Reading>>, val acts: Map<String, List<Act>>)

class LocalHa(val backend: HaBackend) {
    /** Robots, cats and the history since [since] (epoch ms). */
    suspend fun load(since: Long): HaSnapshot {
        val devices = json.parseToJsonElement(backend.post("/api/template", buildJsonObject { put("template", DEVICES_TEMPLATE) }.toString()))
            .jsonArray.map { haRobot(it.jsonObject) }
        val states = json.parseToJsonElement(backend.get("/api/states")).jsonArray.map { it.jsonObject }.associateBy { it.s("entity_id").orEmpty() }

        // Cats: tracker slots in use (a weight, or at least one visit), merged across robots by name.
        data class Slot(val robot: HaRobot, val n: Int, val cat: HaCat, val petId: String) {
            val weight get() = cat.weight; val visits get() = cat.visits
        }
        val slots = mutableListOf<Slot>()
        val pets = LinkedHashMap<String, JsonObject>()
        for (r in devices) for ((n, c) in r.cats.entries.sortedBy { it.key }) {
            val w = c.weight?.let { states[it] }
            val lbs = w?.state()?.toDoubleOrNull()?.let { toLbs(it, w.attr("unit_of_measurement")) }
            val visits = c.visits?.let { states[it] }?.state()?.toDoubleOrNull() ?: 0.0
            if ((lbs == null || lbs <= 0) && visits <= 0) continue
            val name = haCatName(w?.attr("friendly_name"), r.name, n)
            val id = haPetId(name)
            slots += Slot(r, n, c, id)
            val old = pets[id]
            pets[id] = buildJsonObject {
                put("petId", id); put("name", old?.s("name") ?: name); put("type", "CAT")
                (lbs ?: old?.d("weight"))?.let { put("weight", it); put("lastWeightReading", it) }
                // A picture set on the cat's weight sensor in Home Assistant (entity_picture) is the cat's photo.
                (w?.attr("entity_picture")?.let { if (it.startsWith("/")) backend.base + it else it } ?: old?.s("s3ImageURL"))?.let { put("s3ImageURL", it) }
                // Where this cat lives on each robot, for the tracker controls and renaming.
                put("haSlots", JsonArray((old?.get("haSlots") as? JsonArray).orEmpty() + buildJsonObject {
                    put("robot", r.id); put("slot", n); put("entities", JsonArray(c.entities.map(::JsonPrimitive)))
                }))
            }
        }

        val ids = devices.flatMap { r -> listOfNotNull(r.roles["sensor.status"], r.roles["sensor.last_cat_weight"], r.roles["binary_sensor.waste_drawer_full"]) } +
            slots.mapNotNull { it.visits }
        val start = Instant.fromEpochMilliseconds(since).toString()
        val hist = if (ids.isEmpty()) emptyMap() else
            // Without end_time Home Assistant returns only the first day after start.
            parseHistory(backend.get("/api/history/period/$start?filter_entity_id=${ids.joinToString(",")}&end_time=${Instant.fromEpochMilliseconds(nowMs() + 3_600_000L)}&minimal_response&no_attributes"))

        val unit = { r: HaRobot -> r.roles["sensor.last_cat_weight"]?.let { states[it]?.attr("unit_of_measurement") } }
        val acts = devices.associate { r ->
            fun h(role: String) = r.roles[role]?.let { hist[it] }.orEmpty()
            r.id to haActs(h("sensor.status"), h("sensor.last_cat_weight"), h("binary_sensor.waste_drawer_full"), unit(r))
        }
        val weights = HashMap<String, MutableList<Reading>>()
        val extra = HashMap<String, MutableList<Act>>() // repeated weighings: in the visit counter, not in the weight history
        for (s in slots) {
            val lw = s.robot.roles["sensor.last_cat_weight"]?.let { hist[it] }.orEmpty()
            val readings = haCatReadings(s.visits?.let { hist[it] }.orEmpty(), lw, unit(s.robot))
            weights.getOrPut(s.petId) { mutableListOf() } += readings
            val logged = acts[s.robot.id].orEmpty().filter { it.value == "catWeight" }.map { it.ts }.toSet()
            extra.getOrPut(s.robot.id) { mutableListOf() } += readings.filter { it.ts !in logged }.map { Act(it.ts, "catWeight", "${it.lbs}") }
        }
        val allActs = acts.mapValues { (id, a) -> (a + extra[id].orEmpty()).sortedByDescending { it.ts } }
        return HaSnapshot(devices.map { Robot(haRobotJson(it, states)) }, pets.values.map(::Pet), weights.mapValues { it.value.sortedBy { r -> r.ts } }, allActs)
    }

    /** A service call on one of the robot's firmware entities, e.g. ("switch.auto_cycle", "turn_on"). */
    suspend fun call(robot: Robot, role: String, service: String, vararg extra: Pair<String, Any>) {
        val (domain, svc, data) = haService(robot, role, service, *extra)
        backend.post("/api/services/$domain/$svc", data.toString())
    }

    /** One day of the firmware's per-day sleep schedule; times in minutes since midnight. */
    suspend fun setSleep(robot: Robot, day: String, enabled: Boolean, sleepMin: Int, wakeMin: Int) {
        val d = day.lowercase()
        val t = { m: Int -> "%02d:%02d:00".format(m / 60 % 24, m % 60) }
        call(robot, "time.${d}_sleep_time", "set_value", "time" to t(sleepMin))
        call(robot, "time.${d}_wake_time", "set_value", "time" to t(wakeMin))
        call(robot, "switch.${d}_sleep_schedule", if (enabled) "turn_on" else "turn_off")
    }

    /** Sets the cat's weight in the tracker on every robot it uses ("Set Cat N Weight"), so visits match it. */
    suspend fun setTrackerWeight(pet: Pet, lbs: Double) = pet.haSlots().forEach { (_, _, ents) ->
        val e = ents.firstOrNull { it.startsWith("number.") } ?: return@forEach
        backend.post("/api/services/number/set_value", buildJsonObject { put("entity_id", e); put("value", kotlin.math.round(lbs * 100) / 100) }.toString())
    }

    /** "Cat N Tracker Reset" on every robot: forgets the learned weight and zeroes the counters. */
    suspend fun resetTracker(pet: Pet) = pet.haSlots().forEach { (_, _, ents) ->
        val e = ents.firstOrNull { it.startsWith("button.") } ?: return@forEach
        backend.post("/api/services/button/press", buildJsonObject { put("entity_id", e) }.toString())
    }

    suspend fun renameRobot(robot: Robot, name: String) {
        backend.ws(listOf(buildJsonObject { put("type", "config/device_registry/update"); put("device_id", robot.serial); put("name_by_user", name) }))
    }

    /** Renames the cat's entities on every robot ("Mochi Weight" → "Momo Weight"), as the firmware's guide has users do. */
    suspend fun renamePet(pet: Pet, name: String) {
        backend.ws(pet.haSlots().flatMap { (_, n, ents) -> ents.map { renameEntity(it, n, name, enable = false) } })
    }

    /**
     * Adds a cat: on each chosen robot, takes a free tracker slot (never enabled, or no weight and no visits),
     * enables and names its entities, reloads the robot so they appear, then sets the starting weight.
     * Returns the names of robots that had no free slot.
     */
    suspend fun addPet(name: String, lbs: Double, robotIds: Set<String>): List<String> {
        val states = json.parseToJsonElement(backend.get("/api/states")).jsonArray.map { it.jsonObject }.associateBy { it.s("entity_id").orEmpty() }
        fun num(e: String?) = e?.let { states[it]?.state()?.toDoubleOrNull() } ?: 0.0
        // The registry, unlike templates, also lists the cat entities the firmware ships disabled.
        val registry = backend.ws(listOf(buildJsonObject { put("type", "config/entity_registry/list") })).first()
            .let { (it as JsonArray).map { e -> e.jsonObject } }.filter { it.s("device_id") in robotIds }
        val full = mutableListOf<String>()
        val chosen = devices().filter { it.id in robotIds }.mapNotNull { d ->
            val r = haRobot(buildJsonObject { put("id", d.id); put("name", d.name); put("entities", JsonArray(registry.filter { it.s("device_id") == d.id }.map { JsonPrimitive(it.s("entity_id")) })) })
            val free = r.cats.entries.sortedBy { it.key }.firstOrNull { (_, c) -> num(c.weight) <= 0 && num(c.visits) <= 0 }?.key
            if (free == null) { full += d.name; null } else Triple(d, free, r.cats.getValue(free).entities)
        }
        if (chosen.isEmpty()) return full
        val disabled = registry.filter { it["disabled_by"]?.str() != null }.mapNotNull { it.s("entity_id") }.toSet()
        backend.ws(chosen.flatMap { (_, n, ents) -> ents.map { renameEntity(it, n, name, enable = it in disabled) } })
        val reload = chosen.filter { (_, _, ents) -> ents.any { it in disabled } }
        reload.mapNotNull { it.first.entry }.distinct().forEach { backend.post("/api/config/config_entries/entry/$it/reload", "{}") }
        for ((_, _, ents) in chosen) {
            val set = ents.firstOrNull { it.startsWith("number.") } ?: continue
            for (attempt in 1..20) { // after a reload the entities take a few seconds to come back; check the value stuck
                if (reload.isNotEmpty() || attempt > 1) kotlinx.coroutines.delay(1500)
                val st = runCatching { json.parseToJsonElement(backend.get("/api/states/$set")).jsonObject.state() }.getOrNull()
                if (st?.toDoubleOrNull()?.let { kotlin.math.abs(it - lbs) < 0.01 } == true) break
                if (st != null && st != "unavailable") runCatching {
                    backend.post("/api/services/number/set_value", buildJsonObject { put("entity_id", set); put("value", lbs) }.toString())
                }
            }
        }
        return full
    }

    /** Raw robots (for adding a cat). */
    suspend fun devices() = json.parseToJsonElement(backend.post("/api/template", buildJsonObject { put("template", DEVICES_TEMPLATE) }.toString()))
        .jsonArray.map { haRobot(it.jsonObject) }

    suspend fun command(robot: Robot, command: String, value: String?) {
        val (domain, service, data) = haCommand(robot, command, value)
        backend.post("/api/services/$domain/$service", data.toString())
    }
}

/**
 * The cloud API's command names (as the robot screens send them) as Home Assistant service calls
 * on the firmware's entities: (domain, service, data).
 */
fun haService(robot: Robot, role: String, service: String, vararg extra: Pair<String, Any>): Triple<String, String, JsonObject> {
    val id = robot.raw.o("haEntities")?.s(role)
        ?: throw ApiException("This robot's firmware has no ${role.substringAfter('.').replace('_', ' ')} control in Home Assistant")
    return Triple(role.substringBefore('.'), service, buildJsonObject {
        put("entity_id", id)
        extra.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
    })
}

/** The entity's name for a cat: "Cat 3 Weight" → "Pixel Weight", "Set Cat 3 Weight" → "Set Pixel Weight". */
fun haSlotName(entityId: String, slot: Int, cat: String): String {
    val obj = entityId.substringAfter('.').replace(Regex("_\\d+$"), "")
    val set = obj.endsWith("set_cat_${slot}_weight")
    val what = obj.substringAfter("cat_${slot}_").split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    return if (set) "Set $cat Weight" else "$cat $what"
}

private fun renameEntity(entityId: String, slot: Int, cat: String, enable: Boolean) = buildJsonObject {
    put("type", "config/entity_registry/update"); put("entity_id", entityId); put("name", haSlotName(entityId, slot, cat))
    if (enable) put("disabled_by", kotlinx.serialization.json.JsonNull)
}

/** (robot id, slot, entities) for each robot the cat uses. */
fun Pet.haSlots(): List<Triple<String, Int, List<String>>> = raw.a("haSlots").map { o ->
    Triple(o.s("robot").orEmpty(), o.i("slot") ?: 0, (o["entities"] as? JsonArray).orEmpty().mapNotNull { it.str() })
}

fun haCommand(robot: Robot, command: String, value: String?): Triple<String, String, JsonObject> {
    fun call(role: String, service: String, vararg extra: Pair<String, Any>) = haService(robot, role, service, *extra)
    val num = { k: String -> value?.let { json.parseToJsonElement(it).jsonObject.d(k) } ?: throw ApiException("Missing $k") }
    val level = { v: Double -> when { v <= 25 -> "Low"; v <= 50 -> "Medium"; else -> "High" } }
    return when (command) {
        "cleanCycle" -> call("button.manual_cycle", "press")
        "shortResetPress" -> call("button.reset", "press")
        "powerOn" -> call("switch.power", "turn_on")
        "powerOff" -> call("switch.power", "turn_off")
        "keyPadLockOutOn" -> call("switch.control_panel_lockout", "turn_on")
        "keyPadLockOutOff" -> call("switch.control_panel_lockout", "turn_off")
        "nightLightModeOn" -> call("select.night_light_mode", "select_option", "option" to "On")
        "nightLightModeOff" -> call("select.night_light_mode", "select_option", "option" to "Off")
        "nightLightModeAuto" -> call("select.night_light_mode", "select_option", "option" to "Auto")
        "setNightLightValue" -> call("select.night_light_brightness", "select_option", "option" to level(num("nightLightPower")))
        "panelBrightnessLow" -> call("select.panel_brightness", "select_option", "option" to "Low")
        "panelBrightnessMed" -> call("select.panel_brightness", "select_option", "option" to "Medium")
        "panelBrightnessHigh" -> call("select.panel_brightness", "select_option", "option" to "High")
        "setClumpTime" -> call("number.clean_cycle_delay", "set_value", "value" to num("clumpTime").toInt())
        "hopperOn" -> call("switch.hopper", "turn_on")
        "hopperOff" -> call("switch.hopper", "turn_off")
        "replaceFilter" -> call("button.replace_filter", "press")
        "emptyLitter" -> call("button.empty_litter", "press")
        else -> throw ApiException("Not available with the ESPHome firmware: $command")
    }
}

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())


/** Local mode: whether the robot's firmware has this entity, and its state ("on", "Auto", …). */
fun Robot.has(role: String) = raw.o("haEntities")?.s(role) != null
fun Robot.haState(role: String) = raw.o("haState")?.s(role)
