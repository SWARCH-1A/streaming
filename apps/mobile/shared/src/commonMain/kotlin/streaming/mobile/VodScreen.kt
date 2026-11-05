package streaming.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import streaming.mobile.contracts.*

data class SubtitleCue(val fromMs: Long, val toMs: Long, val text: String)

/** The server validates p2-webvtt-text-v1; the client also bounds and validates untrusted text. */
fun subtitleCues(value: String, durationMs: Long = 9_007_199_254_740_991): List<SubtitleCue> {
    require(durationMs in 1..9_007_199_254_740_991 && value.encodeToByteArray().size <= 1_048_576)
    require(value.none { (it.code < 32 && it !in "\r\n\t") || it.code == 127 })
    val lines = value.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    require(Regex("WEBVTT(?:[ \t][^\n]*)?").matches(lines[0]) && "-->" !in lines[0])
    require(lines.size >= 2 && lines[1].isEmpty())
    fun timestamp(value: String): Long {
        val parts = value.split(':')
        val hours = if (parts.size == 3) parts[0] else "0"
        require(hours.length <= 10)
        val seconds = parts.last().split('.')
        require(parts[parts.size - 2].toInt() <= 59 && seconds[0].toInt() <= 59)
        return hours.toLong() * 3_600_000 + parts[parts.size - 2].toLong() * 60_000 +
            seconds[0].toLong() * 1000 + seconds[1].toLong()
    }
    val timing = Regex("((?:[0-9]{2,}:)?[0-9]{2}:[0-9]{2}\\.[0-9]{3})[ \t]+-->[ \t]+((?:[0-9]{2,}:)?[0-9]{2}:[0-9]{2}\\.[0-9]{3})")
    val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "nbsp" to "\u00a0", "lrm" to "\u200e", "rlm" to "\u200f")
    var previous = -1L
    val cues = lines.drop(2).joinToString("\n").trim('\n').split(Regex("\n{2,}")).mapNotNull { block ->
        if (block.isEmpty()) return@mapNotNull null
        val rows = block.split('\n')
        if (Regex("NOTE(?:[ \t].*)?").matches(rows[0])) {
            require("-->" !in block)
            return@mapNotNull null
        }
        require(rows[0] !in listOf("STYLE", "REGION"))
        val index = if ("-->" in rows[0]) 0 else 1
        require(rows.size > index + 1)
        val match = requireNotNull(timing.matchEntire(rows[index]))
        val from = timestamp(match.groupValues[1]); val to = timestamp(match.groupValues[2])
        require(from >= previous && from < to && to <= durationMs)
        previous = from
        val text = rows.drop(index + 1).joinToString("\n")
        require(text.isNotBlank() && '<' !in text && "-->" !in text && !Regex("&(?!amp;|lt;|gt;|nbsp;|lrm;|rlm;)").containsMatchIn(text))
        SubtitleCue(from, to, Regex("&(amp|lt|gt|nbsp|lrm|rlm);").replace(text) { entities.getValue(it.groupValues[1]) })
    }
    require(cues.isNotEmpty() && cues.size <= 10_000)
    return cues
}

@Composable
fun VodScreen(api: Api, vodId: String, foreground: Boolean, back: () -> Unit) {
    var screen by remember(vodId) { mutableStateOf<P2VodScreen?>(null) }
    var failure by remember(vodId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val controls = remember(vodId) { PlaybackControls() }
    var grant by remember(vodId) { mutableStateOf("") }
    var renditions by remember(vodId) { mutableStateOf(emptyList<Rendition>()) }
    var position by remember(vodId) { mutableLongStateOf(0) }
    var tracks by remember(vodId) { mutableStateOf(emptyList<P2SubtitleTrack>()) }
    var trackId by remember(vodId) { mutableStateOf<String?>(null) }
    var cues by remember(vodId) { mutableStateOf(emptyList<SubtitleCue>()) }
    var subtitleError by remember(vodId) { mutableStateOf<String?>(null) }
    var trackCursor by remember(vodId) { mutableStateOf<String?>(null) }
    var moreTracks by remember(vodId) { mutableStateOf<String?>(null) }
    LaunchedEffect(vodId, foreground, retry) {
        if (!foreground) return@LaunchedEffect
        grant = ""; failure = null
        recoverable {
            val result = api.get<P2VodScreen>("/api/screens/vods/${id(vodId)}", api.session.state.value != null)
            require(result.vod.vodId == vodId && result.mapping.mappingVersion == result.vod.mappingVersion)
            screen = result
            while (isActive) {
                val path = api.publicPath(result.vod.playbackPath)
                api.vodMediaUrl(path, vodId)
                val response = api.call(HttpMethod.Get, path, authenticated = api.session.state.value != null)
                val next = response.headers["X-VOD-Playback-Grant"].orEmpty()
                require(next.matches(Regex("[A-Za-z0-9_.-]{1,4096}")))
                renditions = hlsRenditions(api.vodMediaUrl(path, vodId), response.bodyAsText())
                grant = next
                delay(40_000)
            }
        }.onFailure { grant = ""; failure = it.userMessage() }
    }
    val vod = screen?.vod
    LaunchedEffect(vod?.mappingVersion, foreground, trackCursor, retry) {
        if (vod == null || !foreground) return@LaunchedEffect
        recoverable { api.get<P2SubtitlePage>("/api/vods/${id(vodId)}/subtitles" + (trackCursor?.let { "?cursor=${it.encodeURLParameter()}" } ?: ""), api.session.state.value != null) }
            .onSuccess { page ->
                val compatible = page.items.filter { it.mappingVersion == vod.mappingVersion && it.state == "READY" }
                tracks = if (trackCursor == null) compatible else (tracks + compatible).distinctBy { it.trackId }
                moreTracks = page.nextCursor; subtitleError = null
            }.onFailure { subtitleError = it.userMessage() }
    }
    LaunchedEffect(trackId, vod?.mappingVersion, foreground, retry) {
        cues = emptyList()
        val track = tracks.firstOrNull { it.trackId == trackId } ?: return@LaunchedEffect
        if (!foreground) return@LaunchedEffect
        recoverable {
            val path = api.publicPath(track.contentPath)
            require(path == "/vod-media/${id(vodId)}/subtitles/${id(track.trackId)}.vtt")
            subtitleCues(api.call(HttpMethod.Get, path, authenticated = api.session.state.value != null).bodyAsText(), vod!!.durationMs)
        }.onSuccess { cues = it; subtitleError = null }.onFailure { subtitleError = it.userMessage(); trackId = null }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas)) {
        val landscape = maxWidth > maxHeight
        val video: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(Color.Black)) {
                if (foreground && grant.isNotEmpty() && vod != null && failure == null) {
                    key(vodId, vod.mappingVersion, retry) {
                        VideoPlayer(api.vodMediaUrl(vod.playbackPath, vodId), vod.title, Modifier.fillMaxSize(), {},
                            { position = it }, { failure = it; grant = "" }, grant, position, renditions, controls, back)
                    }
                } else Text(failure ?: if (foreground) "Cargando video…" else "Reproducción en pausa", Modifier.align(Alignment.Center).padding(16.dp), color = Muted)
                val caption = cues.filter { position >= it.fromMs && position < it.toMs }.joinToString("\n") { it.text }
                if (caption.isNotEmpty()) Text(caption, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp, start = 16.dp, end = 16.dp).background(Color.Black.copy(alpha = .85f)).padding(8.dp))
            }
        }
        val details: @Composable () -> Unit = {
            Column {
                failure?.let { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar video") } }
                if (vod != null) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 152.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(vod.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val channel = screen?.channel?.sectionData<ChannelBootstrap>()
                        Text(listOfNotNull(channel?.profile?.displayName, videoDuration(vod.durationMs)).joinToString(" · "),
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (vod.description.isNotBlank()) Text(vod.description, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text(tracks.firstOrNull { it.trackId == trackId }?.label ?: "Subtítulos desactivados") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Desactivar") }, onClick = { trackId = null; menu = false })
                            tracks.forEach { track -> DropdownMenuItem(text = { Text("${track.label} (${track.language})") }, onClick = { trackId = track.trackId; menu = false }) }
                            moreTracks?.let { cursor -> DropdownMenuItem(text = { Text("Más idiomas") }, onClick = { trackCursor = cursor; menu = false }) }
                        }
                    }
                    subtitleError?.let { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar subtítulos") } }
                }
            }
        }
        if (landscape) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1.4f).fillMaxHeight()) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9))
                PlaybackToolbar(controls, back)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                details()
                if (vod != null) ReplayMessages(api, vod, position, grant, foreground, Modifier.weight(1f))
            }
        } else Column(Modifier.fillMaxSize()) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9))
            PlaybackToolbar(controls, back)
            details()
            if (vod != null) ReplayMessages(api, vod, position, grant, foreground, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReplayMessages(api: Api, vod: P2Vod, position: Long, grant: String, foreground: Boolean, modifier: Modifier) {
    val window = position / 10_000 * 10_000
    var pages by remember(window) { mutableIntStateOf(1) }
    var next by remember(window, grant) { mutableStateOf<String?>(null) }
    var messages by remember(vod.vodId, vod.mappingVersion) { mutableStateOf(emptyList<P2ReplayMessage>()) }
    var previousWindow by remember { mutableLongStateOf(-1) }
    var moderationVersion by remember { mutableLongStateOf(-1) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(foreground, grant, window) {
        if (foreground && grant.isNotEmpty()) while (isActive) { delay(2000); refresh++ }
    }
    LaunchedEffect(window, grant, foreground, pages, retry, refresh) {
        if (!foreground || grant.isEmpty() || window >= vod.durationMs) return@LaunchedEffect
        if (window != previousWindow && window != previousWindow + 10_000) messages = emptyList()
        previousWindow = window
        pending = true
        recoverable {
            val to = minOf(vod.durationMs, window + 10_000)
            val snapshot = withTimeout(2500) {
                val collected = mutableListOf<P2ReplayMessage>()
                var cursor: String? = null
                var revision: Long? = null
                repeat(pages) {
                    val page = api.call(HttpMethod.Get, "/api/chat/vods/${id(vod.vodId)}/messages?fromMs=$window&toMs=$to&limit=50" +
                        (cursor?.let { "&cursor=${it.encodeURLParameter()}" } ?: ""), authenticated = api.session.state.value != null,
                        headers = mapOf("X-VOD-Playback-Grant" to grant)).body<P2ReplayPage>()
                    require(page.mappingVersion == vod.mappingVersion && (revision == null || revision == page.moderationVersion))
                    revision = page.moderationVersion
                    collected += page.items
                    cursor = page.nextCursor
                    if (cursor == null) return@withTimeout Triple(collected.toList(), revision!!, cursor)
                }
                Triple(collected.toList(), revision!!, cursor)
            }
            if (moderationVersion != -1L && moderationVersion != snapshot.second) messages = emptyList()
            moderationVersion = snapshot.second
            messages = (messages.filter { it.mediaOffsetMs < window || it.mediaOffsetMs >= to } + snapshot.first)
                .distinctBy { it.messageId }.sortedWith(compareBy({ it.mediaOffsetMs }, { it.sequence })).takeLast(500)
            next = snapshot.third; failure = null
        }.onFailure { messages = emptyList(); next = null; failure = it.userMessage() }
        pending = false
    }
    Column(modifier.fillMaxWidth().padding(12.dp)) {
        Text("Chat de la grabación", style = MaterialTheme.typography.titleMedium)
        if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar chat") } }
        val list = rememberLazyListState()
        val visible = messages.filter { it.mediaOffsetMs <= position }
        var follow by remember { mutableStateOf(true) }
        TextButton(onClick = { follow = !follow }) { Text(if (follow) "Pausar desplazamiento" else "Seguir últimos mensajes") }
        LaunchedEffect(visible.size, follow) { if (follow && visible.isNotEmpty()) list.scrollToItem(visible.lastIndex) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(visible, key = { it.messageId }) { message -> Text("${message.author.displayName}: ${message.text}", Modifier.padding(vertical = 4.dp)) }
        }
        if (next != null) {
            if (pages < 10) TextButton(enabled = !pending, onClick = { pages++ }) { Text("Más mensajes") }
            else Text("Se muestran hasta 500 mensajes por ventana.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
