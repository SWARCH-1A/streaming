package streaming.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.text.Normalizer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun platformHttpClient() = HttpClient(OkHttp) { configureVaultClient() }
actual fun randomId(): String = UUID.randomUUID().toString()
actual fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

class AndroidSecureStore(context: Context) : SecureStore {
    private val file = File(context.noBackupFilesDir, "vault-session.enc")
    private val alias = "vault.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size in 29..16_384)
        val buffer = ByteBuffer.wrap(bytes)
        val size = buffer.get().toInt()
        require(size == 12)
        val iv = ByteArray(size).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }.doFinal(encrypted).decodeToString()
    }
    override fun write(value: String?) {
        if (value == null) { check(!file.exists() || file.delete()) { "No se pudo eliminar la sesión local." }; return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        val temp = File(file.parentFile, "vault-session.tmp")
        temp.outputStream().use { it.write(byteArrayOf(cipher.iv.size.toByte())); it.write(cipher.iv); it.write(encrypted); it.fd.sync() }
        check(temp.renameTo(file)) { "No se pudo guardar la sesión de forma segura." }
    }
}

@Composable actual fun NativeBack(enabled: Boolean, onBack: () -> Unit) { BackHandler(enabled, onBack) }
@Composable actual fun Foreground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return active
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun VideoPlayer(url: String, title: String, modifier: Modifier, onPlaying: (Boolean) -> Unit,
                       onPosition: (Long) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        prepare()
        playWhenReady = true
    } }
    val playingCallback by rememberUpdatedState(onPlaying)
    val errorCallback by rememberUpdatedState(onError)
    var tracks by remember(url) { mutableStateOf(Tracks.EMPTY) }
    var quality by remember(url) { mutableStateOf("Automática") }
    var menu by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        var decoded = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { decoded = true; playingCallback(player.isPlaying) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playingCallback(isPlaying && decoded) }
            override fun onTracksChanged(next: Tracks) { tracks = next }
            override fun onPlayerError(error: PlaybackException) {
                playingCallback(false)
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    decoded = false
                    player.seekToDefaultPosition(); player.prepare(); player.play()
                } else errorCallback("No se pudo reproducir el video. Reintenta la reproducción.")
            }
        }
        player.addListener(listener)
        onDispose { playingCallback(false); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player) { while (true) { onPosition(player.currentPosition.coerceAtLeast(0)); delay(250) } }
    Box(modifier) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; contentDescription = title } },
            modifier = Modifier.matchParentSize(), update = { it.player = player }, onRelease = { it.player = null })
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            VideoQualityButton(quality) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Automática") }, onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                    quality = "Automática"; menu = false
                })
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
                    (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                        val format = group.getTrackFormat(index)
                        val label = if (format.height > 0) "${format.height}p" else "${format.bitrate / 1000} kbps"
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                            quality = label; menu = false
                        })
                    }
                }
            }
        }
    }
}
