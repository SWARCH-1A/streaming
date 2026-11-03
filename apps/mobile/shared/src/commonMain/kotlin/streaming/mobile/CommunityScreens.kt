package streaming.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.call.body
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import streaming.mobile.contracts.*

/** Tagged Gateway sections never turn a failed dependency into an empty successful list. */
inline fun <reified T> JsonObject.sectionData(): T? =
    if (string("status") in listOf("AVAILABLE", "EMPTY")) get("data")?.takeUnless { it is JsonNull }
        ?.let { wireJson.decodeFromJsonElement<T>(it) } else null

@Composable
fun SignInNotice(navigate: (String) -> Unit) {
    Notice("Inicia sesión para consultar esta sección.")
    Button(onClick = { navigate("login") }) { Text("Iniciar sesión") }
}

@Composable
fun FollowControl(api: Api, channel: ChannelBootstrap, initial: P2Follow?, navigate: (String) -> Unit) {
    val login by api.session.state.collectAsState()
    var follow by remember(channel.channel.channelId, login?.userId) { mutableStateOf(initial) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val path = "/api/channels/${id(channel.channel.channelId)}/followers/me"
    LaunchedEffect(path, login?.userId, retry) {
        if (login != null) recoverable { api.get<P2Follow>(path, true) }
            .onSuccess { follow = it; failure = null }.onFailure { failure = it.userMessage() }
    }
    if (login?.userId == channel.channel.ownerUserId) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !pending && (login == null || follow != null), onClick = {
            if (login == null) navigate("login") else scope.launch {
                pending = true
                recoverable { api.call(if (follow?.following == true) HttpMethod.Delete else HttpMethod.Put,
                    path, authenticated = true, key = randomId()).body<P2Follow>() }
                    .onSuccess { follow = it; failure = null }.onFailure { failure = it.userMessage(); retry++ }
                pending = false
            }
        }) { Text(if (follow?.following == true) "Dejar de seguir" else "Seguir canal") }
        failure?.let { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } }
        if (follow?.following == true) NotificationPreferences(api, channel.channel.channelId)
    }
}

@Composable
fun FollowingScreen(api: Api, foreground: Boolean, navigate: (String) -> Unit) {
    val login by api.session.state.collectAsState()
    var cursor by remember { mutableStateOf<String?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf(emptyList<Pair<P2FollowedChannel, ChannelBootstrap?>>()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(login?.userId, foreground, cursor, retry) {
        if (login == null || !foreground) return@LaunchedEffect
        pending = true
        recoverable {
            val page = api.get<P2FollowPage>("/api/me/followed-channels?limit=20" + (cursor?.let { "&cursor=${it.encodeURLParameter()}" } ?: ""), true)
            val channels = coroutineScope { page.items.map { follow -> async {
                follow to recoverable { api.get<ChannelBootstrap>("/api/channels/${id(follow.channelId)}") }.getOrNull()
            } }.awaitAll() }
            rows = if (cursor == null) channels else (rows + channels).distinctBy { it.first.channelId }
            next = page.nextCursor; failure = null
        }.onFailure { failure = it.userMessage() }
        pending = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading("Canales seguidos", "Vuelve a tus comunidades.") }
        if (login == null) item { SignInNotice(navigate) }
        else {
            if (pending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            failure?.let { item { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } }
            items(rows, key = { it.first.channelId }) { (_, channel) ->
                if (channel == null) Notice("Canal no disponible.")
                else Column(Modifier.fillMaxWidth().clickable { navigate("channel:${channel.handle}") }.padding(12.dp)) {
                    Text(channel.profile.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("@${channel.handle}", color = Muted); Availability(channel.availability)
                }
            }
            if (!pending && rows.isEmpty() && failure == null) item { Notice("Todavía no sigues ningún canal.") }
            next?.let { value -> item { Button(enabled = !pending, onClick = { cursor = value }) { Text("Más canales") } } }
            item { TextButton(onClick = { cursor = null; retry++ }) { Text("Actualizar") } }
        }
    }
}

/** General and per-channel switches share the same versioned command contract. */
@Composable
fun NotificationPreferences(api: Api, channelId: String? = null) {
    val path = channelId?.let { "/api/notifications/channels/${id(it)}/preference" } ?: "/api/notifications/preferences"
    var value by remember(path) { mutableStateOf<P2Preferences?>(null) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var pending by remember(path) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, retry) {
        recoverable { api.get<P2Preferences>(path, true) }.onSuccess { value = it }.onFailure { failure = it.userMessage() }
    }
    val current = value
    fun change(email: Boolean, push: Boolean) {
        if (current == null || pending) return
        scope.launch {
            pending = true
            recoverable { api.call(HttpMethod.Put, path, buildJsonObject {
                put("emailEnabled", email); put("pushEnabled", push); put("expectedVersion", current.version)
            }, true, randomId()).body<P2Preferences>() }.onSuccess { value = it; failure = null }
                .onFailure { failure = it.userMessage(); retry++ }
            pending = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (channelId == null) "Preferencias de avisos" else "Avisos de este canal", style = MaterialTheme.typography.titleMedium)
        if (current != null) {
            Row { Checkbox(current.emailEnabled, enabled = !pending, onCheckedChange = { change(it, current.pushEnabled) }); Text("Correo electrónico", Modifier.padding(top = 12.dp)) }
            Row { Checkbox(current.pushEnabled, enabled = !pending, onCheckedChange = { change(current.emailEnabled, it) }); Text("Notificaciones push", Modifier.padding(top = 12.dp)) }
        }
        failure?.let { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar preferencias") } }
    }
}

fun notificationDestination(path: String): String? =
    Regex("^/channels/([A-Za-z0-9_-]{1,64})$").matchEntire(path)?.let { "channel:${it.groupValues[1]}" }

@Composable
fun InboxScreen(api: Api, foreground: Boolean, navigate: (String) -> Unit) {
    val login by api.session.state.collectAsState()
    var rows by remember { mutableStateOf(emptyList<P2Notification>()) }
    var next by remember { mutableStateOf<String?>(null) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(login?.userId, foreground, cursor, retry) {
        if (login == null || !foreground) return@LaunchedEffect
        do {
            pending = true
            recoverable { api.get<P2NotificationPage>("/api/notifications?limit=20" + (cursor?.let { "&cursor=${it.encodeURLParameter()}" } ?: ""), true) }
                .onSuccess { page -> rows = if (cursor == null) page.items else (rows + page.items).distinctBy { it.notificationId }; next = page.nextCursor; failure = null }
                .onFailure { failure = it.userMessage() }
            pending = false
            if (cursor != null) break
            delay(3_000)
        } while (isActive)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading("Tu bandeja", "Los avisos de tus canales, en un solo lugar.") }
        if (login == null) item { SignInNotice(navigate) } else {
            item { NotificationPreferences(api) }
            if (pending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            failure?.let { item { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } }
            items(rows, key = { it.notificationId }) { notice ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(notice.title, style = MaterialTheme.typography.titleMedium)
                    Text(notice.createdAtUtc, color = Muted)
                    val destination = if (notice.available) notificationDestination(notice.linkPath) else null
                    if (destination != null) TextButton(onClick = { navigate(destination) }) { Text("Abrir canal") }
                    else Text("Contenido no disponible", color = Muted)
                    if (notice.readAtUtc == null) TextButton(enabled = !pending, onClick = { scope.launch {
                        pending = true
                        recoverable { api.call(HttpMethod.Put, "/api/notifications/${id(notice.notificationId)}/read", authenticated = true, key = randomId()).body<P2Notification>() }
                            .onSuccess { updated -> rows = rows.map { if (it.notificationId == updated.notificationId) updated else it } }
                            .onFailure { failure = it.userMessage() }
                        pending = false
                    } }) { Text("Marcar como leído") }
                }
            }
            if (rows.isEmpty() && !pending && failure == null) item { Notice("No hay avisos todavía.") }
            next?.let { value -> item { Button(enabled = !pending, onClick = { cursor = value }) { Text("Más avisos") } } }
            item { TextButton(onClick = { cursor = null; retry++ }) { Text("Actualizar bandeja") } }
        }
    }
}

@Composable
fun VodCards(page: P2VodPage?, navigate: (String) -> Unit) {
    if (page == null) { Notice("No se pudo consultar la biblioteca."); return }
    if (page.items.isEmpty()) Notice("Todavía no hay grabaciones publicadas.")
    page.items.forEach { vod ->
        TextButton(onClick = { navigate("vod:${vod.vodId}") }) { Text(vod.title + " · ${vod.durationMs / 60_000} min") }
    }
}

internal fun libraryPath(title: String, category: String, tag: String, cursor: String?): String {
    val filters = listOf("title" to title, "categoryId" to category, "tagId" to tag, "cursor" to cursor.orEmpty())
    return "/api/screens/explore?limit=20" + filters.filter { it.second.isNotBlank() }
        .joinToString("") { (key, value) -> "&$key=${value.encodeURLParameter()}" }
}

@Composable
fun LibraryScreen(api: Api, foreground: Boolean, navigate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var cursor by remember(title, category, tag) { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<P2VodPage?>(null) }
    var taxonomy by remember { mutableStateOf<Taxonomy?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { recoverable { api.get<Taxonomy>("/api/taxonomy") }.onSuccess { taxonomy = it } }
    LaunchedEffect(title, category, tag, cursor, foreground, retry) {
        if (!foreground) return@LaunchedEffect
        pending = true; delay(250)
        recoverable {
            val screen = api.get<P2ExploreScreen>(libraryPath(title, category, tag, cursor), api.session.state.value != null)
            val data = screen.vods.sectionData<P2VodPage>() ?: error("VOD dependency unavailable")
            page = if (cursor == null) data else data.copy(items = (page?.items.orEmpty() + data.items).distinctBy { it.vodId })
            failure = null
        }.onFailure { failure = it.userMessage(); page = null }
        pending = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading("Grabaciones", "Mira tus canales a tu ritmo.") }
        item { OutlinedTextField(title, { title = it.take(120) }, label = { Text("Buscar videos") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            Text("Categoría")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(category.isEmpty(), { category = "" }, label = { Text("Todas") })
                taxonomy?.categories?.forEach { c -> FilterChip(category == c.id, { category = c.id }, label = { Text(c.name) }) }
            }
            Text("Etiqueta")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tag.isEmpty(), { tag = "" }, label = { Text("Todas") })
                taxonomy?.tags?.forEach { t -> FilterChip(tag == t.id, { tag = t.id }, label = { Text(t.name) }) }
            }
        }
        if (pending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        failure?.let { item { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } }
        item { VodCards(page, navigate) }
        page?.nextCursor?.let { value -> item { Button(enabled = !pending, onClick = { cursor = value }) { Text("Más videos") } } }
    }
}
