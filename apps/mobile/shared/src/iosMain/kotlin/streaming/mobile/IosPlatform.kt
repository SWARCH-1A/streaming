@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package streaming.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.*
import platform.UIKit.*

actual fun platformHttpClient() = HttpClient(Darwin) { configureVaultClient() }
actual fun randomId(): String = NSUUID().UUIDString.lowercase()
actual fun normalizeText(value: String): String = NSString.create(string = value).precomposedStringWithCanonicalMapping

fun MainViewController(store: SecureStore, origin: String): UIViewController {
    val api = Api(origin, Session(store), platformHttpClient())
    return ComposeUIViewController { VaultApp(api) }
}

@Composable actual fun NativeBack(enabled: Boolean, onBack: () -> Unit) = Unit
@Composable actual fun Foreground(): Boolean {
    var active by remember { mutableStateOf(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val resume = center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { active = true }
        val pause = center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { active = false }
        onDispose { center.removeObserver(resume); center.removeObserver(pause) }
    }
    return active
}

@Composable actual fun VideoPlayer(url: String, title: String, modifier: Modifier, onPlaying: (Boolean) -> Unit,
                                  onPosition: (Long) -> Unit, onError: (String) -> Unit, playbackGrant: String, startPositionMs: Long, renditions: List<Rendition>?, controls: PlaybackControls, onDismiss: () -> Unit) {
    val mediaUrl = NSURL(string = url)
    // AVURLAsset uses Foundation's default cookie store. This short-lived grant is
    // scoped to one VOD; the application session never enters media requests.
    if (playbackGrant.isNotEmpty() && mediaUrl?.host != null) {
        NSHTTPCookie.cookieWithProperties(mapOf<Any?, Any>(NSHTTPCookieName to "vod_access", NSHTTPCookieValue to playbackGrant,
            NSHTTPCookieDomain to mediaUrl.host!!, NSHTTPCookiePath to mediaUrl.path!!.substringBeforeLast('/') + "/",
            NSHTTPCookieSecure to "TRUE", NSHTTPCookieExpires to NSDate.dateWithTimeIntervalSinceNow(60.0)))?.let {
            NSHTTPCookieStorage.sharedHTTPCookieStorage.setCookie(it)
        }
    }
    val player = remember(url) { AVPlayer(uRL = NSURL(string = url)).apply { if (startPositionMs > 0) seekToTime(CMTimeMake(startPositionMs, 1000)) } }
    val controller = remember(player) { AVPlayerViewController().apply {
        this.player = player
        showsPlaybackControls = true
    } }
    // AVKit owns its root view and internal recognizers. Keep our dismiss recognizer on
    // a stable parent rather than adding it to a view AVKit can replace while loading.
    val host = remember(controller) { UIViewController().apply {
        addChildViewController(controller)
        view.addSubview(controller.view)
        controller.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activateConstraints(listOf(
            controller.view.topAnchor.constraintEqualToAnchor(view.topAnchor),
            controller.view.bottomAnchor.constraintEqualToAnchor(view.bottomAnchor),
            controller.view.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor),
            controller.view.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor),
        ))
        controller.didMoveToParentViewController(this)
    } }
    val dismissCallback by rememberUpdatedState(onDismiss)
    val dismissGesture = remember(controller) { PlayerDismissGesture(controller) { dismissCallback() } }
    DisposableEffect(controller) {
        controller.delegate = dismissGesture
        host.view.addGestureRecognizer(dismissGesture.recognizer)
        onDispose { host.view.removeGestureRecognizer(dismissGesture.recognizer); controller.delegate = null }
    }
    var variants by remember(url) { mutableStateOf(emptyList<Rendition>()) }
    val playingCallback by rememberUpdatedState(onPlaying)
    val positionCallback by rememberUpdatedState(onPosition)
    val errorCallback by rememberUpdatedState(onError)
    DisposableEffect(player) {
        // The default iOS audio category is silenced by the Ring/Silent switch.
        // Video playback needs its own media session, activated only while this player exists.
        val audio = AVAudioSession.sharedInstance()
        val audioReady = audio.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeMoviePlayback,
            options = 0u, error = null) && audio.setActive(true, error = null)
        if (audioReady) player.play()
        else errorCallback("No se pudo activar el audio. Finaliza cualquier llamada y reintenta la reproducción.")
        onDispose {
            player.pause(); controller.player = null; playingCallback(false)
            if (audioReady) audio.setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        }
    }
    LaunchedEffect(url, renditions, controls.retry) {
        if (renditions != null) { variants = renditions; controls.unavailable = false; return@LaunchedEffect }
        val client = platformHttpClient()
        try { recoverable {
            val response = client.get(url)
            require(response.status.isSuccess())
            hlsRenditions(url, response.bodyAsText())
        }.onSuccess { variants = it; controls.unavailable = false }.onFailure { controls.unavailable = true } }
        finally { client.close() }
    }
    LaunchedEffect(player) {
        while (true) {
            playingCallback(player.rate > 0 && controller.readyForDisplay)
            val position = CMTimeGetSeconds(player.currentTime())
            if (position.isFinite()) positionCallback((position * 1000).toLong().coerceAtLeast(0))
            if (player.currentItem?.status == AVPlayerItemStatusFailed) {
                errorCallback("No se pudo reproducir el video. Reintenta la reproducción."); break
            }
            delay(250)
        }
    }
    LaunchedEffect(variants) { controls.qualities = variants.map { VideoQuality(it.url, it.label) } }
    LaunchedEffect(player, controls.selectedId, variants) {
        val target = variants.firstOrNull { it.url == controls.selectedId }?.url ?: url
        val current = (player.currentItem?.asset as? AVURLAsset)?.URL?.absoluteString
        if (current != target) {
            val time = player.currentTime()
            val wasPlaying = player.rate > 0
            player.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = NSURL(string = target)))
            player.seekToTime(time)
            if (wasPlaying) player.play()
        }
    }
    UIKitViewController(factory = { host }, modifier = modifier,
        properties = androidx.compose.ui.viewinterop.UIKitInteropProperties(
            interactionMode = androidx.compose.ui.viewinterop.UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ))
}
