package streaming.mobile

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import streaming.mobile.contracts.*

val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

fun HttpClientConfig<*>.configureVaultClient() {
    expectSuccess = false
    followRedirects = false
    install(ContentNegotiation) { json(wireJson) }
    install(HttpTimeout) { requestTimeoutMillis = 8_000; connectTimeoutMillis = 5_000 }
    // OkHttp rejects a finite maxFrameSize during Upgrade. Chat validates text
    // frames before decoding; the server also enforces its inbound size limit.
    install(WebSockets)
    // No cookie jar, request logger, credential retry or trust-all TLS adapter.
}

class ApiFailure(val status: Int, val code: String, val requestId: String?, val retryAfter: String?) :
    Exception(messageFor(status, code))

private fun messageFor(status: Int, code: String): String = when (status) {
    401 -> "La sesión ya no es válida. Inicia sesión de nuevo."
    403 -> "No tienes permiso para realizar esta acción."
    404, 410 -> "Este contenido no está disponible."
    409 -> "Los datos cambiaron. Actualiza antes de reintentar."
    429 -> "Alcanzaste el límite de intentos. Espera antes de reintentar."
    503, 504 -> "El servicio no está disponible. Reintenta en unos momentos."
    else -> if (code == "LOGIN_INVALID" || code == "INVALID_CREDENTIALS") "Revisa tu usuario y contraseña." else "No se pudo completar la solicitud. Revisa los datos e inténtalo de nuevo."
}

fun Throwable.userMessage(): String = when (this) {
    is ApiFailure -> message.orEmpty()
    is IllegalArgumentException -> "Revisa los datos introducidos."
    else -> "No se pudo conectar. Comprueba tu conexión y vuelve a intentarlo."
}

class Api(val origin: String, val session: Session, val client: HttpClient,
          val reportFailure: (String, Throwable) -> Unit = { _, _ -> }) {
    private val base = Url(origin)
    init {
        require(base.protocol == URLProtocol.HTTPS && base.host.isNotBlank() &&
            base.encodedPath in listOf("", "/") && base.parameters.isEmpty() &&
            base.user.isNullOrEmpty() && base.password.isNullOrEmpty() && base.fragment.isEmpty()) {
            "La conexión requiere un origen HTTPS sin ruta ni credenciales."
        }
    }
    fun url(path: String): String {
        require(path.startsWith("/") && !path.startsWith("//") && '\\' !in path &&
            !Regex("(?i)%2[ef]|%5c").containsMatchIn(path) && path.substringBefore('?').split('/').none { it == "." || it == ".." }) {
            "Ruta de contenido inválida."
        }
        return origin.trimEnd('/') + path
    }
    fun publicPath(value: String): String {
        if (value.startsWith('/')) { url(value); return value }
        val candidate = Url(value)
        require(candidate.protocol == base.protocol && candidate.host == base.host && candidate.port == base.port &&
            candidate.user.isNullOrEmpty() && candidate.password.isNullOrEmpty() && candidate.fragment.isEmpty() && candidate.parameters.isEmpty()) {
            "Origen de contenido inválido."
        }
        url(candidate.encodedPath)
        return candidate.encodedPath
    }
    fun mediaUrl(value: String, sessionId: String): String {
        val path = publicPath(value)
        require(path.startsWith("/hls/${id(sessionId)}/") && path.substringBefore('?').endsWith(".m3u8")) {
            "La transmisión no tiene una dirección de reproducción válida."
        }
        return url(path)
    }
    suspend inline fun <reified T> get(path: String, authenticated: Boolean = false): T =
        call(HttpMethod.Get, path, authenticated = authenticated).body()

    suspend fun call(method: HttpMethod, path: String, body: Any? = null,
                     authenticated: Boolean = false, key: String? = null,
                     authorization: String? = null): HttpResponse {
        val ticket = session.ticket()
        if (authenticated && ticket.token == null) throw ApiFailure(401, "AUTH_REQUIRED", null, null)
        val response = client.request(url(path)) {
            this.method = method
            accept(ContentType.Application.Json)
            if (body != null) {
                contentType(ContentType.Application.Json)
                if (body is JsonElement) setBody(TextContent(wireJson.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
                else setBody(body)
            }
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            else if (authenticated) header(HttpHeaders.Authorization, "Bearer ${ticket.token}")
            key?.let { header("Idempotency-Key", it) }
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 401 && authenticated) session.expire(ticket)
            val error = runCatching { wireJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            throw ApiFailure(response.status.value, error?.string("code") ?: "HTTP_ERROR",
                error?.string("requestId"), response.headers[HttpHeaders.RetryAfter])
        }
        return response
    }
    suspend fun login(login: String, password: String) {
        val attempt = session.beginLogin()
        val result = call(HttpMethod.Post, "/api/identity/mobile-sessions", LoginRequest(login, password)).body<P2NativeLogin>()
        session.install(attempt, result)
    }
    suspend fun logout() {
        val ticket = session.ticket()
        try { call(HttpMethod.Delete, "/api/identity/mobile-sessions/current", authenticated = ticket.token != null) }
        catch (e: ApiFailure) { if (e.status != 401) throw e }
        session.expire(ticket)
    }

    suspend fun checkSession(ticket: Session.Ticket): Boolean {
        if (ticket.token == null) return true
        return try {
            call(HttpMethod.Get, "/api/profile/me", authorization = "Bearer ${ticket.token}")
            true
        } catch (failure: ApiFailure) {
            if (failure.status != 401) throw failure
            session.expire(ticket)
            false
        }
    }
    suspend fun register(email: String, handle: String, password: String, key: String): Registration =
        call(HttpMethod.Post, "/api/identity/registrations", RegistrationRequest(email, handle, password), key = key).body()

    suspend fun discovery(query: String, category: String?, tag: String?, cursor: String?): JsonObject {
        val payload = buildJsonObject {
            put("query", DiscoveryQueries.LiveStreams)
            putJsonObject("variables") {
                put("q", query); put("categoryId", category?.let(::JsonPrimitive) ?: JsonNull)
                put("tagId", tag?.let(::JsonPrimitive) ?: JsonNull)
                put("cursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("limit", 20)
            }
        }
        return graphql(payload).getValue("streams").jsonObject
    }
    suspend fun channels(query: String, cursor: String?): JsonObject = graphql(buildJsonObject {
        put("query", DiscoveryQueries.Channels)
        putJsonObject("variables") { put("q", query); put("cursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("limit", 20) }
    }).getValue("channels").jsonObject

    private suspend fun graphql(payload: JsonObject): JsonObject {
        val response = call(HttpMethod.Post, "/api/discovery/graphql", payload).body<JsonObject>()
        require(response["errors"]?.jsonArray.isNullOrEmpty()) { "No se pudo completar la búsqueda. Vuelve a intentarlo." }
        return response.getValue("data").jsonObject
    }
    fun close() = client.close()
}

fun id(value: String): String {
    require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Identificador de contenido inválido." }
    return value
}
fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
fun JsonObject.items(): List<JsonObject> = getValue("items").jsonArray.map { it.jsonObject }

suspend inline fun <T> recoverable(block: () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { Result.failure(failure) }
