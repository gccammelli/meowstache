package io.github.gccammelli.meowstache

import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import io.github.gccammelli.meowstache.YearMonth
import io.github.gccammelli.meowstache.ZoneId
import io.github.gccammelli.meowstache.DateTimeFormatter
import io.github.gccammelli.meowstache.Locale

enum class Range(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year"), ALL("All") }

/** A chart column: readings with start <= day <= end fall into it. */
data class Bucket(val start: LocalDate, val end: LocalDate, val label: String)

fun Long.localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/**
 * Week/Month end yesterday, like the official app ("6 October – 12 October" on the 13th).
 * Year is the last 12 calendar months; All is every month since [earliest].
 */
fun buckets(range: Range, today: LocalDate, earliest: LocalDate?): List<Bucket> {
    val yesterday = today.minusDays(1)
    return when (range) {
        Range.WEEK -> (6 downTo 0).map { yesterday.minusDays(it.toLong()) }
            .map { Bucket(it, it, it.dayOfWeek.shortName()) }
        Range.MONTH -> (29 downTo 0).map { yesterday.minusDays(it.toLong()) }
            .map { Bucket(it, it, "${it.dayOfMonth}/${it.monthValue}") }
        Range.YEAR -> (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
            .map { Bucket(it.atDay(1), it.atEndOfMonth(), it.month.shortName()) }
        Range.ALL -> {
            val first = YearMonth.from(earliest ?: today)
            val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(today) }.toList()
            months.map { Bucket(it.atDay(1), it.atEndOfMonth(), it.format(DateTimeFormatter.ofPattern("MMM yy", Locale.UK))) }
        }
    }
}

fun rangeTitle(b: List<Bucket>): String {
    if (b.isEmpty()) return ""
    val f = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
    val fy = DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK)
    val s = b.first().start; val e = b.last().end
    return if (s.year == e.year && e.toEpochDay() - s.toEpochDay() < 40) "${s.format(f)} – ${e.format(f)}" else "${s.format(fy)} – ${e.format(fy)}"
}

/** Mean weight per bucket, null where there were no readings. */
fun weightSeries(readings: List<Reading>, b: List<Bucket>, zone: ZoneId = ZoneId.systemDefault()): List<Double?> {
    val byDay = readings.groupBy { it.ts.localDate(zone) }
    return b.map { bk ->
        val v = byDay.filterKeys { it in bk.start..bk.end }.values.flatten()
        if (v.isEmpty()) null else v.sumOf { it.lbs } / v.size
    }
}

fun countSeries(timestamps: List<Long>, b: List<Bucket>, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
    val days = timestamps.map { it.localDate(zone) }
    return b.map { bk -> days.count { it in bk.start..bk.end } }
}

fun dayReadings(readings: List<Reading>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
    readings.filter { it.ts.localDate(zone) == day }

/**
 * Minutes each visit lasted, keyed by the weight reading's timestamp.
 * The API has no duration field, so it is estimated from the robot activity log:
 * from the "cat detected" event before the weighing to the cat leaving, which is
 * the next clean cycle start minus the cycle-delay wait. "Cat detected" is logged a few seconds
 * after the cat steps in; adding 9 s per visit matched the official app's figures for both pets
 * (5.3 and 3.5 min on 12 Oct 2026, 2 visits each).
 * shortcut: estimate only; replace with the server value if a duration field turns up.
 */
private const val DETECT_LAG_MS = 9_000L

fun visitDurations(weights: List<Reading>, acts: List<Act>, waitMin: Int): Map<Long, Double> {
    val detects = acts.filter { it.value == "robotStatusCatDetect" }.map { it.ts }.sorted()
    val dumps = acts.filter { it.value == "robotCycleStatusDump" }.map { it.ts }.sorted()
    val wait = waitMin * 60_000L
    val out = HashMap<Long, Double>()
    for (w in weights) {
        val start = detects.lastOrNull { it <= w.ts && w.ts - it < 15 * 60_000 } ?: continue
        val dump = dumps.firstOrNull { it > w.ts && it - w.ts < 90 * 60_000 } ?: continue
        val leave = maxOf(dump - wait, w.ts)
        out[w.ts] = (leave - start + DETECT_LAG_MS) / 60_000.0
    }
    return out
}

/** Official-app-style label for an activity row, or null for rows the official app hides. */
fun actLabel(a: Act): String? = when (a.value) {
    "robotCycleStatusIdle" -> "Cycle completed"
    "litterHopperDispensed" -> "Litter dispensed"
    "catWeight" -> "Weight recorded"
    "robotStatusCatDetect" -> "Cat detected"
    "robotCycleStateCatDetect" -> "Cycle interrupted"
    "DFIFullFlagOn" -> "Waste drawer full"
    "bonnetRemovedYes" -> "Bonnet removed"
    "catDetectStuckLaser" -> "Cat sensor fault"
    "powerTypeDC" -> "Battery backup"
    "globeMotorFaultStatusFaultOvertorqueSlope" -> "Globe motor fault"
    else -> null // internal rows (dump, odometer, drawer %, …) the official app doesn't list
}

/**
 * The pet weight API only returns about the last week, but the robot activity log keeps every
 * weighing ("catWeight", lbs) since setup without saying whose it was. Readings the API knows
 * keep their pet; the rest are walked newest→oldest and given to the pet whose running weight
 * is closest, so slow growth or loss is followed back in time.
 * shortcut: nearest-weight guess; pets within ~0.5 lb of each other can get mixed up.
 */
fun attributeWeights(known: Map<String, List<Reading>>, acts: List<Act>): Map<String, List<Reading>> {
    val est = known.filterValues { it.isNotEmpty() }.mapValues { (_, r) -> r.minBy { it.ts }.lbs }.toMutableMap()
    if (est.isEmpty()) return known
    val knownTs = known.values.flatten().map { it.ts }.sorted()
    val out = known.mapValues { it.value.toMutableList() }.toMutableMap()
    val events = acts.filter { it.value == "catWeight" }.sortedByDescending { it.ts }
    for (a in events) {
        val lbs = a.actionValue?.toDoubleOrNull()?.takeIf { it > 0 } ?: continue
        val i = knownTs.binarySearch(a.ts).let { if (it < 0) -it - 1 else it }
        val near = listOfNotNull(knownTs.getOrNull(i), knownTs.getOrNull(i - 1)).any { kotlin.math.abs(it - a.ts) < 120_000 }
        if (near) continue
        val pet = est.minBy { kotlin.math.abs(it.value - lbs) }.key
        out.getOrPut(pet) { mutableListOf() } += Reading(a.ts, lbs)
        est[pet] = est.getValue(pet) * 0.8 + lbs * 0.2
    }
    return out.mapValues { (_, l) -> l.sortedBy { it.ts } }
}

// ---------- Pet alerts ----------

@Serializable
data class Target(val lbs: Double, val setAt: Long)

@Serializable
data class AlertSettings(
    val noVisitEnabled: Boolean = false,
    val noVisitDays: Int = 1,
    val tooManyEnabled: Boolean = false,
    val tooManyVisits: Int = 8,
    val targets: Map<String, Target> = emptyMap(),
    val patternsEnabled: Boolean = false,
    val recapEnabled: Boolean = false,
    val recapHour: Int = 8,
    val robot: RobotTopics = RobotTopics(),
)

/**
 * The official app's push topics, rebuilt on the phone from the robot state and activity log.
 * Topic defaults match the official app's; the whole group starts off so nothing new fires until the user opts in.
 */
@Serializable
data class RobotTopics(
    val usage: Boolean = false,
    val drawerFull: Boolean = true,
    val lowLitter: Boolean = true,
    val cycleComplete: Boolean = false,
    val cycleInterrupted: Boolean = false,
    val visits: Boolean = false, // not an official-app topic: a summary after each cat visit (GitHub #1)
    val maintenance: Boolean = true,
    val errors: Boolean = false,
    val updates: Boolean = false,
)

/** What [robotAlerts] needs from a robot, so the rules stay pure and testable. */
data class RobotSnapshot(
    val serial: String,
    val name: String,
    val online: Boolean,
    val lastSeen: Long?,
    val litterState: String?,
    val setup: Long?,
    val acts: List<Act>,
    val firmwareUpdate: String? = null, // latest version when an update is available
    val visits: List<VisitNote> = emptyList(),
)

/** A recent weighing, ready to announce: who (if attributed), the weight as shown, and the visit's estimated minutes. */
data class VisitNote(val ts: Long, val pet: String?, val weight: String?, val minutes: Double?)

private const val DAY_MS = 86_400_000L

/**
 * Robot notifications. Activity events only count when newer than 6 h, so turning a topic on
 * doesn't replay the whole history; keys include the event time so each one fires once.
 */
fun robotAlerts(now: Long, r: RobotSnapshot, t: RobotTopics, zone: ZoneId = ZoneId.systemDefault()): List<PetAlert> {
    val out = mutableListOf<PetAlert>()
    val n = r.name
    val recent = r.acts.filter { now - it.ts in 0 until 6 * 3_600_000L }
    fun ev(value: String, key: String, title: String, body: String) =
        recent.filter { it.value == value }.forEach { out += PetAlert("robot:$key:${r.serial}:${it.ts}", "", title, body) }
    if (t.usage) {
        if (t.drawerFull) ev("DFIFullFlagOn", "drawer", "Waste drawer full", "$n's waste drawer is full. Empty it so cleaning cycles keep running.")
        if (t.cycleComplete) ev("robotCycleStatusIdle", "cycle", "Clean cycle complete", "$n finished a clean cycle.")
        if (t.cycleInterrupted) ev("robotCycleStateCatDetect", "interrupted", "Clean cycle interrupted", "$n paused a clean cycle because a cat was detected.")
        // One summary per visit, once its length is known (the next clean cycle), or after 30 minutes without it.
        if (t.visits) r.visits.filter { now - it.ts in 0 until 6 * 3_600_000L && (it.minutes != null || now - it.ts > 30 * 60_000L) }.forEach { v ->
            val at = Instant.ofEpochMilli(v.ts).atZone(zone).toLocalTime()
            out += PetAlert("robot:visit:${r.serial}:${v.ts}", "", "${v.pet ?: "A cat"} visited $n",
                listOfNotNull(v.weight, v.minutes?.let { "%.1f min".format(it) }, "at %02d:%02d".format(at.hour, at.minute)).joinToString(" · "))
        }
        if (t.lowLitter && r.litterState in setOf("LOW", "EMPTY", "REFILL"))
            out += PetAlert("robot:litter:${r.serial}:${now.localDate(zone)}", "", "Low litter", "$n is low on litter. Top it up to the fill line.")
        if (t.maintenance && r.setup != null && now > r.setup) {
            val k = (now - r.setup) / (90 * DAY_MS)
            if (k >= 1) out += PetAlert("robot:deepclean:${r.serial}:$k", "", "Time for a deep clean",
                "It's been about 3 months: a full clean of the globe and base keeps the robot working well for longer.")
        }
    }
    if (t.errors) {
        mapOf("bonnetRemovedYes" to "Bonnet removed", "catDetectStuckLaser" to "Cat sensor fault",
            "globeMotorFaultStatusFaultOvertorqueSlope" to "Globe motor fault", "powerTypeDC" to "Running on battery backup",
        ).forEach { (v, label) -> ev(v, "err:$v", "$n: $label", "$label reported by $n. Check the robot.") }
        if (!r.online) {
            val since = r.lastSeen ?: now
            val h = (now - since) / 3_600_000L
            if (h >= 1) out += PetAlert("robot:offline:${r.serial}:$since:${if (h >= 24) 24 else 1}", "", "$n offline",
                "⚠️ $n has been offline for ${if (h >= 24) "24 hours" else "1 hour"}. Check its connectivity.")
        }
    }
    if (t.updates && r.firmwareUpdate != null)
        out += PetAlert("robot:fw:${r.serial}:${r.firmwareUpdate}", "", "Software update available", "A firmware update is available for $n: ${r.firmwareUpdate}.")
    return out
}

/**
 * The user's own fixes to weight attribution (the official app's "Reassign / Unassign weight"):
 * reading time → pet id, or "" to drop the reading from every pet.
 */
fun applyWeightOverrides(history: Map<String, List<Reading>>, overrides: Map<Long, String>): Map<String, List<Reading>> {
    if (overrides.isEmpty()) return history
    val all = history.values.flatten().associateBy { it.ts }
    val out = history.mapValues { (_, l) -> l.filter { it.ts !in overrides }.toMutableList() }.toMutableMap()
    for ((ts, pet) in overrides) {
        val r = all[ts] ?: continue
        if (pet.isNotEmpty()) out.getOrPut(pet) { mutableListOf() } += r
    }
    return out.mapValues { (_, l) -> l.sortedBy { it.ts } }
}

data class PetAlert(val key: String, val petId: String, val title: String, val body: String)

/**
 * Pure alert rules; the worker dedupes on [PetAlert.key] so each condition fires once.
 * - No visit for N days (once per gap).
 * - More than the threshold visits in the last 24 h (once per pet per day).
 * - Daily mean weight crossing a target, from below or above (once per crossing day).
 *   Daily means are used because single readings jitter by ±0.1 kg.
 */
fun evaluateAlerts(
    now: Long,
    pets: List<Pair<String, String>>,
    weights: Map<String, List<Reading>>,
    s: AlertSettings,
    fmt: (Double) -> String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<PetAlert> {
    val out = mutableListOf<PetAlert>()
    val today = now.localDate(zone)
    for ((id, name) in pets) {
        val r = weights[id].orEmpty().sortedBy { it.ts }
        val last = r.lastOrNull()?.ts
        if (s.noVisitEnabled && last != null && now - last >= s.noVisitDays * 86_400_000L) {
            val d = s.noVisitDays
            out += PetAlert("novisit:$id:$last", id, "$name hasn't used the Litter-Robot",
                "No visits from $name in the last $d day${if (d > 1) "s" else ""}.")
        }
        if (s.tooManyEnabled) {
            val n = r.count { now - it.ts in 0 until 86_400_000L }
            if (n > s.tooManyVisits) {
                out += PetAlert("toomany:$id:$today", id, "$name is visiting a lot",
                    "$name used the Litter-Robot $n times in the last 24 hours (limit ${s.tooManyVisits}).")
            }
        }
        val t = s.targets[id] ?: continue
        val daily = r.groupBy { it.ts.localDate(zone) }.entries.sortedBy { it.key }.associate { it.key to it.value }.mapValues { (_, v) -> v.sumOf { it.lbs } / v.size }
        val days = daily.keys.toList()
        for (i in 1 until days.size) {
            val day = days[i]
            if (day.atStartOfDay(zone).toInstant().toEpochMilli() + 86_400_000L <= t.setAt) continue
            val prev = daily.getValue(days[i - 1]); val cur = daily.getValue(day)
            val dir = when {
                prev < t.lbs && cur >= t.lbs -> "from below"
                prev > t.lbs && cur <= t.lbs -> "from above"
                else -> null
            } ?: continue
            out += PetAlert("target:$id:$day:${t.lbs}", id, "$name reached the target weight",
                "$name's average weight on ${day.dayOfMonth}/${day.monthValue} was ${fmt(cur)}, crossing the ${fmt(t.lbs)} target $dir.")
        }
    }
    return out
}

// ---------- Premium-style insights ----------

/** "today" for a chart paged [offset] periods back; All doesn't page. */
fun pagedToday(range: Range, today: LocalDate, offset: Int): LocalDate = when (range) {
    Range.WEEK -> today.minusDays(7L * offset)
    Range.MONTH -> today.minusDays(30L * offset)
    Range.YEAR -> today.minusYears(offset.toLong())
    Range.ALL -> today
}

data class Visit(val ts: Long, val lbs: Double, val minutes: Double?)

data class DayStats(val day: LocalDate, val visits: List<Visit>) {
    val meanLbs: Double? get() = visits.takeIf { it.isNotEmpty() }?.let { v -> v.sumOf { it.lbs } / v.size }
    val totalMinutes: Double? get() = visits.mapNotNull { it.minutes }.takeIf { it.isNotEmpty() }?.sum()
    /** Hour with the most visits; ties go to the one with more litter-box time. */
    fun peakHour(zone: ZoneId = ZoneId.systemDefault()): Int? =
        visits.groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).hour }
            .maxWithOrNull(compareBy({ it.value.size }, { e -> e.value.sumOf { it.minutes ?: 0.0 } }))?.key
}

fun dayStats(readings: List<Reading>, durations: Map<Long, Double>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
    DayStats(day, readings.filter { it.ts.localDate(zone) == day }.map { Visit(it.ts, it.lbs, durations[it.ts]) })

enum class Metric(val label: String) { VISITS("Visits"), WEIGHT("Weight"), DURATION("Duration") }

/** A day that stands out from the pet's own previous 4 weeks. */
data class Pattern(val metric: Metric, val day: LocalDate, val baseline: Double, val value: Double, val significant: Boolean) {
    val up get() = value > baseline
    fun text(weight: (Double) -> String): String {
        val dir = if (up) "Increased" else "Decreased"
        val from = when (metric) { Metric.WEIGHT -> weight(baseline); Metric.DURATION -> "%.0f min".format(baseline); else -> "%.0f".format(baseline) }
        val to = when (metric) { Metric.WEIGHT -> weight(value); Metric.DURATION -> "%.0f min".format(value); else -> "%.0f".format(value) }
        val ratio = if (baseline > 0) value / baseline else 0.0
        val size = if (metric != Metric.WEIGHT && ratio >= 2) "%.0fX".format(ratio) else "%.0f%%".format(kotlin.math.abs(ratio - 1) * 100)
        return "${metric.label}: $dir $size from $from to $to"
    }
}

/**
 * Premium "Patterns": compares one day with the 28 days before it (needs 7+ days of baseline).
 * Visits and duration must move by at least half/double and clear 2–2.5 standard deviations;
 * weight must move 3% (notable) or 5% (significant) and clear the same spread.
 */
fun detectPatterns(readings: List<Reading>, durations: Map<Long, Double>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Pattern> {
    if (readings.isEmpty()) return emptyList()
    val first = readings.minOf { it.ts }.localDate(zone)
    val base = (28 downTo 1).map { day.minusDays(it.toLong()) }.filter { it >= first }
    if (base.size < 7 || day < first) return emptyList()
    val stats = (base + day).associateWith { dayStats(readings, durations, it, zone) }
    fun check(metric: Metric, value: (DayStats) -> Double?): Pattern? {
        val v = value(stats.getValue(day)) ?: return null
        val b = base.mapNotNull { value(stats.getValue(it)) }
        if (b.size < 7) return null
        val m = b.average(); val sd = kotlin.math.sqrt(b.sumOf { (it - m) * (it - m) } / b.size)
        val d = kotlin.math.abs(v - m)
        val (notable, significant) = when (metric) {
            Metric.WEIGHT -> (d / m >= 0.03 && d > 2 * sd) to (d / m >= 0.05 && d > 2.5 * sd)
            else -> {
                val minAbs = if (metric == Metric.VISITS) 2.0 else 3.0
                val big = (v >= 2 * m || v <= m / 2) && d >= minAbs
                (big && d > 2 * sd) to (big && d > 2.5 * sd)
            }
        }
        return if (notable) Pattern(metric, day, m, v, significant) else null
    }
    return listOfNotNull(
        check(Metric.VISITS) { it.visits.size.toDouble() },
        check(Metric.WEIGHT) { it.meanLbs },
        check(Metric.DURATION) { it.totalMinutes },
    )
}

/** One line per pet for the daily recap notification. */
fun recapLine(name: String, s: DayStats, weight: (Double) -> String, zone: ZoneId = ZoneId.systemDefault()): String {
    if (s.visits.isEmpty()) return "$name: no visits"
    val parts = listOfNotNull(
        "${s.visits.size} visit${if (s.visits.size > 1) "s" else ""}",
        s.meanLbs?.let(weight),
        s.totalMinutes?.let { "%.1f min".format(it) },
        s.peakHour(zone)?.let { "peak ${hourLabel(it)}" },
    )
    return "$name: ${parts.joinToString(" · ")}"
}

fun hourLabel(h: Int) = when { h == 0 -> "12am"; h < 12 -> "${h}am"; h == 12 -> "12pm"; else -> "${h - 12}pm" }
