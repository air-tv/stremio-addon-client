# `@get-air/stremio-addon-client`

> Kotlin Multiplatform migration: the repository now also builds
> `com.getair:stremio-addon-client:0.1.0-SNAPSHOT` for Android, JVM, Linux,
> Windows, macOS, iOS, JavaScript, and Wasm. The existing TypeScript source and
> tests remain the behavioral oracle until Kotlin parity is proven. Run
> `./gradlew jvmTest jsNodeTest wasmJsNodeTest` for the portable KMP contract.
> Release tags publish Maven artifacts to GitHub Packages; no local publishing
> token belongs in this repository.

The Kotlin slice now includes URL/private-network policy, capability checks,
tolerant manifest/resource normalization, bounded Ktor transport, and a suspend
client. Normal tests use fictional addon responses. An opt-in local check loads
the configured metadata, stream, and subtitle addons without printing URLs or
response data:

```sh
./scripts/test-live-integration.sh
```

## Kotlin dependency from GitHub Packages

GitHub Packages requires authentication even when this repository is public.
Create a classic personal access token with `read:packages`, keep it outside the
project, and expose the credentials through environment variables or your user
Gradle properties. Never commit the token.

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/air-tv/stremio-addon-client")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull
            password = providers.environmentVariable("GITHUB_TOKEN").orNull
        }
    }
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation("com.getair:stremio-addon-client:1.2.3")
    }
}
```

For a local machine, `GITHUB_ACTOR` is your GitHub username and `GITHUB_TOKEN`
is the read-only package token. GitHub Actions consumers can use the workflow's
built-in `GITHUB_TOKEN` after granting `packages: read`. Workspace development
continues to use the composite build and does not need package credentials.

[![CI](https://github.com/air-tv/stremio-addon-client/actions/workflows/ci.yml/badge.svg)](https://github.com/air-tv/stremio-addon-client/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/@get-air/stremio-addon-client.svg)](https://www.npmjs.com/package/@get-air/stremio-addon-client)

A safe, fully typed client for consuming remote Stremio addon protocol endpoints. It does not build addons, execute addon code, maintain an installed-addon list, or persist addon configuration.

The root API uses ordinary Promises and plain TypeScript values:

```ts
import { StremioAddon, isStremioAddonError } from "@get-air/stremio-addon-client"

const addon = await StremioAddon.connect("https://example.com/manifest.json")

try {
  const { streams } = await addon.streams("movie", "tt1254207")
  console.log(streams)
} catch (error) {
  if (isStremioAddonError(error)) console.error(error._tag, error.message)
}
```

Effect applications import the same implementation from the explicit entrypoint:

```ts
import { Effect } from "effect"
import { StremioAddon } from "@get-air/stremio-addon-client/effect"

const program = Effect.gen(function* () {
  const addon = yield* StremioAddon.connect("https://example.com/manifest.json")
  return yield* addon.streams("movie", "tt1254207")
})
```

## Protocol coverage

- Manifest discovery from a base URL, `manifest.json` URL, or `stremio://` install URL.
- Catalog, metadata, stream, subtitle, and addon-catalog resources.
- Manifest capability checks before resource requests.
- Correct path encoding and deterministic catalog/subtitle extras.
- Runtime validation of untrusted manifest and resource JSON.

## Safety

HTTPS is required by default. Credentials in URL authorities and literal private-network/localhost targets are rejected. Applications may restrict requests further with `allowedOrigins` or `urlPolicy`. HTTP and private-network access can be enabled explicitly with `allowHttp` and `allowPrivateNetwork` for trusted local addons.

Requests have a 15-second default timeout and a 10 MiB response limit. Redirect destinations are checked against the same URL policy. Non-success status codes, invalid JSON, invalid protocol responses, timeouts, oversized bodies, and unsupported resources have distinct tagged errors.

Kotlin failures implement `AddonClientFailure`, exposing a stable `kind` and
conservative `retryable` flag without retaining configured addon URLs, request
headers, IDs, or response bodies. Library-owned timeouts are ordinary typed
failures; cancelling the calling coroutine still propagates cancellation. HTTP
408, 429, and 5xx responses are retryable, while malformed responses,
unsupported resources, and responses exceeding the configured bound are not.

This library validates protocol data; it does not decide whether a returned stream is legal, trusted, or safe to play. Applications should present P2P warnings and apply their own stream URL policy before playback.

Hostname checks cannot prevent DNS rebinding in a privileged server or desktop process. Applications accepting arbitrary addon URLs should enforce resolved-address policy in their injected transport as well.

## Shared transport and Tauri

All networking uses `@get-air/http`:

```ts
import { makeTauriHttpTransport } from "@get-air/http/tauri"
import { StremioAddon } from "@get-air/stremio-addon-client"

const addon = await StremioAddon.connect(manifestUrl, {
  transport: makeTauriHttpTransport({
    connectTimeout: 10_000,
    maxRedirections: 5,
  }),
})
```

The consuming Tauri app must grant HTTP capabilities for the addon origins it allows.

## Optional cache

Kotlin callers inject the tiny platform-neutral `AddonResponseCache` contract.
The included memory implementation is useful for app sessions; production apps
can back the same interface with their local database:

```kotlin
val addon = connectStremioAddon(
    manifestUrl,
    transport,
    StremioClientOptions(responseCache = MemoryAddonResponseCache()),
)

val freshStreams = addon.streams(
    "movie",
    "tt1254207",
    AddonRequestOptions(bypassCache = true),
)
```

Only successfully normalized resource responses are cached. HTTP `max-age`
takes precedence over the protocol response's `cacheMaxAge`, then the configured
five-minute default is used. `no-store`, `private`, and non-positive TTLs prevent
storage. Cache failures are fail-open, manifests remain uncached, and SHA-256
keys do not expose configured addon URLs or path credentials.

The original TypeScript client accepts any `@get-air/cache` `CacheStore`:

Pass any `@get-air/cache` `CacheStore`. Only validated HTTP response bodies are cached; addon registrations and configuration are never stored. Cache failures are fail-open and can be observed with `onCacheError`.

```ts
import { TauriCacheStore } from "@get-air/cache/tauri"

const cache = await TauriCacheStore.make("air-cache.json")
const addon = await StremioAddon.connect(manifestUrl, { cache })
```

Cache keys contain SHA-256 URL digests rather than configured addon URLs, so path-based addon credentials are not exposed in persistent keys.

## Querying installed addons

The application still owns global/profile addon registration and persistence.
For resource fan-out it can pass an immutable snapshot to the stateless Kotlin
coordinator. The fixed worker count prevents a large profile from opening every
connection at once, while the returned items and failures stay in input-addon
order regardless of completion order:

```kotlin
val result = queryStremioAddons(
    addons = installed.map { installedAddon ->
        StremioAddonBinding(
            AddonInstanceId(installedAddon.localId),
            installedAddon.client,
        )
    },
    query = StremioAddonQuery.Streams("movie", "tt1254207"),
    options = MultiAddonQueryOptions(maxConcurrency = 4),
)
```

Capability filtering happens before a resource call. One addon failure does not
discard healthy results, cancellation stops queued and active work, and both
per-addon and aggregate result counts are capped. `AddonInstanceId` accepts only
a short opaque local identifier; configured URLs and headers must never be used
as identities or diagnostics.

Partial failures also expose their stable kind, retryability, and an HTTP status
when one exists. They never include provider URLs, request headers, media IDs,
or response text.

## Research basis

The implementation follows the [official Stremio addon protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md) and [SDK response definitions](https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api/responses). It also incorporates behavior found in the [official addon client](https://github.com/Stremio/stremio-addon-client), [Harbor](https://github.com/harborstremio/harbor), and [Cremio](https://github.com/itssoap/cremio): capability checks, abortable timeouts, bounded JSON reads, cache-control support, and tolerance for common community-addon response variations.
