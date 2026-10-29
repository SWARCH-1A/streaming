package streaming.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.text.Normalizer
import java.util.UUID

actual fun platformHttpClient() = HttpClient(CIO) { configureVaultClient() }
actual fun randomId(): String = UUID.randomUUID().toString()
actual fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
@Composable actual fun NativeBack(enabled: Boolean, onBack: () -> Unit) = Unit
@Composable actual fun Foreground(): Boolean = true
// JVM target exists for common tests; it is not distributed as a desktop product.
@Composable actual fun VideoPlayer(url: String, title: String, modifier: Modifier,
    onPlaying: (Boolean) -> Unit, onPosition: (Long) -> Unit, onError: (String) -> Unit) = Unit
