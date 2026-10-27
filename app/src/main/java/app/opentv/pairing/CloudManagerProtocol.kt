/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.pairing

import java.net.URI
import java.security.SecureRandom
import kotlinx.serialization.Serializable

/** Narrow, versioned management protocol; never a general URL/network proxy. */
object CloudManagerProtocol {
    const val MAX_REQUEST_BYTES = 256 * 1024
    const val MAX_REPLY_BYTES = 2 * 1024 * 1024
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val random = SecureRandom()

    fun newCode(): String = (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    fun newToken(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    fun displayCode(code: String): String = code.chunked(4).joinToString("-")

    /** Only an HTTPS origin is accepted. Redirects are disabled by the client. */
    fun origin(value: String): String? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port == 443)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        "https://${uri.host.lowercase()}"
    }.getOrNull()

    fun allowed(method: String, path: String): Boolean = runCatching {
        if (path.length > 4096 || !path.startsWith("/") || path.startsWith("//")) return@runCatching false
        val uri = URI(path)
        if (uri.rawFragment != null || uri.rawAuthority != null) return@runCatching false
        if (method == "GET" && uri.rawPath == "/channels") {
            val parts = uri.rawQuery?.split("&") ?: return@runCatching false
            return@runCatching parts.any { it.startsWith("cat=") && it.length > 4 } &&
                parts.all { it.substringBefore('=') in setOf("cat", "source") }
        }
        if (!uri.rawQuery.isNullOrEmpty()) return@runCatching false
        (method == "GET" && path in setOf("/meta", "/recording")) ||
            (method == "POST" && path in setOf("/channel", "/reorder", "/recording", "/recording/test", "/source", "/source/test"))
    }.getOrDefault(false)
}

@Serializable
data class CloudManagerCommand(val id: String, val method: String, val path: String, val body: String = "")

@Serializable
data class CloudManagerReply(val id: String, val status: Int, val body: String)
