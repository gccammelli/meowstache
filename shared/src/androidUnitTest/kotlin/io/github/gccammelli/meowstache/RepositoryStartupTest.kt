package io.github.gccammelli.meowstache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryStartupTest {
    private val apiField=Repo::class.java.getDeclaredField("api").apply { isAccessible=true }
    private fun stop() {
        val scope=Repo::class.java.getDeclaredField("scope").apply { isAccessible=true }.get(Repo) as CoroutineScope
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }
    private fun boot(values: Map<String,String>,tokens: MutableMap<String,String> = mutableMapOf(),cache: String?=null): PlatformStorage {
        stop(); apiField.set(Repo,null); Repo.state.value=AppState()
        val android=androidStorage(ApplicationProvider.getApplicationContext<Context>())
        android.settings.clear(); android.auth.clear()
        values.forEach { (key,value) -> android.settings.putString(key,value) }
        val storage=PlatformStorage(android.settings,android.auth,object : SecretStore {
            override fun get(key: String)=tokens[key]
            override fun set(key: String,value: String?) { if(value==null) tokens.remove(key) else tokens[key]=value }
            override fun clear()=tokens.clear()
        },object : AppFiles {
            override fun read(name: String)=cache
            override fun write(name: String,text: String)=Unit
            override fun delete(name: String)=Unit
        })
        Repo.init(storage)
        return storage
    }

    @Test fun savedPreferencesAndPhoneHistoryAreRestoredAtStartup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val message=Message(1,"Saved message","Saved body",true)
            val notes=LocalData(region="Europe",overrides=mapOf(123L to "cat"))
            boot(mapOf("units" to "lbs","alerts" to json.encodeToString(AlertSettings(noVisitEnabled=true)),
                "local" to json.encodeToString(notes),"messages" to json.encodeToString(listOf(message))),cache="""{"weights":{"cat":[{"ts":123,"lbs":9.0}]},"acts":{"robot":[{"ts":123,"value":"catWeight"}]}}""")
            assertEquals("lbs",Repo.units.value); assertTrue(Repo.alerts.value.noVisitEnabled)
            assertEquals(notes,Repo.local.value); assertEquals(listOf(message),Repo.messages.value)
            assertEquals(mapOf(123L to "cat"),Repo.state.value.overrides)
            assertEquals(listOf(Reading(123,9.0)),Repo.state.value.weights["cat"])
            assertEquals("catWeight",Repo.state.value.acts.getValue("robot").single().value)
            val first=Repo.api
            Repo.init(androidStorage(ApplicationProvider.getApplicationContext()))
            assertSame(first,Repo.api)
        } finally { stop(); Repo.logout(); apiField.set(Repo,null); Dispatchers.resetMain() }
    }

    @Test fun malformedSavedPreferencesFallBackWithoutPreventingStartup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for(broken in listOf("not-json","null","[]")) {
                boot(mapOf("alerts" to broken,"local" to broken,"messages" to "{invalid"),cache=broken)
                assertEquals("kg",Repo.units.value); assertEquals(AlertSettings(),Repo.alerts.value)
                assertEquals(LocalData(),Repo.local.value); assertTrue(Repo.messages.value.isEmpty())
                assertTrue(Repo.state.value.weights.isEmpty()); assertFalse(Repo.signedIn); assertFalse(Repo.canCheckAlerts)
            }
        } finally { stop(); Repo.logout(); apiField.set(Repo,null); Dispatchers.resetMain() }
    }

    @Test fun localSignInAndAlertAvailabilityRequireATokenAndExcludeTheDemo() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val tokens=mutableMapOf<String,String>()
        try {
            val storage=boot(mapOf("mode" to "local","haUrl" to "https://home.example"),tokens)
            assertFalse(Repo.signedIn); assertFalse(Repo.canCheckAlerts)
            assertEquals("https://home.example",Repo.email())
            tokens["haToken"]="saved-token"
            assertTrue(Repo.signedIn); assertTrue(Repo.canCheckAlerts)
            storage.settings.putBoolean("demo",true)
            assertTrue(Repo.signedIn); assertFalse(Repo.canCheckAlerts); assertEquals("Demo Home Assistant",Repo.email())
            storage.settings.putBoolean("demo",false); tokens.clear()
            assertFailsWith<AuthException> { Repo.refresh() }
            assertFalse(Repo.state.value.loading); assertEquals("Not connected",Repo.state.value.error)
            storage.settings.putString("haUrl",null)
            assertFailsWith<AuthException> { Repo.refresh() }
            assertEquals("",Repo.email())
        } finally { stop(); Repo.logout(); apiField.set(Repo,null); Dispatchers.resetMain() }
    }
    @Test fun aRecentVisitRetriesTheMediaFolderUntilItsDelayedRecordingArrives() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            boot(mapOf("mode" to "local","clipsFolder" to "visits"))
            val ts=nowMs()/1000*1000
            val name=utcStamp(ts).replace(' ','T').replace(':','-')+"Z.mp4"
            val clip=Clip(ts,"media/$name")
            var browseCalls=0
            val demo=FakeHomeAssistant()
            Repo::class.java.getDeclaredField("ha").apply { isAccessible=true }.set(Repo,LocalHa(object : HaBackend by demo {
                override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
                    if(commands.all { it.s("type")=="media_source/browse_media" }) {
                        browseCalls++
                        return commands.map { buildJsonObject { put("children",buildJsonArray {
                            if(browseCalls>=3) add(buildJsonObject { put("title",name); put("media_content_id",clip.id) })
                        }) } }
                    }
                    return demo.ws(commands)
                }
            }))
            Repo.state.value=AppState(acts=mapOf("robot" to listOf(Act(ts,"catWeight","10"),Act(ts-20*60_000,"catWeight","10"),Act(ts+60_000,"catWeight","10"))))
            runCurrent(); assertEquals(1,browseCalls); assertTrue(Repo.clips.value.isEmpty())
            Repo.state.value=Repo.state.value.copy(acts=mapOf("robot" to listOf(Act(ts+1,"catWeight","10"))))
            runCurrent(); assertEquals(2,browseCalls)
            advanceTimeBy(20_000); runCurrent()
            assertEquals(3,browseCalls); assertEquals(listOf(clip),Repo.clips.value)
            advanceTimeBy(40_000); runCurrent(); assertEquals(3,browseCalls)
        } finally { stop(); Repo.logout(); apiField.set(Repo,null); Dispatchers.resetMain() }
    }

    @Test fun defaultStoreRegionFollowsThePhonesCountryUntilTheOwnerChoosesOne() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val original=java.util.Locale.getDefault()
        try {
            boot(emptyMap())
            for((country,expected) in listOf("CA" to "Canada (English)","GB" to "United Kingdom","IT" to "Europe","AU" to "United States")) {
                java.util.Locale.setDefault(java.util.Locale("en",country))
                Repo.updateLocal { it.copy(region="") }; assertEquals(expected,Repo.region())
                Repo.updateLocal { it.copy(region="Canada (French)") }; assertEquals("Canada (French)",Repo.region())
            }
            Repo.onLogin(); assertFalse(Repo.api.isLoggedIn)
        } finally { java.util.Locale.setDefault(original); stop(); Repo.logout(); apiField.set(Repo,null); Dispatchers.resetMain() }
    }

}
