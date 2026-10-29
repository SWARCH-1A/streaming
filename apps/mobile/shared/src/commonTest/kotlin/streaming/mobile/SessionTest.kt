package streaming.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import streaming.mobile.contracts.P2NativeLogin
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class MemorySecureStore : SecureStore {
    var value: String? = null
    override fun read() = value
    override fun write(value: String?) { this.value = value }
}

class SessionTest {
    private fun login(token: Char = 'A') = P2NativeLogin(token.toString().repeat(43), "Bearer", "usr_fixture", (Clock.System.now() + 24.hours).toString())

    @Test fun old401CannotClearANewerLogin() {
        val store = MemorySecureStore(); val session = Session(store)
        session.install(session.beginLogin(), login())
        val old = session.ticket()
        session.install(session.beginLogin(), login('B'))
        session.expire(old)
        assertEquals("B".repeat(43), session.state.value?.accessToken)
        val restored = Session(store)
        assertEquals("usr_fixture", restored.state.value?.userId)
    }
    @Test fun aCancelledLoginCannotReplaceANewerAttempt() {
        val session = Session(MemorySecureStore())
        val first = session.beginLogin(); val second = session.beginLogin()
        session.install(second, login('B')); session.install(first, login('A'))
        assertEquals("B".repeat(43), session.state.value?.accessToken)
    }
    @Test fun current401ClearsMemoryAndSecureStorage() = runTest {
        val store = MemorySecureStore(); val session = Session(store)
        session.install(session.beginLogin(), login())
        val engine = MockEngine { request ->
            assertEquals("Bearer ${"A".repeat(43)}", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie]); assertNull(request.headers[HttpHeaders.Origin])
            respond("""{"code":"AUTH_REQUIRED"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = Api("https://fixture.example.test", session, HttpClient(engine) { configureVaultClient() })
        assertFailsWith<ApiFailure> { api.get<String>("/api/profile/me", authenticated = true) }
        assertNull(session.state.value); assertNull(store.value); api.close()
    }
    @Test fun public401DoesNotRevokeTheSession() = runTest {
        val session = Session(MemorySecureStore()); session.install(session.beginLogin(), login())
        val api = Api("https://fixture.example.test", session, HttpClient(MockEngine {
            assertNull(it.headers[HttpHeaders.Authorization])
            respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configureVaultClient() })
        assertFailsWith<ApiFailure> { api.get<String>("/api/public") }
        assertNotNull(session.state.value); api.close()
    }
    @Test fun expiredSessionAndCorruptStorageDoNotRestoreIdentity() {
        val store = MemorySecureStore()
        store.value = "not json"; assertNull(Session(store).state.value); assertNull(store.value)
        val expired = login().copy(expiresAtUtc = "2020-01-01T00:00:00Z")
        store.value = kotlinx.serialization.json.Json.encodeToString(expired)
        assertNull(Session(store).state.value); assertNull(store.value)
    }
    @Test fun tokensAreRedactedByToString() {
        assertFalse(login().toString().contains("A".repeat(43)))
    }
    @Test fun loginSerializesCanonicalRequestAndPersistsOnlyIssuedSession() = runTest {
        val store = MemorySecureStore()
        val session = Session(store)
        val api = Api("https://fixture.example.test", session, HttpClient(MockEngine { request ->
            assertEquals("/api/identity/mobile-sessions", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("fixture", payload.getValue("login").jsonPrimitive.content)
            assertEquals("fictional-password", payload.getValue("password").jsonPrimitive.content)
            respond(Json.encodeToString(login()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configureVaultClient() })
        api.login("fixture", "fictional-password")
        assertEquals("usr_fixture", Session(store).state.value?.userId)
        api.close()
    }
    @Test fun graphqlSerializesJsonTreesWithLiteralsAndNulls() = runTest {
        val api = Api("https://fixture.example.test", Session(MemorySecureStore()), HttpClient(MockEngine { request ->
            val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(JsonNull, payload.getValue("variables").jsonObject["categoryId"])
            assertEquals("fixture", payload.getValue("variables").jsonObject.getValue("q").jsonPrimitive.content)
            respond("""{"data":{"streams":{"items":[],"nextCursor":null}}}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { configureVaultClient() })
        assertTrue(api.discovery("fixture", null, null, null).items().isEmpty())
        api.close()
    }
    @Test fun mediaCannotEscapeThePublicOriginOrSession() {
        val api = Api("https://fixture.example.test", Session(MemorySecureStore()), HttpClient(MockEngine { respond("") }))
        assertFailsWith<IllegalArgumentException> { api.mediaUrl("https://evil.example.test/live.m3u8", "ses_fixture") }
        assertFailsWith<IllegalArgumentException> { api.mediaUrl("/hls/ses_fixture/../../api/profile/me.m3u8", "ses_fixture") }
        assertFailsWith<IllegalArgumentException> { api.mediaUrl("/hls/ses_fixture/%2e%2e/key.m3u8", "ses_fixture") }
        assertEquals("https://fixture.example.test/hls/ses_fixture/index.m3u8", api.mediaUrl("/hls/ses_fixture/index.m3u8", "ses_fixture"))
        assertEquals("https://fixture.example.test/hls/ses_fixture/index.m3u8", api.mediaUrl("https://fixture.example.test/hls/ses_fixture/index.m3u8", "ses_fixture"))
        assertFailsWith<IllegalArgumentException> { api.mediaUrl("https://fixture.example.test:8443/hls/ses_fixture/index.m3u8", "ses_fixture") }
        api.close()
    }
}
