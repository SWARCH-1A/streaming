package streaming.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import streaming.mobile.contracts.P2NativeLogin
import kotlin.time.Clock
import kotlin.time.Instant

class Session(private val secureStore: SecureStore) {
    private val current = MutableStateFlow<P2NativeLogin?>(null)
    val state = current.asStateFlow()
    var generation: Long = 0
        private set

    init {
        val restored = runCatching { secureStore.read()?.let { Json.decodeFromString<P2NativeLogin>(it) } }.getOrNull()
        if (restored != null && valid(restored)) current.value = restored
        else secureStore.write(null)
    }

    fun beginLogin(): Long = ++generation
    fun install(attempt: Long, login: P2NativeLogin) {
        require(valid(login)) { "La sesión recibida no es válida." }
        if (attempt != generation) return
        secureStore.write(Json.encodeToString(login))
        current.value = login
    }
    fun ticket(): Ticket = Ticket(generation, current.value?.accessToken)
    fun expire(ticket: Ticket) {
        if (ticket.generation == generation && ticket.token == current.value?.accessToken) clear()
    }
    fun clear() {
        ++generation
        secureStore.write(null)
        current.value = null
    }
    private fun valid(login: P2NativeLogin): Boolean = runCatching {
        login.tokenType == "Bearer" && login.accessToken.matches(Regex("[A-Za-z0-9_-]{43}")) &&
            Instant.parse(login.expiresAtUtc) > Clock.System.now()
    }.getOrDefault(false)

    class Ticket(val generation: Long, val token: String?) {
        override fun toString() = "Session.Ticket([redacted])"
    }
}
