package io.github.gccammelli.meowstache

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/**
 * The local demo's Home Assistant: two robots on the ESPHome firmware ("Robbie" and "Bobbie"), entities named
 * as Home Assistant names the firmware's, and the demo cats' visits (Demo.kt) as entity history. Mochi uses
 * Robbie; Luna uses both, so she is tracked on two robots and merged by name. It answers the same REST calls as
 * a real Home Assistant, so the demo runs the real local-mode code; service calls change its state.
 */
class FakeHomeAssistant(private val now: Long = nowMs(), zone: ZoneId = ZoneId.systemDefault()) : HaBackend {
    override val base = ""
    /** [label] is the firmware's entity name; [custom] a name the user gave it (shown without the robot's name, as HA does). */
    private class Ent(val id: String, val label: String, val unit: String? = null, val points: MutableList<HaPoint> = mutableListOf(),
                      var picture: String? = null, var custom: String? = null, var enabled: Boolean = true) {
        val state get() = points.lastOrNull()?.state ?: "unknown"
    }

    private val devices = mutableListOf("demo-robbie" to "Robbie", "demo-bobbie" to "Bobbie")
    private val pendingEnable = mutableSetOf<String>()
    private fun friendly(e: Ent) = e.custom ?: "${devices.first { it.first == owner[e.id] }.second} ${e.label}"
    private val ents = LinkedHashMap<String, Ent>()
    private val owner = HashMap<String, String>()
    private var cycleEnds: Pair<String, Long>? = null

    private fun ent(dev: Int, domain: String, obj: String, name: String, initial: String, unit: String? = null, nameOverride: String? = null): Ent {
        val id = "$domain.litter_robot_4_$obj" + if (dev == 0) "" else "_${dev + 1}"
        val e = Ent(id, name, unit, custom = nameOverride)
        e.points += HaPoint(0, initial)
        ents[id] = e; owner[id] = devices[dev].first
        return e
    }

    init {
        val demo = demoState(now, zone)
        val acts = demo.acts.values.first()
        val detects = acts.filter { it.value == "robotStatusCatDetect" }.map { it.ts }.sorted()
        val dumps = acts.filter { it.value == "robotCycleStatusDump" }.map { it.ts }.sorted()
        val idles = acts.filter { it.value == "robotCycleStatusIdle" }.map { it.ts }.sorted()
        val cats = listOf(listOf("Mochi" to "demo-mochi", "Luna" to "demo-luna"), listOf("Luna" to "demo-luna"))

        class Dev(val status: Ent, val weight: Ent, val cycles: Ent, val slots: List<Pair<Ent, Ent>>)
        val devs = devices.indices.map { d ->
            ent(d, "sensor", "waste_drawer_level", "Waste Drawer Level", if (d == 0) "58" else "31", "%")
            ent(d, "sensor", "litter_level", "Litter Level", if (d == 0) "86" else "92", "%")
            ent(d, "sensor", "hopper_status", "Hopper Status", if (d == 0) "Ready" else "Disabled")
            ent(d, "sensor", "fault", "Fault", "None")
            ent(d, "sensor", "power_type", "Power Type", "AC")
            ent(d, "binary_sensor", "waste_drawer_full", "Waste Drawer Full", "off")
            ent(d, "binary_sensor", "sleeping", "Sleeping", "off")
            ent(d, "binary_sensor", "hopper_empty", "Hopper Empty", "off")
            ent(d, "switch", "power", "Power", "on")
            ent(d, "switch", "control_panel_lockout", "Control Panel Lockout", "off")
            ent(d, "switch", "hopper", "Hopper", if (d == 0) "on" else "off")
            ent(d, "select", "night_light_mode", "Night Light Mode", "Auto")
            ent(d, "select", "night_light_brightness", "Night Light Brightness", "Medium")
            ent(d, "select", "panel_brightness", "Panel Brightness", "High")
            ent(d, "number", "clean_cycle_delay", "Clean Cycle Delay", "7", "min")
            ent(d, "switch", "smart_cat_detection", "Smart Cat Detection", "on")
            ent(d, "switch", "auto_cycle", "Auto Cycle", "on")
            listOf("manual_cycle" to "Manual Cycle", "reset" to "Reset", "replace_filter" to "Replace Filter", "empty_litter" to "Empty Litter")
                .forEach { (o, n) -> ent(d, "button", o, n, "unknown") }
            listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").forEach { day ->
                val o = day.lowercase()
                val weekday = day !in setOf("Saturday", "Sunday")
                // Bobbie keeps the firmware's defaults: the sleep schedule entities are disabled in Home Assistant.
                listOf(ent(d, "switch", "${o}_sleep_schedule", "$day Sleep Schedule", if (d == 0 && weekday) "on" else "off"),
                    ent(d, "time", "${o}_sleep_time", "$day Sleep Time", "23:00:00"),
                    ent(d, "time", "${o}_wake_time", "$day Wake Time", "06:30:00")).forEach { it.enabled = d == 0 }
            }
            val slots = (1..4).map { n ->
                val cat = cats[d].getOrNull(n - 1)?.first
                val off = { e: Ent -> e.also { it.enabled = cat != null } } // the firmware ships the cat entities disabled
                off(ent(d, "button", "cat_${n}_tracker_reset", "Cat $n Tracker Reset", "unknown", nameOverride = cat?.let { "$it Tracker Reset" }))
                off(ent(d, "number", "set_cat_${n}_weight", "Set Cat $n Weight", "unknown", "lb", cat?.let { "Set $it Weight" }))
                off(ent(d, "sensor", "cat_${n}_weight", "Cat $n Weight", "unknown", "lb", cat?.let { "$it Weight" })
                    .also { e -> e.picture = cat?.let { "res:files/demo/${it.lowercase()}.jpg" } }) to
                    off(ent(d, "sensor", "cat_${n}_visits", "Cat $n Visits", "0", nameOverride = cat?.let { "$it Visits" }))
            }
            Dev(ent(d, "sensor", "status", "Status", "Ready"),
                ent(d, "sensor", "last_cat_weight", "Last Cat Weight", "unknown", "lb"),
                ent(d, "sensor", "clean_cycles", "Clean Cycles", "1200"), slots)
        }

        // Replay every demo visit on its robot: status changes, the weighing, the cat's tracker counter, the cycle.
        val visits = demo.weights.flatMap { (pet, l) -> l.map { pet to it } }.sortedBy { it.second.ts }
        var luna = 0
        for ((pet, r) in visits) {
            val d = if (pet == "demo-mochi" || luna++ % 2 == 0) 0 else 1
            val dev = devs[d]
            val slot = dev.slots[cats[d].indexOfFirst { it.second == pet }]
            val t = r.ts
            val det = detects.lastOrNull { it <= t } ?: (t - 40_000)
            val dump = dumps.firstOrNull { it > t } ?: (t + 10 * 60_000)
            val idle = idles.firstOrNull { it > dump } ?: (dump + 150_000)
            dev.status.points += listOf(HaPoint(det, "Cat detected"), HaPoint(dump - 7 * 60_000, "Waiting to cycle"),
                HaPoint(dump, "Clean cycle in progress"), HaPoint(idle, "Ready"))
            dev.weight.points += HaPoint(t, "${r.lbs}")
            slot.first.points += HaPoint(t + 200, "${r.lbs}")
            slot.second.points += HaPoint(t + 200, "${slot.second.state.toDouble() + 1}")
            dev.cycles.points += HaPoint(idle, "${dev.cycles.state.toDouble() + 1}")
        }
        ents.values.forEach { e -> e.points.sortBy { it.ts } }
        devs.forEach { dev -> dev.slots.forEach { (w, _) ->
            if (w.state != "unknown") ents.getValue(w.id.replace("sensor.", "number.").replace("_cat_", "_set_cat_")).points += HaPoint(now, w.state)
        } }
    }

    override suspend fun get(path: String): String {
        finishCycle()
        return when {
            path == "/api/states" -> buildJsonArray { ents.values.filter { it.enabled }.forEach { add(stateJson(it)) } }.toString()
            path.startsWith("/api/states/") -> ents[path.removePrefix("/api/states/")]?.takeIf { it.enabled }?.let { stateJson(it).toString() }
                ?: throw ApiException("Home Assistant: HTTP 404")
            path.startsWith("/api/history/period/") -> {
                val start = parseTs(path.removePrefix("/api/history/period/").substringBefore('?')) ?: 0
                val ids = path.substringAfter("filter_entity_id=").substringBefore('&').split(',')
                // Like Home Assistant: one day unless end_time is given.
                val end = path.substringAfter("end_time=", "").substringBefore('&').let(::parseTs) ?: (start + 86_400_000L)
                buildJsonArray {
                    ids.mapNotNull { ents[it] }.forEach { e ->
                        add(buildJsonArray {
                            val before = e.points.lastOrNull { it.ts < start }
                            // Like Home Assistant, a repeated value is no new state.
                            val rows = (listOfNotNull(before?.copy(ts = start)) + e.points.filter { it.ts in start..end })
                                .let { l -> l.filterIndexed { i, p -> i == 0 || p.state != l[i - 1].state } }
                            rows.forEachIndexed { i, p ->
                                add(buildJsonObject {
                                    if (i == 0) put("entity_id", e.id)
                                    put("state", p.state); put("last_changed", Instant.fromEpochMilliseconds(p.ts).toString())
                                })
                            }
                        })
                    }
                }.toString()
            }
            else -> throw ApiException("Demo Home Assistant: no $path")
        }
    }

    override suspend fun post(path: String, body: String): String {
        if (path == "/api/template") return buildJsonArray {
            devices.forEach { (id, name) ->
                add(buildJsonObject {
                    put("id", id); put("name", name); put("model", "Litter Robot 4"); put("sw", "2026.10.0 (ESPHome 2026.9.1)"); put("entry", "entry-$id")
                    putJsonArray("entities") { owner.filterValues { it == id }.keys.filter { ents.getValue(it).enabled }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                })
            }
        }.toString()
        if (path.startsWith("/api/config/config_entries/entry/")) { // reload: newly enabled entities appear
            ents.values.filter { it.id in pendingEnable }.forEach { it.enabled = true }
            pendingEnable.clear()
            return "{}"
        }
        val (domain, service) = path.removePrefix("/api/services/").split('/')
        val data = json.parseToJsonElement(body).jsonObject
        val e = ents[data.s("entity_id")] ?: throw ApiException("Unknown entity")
        val t = nowMs()
        when ("$domain.$service") {
            "switch.turn_on", "switch.turn_off" -> {
                val on = service == "turn_on"
                e.points += HaPoint(t, if (on) "on" else "off")
                // The firmware's Hopper Status follows the hopper switch.
                if (e.id.contains("_hopper")) ents[e.id.replace("switch.", "sensor.").replace("_hopper", "_hopper_status")]
                    ?.points?.add(HaPoint(t, if (on) "Ready" else "Disabled"))
            }
            "select.select_option" -> e.points += HaPoint(t, data.s("option")!!)
            "time.set_value" -> e.points += HaPoint(t, data.s("time")!!)
            "number.set_value" -> {
                e.points += HaPoint(t, data.s("value")!!)
                // The cat tracker takes "Set Cat N Weight" as the cat's weight.
                if ("_set_cat_" in e.id) ents[e.id.replace("number.", "sensor.").replace("_set_cat_", "_cat_")]?.points?.add(HaPoint(t, data.s("value")!!))
            }
            "button.press" -> if ("_tracker_reset" in e.id) {
                ents[e.id.replace("button.", "sensor.").replace("_tracker_reset", "_weight")]?.points?.add(HaPoint(t, "unknown"))
                ents[e.id.replace("button.", "sensor.").replace("_tracker_reset", "_visits")]?.points?.add(HaPoint(t, "0"))
            } else if (e.id.contains("manual_cycle")) {
                val status = ents.getValue(e.id.replace("button.", "sensor.").replace("manual_cycle", "status"))
                status.points += HaPoint(t, "Clean cycle in progress")
                cycleEnds = status.id to t + 20_000
            }
        }
        return "[]"
    }

    override suspend fun ws(commands: List<JsonObject>): List<kotlinx.serialization.json.JsonElement?> = commands.map { c ->
        when (c.s("type")) {
            "config/device_registry/update" -> {
                val i = devices.indexOfFirst { it.first == c.s("device_id") }
                devices[i] = devices[i].first to c.s("name_by_user")!!
            }
            "config/entity_registry/update" -> {
                val e = ents[c.s("entity_id")] ?: throw ApiException("Entity not found")
                c.s("name")?.let { e.custom = it }
                if (c.containsKey("disabled_by") && !e.enabled) pendingEnable += e.id
            }
            "config/device_registry/list" -> return@map buildJsonArray {
                devices.forEach { (id, name) ->
                    add(buildJsonObject {
                        put("id", id); put("name", "Litter Robot 4"); put("name_by_user", name); put("model", "Litter Robot 4")
                        put("manufacturer", "Joseph DiGiovanni"); put("sw_version", "2026.10.0 (ESPHome 2026.9.1)")
                        putJsonArray("config_entries") { add(kotlinx.serialization.json.JsonPrimitive("entry-$id")) }
                        put("disabled_by", null as String?)
                    })
                }
            }
            "config/entity_registry/list" -> return@map buildJsonArray {
                ents.values.forEach { e ->
                    add(buildJsonObject {
                        put("entity_id", e.id); put("device_id", owner[e.id]); put("config_entry_id", "entry-${owner[e.id]}")
                        put("platform", "esphome"); put("original_name", e.label); put("name", e.custom)
                        put("disabled_by", if (e.enabled || e.id in pendingEnable) null else "integration")
                    })
                }
            }
            else -> throw ApiException("Demo Home Assistant: no ${c.s("type")}")
        }
        null
    }

    /** A demo clean cycle takes 20 s. */
    private fun finishCycle() {
        val (id, at) = cycleEnds ?: return
        if (nowMs() < at) return
        ents.getValue(id).points += HaPoint(at, "Ready")
        cycleEnds = null
    }

    private fun stateJson(e: Ent): JsonObject = buildJsonObject {
        put("entity_id", e.id); put("state", e.state)
        putJsonObject("attributes") {
            put("friendly_name", friendly(e)); e.unit?.let { put("unit_of_measurement", it) }; e.picture?.let { put("entity_picture", it) }
        }
        put("last_updated", Instant.fromEpochMilliseconds(e.points.last().ts.coerceAtLeast(now - 60_000)).toString())
    }
}
