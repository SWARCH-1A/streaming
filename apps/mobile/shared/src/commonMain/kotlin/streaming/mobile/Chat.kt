package streaming.mobile

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import streaming.mobile.contracts.ChatHistory
import streaming.mobile.contracts.ChatMessage

data class ChatState(
    val messages: List<ChatMessage> = emptyList(), val connected: Boolean = false,
    val status: String = "NOT_OPEN", val error: String? = null, val gap: Boolean = false,
    val pendingId: String? = null, val pendingText: String = "", val retryAt: Long = 0,
)

/** Sequential frame processing preserves ready/history/event ordering and moderation barriers. */
class Chat(private val api: Api, private val sessionId: String) {
    private val current = MutableStateFlow(ChatState())
    val state = current.asStateFlow()
    private val ticket = api.session.ticket()
    private var socket: DefaultClientWebSocketSession? = null
    private var lastSequence = 0L
    private var moderationVersion = 0L
    private val tombstones = mutableSetOf<String>()

    suspend fun connect() {
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                var stage = "chat-upgrade"
                try {
                    val connection = api.client.webSocketSession {
                        url(api.url("/realtime/chat/sessions/${id(sessionId)}").replaceFirst("https:", "wss:"))
                        ticket.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    }
                    socket = connection
                    stage = "chat-ready"
                    try {
                        val first = withTimeoutOrNull(8_000) { connection.incoming.receive() }
                            ?: error("Timed out waiting for chat.ready")
                        val ready = frame(first)
                        require(ready.string("type") == "chat.ready" && ready.string("sessionId") == sessionId)
                        stage = "chat-history"
                        backlog()
                        stage = "chat-frames"
                        current.update { it.copy(connected = true, status = ready.string("roomStatus") ?: "READ_ONLY", error = null,
                            gap = it.gap || lastSequence < (ready.long("lastSequence") ?: 0)) }
                        attempt = 0
                        for (message in connection.incoming) process(frame(message))
                    } finally { socket = null; withContext(NonCancellable) { withTimeoutOrNull(1_000) { connection.close() } } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    api.reportFailure(stage, failure)
                    current.update { it.copy(connected = false, error = "Chat desconectado. Reconectando…") }
                    // Engines expose failed Upgrades differently. Revalidate the captured
                    // credential with Core; a transport failure never implies logout.
                    if (ticket.token != null && recoverable { api.checkSession(ticket) }.getOrNull() == false) return
                }
                delay(minOf(1_000L * (1L shl minOf(attempt++, 4)), 15_000))
            }
        } finally { current.update { it.copy(connected = false) } }
    }

    private fun frame(value: Frame): JsonObject {
        require(value is Frame.Text && value.data.size <= 65_536)
        return wireJson.parseToJsonElement(value.readText()).jsonObject
    }

    private suspend fun backlog() {
        val raw = api.get<JsonObject>("/api/chat/sessions/${id(sessionId)}/messages?limit=50")
        if ((raw.long("moderationVersion") ?: 0) < moderationVersion) return
        val history = wireJson.decodeFromJsonElement<ChatHistory>(raw)
        require(history.sessionId == sessionId)
        val earliest = history.items.firstOrNull()?.sequence ?: history.snapshotSequence + 1
        val lost = lastSequence > 0 && earliest > lastSequence + 1
        val merged = mergeChat(sessionId, current.value.messages, history.items, tombstones)
        lastSequence = maxOf(lastSequence, history.snapshotSequence)
        current.update { it.copy(messages = merged, status = history.roomStatus, gap = it.gap || lost || hasGap(merged)) }
    }

    private suspend fun process(value: JsonObject) {
        when (value.string("type")) {
            "message.created" -> {
                val raw = value.getValue("message").jsonObject
                if ((value.long("moderationVersion") ?: raw.long("moderationVersion") ?: 0) < moderationVersion) return
                val message = wireJson.decodeFromJsonElement<ChatMessage>(raw)
                require(message.sessionId == sessionId)
                if (message.sequence > lastSequence + 1) backlog()
                val merged = mergeChat(sessionId, current.value.messages, listOf(message), tombstones)
                lastSequence = maxOf(lastSequence, message.sequence)
                current.update { it.copy(messages = merged, gap = it.gap || hasGap(merged)) }
            }
            "message.accepted" -> {
                require(value.string("sessionId") == sessionId)
                if (value.string("clientMessageId") == current.value.pendingId)
                    current.update { it.copy(pendingId = null, pendingText = "", error = null) }
            }
            "chat.status" -> {
                require(value.string("sessionId") == sessionId)
                current.update { it.copy(status = value.string("roomStatus") ?: "READ_ONLY") }
            }
            "message.deleted" -> {
                require(value.string("sessionId") == sessionId)
                val version = value.long("moderationVersion") ?: error("Missing moderation version")
                if (version < moderationVersion) return
                moderationVersion = version
                tombstones += id(value.string("messageId") ?: error("Missing message ID"))
                current.update { it.copy(messages = it.messages.filterNot { message -> message.messageId in tombstones }) }
            }
            "messages.invalidated" -> {
                require(value.string("sessionId") == sessionId)
                val version = value.long("moderationVersion") ?: error("Missing moderation version")
                if (version < moderationVersion) return
                moderationVersion = version
                current.update { it.copy(messages = emptyList()) }
                backlog()
            }
            "error" -> {
                if (value.string("code") == "AUTH_REQUIRED") api.session.expire(ticket)
                current.update { it.copy(error = when (value.string("code")) {
                    "RATE_LIMITED" -> "Espera antes de enviar otro mensaje."
                    "AUTH_REQUIRED" -> "Inicia sesión para escribir."
                    else -> "El mensaje no se pudo confirmar. Puedes reintentarlo."
                }, retryAt = kotlin.time.Clock.System.now().toEpochMilliseconds() + (value.long("retryAfterMs") ?: 0)) }
            }
            else -> error("Unsupported chat frame")
        }
    }
    suspend fun send(raw: String) {
        val text = normalizeText(raw).trim()
        require(text.isNotBlank() && codePoints(text) <= 500) { "Escribe entre 1 y 500 caracteres." }
        check(current.value.connected && current.value.status == "OPEN" && api.session.state.value != null) { "El chat está en modo lectura." }
        require(kotlin.time.Clock.System.now().toEpochMilliseconds() >= current.value.retryAt) { "Espera antes de enviar otro mensaje." }
        val previous = current.value
        val clientId = if (previous.pendingText == text) previous.pendingId ?: randomId() else randomId()
        current.update { it.copy(pendingId = clientId, pendingText = text, error = null) }
        socket?.send(Frame.Text(buildJsonObject {
            put("type", "message.send"); put("clientMessageId", clientId); put("text", text)
        }.toString())) ?: error("El chat está desconectado.")
    }
    fun discard() { current.update { it.copy(pendingId = null, pendingText = "") } }
}

fun mergeChat(sessionId: String, old: List<ChatMessage>, incoming: List<ChatMessage>, removed: Set<String>): List<ChatMessage> =
    (old + incoming).filter { it.sessionId == sessionId && it.messageId !in removed }
        .associateBy { it.sequence }.values.sortedBy { it.sequence }.takeLast(200)

fun hasGap(messages: List<ChatMessage>): Boolean = messages.zipWithNext().any { (a, b) -> b.sequence > a.sequence + 1 }
fun codePoints(value: String): Int = value.indices.count { !value[it].isLowSurrogate() || it == 0 || !value[it - 1].isHighSurrogate() }
