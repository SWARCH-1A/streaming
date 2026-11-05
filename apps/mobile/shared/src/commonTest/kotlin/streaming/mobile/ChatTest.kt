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

    @Test fun moderatorRemovalsHideMessagesLiveAndAfterHistoryReload() = runTest {
        fun message(seq: Int) = """{"messageId":"msg_$seq","sessionId":"ses_fixture","sequence":$seq,""" +
            """"author":{"userId":"usr_ana","handle":"ana_01","displayName":"Ana","avatarUri":null},"text":"texto $seq",""" +
            """"serverCreatedAtUtc":"2026-10-31T20:00:0${seq}Z","streamOffsetMs":${seq}000,"streamGeneration":1}"""
        val histories = listOf(
            """{"sessionId":"ses_fixture","items":[${message(1)},${message(2)}],"snapshotSequence":2,"roomStatus":"OPEN","moderationVersion":0,"removedMessages":[]}""",
            // Al recargar, msg_1 ya fue retirado: el servidor lo marca y el cliente no lo conserva.
            """{"sessionId":"ses_fixture","items":[${message(2)},${message(3)}],"snapshotSequence":3,"roomStatus":"OPEN","moderationVersion":1,""" +
                """"removedMessages":[{"messageId":"msg_1","sessionId":"ses_fixture","sequence":1,"moderationVersion":1,"removedAtUtc":"2026-10-31T20:01:00Z"}]}""",
        )
        var loaded = 0
        lateinit var socket: TestSocket
        val engine = object : MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.encodedPath.startsWith("/realtime/")) {
                    val context = currentCoroutineContext()
                    socket = TestSocket(context)
                    socket.incoming.trySend(Frame.Text(
                        """{"type":"chat.ready","sessionId":"ses_fixture","roomStatus":"OPEN","lastSequence":2}"""))
                    HttpResponseData(HttpStatusCode.SwitchingProtocols, GMTDate(), Headers.Empty,
                        HttpProtocolVersion.HTTP_1_1, socket, context)
                } else respond(histories[minOf(loaded++, 1)], HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) {
            override val supportedCapabilities = super.supportedCapabilities + WebSocketCapability
        }
        val failures = mutableListOf<String>()
        val api = Api("https://fixture.example.test", Session(MemorySecureStore()),
            HttpClient(engine) { configureVaultClient() }, { stage, _ -> failures += stage })
        try {
            val chat = Chat(api, "ses_fixture")
            val job = launch { chat.connect() }
            runCurrent()
            assertEquals(listOf("msg_1", "msg_2"), chat.state.value.messages.map { it.messageId })
            // Un salto de secuencia recarga el historial, que ya trae msg_1 como retirado.
            socket.incoming.trySend(Frame.Text("""{"type":"message.created","message":${message(4)}}"""))
            runCurrent()
            assertEquals(listOf("msg_2", "msg_3", "msg_4"), chat.state.value.messages.map { it.messageId })
            socket.incoming.trySend(Frame.Text(
                """{"type":"message.removed","messageId":"msg_2","sessionId":"ses_fixture","sequence":2,"moderationVersion":2,"removedAtUtc":"2026-10-31T20:02:00Z"}"""))
            runCurrent()
            assertEquals(listOf("msg_3", "msg_4"), chat.state.value.messages.map { it.messageId })
            assertTrue(chat.state.value.connected, "el retiro no provoca reconexión")
            assertTrue(failures.isEmpty(), failures.toString())
            job.cancelAndJoin()
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
