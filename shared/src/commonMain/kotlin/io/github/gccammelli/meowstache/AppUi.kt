package io.github.gccammelli.meowstache

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.gccammelli.meowstache.ui.AboutScreen
import io.github.gccammelli.meowstache.ui.RegionScreen
import io.github.gccammelli.meowstache.ui.FirmwareScreen
import io.github.gccammelli.meowstache.ui.AccountScreen
import io.github.gccammelli.meowstache.ui.DailyRecapScreen
import io.github.gccammelli.meowstache.ui.DeviceSettingsScreen
import io.github.gccammelli.meowstache.ui.VisitSummaryScreen
import io.github.gccammelli.meowstache.ui.Logo
import io.github.gccammelli.meowstache.ui.MainScreen
import io.github.gccammelli.meowstache.ui.MessageCenterScreen
import io.github.gccammelli.meowstache.ui.NotificationsScreen
import io.github.gccammelli.meowstache.ui.PetInsightsScreen
import io.github.gccammelli.meowstache.ui.PetProfileScreen
import io.github.gccammelli.meowstache.ui.Pill
import io.github.gccammelli.meowstache.ui.RobotScreen
import io.github.gccammelli.meowstache.ui.ScaleDetailsScreen
import io.github.gccammelli.meowstache.ui.UnitsScreen
import io.github.gccammelli.meowstache.ui.VisitDetailsScreen
import io.github.gccammelli.meowstache.ui.W
import io.github.gccammelli.meowstache.ui.AppTheme
import kotlinx.coroutines.delay
import androidx.savedstate.read
import kotlinx.coroutines.launch

const val APP_NAME = "Meowstache"
const val APP_TAGLINE = "Your cat, your robot, your data"

/** A route argument; nav-compose keeps them in a SavedState on every platform. */
private fun androidx.navigation.NavBackStackEntry.arg(key: String): String = arguments?.read { getStringOrNull(key) }.orEmpty()

/** The whole app UI; each platform hosts it (MainActivity on Android, MainViewController on iOS). */
@Composable
fun Root() {
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1200); splash = false }
    Box(Modifier.fillMaxSize().background(W.Bg)) {
        if (splash) Splash() else {
            val nav = rememberNavController()
            val start = if (!Repo.introAccepted) "intro" else if (Repo.signedIn) "main" else "welcome"
            NavHost(nav, start) {
                composable("intro") { Intro(nav) }
                composable("welcome") { Welcome(nav) }
                composable("login") { Login(nav) }
                composable("main") { MainScreen(nav) }
                composable("petInsights/{id}") { PetInsightsScreen(nav, it.arg("id")) }
                composable("pet/{id}") { PetProfileScreen(nav, it.arg("id")) }
                composable("scale/{id}") { ScaleDetailsScreen(nav, it.arg("id")) }
                composable("visits/{id}") { VisitDetailsScreen(nav, it.arg("id")) }
                composable("robot/{serial}") { RobotScreen(nav, it.arg("serial")) }
                composable("robotSettings/{serial}") { DeviceSettingsScreen(nav, it.arg("serial")) }
                composable("messages") { MessageCenterScreen(nav) }
                composable("account") { AccountScreen(nav) }
                composable("notifications") { NotificationsScreen(nav) }
                composable("units") { UnitsScreen(nav) }
                composable("about") { AboutScreen(nav) }
                composable("region") { RegionScreen(nav) }
                composable("firmware") { FirmwareScreen(nav) }
                composable("recap") { DailyRecapScreen(nav) }
                composable("visit/{pet}/{ts}") { VisitSummaryScreen(nav, it.arg("pet"), it.arg("ts").toLong()) }
            }
        }
    }
}

@Composable
fun Brand(logo: Int = 120) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(logo.dp)
        Spacer(Modifier.height(24.dp))
        Text(APP_NAME, color = W.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(APP_TAGLINE, color = W.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Shown once, before anything else: what this app is for, and the "as is" disclaimer. */
@Composable
private fun Intro(nav: androidx.navigation.NavHostController) {
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(72.dp)
        Text("Before you start", color = W.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 20.dp))
        listOf(
            "Meowstache helps you take back control of your Litter-Robot data: full history, kept on your phone, and the alerts that matter for your cat's health.",
            "It doesn't replace the official Whisker app in any way. Setup, new devices and some settings still need it.",
            "It was built from the ground up as a nudge for Whisker to step off the path of enshittification.",
        ).forEach { Text(it, color = W.Text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) }
        Text("Meowstache is an independent project, not affiliated with or endorsed by Whisker. It is provided \"as is\", without warranty of any kind. " +
            "Use it at your own risk: the authors aren't liable for any damage to your device, loss of data, harm to your pets or any other issue arising from its use.",
            color = W.Text2, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 28.dp))
        Pill("I understand", {
            Repo.acceptIntro()
            nav.navigate(if (Repo.signedIn) "main" else "welcome") { popUpTo(0) }
        }, Modifier.fillMaxWidth(), filled = true)
    }
}

@Composable
private fun Splash() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Brand() }

@Composable
private fun Welcome(nav: NavHostController) {
    val ctx = LocalPlatform.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.weight(1f))
        Brand()
        Spacer(Modifier.weight(1f))
        Pill("Register", { ctx.openUrl(Repo.storeUrl("customer/account/create/")) }, Modifier.fillMaxWidth(), filled = true)
        Spacer(Modifier.height(18.dp))
        Pill("Login", { nav.navigate("login") }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(60.dp))
        Pill("Try the demo", { Repo.startDemo(); nav.navigate("main") { popUpTo(0) } }, Modifier.fillMaxWidth())
        Text("No account needed: a made-up robot and cats with a year of history.", color = W.Text2, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun Login(nav: NavHostController) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalPlatform.current
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = W.Accent, unfocusedBorderColor = W.Muted, focusedLabelColor = W.Accent,
        cursorColor = W.Accent, focusedTextColor = W.Text, unfocusedTextColor = W.Text,
    )
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Brand(84)
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), colors = colors)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = colors)
        error?.let { Text(it, color = W.Red, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(28.dp))
        if (busy) CircularProgressIndicator(color = W.Accent)
        else Pill("Login", {
            busy = true; error = null
            scope.launch {
                try {
                    Repo.api.login(email, password)
                    Repo.onLogin()
                    ctx.scheduleAlerts()
                    Repo.refreshAsync()
                    nav.navigate("main") { popUpTo(0) }
                } catch (e: Exception) {
                    error = e.message ?: "Login failed"
                } finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), filled = true)
        Text("Use the same account as the official Litter-Robot app. Your password goes straight to its login server and is never stored; only a login token is kept on this phone.",
            color = W.Text2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text("Forgot password?", color = W.Text2, fontSize = 13.sp,
            modifier = Modifier.padding(top = 20.dp).clickable { ctx.openUrl(Repo.storeUrl("customer/account/forgotpassword/")) })
    }
}
