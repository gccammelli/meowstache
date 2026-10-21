package io.github.gccammelli.meowstache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RepositoryPersistenceTest {
    private class Settings : KeyValue {
        val data = mutableMapOf<String, Any>()
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

    @Test fun settingsBackupsAndAccountModesKeepStoredHistoryConsistent() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val prefs = Settings()
        val auth = Settings()
        val tokens = mutableMapOf<String, String>()
        val files = mutableMapOf<String, String>()
        try {
            Repo.init(PlatformStorage(prefs, auth, object : SecretStore {
                override fun get(key: String) = tokens[key]
                override fun set(key: String, value: String?) { if (value == null) tokens.remove(key) else tokens[key] = value }
                override fun clear() = tokens.clear()
            }, object : AppFiles {
                override fun read(name: String) = files[name]
                override fun write(name: String, text: String) { files[name] = text }
                override fun delete(name: String) { files.remove(name) }
            }))
            assertFalse(Repo.introAccepted); Repo.acceptIntro(); assertTrue(Repo.introAccepted)
            Repo.setShowBlogs(true); assertTrue(Repo.showBlogs.value)
            Repo.setShowUnavailable(false); assertFalse(Repo.showUnavailable.value)
            Repo.setInstantNotifications(true); assertTrue(Repo.instantNotifications)
            Repo.setUnits("lbs"); Repo.setDecimals(2)
            assertEquals("lbs", Repo.unitLabel()); assertEquals(10.0, Repo.weightNum(10.0))
            assertEquals("10.00 lbs", Repo.weight(10.0)); assertEquals("—", Repo.weight(null))
            Repo.setUnits("kg"); assertEquals(LBS_TO_KG * 10, Repo.weightNum(10.0))
            assertEquals("kg", Repo.unitLabel())
            Repo.setAlerts(AlertSettings(noVisitEnabled = true))
            assertTrue(json.decodeFromString<AlertSettings>(prefs.getString("alerts")!!).noVisitEnabled)
            assertTrue(Repo.firmwareCheckDue()); assertFalse(Repo.firmwareCheckDue())
            assertEquals(listOf("Unknown", "Good", "Fair", "Poor"), listOf(null, -60, -72, -90).map(Repo::signal))

            Repo.state.value = AppState(weights = mapOf("cat" to listOf(Reading(200000, 10.0))))
            Repo.updateLocal { LocalData(region = "United Kingdom", petNotes = mapOf("cat" to PetNotes(personality = listOf("Shy")))) }
            Repo.reassign(200000, "cat")
            assertEquals(200000, Repo.readingKey(Act(200010, "catWeight")))
            assertEquals(999999, Repo.readingKey(Act(999999, "catWeight")))
            assertTrue(Repo.shopUrl().endsWith("/uk/shop-all-products.html"))
            assertTrue(Repo.storeUrl("customer/account").endsWith("/uk/customer/account"))
            for ((region, path) in listOf("Canada (English)" to "ca/", "Canada (French)" to "ca/fr/", "Europe" to "eu/")) {
                Repo.updateLocal { it.copy(region = region) }
                assertTrue(Repo.storeUrl("returns").endsWith("/$path" + "returns.html"))
            }
            Repo.updateLocal { it.copy(region = "United States") }
            assertEquals("https://www.whisker.com/returns", Repo.storeUrl("returns"))

            val backup = Repo.exportBackup()
            assertEquals("meowstache", json.parseToJsonElement(backup).jsonObject.s("app"))
            val imported = """{"app":"meowstache","version":1,"weights":{"cat":[{"ts":200000,"lbs":99.0},{"ts":100000,"lbs":9.0}]},"acts":{"robot":[{"ts":100000,"value":"catWeight","actionValue":"9"}]},"local":{"region":"Europe","petNotes":{"cat":{"personality":["Bold"]}},"dismissedPatterns":["old"]},"futureField":true}"""
            assertEquals(1, Repo.importBackup(imported))
            assertEquals(listOf(Reading(100000, 9.0), Reading(200000, 10.0)), Repo.state.value.weights["cat"])
            assertEquals("United States", Repo.local.value.region)
            assertEquals(listOf("Shy"), Repo.local.value.petNotes["cat"]?.personality)
            assertTrue("old" in Repo.local.value.dismissedPatterns)
            assertEquals(1, Repo.importBackup(imported))
            assertEquals(1, Repo.state.value.acts["robot"]?.size)
            assertTrue(files.containsKey("history2.json"))
            val before = Repo.state.value
            assertFailsWith<IllegalArgumentException> { Repo.importBackup("""{"app":"other"}""") }
            assertFailsWith<IllegalArgumentException> { Repo.importBackup("""{"app":"meowstache","version":99}""") }
            assertEquals(before, Repo.state.value)

            Repo.addMessage(Message(1, "Title", "Body")); Repo.markAllRead()
            assertTrue(Repo.messages.value.all { it.read })
            Repo.markSent(listOf("a", "b")); Repo.markSent(listOf("a", "c"))
            assertEquals(setOf("a", "b", "c"), Repo.sentAlertKeys())
            Repo.clearMessages(); assertTrue(Repo.messages.value.isEmpty())
            Repo.setMode(true)
            assertTrue(Repo.localMode); assertTrue(Repo.modeChosen); assertFalse(Repo.signedIn)
            Repo.importBackup(imported)
            assertTrue(files.containsKey("history-local.json"))
            Repo.setMode(false)
            assertEquals(2, Repo.state.value.weights["cat"]?.size)
            Repo.logout()
            assertTrue(files.containsKey("history2.json"))
            assertTrue(Repo.clips.value.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
