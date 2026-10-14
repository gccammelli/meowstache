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
    private class Ent(val id: String, val name: String, val unit: String? = null, val points: MutableList<HaPoint> = mutableListOf(), var picture: String? = null) {
        val state get() = points.lastOrNull()?.state ?: "unknown"
    }

    private val devices = listOf("demo-robbie" to "Robbie", "demo-bobbie" to "Bobbie")
    private val ents = LinkedHashMap<String, Ent>()
    private val owner = HashMap<String, String>()
    private var cycleEnds: Pair<String, Long>? = null

    private fun ent(dev: Int, domain: String, obj: String, name: String, initial: String, unit: String? = null, nameOverride: String? = null): Ent {
        val id = "$domain.litter_robot_4_$obj" + if (dev == 0) "" else "_${dev + 1}"
        val e = Ent(id, nameOverride ?: "${devices[dev].second} $name", unit)
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
            ent(d, "sensor", "hopper_status", "Hopper Status", "Ready")
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
            listOf("manual_cycle" to "Manual Cycle", "reset" to "Reset", "replace_filter" to "Replace Filter")
                .forEach { (o, n) -> ent(d, "button", o, n, "unknown") }
            listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday").forEach { day ->
                val o = day.lowercase()
                val weekday = day !in setOf("Saturday", "Sunday")
                ent(d, "switch", "${o}_sleep_schedule", "$day Sleep Schedule", if (d == 0 && weekday) "on" else "off")
                ent(d, "time", "${o}_sleep_time", "$day Sleep Time", "23:00:00")
                ent(d, "time", "${o}_wake_time", "$day Wake Time", "06:30:00")
            }
            val slots = (1..4).map { n ->
                val cat = cats[d].getOrNull(n - 1)?.first
                ent(d, "number", "set_cat_${n}_weight", "Set Cat $n Weight", "unknown", "lb")
                ent(d, "sensor", "cat_${n}_weight", "Cat $n Weight", "unknown", "lb", cat?.let { "$it Weight" })
                    .also { e -> e.picture = cat?.let { "res:files/demo/${it.lowercase()}.jpg" } } to
                    ent(d, "sensor", "cat_${n}_visits", "Cat $n Visits", "0", nameOverride = cat?.let { "$it Visits" })
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
            path == "/api/states" -> buildJsonArray { ents.values.forEach { add(stateJson(it)) } }.toString()
            path.startsWith("/api/history/period/") -> {
                val start = parseTs(path.removePrefix("/api/history/period/").substringBefore('?')) ?: 0
                val ids = path.substringAfter("filter_entity_id=").substringBefore('&').split(',')
                buildJsonArray {
                    ids.mapNotNull { ents[it] }.forEach { e ->
                        add(buildJsonArray {
                            val before = e.points.lastOrNull { it.ts < start }
                            // Like Home Assistant, a repeated value is no new state.
                            val rows = (listOfNotNull(before?.copy(ts = start)) + e.points.filter { it.ts >= start })
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
                    put("id", id); put("name", name); put("model", "Litter Robot 4"); put("sw", "2026.10.0 (ESPHome 2026.9.1)")
                    putJsonArray("entities") { owner.filterValues { it == id }.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                })
            }
        }.toString()
        val (domain, service) = path.removePrefix("/api/services/").split('/')
        val data = json.parseToJsonElement(body).jsonObject
        val e = ents[data.s("entity_id")] ?: throw ApiException("Unknown entity")
        val t = nowMs()
        when ("$domain.$service") {
            "switch.turn_on" -> e.points += HaPoint(t, "on")
            "switch.turn_off" -> e.points += HaPoint(t, "off")
            "select.select_option" -> e.points += HaPoint(t, data.s("option")!!)
            "number.set_value" -> e.points += HaPoint(t, data.s("value")!!)
            "button.press" -> if (e.id.contains("manual_cycle")) {
                val status = ents.getValue(e.id.replace("button.", "sensor.").replace("manual_cycle", "status"))
                status.points += HaPoint(t, "Clean cycle in progress")
                cycleEnds = status.id to t + 20_000
            }
        }
        return "[]"
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
            put("friendly_name", e.name); e.unit?.let { put("unit_of_measurement", it) }; e.picture?.let { put("entity_picture", it) }
        }
        put("last_updated", Instant.fromEpochMilliseconds(e.points.last().ts.coerceAtLeast(now - 60_000)).toString())
    }
}
