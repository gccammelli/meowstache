package io.github.gccammelli.meowstache

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.gccammelli.meowstache.ui.*
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val messages = mutableListOf<String>()
    private val urls = mutableListOf<String>()
    private var savedBackup: String? = null
    private var saveError: Throwable? = null
    private var openedFile: Result<String>? = null
    private var instantSyncs = 0
    private var alertsScheduled = 0
    private var notified: List<PetAlert>? = null
    private val platform = object : PlatformUi {
        override val canInstantNotifications = true
        override fun syncInstantNotifications() { instantSyncs++ }
        override val appId = "test"
        override val appVersion = "1.1.0"
        override val buildNumber = 1
        override fun openUrl(url: String) { urls += url }
        override fun toast(message: String) { messages += message }
        override fun shareFile(mime: String, name: String, text: String) { sharedCsv = text }
        override fun saveFile(suggestedName: String, text: String, done: (Throwable?) -> Unit) { savedBackup = text; done(saveError) }
        override fun openFile(done: (Result<String>?) -> Unit) = done(openedFile)
        override fun scheduleAlerts() { alertsScheduled++ }
        override fun notifyNew(alerts: List<PetAlert>) { notified = alerts }
    }
    private class Settings : KeyValue {
        private val data = mutableMapOf<String, Any>()
        override fun getString(key: String) = data[key] as? String
        override fun putString(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun getBoolean(key: String, default: Boolean) = data[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { data[key] = value }
        override fun getInt(key: String, default: Int) = data[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { data[key] = value }
        override fun getStringSet(key: String) = (data[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        override fun putStringSet(key: String, value: Set<String>) { data[key] = value }
        override fun clear() = data.clear()
    }
    private var sharedCsv: String? = null
    private val pet get() = Repo.state.value.pets.first()
    private val robot get() = Repo.state.value.robots.first()
    private companion object { val settings = Settings() }

    @Before fun home() {
        settings.clear()
        Repo.init(PlatformStorage(settings, Settings(), object : SecretStore {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) = Unit
            override fun clear() = Unit
        }, object : AppFiles {
            override fun read(name: String): String? = null
            override fun write(name: String, text: String) = Unit
            override fun delete(name: String) = Unit
        }))
        Repo.setMode(false)
        Repo.updateLocal { LocalData() }
        Repo.setAlerts(AlertSettings())
        Repo.clearMessages()
        Repo.setUnits("kg"); Repo.setDecimals(1)
        Repo.setShowUnavailable(true); Repo.setShowBlogs(false)
        Repo.askUnavailable.value = null
        Repo.startDemo()
    }

    private fun show(title: String, content: @Composable (NavHostController) -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { content(rememberNavController()) } } }
        compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        compose.runOnIdle {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                if (view.width > 0 && view.height > 0) view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
            }
        }
    }

    private fun robotValues(vararg values: Pair<String, String?>) {
        val raw=robot.raw.toMutableMap()
        for ((key,value) in values) if(value==null) raw.remove(key) else raw[key]=JsonPrimitive(value)
        Repo.state.value=Repo.state.value.copy(robots=listOf(Robot(JsonObject(raw))))
    }

    @Test fun unusualVisitPatternsCanBeDismissedWithoutLosingHistory() {
        val id=pet.id
        val today=LocalDate.now()
        val readings=(0..29).flatMap { days ->
            val at=today.minusDays(days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()+12*60*60_000L
            List(if(days==0) 8 else 2) { visit -> Reading(at+visit*60_000,10.0) }
        }
        Repo.state.value=Repo.state.value.copy(weights=mapOf(id to readings),acts=emptyMap(),overrides=emptyMap())
        show("Patterns") { PatternsCard(state(),id) }
        compose.onNodeWithText("Visits: Increased 4X from 2 to 8").assertExists()
        compose.onNodeWithText("Significant").assertExists()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("Nothing new over the last 30 days.").assertExists()
        assertEquals(setOf("$id|VISITS|$today"),Repo.local.value.dismissedPatterns)
        assertEquals(readings,Repo.state.value.weights[id])
    }

    @Test fun emptyHistoryExplainsWhyThereAreNoPatterns() {
        Repo.state.value=Repo.state.value.copy(weights=emptyMap(),acts=emptyMap())
        show("Patterns") { PatternsCard(state(),pet.id) }
        compose.onNodeWithText("Everything looks normal over the last 30 days.").assertExists()
        compose.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
    }

    @Test fun patternFootersLimitVisibleItemsAndKeepBothDirectionsDistinct() {
        val today=LocalDate.now()
        val days=(0..3).map { today.minusDays(it.toLong()) }
        val b=days.map { Bucket(it,it,"Day") }
        val patterns=days.associateWith { listOf(Pattern(Metric.VISITS,it,2.0,8.0,true)) }
        assertEquals(listOf(1,1,1,1),marksFor(b,Range.WEEK,patterns,Metric.VISITS))
        assertEquals(listOf(0,0,0,0),marksFor(b,Range.MONTH,patterns,Metric.WEIGHT))
        assertTrue(marksFor(b,Range.YEAR,patterns,Metric.VISITS).isEmpty())
        val down=Pattern(Metric.WEIGHT,today,10.0,9.6,false)
        assertEquals(listOf(-1),marksFor(b.take(1),Range.MONTH,mapOf(today to listOf(down)),Metric.WEIGHT))
        compose.setContent { AppTheme { androidx.compose.foundation.layout.Column {
            PatternLine(down)
            PatternFooter(b,Range.WEEK,patterns,Metric.VISITS)
            PatternFooter(b,Range.YEAR,patterns,Metric.VISITS)
            PatternFooter(b,Range.MONTH,patterns,Metric.DURATION)
        } } }
        compose.onAllNodesWithText("Significant").assertCountEquals(3)
        compose.onNodeWithText("Notable").assertExists()
        compose.onNodeWithText("Weight: Decreased",substring=true).assertExists()
        compose.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
    }

    @Test fun visitTimeChartPagesThroughHistoryAndResetsWhenTheRangeChanges() {
        val id=pet.id
        show("Time of Visit") { TimeOfVisitCard(state(),id) }
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithContentDescription("Next").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
        compose.onNodeWithText("Month").performClick()
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithText("Week").performClick()
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
        compose.onNodeWithText("Bubble size shows how long each visit lasted.").assertExists()
    }

    @Test fun durationChartSupportsAllRangesAndEmptyHistory() {
        val id=pet.id
        show("Visit Duration") { DurationCard(state(),id) }
        for(label in listOf("Month","Year","All","Week")) compose.onNodeWithText(label).performClick()
        compose.onNodeWithText("Minutes").assertExists()
        compose.runOnIdle { Repo.state.value=Repo.state.value.copy(weights=emptyMap(),acts=emptyMap()) }
        compose.onNodeWithContentDescription("Previous").assertIsNotEnabled()
        compose.onNodeWithText("Visit Duration").assertExists()
    }

    @Test fun hopperStatesExplainEnableDisableAndUnavailableHardware() {
        robotValues("hopperStatus" to "DISABLED","isHopperRemoved" to "false")
        show(robot.model) { RobotScreen(it,robot.serial) }
        for ((status,label,action) in listOf(Triple("DISABLED","Disabled","Enable"),Triple("READY","Ready","Disable"),
            Triple("EMPTY","Empty","Disable"),Triple("LITTER_LOW","Empty","Disable"),Triple(null,"Not installed","Disable"),
            Triple("unknown-fault","Fault","Disable"))) {
            compose.runOnIdle { robotValues("hopperStatus" to status) }
            compose.onNodeWithText("LitterHopper™").performScrollTo().performClick()
            compose.onNodeWithText("Status: $label",substring=true).assertExists()
            compose.onNodeWithText(action,substring=false).assertExists()
            compose.onNodeWithText("Cancel").performClick()
        }
        compose.onNodeWithText("LitterHopper™").performClick()
        compose.onNodeWithText("Disable",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.startsWith("Failed:") } }
    }

    @Test fun statusInformationAndOddPetCountsRemainReachable() {
        Repo.state.value=Repo.state.value.copy(pets=listOf(pet))
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithContentDescription("Info").performClick()
        compose.onNodeWithText("Every completed clean cycle",substring=true).assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithContentDescription("How SmartScale works").performClick()
        compose.onNodeWithText("The Litter-Robot weighs your cat",substring=true).assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText(pet.name).assertExists()
    }

    @Test fun poweredOffAndLockedRobotControlsUseTheCorrectConfirmation() {
        robotValues("unitPowerStatus" to "OFF","isKeypadLockout" to "true")
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Power",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Turn on?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Panel Lock").performClick()
        compose.onNodeWithText("Unlock panel?").assertExists()
        compose.onNodeWithText("Unlock",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.startsWith("Command failed:") } }
    }

    @Test fun cycleDelayHonorsBoundsAndSendsOnlyAChangedValue() {
        robotValues("cleanCycleWaitTime" to "3")
        show("Device Settings") { DeviceSettingsScreen(it,robot.serial) }
        compose.onNodeWithText("Cycle Delay").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Shorter").assertIsNotEnabled()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        repeat(27) { compose.onNodeWithContentDescription("Longer").performClick() }
        compose.onNodeWithContentDescription("Longer").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Shorter").performClick()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.startsWith("Failed:") } }
    }

    @Test fun mainShowsHome() = show("Home") { MainScreen(it) }
    @Test fun accountShowsPreferences() = show("Account") { AccountScreen(it) }
    @Test fun aboutShowsVersion() = show("App Version") { AboutScreen(it) }
    @Test fun regionShowsCountries() = show("Region") { RegionScreen(it) }
    @Test fun firmwareShowsUpdates() = show("Firmware Updates") { FirmwareScreen(it) }
    @Test fun unitsShowsMeasurements() = show("Measurement Units") { UnitsScreen(it) }
    @Test fun notificationsShowAlertSettings() = show("Notifications") { NotificationsScreen(it) }
    @Test fun messagesShowInbox() = show("Message Center") { MessageCenterScreen(it) }
    @Test fun robotShowsModel() = show(robot.model) { RobotScreen(it, robot.serial) }
    @Test fun settingsShowRobotControls() = show("Device Settings") { DeviceSettingsScreen(it, robot.serial) }
    @Test fun insightsShowPet() = show("${pet.name}’s Insights") { PetInsightsScreen(it, pet.id) }
    @Test fun profileShowsPet() = show(pet.name) { PetProfileScreen(it, pet.id) }
    @Test fun weightShowsHistory() = show("SmartScale® Details") { ScaleDetailsScreen(it, pet.id) }
    @Test fun visitsShowHistory() = show("Visit Details") { VisitDetailsScreen(it, pet.id) }
    @Test fun recapShowsDay() = show("Daily Recap") { DailyRecapScreen(it) }

    @Test fun mainTabsShowInsightsDevicesPetsAndShop() {
        show("Home") { MainScreen(it) }
        for (tab in listOf("Insights", "Devices", "Pets", "Shop", "Home")) {
            compose.onNodeWithText(tab).performClick()
        compose.mainClock.advanceTimeBy(500)
            compose.onAllNodesWithText(tab).onLast().assertIsDisplayed()
        }
    }

    @Test fun mainNavigationConnectsRobotPetHistoryRecapAndAccountScreens() {
        Repo.acceptIntro()
        val p=pet; val r=robot
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { Root() } } }
        compose.mainClock.advanceTimeBy(1500)
        fun back() { compose.onNodeWithContentDescription("Back").performClick(); compose.mainClock.advanceTimeBy(500) }
        compose.onNodeWithText(r.name,substring=false).performScrollTo().performClick()
        compose.onNodeWithText(r.model,substring=false).assertExists(); back()
        compose.onNodeWithText(p.name,substring=false).performScrollTo().performClick()
        compose.onNodeWithText("${p.name}’s Insights").assertExists(); back()
        compose.onNodeWithContentDescription("Pets").performClick()
        compose.onNodeWithText(p.name,substring=false).performClick()
        compose.onNodeWithText("SmartScale®",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("SmartScale® Details").assertExists()
        compose.onNodeWithContentDescription("Info").performClick()
        compose.onNodeWithText("How SmartScale® works").assertExists()
        compose.onNodeWithText("OK").performClick(); back()
        compose.onNodeWithText("Visits",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Visit Details").assertExists()
        compose.onAllNodesWithText("Pet Visit Recorded").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Visit Summary").assertExists(); back(); back(); back()
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.onNodeWithText("Daily Recap",substring=false).performClick()
        compose.onNodeWithText("Recap settings",substring=false).assertDoesNotExist()
        compose.onNodeWithContentDescription("Recap settings").assertExists(); back()
        compose.onNodeWithContentDescription("Messages").performClick()
        compose.onNodeWithText("Message Center").assertExists(); back()
        compose.onNodeWithContentDescription("Account").performClick()
        for((label,title) in listOf("Measurement Units" to "Measurement Units","Firmware Updates" to "Firmware Updates","Region" to "Region")) {
            compose.onNodeWithText(label,substring=false).performScrollTo().performClick()
            compose.onNodeWithText(title,substring=false).assertExists(); back()
        }
    }

    @Test fun anEmptyHomeExplainsRefreshFailuresAndKeepsSupportLinksAvailable() {
        Repo.state.value=AppState(error="Home Assistant unavailable",historyLoading=true)
        Repo.setShowBlogs(true)
        show("Home") { MainScreen(it) }
        compose.onNodeWithText("Couldn't refresh: Home Assistant unavailable").assertExists()
        compose.onNodeWithText("Tell ",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Learn more").performScrollTo().performClick()
        assertEquals("https://www.whisker.com/blog",urls.last())
        compose.onNodeWithText("Litter-Robot Support",substring=false).performScrollTo().performClick()
        assertTrue(urls.last().endsWith("/support/#contact-us"))
        assertTrue(Repo.state.value.pets.isEmpty()); assertTrue(Repo.state.value.robots.isEmpty())
    }
    @Test fun robotHistoryAndControlsAreReachable() {
        show(robot.model) { RobotScreen(it, robot.serial) }
        compose.onNodeWithText("History").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("Controls").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Manual Cycle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Manual Cycle").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Start a clean cycle?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun historyReassignmentUnassignmentAndUndoUpdateOnlyPhoneAssignments() {
        val p=pet; val other=Repo.state.value.pets.last(); val ts=nowMs()
        val a=Act(ts,"catWeight","10.0")
        Repo.state.value=Repo.state.value.copy(weights=mapOf(p.id to listOf(Reading(ts,10.0))),acts=mapOf(robot.serial to listOf(a)))
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithText("History",substring=false).performClick()
        fun menu() { compose.onNodeWithContentDescription("Show menu").performScrollTo().performClick() }
        menu(); compose.onNodeWithText("Unassign weight").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(Repo.local.value.overrides.isEmpty())
        menu(); compose.onNodeWithText("Unassign weight").performClick()
        compose.onNodeWithText("Unassign",substring=false).performClick()
        assertEquals("",Repo.local.value.overrides[ts])
        compose.onNodeWithText("Unassigned").assertExists()
        menu(); compose.onNodeWithText("Undo my change").performClick()
        assertTrue(Repo.local.value.overrides.isEmpty())
        menu(); compose.onNodeWithText("Reassign weight").performClick()
        compose.onNodeWithText(other.name).performClick()
        assertEquals(other.id,Repo.local.value.overrides[ts])
        assertEquals(10.0,Repo.state.value.history.getValue(other.id).single().lbs,0.0)
        assertEquals(listOf(a),Repo.state.value.acts[robot.serial])
    }

    @Test fun emptyRobotHistoryDistinguishesLoadingFromNoRecordedActivity() {
        Repo.state.value=Repo.state.value.copy(acts=emptyMap(),updatedAt=0,historyLoading=true)
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithText("History",substring=false).performClick()
        compose.onNodeWithText("Loading full history…").performScrollTo().assertExists()
        compose.runOnIdle { Repo.state.value=Repo.state.value.copy(historyLoading=false) }
        compose.onNodeWithText("No activity yet").assertExists()
        compose.onNodeWithText("Last updated: —").performScrollTo().assertExists()
        compose.onNodeWithText("Month",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Download Activity").performScrollTo().performClick()
        assertEquals("timestamp,event,label,value,pet,visit_min\n",sharedCsv)
    }

    @Test fun historyPaginationCanCollapseAndCsvRetainsUnknownActivityRecords() {
        val ts=nowMs()
        val events=listOf("litterHopperDispensed","robotStatusCatDetect","bonnetRemovedYes","robotCycleStatusIdle","DFIFullFlagOn","powerTypeDC","unknown-future-event")
            .mapIndexed { i,event -> Act(ts-i*60_000,event) }
        Repo.state.value=Repo.state.value.copy(acts=mapOf(robot.serial to events))
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithText("History",substring=false).performClick()
        compose.onNodeWithText("See More").performScrollTo().performClick()
        compose.onNodeWithText("See Less").performScrollTo().performClick()
        compose.onNodeWithText("See Less").assertDoesNotExist()
        compose.onNodeWithText("Download Activity").performScrollTo().performClick()
        assertTrue(sharedCsv!!.contains(",unknown-future-event,,,,"))
        assertEquals(events.size+1,sharedCsv!!.lines().size)
    }

    @Test fun changedLocalLightSettingsReachTheCorrectFirmwareEntities() {
        localHome()
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithText("Controls",substring=false).performClick()
        compose.onNodeWithText("Lights",substring=false).performScrollTo().performClick()
        compose.onNode(hasText("Off",substring=false) and hasAnyAncestor(isDialog())).performClick()
        compose.onAllNodesWithText("Low",substring=false).onFirst().performClick()
        compose.onAllNodesWithText("Low",substring=false).onLast().performClick()
        compose.onNodeWithText("Set",substring=false).performClick()
        compose.waitUntil(10000) { compose.waitForIdle();
            robot.nightLightMode=="OFF" && robot.raw.i("nightLightBrightness")==25 && robot.raw.i("panelBrightnessHigh")==25
        }
        assertTrue(messages.none { it.startsWith("Failed:") })
    }
    @Test fun robotLightsOfferModes() {
        show(robot.model) { RobotScreen(it, robot.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Lights").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Lights").onLast().assertIsDisplayed()
    }
    @Test fun robotSleepShowsSchedule() {
        show(robot.model) { RobotScreen(it, robot.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Sleep Mode").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Sleep Mode").onLast().assertIsDisplayed()
    }
    @Test fun cycleDelayCanBeEdited() {
        show("Device Settings") { DeviceSettingsScreen(it, robot.serial) }
        compose.onNodeWithText("Cycle Delay").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Cycle Delay").onLast().assertIsDisplayed()
    }
    @Test fun visitSummaryShowsSelectedVisit() {
        val reading = Repo.state.value.history[pet.id]!!.last()
        show("Visit Summary") { VisitSummaryScreen(it, pet.id, reading.ts) }
    }
    @Test fun onboardingShowsModeChoice() {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { Root() } } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Before you start").assertIsDisplayed()
    }
    @Test fun firstLaunchOffersCloudAndLocalWithoutConnecting() {
        Repo.logout()
        settings.putString("mode", null)
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { Root() } } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("How do you connect?").assertIsDisplayed()
        compose.onNodeWithText("Local: Home Assistant").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Before you start").assertIsDisplayed()
        compose.onNodeWithText("I understand").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Connect Home Assistant").assertIsDisplayed()
    }
    @Test fun privacyExplainsWhatTheAppStores() {
        show("Account") { AccountScreen(it) }
        compose.onNodeWithText("Meowstache Privacy").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithText("Meowstache Privacy").onLast().assertIsDisplayed()
    }
    @Test fun messageCenterReadsAndClearsSavedAlerts() {
        Repo.clearMessages()
        listOf("Drawer full", "Maintenance tip", "Cat visit", "Firmware update").forEach { Repo.addMessage(Message(nowMs(), it, "Test body")) }
        show("Message Center") { MessageCenterScreen(it) }
        assertTrue(Repo.messages.value.all { it.read })
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("No messages.", substring = true).assertExists()
        assertTrue(Repo.messages.value.isEmpty())
    }

    @Test fun unitsAndPrecisionPersistUserSelection() {
        Repo.setUnits("kg"); Repo.setDecimals(1)
        show("Measurement Units") { UnitsScreen(it) }
        compose.onNodeWithText("Imperial").performClick()
        assertEquals("lb", Repo.units.value)
        compose.onNodeWithText(Repo.weight(10.8, 2)).performClick()
        assertEquals(2, Repo.decimals.value)
        compose.onNodeWithText("Metric").performClick()
        assertEquals("kg", settings.getString("units"))
    }

    @Test fun regionChoiceChangesShopDestination() {
        show("Region") { RegionScreen(it) }
        for (region in Repo.regions) {
            compose.onNodeWithText(Repo.region()).performClick()
            compose.onAllNodesWithText(region).onLast().performClick()
            compose.onNodeWithText("Save").performClick()
            assertEquals(region, Repo.region())
            assertTrue(Repo.shopUrl().startsWith("https://"))
        }
    }

    @Test fun loginInformationShowsAccountBoundary() {
        show("Account") { AccountScreen(it) }
        compose.onNodeWithText("Login Information").performClick()
        compose.onNodeWithText("Signed in as", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun litterSettingsSurviveBackupAndRestore() = runBlocking<Unit> {
        val serial = robot.serial
        Repo.updateLocal { it.copy(litter = mapOf(serial to Litter("Test brand", "Clay"))) }
        val backup = Repo.exportBackup()
        Repo.updateLocal { it.copy(litter = emptyMap()) }
        Repo.importBackup(backup)
        assertEquals(Litter("Test brand", "Clay"), Repo.local.value.litter[serial])
    }

    @Test fun resetScoopsChangesOnlyLocalBaseline() {
        show("Device Settings") { DeviceSettingsScreen(it, robot.serial) }
        compose.onNodeWithText("Reset Scoops Saved").performScrollTo().performClick()
        compose.onNodeWithText("Reset Scoops Saved?").assertExists()
        compose.onNodeWithText("Reset").performClick()
        assertEquals(0, Repo.scoopsSaved(robot))
    }

    @Test fun notificationTargetShowsEditableWeight() {
        show("Notifications") { NotificationsScreen(it) }
        compose.onNodeWithText(pet.name).performScrollTo().performClick()
        compose.onNodeWithText("Target weight", substring = true).assertExists()
        compose.onNodeWithText("Save").performClick()
        assertTrue(Repo.alerts.value.targets.containsKey(pet.id))
    }

    @Test fun backupMergesHistoryAndRejectsWrongApplicationOrFutureVersion() = runBlocking<Unit> {
        val before = Repo.state.value
        val backup = Repo.exportBackup()
        Repo.state.value = before.copy(weights = emptyMap(), acts = emptyMap())
        Repo.importBackup(backup)
        assertEquals(before.weights, Repo.state.value.weights)
        assertEquals(before.acts, Repo.state.value.acts)
        try { Repo.importBackup(backup.replace("meowstache", "other-app")); fail("Wrong application accepted") } catch (_: IllegalArgumentException) { }
        try { Repo.importBackup(backup.replace("\"version\":1", "\"version\":999")); fail("Future backup accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun settingsAndAlertDeduplicationRoundTrip() {
        Repo.setShowBlogs(true); assertTrue(Repo.showBlogs.value)
        Repo.setShowUnavailable(false); assertFalse(Repo.showUnavailable.value)
        Repo.setInstantNotifications(true); assertTrue(Repo.instantNotifications)
        Repo.acceptIntro(); assertTrue(Repo.introAccepted)
        Repo.markSent(listOf("one", "two")); assertEquals(setOf("one", "two"), Repo.sentAlertKeys())
        settings.putString("firmwareCheckedAt", null)
        assertTrue(Repo.firmwareCheckDue()); assertFalse(Repo.firmwareCheckDue())
        Repo.updateLocal { LocalData() }
        Repo.reassign(123L, pet.id); assertEquals(pet.id, Repo.state.value.overrides[123L])
        for ((signal,label) in listOf(null to "Unknown", -55 to "Good", -65 to "Fair", -90 to "Poor")) assertEquals(label, Repo.signal(signal))
    }

    @Test fun demoCommandsCannotOperateARealRobot() = runBlocking<Unit> {
        assertTrue(Repo.command(robot, "startCycle").isFailure)
        assertTrue(Repo.rename(robot, "New name").isFailure)
        assertTrue(Repo.toggleHopper(robot, true).isFailure)
        assertTrue(Repo.triggerFirmwareUpdate(robot).isFailure)
        assertTrue(Repo.latestFirmware(robot)!!.contains("Up to date"))
        assertTrue(Repo.setTrackerWeight(pet, 10.0).isFailure)
        assertTrue(Repo.resetTracker(pet).isFailure)
        assertTrue(Repo.addPet("Another", 10.0, setOf(robot.serial)).isFailure)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun localDemoRepositoryKeepsPetHistoryThroughRenaming() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            Repo.setMode(true); Repo.startDemo(); Repo.refresh()
            val r = Repo.state.value.robots.first()
            val p = Repo.state.value.pets.first()
            assertTrue(Repo.command(r,"setClumpTime","{\"clumpTime\":10}").isSuccess)
            assertTrue(Repo.rename(r,"Renamed robot").isSuccess)
            assertTrue(Repo.toggleHopper(r,true).isSuccess)
            assertTrue(Repo.setSleep(r,"Saturday",true,1320,420).isSuccess)
            assertTrue(Repo.setTrackerWeight(p,10.0).isSuccess)
            assertTrue(Repo.resetTracker(p).isSuccess)
            Repo.updateLocal { it.copy(petNotes=mapOf(p.id to PetNotes(adoptionDetails="Shelter"))) }
            Repo.reassign(123,p.id)
            assertTrue(Repo.renamePet(p,"Renamed cat").isSuccess)
            val id = haPetId("Renamed cat")
            assertEquals("Shelter",Repo.local.value.petNotes[id]?.adoptionDetails)
            assertEquals(id,Repo.local.value.overrides[123])
            assertTrue(Repo.haDiagnostics("test").contains("mapped"))
            assertTrue(Repo.latestFirmware(r)!!.contains("ESPHome"))
            assertTrue(Repo.triggerFirmwareUpdate(r).isFailure)
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    private fun localHome() {
        val snap = runBlocking { LocalHa(FakeHomeAssistant()).load(nowMs()-365L*86_400_000) }
        Repo.setMode(true)
        Repo.state.value=AppState(robots=snap.robots,pets=snap.pets,weights=snap.weights,acts=snap.acts)
    }

    @Test fun localSleepShowsEachDayAndTimeEditor() {
        localHome()
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Sleep Mode").performScrollTo().performClick()
        compose.onNodeWithText("Mon").assertExists()
        val sleep = robot.raw.o("weekdaySleepModeEnabled")?.o("Monday")?.i("sleepTime") ?: 1320
        compose.onAllNodesWithText("%02d:%02d".format(sleep/60,sleep%60)).onFirst().performClick()
        compose.onNodeWithText("Monday sleep time").assertExists()
        compose.onAllNodesWithText("Cancel").onLast().performClick()
    }

    @Test fun localSettingsExplainDisabledFirmwareEntities() {
        localHome()
        val last = Repo.state.value.robots.last()
        show("Device Settings") { DeviceSettingsScreen(it,last.serial) }
        compose.onNodeWithText("More controls in Home Assistant").performScrollTo().assertIsDisplayed()
    }

    @Test fun profileNotesCanBeReviewedAndChanged() {
        show(pet.name) { PetProfileScreen(it,pet.id) }
        compose.onNodeWithText("Adoption Details").performScrollTo().performClick()
        compose.onNodeWithText("Shelter/Rescue").performClick()
        assertEquals("Shelter/Rescue",Repo.local.value.petNotes[pet.id]?.adoptionDetails)
        compose.onNodeWithText("Feeding Time").performScrollTo().performClick()
        compose.onNodeWithText("Free Feeding").performClick()
        assertEquals("Free Feeding",Repo.local.value.petNotes[pet.id]?.feedingTime)
    }

    @Test fun acceptedIntroReachesCloudLoginAndLocalWelcome() {
        Repo.logout(); Repo.setMode(false); Repo.acceptIntro()
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { Root() } } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Register").assertExists()
        compose.onNodeWithText("Login").performClick()
        compose.onNodeWithText("Email").assertExists()
        compose.onNodeWithText("Forgot password?").performClick()
    }

    @Test fun cloudControlsRequireConfirmationBeforeResetOrReplacingFilter() {
        show(robot.model) { RobotScreen(it,robot.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Reset Robot").performScrollTo().performClick()
        compose.onNodeWithText("Reset robot?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Replace Filter").performScrollTo().performClick()
        compose.onNodeWithText("Replaced").performClick()
        assertTrue(Repo.local.value.filterAt.containsKey(robot.serial))
    }

    @Test fun homeDeviceLayoutCanShowACompactGrid() {
        show("Home") { MainScreen(it) }
        compose.onAllNodesWithText("Devices").onFirst().onParent().onChildren().filter(hasClickAction()).onLast().performClick()
        compose.onNodeWithText("Waste:").assertExists()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun visitHistoryCanBeExportedAsCsv() {
        show("${pet.name}’s Insights") { PetInsightsScreen(it,pet.id) }
        compose.onNodeWithText("Export full history (CSV)").performScrollTo().performClick()
        assertTrue(sharedCsv!!.startsWith("timestamp,weight_lbs,weight_kg,duration_min\n"))
        assertTrue(sharedCsv!!.lines().size > 1)
    }

    @Test fun recapSettingsControlReminderTimeAndPatternAlerts() {
        show("Daily Recap") { DailyRecapScreen(it) }
        compose.onNodeWithContentDescription("Recap settings").performClick()
        compose.onNodeWithText("Recap notifications").assertExists()
        if(!Repo.alerts.value.recapEnabled) compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText(hourLabel(9)).performClick()
        assertEquals(9,Repo.alerts.value.recapHour)
        val patterns=Repo.alerts.value.patternsEnabled
        compose.onAllNodes(isToggleable()).onLast().performClick()
        assertEquals(!patterns,Repo.alerts.value.patternsEnabled)
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Previous week").performClick()
        compose.onNodeWithContentDescription("Next week").performClick()
    }

    @Test fun recapListsTheVisitsForTheSelectedDay() {
        val st=Repo.state.value
        val p=st.pets.first()
        val day = java.time.LocalDate.now()
        // Subtracting five minutes from wall-clock time puts visits on yesterday near midnight.
        val noon = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Repo.state.value=st.copy(weights=mapOf(p.id to (0..5).map { Reading(noon-it*60_000,10.0) }))
        show("Daily Recap") { DailyRecapScreen(it) }
        compose.onNodeWithText("%02d".format(day.dayOfMonth)).performClick()
        compose.onAllNodesWithText("Visit Breakdown").onFirst().assertExists()
        compose.onAllNodesWithText("See More").onFirst().performScrollTo().performClick()
        compose.onAllNodesWithText("Visit Breakdown").onFirst().assertExists()
    }

    @Test fun backupAndRestoreActionsReportSuccessCancellationAndFailures() {
        show("Account") { AccountScreen(it) }
        compose.onNodeWithText("Back up history").performScrollTo().performClick()
        assertTrue(savedBackup!!.contains("meowstache")); assertEquals("History backed up", messages.last())
        saveError = java.io.IOException("disk full")
        compose.onNodeWithText("Back up history").performClick()
        assertEquals("Backup failed: disk full", messages.last())
        compose.onNodeWithText("Restore history").performScrollTo().performClick()
        assertEquals(2, messages.size) // Cancelling the picker must not report a restore.
        openedFile = Result.success(savedBackup!!)
        compose.onNodeWithText("Restore history").performClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.startsWith("Restored ·") } }
        openedFile = Result.success("not a backup")
        compose.onNodeWithText("Restore history").performClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.last().startsWith("Restore failed:") }
        openedFile = Result.failure(java.io.IOException("file unavailable"))
        compose.onNodeWithText("Restore history").performClick()
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.last() == "Restore failed: file unavailable" }
    }

    @Test fun accountLinksUseTheSelectedRegionalShopAndExplainUnavailableFeatures() {
        Repo.updateLocal { it.copy(region = "United Kingdom") }
        show("Account") { AccountScreen(it) }
        for (label in listOf("Addresses", "Payment Methods", "Referral Dashboard", "Orders", "Subscriptions", "Returns", "Release Notes")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        assertTrue(urls.contains(Repo.storeUrl("customer/address/")))
        assertTrue(urls.last().endsWith("/tag/v1.1.0"))
        compose.onNodeWithContentDescription("Info").performScrollTo().performClick()
        compose.onNodeWithText("Unavailable features").assertExists()
        compose.onNodeWithText("About the firmware").performClick()
        assertTrue(urls.last().contains("esphome-litter-robot"))
        compose.onNodeWithText("OK").performClick()
        for (label in listOf("Email Meowstache", "Report a Meowstache Issue", "Litter-Robot Support", "Litter-Robot FAQ", "Buy me a coffee", "Litter-Robot 4 Guide")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        assertTrue(urls.any { it.startsWith("mailto:") })
        assertTrue(urls.any { it.endsWith("/issues") })
    }

    @Test fun notificationChoicesPersistAndScheduleChecksAndCanDisableATarget() {
        show("Notifications") { NotificationsScreen(it) }
        fun toggle(label: String) {
            compose.onNodeWithText(label).performScrollTo()
            val index = listOf("No litter box visit", "Too many visits", "Unusual patterns", "Daily recap").indexOf(label)
            compose.onAllNodes(isToggleable()).filter(hasAnySibling(hasText("Pet Alerts")))[index].performClick()
            compose.waitForIdle()
        }
        toggle("No litter box visit")
        compose.onNodeWithText("3 days").performScrollTo().performClick()
        assertEquals(3, Repo.alerts.value.noVisitDays)
        toggle("Too many visits")
        assertTrue("Too many visits not enabled: ${Repo.alerts.value}", Repo.alerts.value.tooManyEnabled)
        compose.onNodeWithText("12").performScrollTo().performClick()
        assertEquals(12, Repo.alerts.value.tooManyVisits)
        toggle("Unusual patterns")
        toggle("Daily recap")
        compose.onNodeWithText(hourLabel(10)).performScrollTo().performClick()
        assertEquals(10, Repo.alerts.value.recapHour)
        compose.onNodeWithText(pet.name).performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText(pet.name).performClick()
        compose.onNodeWithText("Turn off").performClick()
        assertFalse(Repo.alerts.value.targets.containsKey(pet.id))
        compose.onNodeWithText("Check now").performScrollTo().performClick()
        assertNotNull(notified); assertTrue(alertsScheduled >= 8)
        assertTrue(messages.last().contains("report") || messages.last().contains("alert(s)"))
    }

    @Test fun firmwareDetailsAndAboutLinksAreReachable() {
        show("Firmware Updates") { FirmwareScreen(it) }
        compose.onNodeWithText("Firmware details").performClick()
        compose.onNodeWithText("Firmware Information").assertExists()
        compose.onNodeWithText("OK").performClick()
    }

    @Test fun firmwareInstallRequiresConfirmationAndReportsEachBackendOutcome() {
        val prefs=Repo::class.java.getDeclaredField("prefs").apply { isAccessible=true }.get(Repo) as KeyValue
        prefs.putBoolean("demo",false)
        val field=Repo::class.java.getDeclaredField("api").apply { isAccessible=true }; val original=field.get(Repo)
        val jwt="header."+kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode("""{"mid":"owner","exp":4102444800}""".encodeToByteArray())+".sig"
        val secrets=object : SecretStore {
            override fun get(key: String)=if(key=="id") jwt else null
            override fun set(key: String,value: String?)=Unit
            override fun clear()=Unit
        }
        var outcome=0; var requests=0
        io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            val query=json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject.s("query").orEmpty()
            val body=if("getFirmwareDetails" in query) """{"data":{"litterRobot4CompareFirmwareVersion":{"isEspFirmwareUpdateNeeded":true,"latestFirmware":{"espFirmwareVersion":"2","picFirmwareVersion":"3","laserBoardFirmwareVersion":"4"}}}}"""
            else {
                requests++
                if(outcome==2) """{"errors":[{"message":"Update denied"}]}""" else
                    """{"data":{"litterRobot4TriggerFirmwareUpdate":{"isUpdateTriggered":${outcome==1}}}}"""
            }
            respond(body)
        }).use { http ->
            field.set(Repo,Api(Settings(),secrets,http))
            try {
                val label="Install update on ${robot.name}"
                show("Firmware Updates") { FirmwareScreen(it) }
                compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.onNodeWithText("Cancel").performClick()
                assertEquals(0,requests)
                for((result,message) in listOf(0 to "Update not started",1 to "Update started",2 to "Failed: Update denied")) {
                    outcome=result
                    compose.onNodeWithText(label).performClick()
                    compose.onNodeWithText("Install",substring=false).performClick()
                    compose.waitUntil(5000) { compose.waitForIdle(); messages.contains(message) }
                }
                assertEquals(3,requests)
            } finally { field.set(Repo,original) }
        }
    }

    @Test fun visitSummaryWithoutRobotOrDurationKeepsUnavailableFeaturesExplicit() {
        val p=pet; val ts=nowMs()
        Repo.state.value=AppState(pets=listOf(p),weights=mapOf(p.id to listOf(Reading(ts,10.0))))
        show("Visit Summary") { VisitSummaryScreen(it,p.id,ts) }
        compose.onNodeWithText("Litter-Robot",substring=false).assertExists()
        compose.onNodeWithText("—min",substring=false).assertExists()
        compose.onNodeWithText("Waste analysis (urine / feces)").performClick()
        assertEquals("Waste analysis",Repo.askUnavailable.value)
        compose.onNodeWithText("Needs a camera model (Litter-Robot 5 Pro)").performClick()
        assertEquals("Pictures and videos of a visit",Repo.askUnavailable.value)
        compose.runOnIdle { Repo.setShowUnavailable(false) }
        compose.onNodeWithText("Pictures & Videos").assertDoesNotExist()
    }

    @Test fun sparsePetProfilesShowMissingDataAndKeepLocalNotesEditable() {
        val id=pet.id
        val p=Pet(kotlinx.serialization.json.buildJsonObject { put("petId",id); put("name","New cat") })
        Repo.state.value=AppState(pets=listOf(p))
        show("New cat") { PetProfileScreen(it,id) }
        compose.onNodeWithText("Last Visit:",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Feeding Time").performScrollTo().performClick()
        compose.onNodeWithText("Specific Times").performClick()
        assertEquals("Specific Times",Repo.local.value.petNotes[id]!!.feedingTime)
        compose.onNodeWithText("Personality",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Curious").performClick()
        compose.onNodeWithText("Playful").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("Playful","Curious"),Repo.local.value.petNotes[id]!!.personality)
    }

    @Test fun unavailableFeatureDialogCanHideUnsupportedControls() {
        show("Home") { MainScreen(it); UnavailableDialog() }
        compose.runOnIdle { askUnavailable("Network setup") }
        compose.onNodeWithText("Only in Whisker's app").assertExists()
        compose.onNodeWithText("Network setup is only available", substring=true).assertExists()
        compose.onNodeWithText("Hide unavailable features").performClick()
        assertFalse(Repo.showUnavailable.value); assertNull(Repo.askUnavailable.value)
    }

    @Test fun chartTapSelectsTheReadingAndAreaChartsRenderMissingValuesAndPatterns() {
        val displayed = mutableListOf<Double>()
        compose.setContent { AppTheme {
            androidx.compose.foundation.layout.Column {
                Chart(listOf(1.0,3.0,2.0),listOf("First","Middle","Last"),ChartKind.LINE,
                    modifier=androidx.compose.ui.Modifier.then(androidx.compose.ui.Modifier.testTag("readings")),
                    marks=listOf(1,0,-1),tooltip={ displayed += it; "Reading $it" })
                Chart(listOf(1.0,null,4.0,2.0),listOf("A","B","C","D"),ChartKind.AREA)
                LevelBar(2f,W.Teal,mid=true)
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("readings").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*.12f,height*.5f)) }
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
        }
        assertTrue(displayed.contains(1.0))
    }

    @Test fun freshAlertsAreDeduplicatedAndUpdatesDoNotCreateDuplicateInboxMessages() {
        val first=PetAlert("test-first",pet.id,"A visit","First reading",slot="visit")
        assertEquals(listOf(first),Repo.takeFresh(listOf(first)))
        assertTrue(Repo.takeFresh(listOf(first)).isEmpty())
        val update=first.copy(key="test-second",body="Visit duration now known",update=true)
        Repo.takeFresh(listOf(update))
        assertEquals(1,Repo.messages.value.size)
        assertTrue(Repo.sentAlertKeys().containsAll(listOf(first.key,update.key)))
    }

    @Test fun robotUsageTopicsCanBeExpandedAndChangedIndependently() {
        show("Notifications") { NotificationsScreen(it) }
        val heading=compose.onNodeWithText("Robot Usage").fetchSemanticsNode().boundsInRoot
        compose.onNode(SemanticsMatcher("Usage expander") { node ->
            node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                kotlin.math.abs(node.boundsInRoot.center.y-heading.center.y)<2 && node.boundsInRoot.right<heading.left
        }).performClick()
        if (!Repo.alerts.value.robot.usage) {
            compose.onNodeWithText("Robot Usage").performScrollTo()
            val label=compose.onNodeWithText("Robot Usage").fetchSemanticsNode().boundsInRoot
            compose.onNode(isToggleable() and SemanticsMatcher("Usage switch") { node ->
                kotlin.math.abs(node.boundsInRoot.center.y-label.center.y)<2 && node.boundsInRoot.left>label.right
            }).performClick()
        }
        val before=Repo.alerts.value.robot
        for(label in listOf("Drawer full","Low Litter","Clean Cycle Complete","Clean Cycle Interrupted","Cat Visit Summary","Maintenance Tips")) {
            compose.onNodeWithText(label).performScrollTo()
            val bounds=compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            compose.onNode(isToggleable() and SemanticsMatcher("Switch for $label") { node ->
                kotlin.math.abs(node.boundsInRoot.center.y-bounds.center.y)<2 && node.boundsInRoot.left>bounds.right
            }).performClick()
        }
        val after=Repo.alerts.value.robot
        assertEquals(!before.drawerFull,after.drawerFull); assertEquals(!before.lowLitter,after.lowLitter)
        assertEquals(!before.cycleComplete,after.cycleComplete); assertEquals(!before.cycleInterrupted,after.cycleInterrupted)
        assertEquals(!before.visits,after.visits); assertEquals(!before.maintenance,after.maintenance)
        compose.onNodeWithText("Cat Detected In Drawer").performScrollTo().assertExists()
        assertTrue(alertsScheduled>=6)
    }

    @Test fun localDiagnosticsRequireConfirmationAndReportSaveFailures() {
        Repo.setMode(true); Repo.startDemo()
        show("Account") { AccountScreen(it) }
        compose.onNodeWithText("Home Assistant diagnostics").performScrollTo().performClick()
        assertNull(savedBackup)
        compose.onNodeWithText("Save file").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Diagnostics saved") }
        assertTrue(savedBackup!!.contains("1.1.0"))
        saveError=java.io.IOException("storage unavailable")
        compose.onAllNodesWithText("Home Assistant diagnostics").filter(hasClickAction()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Save file").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Couldn't save: storage unavailable") }
        compose.onNodeWithText("Meowstache Privacy").performScrollTo().performClick()
        compose.onNodeWithText("ESPHome firmware").performClick()
        assertTrue(urls.last().contains("esphome-litter-robot"))
        compose.onNodeWithText("ESPHome firmware").assertDoesNotExist()
        compose.onNodeWithText(Repo.email()).performScrollTo().performClick()
        compose.onNodeWithText("To disconnect for good",substring=true).assertExists()
    }

    @Test fun instantNotificationChoicePersistsAndExplainsItsBackgroundConnection() {
        (Repo::class.java.getDeclaredField("prefs").apply { isAccessible=true }.get(Repo) as KeyValue).putBoolean("demo",false)
        Repo.setMode(false); Repo.setInstantNotifications(false)
        show("Notifications") { NotificationsScreen(it) }
        compose.onNodeWithText("Instant Notifications").performScrollTo()
        val label=compose.onNodeWithText("Instant Notifications").fetchSemanticsNode().boundsInRoot
        compose.onNode(isToggleable() and SemanticsMatcher("Instant notification switch") { node ->
            kotlin.math.abs(node.boundsInRoot.center.y-label.center.y)<2 && node.boundsInRoot.left>label.right
        }).performClick()
        assertTrue(Repo.instantNotifications); assertEquals(1,instantSyncs)
        compose.onNodeWithText("as soon as your robot changes",substring=true).assertExists()
        compose.onNode(hasContentDescription("Info") and SemanticsMatcher("Instant notification help") { node ->
            kotlin.math.abs(node.boundsInRoot.center.y-label.center.y)<2
        }).performClick()
        compose.onNodeWithText("every 2 minutes",substring=true).assertExists()
    }

}
