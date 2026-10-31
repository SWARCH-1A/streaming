package streaming.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient

interface SecureStore {
    fun read(): String?
    fun write(value: String?)
}

fun createApi(origin: String, store: SecureStore, reportFailure: (String, Throwable) -> Unit = { _, _ -> }): Api =
    Api(origin, Session(store), platformHttpClient(), reportFailure)

expect fun platformHttpClient(): HttpClient
expect fun randomId(): String
expect fun normalizeText(value: String): String

/** A player belongs to one screen and never survives backgrounding as an active stream. */
@Composable
expect fun VideoPlayer(
    url: String,
    title: String,
    modifier: Modifier,
    onPlaying: (Boolean) -> Unit,
    onPosition: (Long) -> Unit,
    onError: (String) -> Unit,
)
