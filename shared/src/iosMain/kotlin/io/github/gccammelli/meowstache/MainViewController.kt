@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.gccammelli.meowstache

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import io.github.gccammelli.meowstache.ui.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

/** The iOS entry point, called from the Swift app (iosApp/ContentView.swift). */
fun MainViewController(): UIViewController {
    Repo.init(iosStorage())
    // CI screenshots: `simctl launch … --demo` skips the intro and opens demo mode (simulators can't be tapped from the command line).
    if ("--demo" in NSProcessInfo.processInfo.arguments) { Repo.acceptIntro(); Repo.startDemo() }
    NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        if (Repo.signedIn) Repo.refreshAsync()
    }
    if (Repo.signedIn) Repo.refreshAsync()
    val ui = IosPlatformUi()
    return ComposeUIViewController {
        CompositionLocalProvider(LocalPlatform provides ui) {
            AppTheme {
                Box(Modifier.fillMaxSize()) {
                    Root()
                    Toast(ui.toastMessage)
                }
            }
        }
    }
}

/** iOS has no toasts: a small message near the bottom that disappears after a few seconds. */
@Composable
private fun Toast(state: MutableStateFlow<String?>) {
    val msg by state.collectAsState()
    LaunchedEffect(msg) { if (msg != null) { delay(3000); state.value = null } }
    msg?.let {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Text(it, color = Color.White, modifier = Modifier.background(Color(0xE6303030), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

private class IosPlatformUi : PlatformUi {
    val toastMessage = MutableStateFlow<String?>(null)
    private var pickerDelegate: NSObject? = null // UIKit holds delegates weakly

    private val info get() = NSBundle.mainBundle.infoDictionary
    override val appId get() = NSBundle.mainBundle.bundleIdentifier ?: ""
    override val appVersion get() = info?.get("CFBundleShortVersionString") as? String ?: ""
    override val buildNumber get() = (info?.get("CFBundleVersion") as? String)?.toIntOrNull() ?: 0

    private fun top(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }

    override fun openUrl(url: String) {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
    }

    override fun toast(message: String) { toastMessage.value = message }

    /** iOS's own full-screen player, opened at once; the parts of a split visit play back to back (Safari only plays one). */
    override fun playVideo(load: suspend () -> List<String>) {
        val vc = AVPlayerViewController()
        top()?.presentViewController(vc, true, null) ?: return
        kotlinx.coroutines.MainScope().launch {
            runCatching { load().map { AVPlayerItem(uRL = NSURL.URLWithString(it)!!) } }
                .onSuccess { items -> vc.player = AVQueuePlayer(items = items).apply { play() } }
                .onFailure { vc.dismissViewControllerAnimated(true, null); toast("Couldn't open the video: ${it.message}") }
        }
    }

    private fun tempFile(name: String, text: String): NSURL {
        val path = NSTemporaryDirectory() + name
        NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
        return NSURL.fileURLWithPath(path)
    }

    override fun shareFile(mime: String, name: String, text: String) {
        val top = top() ?: return
        val vc = UIActivityViewController(listOf(tempFile(name, text)), null)
        vc.popoverPresentationController?.sourceView = top.view
        top.presentViewController(vc, true, null)
    }

    override fun saveFile(suggestedName: String, text: String, done: (Throwable?) -> Unit) {
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(tempFile(suggestedName, text)), asCopy = true)
        present(picker, onPick = { done(null) }, onCancel = {})
    }

    override fun openFile(done: (Result<String>?) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON, UTTypeData), asCopy = true)
        present(picker, onPick = { url ->
            done(runCatching { NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null) ?: error("Couldn't read the file") })
        }, onCancel = { done(null) })
    }

    private fun present(picker: UIDocumentPickerViewController, onPick: (NSURL) -> Unit, onCancel: () -> Unit) {
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(onPick); pickerDelegate = null
            }
            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { onCancel(); pickerDelegate = null }
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        top()?.presentViewController(picker, true, null)
    }

    // shortcut: no background checks on iOS yet (BGTaskScheduler), so alerts are only checked while the app is open.
    // Asks for notification permission here, when the user turns an alert on, rather than at launch.
    override fun scheduleAlerts() {
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { _, _ -> }
    }

    override fun notifyNew(alerts: List<PetAlert>) {
        for (a in Repo.takeFresh(alerts)) {
            val content = UNMutableNotificationContent().apply { setTitle(a.title); setBody(a.body) }
            UNUserNotificationCenter.currentNotificationCenter()
                .addNotificationRequest(UNNotificationRequest.requestWithIdentifier(a.key, content, null), null)
        }
    }
}
