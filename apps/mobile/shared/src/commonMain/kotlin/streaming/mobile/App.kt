package streaming.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import streaming.mobile.resources.Res
import streaming.mobile.resources.vault_tv

@Composable expect fun Foreground(): Boolean
@Composable expect fun NativeBack(enabled: Boolean, onBack: () -> Unit)

@Composable
fun VaultApp(api: Api) {
    val identity by api.session.state.collectAsState()
    val foreground = Foreground()
    var stack by rememberSaveable { mutableStateOf(listOf("explore")) }
    LaunchedEffect(Unit) {
        DestinationLinks.pending.collect { destination ->
            if (destination != null) {
                stack = listOf("explore", destination)
                DestinationLinks.consumed(destination)
            }
        }
    }
    val route = stack.last()
    val watching = route.startsWith("watch:")
    val navigate: (String) -> Unit = { if (it != route) stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    NativeBack(stack.size > 1, back)
    VaultTheme {
        Scaffold(containerColor = Canvas, topBar = {
            if (!watching) {
                Row(Modifier.fillMaxWidth().background(Panel).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (stack.size > 1) TextButton(onClick = back) { Text("Volver") }
                    Image(painterResource(Res.drawable.vault_tv), "Vault tv", Modifier.height(40.dp).width(118.dp))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { navigate(if (identity == null) "login" else "account") }) {
                        Text(if (identity == null) "Iniciar sesión" else "Mi cuenta")
                    }
                }
            }
        }, bottomBar = {
            if (!watching) {
                NavigationBar(containerColor = Panel) {
                    listOf("explore" to "Explorar", "search" to "Buscar", "following" to "Siguiendo", "inbox" to "Bandeja").forEach { (key, label) ->
                        NavigationBarItem(selected = route == key,
                            onClick = { stack = listOf(key) },
                            icon = { NavigationGlyph(key) }, label = { Text(label) })
                    }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                when {
                    route == "explore" || route == "search" -> ExploreScreen(api, route == "search", foreground, navigate)
                    route.startsWith("channel:") -> ChannelScreen(api, route.substringAfter(':'), foreground, navigate)
                    route.startsWith("watch:") -> WatchScreen(api, route.substringAfter(':'), foreground, navigate, back)
                    route in listOf("login", "register", "recovery", "reset") -> AuthScreen(api, route, navigate) {
                        stack = stack.dropLast(1).ifEmpty { listOf("explore") }
                    }
                    route == "account" -> AccountScreen(api, navigate)
                    route == "following" -> DependencyScreen("Canales seguidos", "Aquí podrás volver a tus canales favoritos.", identity != null, navigate,
                        "El seguimiento aún no está disponible en el servidor. Puedes descubrir canales desde Explorar.")
                    route == "inbox" -> DependencyScreen("Tu bandeja", "Los avisos de tus canales, en un solo lugar.", identity != null, navigate,
                        "La bandeja aún no está disponible en el servidor. Podrás consultarla aunque no actives notificaciones push.")
                    else -> Notice("Este contenido no está disponible.")
                }
            }
        }
    }
}

@Composable
private fun NavigationGlyph(kind: String) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.7.dp.toPx())
        val color = Muted
        when (kind) {
            "search" -> {
                drawCircle(color, size.width * .3f, center = androidx.compose.ui.geometry.Offset(size.width * .4f, size.height * .4f), style = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .63f, size.height * .63f), androidx.compose.ui.geometry.Offset(size.width * .95f, size.height * .95f), strokeWidth = stroke.width)
            }
            "following" -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * .5f, size.height * .9f)
                    cubicTo(-size.width * .2f, size.height * .4f, size.width * .1f, -size.height * .15f, size.width * .5f, size.height * .25f)
                    cubicTo(size.width * .9f, -size.height * .15f, size.width * 1.2f, size.height * .4f, size.width * .5f, size.height * .9f)
                }; drawPath(path, color, style = stroke)
            }
            "inbox" -> {
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 6.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(3.dp.toPx(), size.height * .65f), androidx.compose.ui.geometry.Offset(size.width - 3.dp.toPx(), size.height * .65f), strokeWidth = stroke.width)
            }
            else -> {
                drawCircle(color, size.width * .44f, style = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .35f, size.height * .67f), androidx.compose.ui.geometry.Offset(size.width * .65f, size.height * .33f), strokeWidth = stroke.width * 2)
            }
        }
    }
}

@Composable
private fun DependencyScreen(title: String, caption: String, signedIn: Boolean, navigate: (String) -> Unit, message: String) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeading(title, caption)
        Notice(if (signedIn) message else "Inicia sesión para consultar esta sección.")
        Button(onClick = { navigate(if (signedIn) "explore" else "login") }) { Text(if (signedIn) "Explorar canales" else "Iniciar sesión") }
    }
}
