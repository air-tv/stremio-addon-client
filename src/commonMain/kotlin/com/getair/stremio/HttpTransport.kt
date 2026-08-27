package com.getair.stremio

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

data class AddonHttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 15_000,
    val maxResponseBytes: Int = 10 * 1024 * 1024,
) {
    init {
        require(timeoutMillis >= 0)
        require(maxResponseBytes > 0)
    }

    override fun toString(): String =
        "AddonHttpRequest(url=<redacted>, headers=<redacted>, timeoutMillis=$timeoutMillis, " +
            "maxResponseBytes=$maxResponseBytes)"
}

data class AddonHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

class AddonTransportException(message: String) : IllegalStateException(message)

fun interface AddonHttpTransport {
    suspend fun execute(request: AddonHttpRequest): AddonHttpResponse
}

class KtorAddonHttpTransport(private val client: HttpClient) : AddonHttpTransport {
    override suspend fun execute(request: AddonHttpRequest): AddonHttpResponse = try {
        if (request.timeoutMillis > 0) withTimeout(request.timeoutMillis) { executeWithoutTimeout(request) }
        else executeWithoutTimeout(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: AddonTransportException) {
        throw error
    } catch (_: Throwable) {
        throw AddonTransportException("Stremio addon request failed")
    }

    private suspend fun executeWithoutTimeout(request: AddonHttpRequest): AddonHttpResponse {
        val response = client.get(request.url) {
            request.headers.forEach { (name, value) -> header(name, value) }
        }
        val declaredLength = response.headers["Content-Length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > request.maxResponseBytes) {
            throw AddonTransportException("Stremio addon response exceeded the configured size limit")
        }
        val channel = response.bodyAsChannel()
        val output = BoundedByteAccumulator(request.maxResponseBytes)
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            if (!output.append(buffer, count)) {
                channel.cancel(null)
                throw AddonTransportException("Stremio addon response exceeded the configured size limit")
            }
        }
        return AddonHttpResponse(
            status = response.status.value,
            headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(",") },
            body = output.toByteArray(),
        )
    }

    override fun toString(): String = "KtorAddonHttpTransport(client=<redacted>)"
}

private class BoundedByteAccumulator(private val maximumSize: Int) {
    private var storage = ByteArray(maximumSize.coerceAtMost(8 * 1024))
    private var size = 0

    fun append(source: ByteArray, count: Int): Boolean {
        if (count < 0 || size > maximumSize - count) return false
        ensureCapacity(size + count)
        source.copyInto(storage, destinationOffset = size, startIndex = 0, endIndex = count)
        size += count
        return true
    }

    fun toByteArray(): ByteArray = storage.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= storage.size) return
        var capacity = storage.size.coerceAtLeast(1)
        while (capacity < required) capacity = (capacity * 2).coerceAtMost(maximumSize)
        storage = storage.copyOf(capacity)
    }
}
