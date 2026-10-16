package io.github.gccammelli.meowstache

import kotlinx.coroutines.launch

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/** Small key-value settings store: SharedPreferences on Android, NSUserDefaults on iOS. */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?) // null removes the key
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getStringSet(key: String): Set<String>
    fun putStringSet(key: String, value: Set<String>)
    fun clear()
}

/** Login tokens: encrypted with the Android Keystore, or kept in the iOS Keychain. */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String?) // null removes
    fun clear()
}

/** Private app files (the history cache). */
interface AppFiles {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
}

/** Everything Repo needs from the platform, created once at startup. */
class PlatformStorage(val settings: KeyValue, val auth: KeyValue, val secrets: SecretStore, val files: AppFiles)

/** UI-side platform actions, provided by the host (MainActivity / MainViewController) via [LocalPlatform]. */
interface PlatformUi {
    val appId: String
    val appVersion: String
    val buildNumber: Int
    fun openUrl(url: String)
    /**
     * Plays a video whose link [load] fetches (visit videos: Home Assistant signs it first). Platforms should show
     * something at once, since fetching and buffering take a moment; by default the system opens the link (Safari
     * plays MP4 itself).
     */
    fun playVideo(load: suspend () -> String) {
        kotlinx.coroutines.MainScope().launch {
            runCatching { load() }.onSuccess(::openUrl).onFailure { toast("Couldn't open the video: ${it.message}") }
        }
    }
    /** Instant notifications need a background connection; only Android allows one (see LiveService). */
    val canInstantNotifications: Boolean get() = false
    fun syncInstantNotifications() {}
    fun toast(message: String)
    /** Share a text file (CSV export) through the system share sheet. */
    fun shareFile(mime: String, name: String, text: String)
    /** Let the user pick where to save a text file; [done] gets null on success or the error. */
    fun saveFile(suggestedName: String, text: String, done: (Throwable?) -> Unit)
    /** Let the user pick a text file to open; [done] gets its content, null if cancelled, or throws via Result. */
    fun openFile(done: (Result<String>?) -> Unit)
    /** (Re)schedule the background alert checks. */
    fun scheduleAlerts()
    /** Post the alerts not posted before (also logs them to the Message Center). */
    fun notifyNew(alerts: List<PetAlert>)
}

val LocalPlatform = staticCompositionLocalOf<PlatformUi> { error("No platform provided") }

/** The device's country code ("GB", "US", …), for the default store region. */
expect fun deviceCountry(): String

/** Decodes an image, downscaled so its longest side is at most [maxSide] pixels. */
expect fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap?
