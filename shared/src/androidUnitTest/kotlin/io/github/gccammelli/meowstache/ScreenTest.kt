package io.github.gccammelli.meowstache

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.gccammelli.meowstache.ui.*
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
    private val platform = object : PlatformUi {
        override val appId = "test"
        override val appVersion = "1.1.0"
        override val buildNumber = 1
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) = Unit
        override fun shareFile(mime: String, name: String, text: String) = Unit
        override fun saveFile(suggestedName: String, text: String, done: (Throwable?) -> Unit) = done(null)
        override fun openFile(done: (Result<String>?) -> Unit) = done(null)
        override fun scheduleAlerts() = Unit
        override fun notifyNew(alerts: List<PetAlert>) = Unit
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
        Repo.startDemo()
    }

    private fun show(title: String, content: @Composable (NavHostController) -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { content(rememberNavController()) } } }
        compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        }
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
}
