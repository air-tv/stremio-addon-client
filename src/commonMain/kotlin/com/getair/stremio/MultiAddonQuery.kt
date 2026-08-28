package com.getair.stremio

import com.getair.stremio.model.AddonCatalogItem
import com.getair.stremio.model.Meta
import com.getair.stremio.model.MetaPreview
import com.getair.stremio.model.Stream
import com.getair.stremio.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Opaque, caller-owned identity for one installed addon configuration.
 *
 * The value is deliberately restricted to a short identifier so configured addon URLs,
 * authorization headers, and other secrets cannot accidentally become query diagnostics.
 */
class AddonInstanceId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) {
            "Addon instance IDs must contain 1-128 ASCII letters, digits, '.', '_', or '-'"
        }
    }

    override fun equals(other: Any?): Boolean = other is AddonInstanceId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "AddonInstanceId(<redacted>)"

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

class StremioAddonBinding(
    val id: AddonInstanceId,
    val client: StremioAddonClient,
) {
    override fun toString(): String = "StremioAddonBinding(id=$id, client=<redacted>)"
}

data class MultiAddonQueryOptions(
    val maxConcurrency: Int = 4,
    val maxAddons: Int = 256,
    val maxItemsPerAddon: Int = 200,
    val maxTotalItems: Int = 1_000,
) {
    init {
        require(maxConcurrency in 1..64) { "maxConcurrency must be between 1 and 64" }
        require(maxAddons in 1..10_000) { "maxAddons must be between 1 and 10000" }
        require(maxItemsPerAddon in 1..100_000) {
            "maxItemsPerAddon must be between 1 and 100000"
        }
        require(maxTotalItems in 1..1_000_000) {
            "maxTotalItems must be between 1 and 1000000"
        }
    }
}

enum class AddonQueryFailureKind {
    Transport,
    InvalidResponse,
    Unexpected,
}

data class AddonQueryFailure(
    val addonId: AddonInstanceId,
    val kind: AddonQueryFailureKind,
)

data class SourcedAddonItem<T>(
    val addonId: AddonInstanceId,
    val value: T,
) {
    override fun toString(): String = "SourcedAddonItem(addonId=$addonId, value=<redacted>)"
}

data class MultiAddonQueryResult<T>(
    val items: List<SourcedAddonItem<T>>,
    val failures: List<AddonQueryFailure>,
    val unsupportedAddonIds: List<AddonInstanceId>,
    val truncatedAddonIds: List<AddonInstanceId>,
)

/**
 * A typed resource request for [queryStremioAddons]. The subclasses cover the existing
 * single-addon API without introducing parallel callback or platform-specific facades.
 */
sealed class StremioAddonQuery<T> protected constructor(
    internal val resource: String,
    internal val type: String,
    internal val id: String,
) {
    internal abstract suspend fun load(client: StremioAddonClient): List<T>

    class Catalog(
        type: String,
        id: String,
        private val extra: Map<String, List<String>> = emptyMap(),
        private val requestOptions: AddonRequestOptions = AddonRequestOptions(),
    ) : StremioAddonQuery<MetaPreview>("catalog", type, id) {
        override suspend fun load(client: StremioAddonClient): List<MetaPreview> =
            client.catalog(type, id, extra, requestOptions).metas
    }

    class Metadata(
        type: String,
        id: String,
        private val requestOptions: AddonRequestOptions = AddonRequestOptions(),
    ) : StremioAddonQuery<Meta>("meta", type, id) {
        override suspend fun load(client: StremioAddonClient): List<Meta> =
            listOf(client.meta(type, id, requestOptions).meta)
    }

    class Streams(
        type: String,
        id: String,
        private val requestOptions: AddonRequestOptions = AddonRequestOptions(),
    ) : StremioAddonQuery<Stream>("stream", type, id) {
        override suspend fun load(client: StremioAddonClient): List<Stream> =
            client.streams(type, id, requestOptions).streams
    }

    class Subtitles(
        type: String,
        id: String,
        private val extra: Map<String, List<String>> = emptyMap(),
        private val requestOptions: AddonRequestOptions = AddonRequestOptions(),
    ) : StremioAddonQuery<Subtitle>("subtitles", type, id) {
        override suspend fun load(client: StremioAddonClient): List<Subtitle> =
            client.subtitles(type, id, extra, requestOptions).subtitles
    }

    class AddonCatalog(
        type: String,
        id: String,
        private val extra: Map<String, List<String>> = emptyMap(),
        private val requestOptions: AddonRequestOptions = AddonRequestOptions(),
    ) : StremioAddonQuery<AddonCatalogItem>("addon_catalog", type, id) {
        override suspend fun load(client: StremioAddonClient): List<AddonCatalogItem> =
            client.addonCatalog(type, id, extra, requestOptions).addons
    }

    override fun toString(): String =
        "StremioAddonQuery(resource=$resource, type=<redacted>, id=<redacted>)"
}

/**
 * Queries capable addons with a fixed number of workers and returns results in addon order.
 *
 * Registrations remain owned and persisted by the application. Unsupported addons are skipped
 * before their resource method is called, one addon failure does not cancel its siblings, and
 * cancellation of the caller immediately cancels the producer and every worker.
 */
suspend fun <T> queryStremioAddons(
    addons: List<StremioAddonBinding>,
    query: StremioAddonQuery<T>,
    options: MultiAddonQueryOptions = MultiAddonQueryOptions(),
): MultiAddonQueryResult<T> {
    require(addons.size <= options.maxAddons) {
        "Addon count exceeds the configured per-query limit"
    }
    require(addons.mapTo(mutableSetOf()) { it.id }.size == addons.size) {
        "Addon instance IDs must be unique within a query"
    }
    if (addons.isEmpty()) {
        return MultiAddonQueryResult(emptyList(), emptyList(), emptyList(), emptyList())
    }

    val outcomes = executeBounded(addons, query, options.maxConcurrency, options.maxItemsPerAddon)
    val items = ArrayList<SourcedAddonItem<T>>(minOf(options.maxTotalItems, 256))
    val failures = ArrayList<AddonQueryFailure>()
    val unsupported = ArrayList<AddonInstanceId>()
    val truncated = ArrayList<AddonInstanceId>()

    outcomes.forEachIndexed { index, outcome ->
        val addonId = addons[index].id
        when (outcome) {
            is QueryOutcome.Failure -> failures += AddonQueryFailure(addonId, outcome.kind)
            QueryOutcome.Unsupported -> unsupported += addonId
            is QueryOutcome.Success -> {
                val remaining = options.maxTotalItems - items.size
                val accepted = minOf(outcome.items.size, remaining)
                for (itemIndex in 0 until accepted) {
                    items += SourcedAddonItem(addonId, outcome.items[itemIndex])
                }
                if (outcome.truncated || accepted < outcome.items.size) truncated += addonId
            }
        }
    }

    return MultiAddonQueryResult(items, failures, unsupported, truncated)
}

private suspend fun <T> executeBounded(
    addons: List<StremioAddonBinding>,
    query: StremioAddonQuery<T>,
    maxConcurrency: Int,
    maxItemsPerAddon: Int,
): List<QueryOutcome<T>> = coroutineScope {
    val work = Channel<IndexedValue<StremioAddonBinding>>(maxConcurrency)
    val completed = Channel<IndexedValue<QueryOutcome<T>>>(maxConcurrency)
    val outcomes = MutableList<QueryOutcome<T>?>(addons.size) { null }
    val workerCount = minOf(maxConcurrency, addons.size)

    launch {
        try {
            addons.forEachIndexed { index, addon -> work.send(IndexedValue(index, addon)) }
        } finally {
            work.close()
        }
    }
    repeat(workerCount) {
        launch {
            for ((index, addon) in work) {
                completed.send(IndexedValue(index, executeOne(addon, query, maxItemsPerAddon)))
            }
        }
    }
    repeat(addons.size) {
        val (index, outcome) = completed.receive()
        outcomes[index] = outcome
    }

    outcomes.map { checkNotNull(it) }
}

private suspend fun <T> executeOne(
    addon: StremioAddonBinding,
    query: StremioAddonQuery<T>,
    maxItems: Int,
): QueryOutcome<T> = try {
    val manifest = addon.client.manifest()
    if (!AddonUrls.isResourceSupported(manifest, query.resource, query.type, query.id)) {
        QueryOutcome.Unsupported
    } else {
        val items = query.load(addon.client)
        QueryOutcome.Success(items.take(maxItems), items.size > maxItems)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: AddonTransportException) {
    QueryOutcome.Failure(AddonQueryFailureKind.Transport)
} catch (_: AddonResponseValidationException) {
    QueryOutcome.Failure(AddonQueryFailureKind.InvalidResponse)
} catch (_: Exception) {
    QueryOutcome.Failure(AddonQueryFailureKind.Unexpected)
}

private sealed interface QueryOutcome<out T> {
    data class Success<T>(val items: List<T>, val truncated: Boolean) : QueryOutcome<T>
    data class Failure(val kind: AddonQueryFailureKind) : QueryOutcome<Nothing>
    data object Unsupported : QueryOutcome<Nothing>
}
