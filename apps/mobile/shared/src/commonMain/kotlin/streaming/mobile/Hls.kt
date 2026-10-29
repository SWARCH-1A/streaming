package streaming.mobile

import io.ktor.http.Url

data class Rendition(val label: String, val url: String)

/** Only variants actually advertised by this session's master may be selected. */
fun hlsRenditions(masterUrl: String, manifest: String): List<Rendition> {
    require(manifest.length <= 262_144 && manifest.trimStart().startsWith("#EXTM3U"))
    val master = Url(masterUrl)
    val directory = masterUrl.substringBeforeLast('/') + "/"
    val result = mutableListOf<Rendition>()
    var label: String? = null
    manifest.lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.startsWith("#EXT-X-STREAM-INF:") -> {
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)
                val bitrate = Regex("(?:[:,])BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                label = height?.let { "${it}p" } ?: bitrate?.let { "${it / 1000} kbps" }
            }
            line.isNotEmpty() && !line.startsWith('#') && label != null -> {
                require(!line.startsWith('/') && ':' !in line && '\\' !in line && '%' !in line && line.split('/').none { it == ".." || it == "." })
                val variant = Url(directory + line)
                require(variant.host == master.host && variant.port == master.port && variant.protocol == master.protocol)
                result += Rendition(label, variant.toString()); label = null
            }
        }
    }
    return result.distinctBy { it.url }.take(4)
}
