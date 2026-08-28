package com.getair.stremio

import com.getair.stremio.model.AddonManifest

data class AddonUrlOptions(
    val allowHttp: Boolean = false,
    val allowPrivateNetwork: Boolean = false,
    val allowedOrigins: Set<String>? = null,
    val urlPolicy: ((String) -> Boolean)? = null,
)

data class AddonResourceRequest(
    val resource: String,
    val type: String,
    val id: String,
    val extra: Map<String, List<String>> = emptyMap(),
)

object AddonUrls {
    fun normalizeManifestUrl(input: String, options: AddonUrlOptions = AddonUrlOptions()): String =
        normalize(input, options, requireManifest = true)

    fun normalizeResourceUrl(input: String, options: AddonUrlOptions = AddonUrlOptions()): String =
        normalize(input, options, requireManifest = false)

    fun resolveRedirect(
        baseUrl: String,
        location: String,
        options: AddonUrlOptions = AddonUrlOptions(),
    ): String {
        val base = parse(baseUrl)
        val raw = location.trim()
        val absolute = when {
            URL_PATTERN.matches(raw) -> raw
            raw.startsWith("//") -> "${base.scheme}:$raw"
            raw.startsWith('/') -> "${base.origin()}$raw"
            else -> {
                val directory = base.path.substringBeforeLast('/', missingDelimiterValue = "")
                "${base.origin()}${normalizePath("$directory/$raw")}"
            }
        }
        return normalizeResourceUrl(absolute, options)
    }

    fun sameOrigin(left: String, right: String): Boolean = parse(left).origin() == parse(right).origin()

    private fun normalize(input: String, options: AddonUrlOptions, requireManifest: Boolean): String {
        val url = parse(input)
        val scheme = if (url.scheme == "stremio") "https" else url.scheme
        requireAllowed(scheme == "https" || (scheme == "http" && options.allowHttp)) {
            "Only HTTPS addon URLs are allowed unless allowHttp is enabled"
        }
        requireAllowed(!url.hasCredentials) { "Credentials in addon URL authorities are not allowed" }
        requireAllowed(options.allowPrivateNetwork || !isPrivateHostname(url.host)) {
            "Private-network addon URLs require allowPrivateNetwork"
        }

        val path = if (!requireManifest || url.path.endsWith("/manifest.json")) {
            url.path
        } else {
            "${url.path.trimEnd('/')}/manifest.json"
        }
        val normalized = ParsedUrl(scheme, url.host, url.port, path, url.query).render()
        val origin = ParsedUrl(scheme, url.host, url.port, "/", null).origin()
        val allowedOrigins = options.allowedOrigins?.mapTo(mutableSetOf()) { parse(it).origin() }
        requireAllowed(allowedOrigins == null || origin in allowedOrigins) { "Origin is not allowed" }
        requireAllowed(options.urlPolicy?.invoke(normalized) != false) {
            "The application URL policy rejected this addon URL"
        }
        return normalized
    }

    fun makeResourceUrl(manifestUrl: String, request: AddonResourceRequest): String {
        val url = parse(manifestUrl)
        val basePath = url.path.removeSuffix("/manifest.json")
        val segments = listOf(request.resource, request.type, request.id)
            .joinToString(separator = "/", transform = ::encodePathSegment)
        val extra = encodeExtra(request.extra)
        val suffix = if (extra == null) ".json" else "/$extra.json"
        return ParsedUrl(url.scheme, url.host, url.port, "$basePath/$segments$suffix", null).render()
    }

    fun isResourceSupported(
        manifest: AddonManifest,
        resource: String,
        type: String,
        id: String,
    ): Boolean {
        if (resource == "catalog") {
            return manifest.catalogs.any { it.type == type && it.id == id }
        }
        return manifest.resources.any { candidate ->
            if (candidate.name != resource) return@any false
            val types = candidate.types ?: manifest.types
            val prefixes = if (candidate.types == null) {
                manifest.idPrefixes
            } else {
                candidate.idPrefixes.orEmpty()
            }
            type in types && (prefixes.isEmpty() || prefixes.any(id::startsWith))
        }
    }

    private fun encodeExtra(extra: Map<String, List<String>>): String? =
        extra.keys.sorted()
            .flatMap { key -> extra.getValue(key).map { value -> "${encodeForm(key)}=${encodeForm(value)}" } }
            .joinToString("&")
            .ifEmpty { null }

    private fun parse(input: String): ParsedUrl {
        val match = URL_PATTERN.matchEntire(input.trim())
            ?: throw InvalidAddonUrlException("Invalid addon URL")
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        val hasCredentials = '@' in authority
        val hostPort = authority.substringAfterLast('@')
        val (host, port) = splitHostPort(hostPort)
        requireAllowed(host.isNotBlank()) { "Addon URL host is required" }
        val path = match.groupValues[3].ifBlank { "/" }
        val query = match.groupValues[4].removePrefix("?").ifBlank { null }
        return ParsedUrl(scheme, host.lowercase(), port, path, query, hasCredentials)
    }

    private fun splitHostPort(authority: String): Pair<String, Int?> {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            requireAllowed(close > 0) { "Invalid IPv6 addon URL" }
            val host = authority.substring(1, close)
            val tail = authority.substring(close + 1)
            val port = tail.removePrefix(":").takeIf { it.isNotBlank() }?.toIntOrNull()
            requireAllowed(tail.isBlank() || (tail.startsWith(":") && port != null)) { "Invalid addon URL port" }
            return host to port
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        val port = authority.substring(colon + 1).toIntOrNull()
        requireAllowed(port != null) { "Invalid addon URL port" }
        return authority.substring(0, colon) to port
    }

    private fun normalizePath(pathWithQuery: String): String {
        val fragmentless = pathWithQuery.substringBefore('#')
        val queryIndex = fragmentless.indexOf('?')
        val path = if (queryIndex < 0) fragmentless else fragmentless.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else fragmentless.substring(queryIndex)
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/${segments.joinToString("/")}$query"
    }

    private fun isPrivateHostname(hostname: String): Boolean {
        val normalized = hostname.trim('[', ']').lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        IPV4_MAPPED.matchEntire(normalized)?.let { mapped ->
            val high = mapped.groupValues[1].toInt(16)
            val low = mapped.groupValues[2].toInt(16)
            return isPrivateIpv4(listOf(high ushr 8, high and 255, low ushr 8, low and 255))
        }
        if (':' in normalized) {
            val first = normalized.split(':').firstOrNull(String::isNotBlank)?.toIntOrNull(16) ?: 0
            return normalized == "::" || normalized == "::1" ||
                first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80 || first and 0xff00 == 0xff00
        }
        return isPrivateIpv4(normalized.split('.').map { it.toIntOrNull() ?: -1 })
    }

    private fun isPrivateIpv4(parts: List<Int>): Boolean {
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val first = parts[0]
        val second = parts[1]
        return first == 10 || first == 127 || first == 0 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && (second == 0 || second == 168)) ||
            (first == 198 && (second == 18 || second == 19)) ||
            first >= 224
    }

    private fun encodePathSegment(value: String): String = percentEncode(value, PATH_SEGMENT_ALLOWED, spaceAsPlus = false)
    private fun encodeForm(value: String): String = percentEncode(value, FORM_ALLOWED, spaceAsPlus = true)

    private fun percentEncode(value: String, allowed: String, spaceAsPlus: Boolean): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            when {
                char == ' ' && spaceAsPlus -> append('+')
                unsigned < 128 && (char.isLetterOrDigit() || char in allowed) -> append(char)
                else -> {
                    append('%')
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0f])
                }
            }
        }
    }

    private inline fun requireAllowed(value: Boolean, lazyMessage: () -> String) {
        if (!value) throw InvalidAddonUrlException(lazyMessage())
    }

    private val URL_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?(?:#.*)?$")
    private val IPV4_MAPPED = Regex("^::ffff:([0-9a-f]{1,4}):([0-9a-f]{1,4})$")
    private const val PATH_SEGMENT_ALLOWED = "-_.!~*'()"
    private const val FORM_ALLOWED = "*-._"
    private const val HEX = "0123456789ABCDEF"
}

private data class ParsedUrl(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val hasCredentials: Boolean = false,
) {
    fun origin(): String {
        val renderedPort = effectivePort()?.let { ":$it" }.orEmpty()
        return "$scheme://${renderHost()}$renderedPort"
    }

    fun render(): String = origin() + path + query?.let { "?$it" }.orEmpty()

    private fun effectivePort(): Int? = port?.takeUnless {
        (scheme == "https" && it == 443) || (scheme == "http" && it == 80)
    }

    private fun renderHost(): String = if (':' in host) "[$host]" else host
}
