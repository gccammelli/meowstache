package io.github.gccammelli.meowstache.ui

import io.github.gccammelli.meowstache.*
import kotlinx.datetime.LocalDate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.MenuBook
import io.github.gccammelli.meowstache.s
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import io.github.gccammelli.meowstache.APP_NAME
import io.github.gccammelli.meowstache.Brand
import io.github.gccammelli.meowstache.LBS_TO_KG
import io.github.gccammelli.meowstache.Repo
import io.github.gccammelli.meowstache.Target

// ---------- Message Center ----------

@Composable
fun MessageCenterScreen(nav: NavHostController) {
    val msgs = Repo.messages.collectAsState().value
    LaunchedEffect(Unit) { Repo.markAllRead() }
    Screen({ BackTopBar("Message Center", { nav.popBackStack() }) }) {
        Scroll {
            WCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Messages", color = W.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50)).background(W.Accent).padding(horizontal = 10.dp, vertical = 2.dp)) {
                        Text("${msgs.size}", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Clear all", color = W.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.border(2.dp, W.Text, RoundedCornerShape(50)).clickable { Repo.clearMessages() }
                            .padding(horizontal = 18.dp, vertical = 6.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = W.Line)
                if (msgs.isEmpty()) Text("No messages. Robot and pet alerts you turn on in Account › Notifications show up here.", color = W.Muted, fontSize = 13.sp)
                msgs.forEach { m ->
                    Row(Modifier.padding(bottom = 18.dp)) {
                        val (ic, bg) = messageIcon(m.title)
                        CircleIcon(ic, bg, Color.White, 34.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!m.read) StatusDot(W.Accent, 12.dp)
                                Text(m.title, color = W.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(m.body, color = W.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            Text(fmt(m.ts, "d MMM yyyy 'at' HH:mm"), color = W.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(bottom = 18.dp), color = W.Line)
                }
            }
        }
    }
}

/** The official app's message icons: bell for status, bulb for tips, bin for the waste drawer. */
private fun messageIcon(title: String) = when {
    "drawer" in title.lowercase() -> Icons.Filled.Delete to W.Accent
    "deep clean" in title.lowercase() -> Icons.Filled.Lightbulb to W.Accent
    "update" in title.lowercase() -> Icons.Filled.SystemUpdate to W.Blue
    else -> Icons.Filled.Notifications to W.Green
}

// ---------- Account ----------

private const val COFFEE = "https://buymeacoffee.com/meowstache"
/** This version's release page; previews (e.g. "1.1.0-beta") have no tag of their own, so they get the releases list. */
private fun releaseNotes(version: String) = "https://github.com/gccammelli/meowstache/releases" + if ('-' in version) "" else "/tag/v$version"

@Composable
fun AccountScreen(nav: NavHostController) {
    val s = state(); val ctx = LocalPlatform.current
    var login by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun toast(t: String) = ctx.toast(t)
    Screen({ BackTopBar("Account", { nav.popBackStack() }) }) {
        Scroll {
            WCard(Modifier.fillMaxWidth(), onClick = { login = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.firstName} ${s.lastName}".trim().ifBlank { "Account" }, color = W.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(Repo.email(), color = W.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Chevron()
                }
            }
            if (!Repo.localMode) Group("Account", W.Accent) {
                ListRow("Login Information", null, { login = true }, icon = Icons.Filled.AccountBox)
                ListRow("Addresses", null, { ctx.openUrl(Repo.storeUrl("customer/address/")) }, icon = Icons.Filled.Home, divider = true)
                ListRow("Payment Methods", null, { ctx.openUrl(Repo.storeUrl("vault/cards/listaction/")) }, icon = Icons.Filled.CreditCard, divider = true)
                ListRow("Referral Dashboard", null, { ctx.openUrl("https://www.whisker.com/share") }, icon = Icons.Filled.Groups, divider = true)
            }
            if (!Repo.localMode) Group("Orders & Subscriptions", W.Green) {
                ListRow("Orders", null, { ctx.openUrl(Repo.storeUrl("sales/order/history/")) }, icon = Icons.Filled.LocalShipping)
                ListRow("Subscriptions", null, { ctx.openUrl(Repo.storeUrl("customer/subscriptions/")) }, icon = Icons.Filled.Autorenew, divider = true)
                ListRow("Returns", null, { ctx.openUrl(Repo.storeUrl("returns")) }, icon = Icons.AutoMirrored.Filled.AssignmentReturn, divider = true)
            }
            Group("Settings", W.Blue) {
                ListRow("Notifications", null, { nav.navigate("notifications") }, icon = Icons.Filled.Notifications)
                ListRow("Region", null, { nav.navigate("region") }, icon = Icons.Filled.Place, divider = true)
                ListRow("App Version", null, { nav.navigate("about") }, icon = Icons.Filled.Pets, divider = true)
                ListRow("Firmware Updates", null, { nav.navigate("firmware") }, icon = Icons.Filled.SystemUpdate, divider = true)
                ListRow("Release Notes", null, { ctx.openUrl(releaseNotes(ctx.appVersion)) }, icon = Icons.Filled.Edit, divider = true)
                ListRow("Measurement Units", null, { nav.navigate("units") }, icon = Icons.Filled.Straighten, divider = true)
                ListRow("Back up history", "Save every weighing and setting to a file", {
                    ctx.saveFile("meowstache-${LocalDate.now()}.json", Repo.exportBackup()) { e -> toast(if (e == null) "History backed up" else "Backup failed: ${e.message}") }
                }, icon = Icons.Filled.Backup, divider = true)
                if (Repo.localMode) ListRow("Home Assistant diagnostics", "Save what Meowstache sees, to send with a bug report", { diag = true },
                    icon = Icons.Filled.BugReport, divider = true)
                ListRow("Restore history", "Merge a backup file into this phone", {
                    ctx.openFile { r ->
                        r ?: return@openFile
                        scope.launch {
                            runCatching { Repo.importBackup(r.getOrThrow()) }
                                .fold({ toast("Restored · %,d weighings on record".format(it)) }, { toast("Restore failed: ${it.message}") })
                        }
                    }
                },
                    icon = Icons.Filled.Restore, divider = true)
                HorizontalDivider(color = W.Line, thickness = 1.dp)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Article, null, tint = W.Icon, modifier = Modifier.padding(end = 16.dp).size(24.dp))
                    Text("Show Blogs on Home", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(Repo.showBlogs.collectAsState().value, Repo::setShowBlogs, colors = SwitchDefaults.colors(checkedTrackColor = W.Blue, checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFDDDDDD), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
                }
            }
            Group("Support", W.Red) {
                ListRow("Meowstache Privacy", "What this app stores and sends", { privacy = true }, icon = Icons.Filled.PrivacyTip)
                ListRow("Email Meowstache", "meowstache.app@gmail.com", { ctx.openUrl("mailto:meowstache.app@gmail.com") }, icon = Icons.Filled.Email, divider = true)
                ListRow("Report a Meowstache Issue", null, { ctx.openUrl("https://github.com/gccammelli/meowstache/issues") }, icon = Icons.Filled.BugReport, divider = true)
                ListRow("Litter-Robot Support", "The manufacturer's help and contact page", { ctx.openUrl("https://www.litter-robot.com/support/#contact-us") }, icon = Icons.Filled.SupportAgent, divider = true)
                ListRow("Litter-Robot FAQ", "The manufacturer's FAQ", { ctx.openUrl("https://www.litter-robot.com/faq.html") }, icon = Icons.AutoMirrored.Filled.Help, divider = true)
                ListRow("Buy me a coffee", "Help keep Meowstache going", { ctx.openUrl(COFFEE) }, icon = Icons.Filled.LocalCafe, divider = true)
                ListRow("Litter-Robot 4 Guide", "The manufacturer's setup guide", { ctx.openUrl("https://www.litter-robot.com/support/litter-robot-4/") }, icon = Icons.AutoMirrored.Filled.MenuBook, divider = true)
            }
            Spacer(Modifier.height(20.dp))
            Pill(if (Repo.demo) "Exit Demo" else "Log Out", {
                Repo.logout()
                nav.navigate("welcome") { popUpTo(0) }
            }, Modifier.fillMaxWidth())
            Text("Made with spite 💔\nV${LocalPlatform.current.appVersion}", color = W.Text, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        }
    }
    if (diag) ConfirmDialog("Home Assistant diagnostics",
        "Saves a file with what Meowstache reads from Home Assistant about your robots: their entities, current values and the last day of history, " +
            "plus how the app understood them. It includes robot and cat names and weights, but not your token or Home Assistant's address. " +
            "Send it with a bug report (meowstache.app@gmail.com or GitHub) so we can see what your firmware reports.",
        "Save file", {
            val version = ctx.appVersion
            Repo.launch {
                runCatching { Repo.haDiagnostics(version) }.fold(
                    { text -> ctx.saveFile("meowstache-ha-diagnostics-${LocalDate.now()}.json", text) { e -> toast(if (e == null) "Diagnostics saved" else "Couldn't save: ${e.message}") } },
                    { toast("Couldn't read Home Assistant: ${it.message}") })
            }
        }, { diag = false })
    if (privacy && Repo.localMode) ConfirmDialog("Meowstache Privacy",
        "• Local mode only talks to your own Home Assistant, at the address you entered. Nothing is sent to the Litter-Robot servers.\n" +
        "• The access token is kept on this phone, encrypted with a key in the phone's secure hardware, and never backed up.\n" +
        "• Your robot and pet history is stored only on this phone (and in Android's own backup, if you use it).\n" +
        "• No analytics, no ads, no other servers.",
        "ESPHome firmware", { ctx.openUrl(io.github.gccammelli.meowstache.ESPHOME_FIRMWARE_URL) }, { privacy = false }, dismiss = "Close")
    else if (privacy) ConfirmDialog("Meowstache Privacy",
        "• Your password goes straight to the Litter-Robot login server over HTTPS and is never stored.\n" +
        "• A login token is kept on this phone, encrypted with a key in the phone's secure hardware, and never backed up.\n" +
        "• Your robot and pet history is stored only on this phone (and in Android's own backup, if you use it).\n" +
        "• The app only talks to the Litter-Robot servers. No analytics, no ads, no other servers.\n\n" +
        "Your data on the Litter-Robot servers is covered by the manufacturer's own privacy policy.",
        "Manufacturer's policy", { ctx.openUrl("https://www.litter-robot.com/legal.html#privacypolicy") }, { privacy = false }, dismiss = "Close")
    if (login && Repo.localMode) InfoDialog("Home Assistant", "Connected to ${Repo.email()}.\n\n" +
        "To disconnect for good, log out here and delete the Meowstache token in Home Assistant (your profile › Security).") { login = false }
    else if (login) ConfirmDialog("Login Information", "Signed in as ${Repo.email()}.\n\nYour email and password belong to your Litter-Robot account. You can change the password on the manufacturer's website.",
        "Change password", { ctx.openUrl(Repo.storeUrl("customer/account/forgotpassword/")) }, { login = false })
}

@Composable
private fun Group(title: String, dot: Color, content: @Composable () -> Unit) {
    Spacer(Modifier.height(14.dp))
    SectionHeader(title, dot = dot)
    WCard(Modifier.fillMaxWidth(), pad = 0.dp) { Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { content() } }
}

@Composable
private fun KeyValue(key: String, value: String, divider: Boolean = true) {
    Text(key, color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
    Text(value, color = W.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
    if (divider) HorizontalDivider(color = W.Line, thickness = 1.dp)
}

@Composable
fun AboutScreen(nav: NavHostController) = Screen({ BackTopBar("App Version", { nav.popBackStack() }) }) {
    val ctx = LocalPlatform.current
    Scroll {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Brand(90) }
        WCard(Modifier.fillMaxWidth(), pad = 0.dp) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                KeyValue("Package", LocalPlatform.current.appId)
                KeyValue("Version", LocalPlatform.current.appVersion)
                KeyValue("Build", "${LocalPlatform.current.buildNumber}")
                Text("Buy me a coffee", color = W.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { ctx.openUrl(COFFEE) }.padding(top = 16.dp))
                Text("Release Notes", color = W.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { ctx.openUrl(releaseNotes(ctx.appVersion)) }.padding(vertical = 16.dp))
            }
        }
        Text("An unofficial companion app for the Litter-Robot 4 that keeps your pets' full history and adds pet alerts. It doesn't replace the official app and isn't affiliated with its maker.",
            color = W.Text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

@Composable
fun RegionScreen(nav: NavHostController) = Screen({ BackTopBar("Region", { nav.popBackStack() }) }) {
    var pick by remember { mutableStateOf(Repo.region()) }
    var open by remember { mutableStateOf(false) }
    Scroll {
        Text("Please select your region below.", color = W.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(pick, color = Color(0xFF1E1E1E), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, null, tint = Color(0xFF1E1E1E))
            }
            androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Color.White) {
                Repo.regions.forEach { r ->
                    androidx.compose.material3.DropdownMenuItem({ Text(r, color = Color(0xFF1E1E1E)) }, { pick = r; open = false })
                }
            }
        }
        Text("Picks which regional website the Shop and account links open.", color = W.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        Spacer(Modifier.height(28.dp))
        Pill("Save", { Repo.updateLocal { it.copy(region = pick) }; nav.popBackStack() }, Modifier.fillMaxWidth().padding(horizontal = 40.dp), filled = true)
    }
}

@Composable
fun FirmwareScreen(nav: NavHostController) = Screen({ BackTopBar("Firmware Updates", { nav.popBackStack() }) }) {
    val s = state()
    var details by remember { mutableStateOf<io.github.gccammelli.meowstache.Robot?>(null) }
    val latest = remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    LaunchedEffect(s.robots.map { it.serial }) { s.robots.forEach { r -> latest.value += r.serial to Repo.latestFirmware(r) } }
    Scroll {
        s.robots.forEach { r ->
            val l = latest.value[r.serial]
            WCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RobotArt(30.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(r.name, color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Version: ${r.raw.s("espFirmware") ?: "—"}", color = W.Text, fontSize = 12.sp)
                    }
                    Text(when { l == null -> "Checking…"; "Update available" in l -> "Update available"; else -> "Up to date" },
                        color = if (l != null && "Update available" in l) W.Accent else W.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("Firmware details", color = W.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { details = s.robots.firstOrNull() }.padding(16.dp))
        val ctx = LocalPlatform.current; val scope = rememberCoroutineScope()
        var install by remember { mutableStateOf<io.github.gccammelli.meowstache.Robot?>(null) }
        s.robots.filter { latest.value[it.serial]?.contains("Update available") == true }.forEach { r ->
            Pill("Install update on ${r.name}", { install = r }, Modifier.fillMaxWidth().padding(top = 8.dp), filled = true)
        }
        install?.let { r ->
            ConfirmDialog("Install firmware update?", "${r.name} will download and install the update. It can't cycle while updating, so make sure no cat needs it for a few minutes.", "Install", {
                scope.launch {
                    Repo.triggerFirmwareUpdate(r).fold({ ok -> ctx.toast(if (ok) "Update started" else "Update not started") },
                        { ctx.toast("Failed: ${it.message}") })
                }
            }, { install = null })
        }
    }
    details?.let { r ->
        InfoDialog("Firmware Information", "Installed: ESP ${r.raw.s("espFirmware")} / PIC ${r.raw.s("picFirmwareVersion")} / TOF ${r.raw.s("laserBoardFirmwareVersion")}\n\nLatest: ${latest.value[r.serial] ?: "checking…"}") { details = null }
    }
}

@Composable
fun UnitsScreen(nav: NavHostController) = Screen({ BackTopBar("Measurement Units", { nav.popBackStack() }) }) {
    val u = Repo.units.collectAsState().value
    val d = Repo.decimals.collectAsState().value
    Scroll {
        WCard(Modifier.fillMaxWidth(), pad = 22.dp) {
            Text("Measurement Type", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Segmented(listOf("Metric", "Imperial"), if (u == "kg") 0 else 1, { Repo.setUnits(if (it == 0) "kg" else "lb") })
        }
        Spacer(Modifier.height(14.dp))
        WCard(Modifier.fillMaxWidth(), pad = 22.dp) {
            Text("Weight Precision", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Segmented(listOf(Repo.weight(10.8, 1), Repo.weight(10.8, 2)), d - 1, { Repo.setDecimals(it + 1) })
        }
    }
}

// ---------- Notifications ----------

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, info: (() -> Unit)? = null, na: Boolean = false, onChange: (Boolean) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = if (enabled) W.Text else W.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (info != null) Icon(Icons.Outlined.Info, "Info", tint = W.Accent, modifier = Modifier.padding(start = 8.dp).size(20.dp).clickable(onClick = info))
            }
            if (na) NotAvailable()
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = W.Blue, checkedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFDDDDDD), uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Color.Transparent, disabledCheckedTrackColor = W.Blue.copy(alpha = 0.4f), disabledUncheckedTrackColor = W.pick(0xFF666666, 0xFFEEEEEE),
        ))
    }
}

@Composable
private fun Choice(options: List<Int>, selected: Int, suffix: (Int) -> String, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            Text(suffix(o), color = if (o == selected) Color(0xFF2B2B2B) else W.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (o == selected) W.Accent else W.Inner)
                    .clickable { onSelect(o) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }

@Composable
fun NotificationsScreen(nav: NavHostController) {
    val s = state(); val ctx = LocalPlatform.current
    val a = Repo.alerts.collectAsState().value
    var usageOpen by remember { mutableStateOf(false) }
    fun update(n: io.github.gccammelli.meowstache.AlertSettings) { Repo.setAlerts(n); ctx.scheduleAlerts() }
    Screen({ BackTopBar("Notifications", { nav.popBackStack() }) }) {
        Scroll {
            // The official app's push topics, rebuilt locally: checked on this phone with the pet alerts.
            val t = a.robot
            fun topics(f: (io.github.gccammelli.meowstache.RobotTopics) -> io.github.gccammelli.meowstache.RobotTopics) = update(a.copy(robot = f(t)))
            var info by remember { mutableStateOf<Pair<String, String>?>(null) }
            WCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device:", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(s.robots.firstOrNull()?.model ?: "—", color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50)).background(W.pick(0xFF262626, 0xFFF1F1F1)).padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Text("Receive push notifications about your Litter-Robot 4. Select the topics you want to be informed about.",
                    color = W.Text, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(W.pick(0xFF2A2A2A, 0xFFF1F1F1), CircleShape).clickable { usageOpen = !usageOpen }, contentAlignment = Alignment.Center) {
                        Icon(if (usageOpen) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = W.Text)
                    }
                    Box(Modifier.weight(1f).padding(start = 14.dp)) { Toggle("Robot Usage", t.usage) { v -> topics { it.copy(usage = v) } } }
                }
                if (usageOpen) Column(Modifier.padding(start = 44.dp)) {
                    Toggle("Drawer full", t.drawerFull, enabled = t.usage) { v -> topics { it.copy(drawerFull = v) } }
                    Toggle("Low Litter", t.lowLitter, enabled = t.usage) { v -> topics { it.copy(lowLitter = v) } }
                    Toggle("Clean Cycle Complete", t.cycleComplete, enabled = t.usage) { v -> topics { it.copy(cycleComplete = v) } }
                    Toggle("Clean Cycle Interrupted", t.cycleInterrupted, enabled = t.usage) { v -> topics { it.copy(cycleInterrupted = v) } }
                    Toggle("Maintenance Tips", t.maintenance, enabled = t.usage) { v -> topics { it.copy(maintenance = v) } }
                    Toggle("Cat Detected In Drawer", false, enabled = false, na = true)
                    Toggle("Hopper Enabled", false, enabled = false, na = true)
                }
                Toggle("Errors", t.errors, info = { info = "Errors" to "Robot faults from the activity log (bonnet removed, cat sensor or globe motor fault, battery backup) and the robot going offline for 1 or 24 hours." }) { v -> topics { it.copy(errors = v) } }
                Toggle("Software Updates", t.updates, info = { info = "Software Updates" to "Tells you when newer firmware is published for your robot. The robot installs updates by itself." }) { v -> topics { it.copy(updates = v) } }
                Text("Checked by $APP_NAME on this phone every 30 minutes, not pushed by the manufacturer.", color = W.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            info?.let { (title, text) -> InfoDialog(title, text) { info = null } }
            Spacer(Modifier.height(14.dp))
            WCard(Modifier.fillMaxWidth()) { Text("LitterHopper", color = W.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); NotAvailable() }

            // ---- Meowstache's own alerts, checked on this phone every 30 minutes ----
            Spacer(Modifier.height(14.dp))
            WCard(Modifier.fillMaxWidth()) {
                Text("Pet Alerts", color = W.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Checked by $APP_NAME on this phone every 30 minutes.", color = W.Text2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))

                Toggle("No litter box visit", a.noVisitEnabled) { update(a.copy(noVisitEnabled = it)) }
                if (a.noVisitEnabled) {
                    Text("Alert when a pet hasn't visited for", color = W.Text2, fontSize = 12.sp)
                    Choice(listOf(1, 2, 3), a.noVisitDays, { "$it day${if (it > 1) "s" else ""}" }) { update(a.copy(noVisitDays = it)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = W.Line)

                Toggle("Too many visits", a.tooManyEnabled) { update(a.copy(tooManyEnabled = it)) }
                if (a.tooManyEnabled) {
                    Text("Alert when a pet visits more than this many times in 24 hours", color = W.Text2, fontSize = 12.sp)
                    Choice(listOf(4, 6, 8, 10, 12), a.tooManyVisits, { "$it" }) { update(a.copy(tooManyVisits = it)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = W.Line)

                Toggle("Unusual patterns", a.patternsEnabled) { update(a.copy(patternsEnabled = it)) }
                Text("Significant changes in visits, weight or duration vs the previous 4 weeks.", color = W.Text2, fontSize = 12.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = W.Line)

                Toggle("Daily recap", a.recapEnabled) { update(a.copy(recapEnabled = it)) }
                if (a.recapEnabled) {
                    Text("Yesterday's summary for every pet, sent at", color = W.Text2, fontSize = 12.sp)
                    Choice(listOf(7, 8, 9, 10), a.recapHour, { io.github.gccammelli.meowstache.hourLabel(it) }) { update(a.copy(recapHour = it)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = W.Line)

                Text("Target weight", color = W.Text, fontSize = 13.sp)
                Text("Optional, per pet. Alerts when the daily average weight crosses the target, from below or above.",
                    color = W.Text2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                var editing by remember { mutableStateOf<io.github.gccammelli.meowstache.Pet?>(null) }
                s.pets.forEach { p ->
                    val t = a.targets[p.id]
                    Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.image, 40.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.name, color = W.Text, fontSize = 13.sp)
                            Text("Now ${Repo.weight(yesterdayLbs(s.readings(p.id)))}", color = W.Text2, fontSize = 12.sp)
                        }
                        Text(t?.let { Repo.weight(it.lbs) } ?: "Off", color = if (t != null) Color(0xFF2B2B2B) else W.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (t != null) W.Accent else W.Inner).padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                editing?.let { p ->
                    TargetDialog(p.name, a.targets[p.id]?.lbs ?: yesterdayLbs(s.readings(p.id)), a.targets[p.id] != null, { lbs ->
                        update(a.copy(targets = if (lbs == null) a.targets - p.id else a.targets + (p.id to Target(lbs, nowMs()))))
                    }, { editing = null })
                }
                Spacer(Modifier.height(12.dp))
                Pill("Check now", {
                    val st = Repo.state.value
                    val found = io.github.gccammelli.meowstache.allAlerts(st, Repo.alerts.value)
                    ctx.notifyNew(found)
                    ctx.toast(if (found.isEmpty()) "Nothing to report" else "${found.size} alert(s) active")
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Two wheels (whole number, decimals) in the user's units; decimals follow the precision setting. */
@Composable
private fun TargetDialog(name: String, startLbs: Double?, isSet: Boolean, onSave: (Double?) -> Unit, onDismiss: () -> Unit) {
    val kg = Repo.units.value == "kg"
    val dec = Repo.decimals.value
    val steps = if (dec == 2) 100 else 10
    val wholes = if (kg) (1..20).toList() else (2..45).toList()
    val start = Repo.weightNum(startLbs ?: 10.0)
    val startHundredths = kotlin.math.floor(start * steps + 0.5).toLong()
    var whole by remember { mutableStateOf((startHundredths / steps).toInt().coerceIn(wholes.first(), wholes.last())) }
    var frac by remember { mutableStateOf((startHundredths % steps).toInt()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = W.Card,
        title = { Text("$name's target weight", color = W.Text) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel(wholes.map { "$it" }, wholes.indexOf(whole), { whole = wholes[it] }, Modifier.width(80.dp))
                Text(".", color = W.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Wheel((0 until steps).map { if (dec == 2) "%02d".format(it) else "$it" }, frac, { frac = it }, Modifier.width(80.dp))
                Text(Repo.unitLabel(), color = W.Text2, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val v = whole + frac.toDouble() / steps
                onSave(if (kg) v / LBS_TO_KG else v); onDismiss()
            }) { Text("Save", color = W.Accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { if (isSet) onSave(null); onDismiss() }) {
                Text(if (isSet) "Turn off" else "Cancel", color = W.Text2)
            }
        },
    )
}
