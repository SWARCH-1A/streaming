package streaming.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import streaming.mobile.contracts.*

@Composable
fun ChannelScreen(api: Api, handle: String, foreground: Boolean, navigate: (String) -> Unit) {
    var channel by remember(handle) { mutableStateOf<ChannelBootstrap?>(null) }
    var error by remember(handle) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(handle, foreground, retry) {
        if (!foreground) return@LaunchedEffect
        while (isActive) {
            recoverable { api.get<ChannelBootstrap>("/api/channels/by-handle/${id(handle)}") }
                .onSuccess { channel = it; error = null }.onFailure { error = it.userMessage(); if (it is ApiFailure && it.status in listOf(404, 410, 403)) channel = null }
            delay(3_000)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (channel == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } }
        channel?.let { data ->
            item { NetworkImage(api, data.channel.bannerUri, "Portada de ${data.profile.displayName}", Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp))) }
            item { SectionHeading(data.profile.displayName, "@${data.handle}") }
            item { Availability(data.availability); if (data.channel.description.isNotBlank()) Text(data.channel.description, modifier = Modifier.padding(top = 12.dp)) }
            data.stream?.let { stream -> item {
                Text(stream.title, style = MaterialTheme.typography.titleLarge)
                Button(enabled = data.availability in listOf("PLAYABLE", "RECONNECTING"), onClick = { navigate("watch:${stream.streamId}") }) { Text("Ver transmisión") }
            } }
            item { Notice("El seguimiento aún no está disponible en el servidor.") }
            item { SectionHeading("Videos del canal"); Notice("La biblioteca de videos aún no está disponible en el servidor.") }
        }
    }
}

@Composable
fun WatchScreen(api: Api, streamId: String, foreground: Boolean, navigate: (String) -> Unit, back: () -> Unit) {
    var stream by remember(streamId) { mutableStateOf<PublicStream?>(null) }
    var live by remember(streamId) { mutableStateOf<PublicSession?>(null) }
    var channel by remember(streamId) { mutableStateOf<ChannelBootstrap?>(null) }
    var error by remember(streamId) { mutableStateOf<String?>(null) }
    var playerError by remember(streamId) { mutableStateOf<String?>(null) }
    var leaseError by remember(streamId) { mutableStateOf<String?>(null) }
    var playing by remember(streamId) { mutableStateOf(false) }
    var retry by remember(streamId) { mutableIntStateOf(0) }
    LaunchedEffect(streamId, foreground, retry) {
        if (!foreground) return@LaunchedEffect
        while (isActive) {
            recoverable {
                val next = api.get<PublicStream>("/api/streams/${id(streamId)}")
                val session = next.sessionId?.let { api.get<PublicSession>("/api/streams/sessions/${id(it)}") }
                require(session == null || (session.streamId == streamId && session.sessionId == next.sessionId))
                stream = next; live = session; error = null
            }.onFailure {
                error = it.userMessage()
                live = null; playing = false
            }
            delay(3_000)
        }
    }
    LaunchedEffect(stream?.channelId) {
        stream?.channelId?.let { channelId -> recoverable { api.get<ChannelBootstrap>("/api/channels/${id(channelId)}") }.onSuccess { channel = it } }
    }
    val leases = remember(live?.sessionId) { live?.let { ViewerLeases(api, it.sessionId) } }
    LaunchedEffect(playing, foreground, leases) { if (playing && foreground) leases?.whilePlaying { leaseError = it } }
    val keyboardHeight = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val keyboardOpen = keyboardHeight > 0.dp
    BoxWithConstraints(Modifier.fillMaxSize().background(Canvas)) {
        val landscape = maxWidth > maxHeight + keyboardHeight
        val video: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(Color.Black)) {
                val session = live
                val media = session?.playbackUrl?.let { runCatching { api.mediaUrl(it, session.sessionId) }.getOrNull() }
                if (foreground && session?.availability == "PLAYABLE" && media != null && playerError == null) {
                    key(session.sessionId, retry) {
                        VideoPlayer(media, stream?.title.orEmpty(), Modifier.fillMaxSize(),
                            { playing = it }, {}, { playerError = it; playing = false })
                    }
                } else Column(Modifier.align(Alignment.Center).padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(playerError ?: error ?: when {
                        !foreground -> "Reproducción en pausa"
                        session?.availability == "RECONNECTING" -> "Reconectando la transmisión…"
                        stream == null -> "Cargando transmisión…"
                        session?.availability == "PLAYABLE" && media == null -> "Video no disponible"
                        else -> "El canal está offline"
                    }, color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    if (error != null || playerError != null) TextButton(onClick = { playerError = null; retry++ }) { Text("Reintentar") }
                }
                IconButton(onClick = back, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .size(48.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(8.dp))) {
                    WatchGlyph("back", Ink, "Volver")
                }
            }
        }
        val details: @Composable () -> Unit = {
            if (!keyboardOpen) StreamDetails(api, stream, live, channel, navigate)
        }
        val chat: @Composable (Modifier) -> Unit = { modifier ->
            val session = live
            if (session != null) key(session.sessionId) { LiveChat(api, session.sessionId, foreground, navigate, modifier, keyboardOpen, leaseError) }
            else Column(modifier) {
                ChatHeading(false, "Chat en vivo")
                Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (error == null) "El chat estará disponible cuando comience la transmisión." else "No se pudo conectar. Reintenta la transmisión.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
        }
        if (landscape) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1.2f).fillMaxHeight()) { video(Modifier.fillMaxWidth().weight(1f)); details() }
            VerticalDivider(color = Raised)
            chat(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize()) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9))
            details()
            chat(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun StreamDetails(api: Api, stream: PublicStream?, live: PublicSession?, channel: ChannelBootstrap?, navigate: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surface).then(if (channel != null)
        Modifier.clickable { navigate("channel:${channel.handle}") } else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val name = channel?.profile?.displayName ?: "Transmisión en vivo"
        NetworkImage(api, channel?.profile?.avatarUri, null, Modifier.size(36.dp).clip(CircleShape), name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                stream?.category?.name?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp)) }
            }
            Text(stream?.title ?: "Conectando…", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (live?.availability == "PLAYABLE") "EN VIVO" else if (live?.availability == "RECONNECTING") "RECONECTANDO" else "OFFLINE",
                color = if (live?.availability == "PLAYABLE") Live else Muted, style = MaterialTheme.typography.labelMedium)
            live?.let { Text(if (it.viewerCount == 1L) "1 espectador" else "${it.viewerCount} espectadores", color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
    HorizontalDivider(color = Raised)
}

@Composable
private fun ChatHeading(connected: Boolean, title: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(6.dp).background(if (connected) Primary else Muted, CircleShape))
        Text(title, style = MaterialTheme.typography.labelLarge)
    }
    HorizontalDivider(color = Raised)
}

@Composable
private fun LiveChat(api: Api, sessionId: String, foreground: Boolean, navigate: (String) -> Unit,
                     modifier: Modifier, keyboardOpen: Boolean, leaseError: String?) {
    val login by api.session.state.collectAsState()
    val chat = remember(sessionId, login?.userId, api.session.generation) { Chat(api, sessionId) }
    val state by chat.state.collectAsState()
    var text by remember(sessionId, login?.userId) { mutableStateOf("") }
    var error by remember(sessionId) { mutableStateOf<String?>(null) }
    var following by remember(sessionId) { mutableStateOf(true) }
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(chat, foreground) { if (foreground) chat.connect() }
    LaunchedEffect(dragging) { if (dragging) following = false }
    LaunchedEffect(list) { snapshotFlow { !list.canScrollForward && !list.isScrollInProgress }.collect { if (it) following = true } }
    LaunchedEffect(state.messages.lastOrNull()?.messageId, following) {
        if (following && state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex)
    }
    fun send() {
        scope.launch {
            error = null
            recoverable { chat.send(if (state.pendingId != null) state.pendingText else text) }.onFailure { error = it.userMessage() }
        }
    }
    Column(modifier.background(Canvas)) {
        if (!keyboardOpen) ChatHeading(state.connected, if (state.connected) "Chat en vivo" else "Conectando al chat…")
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = false,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                items(state.messages, key = { it.messageId }) { message ->
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = Primary, fontWeight = FontWeight.SemiBold)) { append(message.author.displayName) }
                        append(": "); append(message.text)
                    }, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.messages.isEmpty()) Text("Bienvenido al chat", color = Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
            if (!following && list.canScrollForward) FilledTonalButton(onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp), shape = RoundedCornerShape(8.dp)) { Text("Últimos mensajes ↓") }
        }
        val notice = error ?: state.error ?: leaseError ?: when {
            state.pendingId != null -> "Mensaje sin confirmar. Reintenta o descarta el envío."
            state.gap -> "Falta parte del historial. Se muestran los mensajes recientes."
            state.connected && state.status != "OPEN" -> "El chat está en modo lectura."
            else -> null
        }
        notice?.let {
            Row(Modifier.fillMaxWidth().background(Panel).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = if (error != null || state.error != null) MaterialTheme.colorScheme.error else Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (state.pendingId != null) TextButton(onClick = { chat.discard() }) { Text("Descartar") }
            }
        }
        HorizontalDivider(color = Raised)
        if (login == null) {
            OutlinedButton(onClick = { navigate("login") }, modifier = Modifier.fillMaxWidth().padding(12.dp).height(48.dp),
                shape = RoundedCornerShape(8.dp)) { Text("Inicia sesión para chatear") }
        } else Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val canWrite = state.connected && state.status == "OPEN"
            OutlinedTextField(text, { text = it; error = null }, enabled = canWrite && state.pendingId == null,
                placeholder = { Text("Enviar un mensaje", style = MaterialTheme.typography.bodyMedium) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canWrite && text.isNotBlank() && codePoints(text) <= 500) send() }),
                isError = codePoints(text) > 500,
                trailingIcon = if (codePoints(text) >= 450) ({ Text("${codePoints(text)}/500", style = MaterialTheme.typography.labelMedium) }) else null,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            FilledIconButton(onClick = ::send, enabled = canWrite && (state.pendingId != null || (text.isNotBlank() && codePoints(text) <= 500)),
                modifier = Modifier.size(56.dp), shape = RoundedCornerShape(8.dp)) {
                if (state.pendingId != null) Text("↻", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "Reintentar mensaje" })
                else WatchGlyph("send", LocalContentColor.current, "Enviar mensaje")
            }
        }
        LaunchedEffect(state.pendingId) { if (state.pendingId == null && state.pendingText.isEmpty()) text = "" }
    }
}

@Composable
private fun WatchGlyph(kind: String, color: Color, description: String) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp).semantics { contentDescription = description }) {
        val stroke = 2.dp.toPx()
        if (kind == "back") {
            drawLine(color, Offset(size.width * .65f, size.height * .2f), Offset(size.width * .35f, size.height * .5f), strokeWidth = stroke)
            drawLine(color, Offset(size.width * .35f, size.height * .5f), Offset(size.width * .65f, size.height * .8f), strokeWidth = stroke)
        } else {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .15f, size.height * .2f); lineTo(size.width * .85f, size.height * .5f)
                lineTo(size.width * .15f, size.height * .8f); lineTo(size.width * .3f, size.height * .5f); close()
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawLine(color, Offset(size.width * .3f, size.height * .5f), Offset(size.width * .85f, size.height * .5f), strokeWidth = stroke)
        }
    }
}

@Composable
fun VideoQualityButton(quality: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(48.dp).semantics { contentDescription = "Calidad de video: $quality" },
        colors = ButtonDefaults.textButtonColors(contentColor = Ink, containerColor = Color.Black.copy(alpha = .65f)),
        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text("${if (quality == "Automática") "Auto" else quality} ▾", style = MaterialTheme.typography.labelMedium)
    }
}
