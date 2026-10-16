package io.github.gccammelli.meowstache.ui

import io.github.gccammelli.meowstache.ESPHOME_FIRMWARE_URL
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import io.github.gccammelli.meowstache.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import io.github.gccammelli.meowstache.AppState
import io.github.gccammelli.meowstache.Pet
import io.github.gccammelli.meowstache.Range
import io.github.gccammelli.meowstache.Reading
import io.github.gccammelli.meowstache.Repo
import io.github.gccammelli.meowstache.buckets
import io.github.gccammelli.meowstache.countSeries
import io.github.gccammelli.meowstache.dayReadings
import io.github.gccammelli.meowstache.localDate
import io.github.gccammelli.meowstache.rangeTitle
import io.github.gccammelli.meowstache.weightSeries
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import io.github.gccammelli.meowstache.ZoneId
import io.github.gccammelli.meowstache.DateTimeFormatter

// ---------- shared helpers ----------

private val AT = DateTimeFormatter.ofPattern("dd/MM 'at' HH:mm")
fun fmtAt(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(AT)
fun fmt(ts: Long, pattern: String): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))

val yesterday: LocalDate get() = LocalDate.now().minusDays(1)
fun AppState.readings(petId: String): List<Reading> = history[petId].orEmpty()
fun AppState.pet(id: String): Pet? = pets.firstOrNull { it.id == id }

/** The official app's "Yesterday's weight": mean of yesterday's readings, falling back to the latest reading. */
fun yesterdayLbs(r: List<Reading>): Double? =
    dayReadings(r, yesterday).takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.lbs } / l.size } ?: r.lastOrNull()?.lbs

/** Total minutes in the litter box yesterday (the official app's "Yesterday's Duration"). */
fun AppState.yesterdayMinutes(petId: String): Double? {
    val d = durations(petId)
    val y = dayReadings(readings(petId), yesterday).mapNotNull { d[it.ts] }
    return if (y.isEmpty()) null else y.sum()
}

@Composable
fun state() = Repo.state.collectAsState().value

@Composable
fun Screen(top: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(W.Bg)) {
        top()
        val s = state()
        if (s.loading || s.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = W.Accent, trackColor = W.Bg)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun Scroll(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp)) {
        content(); Spacer(Modifier.height(24.dp))
    }

@Composable
fun BigStat(value: String, unit: String, label: String, modifier: Modifier = Modifier) = Column(modifier.padding(vertical = 6.dp)) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)) { append(value) }
        withStyle(SpanStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) { append(unit) }
    }, color = W.Text)
    Text(label, color = W.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

/** Number, unit suffix and number of the given weight in the user's units, e.g. ("4.9", "kg"). */
fun splitWeight(lbs: Double?): Pair<String, String> =
    if (lbs == null) "—" to "" else Repo.num(Repo.weightNum(lbs)) to Repo.unitLabel()

@Composable
fun ErrorBanner() {
    val s = state()
    if (Repo.demo) Text(if (Repo.localMode) "Demo mode: made-up robots on the ESPHome firmware, through a pretend Home Assistant. Log out to connect yours."
        else "Demo mode: a made-up robot and cats. Log out to use your own account.", color = W.Accent, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    s.error?.let { Text("Couldn't refresh: $it", color = W.Red, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
}

// ---------- main tabs ----------

private data class Tab(val label: String, val icon: ImageVector)
private val TABS = listOf(
    Tab("Home", Icons.Filled.Home), Tab("Insights", Icons.Filled.BarChart), Tab("Devices", Icons.Filled.Inventory2),
    Tab("Pets", Icons.Filled.Pets), Tab("Shop", Icons.Filled.ShoppingBag),
)

@Composable
fun MainScreen(nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val unread = Repo.messages.collectAsState().value.any { !it.read }
    Column(Modifier.fillMaxSize().background(W.Bg)) {
        Box(Modifier.weight(1f)) {
            Screen({ MainTopBar(unread, { nav.navigate("messages") }, { nav.navigate("account") }, { tab = 0 }) }) {
                when (tab) {
                    0 -> HomeTab(nav)
                    1 -> InsightsTab(nav)
                    2 -> DevicesTab(nav)
                    3 -> PetsTab(nav)
                    else -> ShopTab()
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)).background(W.Nav)
                .navigationBarsPadding().padding(vertical = 10.dp),
        ) {
            TABS.forEachIndexed { i, t ->
                Column(Modifier.weight(1f).clickable { tab = i }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val c = if (i == tab) W.Accent else W.Muted
                    Icon(t.icon, t.label, tint = c, modifier = Modifier.size(30.dp))
                    Text(t.label, color = if (i == tab) W.Text else W.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeTab(nav: NavHostController) {
    val s = state(); val ctx = LocalPlatform.current
    var devicesGrid by rememberSaveable { mutableStateOf(false) }
    var petsGrid by rememberSaveable { mutableStateOf(true) }
    Scroll {
        ErrorBanner()
        Spacer(Modifier.height(24.dp))
        Greeting(s)
        Spacer(Modifier.height(16.dp))
        SectionHeader("Devices") { LayoutGlyph(!devicesGrid) { devicesGrid = !devicesGrid } }
        if (devicesGrid) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            s.robots.forEach { r ->
                WCard(Modifier.weight(1f), onClick = { nav.navigate("robot/${r.serial}") }) {
                    RobotArt(90.dp, Modifier.align(Alignment.CenterHorizontally))
                    Text(r.name, color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    StatusLine(r.status)
                }
            }
            if (s.robots.size % 2 == 1) Spacer(Modifier.weight(1f))
        } else s.robots.forEach { RobotCard(it) { nav.navigate("robot/${it.serial}") }; Spacer(Modifier.height(14.dp)) }
        if (devicesGrid) Spacer(Modifier.height(14.dp))
        AddCard("Add a Device")
        Spacer(Modifier.height(16.dp))
        SectionHeader("Pets") { LayoutGlyph(!petsGrid) { petsGrid = !petsGrid } }
        if (petsGrid) s.pets.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { HomePetCard(s, it, Modifier.weight(1f)) { nav.navigate("petInsights/${it.id}") } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
        } else s.pets.forEach { HomePetCard(s, it, Modifier.fillMaxWidth()) { nav.navigate("petInsights/${it.id}") }; Spacer(Modifier.height(14.dp)) }
        AddCard("Add a pet")
        Spacer(Modifier.height(20.dp))
        if (Repo.showBlogs.collectAsState().value) {
            SectionHeader("Blogs")
            WCard(pad = 0.dp) {
                Box(Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF2A6F6B)), contentAlignment = Alignment.Center) {
                    RobotArt(140.dp)
                }
                Text("Everyday Pet Care Tips", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
                Pill("Learn more", { ctx.openUrl("https://www.whisker.com/blog") }, Modifier.fillMaxWidth().padding(20.dp), filled = true)
            }
            Spacer(Modifier.height(30.dp))
        }
        WCard(Modifier.fillMaxWidth(), pad = 16.dp, onClick = { ctx.openUrl("https://www.litter-robot.com/support/#contact-us") }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(Icons.Filled.SupportAgent, W.Accent, Color(0xFF3A2A12), 34.dp)
                Text("Litter-Robot Support", color = W.Text, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun Greeting(s: AppState) {
    val h = LocalTime.now().hour
    val (word, icon) = when (h) { in 5..11 -> "morning" to Icons.Filled.WbTwilight; in 12..17 -> "afternoon" to Icons.Filled.WbSunny; else -> "evening" to Icons.Filled.NightsStay }
    val pet = s.pets.takeIf { it.isNotEmpty() }?.let { it[(LocalDate.now().toEpochDay() % it.size).toInt()] }
    WCard(Modifier.fillMaxWidth(), pad = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(pet?.image, 32.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Good $word${if (s.firstName.isNotBlank()) ", ${s.firstName}" else ""}", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Icon(icon, null, tint = W.Accent, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                }
                pet?.let { Text("Tell ${it.name} we said hi!", color = W.Text, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
fun StatusLine(status: Pair<String, Long>, size: Int = 13) = Row(verticalAlignment = Alignment.CenterVertically) {
    StatusDot(Color(status.second), (size + 3).dp)
    Text(status.first, color = W.Text, fontSize = size.sp, modifier = Modifier.padding(start = 8.dp))
}

@Composable
fun RobotCard(r: io.github.gccammelli.meowstache.Robot, onClick: (() -> Unit)?) {
    WCard(Modifier.fillMaxWidth(), onClick = onClick, pad = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RobotArt(108.dp)
            Column(Modifier.padding(start = 20.dp)) {
                Text(r.name, color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                StatusLine(r.status)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Waste:", color = W.Text, fontSize = 13.sp); Spacer(Modifier.height(8.dp))
                        WasteIcon(r.wastePercent); Spacer(Modifier.height(8.dp))
                        Text("${r.wastePercent}%", color = W.Text, fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Litter:", color = W.Text, fontSize = 13.sp); Spacer(Modifier.height(8.dp))
                        LitterIcon(r.litterState); Spacer(Modifier.height(8.dp))
                        Text(r.litterLabel, color = W.Text, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** "Add a Device" / "Add a pet": not possible through the cloud API; in local mode a cat goes on a free tracker slot. */
@Composable
private fun AddCard(label: String) {
    val local = Repo.localMode
    var open by remember { mutableStateOf(false) }
    WCard(Modifier.fillMaxWidth().height(56.dp), pad = 0.dp, onClick = if (local) ({ open = true }) else null) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AddCircle, null, tint = W.Accent.copy(alpha = if (local) 1f else 0.5f), modifier = Modifier.size(24.dp))
            Text(label, color = if (local) W.Text else W.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            if (!local) NotAvailable()
        }
    }
    if (!open) return
    val ctx = LocalPlatform.current
    if (label.contains("Device")) ConfirmDialog("Add a robot",
        "A robot shows up here once it runs the ESPHome firmware and Home Assistant has adopted it (Settings › Devices, it's discovered automatically). " +
            "Flashing needs opening the robot; the firmware's guide has the steps.",
        "Flashing guide", { ctx.openUrl("$ESPHOME_FIRMWARE_URL/src/branch/main/docs/Litter%20Robot%204/Flashing%20Instructions.md") }, { open = false }, dismiss = "Close")
    else AddPetDialog { open = false }
}

/** Local mode: a new cat on a free cat tracker slot of the chosen robots, with a starting weight so visits match it. */
@Composable
private fun AddPetDialog(onDismiss: () -> Unit) {
    val ctx = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val robots = state().robots
    var name by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(robots.map { it.serial }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val lbs = weight.replace(',', '.').toDoubleOrNull()?.let { if (Repo.units.value == "kg") it / io.github.gccammelli.meowstache.LBS_TO_KG else it }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = W.Card,
        title = { Text("Add a cat", color = W.Text) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(weight, { weight = it }, label = { Text("Weight (${Repo.unitLabel()})") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                Text("Uses robots", color = W.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                robots.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(r.serial in on, { on = if (it) on + r.serial else on - r.serial })
                        Text(r.name, color = W.Text, fontSize = 14.sp)
                    }
                }
                Text("Takes a free \"Cat N\" slot of the robot's cat tracker and names it in Home Assistant. The robot then matches visits to this weight.",
                    color = W.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                if (busy) CircularProgressIndicator(color = W.Accent, modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally))
            }
        },
        confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && lbs != null && lbs > 0 && on.isNotEmpty(), onClick = {
            busy = true
            scope.launch {
                Repo.addPet(name.trim(), lbs!!, on).fold(
                    { full -> ctx.toast(if (full.isEmpty()) "${name.trim()} added" else "Added, but no free slot on ${full.joinToString()}") },
                    { ctx.toast("Couldn't add: ${it.message}") })
                busy = false; onDismiss()
            }
        }) { Text("Add", color = W.Accent) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel", color = W.Text2) } })
}

/** Local mode: rename the cat, set its weight in the robots' tracker, or reset the tracker. */
@Composable
private fun LocalPetDialog(p: io.github.gccammelli.meowstache.Pet, onRenamed: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(p.name) }
    var weight by remember { mutableStateOf("") }
    var reset by remember { mutableStateOf(false) }
    val lbs = weight.replace(',', '.').toDoubleOrNull()?.let { if (Repo.units.value == "kg") it / io.github.gccammelli.meowstache.LBS_TO_KG else it }
    AlertDialog(onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text("Edit ${p.name}", color = W.Text) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(weight, { weight = it }, label = { Text("Set tracker weight (${Repo.unitLabel()})") }, singleLine = true,
                    placeholder = { Text(Repo.weight(p.referenceLbs)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                Text("Only if the robot keeps matching visits to the wrong cat: the tracker then starts from this weight.",
                    color = W.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Reset tracker…", color = W.Red, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp).clickable { reset = true })
            }
        },
        confirmButton = { TextButton(onClick = {
            val newName = name.trim()
            onDismiss()
            Repo.launch {
                if (lbs != null && lbs > 0) Repo.setTrackerWeight(p, lbs).onFailure { ctx.toast("Failed: ${it.message}") }
                if (newName.isNotEmpty() && newName != p.name) Repo.renamePet(p, newName)
                    .fold({ ctx.toast("Renamed to $newName"); onRenamed() }, { ctx.toast("Rename failed: ${it.message}") })
            }
        }) { Text("Save", color = W.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = W.Text2) } })
    if (reset) ConfirmDialog("Reset ${p.name}'s tracker?", "The robots forget ${p.name}'s learned weight and zero the visit counters. " +
        "The history on this phone stays. Set a weight afterwards so visits are matched again.", "Reset",
        { Repo.launch { Repo.resetTracker(p).onFailure { ctx.toast("Failed: ${it.message}") } } }, { reset = false })
}

@Composable
private fun HomePetCard(s: AppState, p: Pet, modifier: Modifier, onClick: () -> Unit) {
    val r = s.readings(p.id)
    val (v, u) = splitWeight(yesterdayLbs(r))
    WCard(modifier, onClick = onClick, pad = 14.dp) {
        Avatar(p.image, 40.dp)
        Text(p.name, color = W.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("$v $u", color = W.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        r.lastOrNull()?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(Icons.Filled.Scale, W.Blue, Color(0xFF1E1E1E), 16.dp)
                Text(fmtAt(it.ts), color = W.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

// ---------- Insights ----------

@Composable
private fun InsightsTab(nav: NavHostController) {
    val s = state()
    Scroll {
        ErrorBanner()
        WCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("recap") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarMonth, null, tint = W.Accent, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Daily Recap", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Each pet's day: weight, visits, duration, peak time", color = W.Text2, fontSize = 12.sp)
                }
                Chevron()
            }
        }
        Spacer(Modifier.height(14.dp))
        s.pets.forEach { p ->
            val r = s.readings(p.id)
            val (v, u) = splitWeight(yesterdayLbs(r))
            WCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("petInsights/${p.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.image, 42.dp)
                    Text(p.name, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Chevron()
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    BigStatSmall(v, u, "Yesterday’s weight", Modifier.weight(1f))
                    BigStatSmall("${dayReadings(r, yesterday).size}", "", "Yesterday’s Visits", Modifier.weight(1f))
                }
                BigStatSmall(s.yesterdayMinutes(p.id)?.let { "%.1f".format(it) } ?: "—", "min", "Yesterday's Duration")
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun BigStatSmall(value: String, unit: String, label: String, modifier: Modifier = Modifier) = Column(modifier.padding(vertical = 4.dp)) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)) { append(value) }
        withStyle(SpanStyle(fontSize = 12.sp)) { append(unit) }
    }, color = W.Text)
    Text(label, color = W.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

/** Range tabs + title shared by every history chart. The official app paywalls Month/Year; here they're all open, plus "All". */
@Composable
fun RangeChartCard(
    earliest: LocalDate?,
    title: String? = null,
    header: (@Composable () -> Unit)? = null,
    body: @Composable (List<io.github.gccammelli.meowstache.Bucket>, Range) -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    var offset by rememberSaveable(range) { mutableIntStateOf(0) } // periods back in time (the premium tier's ‹ ›)
    val b = remember(range, earliest, offset) { buckets(range, io.github.gccammelli.meowstache.pagedToday(range, LocalDate.now(), offset), earliest) }
    WCard(Modifier.fillMaxWidth()) {
        title?.let { Text(it, color = W.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp)) }
        WTabs(Range.entries.map { it.label }, range.ordinal, { range = Range.entries[it] })
        PeriodNav(rangeTitle(b), canBack = earliest != null && b.first().start > earliest, canForward = offset > 0,
            { offset++ }, { offset-- }, arrows = range != Range.ALL)
        header?.invoke()
        body(b, range)
    }
}

fun AppState.earliest(petId: String): LocalDate? = readings(petId).firstOrNull()?.ts?.localDate()

@Composable
fun PetInsightsScreen(nav: NavHostController, id: String) {
    val s = state(); val ctx = LocalPlatform.current
    val p = s.pet(id) ?: return
    val r = s.readings(id)
    Screen({ BackTopBar("${p.name}’s Insights", { nav.popBackStack() }) }) {
        Scroll {
            Text("Weight Overview", color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            WCard(Modifier.fillMaxWidth()) { val (v, u) = splitWeight(yesterdayLbs(r)); BigStat(v, u, "Yesterday’s weight") }
            Spacer(Modifier.height(14.dp))
            val patterns = rememberPatterns(s, id, remember { (0..30).map { LocalDate.now().minusDays(it.toLong()) } })
            RangeChartCard(s.earliest(id)) { b, range ->
                val w = weightSeries(r, b).map { it?.let(Repo::weightNum) }
                val nn = w.filterNotNull()
                Chart(w, b.map { it.label }, ChartKind.LINE, average = nn.takeIf { it.isNotEmpty() }?.average(),
                    tooltip = { "${Repo.num(it)} ${Repo.unitLabel()}" }, marks = marksFor(b, range, patterns, io.github.gccammelli.meowstache.Metric.WEIGHT))
                Legend("Weight" to W.Teal, "Average" to W.Purple)
                PatternFooter(b, range, patterns, io.github.gccammelli.meowstache.Metric.WEIGHT)
            }
            Spacer(Modifier.height(28.dp))
            Text("Pet Visits Overview", color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            WCard(Modifier.fillMaxWidth()) {
                val week = r.count { it.ts.localDate() in yesterday.minusDays(6)..yesterday }
                Row {
                    BigStat("${dayReadings(r, yesterday).size}", "", "Yesterday’s Visits", Modifier.weight(1f))
                    BigStat("$week", "", "Weekly Visits", Modifier.weight(1f))
                }
                Row {
                    BigStat(s.yesterdayMinutes(id)?.let { "%.1f".format(it) } ?: "—", "min", "Yesterday's Duration", Modifier.weight(1f))
                    BigStat("${r.size}", "", "All-time Visits", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(14.dp))
            RangeChartCard(s.earliest(id), title = "Litter-Robot Visits") { b, range ->
                val c = countSeries(r.map { it.ts }, b).map { it.toDouble() }
                Chart(c, b.map { it.label }, ChartKind.BARS, average = c.takeIf { it.isNotEmpty() }?.average(),
                    marks = marksFor(b, range, patterns, io.github.gccammelli.meowstache.Metric.VISITS))
                Legend("Visits" to W.Teal, "Average" to W.Purple)
                PatternFooter(b, range, patterns, io.github.gccammelli.meowstache.Metric.VISITS)
            }
            Spacer(Modifier.height(14.dp))
            DurationCard(s, id)
            Spacer(Modifier.height(14.dp))
            TimeOfVisitCard(s, id)
            Spacer(Modifier.height(14.dp))
            PatternsCard(s, id)
            Spacer(Modifier.height(20.dp))
            Pill("Export full history (CSV)", {
                val d = s.durations(id)
                ctx.shareFile("text/csv", "${p.name}-history.csv", "timestamp,weight_lbs,weight_kg,duration_min\n" + r.joinToString("\n") {
                    "${Instant.ofEpochMilli(it.ts)},${it.lbs},${"%.2f".format(it.lbs * io.github.gccammelli.meowstache.LBS_TO_KG)},${d[it.ts]?.let { m -> "%.1f".format(m) } ?: ""}"
                })
            }, Modifier.fillMaxWidth(), icon = { Icon(Icons.Filled.FileDownload, null, tint = Color(0xFF222222)) })
        }
    }
}

// ---------- Devices ----------

@Composable
private fun DevicesTab(nav: NavHostController) {
    val s = state()
    Scroll {
        ErrorBanner()
        s.robots.forEach { RobotCard(it) { nav.navigate("robot/${it.serial}") }; Spacer(Modifier.height(14.dp)) }
        AddCard("Add a Device")
    }
}

// ---------- Pets ----------

fun petSummary(p: Pet) = listOfNotNull(p.breed, listOfNotNull(p.environment, p.genderLabel.ifBlank { null }).joinToString(" ").ifBlank { null },
    Repo.ageLabel(p).ifBlank { null }).joinToString(" • ")

@Composable
private fun PetsTab(nav: NavHostController) {
    val s = state()
    var menuNa by remember { mutableStateOf(false) }
    Scroll {
        ErrorBanner()
        s.pets.forEach { p ->
            WCard(Modifier.fillMaxWidth(), pad = 0.dp, onClick = { nav.navigate("pet/${p.id}") }) {
                NetImage(p.image, Modifier.fillMaxWidth().aspectRatio(2.7f))
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, color = W.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.MoreHoriz, "Show menu", tint = W.Text, modifier = Modifier.clickable { menuNa = true })
                    }
                    Text(petSummary(p), color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 22.dp))
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(W.Green, 12.dp)
                        Text("Last Weight: ${Repo.weight(s.readings(p.id).lastOrNull()?.lbs ?: p.lastLbs)}", color = W.Text, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        AddCard("Add a pet")
    }
    if (menuNa) InfoDialog("Pet options", "Editing or removing pets isn't available through the public API.") { menuNa = false }
}

@Composable
fun PetProfileScreen(nav: NavHostController, id: String) {
    val s = state()
    val p = s.pet(id) ?: return
    val r = s.readings(id)
    var na by remember { mutableStateOf(false) }
    var localEdit by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<String?>(null) }
    val notes = Repo.local.collectAsState().value.petNotes[id] ?: io.github.gccammelli.meowstache.PetNotes()
    if (localEdit) LocalPetDialog(p, { nav.popBackStack() }) { localEdit = false }
    Box(Modifier.fillMaxSize().background(W.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NetImage(p.image, Modifier.fillMaxWidth().aspectRatio(1.67f))
            Column(Modifier.fillMaxWidth().background(W.pick(0xFF3A3A3A, 0xFFFFFFFF)).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = W.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text(petSummary(p), color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                r.lastOrNull()?.let { Text("Last Visit: ${fmt(it.ts, "dd/MM/yyyy HH:mm")}", color = W.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                Pill("Edit Profile", { if (Repo.localMode) localEdit = true else na = true }, Modifier.padding(top = 16.dp).width(260.dp),
                    icon = { Icon(Icons.Filled.Edit, null, tint = W.Accent) })
                if (!Repo.localMode) NotAvailable(Modifier.padding(top = 4.dp))
            }
            Column(Modifier.padding(18.dp)) {
                val (v, u) = splitWeight(yesterdayLbs(r))
                MetricCard("SmartScale®", "Yesterday’s weight", "$v $u") { nav.navigate("scale/$id") }
                Spacer(Modifier.height(14.dp))
                MetricCard("Visits", "Yesterday’s Visits", "${dayReadings(r, yesterday).size} visits") { nav.navigate("visits/$id") }
                Spacer(Modifier.height(14.dp))
                InfoRow(Icons.Filled.Male, "Gender", p.fixedLabel)
                InfoRow(Icons.Filled.Scale, "Reference weight", Repo.weight(p.referenceLbs))
                InfoRow(Icons.Filled.Cake, "Birthday", p.birthday?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "—")
                InfoRow(Icons.Filled.CalendarMonth, "Age", Repo.ageLabel(p, long = true).ifBlank { "—" })
                InfoRow(Icons.Filled.Pets, "Breed", p.breed)
                InfoRow(Icons.Filled.Favorite, "Adoption Day (Optional)", p.adoption?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "—")
                InfoRow(Icons.Filled.Description, "Adoption Details", notes.adoptionDetails.ifBlank { "Tap to add" }) { edit = "adoption" }
                InfoRow(Icons.Filled.MonitorHeart, "Health Issues", p.health)
                InfoRow(Icons.Filled.Restaurant, "Diet", p.diet)
                InfoRow(Icons.Filled.Schedule, "Feeding Time", notes.feedingTime.ifBlank { "Tap to add" }) { edit = "feeding" }
                InfoRow(Icons.Filled.Park, "Environment", p.environment)
                InfoRow(Icons.Filled.EmojiEmotions, "Personality", notes.personality.joinToString(", ").ifBlank { "Tap to add" }) { edit = "personality" }
            }
        }
        Box(Modifier.padding(start = 6.dp, top = 40.dp).size(40.dp).background(Color(0xCC1E1E1E), CircleShape).clickable { nav.popBackStack() },
            contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = Color.White, modifier = Modifier.padding(start = 6.dp).size(20.dp))
        }
    }
    fun save(f: (io.github.gccammelli.meowstache.PetNotes) -> io.github.gccammelli.meowstache.PetNotes) =
        Repo.updateLocal { it.copy(petNotes = it.petNotes + (id to f(notes))) }
    val note = "Saved on this phone; the official app keeps its own copy."
    when (edit) {
        "adoption" -> ChoiceDialog("Where did you get them?", listOf("Breeder/Pet store", "Shelter/Rescue", "Friend/Family", "Found/Stray", "Other"),
            setOf(notes.adoptionDetails), { edit = null }, note) { v -> save { it.copy(adoptionDetails = v.first()) } }
        "feeding" -> ChoiceDialog("When does ${p.name} usually eat?", listOf("Free Feeding", "Specific Times", "Not Sure"),
            setOf(notes.feedingTime), { edit = null }, note) { v -> save { it.copy(feedingTime = v.first()) } }
        "personality" -> ChoiceDialog("Personality", PERSONALITIES, notes.personality.toSet(), { edit = null }, note, multi = true) { v ->
            save { it.copy(personality = PERSONALITIES.filter { t -> t in v }) }
        }
    }
    if (na) InfoDialog("Edit Profile", "Editing pet profiles isn't available through the public API. Use the official app for now.") { na = false }
}

@Composable
private fun MetricCard(title: String, label: String, value: String, onClick: () -> Unit) =
    WCard(Modifier.fillMaxWidth(), onClick = onClick, pad = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("View more", color = W.Text2, fontSize = 13.sp); Chevron(W.Text2)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(label, color = W.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
                Text(value, color = W.Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
            Icon(Icons.Filled.BarChart, null, tint = W.Off, modifier = Modifier.size(56.dp))
        }
    }

private val PERSONALITIES = listOf("Affectionate", "Playful", "Independent", "Curious", "Bossy", "Hunter", "Shy", "Lazy", "Vocal", "Social")

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String?, onClick: (() -> Unit)? = null) {
    WCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), pad = 0.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = W.Accent, modifier = Modifier.size(30.dp))
            Column(Modifier.padding(start = 20.dp)) {
                Text(label, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (value != null) Text(value, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
                else NotAvailable(Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** SmartScale® Details: weight chart (all ranges) and the full, paged reading list. */
@Composable
fun ScaleDetailsScreen(nav: NavHostController, id: String) {
    val s = state()
    val p = s.pet(id) ?: return
    val r = s.readings(id)
    var shown by remember { mutableIntStateOf(10) }
    var info by remember { mutableStateOf(false) }
    Screen({ BackTopBar("SmartScale® Details", { nav.popBackStack() }) {
        Icon(Icons.Outlined.Info, "Info", tint = W.Text, modifier = Modifier.size(30.dp).clickable { info = true })
    } }) {
        Scroll {
            MetricHeader("Yesterday’s weight", splitWeight(yesterdayLbs(r)).let { "${it.first} ${it.second}" })
            Spacer(Modifier.height(14.dp))
            RangeChartCard(s.earliest(id), header = {
                Text("Weight Overview", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }) { b, _ ->
                Chart(weightSeries(r, b).map { it?.let(Repo::weightNum) }, b.map { it.label }, ChartKind.DOTS, fromZero = true,
                    tooltip = { "${Repo.num(it)} ${Repo.unitLabel()}" })
                Legend("Weights" to W.Accent)
            }
            Spacer(Modifier.height(14.dp))
            val durations = s.durations(id)
            HistoryList("Recent Weight History", r.asReversed(), shown, { shown += 20 }) { it ->
                EventRow(leading = { Avatar(p.image, 40.dp) }, title = {
                    Text(buildAnnotatedString {
                        append("Weight recorded - "); withStyle(SpanStyle(color = W.Accent)) { append(Repo.weight(it.lbs)) }
                    }, color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }, lines = listOfNotNull(p.name, durations[it.ts]?.let { m -> "Visit: ≈ ${minSec(m)}" }, fmtAt(it.ts)), trailing = { ClipButton(it.ts) })
            }
        }
    }
    if (info) InfoDialog("How SmartScale® works", "Each time ${p.name} visits, the Litter-Robot weighs them and assigns the reading to the pet with the closest weight. This screen shows every reading on record, not just the last week.") { info = false }
}

@Composable
fun VisitDetailsScreen(nav: NavHostController, id: String) {
    val s = state()
    s.pet(id) ?: return
    val r = s.readings(id)
    val d = s.durations(id)
    var shown by remember { mutableIntStateOf(10) }
    Screen({ BackTopBar("Visit Details", { nav.popBackStack() }) }) {
        Scroll {
            MetricHeader("Yesterday’s Visits", "${dayReadings(r, yesterday).size} visits")
            Spacer(Modifier.height(14.dp))
            RangeChartCard(s.earliest(id), header = {
                Text("Visits Overview", color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }) { b, _ ->
                Chart(countSeries(r.map { it.ts }, b).map { it.toDouble() }, b.map { it.label }, ChartKind.DOTS)
                Legend("Visits" to W.Accent)
            }
            Spacer(Modifier.height(14.dp))
            HistoryList("Recent Visit History", r.asReversed(), shown, { shown += 20 }) {
                EventRow(onClick = { nav.navigate("visit/$id/${it.ts}") },leading = { CircleIcon(Icons.Filled.Scale, W.Green, Color(0xFF1E1E1E), 34.dp) },
                    title = { Text("Pet Visit Recorded", color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                    lines = listOfNotNull(fmtAt(it.ts), d[it.ts]?.let { m -> "≈ ${minSec(m)}" }), trailing = { ClipButton(it.ts) })
            }
        }
    }
}

@Composable
private fun MetricHeader(label: String, value: String) = WCard(Modifier.fillMaxWidth(), pad = 20.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = W.Text2, fontSize = 13.sp)
            Text(value, color = W.Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        }
        Icon(Icons.Filled.BarChart, null, tint = W.Off, modifier = Modifier.size(56.dp))
    }
}

@Composable
fun <T> HistoryList(title: String, items: List<T>, shown: Int, more: () -> Unit, row: @Composable (T) -> Unit) =
    WCard(Modifier.fillMaxWidth()) {
        Text(title, color = W.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
        items.take(shown).forEach { row(it); Spacer(Modifier.height(10.dp)) }
        if (items.size > shown) Text("See More (${items.size - shown} more)", color = W.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable(onClick = more).padding(12.dp))
        if (items.isEmpty()) Text("No history yet", color = W.Muted, modifier = Modifier.padding(8.dp))
    }

/**
 * ▶ for the video of the visit weighed at [ts] (GitHub #3), when Home Assistant has a clip near it. Clips go by time,
 * not by cat, so a reassigned weighing keeps its video.
 */
@Composable
fun ClipButton(ts: Long) {
    val clips by Repo.clips.collectAsState()
    val clip = clipFor(ts, clips) ?: return
    val ctx = LocalPlatform.current
    Icon(Icons.Filled.PlayCircle, "Play the visit's video", tint = W.Accent,
        modifier = Modifier.clickable { ctx.playVideo { Repo.clipUrl(clip) } }.padding(8.dp))
}

@Composable
fun EventRow(leading: @Composable () -> Unit, title: @Composable () -> Unit, lines: List<String>, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) =
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(W.Row)
            .let { if (W.dark) it else it.border(1.dp, W.Line, RoundedCornerShape(16.dp)) }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            title()
            lines.forEach { Text(it, color = W.Text, fontSize = 13.sp) }
        }
        trailing?.invoke()
    }

// ---------- Shop ----------

@Composable
private fun ShopTab() {
    val ctx = LocalPlatform.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.ShoppingBag, null, tint = W.Accent, modifier = Modifier.size(80.dp))
        Text("Shop", color = W.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        Text("Litter, filters, waste drawer liners and accessories.", color = W.Text2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Pill("Open shop", { ctx.openUrl(Repo.shopUrl()) }, Modifier.fillMaxWidth(), filled = true)
    }
}
