package streaming.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import streaming.mobile.contracts.P2NativeLogin
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerLeasesTest {
    @Test fun expiredHeartbeatReacquiresWithANewKeyAndPreservesLogin() = runTest {
        val store = MemorySecureStore()
        val session = Session(store)
        session.install(session.beginLogin(), P2NativeLogin("A".repeat(43), "Bearer", "usr_fixture",
            (Clock.System.now() + 24.hours).toString()))
        val savedSession = store.value
        val keys = mutableListOf<String>()
        val heartbeats = mutableListOf<String?>()
        val released = mutableListOf<String>()
        val api = Api("https://fixture.example.test", session, HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.method) {
                        HttpMethod.Post -> {
                            assertNull(request.headers[HttpHeaders.Authorization])
                            keys += assertNotNull(request.headers["Idempotency-Key"])
                            val index = keys.size
                            respond("""{"leaseId":"lease_$index","leaseToken":"fictional-$index","sessionId":"ses_fixture","heartbeatEverySeconds":10,"expiresAfterSeconds":30}""",
                                HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        HttpMethod.Put -> {
                            heartbeats += request.headers[HttpHeaders.Authorization]
                            if (heartbeats.size == 1)
                                respond("""{"code":"INVALID_VIEWER_LEASE_TOKEN"}""", HttpStatusCode.Unauthorized,
                                    headersOf(HttpHeaders.ContentType, "application/json"))
                            else respond("{}", HttpStatusCode.OK)
                        }
                        HttpMethod.Delete -> {
                            released += request.url.encodedPath
                            respond("", HttpStatusCode.NoContent)
                        }
                        else -> fail("Unexpected lease request")
                    }
                }
            }
            configureVaultClient()
        })
        try {
            val job = launch { ViewerLeases(api, "ses_fixture").whilePlaying {} }
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(2, keys.size)
            assertNotEquals(keys[0], keys[1])
            assertEquals(listOf<String?>("ViewerLease fictional-1", "ViewerLease fictional-2"), heartbeats)
            assertEquals("A".repeat(43), session.state.value?.accessToken)
            assertEquals(savedSession, store.value)
            job.cancelAndJoin()
            assertEquals(listOf("/api/streams/viewer-leases/lease_2"), released)
        } finally { api.close() }
    }

    @Test fun cancellationWaitsForInFlightCreateAndReleasesTheIssuedLease() = runTest {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var created = 0
        var closed = 0
        val api = Api("https://fixture.example.test", Session(MemorySecureStore()), HttpClient(MockEngine) {
            engine {
                // Keep mocked I/O on the virtual clock used by the cleanup timeout.
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.method) {
                        HttpMethod.Post -> {
                            created++
                            assertNotNull(request.headers["Idempotency-Key"])
                            assertNull(request.headers[HttpHeaders.Authorization])
                            entered.complete(Unit)
                            response.await()
                            respond("""{"leaseId":"lease_fixture","leaseToken":"fictional-lease","sessionId":"ses_fixture","heartbeatEverySeconds":10,"expiresAfterSeconds":30}""",
                                HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        HttpMethod.Delete -> {
                            closed++
                            assertEquals("/api/streams/viewer-leases/lease_fixture", request.url.encodedPath)
                            assertEquals("ViewerLease fictional-lease", request.headers[HttpHeaders.Authorization])
                            respond("", HttpStatusCode.NoContent)
                        }
                        else -> fail("A cancelled player must not heartbeat")
                    }
                }
            }
            configureVaultClient()
        })
        val job = launch { ViewerLeases(api, "ses_fixture").whilePlaying {} }
        entered.await()
        job.cancel()
        response.complete(Unit)
        job.join()
        assertEquals(1, created)
        assertEquals(1, closed)
        api.close()
    }
}
