package streaming.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class VideoQuality(val id: String, val label: String)

/** The platform owns available tracks; the screen owns the selection and its toolbar. */
@Stable
class PlaybackControls {
    var qualities by mutableStateOf(emptyList<VideoQuality>())
        internal set
    var selectedId by mutableStateOf<String?>(null)
    var unavailable by mutableStateOf(false)
        internal set
    var retry by mutableIntStateOf(0)
}

internal fun shouldDismissVideo(distance: Float, threshold: Float): Boolean = distance >= threshold

/** Android video surface and the shared drag handle; iOS video uses a native recognizer. */
internal fun Modifier.dismissVideoOnSwipe(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) {
    var distance = 0f
    detectVerticalDragGestures(
        onDragStart = { distance = 0f },
        onVerticalDrag = { _, amount -> distance = (distance + amount).coerceAtLeast(0f) },
        onDragCancel = { distance = 0f },
        onDragEnd = { if (shouldDismissVideo(distance, 72.dp.toPx())) onDismiss(); distance = 0f },
    )
}

@Composable
fun PlaybackToolbar(controls: PlaybackControls, onDismiss: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Surface).heightIn(min = 44.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onDismiss) { VaultIcon(Glyph.Back, description = "Cerrar reproducción") }
        Box(Modifier.weight(1f).heightIn(min = 44.dp).dismissVideoOnSwipe(onDismiss).semantics {
            customActions = listOf(CustomAccessibilityAction("Cerrar reproducción") { onDismiss(); true })
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(32.dp).height(3.dp).background(Muted.copy(alpha = .5f), RoundedCornerShape(2.dp)))
        }
        Box {
            val label = controls.qualities.firstOrNull { it.id == controls.selectedId }?.label ?: "Automática"
            TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp)); VaultIcon(Glyph.Down, Modifier.size(16.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Automática") }, leadingIcon = { if (controls.selectedId == null) VaultIcon(Glyph.Check, tint = Primary) }, onClick = { controls.selectedId = null; menu = false })
                controls.qualities.forEach { quality ->
                    DropdownMenuItem(text = { Text(quality.label) }, leadingIcon = { if (controls.selectedId == quality.id) VaultIcon(Glyph.Check, tint = Primary) }, onClick = { controls.selectedId = quality.id; menu = false })
                }
                if (controls.unavailable) DropdownMenuItem(text = { Text("Reintentar cargar calidades") }, onClick = { controls.retry++; menu = false })
            }
        }
    }
}
