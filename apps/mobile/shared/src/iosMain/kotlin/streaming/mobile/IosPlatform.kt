@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package streaming.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.interop.UIKitViewController
import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import platform.AVFoundation.*
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
                                  onPosition: (Long) -> Unit, onError: (String) -> Unit, playbackGrant: String, startPositionMs: Long) {
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
    val player = remember(url) { AVPlayer(uRL = NSURL(string = url)).apply { if (startPositionMs > 0) seekToTime(CMTimeMake(startPositionMs, 1000)); play() } }
    val controller = remember(player) { AVPlayerViewController().apply { this.player = player } }
    var variants by remember(url) { mutableStateOf(emptyList<Rendition>()) }
    var quality by remember(url) { mutableStateOf("Automática") }
    var menu by remember { mutableStateOf(false) }
    val playingCallback by rememberUpdatedState(onPlaying)
    DisposableEffect(player) {
        onDispose { player.pause(); controller.player = null; playingCallback(false) }
    }
    LaunchedEffect(url) {
        val client = platformHttpClient()
        try { recoverable { hlsRenditions(url, client.get(url).bodyAsText()) }.onSuccess { variants = it } }
        finally { client.close() }
    }
    LaunchedEffect(player) {
        while (true) {
            playingCallback(player.rate > 0 && controller.readyForDisplay)
            val position = CMTimeGetSeconds(player.currentTime())
            if (position.isFinite()) onPosition((position * 1000).toLong().coerceAtLeast(0))
            if (player.currentItem?.status == AVPlayerItemStatusFailed) {
                onError("No se pudo reproducir el video. Reintenta la reproducción."); break
            }
            delay(250)
        }
    }
    Box(modifier) {
        UIKitViewController(factory = { controller }, modifier = Modifier.matchParentSize())
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            VideoQualityButton(quality) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                (listOf(Rendition("Automática", url)) + variants).forEach { rendition ->
                    DropdownMenuItem(text = { Text(rendition.label) }, onClick = {
                        val time = player.currentTime()
                        val wasPlaying = player.rate > 0
                        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = NSURL(string = rendition.url)))
                        player.seekToTime(time)
                        if (wasPlaying) player.play()
                        quality = rendition.label; menu = false
                    })
                }
            }
        }
    }
}
