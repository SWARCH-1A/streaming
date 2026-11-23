package streaming.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import streaming.mobile.contracts.Taxonomy
import streaming.mobile.contracts.P2ExploreScreen
import io.ktor.http.encodeURLParameter
import kotlin.time.TimeSource

@Composable
fun ExploreScreen(api: Api, search: Boolean, foreground: Boolean, navigate: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var cursor by rememberSaveable(query, category, tag) { mutableStateOf<String?>(null) }
    var channelCursor by rememberSaveable(query) { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var channels by remember { mutableStateOf<JsonObject?>(null) }
    var taxonomy by remember { mutableStateOf<Taxonomy?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        recoverable { api.get<Taxonomy>("/api/taxonomy") }.onSuccess { taxonomy = it; catalogError = null }
            .onFailure { catalogError = it.userMessage() }
    }
    LaunchedEffect(query, category, tag, cursor, channelCursor, search, foreground, retry) {
        if (!foreground) return@LaunchedEffect
        result = null; channels = null; failure = null; channelError = null
        delay(250)
        while (isActive) {
            val started = TimeSource.Monotonic.markNow()
            recoverable {
                val path = libraryPath(query, category.orEmpty(), tag.orEmpty(), null) +
                    (cursor?.let { "&liveCursor=${it.encodeURLParameter()}" } ?: "") +
                    (channelCursor?.let { "&channelCursor=${it.encodeURLParameter()}" } ?: "") +
                    (if (search) "&searchChannels=true" else "")
                val screen = api.get<P2ExploreScreen>(path, api.session.state.value != null)
                val section = screen.discovery
                require(section.string("status") in listOf("AVAILABLE", "EMPTY")) { "Discovery no está disponible. Reintenta la consulta." }
                val data = section["data"]?.jsonObject ?: error("Discovery no devolvió datos.")
                result = data["streams"]?.jsonObject ?: error("No se recibieron las transmisiones.")
                channels = if (search) data["channels"]?.jsonObject ?: error("No se recibieron los canales.") else null
                failure = null; channelError = null
            }.onFailure { result = null; channels = null; failure = it.userMessage() }
            delay((2000 - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenTitle(if (search) "Buscar" else "Descubre tu próximo directo", if (search) "Canales y transmisiones en un solo lugar." else "En vivo. En comunidad.") }
        item { SearchField(query, { query = it.take(120) }, "Buscar canales o transmisiones") }
        item {
            CatalogFilters(taxonomy, category, tag, { category = it }, { tag = it })
            catalogError?.let { Notice("No se pudieron cargar los filtros. $it", true) }
        }
        failure?.let { item { EmptyState("No pudimos cargar el catálogo", it, Glyph.Alert, "Reintentar") { retry++ } } }
        if (result == null && failure == null) item { LoadingCards() }
        channelError?.let { item { Notice("Canales: $it", true) } }
        if (search) channels?.let { page ->
            item { SectionHeading("Canales", if (query.isBlank()) "Encuentra una comunidad para seguir." else "Resultados para «$query»") }
            items(page.items(), key = { "channel:${it.string("channelId")}" }) { item ->
                ChannelRow(api, item) { navigate("channel:${item.string("handle")}") }
            }
            if (page.items().isEmpty()) item { EmptyState("Sin canales encontrados", "Prueba con otro nombre o usuario.") }
            page.string("nextCursor")?.let { next -> item { VaultSecondaryButton(onClick = { channelCursor = next }, modifier = Modifier.fillMaxWidth()) { Text("Más canales") } } }
        }
        result?.let { page ->
            val streams = page.items()
            if (page["statusFresh"]?.jsonPrimitive?.booleanOrNull == false) item { Notice("El estado de las emisiones no está actualizado.") }
            if (!search && streams.isNotEmpty()) {
                item { SectionHeading("Destacado ahora") }
                item(key = "featured:${streams.first().string("streamId")}") {
                    val stream = streams.first()
                    StreamCard(api, stream, { navigate("watch:${stream.string("streamId")}") },
                        { navigate("channel:${stream.getValue("channel").jsonObject.string("handle")}") }, featured = true)
                }
            }
            if (search || streams.size != 1) item { SectionHeading("Canales en vivo", "Por popularidad") }
            items(if (search) streams else streams.drop(1), key = { it.string("streamId").orEmpty() }) { item ->
                StreamCard(api, item, { navigate("watch:${item.string("streamId")}") },
                    { navigate("channel:${item.getValue("channel").jsonObject.string("handle")}") })
            }
            if (streams.isEmpty()) item {
                EmptyState("No hay transmisiones aquí", "Prueba otra búsqueda o elimina los filtros.", Glyph.Video,
                    if (query.isNotEmpty() || category != null || tag != null) "Limpiar filtros" else null) { query = ""; category = null; tag = null }
            }
            page.string("nextCursor")?.let { next -> item { VaultSecondaryButton(onClick = { cursor = next }, modifier = Modifier.fillMaxWidth()) { Text("Más transmisiones") } } }
        }
    }
}
