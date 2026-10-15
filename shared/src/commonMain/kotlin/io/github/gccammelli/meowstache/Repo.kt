package io.github.gccammelli.meowstache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.IO
import kotlinx.datetime.periodUntil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.time.Instant
import io.github.gccammelli.meowstache.ZoneOffset
import io.github.gccammelli.meowstache.DateTimeFormatter

data class AppState(
    val firstName: String = "",
    val lastName: String = "",
    val robots: List<Robot> = emptyList(),
    val pets: List<Pet> = emptyList(),
    val weights: Map<String, List<Reading>> = emptyMap(),
    val acts: Map<String, List<Act>> = emptyMap(),
    val loading: Boolean = false,
    val historyLoading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
    val overrides: Map<Long, String> = emptyMap(),
) {
    /** Full per-pet history: API readings plus weighings recovered from the robot activity log, with the user's reassignments. */
    val history: Map<String, List<Reading>> by lazy { applyWeightOverrides(attributeWeights(weights, acts.values.flatten()), overrides) }

    /** Estimated minutes per visit, keyed by reading timestamp. */
    fun durations(petId: String): Map<Long, Double> = durationCache.getOrPut(petId) {
        // Each visit is timed on the robot that weighed it (a cat can use several robots).
        val first = robots.firstOrNull() ?: return@getOrPut emptyMap()
        val weighedOn = acts.flatMap { (serial, a) -> a.filter { it.value == "catWeight" }.map { it.ts to serial } }.toMap()
        history[petId].orEmpty().groupBy { weighedOn[it.ts] ?: first.serial }.flatMap { (serial, visits) ->
            val robot = robots.firstOrNull { it.serial == serial } ?: first
            visitDurations(visits, acts[serial].orEmpty(), robot.waitMinutes).entries
        }.associate { it.key to it.value }
    }
    private val durationCache = HashMap<String, Map<Long, Double>>()
}

@Serializable
private data class HistoryCache(
    val weights: Map<String, List<Reading>> = emptyMap(),
    val acts: Map<String, List<Act>> = emptyMap(),
)

@Serializable
data class Litter(val brand: String = "", val formulation: String = "")

@Serializable
data class PetNotes(val adoptionDetails: String = "", val feedingTime: String = "", val personality: List<String> = emptyList())

/** Settings the official service keeps on its servers that the public API can't write; kept on this phone instead. */
@Serializable
data class LocalData(
    val region: String = "",
    val litter: Map<String, Litter> = emptyMap(),
    val scoopsBase: Map<String, Int> = emptyMap(), // serial → scoops count at the last local reset
    val filterAt: Map<String, Long> = emptyMap(), // serial → when the filter was last replaced
    val petNotes: Map<String, PetNotes> = emptyMap(),
    val overrides: Map<Long, String> = emptyMap(), // weight reading time → pet id ("" = unassigned)
)

/**
 * The "Back up history" file: everything that can't be fetched again once the official servers drop it.
 * Adding fields is safe both ways (unknown keys are ignored, missing ones default). Bump [BACKUP_VERSION] only for a
 * change older apps would misread; they then refuse the file instead of restoring it wrongly.
 */
@Serializable
private data class Backup(
    val app: String? = null, // "meowstache" in every file we write
    val version: Int = BACKUP_VERSION,
    val owner: String? = null,
    val weights: Map<String, List<Reading>> = emptyMap(),
    val acts: Map<String, List<Act>> = emptyMap(),
    val local: LocalData = LocalData(),
)

@Serializable
data class Message(val ts: Long, val title: String, val body: String, val read: Boolean = false)

private const val BACKUP_VERSION = 1

private val backupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

object Repo {
    lateinit var api: Api; private set
    private lateinit var prefs: KeyValue
    private lateinit var secrets: SecretStore
    private lateinit var files: AppFiles
    val state = MutableStateFlow(AppState())
    val units = MutableStateFlow("kg")
    /** Decimal places for weights: 1 like the official app, or 2 for extra precision. */
    val decimals = MutableStateFlow(1)
    val showBlogs = MutableStateFlow(false)
    val alerts = MutableStateFlow(AlertSettings())
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val local = MutableStateFlow(LocalData())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val refreshLock = Mutex()

    /** Cloud and local mode keep separate histories, so switching never mixes or wipes either. */
    private val CACHE get() = if (localMode) "history-local.json" else "history2.json"

    fun init(p: PlatformStorage) {
        if (::api.isInitialized) return
        api = Api(p.auth, p.secrets)
        prefs = p.settings
        secrets = p.secrets
        files = p.files
        units.value = (prefs.getString("units") ?: "kg")
        decimals.value = prefs.getInt("decimals", 1)
        showBlogs.value = prefs.getBoolean("showBlogs", false)
        alerts.value = prefs.getString("alerts")?.let { runCatching { json.decodeFromString<AlertSettings>(it) }.getOrNull() } ?: AlertSettings()
        local.value = prefs.getString("local")?.let { runCatching { json.decodeFromString<LocalData>(it) }.getOrNull() } ?: LocalData()
        state.update { it.copy(overrides = local.value.overrides) }
        messages.value = prefs.getString("messages")?.let { runCatching { json.decodeFromString<List<Message>>(it) }.getOrNull() }.orEmpty()
        loadCache()
        if (demo) startDemo()
    }

    /** "Try the demo": made-up robot and cats, no account, nothing sent anywhere (see Demo.kt). */
    val demo get() = prefs.getBoolean("demo", false)
    val signedIn get() = if (localMode) demo || secrets.get("haToken") != null else api.isLoggedIn || demo
    /** Background alert checks run for a real account: Whisker in cloud mode, Home Assistant in local mode; never the demo. */
    val canCheckAlerts get() = !demo && (if (localMode) secrets.get("haToken") != null else api.isLoggedIn)
    val introAccepted get() = prefs.getBoolean("intro", false)
    fun acceptIntro() = prefs.putBoolean("intro", true)
    fun email(): String = when {
        localMode -> if (demo) "Demo Home Assistant" else prefs.getString("haUrl").orEmpty()
        demo -> DEMO_EMAIL
        else -> api.email
    }
    fun startDemo() {
        prefs.putBoolean("demo", true)
        if (localMode) { state.value = AppState(overrides = local().overrides); ha = null; refreshAsync() } else state.value = demoState()
    }
    private fun local() = this.local.value

    /**
     * Local mode: robots on the community ESPHome firmware, read through the user's Home Assistant
     * instead of the manufacturer's cloud (HomeAssistant.kt). Chosen on the first screen; cloud is the default.
     */
    val localMode get() = prefs.getString("mode") == "local"
    val modeChosen get() = prefs.getString("mode") != null
    fun setMode(local: Boolean) {
        prefs.putString("mode", if (local) "local" else "cloud")
        ha = null
        state.value = AppState(overrides = local().overrides)
        loadCache()
    }
    // ---------- visit videos (GitHub #3) ----------

    val clips = MutableStateFlow<List<Clip>>(emptyList())
    /** The Home Assistant media folder holding the clips; null = visit videos off. */
    val clipsFolder get() = prefs.getString("clipsFolder")
    /** Local mode reuses its Home Assistant; Whisker mode has its own connection, for videos only. */
    private fun clipsHa(): HaBackend? = when {
        demo -> null
        localMode -> runCatching { ha().backend }.getOrNull()
        else -> prefs.getString("camUrl")?.let { u -> secrets.get("camToken")?.let { HaHttp(u, it) } }
    }

    private suspend fun loadClips() {
        val b = clipsHa()
        val folder = clipsFolder
        if (b == null || folder == null) { clips.value = emptyList(); return }
        runCatching { b.clips(folder) }.onSuccess { clips.value = it } // offline or away from home: keep the last list
    }

    /** Checks the folder (and, in Whisker mode, the address and token), then keeps them. Returns the number of clips found. */
    suspend fun setVideos(folder: String, url: String? = null, rawToken: String? = null): Int {
        val f = folder.trim().trim('/').ifEmpty { throw ApiException("Enter the media folder's name") }
        val b = if (localMode) ha().backend else HaHttp(url.orEmpty(), rawToken.orEmpty().filterNot { it.isWhitespace() })
        val found = b.clips(f)
        if (!localMode) { prefs.putString("camUrl", b.base); secrets.set("camToken", rawToken!!.filterNot { it.isWhitespace() }) }
        prefs.putString("clipsFolder", f)
        clips.value = found
        return found.size
    }

    fun videosOff() {
        listOf("clipsFolder", "camUrl").forEach { prefs.putString(it, null) }
        secrets.set("camToken", null)
        clips.value = emptyList()
    }

    suspend fun clipUrl(c: Clip): String = (clipsHa() ?: throw ApiException("Visit videos aren't connected")).clipUrl(c)

    private var ha: LocalHa? = null
    private fun ha(): LocalHa = ha ?: LocalHa(
        if (demo) FakeHomeAssistant()
        else HaHttp(prefs.getString("haUrl") ?: throw AuthException("Not connected"), secrets.get("haToken") ?: throw AuthException("Not connected"))
    ).also { ha = it }

    /** Checks the address and token by loading the robots, then keeps them (token in the secret store). */
    suspend fun connectHa(url: String, rawToken: String): Int {
        val token = rawToken.filterNot { it.isWhitespace() } // pasted tokens often carry line breaks or spaces
        val client = HaHttp(url, token)
        val found = LocalHa(client).load(nowMs()).robots.size
        prefs.putBoolean("demo", false)
        prefs.putString("haUrl", client.base)
        secrets.set("haToken", token)
        ha = null
        state.value = AppState(overrides = local().overrides)
        loadCache()
        return found
    }
    private fun demoOnly(): Nothing = throw ApiException("Demo mode: there's no real robot to control")

    private fun loadCache() = runCatching { json.decodeFromString<HistoryCache>(files.read(CACHE)!!) }.getOrNull()?.let { c ->
        state.update { it.copy(weights = c.weights, acts = c.acts) }
    }

    /** Keeps the history across log out / log in, but never mixes two accounts' data. */
    fun onLogin() {
        prefs.putBoolean("demo", false)
        val uid = api.userId ?: return
        val owner = prefs.getString("owner")
        if (owner != null && owner != uid) {
            files.delete(CACHE)
            listOf("sent", "messages", "local").forEach { prefs.putString(it, null) }
            messages.value = emptyList(); local.value = LocalData()
        }
        prefs.putString("owner", uid)
        state.value = AppState(overrides = local.value.overrides)
        loadCache()
    }

    fun exportBackup(): String = state.value.let { st ->
        backupJson.encodeToString(Backup(app = "meowstache", owner = api.userId, weights = st.weights, acts = st.acts, local = local.value))
    }

    /** Merges a backup file into the current history; returns the number of weighings now on record. */
    suspend fun importBackup(text: String): Int = refreshLock.withLock {
        val b = backupJson.decodeFromString<Backup>(text)
        require(b.app == "meowstache") { "Not a Meowstache backup" }
        require(b.version <= BACKUP_VERSION) { "This backup was made by a newer Meowstache: update the app to restore it" }
        require(b.owner == null || api.userId == null || b.owner == api.userId) { "This backup belongs to a different account" }
        state.update { st ->
            st.copy(
                weights = (st.weights.keys + b.weights.keys).associateWith { merge(st.weights[it], b.weights[it].orEmpty()) },
                acts = (st.acts.keys + b.acts.keys).associateWith { (st.acts[it].orEmpty() + b.acts[it].orEmpty()).distinct().sortedByDescending { a -> a.ts } },
            )
        }
        val l = b.local
        updateLocal { c -> // what's already on this phone wins
            c.copy(region = c.region.ifBlank { l.region }, litter = l.litter + c.litter, scoopsBase = l.scoopsBase + c.scoopsBase,
                filterAt = l.filterAt + c.filterAt, petNotes = l.petNotes + c.petNotes, overrides = l.overrides + c.overrides)
        }
        saveCache()
        state.value.acts.values.sumOf { a -> a.count { it.value == "catWeight" } }
    }

    private suspend fun saveCache() = withContext(Dispatchers.IO) {
        val CACHE = CACHE
        if (demo) return@withContext // never mix demo data into the real history
        val st = state.value
        files.write(CACHE, json.encodeToString(HistoryCache(st.weights, st.acts)))
    }

    // ---------- settings ----------

    fun setUnits(u: String) { units.value = u; prefs.putString("units", u) }
    fun setShowBlogs(v: Boolean) { showBlogs.value = v; prefs.putBoolean("showBlogs", v) }
    fun setDecimals(d: Int) { decimals.value = d; prefs.putInt("decimals", d) }
    fun setAlerts(a: AlertSettings) { alerts.value = a; prefs.putString("alerts", json.encodeToString(a)) }
    fun updateLocal(f: (LocalData) -> LocalData) {
        local.value = f(local.value)
        prefs.putString("local", json.encodeToString(local.value))
        state.update { it.copy(overrides = local.value.overrides) }
    }
    fun reassign(ts: Long, petId: String) = updateLocal { it.copy(overrides = it.overrides + (ts to petId)) }
    /** The key a weight override uses for an activity "catWeight" row: the matching reading's time, or an existing override's. */
    fun readingKey(a: Act): Long =
        nearest(local.value.overrides.keys, a.ts) { it } ?: nearest(state.value.history.values.flatten(), a.ts) { it.ts }?.ts ?: a.ts

    /**
     * The item closest in time to [ts], within 2 minutes (API weighings and activity rows differ by seconds).
     * Closest, not first: two cats weighed back to back can be under 2 minutes apart.
     */
    private fun <T> nearest(items: Iterable<T>, ts: Long, time: (T) -> Long): T? =
        items.filter { kotlin.math.abs(time(it) - ts) < 120_000 }.minByOrNull { kotlin.math.abs(time(it) - ts) }
    fun scoopsSaved(r: Robot) = (r.scoopsSaved - (local.value.scoopsBase[r.serial] ?: 0)).coerceAtLeast(0)

    /**
     * The official app's region picker; here it only picks which regional store the shop and account links open.
     * US unless the user picked a region, or the phone's country clearly is one of them.
     */
    val regions = listOf("United States", "Canada (English)", "Canada (French)", "United Kingdom", "Europe")
    private val euCountries = setOf("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE")
    fun region(): String = local.value.region.ifBlank {
        val c = deviceCountry()
        when { c == "CA" -> regions[1]; c == "GB" -> regions[3]; c in euCountries -> regions[4]; else -> regions[0] }
    }

    /**
     * A page of the regional store. All regions run the same store software with the same account paths
     * (checked: logged-out requests redirect to login, unknown paths 404); non-US stores add ".html" to
     * content pages. Note whisker.co.uk is a parked domain, not the UK store.
     */
    fun storeUrl(page: String): String {
        val base = when (region()) {
            "United Kingdom" -> "https://www.litter-robot.com/uk/"
            "Canada (English)" -> "https://www.litter-robot.com/ca/"
            "Canada (French)" -> "https://www.litter-robot.com/ca/fr/"
            "Europe" -> "https://www.litter-robot.com/eu/"
            else -> return "https://www.whisker.com/$page"
        }
        return base + if (page in setOf("shop-all-products", "returns")) "$page.html" else page
    }
    fun shopUrl() = storeUrl("shop-all-products")

    fun sentAlertKeys(): Set<String> = prefs.getStringSet("sent")
    fun markSent(keys: Collection<String>) = prefs.putStringSet("sent", sentAlertKeys() + keys)

    /** The alerts not posted before: marks them sent and logs them to the Message Center. The platform then posts them. */
    fun takeFresh(alerts: List<PetAlert>): List<PetAlert> {
        val sent = sentAlertKeys()
        val fresh = alerts.filter { it.key !in sent }
        if (fresh.isEmpty()) return fresh
        markSent(fresh.map { it.key })
        fresh.forEach { addMessage(Message(nowMs(), it.title, it.body)) }
        return fresh
    }

    fun addMessage(m: Message) {
        messages.value = (listOf(m) + messages.value).take(200)
        prefs.putString("messages", json.encodeToString(messages.value))
    }
    fun markAllRead() { messages.value = messages.value.map { it.copy(read = true) }; saveMessages() }
    fun clearMessages() { messages.value = emptyList(); saveMessages() }
    private fun saveMessages() = prefs.putString("messages", json.encodeToString(messages.value))

    /** Signs out but keeps the history on the phone; [onLogin] drops it only if a different account signs in. */
    fun logout() {
        prefs.putBoolean("demo", false)
        prefs.putString("haUrl", null)
        ha = null
        videosOff()
        api.logout()
        state.value = AppState(overrides = local.value.overrides)
    }

    // ---------- formatting ----------

    fun weight(lbs: Double?, decimals: Int = this.decimals.value): String {
        if (lbs == null) return "—"
        return if (units.value == "kg") "%.${decimals}f kg".format(lbs * LBS_TO_KG) else "%.${decimals}f lbs".format(lbs)
    }
    fun num(v: Double) = "%.${decimals.value}f".format(v)
    fun weightNum(lbs: Double) = if (units.value == "kg") lbs * LBS_TO_KG else lbs
    fun unitLabel() = if (units.value == "kg") "kg" else "lbs"

    // ---------- loading ----------

    fun refreshAsync() = scope.launch { refresh(); loadClips() }
    /** For work started from a dialog that closes right away (a dialog's own scope would cancel it). */
    fun launch(block: suspend () -> Unit) = scope.launch { block() }

    /** Instant notifications (GitHub #4, Android): a live connection to the robots instead of the 30-minute check. */
    val instantNotifications get() = prefs.getBoolean("instant", false)
    fun setInstantNotifications(on: Boolean) = prefs.putBoolean("instant", on)

    /** For app foregrounds: skip when the last refresh is under a minute old, so app switching doesn't hammer Whisker. */
    fun refreshIfStale() { if (nowMs() - state.value.updatedAt > 60_000L) refreshAsync() }

    /** True at most every 6 hours: firmware ships rarely, so background checks needn't ask every 30 minutes. */
    fun firmwareCheckDue(): Boolean {
        val last = prefs.getString("firmwareCheckedAt")?.toLongOrNull() ?: 0L
        if (nowMs() - last < 6 * 3_600_000L) return false
        prefs.putString("firmwareCheckedAt", nowMs().toString())
        return true
    }

    suspend fun refresh(full: Boolean = true) = refreshLock.withLock {
        if (demo && !localMode) return@withLock
        state.update { it.copy(loading = true, error = null) }
        try {
            if (localMode) return@withLock refreshLocal()
            val uid = api.userId ?: throw AuthException("Not logged in")
            if (prefs.getString("owner") == null) prefs.putString("owner", uid)
            coroutineScope {
                val user = async { runCatching { api.user() }.getOrNull() }
                val robots = async {
                    api.gql(LR4_ENDPOINT, "query GetLR4(\$userId: String!) { getLitterRobot4ByUser(userId: \$userId) $LR4_MODEL }",
                        mapOf("userId" to uid)).a("getLitterRobot4ByUser").map(::Robot).filter { it.serial.isNotEmpty() }
                }
                val pets = async {
                    api.gql(PET_ENDPOINT, "query GetPetsByUser(\$userId: String!) { getPetsByUser(userId: \$userId) $PET_MODEL }",
                        mapOf("userId" to uid)).a("getPetsByUser").map(::Pet)
                }
                val u = user.await()
                val r = robots.await(); val p = pets.await()
                state.update { s ->
                    s.copy(
                        firstName = u?.s("firstName") ?: s.firstName, lastName = u?.s("lastName") ?: s.lastName,
                        robots = r, pets = p,
                        weights = p.associate { pet -> pet.id to merge(s.weights[pet.id], pet.embeddedWeights) },
                        loading = false, updatedAt = nowMs(),
                    )
                }
            }
            if (full) loadHistory()
        } catch (e: Exception) {
            state.update { it.copy(loading = false, historyLoading = false, error = e.message ?: e.toString()) }
            if (e is AuthException && !api.isLoggedIn) throw e
        }
    }

    /** Home Assistant keeps ~10 days of history by default, so fetch from a day before the newest cached row (or a year back). */
    private suspend fun refreshLocal() {
        val have = state.value.acts.values.flatten().maxOfOrNull { it.ts }
        val snap = withContext(Dispatchers.Default) { ha().load(have?.minus(86_400_000L) ?: (nowMs() - 365L * 86_400_000L)) } // parsing a year of history is heavy
        state.update { s ->
            s.copy(
                firstName = "", robots = snap.robots, pets = snap.pets,
                weights = (s.weights.keys + snap.weights.keys).associateWith { merge(s.weights[it], snap.weights[it].orEmpty()) },
                acts = (s.acts.keys + snap.acts.keys).associateWith { (s.acts[it].orEmpty() + snap.acts[it].orEmpty()).distinct().sortedByDescending { a -> a.ts } },
                loading = false, historyLoading = false, updatedAt = nowMs(),
            )
        }
        saveCache()
    }

    /** Pulls the whole weight and activity history and merges it into the on-device cache. */
    private suspend fun loadHistory() {
        state.update { it.copy(historyLoading = true) }
        val s = state.value
        coroutineScope {
            val w = s.pets.map { pet ->
                async {
                    pet.id to runCatching {
                        api.gql(PET_ENDPOINT,
                            "query GetWeightHistoryByPetId(\$petId: String!, \$limit: Int) { getWeightHistoryByPetId(petId: \$petId, limit: \$limit) { weight timestamp } }",
                            mapOf("petId" to pet.id, "limit" to 100_000)
                        ).a("getWeightHistoryByPetId").mapNotNull { r ->
                            Reading(r.s("timestamp")?.let(::parseTs) ?: return@mapNotNull null, r.d("weight") ?: return@mapNotNull null)
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
            state.update { st -> st.copy(weights = st.weights + w.associate { (id, l) -> id to merge(st.weights[id], l) }) }
            for (robot in s.robots) {
                val have = state.value.acts[robot.serial].orEmpty()
                val acts = runCatching { fetchActivity(robot, have.maxOfOrNull { it.ts }) }.getOrDefault(emptyList())
                state.update { st -> st.copy(acts = st.acts + (robot.serial to (have + acts).distinct().sortedByDescending { it.ts })) }
            }
        }
        state.update { it.copy(historyLoading = false) }
        saveCache()
    }

    /**
     * Without a start time the activity API returns only ~6 days, so walk forward in 30-day windows
     * from setup (first run) or from the newest cached row (later runs).
     */
    private suspend fun fetchActivity(robot: Robot, newest: Long?): List<Act> {
        val now = nowMs()
        var from = newest?.minus(86_400_000L) ?: robot.setup ?: (now - 3L * 365 * 86_400_000L)
        val out = mutableListOf<Act>()
        while (from < now) {
            val to = minOf(from + 30L * 86_400_000L, now + 3_600_000L)
            out += api.gql(LR4_ENDPOINT, """
                query GetLR4Activity(${'$'}serial: String!, ${'$'}startTimestamp: String, ${'$'}endTimestamp: String, ${'$'}limit: Int, ${'$'}consumer: String) {
                    getLitterRobot4Activity(serial: ${'$'}serial, startTimestamp: ${'$'}startTimestamp, endTimestamp: ${'$'}endTimestamp, limit: ${'$'}limit, consumer: ${'$'}consumer) {
                        timestamp value actionValue valueString stateString
                    }
                }""",
                mapOf("serial" to robot.serial, "startTimestamp" to utcStamp(from),
                    "endTimestamp" to utcStamp(to), "limit" to 20_000, "consumer" to "app")
            ).a("getLitterRobot4Activity").mapNotNull { a ->
                Act(a.s("timestamp")?.let(::parseTs) ?: return@mapNotNull null, a.s("value") ?: return@mapNotNull null, a.s("actionValue"), a.s("valueString"), a.s("stateString"))
            }
            from = to
        }
        return out
    }

    private fun merge(a: List<Reading>?, b: List<Reading>) = (a.orEmpty() + b).distinctBy { it.ts }.sortedBy { it.ts }

    // ---------- commands ----------

    suspend fun command(robot: Robot, command: String, value: String? = null): Result<Unit> = runCatching {
        if (localMode) {
            ha().command(robot, command, value)
            kotlinx.coroutines.delay(1000)
            return@runCatching refresh(full = false)
        }
        if (demo) demoOnly()
        val data = api.gql(LR4_ENDPOINT, """
            mutation sendCommand(${'$'}serial: String!, ${'$'}command: String!, ${'$'}value: String, ${'$'}commandSource: String) {
                sendLitterRobot4Command(input: { serial: ${'$'}serial, command: ${'$'}command, value: ${'$'}value, commandSource: ${'$'}commandSource })
            }""", mapOf("serial" to robot.serial, "command" to command, "value" to value, "commandSource" to "app"))
        val res = data.s("sendLitterRobot4Command").orEmpty()
        if ("Error" in res) throw ApiException(res)
        kotlinx.coroutines.delay(1500)
        refresh(full = false)
    }

    suspend fun rename(robot: Robot, name: String): Result<Unit> = runCatching {
        if (localMode) return local { ha().renameRobot(robot, name) }
        if (demo) demoOnly()
        api.gql(LR4_ENDPOINT, "mutation rename(\$serial: String!, \$name: String) { updateLitterRobot4(input: { serial: \$serial, name: \$name }) { name } }",
            mapOf("serial" to robot.serial, "name" to name))
        refresh(full = false)
    }

    /** pylitterbot: isRemoved = true disables the hopper; the API takes ~5 s to reflect it. */
    suspend fun toggleHopper(robot: Robot, removed: Boolean): Result<Unit> = runCatching {
        if (localMode) return command(robot, if (removed) "hopperOff" else "hopperOn")
        if (demo) demoOnly()
        val r = api.gql(LR4_ENDPOINT, "mutation ToggleHopper(\$serial: String!, \$isRemoved: Boolean!) { toggleHopper(serial: \$serial, isRemoved: \$isRemoved) { success } }",
            mapOf("serial" to robot.serial, "isRemoved" to removed))
        if (r.o("toggleHopper")?.b("success") != true) throw ApiException("Hopper not changed")
        kotlinx.coroutines.delay(5000)
        refresh(full = false)
    }

    suspend fun triggerFirmwareUpdate(robot: Robot): Result<Boolean> = runCatching {
        if (localMode) throw ApiException("Update the ESPHome firmware from Home Assistant or the ESPHome dashboard")
        if (demo) demoOnly()
        api.gql(LR4_ENDPOINT, "mutation updateFirmware(\$serial: String!) { litterRobot4TriggerFirmwareUpdate(input: {serial: \$serial}) { isUpdateTriggered } }",
            mapOf("serial" to robot.serial)).o("litterRobot4TriggerFirmwareUpdate")?.b("isUpdateTriggered") == true
    }

    suspend fun latestFirmware(robot: Robot): String? = runCatching {
        if (localMode) return@runCatching "ESPHome firmware ${robot.raw.s("haFirmware") ?: ""}\nUpdates via Home Assistant / ESPHome"
        if (demo) return@runCatching "ESP 1.1.84 / PIC 10500.3072.2.93 / TOF 5.0.2.1\nUp to date"
        val f = api.gql(LR4_ENDPOINT, """query getFirmwareDetails(${'$'}serial: String!) { litterRobot4CompareFirmwareVersion(serial: ${'$'}serial) {
            isEspFirmwareUpdateNeeded isPicFirmwareUpdateNeeded isLaserboardFirmwareUpdateNeeded
            latestFirmware { espFirmwareVersion picFirmwareVersion laserBoardFirmwareVersion } } }""",
            mapOf("serial" to robot.serial)).o("litterRobot4CompareFirmwareVersion")
        val l = f?.o("latestFirmware")
        val needed = listOf("isEspFirmwareUpdateNeeded", "isPicFirmwareUpdateNeeded", "isLaserboardFirmwareUpdateNeeded").any { f?.b(it) == true }
        "ESP ${l?.s("espFirmwareVersion")} / PIC ${l?.s("picFirmwareVersion")} / TOF ${l?.s("laserBoardFirmwareVersion")}" +
            if (needed) "\nUpdate available" else "\nUp to date"
    }.getOrNull()

    // ---------- local mode only: what the ESPHome firmware adds over the cloud ----------

    /** Runs a Home Assistant change, then reloads what it touched. */
    private suspend fun local(block: suspend () -> Unit): Result<Unit> = runCatching {
        if (!localMode) throw ApiException("Only with the ESPHome firmware (local mode)")
        block()
        kotlinx.coroutines.delay(1000)
        refresh(full = false)
    }

    /** A switch or button of the firmware, e.g. ("switch.auto_cycle", "turn_on"). */
    suspend fun haCall(robot: Robot, role: String, service: String) = local { ha().call(robot, role, service) }
    suspend fun haDiagnostics(appVersion: String): String = ha().diagnostics(appVersion)
    suspend fun setSleep(robot: Robot, day: String, enabled: Boolean, sleepMin: Int, wakeMin: Int) = local { ha().setSleep(robot, day, enabled, sleepMin, wakeMin) }
    suspend fun setTrackerWeight(pet: Pet, lbs: Double) = local { ha().setTrackerWeight(pet, lbs) }
    suspend fun resetTracker(pet: Pet) = local { ha().resetTracker(pet) }

    /** Renames the cat in Home Assistant; its history and notes here follow it to the new name. */
    suspend fun renamePet(pet: Pet, name: String) = local {
        ha().renamePet(pet, name)
        val old = pet.id; val new = haPetId(name)
        if (old != new) {
            state.update { st -> st.copy(weights = st.weights - old + (new to merge(st.weights[new], st.weights[old].orEmpty()))) }
            updateLocal { l -> l.copy(petNotes = l.petNotes[old]?.let { l.petNotes - old + (new to it) } ?: l.petNotes,
                overrides = l.overrides.mapValues { (_, p) -> if (p == old) new else p }) }
            saveCache()
        }
    }

    /** Adds a cat on the chosen robots' free tracker slots; returns the robots that had none. */
    suspend fun addPet(name: String, lbs: Double, robots: Set<String>): Result<List<String>> {
        var full = emptyList<String>()
        return local { full = ha().addPet(name, lbs, robots) }.map { full }
    }

    // ---------- derived data ----------

    /** Matches a robot "catWeight" row to the pet whose SmartScale reading has the same time. */
    fun petForWeightAct(a: Act): Pet? {
        val s = state.value
        val owner = nearest(s.pets.flatMap { p -> s.history[p.id].orEmpty().map { p to it } }, a.ts) { it.second.ts } ?: return null
        return owner.first
    }

    fun signal(rssi: Int?) = when {
        rssi == null -> "Unknown"; rssi >= -60 -> "Good"; rssi >= -72 -> "Fair"; else -> "Poor"
    }

    fun ageLabel(p: Pet, long: Boolean = false): String {
        val b = p.birthday ?: return ""
        val per = b.periodUntil(kotlinx.datetime.LocalDate.now())
        return if (long) "${per.years} years ${per.months} months old" else "${per.years} yrs ${per.months} mos"
    }
}
