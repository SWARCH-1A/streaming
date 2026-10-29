package streaming.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

class ViewerLeasesTest {
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
