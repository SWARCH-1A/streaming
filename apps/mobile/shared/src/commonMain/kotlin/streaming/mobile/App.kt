package streaming.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.jetbrains.compose.resources.painterResource
import streaming.mobile.resources.Res
import streaming.mobile.resources.vault_tv

@Composable expect fun Foreground(): Boolean
@Composable expect fun NativeBack(enabled: Boolean, onBack: () -> Unit)

@Composable
fun VaultApp(api: Api) {
    val identity by api.session.state.collectAsState()
    val foreground = Foreground()
    val largeText = LocalDensity.current.fontScale > 1.3f
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
    val watching = route.startsWith("watch:") || route.startsWith("vod:")
    val navigate: (String) -> Unit = { destination ->
        if (destination != route) stack = when {
            destination == "explore" -> listOf(destination)
            route in listOf("login", "register", "recovery", "reset") && destination in listOf("login", "register", "recovery", "reset") -> stack.dropLast(1) + destination
            else -> stack + destination
        }
    }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    NativeBack(stack.size > 1, back)
    VaultTheme {
        Scaffold(containerColor = Canvas, topBar = {
            if (!watching) {
                Row(Modifier.fillMaxWidth().background(Canvas).statusBarsPadding().heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(Res.drawable.vault_tv), "Vault tv", Modifier.height(40.dp).width(118.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { stack = listOf("search") }) { VaultIcon(Glyph.Search, description = "Buscar") }
                    FilledIconButton(onClick = { navigate(if (identity == null) "login" else "account") },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        VaultIcon(Glyph.User, description = if (identity == null) "Iniciar sesión" else "Mi cuenta")
                    }
                }
            }
        }, bottomBar = {
            if (!watching) {
                Column(Modifier.background(Canvas).navigationBarsPadding()) {
                    HorizontalDivider(color = Outline.copy(alpha = .4f))
                    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 4.dp)) {
                        listOf("explore" to "Explorar", "search" to "Buscar", "library" to "Videos",
                            "following" to "Siguiendo", "inbox" to "Bandeja").forEach { (key, label) ->
                            val selected = route == key || (route !in listOf("explore", "search", "library", "following", "inbox") && stack.first() == key)
                            Column(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .semantics { contentDescription = label }
                                .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = { stack = listOf(key) })
                                .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.width(42.dp).height(27.dp).background(if (selected) Primary.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent,
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    VaultIcon(when(key) { "search" -> Glyph.Search; "library" -> Glyph.Video; "following" -> Glyph.Heart; "inbox" -> Glyph.Inbox; else -> Glyph.Explore },
                                        tint = if (selected) Primary else Muted)
                                }
                                if (!largeText) Text(label, color = if (selected) Primary else Muted, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                Box(if (watching) Modifier.fillMaxSize() else Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    key(identity?.userId, api.session.generation) { when {
                        route == "explore" || route == "search" -> ExploreScreen(api, route == "search", foreground, navigate)
                        route.startsWith("channel:") -> ChannelScreen(api, route.substringAfter(':'), foreground, navigate)
                        route.startsWith("watch:") -> WatchScreen(api, route.substringAfter(':'), foreground, navigate, back)
                        route in listOf("login", "register", "recovery", "reset") -> AuthScreen(api, route, navigate) {
                            stack = stack.dropLast(1).ifEmpty { listOf("explore") }
                        }
                        route == "account" -> AccountScreen(api, navigate)
                        route == "following" -> FollowingScreen(api, foreground, navigate)
                        route == "inbox" -> InboxScreen(api, foreground, navigate)
                        route == "library" -> LibraryScreen(api, foreground, navigate)
                        route.startsWith("vod:") -> VodScreen(api, route.substringAfter(':'), foreground, back)
                        else -> Notice("Este contenido no está disponible.")
                    } }
                }
            }
        }
    }
}
