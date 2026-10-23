package io.github.gccammelli.meowstache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RepositoryNetworkTest {
    @Test fun refreshCommandsAndBackupsUseAuthenticatedApiWithoutLosingHistory() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val storage = androidStorage(ctx)
        Repo.init(storage)
        Repo.logout(); Repo.setMode(false)
        val demo = demoState()
        val rawRobot = JsonObject(demo.robots.first().raw + ("setupDateTime" to JsonPrimitive(utcStamp(nowMs()-86_400_000))))
        val pet = demo.pets.first()
        val timestamp = utcStamp(nowMs()-1000)
        val calls = mutableListOf<String>()
        val token = "header." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"mid":"owner","exp":4102444800}""".encodeToByteArray()) + ".sig"
        val secrets = object : SecretStore {
            val values = mutableMapOf("id" to token,"refresh" to "test-refresh")
            override fun get(key: String) = values[key]
            override fun set(key: String, value: String?) { if(value == null) values.remove(key) else values[key]=value }
            override fun clear() = values.clear()
        }
        var commandError = false
        HttpClient(MockEngine { request ->
            if (request.method.value == "GET") respond("""{"user":{"firstName":"Test","lastName":"Owner"}}""") else {
                val query = json.parseToJsonElement((request.body as TextContent).text).jsonObject.s("query").orEmpty()
                calls += query
                val data = when {
                    "getLitterRobot4ByUser" in query -> """{"getLitterRobot4ByUser":[$rawRobot]}"""
                    "getPetsByUser" in query -> """{"getPetsByUser":[${pet.raw}]}"""
                    "getWeightHistoryByPetId" in query -> """{"getWeightHistoryByPetId":[{"weight":10.0,"timestamp":"$timestamp"},{}]}"""
                    "getLitterRobot4Activity" in query -> """{"getLitterRobot4Activity":[{"timestamp":"$timestamp","value":"catWeight","actionValue":"10.0"},{}]}"""
                    "sendLitterRobot4Command" in query -> """{"sendLitterRobot4Command":"${if(commandError) "Error: refused" else "ok"}"}"""
                    "toggleHopper" in query -> """{"toggleHopper":{"success":true}}"""
                    "litterRobot4TriggerFirmwareUpdate" in query -> """{"litterRobot4TriggerFirmwareUpdate":{"isUpdateTriggered":true}}"""
                    "litterRobot4CompareFirmwareVersion" in query -> """{"litterRobot4CompareFirmwareVersion":{"isEspFirmwareUpdateNeeded":true,"latestFirmware":{"espFirmwareVersion":"2.0","picFirmwareVersion":"3.0","laserBoardFirmwareVersion":"4.0"}}}"""
                    else -> "{}"
                }
                respond("""{"data":$data}""")
            }
        }).use { http ->
            Repo::class.java.getDeclaredField("api").apply { isAccessible = true }.set(Repo, Api(storage.auth, secrets, http))
            try {
                val prefs=Repo::class.java.getDeclaredField("prefs").apply { isAccessible=true }.get(Repo) as KeyValue
                prefs.putString("owner","different-previous-owner")
                Repo.addMessage(Message(nowMs(),"Previous owner","Private history"))
                Repo.updateLocal { it.copy(overrides=mapOf(1L to "old-pet")) }
                Repo.onLogin()
                assertTrue(Repo.messages.value.isEmpty())
                assertTrue(Repo.local.value.overrides.isEmpty())
                assertTrue(Repo.canCheckAlerts)
                Repo.refresh()
                storage.auth.putString("email","test@example.test")
                assertEquals("test@example.test",Repo.email())
                val requestCount=calls.size
                Repo.refreshIfStale()
                assertEquals(requestCount,calls.size)
                val st = Repo.state.value
                assertEquals("Test", st.firstName); assertEquals(1, st.robots.size)
                assertTrue(st.weights.getValue(pet.id).any { it.lbs == 10.0 })
                assertTrue(st.acts.values.flatten().any { it.value == "catWeight" })
                assertFalse(st.loading); assertFalse(st.historyLoading)
                val robot = st.robots.first()
                assertTrue(Repo.command(robot, "startCycle").isSuccess)
                commandError = true; assertTrue(Repo.command(robot, "startCycle").isFailure)
                assertTrue(Repo.rename(robot, "New nickname").isSuccess)
                assertTrue(Repo.toggleHopper(robot, true).isSuccess)
                assertEquals(true, Repo.triggerFirmwareUpdate(robot).getOrThrow())
                assertTrue(Repo.latestFirmware(robot)!!.contains("Update available"))
                val backup = Repo.exportBackup()
                Repo.state.value = st.copy(weights = emptyMap(), acts = emptyMap())
                assertTrue(Repo.importBackup(backup) > 0)
                assertNotNull(storage.files.read("history2.json"))
                assertTrue(calls.any { "getLitterRobot4Activity" in it })
                Repo.logout()
                assertFalse(Repo.signedIn)
                assertFailsWith<AuthException> { Repo.refresh() }
            } finally { Repo.logout(); Dispatchers.resetMain() }
        }
    }
}
