package streaming.mobile

import io.ktor.http.Url
import io.ktor.http.protocolWithAuthority

data class Rendition(val label: String, val url: String)

/** Relative, origin-relative and absolute HLS references stay inside this recording/session. */
internal fun hlsVariantUrl(masterUrl: String, reference: String): String {
    require(reference.isNotBlank() && !reference.startsWith("//") &&
        reference.none { it.isWhitespace() || it in "\\%?#" } &&
        reference.split('/').none { it == "." || it == ".." })
    val master = Url(masterUrl)
    val directory = master.encodedPath.substringBeforeLast('/') + "/"
    val variant = Url(when {
        reference.startsWith('/') -> "${master.protocolWithAuthority}$reference"
        reference.startsWith("https://") -> reference
        else -> { require(':' !in reference); masterUrl.substringBeforeLast('/') + "/" + reference }
    })
    require(variant.protocol == master.protocol && variant.host == master.host && variant.port == master.port &&
        variant.user.isNullOrEmpty() && variant.password.isNullOrEmpty() &&
        variant.encodedPath.startsWith(directory) && variant.encodedPath.endsWith(".m3u8"))
    return variant.toString()
}

/** Only variants actually advertised by this session's master may be selected. */
fun hlsRenditions(masterUrl: String, manifest: String): List<Rendition> {
    require(manifest.length <= 262_144 && manifest.trimStart().startsWith("#EXTM3U"))
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
                result += Rendition(label, hlsVariantUrl(masterUrl, line)); label = null
            }
        }
    }
    return result.distinctBy { it.url }.take(4)
}
