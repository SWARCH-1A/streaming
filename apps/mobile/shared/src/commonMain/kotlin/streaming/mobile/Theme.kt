package streaming.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import streaming.mobile.resources.Res
import streaming.mobile.resources.inter
import streaming.mobile.resources.jakarta

val Canvas = Color(0xFF0D0E12)
val Surface = Color(0xFF121317)
val Panel = Color(0xFF1F1F24)
val Raised = Color(0xFF292A2E)
val Ink = Color(0xFFE3E2E7)
val Muted = Color(0xFFB0ADBE)
val Primary = Color(0xFFC0C1FF)
val Live = Color(0xFFD10030)
val Outline = Color(0xFF464554)
val Positive = Color(0xFF4EDEA3)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    val body = FontFamily(Font(Res.font.inter))
    val display = FontFamily(Font(Res.font.jakarta))
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Primary, onPrimary = Color(0xFF1000A9),
            background = Canvas, surface = Surface, surfaceVariant = Panel,
            onBackground = Ink, onSurface = Ink, onSurfaceVariant = Muted,
            surfaceContainer = Panel, surfaceContainerHigh = Raised, secondaryContainer = Raised,
            onSecondaryContainer = Ink, outline = Outline, error = Color(0xFFFFB4AB)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-.6).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-.3).sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
            titleMedium = androidx.compose.ui.text.TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = body, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = body, fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontFamily = body, fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            titleSmall = androidx.compose.ui.text.TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = androidx.compose.ui.text.TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = .2.sp),
            labelMedium = androidx.compose.ui.text.TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        ),
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
fun Notice(text: String, error: Boolean = false) {
    Row(Modifier.fillMaxWidth().background(if (error) MaterialTheme.colorScheme.error.copy(alpha = .10f) else Panel,
        RoundedCornerShape(12.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VaultIcon(if (error) Glyph.Alert else Glyph.Info, tint = if (error) MaterialTheme.colorScheme.error else Primary)
        Text(text, color = if (error) MaterialTheme.colorScheme.error else Muted,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
