package io.github.gccammelli.meowstache.ui

import io.github.gccammelli.meowstache.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import io.github.gccammelli.meowstache.AppState
import io.github.gccammelli.meowstache.Bucket
import io.github.gccammelli.meowstache.Metric
import io.github.gccammelli.meowstache.Pattern
import io.github.gccammelli.meowstache.Range
import io.github.gccammelli.meowstache.Repo
import io.github.gccammelli.meowstache.dayStats
import io.github.gccammelli.meowstache.detectPatterns
import io.github.gccammelli.meowstache.hourLabel
import io.github.gccammelli.meowstache.localDate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import io.github.gccammelli.meowstache.ZoneId
import io.github.gccammelli.meowstache.Locale

// Premium-style features, rebuilt on data the free API already exposes.

/** Patterns for each of [days], computed once per data change. */
@Composable
fun rememberPatterns(s: AppState, petId: String, days: List<LocalDate>): Map<LocalDate, List<Pattern>> {
    val r = s.readings(petId)
    val d = s.durations(petId)
    return remember(r, d, days) { days.associateWith { detectPatterns(r, d, it) } }
}

/** Per-bucket marks for one metric (day buckets only; months are too coarse for a daily baseline). */
fun marksFor(b: List<Bucket>, range: Range, patterns: Map<LocalDate, List<Pattern>>, metric: Metric): List<Int> =
    if (range != Range.WEEK && range != Range.MONTH) emptyList()
    else b.map { bk -> patterns[bk.start].orEmpty().firstOrNull { it.metric == metric }?.let { if (it.up) 1 else -1 } ?: 0 }

@Composable
fun PatternLine(p: Pattern) = Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(22.dp).background(W.Text, CircleShape), contentAlignment = Alignment.Center) {
        Icon(if (p.up) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null, tint = W.Ink, modifier = Modifier.size(15.dp))
    }
    Column(Modifier.weight(1f).padding(start = 10.dp)) {
        Text(p.text { Repo.weight(it) }, color = W.Text, fontSize = 12.sp)
        Text("${p.day.dayOfMonth}/${p.day.monthValue}", color = W.Text2, fontSize = 11.sp)
    }
    Text(if (p.significant) "Significant" else "Notable", color = if (p.significant) W.Text else W.Text2,
        fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
}

/** Footer under a chart listing the patterns inside the visible range. */
@Composable
fun PatternFooter(b: List<Bucket>, range: Range, patterns: Map<LocalDate, List<Pattern>>, metric: Metric) {
    if (range != Range.WEEK && range != Range.MONTH) return
    val list = b.flatMap { patterns[it.start].orEmpty() }.filter { it.metric == metric }.sortedByDescending { it.day }
    if (list.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    list.take(3).forEach { PatternLine(it) }
}

@Composable
fun BetaBadge() = Text("BETA", color = Color(0xFF2B2B2B), fontSize = 10.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(W.Accent).padding(horizontal = 8.dp, vertical = 2.dp))

/** "Patterns" card on a pet's insights: everything unusual in the last 30 days. */
@Composable
fun PatternsCard(s: AppState, petId: String) {
    val days = remember { (0..30).map { LocalDate.now().minusDays(it.toLong()) } }
    val all = rememberPatterns(s, petId, days).values.flatten().sortedByDescending { it.day }
    WCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Patterns", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); BetaBadge()
        }
        Text("Days that differ from this pet's own previous 4 weeks.", color = W.Text2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        if (all.isEmpty()) Text("Everything looks normal over the last 30 days.", color = W.Green, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        all.take(6).forEach { PatternLine(it) }
    }
}

/** Duration trend: litter-box minutes per day (month buckets show the daily average). */
@Composable
fun DurationCard(s: AppState, petId: String) {
    val r = s.readings(petId); val d = s.durations(petId)
    val days = remember { (0..30).map { LocalDate.now().minusDays(it.toLong()) } }
    val patterns = rememberPatterns(s, petId, days)
    RangeChartCard(s.earliest(petId), title = "Visit Duration") { b, range ->
        val values = remember(r, d, b) {
            b.map { bk ->
                val perDay = r.filter { it.ts.localDate() in bk.start..bk.end }.groupBy { it.ts.localDate() }
                    .mapNotNull { (_, v) -> v.mapNotNull { d[it.ts] }.takeIf { it.isNotEmpty() }?.sum() }
                perDay.takeIf { it.isNotEmpty() }?.average()
            }
        }
        Chart(values, b.map { it.label }, ChartKind.BARS, average = values.filterNotNull().takeIf { it.isNotEmpty() }?.average(),
            tooltip = { "%.1f min".format(it) }, marks = marksFor(b, range, patterns, Metric.DURATION))
        Legend("Minutes" to W.Teal, "Average" to W.Purple)
        PatternFooter(b, range, patterns, Metric.DURATION)
    }
}

/** Time of Visit bubble chart (Week/Month, pageable). */
@Composable
fun TimeOfVisitCard(s: AppState, petId: String) {
    val r = s.readings(petId); val d = s.durations(petId)
    var month by rememberSaveable { mutableStateOf(false) }
    var offset by rememberSaveable(month) { mutableIntStateOf(0) }
    val len = if (month) 30 else 7
    val end = LocalDate.now().minusDays(1L + offset * len)
    val days = (len - 1 downTo 0).map { end.minusDays(it.toLong()) }
    val zone = ZoneId.systemDefault()
    val points = remember(r, d, end, len) {
        r.mapNotNull { x ->
            val z = Instant.ofEpochMilli(x.ts).atZone(zone)
            val col = days.indexOf(z.toLocalDate()).takeIf { it >= 0 } ?: return@mapNotNull null
            Triple(col, z.hour + z.minute / 60f, d[x.ts])
        }
    }
    WCard(Modifier.fillMaxWidth()) {
        Text("Time of Visit", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        WTabs(listOf("Week", "Month"), if (month) 1 else 0, { month = it == 1 })
        PeriodNav(rangeTitleFor(days.first(), days.last()), canBack = s.earliest(petId)?.let { days.first() > it } ?: false,
            canForward = offset > 0, { offset++ }, { offset-- })
        TimeOfVisitChart(days.map { if (month) "${it.dayOfMonth}" else it.dayOfWeek.shortName() }, points)
        Text("Bubble size shows how long each visit lasted.", color = W.Text2, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

fun rangeTitleFor(a: LocalDate, b: LocalDate) = io.github.gccammelli.meowstache.rangeTitle(listOf(Bucket(a, b, "")))

/** "‹  6 October – 12 October  ›" period switcher used under range tabs. */
@Composable
fun PeriodNav(title: String, canBack: Boolean, canForward: Boolean, back: () -> Unit, forward: () -> Unit, arrows: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (arrows) Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = if (canBack) W.Text else W.Off,
            modifier = Modifier.size(30.dp).clickable(enabled = canBack, onClick = back))
        Text(title, color = W.Text, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        if (arrows) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = if (canForward) W.Text else W.Off,
            modifier = Modifier.size(30.dp).clickable(enabled = canForward, onClick = forward))
    }
}

// ---------- Daily Recap ----------

@Composable
fun DailyRecapScreen(nav: NavHostController) {
    val s = state()
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var settings by remember { mutableStateOf(false) }
    val end = LocalDate.now().minusDays(7L * weekOffset)
    val week = (6 downTo 0).map { end.minusDays(it.toLong()) }
    val day = LocalDate.ofEpochDay(selected)
    val earliest = s.pets.mapNotNull { s.earliest(it.id) }.minOrNull()
    Screen({
        BackTopBar("Daily Recap", { nav.popBackStack() }) {
            Icon(Icons.Filled.Settings, "Recap settings", tint = W.Text, modifier = Modifier.size(26.dp).clickable { settings = true })
        }
    }) {
        Scroll {
            // Day strip; days with a pattern for any pet are orange.
            val flagged = s.pets.flatMap { p -> rememberPatterns(s, p.id, week).filterValues { it.isNotEmpty() }.keys }.toSet()
            WCard(Modifier.fillMaxWidth(), pad = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week", tint = if (earliest == null || week.first() > earliest) W.Text else W.Off,
                        modifier = Modifier.size(26.dp).clickable(enabled = earliest == null || week.first() > earliest) { weekOffset++; selected = LocalDate.now().minusDays(7L * weekOffset).toEpochDay() })
                    week.forEach { d ->
                        val sel = d == day
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) W.Accent else Color.Transparent)
                                .clickable { selected = d.toEpochDay() }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val c = when { sel -> Color(0xFF2B2B2B); d in flagged -> W.Accent; else -> W.Text }
                            Text(d.dayOfWeek.shortName(), color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("%02d".format(d.dayOfMonth), color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week", tint = if (weekOffset > 0) W.Text else W.Off,
                        modifier = Modifier.size(26.dp).clickable(enabled = weekOffset > 0) { weekOffset--; selected = LocalDate.now().minusDays(7L * weekOffset).toEpochDay() })
                }
            }
            Spacer(Modifier.height(14.dp))
            s.pets.forEach { p ->
                val st = dayStats(s.readings(p.id), s.durations(p.id), day)
                val pats = rememberPatterns(s, p.id, listOf(day))[day].orEmpty()
                var shown by remember(day) { mutableIntStateOf(4) }
                WCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.image, 44.dp)
                        Text(p.name, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    val (wv, wu) = splitWeight(st.meanLbs)
                    Row {
                        RecapStat(wv, wu, "Weight", Modifier.weight(1f))
                        RecapStat("${st.visits.size}", "", "Visits", Modifier.weight(1f))
                    }
                    Row {
                        RecapStat(st.totalMinutes?.let { "%.0f".format(it) } ?: "—", "mins", "Duration", Modifier.weight(1f))
                        val peak = st.peakHour()?.let(::hourLabel)
                        RecapStat(peak?.dropLast(2) ?: "—", peak?.takeLast(2) ?: "", "Peak Visit Time", Modifier.weight(1f))
                    }
                    pats.forEach { PatternLine(it) }
                    if (st.visits.isNotEmpty()) {
                        Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(W.Row).padding(14.dp)) {
                            Text("Visit Breakdown", color = W.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            st.visits.sortedByDescending { it.ts }.take(shown).forEach { v ->
                                Row(Modifier.fillMaxWidth().clickable { nav.navigate("visit/${p.id}/${v.ts}") }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(3.dp).height(20.dp).background(W.Text))
                                    Text(fmt(v.ts, "h:mma").lowercase() + (v.minutes?.let { " for %.0fmin".format(it.coerceAtLeast(1.0)) } ?: ""),
                                        color = W.Text, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                    Text(Repo.weight(v.lbs), color = W.Text2, fontSize = 12.sp)
                                }
                            }
                            if (st.visits.size > shown) Row(Modifier.clickable { shown = st.visits.size }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("See More", color = W.Accent, fontSize = 12.sp); Icon(Icons.Filled.ExpandMore, null, tint = W.Accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
    if (settings) RecapSettingsDialog { settings = false }
}

@Composable
private fun RecapStat(value: String, unit: String, label: String, modifier: Modifier) = Column(modifier.padding(vertical = 6.dp)) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)) { append(value) }
        withStyle(SpanStyle(fontSize = 12.sp, color = W.Text2)) { append(unit) }
    }, color = W.Text)
    Text(label, color = W.Text2, fontSize = 12.sp)
}

@Composable
private fun RecapSettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalPlatform.current
    val a = Repo.alerts.collectAsState().value
    fun update(n: io.github.gccammelli.meowstache.AlertSettings) { Repo.setAlerts(n); ctx.scheduleAlerts() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text("Recap notifications", color = W.Text) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily recap", color = W.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(a.recapEnabled, { update(a.copy(recapEnabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = W.Blue))
                }
                Text("A summary of yesterday for every pet, each morning.", color = W.Text2, fontSize = 12.sp)
                if (a.recapEnabled) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 8, 9, 10).forEach { h ->
                        Text(hourLabel(h), color = if (h == a.recapHour) Color(0xFF2B2B2B) else W.Text, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (h == a.recapHour) W.Accent else W.Inner)
                                .clickable { update(a.copy(recapHour = h)) }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pattern alerts", color = W.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(a.patternsEnabled, { update(a.copy(patternsEnabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = W.Blue))
                }
                Text("Notify when a day is significantly different from normal.", color = W.Text2, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = W.Accent) } })
}

// ---------- Visit Summary ----------

@Composable
fun VisitSummaryScreen(nav: NavHostController, petId: String, ts: Long) {
    val s = state()
    val p = s.pet(petId) ?: return
    val v = s.readings(petId).firstOrNull { it.ts == ts } ?: return
    val minutes = s.durations(petId)[ts]
    val robot = s.robots.firstOrNull()
    Screen({ BackTopBar("Visit Summary", { nav.popBackStack() }) }) {
        Scroll {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RobotArt(52.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(robot?.name ?: "Litter-Robot", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(fmt(ts, "dd/MM h:mma").lowercase(), color = W.Text2, fontSize = 12.sp)
                }
            }
            Text("Bathroom Overview", color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 14.dp))
            WCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = W.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Avatar(p.image, 48.dp)
                }
                Row(Modifier.padding(top = 12.dp)) {
                    RecapStat(splitWeight(v.lbs).first, splitWeight(v.lbs).second, "Weight", Modifier.weight(1f))
                    RecapStat(minutes?.let { "%.1f".format(it) } ?: "—", "min", "Duration", Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WaterDrop, null, tint = W.Muted, modifier = Modifier.size(20.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("Waste analysis (urine / feces)", color = W.Muted, fontSize = 12.sp); NotAvailable()
                    }
                }
            }
            Text("Pictures & Videos", color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 14.dp))
            WCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Videocam, null, tint = W.Muted, modifier = Modifier.size(22.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("Needs a camera model (Litter-Robot 5 Pro)", color = W.Muted, fontSize = 12.sp); NotAvailable()
                    }
                }
            }
        }
    }
}
