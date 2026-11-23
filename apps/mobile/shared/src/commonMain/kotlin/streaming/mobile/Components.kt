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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
        bitmap = null
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
            ?: if (initials.isNotBlank()) Text(initials.take(2).uppercase(), color = Primary, style = MaterialTheme.typography.titleLarge)
            else VaultIcon(Glyph.Video, Modifier.size(36.dp), tint = Muted.copy(alpha = .5f))
    }
}

@Composable
fun SectionHeading(title: String, caption: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        caption?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun Availability(value: String) {
    val active = value == "PLAYABLE"
    Row(Modifier.background(if (active) Live else Raised, CircleShape).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (active) Box(Modifier.size(6.dp).background(androidx.compose.ui.graphics.Color.White, CircleShape))
        Text(when (value) { "PLAYABLE" -> "EN VIVO"; "RECONNECTING" -> "Reconectando"; "OFFLINE" -> "Sin conexión"; else -> "Sin actualizar" },
            color = if (active) androidx.compose.ui.graphics.Color.White else Muted,
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun ChannelRow(api: Api, item: JsonObject, onClick: () -> Unit) {
    val name = item.string("displayName") ?: item.string("handle").orEmpty()
    ChannelListRow(api, name, item.string("handle").orEmpty(), item.string("avatarUri"), item.string("availability") ?: "UNKNOWN", onClick)
}

@Composable
fun ChannelListRow(api: Api, name: String, handle: String, avatar: String?, availability: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).background(Panel).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NetworkImage(api, avatar, null, Modifier.size(48.dp).clip(CircleShape), name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@$handle", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Availability(availability)
        }
        VaultIcon(Glyph.Arrow, tint = Muted)
    }
}

@Composable
fun StreamCard(api: Api, stream: JsonObject, watch: () -> Unit, channel: () -> Unit, featured: Boolean = false) {
    val owner = stream.getValue("channel").jsonObject
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clickable(role = Role.Button, onClickLabel = "Ver transmisión", onClick = watch)) {
            NetworkImage(api, stream.string("previewUri"), "Vista previa de ${stream.string("title")}", Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.TopStart).padding(10.dp)) { Availability(stream.string("availability") ?: "UNKNOWN") }
            Row(Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Canvas.copy(alpha = .9f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                VaultIcon(Glyph.Eye, Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color(0xFFFFB3B2))
                Text(if (stream["viewerCountFresh"]?.jsonPrimitive?.booleanOrNull == true) "${stream.long("viewerCount") ?: 0}" else "Sin conteo",
                    color = Ink, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NetworkImage(api, owner.string("avatarUri"), "Canal ${owner.string("displayName")}",
                    Modifier.size(if (featured) 44.dp else 36.dp).clip(CircleShape).clickable(onClick = channel), owner.string("displayName").orEmpty())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stream.string("title").orEmpty(), style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = watch))
                    Text(owner.string("displayName").orEmpty(), color = if (featured) Primary else Muted,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = channel))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stream["category"]?.jsonObject?.string("name")?.takeIf { it.isNotBlank() }?.let { MetadataChip(it) }
                (stream["tags"] as? kotlinx.serialization.json.JsonArray)?.take(3)?.forEach { tag ->
                    (tag as? JsonObject)?.string("name")?.let { MetadataChip(it) }
                }
            }
            if (featured) VaultButton(onClick = watch, modifier = Modifier.fillMaxWidth()) {
                VaultIcon(Glyph.Play, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Ver ahora")
            }
        }
    }
}
