@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
package streaming.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import org.jetbrains.compose.resources.decodeToImageBitmap
import streaming.mobile.contracts.P2Vod

fun videoDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    else "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
fun VodCard(api: Api, vod: P2Vod, category: String? = null, navigate: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClickLabel = "Reproducir ${vod.title}") { navigate("vod:${vod.vodId}") },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VodThumbnail(api, vod)
        Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vod.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Grabación", color = Muted, style = MaterialTheme.typography.bodySmall)
                category?.let { Text("·", color = Muted); Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
            if (vod.description.isNotBlank()) Text(vod.description, color = Muted,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VodThumbnail(api: Api, vod: P2Vod) {
    var image by remember(vod.vodId, vod.mappingVersion, vod.version) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(vod.vodId, vod.mappingVersion, vod.version) { mutableStateOf(false) }
    LaunchedEffect(vod.vodId, vod.mappingVersion, vod.version) {
        recoverable {
            val response = api.call(HttpMethod.Get, "/api/vods/${id(vod.vodId)}/thumbnail",
                authenticated = api.session.state.value != null)
            val bytes = response.body<ByteArray>()
            require(bytes.size <= 512 * 1024)
            bytes.decodeToImageBitmap()
        }.onSuccess { image = it }.onFailure { failed = true }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(Panel), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else {
            androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
                val triangle = Path().apply {
                    moveTo(size.width * .3f, size.height * .16f); lineTo(size.width * .82f, size.height * .5f)
                    lineTo(size.width * .3f, size.height * .84f); close()
                }
                drawPath(triangle, Muted)
            }
            if (failed) Text("Vista previa no disponible", color = Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
            else LinearProgressIndicator(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp), color = Primary, trackColor = Raised)
        }
        Text(videoDuration(vod.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(Color.Black.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}
