package io.github.gccammelli.meowstache

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.FileProvider
import io.github.gccammelli.meowstache.ui.AppTheme
import java.io.File

class MainActivity : ComponentActivity(), PlatformUi {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var pendingSave: Pair<String, (Throwable?) -> Unit>? = null
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val (text, done) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri != null) done(runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }.exceptionOrNull())
    }
    private var pendingOpen: ((Result<String>?) -> Unit)? = null
    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = pendingOpen ?: return@registerForActivityResult
        pendingOpen = null
        done(uri?.let { u -> runCatching { contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() } } })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { CompositionLocalProvider(LocalPlatform provides this) { AppTheme { Root() } } }
    }

    override fun onResume() {
        super.onResume()
        if (Repo.signedIn) Repo.refreshIfStale()
        LiveService.sync(this)
    }

    // ---------- PlatformUi ----------

    override val appId get() = BuildConfig.APPLICATION_ID
    override val appVersion get() = BuildConfig.VERSION_NAME
    override val buildNumber get() = BuildConfig.VERSION_CODE

    override fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /**
     * In-app, with the platform's VideoView (the phone's default video app may want storage access just to stream:
     * MX Player does). It opens at once with a spinner while Home Assistant signs the link and the video buffers.
     */
    override fun playVideo(load: suspend () -> String) {
        val video = android.widget.VideoView(this)
        val spinner = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(android.widget.ProgressBar(context))
            addView(android.widget.TextView(context).apply {
                text = "Loading video…"; setTextColor(android.graphics.Color.WHITE); setPadding(0, 24, 0, 0)
            })
        }
        val box = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(video, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
            addView(spinner, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(box)
        video.setMediaController(android.widget.MediaController(this).also { it.setAnchorView(video) })
        video.setOnPreparedListener { spinner.visibility = android.view.View.GONE; video.start() }
        video.setOnErrorListener { _, _, _ -> toast("Couldn't play the video"); dialog.dismiss(); true }
        val job = lifecycleScope.launch {
            runCatching { load() }
                .onSuccess { video.setVideoURI(Uri.parse(it)) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { toast("Couldn't open the video: ${it.message}"); dialog.dismiss() } }
        }
        dialog.setOnDismissListener { job.cancel(); video.stopPlayback() }
        dialog.show()
    }

    override val canInstantNotifications get() = true
    override fun syncInstantNotifications() = LiveService.sync(this)

    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun shareFile(mime: String, name: String, text: String) {
        val f = File(File(cacheDir, "exports").apply { mkdirs() }, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export"))
    }

    override fun saveFile(suggestedName: String, text: String, done: (Throwable?) -> Unit) {
        pendingSave = text to done
        saveDoc.launch(suggestedName)
    }

    override fun openFile(done: (Result<String>?) -> Unit) {
        pendingOpen = done
        openDoc.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
    }

    override fun scheduleAlerts() = AlertWorker.schedule(this)

    override fun notifyNew(alerts: List<PetAlert>) = AlertWorker.notifyNew(this, alerts)
}
