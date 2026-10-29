package streaming.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.util.date.GMTDate
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTest {
    @Test fun readyTimeoutReconnectsAndLoadsHistory() = runTest {
        val sockets = mutableListOf<TestSocket>()
        val failures = mutableListOf<String>()
        var histories = 0
        val engine = object : MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.encodedPath.startsWith("/realtime/")) {
                    val context = currentCoroutineContext()
                    val socket = TestSocket(context)
                    sockets += socket
                    if (sockets.size == 2) socket.incoming.trySend(Frame.Text(
                        """{"type":"chat.ready","sessionId":"ses_fixture","roomStatus":"OPEN","lastSequence":0}"""))
                    HttpResponseData(HttpStatusCode.SwitchingProtocols, GMTDate(), Headers.Empty,
                        HttpProtocolVersion.HTTP_1_1, socket, context)
                } else {
                    assertEquals("/api/chat/sessions/ses_fixture/messages", request.url.encodedPath)
                    histories++
                    respond("""{"sessionId":"ses_fixture","items":[],"snapshotSequence":0,"roomStatus":"OPEN","moderationVersion":0}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }) {
            override val supportedCapabilities = super.supportedCapabilities + WebSocketCapability
        }
        val api = Api("https://fixture.example.test", Session(MemorySecureStore()),
            HttpClient(engine) { configureVaultClient() }, { stage, _ -> failures += stage })
        try {
            val chat = Chat(api, "ses_fixture")
            val job = launch { chat.connect() }
            runCurrent()
            assertEquals(1, sockets.size)
            advanceTimeBy(8_000)
            runCurrent()
            assertTrue(job.isActive)
            assertEquals(listOf("chat-ready"), failures)
            assertFalse(chat.state.value.connected)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, sockets.size)
            assertEquals(1, histories)
            assertTrue(chat.state.value.connected)
            job.cancelAndJoin()
            assertFalse(chat.state.value.connected)
        } finally { api.close() }
    }

    @Test fun parentCancellationWhileAwaitingReadyDoesNotRetry() = runTest {
        var connections = 0
        val failures = mutableListOf<String>()
        val engine = object : MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                connections++
                HttpResponseData(HttpStatusCode.SwitchingProtocols, GMTDate(), Headers.Empty,
                    HttpProtocolVersion.HTTP_1_1, TestSocket(currentCoroutineContext()), currentCoroutineContext())
            }
        }) {
            override val supportedCapabilities = super.supportedCapabilities + WebSocketCapability
        }
        val api = Api("https://fixture.example.test", Session(MemorySecureStore()),
            HttpClient(engine) { configureVaultClient() }, { stage, _ -> failures += stage })
        try {
            val job = launch { Chat(api, "ses_fixture").connect() }
            runCurrent()
            advanceTimeBy(1_000)
            job.cancelAndJoin()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(1, connections)
            assertTrue(job.isCancelled)
            assertTrue(failures.isEmpty())
        } finally { api.close() }
    }
}

@OptIn(io.ktor.utils.io.InternalAPI::class)
private class TestSocket(override val coroutineContext: CoroutineContext) : DefaultWebSocketSession {
    override var masking = false
    override var maxFrameSize = Long.MAX_VALUE
    override var pingIntervalMillis = 0L
    override var timeoutMillis = 0L
    override val incoming = Channel<Frame>(Channel.UNLIMITED)
    override val outgoing = Channel<Frame>(Channel.UNLIMITED)
    override val extensions = emptyList<WebSocketExtension<*>>()
    override val closeReason = CompletableDeferred<CloseReason?>()
    override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) {}
    override suspend fun flush() {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun terminate() { incoming.close(); outgoing.close() }
}
