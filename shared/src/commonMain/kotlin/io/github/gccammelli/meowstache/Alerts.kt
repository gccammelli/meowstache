package io.github.gccammelli.meowstache

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Every alert rule: the original pet alerts plus the premium-style recap and pattern alerts. */
fun allAlerts(st: AppState, s: AlertSettings, now: Long = nowMs()): List<PetAlert> {
    val fmt: (Double) -> String = { Repo.weight(it) }
    val out = evaluateAlerts(now, st.pets.map { it.id to it.name }, st.history, s, fmt).toMutableList()
    val today = LocalDate.now(); val y = today.minusDays(1)
    if (s.recapEnabled && LocalTime.now().hour >= s.recapHour && st.pets.isNotEmpty()) {
        val lines = st.pets.map { recapLine(it.name, dayStats(st.history[it.id].orEmpty(), st.durations(it.id), y), fmt) }
        out += PetAlert("recap:$today", "", "Daily recap · ${y.dayOfMonth}/${y.monthValue}", lines.joinToString("\n"))
    }
    if (s.patternsEnabled) for (p in st.pets) {
        detectPatterns(st.history[p.id].orEmpty(), st.durations(p.id), y).filter { it.significant }.forEach {
            out += PetAlert("pattern:${p.id}:$y:${it.metric}", p.id, "${p.name}: unusual ${it.metric.label.lowercase()}", it.text(fmt))
        }
    }
    out += st.robots.flatMap { r -> robotAlerts(now, r.snapshot(st), s.robot) }
    return out
}

fun Robot.snapshot(st: AppState, firmwareUpdate: String? = null) =
    RobotSnapshot(serial, name, isOnline, lastSeen, litterState, setup, st.acts[serial].orEmpty(), firmwareUpdate)

