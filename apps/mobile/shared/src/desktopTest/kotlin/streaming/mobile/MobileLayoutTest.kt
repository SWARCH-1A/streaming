@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package streaming.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.test.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import streaming.mobile.contracts.P2Vod

/** Real Compose measurement/rendering, with explicit fictional API fixtures and no device. */
class MobileLayoutTest {
    private fun api() = Api("https://fixture.test", Session(object : SecureStore {
        override fun read(): String? = null
        override fun write(value: String?) = Unit
    }), HttpClient(MockEngine { request ->
        when (request.url.encodedPath) {
            "/api/taxonomy" -> respond("""{"catalogVersion":1,"categories":[{"id":"games","name":"Videojuegos","active":true}],"tags":[{"id":"es","name":"Español","active":true}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            "/api/screens/explore" -> respond("""{"discovery":{"status":"AVAILABLE","data":{"streams":{"items":[],"nextCursor":null,"statusFresh":true},"channels":{"items":[],"nextCursor":null}}},"vods":{"status":"EMPTY","data":{"items":[],"nextCursor":null}},"meta":{"requestId":"fixture","partial":false}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            else -> respond("""{"code":"UNAVAILABLE"}""", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }) { configureVaultClient() })

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun render(name: String, width: Int, height: Int, fontScale: Float = 1f, content: @Composable () -> Unit): List<SemanticsNode> {
        val scene = ImageComposeScene(width, height, Density(1f, fontScale), coroutineContext = Dispatchers.Unconfined) { VaultTheme { androidx.compose.material3.Surface(color = Canvas, contentColor = Ink) { content() } } }
        try {
            repeat(30) { scene.render(it * 16_666_667L).close(); Thread.sleep(20) }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            assertTrue(nodes.isNotEmpty(), "$name must render accessible content")
            val output = File("build/reports/mobile-layout/$name.png")
            output.parentFile.mkdirs()
            scene.render(600_000_000).use { image -> image.encodeToData()!!.use { output.writeBytes(it.bytes) } }
            return nodes
        } finally { scene.close() }
    }

    @Test fun catalogCardsConstrainLongContentAtPhoneWidths() {
        val api = api()
        try {
            val stream = Json.parseToJsonElement("""{"streamId":"fixture-live","title":"Una transmisión con un título largo para comprobar el espacio disponible en móviles pequeños","availability":"PLAYABLE","viewerCount":1248,"viewerCountFresh":true,"channel":{"displayName":"Comunidad de prueba","handle":"fixture"},"category":{"name":"Videojuegos"},"tags":[{"name":"Español"},{"name":"Comunidad"}]}""").jsonObject
            val vod = P2Vod("fixture-session", "fixture-stream", "fixture-channel", 1,
                "Una grabación con título extenso que debe respetar el ancho de la tarjeta", "Descripción de prueba.",
                "games", emptyList(), "fixture-vod", "READY", "PUBLISHED", "NONE", 1, 1, 77271,
                emptyList(), "/vod-media/fixture-vod/master.m3u8", "2026-11-22T12:00:00Z")
            for (width in listOf(320, 390)) {
                val nodes = render("featured-$width", width, 844) {
                    Column(Modifier.padding(16.dp)) { StreamCard(api, stream, {}, {}, featured = true) }
                }
                assertTrue(nodes.any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == "Ver ahora" } })
                val video = render("video-$width", width, 844) { Column(Modifier.padding(16.dp)) { VodCard(api, vod, "Videojuegos") {} } }
                assertTrue(video.any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == "1:17" } })
                (nodes + video).filter { it.config.contains(SemanticsProperties.Text) }.forEach {
                    assertTrue(it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= width, "Card content must fit the viewport")
                }
            }
        } finally { api.close() }
    }

    @Test fun navigationRemainsVisibleAtNarrowWidthAndLargeText() {
        val api = api()
        try {
            render("navigation-390", 390, 844) { VaultApp(api) }
            val nodes = render("navigation-320-large-text", 320, 760, 1.8f) { VaultApp(api) }
            val labels = listOf("Explorar", "Buscar", "Videos", "Siguiendo", "Bandeja")
            val nav = labels.map { label ->
                nodes.lastOrNull { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains(label) }
                    ?: error("Missing destination $label")
            }
            nav.forEach { node -> assertTrue(node.boundsInRoot.bottom <= 760 && node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= 320, "Navigation must remain within the viewport") }
            nav.zipWithNext().forEach { (left, right) -> assertTrue(left.boundsInRoot.right <= right.boundsInRoot.left, "Destination labels must not overlap") }
        } finally { api.close() }
    }

    @Test fun everyAuthenticationFormMeasuresAtPhoneAndLandscapeSizes() {
        val api = api()
        try {
            for (mode in listOf("login", "register", "recovery", "reset")) {
                for ((width, height) in listOf(390 to 844, 740 to 360)) {
                    val nodes = render("$mode-$width", width, height) { VaultTheme { AuthScreen(api, mode, {}, {}) } }
                    assertTrue(nodes.any { it.config.contains(SemanticsProperties.EditableText) }, "$mode must expose its fields")
                }
            }
        } finally { api.close() }
    }

    @Test fun unavailablePlaybackAndSignedOutCommunityRemainNavigable() {
        val api = api()
        try {
            render("following", 390, 844) { VaultTheme { FollowingScreen(api, true, {}) } }
            render("inbox", 390, 844) { VaultTheme { InboxScreen(api, true, {}) } }
            render("library", 390, 844) { VaultTheme { LibraryScreen(api, true, {}) } }
            render("channel-error", 390, 844) { VaultTheme { ChannelScreen(api, "fixture", true, {}) } }
            val live = render("live-error", 390, 844) { VaultTheme { WatchScreen(api, "fixture", true, {}, {}) } }
            assertTrue(live.any { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.contains("Cerrar reproducción") })
            render("vod-error-landscape", 740, 360) { VaultTheme { VodScreen(api, "fixture", true, {}) } }
        } finally { api.close() }
    }
}
