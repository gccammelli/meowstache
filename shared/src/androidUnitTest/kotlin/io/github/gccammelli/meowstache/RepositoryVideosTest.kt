package io.github.gccammelli.meowstache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RepositoryVideosTest {
    private val tokens=mutableMapOf<String,String>()
    private lateinit var storage: PlatformStorage
    private val clip=Clip(1_792_084_173_000L,"media-source://media_source/local/visits/2026-10-15T17-09-33Z.mp4")
    private fun media(command: JsonObject): JsonElement = when(command.s("type")) {
        "media_source/browse_media" -> buildJsonObject { put("children",buildJsonArray { add(buildJsonObject {
            put("title","2026-10-15T17-09-33Z.mp4"); put("media_content_id",clip.id)
        }) }) }
        "media_source/resolve_media" -> buildJsonObject { put("url","/signed/clip.mp4") }
        else -> error("Unexpected media command")
    }
    private fun init() {
        val android=androidStorage(ApplicationProvider.getApplicationContext<Context>())
        storage=PlatformStorage(android.settings,android.auth,object : SecretStore {
            override fun get(key: String)=tokens[key]
            override fun set(key: String,value: String?) { if(value==null) tokens.remove(key) else tokens[key]=value }
            override fun clear()=tokens.clear()
        },object : AppFiles {
            override fun read(name: String): String?=null
            override fun write(name: String,text: String)=Unit
            override fun delete(name: String)=Unit
        })
        Repo.init(storage)
        // The process singleton must use this test's stores even after another suite initialized it.
        for ((field,value) in listOf("prefs" to storage.settings,"secrets" to storage.secrets,"files" to storage.files,"api" to Api(storage.auth,storage.secrets))) {
            Repo::class.java.getDeclaredField(field).apply { isAccessible=true }.set(Repo,value)
        }
        Repo.logout(); Repo.setMode(false)
    }

    @Test fun cloudVideoSetupChecksBeforeSavingAndSignsClipsWithoutCloudLogin() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler)); init()
        try {
            HaSocket(sessions=2,commandsPerSession=1,result=::media).use { peer ->
                assertEquals(1,withContext(Dispatchers.IO) { Repo.setVideos(" /visits/ ",peer.url," test\n token ") })
                assertEquals("visits",Repo.clipsFolder); assertEquals(listOf(clip),Repo.clips.value)
                assertEquals("testtoken",tokens["camToken"])
                assertEquals(peer.url,storage.settings.getString("camUrl")); assertNull(Repo.clipsAwayUrl)
                assertEquals(peer.url+"/signed/clip.mp4",withContext(Dispatchers.IO) { Repo.clipUrl(clip) })
                assertFalse(Repo.signedIn)
                assertEquals(listOf("testtoken","testtoken"),peer.received.filter { it.s("type")=="auth" }.map { it.s("access_token") })
            }
            Repo.videosOff()
            assertNull(Repo.clipsFolder); assertNull(tokens["camToken"]); assertTrue(Repo.clips.value.isEmpty())
            assertEquals("Visit videos aren't connected",assertFailsWith<ApiException> { Repo.clipUrl(clip) }.message)
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun failedHomeAddressUsesAwayAddressAndRemembersItForSubsequentClips() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler)); init()
        try {
            HaSocket(sessions=2,commandsPerSession=1,result={ throw ApiException("Home address offline") }).use { home ->
                HaSocket(sessions=3,commandsPerSession=1,result=::media).use { away ->
                    assertEquals(1,withContext(Dispatchers.IO) { Repo.setVideos("visits",home.url,"token",away.url) })
                    assertEquals(away.url,Repo.clipsAwayUrl)
                    repeat(2) { assertEquals(away.url+"/signed/clip.mp4",withContext(Dispatchers.IO) { Repo.clipUrl(clip) }) }
                    assertEquals(2,home.received.count { it.s("type")=="auth" })
                    assertEquals(3,away.received.count { it.s("type")=="auth" })
                }
            }
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun invalidVideoConfigurationDoesNotReplaceTheVerifiedAddressOrToken() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler)); init()
        try {
            assertEquals("Enter the media folder's name",assertFailsWith<ApiException> { Repo.setVideos(" / ") }.message)
            HaSocket(commandsPerSession=1,result=::media).use { peer ->
                withContext(Dispatchers.IO) { Repo.setVideos("visits",peer.url,"working-token") }
            }
            val original=storage.settings.getString("camUrl")
            HaSocket(commandsPerSession=1,result={ throw ApiException("Permission denied") }).use { peer ->
                assertEquals("Permission denied",assertFailsWith<ApiException> {
                    withContext(Dispatchers.IO) { Repo.setVideos("other",peer.url,"wrong-token") }
                }.message)
            }
            assertEquals(original,storage.settings.getString("camUrl")); assertEquals("working-token",tokens["camToken"])
            assertEquals("visits",Repo.clipsFolder); assertEquals(listOf(clip),Repo.clips.value)
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun localVideosReuseHomeAssistantAndOfflineRefreshKeepsTheLastClipList() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler)); init()
        try {
            Repo.setMode(true)
            storage.settings.putString("haUrl","https://home.example")
            tokens["haToken"]="local-token"
            var mediaCalls=0; var offline=false
            val demo=FakeHomeAssistant()
            val backend=object : HaBackend by demo {
                override val base="https://home.example"
                override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
                    if(commands.all { it.s("type")?.startsWith("media_source/")==true }) {
                        mediaCalls++
                        if(offline) throw ApiException("Video storage offline")
                        return commands.map(::media)
                    }
                    return demo.ws(commands)
                }
            }
            Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }.set(Repo,LocalHa(backend))
            assertEquals(1,Repo.setVideos("visits"))
            assertTrue(Repo.signedIn); assertTrue(Repo.canCheckAlerts)
            assertEquals("https://home.example",Repo.email())
            assertNull(tokens["camToken"]); assertNull(storage.settings.getString("camUrl"))
            assertEquals("https://home.example/signed/clip.mp4",Repo.clipUrl(clip))
            val before=mediaCalls
            offline=true
            Repo.refreshAsync().join()
            assertTrue(mediaCalls>before); assertEquals(listOf(clip),Repo.clips.value)
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }

    @Test fun localConnectionNormalizesTheTokenAndKeepsVerifiedSettingsAfterARejectedChange() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler)); init()
        Repo.setMode(true)
        Repo.updateLocal { it.copy(overrides=mapOf(123L to "saved-pet")) }
        val registry: (JsonObject)->JsonElement = { command -> json.parseToJsonElement(when(command.s("type")) {
            "config/device_registry/list" -> """[{"id":"r","name_by_user":"Workshop","model":"Litter Robot 4"}]"""
            "config/entity_registry/list" -> """[{"entity_id":"sensor.status","device_id":"r","platform":"esphome","original_name":"Status"}]"""
            else -> error("Unexpected registry command")
        }) }
        try {
            HaSocket(sessions=3,result=registry,httpResponse={ path ->
                if(path=="/api/states") """[{"entity_id":"sensor.status","state":"Ready"}]""" else {
                    assertTrue(path.startsWith("/api/history/period/")); "[]"
                }
            }).use { peer ->
                assertEquals(1,withContext(Dispatchers.IO) { Repo.connectHa(" ${peer.url}/ "," pasted\n token ") })
                assertEquals(peer.url,storage.settings.getString("haUrl")); assertEquals("pastedtoken",tokens["haToken"])
                assertTrue(Repo.signedIn); assertTrue(Repo.canCheckAlerts)
                assertEquals(mapOf(123L to "saved-pet"),Repo.state.value.overrides)
                assertEquals("pastedtoken",peer.received.first().s("access_token"))
            }
            val verified=storage.settings.getString("haUrl")
            HaSocket(authenticate=false).use { peer ->
                assertFailsWith<AuthException> { withContext(Dispatchers.IO) { Repo.connectHa(peer.url,"rejected") } }
            }
            assertEquals(verified,storage.settings.getString("haUrl")); assertEquals("pastedtoken",tokens["haToken"])
        } finally { Repo.logout(); Dispatchers.resetMain() }
    }
}
