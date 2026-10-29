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
    LaunchedEffect(query, category, tag, cursor, channelCursor, foreground, retry) {
        if (!foreground) return@LaunchedEffect
        result = null; channels = null; failure = null; channelError = null
        delay(250)
        while (isActive) {
            coroutineScope {
                launch {
                    recoverable { api.discovery(query, category, tag, cursor) }.onSuccess { result = it; failure = null }
                        .onFailure { failure = it.userMessage() }
                }
                if (search) launch {
                    recoverable { api.channels(query, channelCursor) }.onSuccess { channels = it; channelError = null }
                        .onFailure { channelError = it.userMessage() }
                }
            }
            delay(3_000)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { SectionHeading(if (search) "Encuentra tu comunidad" else "Explorar transmisiones", "En vivo, a tu ritmo.") }
        item {
            OutlinedTextField(query, { query = it }, label = { Text("Buscar canales o transmisiones") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Categoría", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == null, { category = null }, label = { Text("Todas") })
                    taxonomy?.categories?.forEach { item -> FilterChip(category == item.id, { category = item.id }, label = { Text(item.name) }) }
                }
                Text("Etiqueta", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tag == null, { tag = null }, label = { Text("Todas") })
                    taxonomy?.tags?.forEach { item -> FilterChip(tag == item.id, { tag = item.id }, label = { Text(item.name) }) }
                }
                catalogError?.let { Notice("Catálogo: $it", true) }
            }
        }
        failure?.let { item { Column { Notice(it, true); TextButton(onClick = { retry++ }) { Text("Reintentar") } } } }
        if (result == null && failure == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        channelError?.let { item { Notice("Canales: $it", true) } }
        if (search) channels?.let { page ->
            item { SectionHeading("Canales encontrados") }
            items(page.items(), key = { "channel:${it.string("channelId")}" }) { item ->
                ChannelRow(api, item) { navigate("channel:${item.string("handle")}") }
            }
            if (page.items().isEmpty()) item { Notice("No encontramos canales. Prueba otro nombre.") }
            page.string("nextCursor")?.let { next -> item { OutlinedButton(onClick = { channelCursor = next }) { Text("Más canales") } } }
        }
        result?.let { page ->
            item { SectionHeading("Transmisiones en vivo", "Más populares") }
            if (page["statusFresh"]?.jsonPrimitive?.booleanOrNull == false) item { Notice("El estado de las emisiones no está actualizado.") }
            items(page.items(), key = { it.string("streamId").orEmpty() }) { item ->
                StreamCard(api, item, { navigate("watch:${item.string("streamId")}") },
                    { navigate("channel:${item.getValue("channel").jsonObject.string("handle")}") })
            }
            if (page.items().isEmpty()) item { Notice("No encontramos transmisiones. Prueba otra búsqueda o elimina los filtros.") }
            page.string("nextCursor")?.let { next -> item { OutlinedButton(onClick = { cursor = next }) { Text("Más transmisiones") } } }
        }
    }
}
