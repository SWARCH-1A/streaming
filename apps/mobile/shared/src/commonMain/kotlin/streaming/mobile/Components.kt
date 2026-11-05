@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
package streaming.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
fun NetworkImage(api: Api, path: String?, description: String?, modifier: Modifier, initials: String = "") {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path != null) recoverable {
            val resource = api.publicPath(path)
            require(resource.startsWith("/api/profile/avatars/") || resource.startsWith("/api/channels/banners/") || resource.startsWith("/hls/"))
            val response = api.client.get(api.url(resource))
            require(response.status.isSuccess())
            val bytes = response.body<ByteArray>()
            require(bytes.size <= 10 * 1024 * 1024)
            bitmap = bytes.decodeToImageBitmap()
        }
    }
    Box(modifier.background(Raised), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, description, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
            ?: Text(initials.take(2).uppercase(), color = Muted, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun SectionHeading(title: String, caption: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        caption?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun Availability(value: String) {
    Text(when (value) {
        "PLAYABLE" -> "EN VIVO"
        "RECONNECTING" -> "Reconectando"
        "OFFLINE" -> "Offline"
        else -> "Estado no actualizado"
    }, color = if (value == "PLAYABLE") androidx.compose.ui.graphics.Color(0xFFFFE1E0) else Ink,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(if (value == "PLAYABLE") Live else Raised, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
fun ChannelRow(api: Api, item: JsonObject, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).background(Panel).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val name = item.string("displayName") ?: item.string("handle").orEmpty()
        NetworkImage(api, item.string("avatarUri"), null, Modifier.size(44.dp).clip(CircleShape), name)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text("@${item.string("handle")}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Availability(item.string("availability") ?: "UNKNOWN")
    }
}

@Composable
fun StreamCard(api: Api, stream: JsonObject, watch: () -> Unit, channel: () -> Unit) {
    val owner = stream.getValue("channel").jsonObject
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).clickable(onClick = watch)) {
            NetworkImage(api, stream.string("previewUri"), "Vista previa de ${stream.string("title")}", Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { Availability(stream.string("availability") ?: "UNKNOWN") }
            Text(if (stream["viewerCountFresh"]?.jsonPrimitive?.booleanOrNull == true)
                "${stream.long("viewerCount") ?: 0} espectadores" else "Conteo no disponible", color = Ink,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Canvas.copy(alpha = .9f), RoundedCornerShape(4.dp)).padding(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetworkImage(api, owner.string("avatarUri"), null,
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = channel), owner.string("displayName").orEmpty())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stream.string("title").orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = watch))
                Text(owner.string("displayName").orEmpty(), color = Muted, modifier = Modifier.clickable(onClick = channel))
                Text(stream["category"]?.jsonObject?.string("name").orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
