package io.github.gccammelli.meowstache.ui

import io.github.gccammelli.meowstache.*
import kotlinx.datetime.daysUntil

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.foundation.layout.width
import io.github.gccammelli.meowstache.has
import io.github.gccammelli.meowstache.haState
import io.github.gccammelli.meowstache.haHidden
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import io.github.gccammelli.meowstache.Act
import io.github.gccammelli.meowstache.Repo
import io.github.gccammelli.meowstache.Robot
import io.github.gccammelli.meowstache.actLabel
import io.github.gccammelli.meowstache.buckets
import io.github.gccammelli.meowstache.s
import io.github.gccammelli.meowstache.i
import io.github.gccammelli.meowstache.o
import io.github.gccammelli.meowstache.b
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import io.github.gccammelli.meowstache.countSeries
import io.github.gccammelli.meowstache.localDate
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

@Composable
fun RobotScreen(nav: NavHostController, serial: String) {
    val s = state()
    val robot = s.robots.firstOrNull { it.serial == serial } ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(W.Bg)) {
        Box(Modifier.weight(1f)) {
            Screen({
                BackTopBar(robot.model, { nav.popBackStack() }) {
                    Icon(Icons.Outlined.Settings, "Settings", tint = W.Text, modifier = Modifier.size(26.dp).clickable { nav.navigate("robotSettings/$serial") })
                }
            }) {
                when (tab) {
                    0 -> StatusTab(nav, robot)
                    1 -> HistoryTab(robot)
                    else -> ControlsTab(robot)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)).background(W.Nav)
                .navigationBarsPadding().padding(vertical = 10.dp),
        ) {
            listOf("Status" to Icons.Outlined.MonitorHeart, "History" to Icons.Outlined.History, "Controls" to Icons.Outlined.Tune).forEachIndexed { i, (l, ic) ->
                Column(Modifier.weight(1f).clickable { tab = i }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ic, l, tint = if (i == tab) W.Accent else W.Muted, modifier = Modifier.size(30.dp))
                    Text(l, color = if (i == tab) W.Text else W.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

// ---------- Status ----------

@Composable
private fun StatusTab(nav: NavHostController, r: Robot) {
    val s = state()
    var scoopsInfo by remember { mutableStateOf(false) }
    var scaleInfo by remember { mutableStateOf(false) }
    var hopper by remember { mutableStateOf(false) }
    Scroll {
        RobotCard(r, null)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WCard(Modifier.weight(1f).height(106.dp), onClick = { hopper = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LitterHopper™", color = W.Text, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); Chevron()
                }
                Spacer(Modifier.height(18.dp))
                val (label, color) = hopperLabel(r)
                StatusLine(label to color, 17)
            }
            WCard(Modifier.weight(1f).height(106.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scoops Saved", color = W.Text, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Info, "Info", tint = W.Accent, modifier = Modifier.size(20.dp).clickable { scoopsInfo = true })
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = W.Green, modifier = Modifier.size(16.dp))
                    Text("%,d".format(Repo.scoopsSaved(r)), color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        WCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SmartScale®", color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Outlined.Info, "How SmartScale works", tint = W.Accent, modifier = Modifier.padding(start = 8.dp).size(20.dp).clickable { scaleInfo = true })
            }
            Spacer(Modifier.height(20.dp))
            s.pets.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { p ->
                        WCard(Modifier.weight(1f), color = W.Inner, onClick = { nav.navigate("scale/${p.id}") }) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Avatar(p.image, 30.dp); Spacer(Modifier.weight(1f)); Chevron() }
                            Text(p.name, color = W.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Text(Repo.weight(yesterdayLbs(s.readings(p.id))), color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            Text("*Yesterday’s weight", color = W.Text2, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
    if (scoopsInfo) InfoDialog("Scoops Saved", "Every completed clean cycle is a scoop you didn't have to do by hand.") { scoopsInfo = false }
    if (scaleInfo) InfoDialog("How SmartScale® works", "The Litter-Robot weighs your cat on every visit and assigns the reading to the pet with the closest reference weight.") { scaleInfo = false }
    if (hopper) {
        val ctx = LocalPlatform.current; val scope = rememberCoroutineScope()
        val enable = r.hopperRemoved || r.hopperStatus == "DISABLED"
        ConfirmDialog("LitterHopper™", "Status: ${hopperLabel(r).first}\n\n${if (enable) "Enable the LitterHopper so it refills litter automatically?" else "Disable the LitterHopper?"}",
            if (enable) "Enable" else "Disable", {
                scope.launch { Repo.toggleHopper(r, removed = !enable).onFailure { ctx.toast("Failed: ${it.message}") } }
            }, { hopper = false })
    }
}

private fun hopperLabel(r: Robot): Pair<String, Long> = when {
    r.hopperRemoved || r.hopperStatus == "DISABLED" -> "Disabled" to 0xFF9E9E9E
    r.hopperStatus == "ENABLED" || r.hopperStatus == "READY" -> "Ready" to 0xFF2E9BF0
    r.hopperStatus == "EMPTY" || r.hopperStatus == "LITTER_LOW" -> "Empty" to 0xFFFBC02D
    r.hopperStatus == null -> "Not installed" to 0xFF9E9E9E
    else -> "Fault" to 0xFFE5534B
}

// ---------- History ----------

@Composable
private fun HistoryTab(r: Robot) {
    val s = state(); val ctx = LocalPlatform.current; val scope = rememberCoroutineScope()
    val acts = s.acts[r.serial].orEmpty()
    val visible = remember(acts) { acts.filter { actLabel(it) != null } }
    val clips by Repo.clips.collectAsState()
    // Opens the visit's video (GitHub #3) in the phone's player or browser, through a link Home Assistant signs.
    fun play(c: io.github.gccammelli.meowstache.Clip) = scope.launch {
        runCatching { Repo.clipUrl(c) }.onSuccess(ctx::playVideo).onFailure { ctx.toast("Couldn't open the video: ${it.message}") }
    }
    // Each weighing's visit length (same estimate as the daily recap), keyed by its time (GitHub #2).
    val visitMin = remember(acts, r.waitMinutes) {
        visitDurations(acts.filter { it.value == "catWeight" }.map { Reading(it.ts, it.actionValue?.toDoubleOrNull() ?: 0.0) }, acts, r.waitMinutes)
    }
    val cycles = remember(acts) { acts.filter { it.value == "robotCycleStatusIdle" }.map { it.ts } }
    var shown by remember { mutableIntStateOf(5) }
    var menuFor by remember { mutableStateOf<Act?>(null) }
    Scroll {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RobotArt(64.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(r.name, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Last updated: ${if (s.updatedAt > 0) fmtAt(s.updatedAt) else "—"}", color = W.Text2, fontSize = 13.sp)
            }
        }
        Text("Cycle Overview", color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 18.dp))
        val earliest = cycles.minOrNull()?.localDate() ?: r.setup?.localDate()
        RangeChartCard(earliest) { b, range ->
            // The official app's cycle chart week ends today, unlike the pet charts.
            val bb = if (range == io.github.gccammelli.meowstache.Range.WEEK) buckets(range, LocalDate.now().plusDays(1), null) else b
            Chart(countSeries(cycles, bb).map { it.toDouble() }, bb.map { it.label }, ChartKind.AREA)
            Legend("Cycles" to W.Green)
            val today = LocalDate.now()
            Row(Modifier.padding(top = 16.dp)) {
                CycleStat("${cycles.count { it.localDate() == today.minusDays(1) }}", "Cycles Yesterday", Modifier.weight(1f))
                CycleStat("${cycles.count { it.localDate() > today.minusDays(7) }}", "Cycles This Week", Modifier.weight(1f))
            }
            CycleStat("%,d".format(cycles.size), "Cycles on record", Modifier.padding(top = 8.dp))
        }
        Text("Device Activity", color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 18.dp))
        WCard(Modifier.fillMaxWidth()) {
            visible.take(shown).forEach { a -> ActivityRow(a, if (a.value == "catWeight") Repo.petForWeightAct(a) else null, visitMin[a.ts],
                clipFor(a.ts, clips)?.takeIf { a.value == "catWeight" }?.let { c -> { play(c) } }) { menuFor = a }; Spacer(Modifier.height(10.dp)) }
            if (visible.isEmpty()) Text(if (s.historyLoading) "Loading full history…" else "No activity yet", color = W.Muted)
            if (visible.size > shown) Text("See More", color = W.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { shown += 25 }.padding(12.dp))
            if (shown > 5) Text("See Less", color = W.Text2, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { shown = 5 }.padding(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        // CSV for spreadsheets: only ever add columns at the end, so existing sheets and formulas keep working.
        Pill("Download Activity", {
            ctx.shareFile("text/csv", "${r.name}-activity.csv", "timestamp,event,label,value,pet,visit_min\n" + acts.joinToString("\n") { a ->
                "${Instant.ofEpochMilli(a.ts)},${a.value},${actLabel(a) ?: ""},${a.actionValue ?: ""},${if (a.value == "catWeight") Repo.petForWeightAct(a)?.name ?: "" else ""},${visitMin[a.ts]?.let { "%.1f".format(it) } ?: ""}"
            })
        }, Modifier.fillMaxWidth(), icon = { Icon(Icons.Filled.FileDownload, null, tint = Color(0xFF222222)) })
    }
    var reassign by remember { mutableStateOf<Long?>(null) }
    var unassign by remember { mutableStateOf<Long?>(null) }
    menuFor?.let { a ->
        val key = Repo.readingKey(a)
        AlertDialog(onDismissRequest = { menuFor = null }, containerColor = W.Card,
            title = { Text("Weight recorded - ${Repo.weight(a.actionValue?.toDoubleOrNull())}", color = W.Text, fontSize = 18.sp) },
            text = {
                Column {
                    Text("Reassign weight", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { menuFor = null; reassign = key }.padding(vertical = 12.dp))
                    Text("Unassign weight", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { menuFor = null; unassign = key }.padding(vertical = 12.dp))
                    if (key in Repo.local.value.overrides) Text("Undo my change", color = W.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().clickable { menuFor = null; Repo.updateLocal { it.copy(overrides = it.overrides - key) } }.padding(vertical = 12.dp))
                    Text("Changes are kept on this phone and update every chart and alert here; the official app keeps its own assignment.",
                        color = W.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close", color = W.Text2) } })
    }
    reassign?.let { key ->
        ChoiceDialog("Assign to", s.pets.map { it.name }, emptySet(), { reassign = null }) { pick ->
            s.pets.firstOrNull { it.name in pick }?.let { Repo.reassign(key, it.id) }
        }
    }
    unassign?.let { key ->
        ConfirmDialog("Unassign weight?", "This reading won't count for any pet.", "Unassign", { Repo.reassign(key, "") }, { unassign = null })
    }
}

@Composable
private fun CycleStat(value: String, label: String, modifier: Modifier = Modifier) = Column(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleIcon(Icons.Filled.Autorenew, W.Green, Color(0xFF2B2B2B), 34.dp)
        Text(value, color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
    Text(label, color = W.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ActivityRow(a: Act, pet: io.github.gccammelli.meowstache.Pet?, visitMinutes: Double?, onPlay: (() -> Unit)?, onMenu: () -> Unit) {
    val label = actLabel(a) ?: return
    if (a.value == "catWeight") {
        val lbs = a.actionValue?.toDoubleOrNull()
        EventRow(
            leading = { if (pet != null) Avatar(pet.image, 40.dp) else CircleIcon(Icons.Filled.Pets, W.Green, Color(0xFF2B2B2B), 40.dp) },
            title = {
                Text(buildAnnotatedString {
                    append("Weight recorded - "); withStyle(SpanStyle(color = W.Accent)) { append(Repo.weight(lbs).replace(" ", "")) }
                }, color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            },
            lines = listOfNotNull(pet?.name ?: "Unassigned", visitMinutes?.let { "Visit: ≈ ${minSec(it)}" }, fmtAt(a.ts)),
            trailing = {
                if (onPlay != null) Icon(Icons.Filled.PlayCircle, "Play the visit's video", tint = W.Accent, modifier = Modifier.clickable(onClick = onPlay).padding(8.dp))
                Icon(Icons.Filled.MoreHoriz, "Show menu", tint = W.Text, modifier = Modifier.clickable(onClick = onMenu).padding(8.dp))
            },
        )
    } else {
        val icon = when (a.value) {
            "robotCycleStatusIdle" -> Icons.Filled.Autorenew
            "litterHopperDispensed" -> Icons.Filled.Grain
            "robotStatusCatDetect" -> Icons.Filled.Pets
            else -> Icons.Filled.Warning
        }
        EventRow(
            leading = { CircleIcon(icon, W.Green, Color(0xFF2B2B2B), 40.dp) },
            title = { Text(label, color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold) },
            lines = listOf(fmtAt(a.ts)),
        )
    }
}

// ---------- Controls ----------

private data class Pending(val title: String, val text: String, val confirm: String, val command: String, val value: String? = null)

@Composable
private fun ControlsTab(r: Robot) {
    val ctx = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }
    var lights by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(false) }
    val filterAt = Repo.local.collectAsState().value.filterAt[r.serial]
    var na by remember { mutableStateOf<String?>(null) }
    fun run(cmd: String, value: String? = null) = scope.launch {
        Repo.command(r, cmd, value).onFailure { ctx.toast("Command failed: ${it.message}") }
            .onSuccess { ctx.toast("Sent") }
    }
    Scroll {
        WCard(Modifier.fillMaxWidth(), pad = 22.dp) {
            Row {
                ControlButton(Icons.Filled.PowerSettingsNew, "Power", if (r.isOn) "On" else "Off", if (r.isOn) W.Green else W.Muted, Modifier.weight(1f)) {
                    pending = if (r.isOn) Pending("Turn off?", "Turn ${r.name} off? It won't cycle until you turn it back on.", "Turn Off", "powerOff")
                    else Pending("Turn on?", "Turn ${r.name} on?", "Turn On", "powerOn")
                }
                ControlButton(if (r.panelLocked) Icons.Filled.Lock else Icons.Filled.LockOpen, "Panel Lock", if (r.panelLocked) "On" else "Off",
                    if (r.panelLocked) W.Green else W.Icon, Modifier.weight(1f)) {
                    pending = if (r.panelLocked) Pending("Unlock panel?", "Re-enable the buttons on ${r.name}?", "Unlock", "keyPadLockOutOff")
                    else Pending("Lock panel?", "Disable the buttons on ${r.name}?", "Lock", "keyPadLockOutOn")
                }
            }
            Spacer(Modifier.height(24.dp))
            Row {
                val mode = r.nightLightMode
                ControlButton(Icons.Filled.Lightbulb, "Lights", when (mode) { "OFF" -> "Off"; "AUTO" -> "Auto"; else -> "On" },
                    if (mode == "OFF") W.Icon else W.Green, Modifier.weight(1f)) { lights = true }
                ControlButton(Icons.Filled.Bedtime, "Sleep Mode", if (r.sleepEnabled) "Enabled" else "Disabled", if (r.sleepEnabled) W.Green else W.Icon,
                    Modifier.weight(1f)) { sleep = true }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp).height(1.dp).background(W.Line))
            Row {
                ControlButton(Icons.Filled.Air, "Replace Filter", filterAt?.let { "Replaced ${it.localDate().daysUntil(LocalDate.now())} days ago" },
                    W.Icon, Modifier.weight(1f)) { filter = true }
                ControlButton(Icons.Filled.Autorenew, "Manual Cycle", null, W.Icon, Modifier.weight(1f)) {
                    pending = Pending("Start a clean cycle?", "Make sure no cat is inside ${r.name}.", "Start", "cleanCycle")
                }
            }
            Spacer(Modifier.height(24.dp))
            Row {
                ControlButton(Icons.Filled.RestartAlt, "Reset Robot", null, W.Icon, Modifier.weight(1f)) {
                    pending = Pending("Reset robot?", "This clears errors and may start a cycle. Make sure the globe is clear.", "Reset", "shortResetPress")
                }
                if (r.has("button.empty_litter")) ControlButton(Icons.Filled.DeleteSweep, "Empty Litter", null, W.Icon, Modifier.weight(1f)) {
                    pending = Pending("Empty all litter?", "${r.name} turns to pour all of its litter into the waste drawer, e.g. to change litter. " +
                        "Make sure no cat is inside and the drawer has room.", "Empty", "emptyLitter")
                }
            }
        }
    }
    pending?.let { p -> ConfirmDialog(p.title, p.text, p.confirm, { run(p.command, p.value) }, { pending = null }) }
    na?.let { InfoDialog("Not available", it) { na = null } }
    if (lights) LightsDialog(r, { lights = false }) { c, v -> run(c, v) }
    if (sleep) { if (Repo.localMode && r.has("switch.monday_sleep_schedule")) LocalSleepDialog(r) { sleep = false } else SleepDialog(r) { sleep = false } }
    val robotFilter = r.has("button.replace_filter")
    if (filter) ConfirmDialog("Replace Filter", "Mark the carbon filter as replaced today? A new filter every 3 months is recommended. " +
        if (robotFilter) "This also starts the robot's own filter replacement mode (the firmware's Replace Filter)." else "This is tracked on this phone; the robot's own filter reminder isn't reachable through the public API.", "Replaced",
        { Repo.updateLocal { it.copy(filterAt = it.filterAt + (r.serial to nowMs())) }; if (robotFilter) run("replaceFilter") }, { filter = false })
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, state: String?, tint: Color, modifier: Modifier, notAvailable: Boolean = false, onClick: () -> Unit) =
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(74.dp).cardShadow(CircleShape).background(W.Well, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (notAvailable) W.Muted else tint, modifier = Modifier.size(38.dp))
        }
        Text(label, color = if (notAvailable) W.Muted else W.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        state?.let { Text(it, color = W.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        if (notAvailable) NotAvailable()
    }

// ---------- Device settings ----------

@Composable
fun DeviceSettingsScreen(nav: NavHostController, serial: String) {
    val s = state()
    val r = s.robots.firstOrNull { it.serial == serial } ?: return
    val ctx = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var delay by remember { mutableStateOf(false) }
    var litter by remember { mutableStateOf(false) }
    var scoops by remember { mutableStateOf(false) }
    val lit = Repo.local.collectAsState().value.litter[r.serial]
    Screen({ BackTopBar("Device Settings", { nav.popBackStack() }) }) {
        Scroll {
            WCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        StatusLine(r.status, 12)
                        Text("SN: ${r.serial}", color = W.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    RobotArt(104.dp)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(W.Line))
                ListRow("Warranty", onClick = null)
            }
            Spacer(Modifier.height(14.dp))
            WCard(Modifier.fillMaxWidth()) {
                ListRow("Device Nickname", "Name: ${r.name}", { rename = true })
                ListRow("Update Network", "Signal Strength: ${Repo.signal(r.wifiRssi)}", null)
                ListRow("Firmware Information", "Version: ${r.firmware}", { nav.navigate("firmware") })
            }
            Spacer(Modifier.height(14.dp))
            WCard(Modifier.fillMaxWidth()) {
                ListRow("Litter", lit?.let { "Brand: ${it.brand.ifBlank { "—" }}" } ?: "Not set", { litter = true },
                    extra = lit?.formulation?.ifBlank { null }?.let { "Formulation: $it" })
                ListRow("Cycle Delay", "Time: ${r.waitMinutes} minutes", { delay = true })
                ListRow("Device Floor Surface", "Floor Surface: ${when (r.surface) { "TILE" -> "Wood/Tile"; "CARPET" -> "Carpet"; else -> "Unknown" }}", null)
            }
            val firmware = listOf(
                Triple("switch.smart_cat_detection", "Smart Cat Detection", "Only counts a visit when both the weight and curtain sensors see a cat: fewer false cycles."),
                Triple("switch.auto_cycle", "Auto Cycle", "Off: the robot doesn't clean after a visit; start cycles yourself (for jumpy cats or kittens)."),
                Triple("switch.odor_control", "Odor Control", "Cycles sooner after a poop (WasteSense sensor)."),
                Triple("switch.sleep_override", "Sleep Override", "Keeps the robot asleep until turned off. It sets every day's schedule to sleep; turning it off switches all days off, so set your schedule again."),
            ).filter { r.has(it.first) }
            // The firmware ships these disabled in Home Assistant; say what to enable instead of hiding the feature.
            val hidden = r.haHidden()
            val missing = listOfNotNull(
                "sleep schedule (the 7 \"… Sleep Schedule\" switches and 14 sleep/wake times)".takeIf { hidden.any { "sleep_schedule" in it || "sleep_time" in it || "wake_time" in it } },
                "LitterHopper on/off (\"Hopper\")".takeIf { "switch.hopper" in hidden },
                "Auto Cycle".takeIf { "switch.auto_cycle" in hidden },
            )
            if (Repo.localMode && missing.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                WCard(Modifier.fillMaxWidth()) {
                    Text("More controls in Home Assistant", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("These are disabled in Home Assistant, so Meowstache can't use them yet: ${missing.joinToString("; ")}. " +
                        "Enable them on the robot's device page (Settings › Devices › ${r.name} › entities not shown), then reopen Meowstache.",
                        color = W.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (firmware.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                WCard(Modifier.fillMaxWidth()) {
                    Text("ESPHome firmware", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    firmware.forEach { (role, label, hint) ->
                        val on = r.haState(role) == "on"
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(label, color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(hint, color = W.Text2, fontSize = 12.sp)
                            }
                            androidx.compose.material3.Switch(on, { v ->
                                scope.launch { Repo.haCall(r, role, if (v) "turn_on" else "turn_off").onFailure { ctx.toast("Failed: ${it.message}") } }
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            WCard(Modifier.fillMaxWidth()) {
                ListRow("Reset Scoops Saved", null, { scoops = true })
                ListRow("Reset to Factory Settings", null, null)
                ListRow("Delete Robot", null, null, titleColor = W.Red)
            }
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(r.name) }
        AlertDialog(onDismissRequest = { rename = false }, containerColor = W.Card,
            title = { Text("Device Nickname", color = W.Text) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    rename = false
                    scope.launch { Repo.rename(r, name.trim()).onFailure { ctx.toast("Rename failed: ${it.message}") } }
                }) { Text("Save", color = W.Accent) }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel", color = W.Text2) } })
    }
    if (delay) CycleDelaySheet(r.waitMinutes, { delay = false }) { m ->
        scope.launch {
            Repo.command(r, "setClumpTime", "{\"clumpTime\": $m}")
                .onFailure { ctx.toast("Failed: ${it.message}") }
        }
    }
    if (litter) LitterDialog(lit ?: io.github.gccammelli.meowstache.Litter(), { litter = false }) { l ->
        Repo.updateLocal { it.copy(litter = it.litter + (r.serial to l)) }
    }
    if (scoops) ConfirmDialog("Reset Scoops Saved?", "Start counting scoops saved from zero again. The count is reset on this phone only.", "Reset",
        { Repo.updateLocal { it.copy(scoopsBase = it.scoopsBase + (r.serial to r.scoopsSaved)) } }, { scoops = false })
}

/** The official app's bottom sheet: ‹ 6 7 [8] 9 10 › stepper over 3–30 minutes, saved only on Save. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CycleDelaySheet(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var m by remember { mutableIntStateOf(current.coerceIn(3, 30)) }
    androidx.compose.material3.ModalBottomSheet(onDismiss, containerColor = W.pick(0xFF434343, 0xFFFCFCFC)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Cycle Delay", color = W.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp).height(1.dp).background(W.Line))
            Text("Minutes to wait after a cat leaves before the robot cleans.", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Shorter" to "Cleans sooner, less smell", "Longer" to "Firmer clumps, better for several cats").forEach { (t, d) ->
                    Column(Modifier.weight(1f).border(1.dp, W.pick(0xFF5A5A5A, 0xFFD3D3D3), RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Text(t, color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(d, color = W.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Shorter", tint = if (m > 3) W.Accent else W.Muted,
                    modifier = Modifier.size(36.dp).clickable(enabled = m > 3) { m-- })
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    (m - 2..m + 2).forEach { v ->
                        val d = kotlin.math.abs(v - m)
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(if (d == 0) Color(0xFF2B2B2B) else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            if (v in 3..30) Text("$v", color = if (d == 0) Color.White else W.Text2.copy(alpha = if (d == 1) 1f else 0.6f),
                                fontSize = if (d == 0) 16.sp else 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Longer", tint = if (m < 30) W.Accent else W.Muted,
                    modifier = Modifier.size(36.dp).clickable(enabled = m < 30) { m++ })
            }
            Text("Minutes", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            Pill("Save", { onSave(m); onDismiss() }, Modifier.fillMaxWidth().padding(horizontal = 40.dp), filled = true, enabled = m != current)
            Spacer(Modifier.height(12.dp))
            Pill("Cancel", onDismiss, Modifier.fillMaxWidth().padding(horizontal = 40.dp))
        }
    }
}

/** Litter brand and formulation; the official service stores these on its servers, here they're kept on the phone. */
@Composable
private fun LitterDialog(l: io.github.gccammelli.meowstache.Litter, onDismiss: () -> Unit, onSave: (io.github.gccammelli.meowstache.Litter) -> Unit) {
    var brand by remember { mutableStateOf(l.brand) }
    var form by remember { mutableStateOf(l.formulation) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text("Litter", color = W.Text) },
        text = {
            Column {
                OutlinedTextField(brand, { brand = it }, label = { Text("Litter Brand") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(form, { form = it }, label = { Text("Litter Formulation") }, singleLine = true)
                Text("Saved on this phone.", color = W.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(io.github.gccammelli.meowstache.Litter(brand.trim(), form.trim())); onDismiss() }) { Text("Save", color = W.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Text2) } })
}

/** The official app's Lights dialog; Set sends only what changed (commands from pylitterbot). */
@Composable
private fun LightsDialog(r: Robot, onDismiss: () -> Unit, run: (String, String?) -> Unit) {
    val modes = listOf("ON" to "nightLightModeOn", "OFF" to "nightLightModeOff", "AUTO" to "nightLightModeAuto")
    var mode by remember { mutableIntStateOf(modes.indexOfFirst { it.first == r.nightLightMode }.coerceAtLeast(0)) }
    // Both brightness fields hold 25 / 50 / 100.
    fun level(v: Int?) = when { v == null -> -1; v <= 33 -> 0; v <= 66 -> 1; else -> 2 }
    val globe0 = level(r.raw.i("nightLightBrightness")); val panel0 = level(r.raw.i("panelBrightnessHigh"))
    var globe by remember { mutableIntStateOf(globe0) }
    var panel by remember { mutableIntStateOf(panel0) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text("Lights", color = W.Text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Globe Light", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                Segmented(listOf("On", "Off", "Auto"), mode, { mode = it }, dark = true)
                Text("Globe Brightness", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                Segmented(listOf("Low", "Medium", "High"), globe, { globe = it }, dark = true)
                Text("Panel Brightness", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                Segmented(listOf("Low", "Medium", "High"), panel, { panel = it }, dark = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (modes[mode].first != r.nightLightMode) run(modes[mode].second, null)
            if (globe != globe0 && globe >= 0) run("setNightLightValue", "{\"nightLightPower\": ${listOf(25, 50, 100)[globe]}}")
            if (panel != panel0 && panel >= 0) run(listOf("panelBrightnessLow", "panelBrightnessMed", "panelBrightnessHigh")[panel], null)
            onDismiss()
        }) { Text("Set", color = W.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Text2) } })
}

/** Local mode: the ESPHome firmware's per-day sleep schedule, editable (each day on/off, sleep and wake time). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LocalSleepDialog(r: Robot, onDismiss: () -> Unit) {
    val ctx = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val o = r.raw.o("weekdaySleepModeEnabled")
    val start = names.associateWith { d -> Triple(o?.o(d)?.b("isEnabled") == true, o?.o(d)?.i("sleepTime") ?: 22 * 60, o?.o(d)?.i("wakeTime") ?: 6 * 60) }
    var days by remember { mutableStateOf(start) }
    var editing by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // day, true = sleep time
    fun hhmm(m: Int) = "%02d:%02d".format(m / 60 % 24, m % 60)
    AlertDialog(onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text("Sleep Mode", color = W.Text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column {
                names.forEach { d ->
                    val (on, sl, wk) = days.getValue(d)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(d.take(3), color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                        Text(hhmm(sl), color = if (on) W.Accent else W.Muted, fontSize = 15.sp, modifier = Modifier.clickable { editing = d to true }.padding(8.dp))
                        Text("–", color = W.Text2)
                        Text(hhmm(wk), color = if (on) W.Accent else W.Muted, fontSize = 15.sp, modifier = Modifier.clickable { editing = d to false }.padding(8.dp))
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.Switch(on, { days = days + (d to Triple(it, sl, wk)) })
                    }
                }
                Text("The robot doesn't cycle while asleep. Tap a time to change it.", color = W.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = {
            val changed = days.filter { (d, v) -> v != start[d] }
            onDismiss()
            Repo.launch {
                for ((d, v) in changed) Repo.setSleep(r, d, v.first, v.second, v.third).onFailure { ctx.toast("Failed: ${it.message}"); return@launch }
                if (changed.isNotEmpty()) ctx.toast("Sleep schedule saved")
            }
        }) { Text("Save", color = W.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Text2) } })
    editing?.let { (d, isSleep) ->
        val (on, sl, wk) = days.getValue(d)
        val m = if (isSleep) sl else wk
        val st = androidx.compose.material3.rememberTimePickerState(m / 60, m % 60, is24Hour = true)
        AlertDialog(onDismissRequest = { editing = null }, containerColor = W.Card,
            title = { Text("$d ${if (isSleep) "sleep" else "wake"} time", color = W.Text) },
            text = { androidx.compose.material3.TimeInput(st) },
            confirmButton = { TextButton(onClick = {
                val t = st.hour * 60 + st.minute
                days = days + (d to if (isSleep) Triple(true, t, wk) else Triple(true, sl, t))
                editing = null
            }) { Text("OK", color = W.Accent) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel", color = W.Text2) } })
    }
}

/** The robot's sleep schedule as the official app shows it (weekdays / weekends). Read-only: the edit mutation isn't known. */
@Composable
private fun SleepDialog(r: Robot, onDismiss: () -> Unit) {
    val days = r.raw.o("weekdaySleepModeEnabled")
    fun day(n: String) = days?.o(n)
    fun hhmm(m: Int?) = m?.let { "%02d:%02d".format(it / 60 % 24, it % 60) } ?: "00:00"
    AlertDialog(onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text("Sleep Mode", color = W.Text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column {
                listOf("Weekdays" to "Monday", "Weekends" to "Saturday").forEach { (label, d) ->
                    val o = day(d)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(o?.b("isEnabled") == true, null, enabled = false)
                    }
                    Row(Modifier.padding(vertical = 10.dp)) {
                        listOf("Start Time" to o?.i("sleepTime"), "End Time" to o?.i("wakeTime")).forEach { (t, v) ->
                            Column(Modifier.weight(1f)) {
                                Text(t, color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(hhmm(v), color = W.Text, fontSize = 12.sp)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(1.dp).background(W.Line))
                }
                Text(if (Repo.localMode) "To change the schedule here, enable the robot's sleep schedule entities in Home Assistant (they're disabled by default)."
                    else "Editing the schedule isn't available through the public API yet.", color = W.Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = W.Accent) } })
}
