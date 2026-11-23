package streaming.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    EmptyState("Tu comunidad te espera", "Inicia sesión para seguir canales y consultar sus avisos.", Glyph.User, "Iniciar sesión") { navigate("login") }
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
        VaultButton(modifier = Modifier.fillMaxWidth(), enabled = !pending && (login == null || follow != null), onClick = {
            if (login == null) navigate("login") else scope.launch {
                pending = true
                recoverable { api.call(if (follow?.following == true) HttpMethod.Delete else HttpMethod.Put,
                    path, authenticated = true, key = randomId()).body<P2Follow>() }
                    .onSuccess { follow = it; failure = null }.onFailure { failure = it.userMessage(); retry++ }
                pending = false
            }
        }) {
            if (pending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            else VaultIcon(if (follow?.following == true) Glyph.Check else Glyph.Heart, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(if (follow?.following == true) "Dejar de seguir" else "Seguir canal")
        }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle("Siguiendo", "Tus canales, siempre cerca.") { IconButton(onClick = { cursor = null; retry++ }) { VaultIcon(Glyph.Refresh, description = "Actualizar canales") } } }
        if (login == null) item { SignInNotice(navigate) }
        else {
            if (pending && rows.isEmpty()) item { LoadingCards() }
            failure?.let { item { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } }
            items(rows, key = { it.first.channelId }) { (_, channel) ->
                if (channel == null) Notice("Canal no disponible.")
                else ChannelListRow(api, channel.profile.displayName, channel.handle, channel.profile.avatarUri, channel.availability) { navigate("channel:${channel.handle}") }
            }
            if (!pending && rows.isEmpty() && failure == null) item { EmptyState("Tu lista empieza aquí", "Explora los directos y sigue los canales que quieras volver a ver.", Glyph.Heart, "Explorar canales") { navigate("explore") } }
            next?.let { value -> item { VaultSecondaryButton(modifier = Modifier.fillMaxWidth(), enabled = !pending, onClick = { cursor = value }) { Text("Más canales") } } }
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
    SurfacePanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VaultIcon(Glyph.Bell, tint = Primary)
            Text(if (channelId == null) "Preferencias de avisos" else "Avisos de este canal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (current == null && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (current != null) {
            PreferenceSwitch("Correo electrónico", "Recibe avisos cuando un canal salga en vivo.", current.emailEnabled, !pending) { change(it, current.pushEnabled) }
            HorizontalDivider(color = Outline.copy(alpha = .4f))
            PreferenceSwitch("Notificaciones push", "La entrega al teléfono todavía no está disponible.", current.pushEnabled, !pending) { change(current.emailEnabled, it) }
        }
        failure?.let { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar preferencias") } }
    }
}

@Composable
private fun PreferenceSwitch(title: String, caption: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(caption, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
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
    var showPreferences by remember { mutableStateOf(false) }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle("Bandeja", "Lo nuevo de tus canales.") {
            IconButton(onClick = { showPreferences = !showPreferences }) { VaultIcon(Glyph.Settings, description = if (showPreferences) "Ocultar preferencias" else "Configurar avisos") }
        } }
        if (login == null) item { SignInNotice(navigate) } else {
            if (showPreferences) item { NotificationPreferences(api) }
            if (pending && rows.isEmpty()) item { LoadingCards() }
            failure?.let { item { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } }
            items(rows, key = { it.notificationId }) { notice ->
                SurfacePanel {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(40.dp).background(if (notice.readAtUtc == null) Primary.copy(alpha = .12f) else Raised, CircleShape), contentAlignment = Alignment.Center) {
                            VaultIcon(Glyph.Bell, tint = if (notice.readAtUtc == null) Primary else Muted)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(notice.title, style = MaterialTheme.typography.titleMedium)
                            Text(notice.createdAtUtc.replace("T", " · ").removeSuffix("Z").substringBefore("."), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (notice.readAtUtc == null) Box(Modifier.padding(top = 6.dp).size(7.dp).background(Primary, CircleShape))
                    }
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
            if (rows.isEmpty() && !pending && failure == null) item { EmptyState("Estás al día", "Cuando un canal que sigues salga en vivo, su aviso aparecerá aquí.", Glyph.Inbox) }
            next?.let { value -> item { VaultSecondaryButton(modifier = Modifier.fillMaxWidth(), enabled = !pending, onClick = { cursor = value }) { Text("Más avisos") } } }
            item { TextButton(onClick = { cursor = null; retry++ }) { Text("Actualizar bandeja") } }
        }
    }
}

@Composable
fun VodCards(api: Api, page: P2VodPage?, navigate: (String) -> Unit) {
    if (page == null) { Notice("No se pudo consultar la biblioteca."); return }
    if (page.items.isEmpty()) EmptyState("Sin grabaciones todavía", "Los videos publicados de este canal aparecerán aquí.", Glyph.Video)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        page.items.forEach { vod -> VodCard(api, vod, navigate = navigate) }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenTitle("Videos", "Los directos terminan. Las historias siguen.") }
        item { SearchField(title, { title = it.take(120) }, "Buscar grabaciones") }
        item {
            CatalogFilters(taxonomy, category, tag, { category = it.orEmpty() }, { tag = it.orEmpty() })
        }
        if (pending && page == null) item { LoadingCards() }
        failure?.let { item { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } }
        if (page != null && page!!.items.isEmpty() && !pending) item { EmptyState("No hay videos aquí", "Prueba otra búsqueda o vuelve cuando haya nuevas grabaciones.", Glyph.Video,
            if (title.isNotEmpty() || category.isNotEmpty() || tag.isNotEmpty()) "Limpiar filtros" else null) { title = ""; category = ""; tag = "" } }
        items(page?.items.orEmpty(), key = { it.vodId }) { vod ->
            VodCard(api, vod, taxonomy?.categories?.firstOrNull { it.id == vod.categoryId }?.name, navigate)
        }
        page?.nextCursor?.let { value -> item { VaultSecondaryButton(modifier = Modifier.fillMaxWidth(), enabled = !pending, onClick = { cursor = value }) { Text("Más videos") } } }
    }
}
