package streaming.mobile

import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import streaming.mobile.contracts.ViewerLease

class ViewerLeases(private val api: Api, private val sessionId: String) {
    private val lock = Mutex()
    /** Serializes start/stop, including a create whose result was received after cancellation. */
    suspend fun whilePlaying(report: (String?) -> Unit) = lock.withLock {
        var lease: ViewerLease? = null
        var key = randomId()
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    if (lease == null) {
                        // Finish this bounded request so that cancellation can release its lease.
                        lease = withContext(NonCancellable) {
                            api.call(HttpMethod.Post, "/api/streams/sessions/${id(sessionId)}/viewer-leases", key = key).body()
                        }
                        require(lease?.sessionId == sessionId)
                    } else {
                        api.call(HttpMethod.Put, "/api/streams/viewer-leases/${id(lease.leaseId)}/heartbeat",
                            authorization = "ViewerLease ${lease.leaseToken}")
                    }
                    report(null)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: ApiFailure) {
                    val expiredToken = failure.status == 401 && failure.code == "INVALID_VIEWER_LEASE_TOKEN"
                    if (failure.status in listOf(404, 409, 410) || (lease != null && expiredToken)) {
                        if (lease == null) { report("La sesión ya no acepta espectadores."); return@withLock }
                        lease = null; key = randomId()
                    }
                    report("No se pudo actualizar el conteo de espectadores.")
                } catch (_: Exception) { report("No se pudo actualizar el conteo de espectadores.") }
                delay(10_000)
            }
        } finally {
            lease?.let {
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) {
                        runCatching { api.call(HttpMethod.Delete, "/api/streams/viewer-leases/${id(it.leaseId)}",
                            authorization = "ViewerLease ${it.leaseToken}") }
                    }
                }
            }
        }
    }
}
