package streaming.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared 24-unit outline icons; labels belong to their interactive parent. */
enum class Glyph { Explore, Search, Heart, Inbox, Video, User, Back, Arrow, Close, Check, Eye, Lock, Mail, Chat, Send, Settings, Refresh, Alert, Info, Play, Down, Logout, Bell }

@Composable
fun VaultIcon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, description: String? = null) {
    Canvas(modifier.size(22.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x,y), Offset(x2,y2), 1.8f, StrokeCap.Round)
            fun path(vararg points: Pair<Float,Float>, close: Boolean = false) {
                val p = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first,it.second) }; if (close) close() }
                drawPath(p,tint,style=stroke)
            }
            when(glyph) {
                Glyph.Search -> { drawCircle(tint,7f,Offset(10f,10f),style=stroke); line(15f,15f,21f,21f) }
                Glyph.Explore -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); path(15.5f to 8.5f,13.5f to 13.5f,8.5f to 15.5f,10.5f to 10.5f,close=true) }
                Glyph.Heart -> drawPath(Path().apply { moveTo(12f,21f); cubicTo(-5f,10f,4f,-3f,12f,6f); cubicTo(20f,-3f,29f,10f,12f,21f); close() },tint,style=stroke)
                Glyph.Video -> { drawRoundRect(tint,Offset(2f,3f),Size(20f,15f),CornerRadius(2f),style=stroke); path(10f to 7f,16f to 10.5f,10f to 14f,close=true); line(8f,22f,16f,22f); line(12f,18f,12f,22f) }
                Glyph.Inbox -> { path(3f to 4f,21f to 4f,21f to 20f,3f to 20f,close=true); path(3f to 13f,8f to 13f,10f to 16f,14f to 16f,16f to 13f,21f to 13f) }
                Glyph.User -> { drawCircle(tint,3.5f,Offset(12f,7f),style=stroke); drawPath(Path().apply { moveTo(4f,21f); lineTo(4f,18f); cubicTo(4f,11f,20f,11f,20f,18f); lineTo(20f,21f); close() },tint,style=stroke) }
                Glyph.Back -> path(15f to 4f,7f to 12f,15f to 20f)
                Glyph.Arrow, Glyph.Logout -> { line(3f,12f,21f,12f); path(15f to 6f,21f to 12f,15f to 18f) }
                Glyph.Close -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                Glyph.Check -> path(4f to 12f,9f to 17f,20f to 6f)
                Glyph.Down -> path(6f to 9f,12f to 15f,18f to 9f)
                Glyph.Eye -> { drawPath(Path().apply { moveTo(1f,12f); quadraticTo(12f,-2f,23f,12f); quadraticTo(12f,26f,1f,12f); close() },tint,style=stroke); drawCircle(tint,3f,Offset(12f,12f),style=stroke) }
                Glyph.Lock -> { drawRoundRect(tint,Offset(4f,10f),Size(16f,12f),CornerRadius(2f),style=stroke); drawPath(Path().apply { moveTo(8f,10f); lineTo(8f,6f); cubicTo(8f,0f,16f,0f,16f,6f); lineTo(16f,10f) },tint,style=stroke); line(12f,15f,12f,18f) }
                Glyph.Mail -> { drawRoundRect(tint,Offset(2f,4f),Size(20f,16f),CornerRadius(2f),style=stroke); path(2f to 5f,12f to 13f,22f to 5f) }
                Glyph.Chat -> path(3f to 3f,21f to 3f,21f to 17f,9f to 17f,3f to 22f,close=true)
                Glyph.Send -> { path(3f to 3f,22f to 12f,3f to 21f,6f to 12f,close=true); line(6f,12f,22f,12f) }
                Glyph.Play -> path(7f to 3f,21f to 12f,7f to 21f,close=true)
                Glyph.Refresh -> { drawArc(tint,45f,290f,false,Offset(3f,3f),Size(18f,18f),style=stroke); path(22f to 3f,22f to 9f,16f to 9f) }
                Glyph.Settings -> { drawCircle(tint,8f,Offset(12f,12f),style=stroke); drawCircle(tint,3f,Offset(12f,12f),style=stroke); line(12f,1f,12f,4f); line(12f,20f,12f,23f); line(1f,12f,4f,12f); line(20f,12f,23f,12f) }
                Glyph.Bell -> { path(4f to 18f,6f to 15f,6f to 9f); drawArc(tint,180f,180f,false,Offset(6f,3f),Size(12f,12f),style=stroke); path(18f to 9f,18f to 15f,20f to 18f,4f to 18f); line(10f,22f,14f,22f) }
                Glyph.Alert, Glyph.Info -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); line(12f,7f,12f,13f); drawCircle(tint,1f,Offset(12f,17f)) }
            }
        }
    }
}

@Composable
fun VaultButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled, shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), content = content)
}

@Composable
fun VaultSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp), content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled, shape = shape,
        border = BorderStroke(1.dp, Outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink), content = content)
}

@Composable
fun VaultFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    FilterChip(selected, onClick, label = label, shape = CircleShape, border = null,
        colors = FilterChipDefaults.filterChipColors(containerColor = Panel, labelColor = Muted,
            selectedContainerColor = Primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary),
        modifier = Modifier.heightIn(min = 40.dp))
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { VaultIcon(Glyph.Search, tint = Muted) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onValueChange("") }) { VaultIcon(Glyph.Close, description = "Borrar búsqueda") } }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), shape = RoundedCornerShape(12.dp),
        colors = vaultFieldColors(), textStyle = MaterialTheme.typography.bodyMedium)
}

@Composable
fun vaultFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Surface, unfocusedContainerColor = Surface, disabledContainerColor = Surface,
    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Primary,
    unfocusedLabelColor = Muted, focusedLabelColor = Primary, cursorColor = Primary)

@Composable
fun SurfacePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
fun EmptyState(title: String, message: String, glyph: Glyph = Glyph.Search, action: String? = null, onAction: () -> Unit = {}) {
    SurfacePanel {
        Box(Modifier.size(44.dp).background(Raised, CircleShape), contentAlignment = Alignment.Center) { VaultIcon(glyph, tint = Primary) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (action != null) VaultButton(onAction) { Text(action) }
    }
}

@Composable
fun LoadingCards() {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Cargando contenido" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(Panel))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Panel, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(.85f).height(16.dp).background(Panel, RoundedCornerShape(4.dp)))
                Box(Modifier.fillMaxWidth(.5f).height(12.dp).background(Panel, RoundedCornerShape(4.dp)))
            }
        }
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Primary, trackColor = Panel)
    }
}

@Composable
fun MetadataChip(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(Raised, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun ScreenTitle(title: String, caption: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            caption?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) }
        }
        action?.invoke()
    }
}

@Composable
fun VaultTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    enabled: Boolean = true, singleLine: Boolean = false, isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
) {
    OutlinedTextField(value, onValueChange, modifier, enabled = enabled, label = label, placeholder = placeholder,
        singleLine = singleLine, isError = isError, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation, trailingIcon = trailingIcon, supportingText = supportingText,
        textStyle = textStyle, shape = shape, colors = vaultFieldColors())
}

@Composable
fun CatalogFilters(taxonomy: streaming.mobile.contracts.Taxonomy?, category: String?, tag: String?,
                   onCategory: (String?) -> Unit, onTag: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultFilterChip(category.isNullOrEmpty(), { onCategory(null) }, label = { Text("Todas") })
            taxonomy?.categories?.forEach { item -> VaultFilterChip(category == item.id, { onCategory(item.id) }, label = { Text(item.name) }) }
        }
        if (!taxonomy?.tags.isNullOrEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultFilterChip(tag.isNullOrEmpty(), { onTag(null) }, label = { Text("Sin etiqueta") })
            taxonomy.tags.forEach { item -> VaultFilterChip(tag == item.id, { onTag(item.id) }, label = { Text("#${item.name}") }) }
        }
    }
}
