package streaming.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Links select a public screen; the domain API still decides whether content is available. */
object DestinationLinks {
    private val current = MutableStateFlow<String?>(null)
    val pending = current.asStateFlow()
    fun open(url: String): Boolean {
        val route = destinationRoute(url) ?: return false
        current.value = route
        return true
    }
    fun consumed(route: String) { if (current.value == route) current.value = null }
}

fun destinationRoute(url: String): String? {
    val match = Regex("^vault://(watch|channel)/([A-Za-z0-9_-]{1,128})$").matchEntire(url) ?: return null
    return "${match.groupValues[1]}:${match.groupValues[2]}"
}
