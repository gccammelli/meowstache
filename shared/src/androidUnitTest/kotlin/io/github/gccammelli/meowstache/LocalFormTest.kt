package io.github.gccammelli.meowstache

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import io.github.gccammelli.meowstache.ui.*
import io.ktor.client.engine.mock.respond
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Fixed screen-size qualifiers trigger Robolectric issue #8460 for dialog text fields.
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val messages = mutableListOf<String>()
    private var sharedCsv: String? = null
    private val urls = mutableListOf<String>()
    private var usesInstantTasks = false
    private val testHttpClients = mutableListOf<io.ktor.client.HttpClient>()
    private val platform = object : PlatformUi {
        override val appId = "test"
        override val appVersion = "1.1.0"
        override val buildNumber = 1
        override fun openUrl(url: String) { urls += url }
        override fun toast(message: String) { messages += message }
        override fun shareFile(mime: String, name: String, text: String) { sharedCsv=text }
        override fun saveFile(suggestedName: String, text: String, done: (Throwable?) -> Unit) = done(null)
        override fun openFile(done: (Result<String>?) -> Unit) = done(null)
        override fun scheduleAlerts() = Unit
        override fun notifyNew(alerts: List<PetAlert>) = Unit
    }

    @Before fun home() {
        // Repo is a process singleton; each form test owns its storage and startup observer.
        Repo::class.java.getDeclaredField("api").apply { isAccessible=true }.set(Repo,null)
        Repo.init(androidStorage(ApplicationProvider.getApplicationContext()))
        Repo.logout(); Repo.setMode(true); Repo.setUnits("kg"); Repo.setShowUnavailable(true)
        Repo.updateLocal { LocalData() }
        Repo.startDemo()
        compose.waitUntil(5000) { compose.waitForIdle(); Repo.state.value.robots.isNotEmpty() }
    }

    @After fun disconnect() {
        Repo.logout()
        val scope = Repo::class.java.getDeclaredField("scope").apply { isAccessible=true }.get(Repo) as kotlinx.coroutines.CoroutineScope
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        testHttpClients.forEach { it.close() }
        if(usesInstantTasks) androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
        Repo::class.java.getDeclaredField("api").apply { isAccessible=true }.set(Repo,null)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { content() } } }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun addingACatValidatesRequiredFieldsAndAssignsAFreeTrackerSlot() {
        show { MainScreen(rememberNavController()) }
        compose.onNodeWithText("Add a pet").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Test cat")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Weight (kg)").performTextInput("4,5")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Add", substring = false).assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.contains("added") || it.startsWith("Added") } }
        assertTrue(Repo.state.value.pets.any { it.name == "Test cat" })
    }

    @Test fun litterDetailsAreTrimmedAndSavedOnThePhone() {
        val robot = Repo.state.value.robots.first()
        show { DeviceSettingsScreen(rememberNavController(), robot.serial) }
        compose.onNodeWithText("Litter", substring = false).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Litter Brand").performTextInput(" Test brand ")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Litter Formulation").performTextInput(" Clay ")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Save").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(Litter("Test brand", "Clay"), Repo.local.value.litter[robot.serial])
    }

    @Test fun petEditorRenamesTrackerAndPreservesExistingPhoneHistory() {
        val pet = Repo.state.value.pets.first()
        val history = Repo.state.value.weights[pet.id]
        show { PetProfileScreen(rememberNavController(), pet.id) }
        compose.onNodeWithText("Edit Profile").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextReplacement("Renamed test cat")
        compose.onNodeWithText("Set tracker weight (kg)").performTextInput("4.5")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it == "Renamed to Renamed test cat" } }
        val renamed = Repo.state.value.pets.first { it.name == "Renamed test cat" }
        assertEquals(history, Repo.state.value.weights[renamed.id])
        assertEquals(4.5 / LBS_TO_KG, renamed.referenceLbs!!, 0.01)
    }

    @Test fun newCatCannotBeAddedWithZeroWeightOrWithoutASelectedRobot() {
        show { MainScreen(rememberNavController()) }
        compose.onNodeWithText("Add a pet").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextInput("Pixel")
        compose.onNodeWithText("Weight (kg)").performTextInput("0")
        compose.onNodeWithText("Add",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Weight (kg)").performTextReplacement("4,5")
        val count=compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        repeat(count) { compose.onAllNodes(isToggleable())[it].performClick() }
        compose.onNodeWithText("Add",substring=false).assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("Add",substring=false).assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(Repo.state.value.pets.any { it.name=="Pixel" })
    }

    private fun refuseTrackerChanges() {
        val field=Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }
        val original=(field.get(Repo) as LocalHa).backend
        field.set(Repo,LocalHa(object : HaBackend by original {
            override suspend fun post(path: String,body: String): String=throw ApiException("Service unavailable")
            override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
                if(commands.any { it.s("type")?.endsWith("/update")==true }) throw ApiException("Admin permission required")
                return original.ws(commands)
            }
        }))
    }

    @Test fun failedTrackerWeightRenameAndResetShowTheirErrorsWithoutRemovingThePet() {
        val pet=Repo.state.value.pets.first(); refuseTrackerChanges()
        show { PetProfileScreen(rememberNavController(),pet.id) }
        compose.onNodeWithText("Edit Profile").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextReplacement("Pixel")
        compose.onNodeWithText("Set tracker weight (kg)").performTextInput("4,5")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Rename failed: Admin permission required") }
        assertTrue(messages.contains("Failed: Service unavailable"))
        compose.onNodeWithText("Edit Profile").performClick()
        compose.onNodeWithText("Reset tracker…").performClick()
        compose.onNodeWithText("Reset",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.count { it=="Failed: Service unavailable" }==2 }
        assertTrue(Repo.state.value.pets.any { it.id==pet.id })
    }

    @Test fun resettingATrackerRequiresConfirmationAndPreservesPhoneHistory() {
        val pet=Repo.state.value.pets.first(); Repo.setUnits("lb")
        val history=Repo.state.value.weights[pet.id]
        show { PetProfileScreen(rememberNavController(),pet.id) }
        compose.onNodeWithText("Edit Profile").performScrollTo().performClick()
        compose.onNodeWithText("Set tracker weight (lbs)").performTextInput("invalid")
        compose.onNodeWithText("Reset tracker…").performClick()
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        assertTrue(Repo.state.value.pets.any { it.id==pet.id })
        compose.onNodeWithText("Reset tracker…").performClick()
        compose.onNodeWithText("Reset",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); Repo.state.value.pets.none { it.id==pet.id } }
        assertEquals(history,Repo.state.value.weights[pet.id])
    }

    @Test fun robotNicknameCanBeChangedThroughTheLocalBackend() {
        val robot = Repo.state.value.robots.first()
        show { DeviceSettingsScreen(rememberNavController(), robot.serial) }
        compose.onNodeWithText("Device Nickname").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Test robot name")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); Repo.state.value.robots.any { it.name == "Test robot name" } }
        assertTrue(messages.none { it.startsWith("Rename failed") })
    }

    @Test fun videoFolderCanBeValidatedConnectedAndTurnedOff() {
        val demo = FakeHomeAssistant()
        var fail = true
        val backend = object : HaBackend by demo {
            override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = commands.map { c ->
                if(c.s("type") == "media_source/browse_media") {
                    if(fail) throw ApiException("Media folder unavailable")
                    buildJsonObject { put("children",buildJsonArray {
                        add(buildJsonObject { put("title","2026-10-23T08-30-00.mp4"); put("media_content_id","test-clip") })
                    }) }
                } else demo.ws(listOf(c)).first()
            }
        }
        Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }.set(Repo,LocalHa(backend))
        (Repo::class.java.getDeclaredField("prefs").apply { isAccessible=true }.get(Repo) as KeyValue).putBoolean("demo",false)
        show { AccountScreen(rememberNavController()) }
        compose.onNodeWithText("Visit Videos",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Media folder").performTextReplacement("")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Media folder").performTextInput("test-clips")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Media folder unavailable").fetchSemanticsNodes().isNotEmpty() }
        fail = false
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.startsWith("Connected ·") } }
        assertEquals("test-clips",Repo.clipsFolder)
        compose.onNodeWithText("Visit Videos",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Turn off").performClick()
        assertNull(Repo.clipsFolder); assertTrue(Repo.clips.value.isEmpty())
    }

    @Test fun cloudVideosValidateCredentialsReportErrorsAndSaveAWorkingConnection() {
        (Repo::class.java.getDeclaredField("prefs").apply { isAccessible=true }.get(Repo) as KeyValue).putBoolean("demo",false)
        val tokens=mutableMapOf<String,String>()
        Repo::class.java.getDeclaredField("secrets").apply { isAccessible=true }.set(Repo,object : SecretStore {
            override fun get(key: String)=tokens[key]
            override fun set(key: String,value: String?) { if(value==null) tokens.remove(key) else tokens[key]=value }
            override fun clear()=tokens.clear()
        })
        Repo.setMode(false)
        show { AccountScreen(rememberNavController()) }
        compose.onNodeWithText("Visit Videos").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Setup guide").performClick()
        assertTrue(urls.last().endsWith("/docs/visit-videos.md"))
        compose.onNodeWithText("Long-lived access token").performTextInput("test-token")
        compose.onNodeWithText("Away address (optional)").performTextInput("https://away.example")
        compose.onNodeWithText("Away address (optional)").performTextClearance()
        compose.onNodeWithText("Media folder").performTextReplacement(" / ")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Enter the media folder's name").fetchSemanticsNodes().isNotEmpty() }
        HaSocket(commandsPerSession=1,result={ kotlinx.serialization.json.buildJsonObject {
            put("children",kotlinx.serialization.json.JsonArray(emptyList()))
        } }).use { peer ->
            compose.onNodeWithText("Home Assistant address").performTextReplacement(peer.url)
            compose.onNodeWithText("Media folder").performTextReplacement("visits")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.startsWith("Connected · no videos yet") } }
            assertEquals("visits",Repo.clipsFolder)
        }
        compose.onNodeWithText("Visit Videos").performClick()
        compose.onNodeWithText("Turn off").performClick()
        assertNull(Repo.clipsFolder)
    }


    @Test fun localControlsConfirmPowerPanelAndCycleCommands() {
        val robot = Repo.state.value.robots.first()
        show { RobotScreen(rememberNavController(),robot.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Power",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Turn Off").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Sent") }
        compose.onNodeWithText("Panel Lock").performScrollTo().performClick()
        compose.onNodeWithText("Lock",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); Repo.state.value.robots.first().panelLocked }
        compose.onNodeWithText("Manual Cycle").performScrollTo().performClick()
        compose.onNodeWithText("Start",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.count { it=="Sent" } >= 3 }
        compose.onNodeWithText("Empty Litter").performScrollTo().performClick()
        compose.onNodeWithText("Empty all litter?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(messages.any { it.startsWith("Command failed:") })
    }

    @Test fun lightAndSleepChangesReachTheLocalRobot() {
        val robot = Repo.state.value.robots.first()
        show { RobotScreen(rememberNavController(),robot.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Lights",substring=false).performScrollTo().performClick()
        compose.onAllNodesWithText("Off",substring=false).onLast().performClick()
        compose.onAllNodesWithText("Low",substring=false).onFirst().performClick()
        compose.onAllNodesWithText("Low",substring=false).onLast().performClick()
        compose.onNodeWithText("Set",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.count { it=="Sent" } >= 3 }
        compose.onNodeWithText("Sleep Mode",substring=false).performScrollTo().performClick()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("Save",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Sleep schedule saved") }
        assertFalse(messages.any { it.startsWith("Failed:") })
    }

    @Test fun robotHistoryExportsActivitiesAndSupportsReassigningAReading() {
        val st=Repo.state.value
        val robot=st.robots.first()
        val reading=st.acts.getValue(robot.serial).first { it.value=="catWeight" }
        Repo.state.value=st.copy(acts=mapOf(robot.serial to listOf(reading)))
        show { RobotScreen(rememberNavController(),robot.serial) }
        compose.onNodeWithText("History",substring=false).performClick()
        compose.onNodeWithText("Download Activity").performScrollTo().performClick()
        assertTrue(sharedCsv!!.startsWith("timestamp,event,label,value,pet,visit_min\n"))
        compose.onNodeWithContentDescription("Show menu").performScrollTo().performClick()
        compose.onNodeWithText("Unassign weight",substring=false).performClick()
        compose.onNodeWithText("Unassign",substring=false).performClick()
        val key=Repo.readingKey(reading)
        assertEquals("",Repo.local.value.overrides[key])
        compose.onNodeWithContentDescription("Show menu").performClick()
        compose.onNodeWithText("Undo my change").performClick()
        assertFalse(Repo.local.value.overrides.containsKey(key))
        compose.onNodeWithContentDescription("Show menu").performClick()
        compose.onNodeWithText("Reassign weight").performClick()
        compose.onAllNodesWithText(st.pets.last().name,substring=false).filter(hasClickAction()).onFirst().performClick()
        assertEquals(st.pets.last().id,Repo.local.value.overrides[key])
    }

    @Test fun homeCanShowGridAndBlogLinksAndExplainsAddingARobot() {
        Repo.setShowBlogs(true)
        show { MainScreen(rememberNavController()) }
        val heading=compose.onAllNodesWithText("Devices",substring=false).onFirst().fetchSemanticsNode().boundsInRoot
        compose.onNode(SemanticsMatcher("Device layout switch") { node ->
            node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                kotlin.math.abs(node.boundsInRoot.center.y-heading.center.y)<2 && node.boundsInRoot.left>heading.right
        }).performClick()
        compose.onAllNodesWithText("Waste:").assertCountEquals(Repo.state.value.robots.size)
        compose.onNodeWithText("Learn more").performScrollTo().performClick()
        assertEquals("https://www.whisker.com/blog",urls.last())
        compose.onNodeWithText("Add a Device").performScrollTo().performClick()
        compose.onNodeWithText("Flashing guide").performClick()
        assertTrue(urls.last().endsWith("Flashing%20Instructions.md"))
        compose.onNodeWithText("Flashing guide").assertDoesNotExist()
    }

    @Test @Config(sdk=[35],qualifiers="w411dp-h891dp")
    fun cloudLoginReportsRejectedPasswordAndAllowsSuccessfulRetry() {
        // Match InstantTaskExecutorRule for navigation lifecycle changes in this JVM test.
        usesInstantTasks=true
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        Repo.logout(); Repo.setMode(false); Repo.acceptIntro()
        val store=androidStorage(ApplicationProvider.getApplicationContext())
        val tokens=mutableMapOf<String,String>()
        val secrets=object : SecretStore {
            override fun get(key: String)=tokens[key]
            override fun set(key: String,value: String?) { if(value==null) tokens.remove(key) else tokens[key]=value }
            override fun clear()=tokens.clear()
        }
        var reject=true
        val token="header."+kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode("""{"mid":"test-owner","exp":4102444800}""".encodeToByteArray())+".signature"
        val http=io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            when {
                request.headers["X-Amz-Target"] != null && reject -> respond("""{"__type":"NotAuthorizedException"}""",io.ktor.http.HttpStatusCode.BadRequest)
                request.headers["X-Amz-Target"] != null -> respond("""{"AuthenticationResult":{"IdToken":"$token","RefreshToken":"test-refresh"}}""")
                request.method.value=="GET" -> respond("""{"user":{"firstName":"Test"}}""")
                else -> respond("""{"data":{}}""")
            }
        }).also { testHttpClients += it }
        Repo::class.java.getDeclaredField("api").apply { isAccessible=true }.set(Repo,Api(store.auth,secrets,http))
        show { Root() }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Login",substring=false).performClick()
        compose.onNodeWithText("Email",substring=false).performTextInput("test@example.invalid")
        compose.onNodeWithText("Password",substring=false).performTextInput("test-password")
        compose.onNodeWithText("Login",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Incorrect email or password").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(Repo.signedIn)
        reject=false
        compose.onNodeWithText("Login",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); Repo.signedIn && compose.onAllNodesWithText("Home",substring=false).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("test-owner",Repo.api.userId)
    }

    @Test @Config(sdk=[35],qualifiers="w411dp-h891dp")
    fun localLoginExplainsTokenSetupAndKeepsInvalidAddressFailuresVisible() {
        Repo.logout(); Repo.setMode(true); Repo.acceptIntro()
        show { Root() }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Connect Home Assistant").performClick()
        compose.onNodeWithText("Connect",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Firmware setup guide").performScrollTo().performClick()
        assertTrue(urls.last().contains("User%20Guide.md"))
        compose.onNodeWithText("Home Assistant address").performScrollTo().performTextReplacement("http://")
        compose.onNodeWithText("Long-lived access token").performScrollTo().performTextInput("test-only")
        compose.onNodeWithText("Connect",substring=false).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); compose.onAllNodesWithText("Connect",substring=false).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(Repo.signedIn)
        compose.onNodeWithText("Home Assistant address").assertExists()
    }
    @Test fun editingSleepAndWakeTimesEnablesTheDayAndPersistsBothValues() {
        val r=Repo.state.value.robots.first()
        show { RobotScreen(rememberNavController(),r.serial) }
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Sleep Mode",substring=false).performScrollTo().performClick()
        val day=r.raw.o("weekdaySleepModeEnabled")!!.o("Monday")!!
        val sleep=day.i("sleepTime")!!; val wake=day.i("wakeTime")!!
        compose.onAllNodesWithText("%02d:%02d".format(sleep/60,sleep%60)).onFirst().performClick()
        compose.onNodeWithText("Monday sleep time").assertExists()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("23")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("45")
        compose.onNodeWithText("OK").performClick()
        compose.onAllNodesWithText("%02d:%02d".format(wake/60,wake%60)).onFirst().performClick()
        compose.onNodeWithText("Monday wake time").assertExists()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("6")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("15")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Save",substring=false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Sleep schedule saved") }
        val saved=Repo.state.value.robots.first().raw.o("weekdaySleepModeEnabled")!!.o("Monday")!!
        assertEquals(1425,saved.i("sleepTime")); assertEquals(375,saved.i("wakeTime")); assertTrue(saved.b("isEnabled"))
    }

    @Test fun firmwareSwitchesSendBothDirectionsAndReportRejectedChanges() {
        val r=Repo.state.value.robots.first()
        val field=Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }
        val original=(field.get(Repo) as LocalHa).backend
        val calls=java.util.concurrent.ConcurrentLinkedQueue<String>()
        var fail=false
        field.set(Repo,LocalHa(object : HaBackend by original {
            override suspend fun post(path: String,body: String): String {
                calls+=path
                if(fail) throw ApiException("Switch unavailable")
                return original.post(path,body)
            }
        }))
        show { DeviceSettingsScreen(rememberNavController(),r.serial) }
        compose.onNodeWithText("ESPHome firmware").performScrollTo()
        val switches=compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        assertTrue(switches>0)
        repeat(2) { pass ->
            repeat(switches) { index -> compose.onAllNodes(isToggleable())[index].performScrollTo().performClick() }
            compose.waitUntil(5000) { compose.waitForIdle(); calls.size >= (pass+1)*switches }
            compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
        }
        assertTrue(calls.any { it.endsWith("turn_on") }); assertTrue(calls.any { it.endsWith("turn_off") })
        fail=true
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.contains("Failed: Switch unavailable") }
    }

}
