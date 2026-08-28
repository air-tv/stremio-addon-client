# Air Stremio Addon Client

One Kotlin Multiplatform client for consuming remote Stremio addon protocol
endpoints on Android, JVM, Linux, Windows, macOS, iOS, JavaScript, and Wasm.
The library validates addon data; applications remain responsible for addon
registration, profile policy, stream trust, and playback.

## Gradle dependency

Releases are published as `com.getair:stremio-addon-client:<version>` through
GitHub Packages. GitHub's Maven registry requires authentication even for a
public package. Keep a GitHub username and classic `read:packages` token outside
the project:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/air-tv/stremio-addon-client")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
```

```kotlin
// build.gradle.kts
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("com.getair:stremio-addon-client:1.0.0")
    }
}
```

In GitHub Actions, grant `packages: read` and allow the consuming repository to
read the package. Never commit package credentials.

## Usage

```kotlin
val transport = KtorAddonHttpTransport(HttpClient())
val addon = connectStremioAddon(
    manifestUrl = "https://example.com/manifest.json",
    transport = transport,
    options = StremioClientOptions(
        responseCache = MemoryAddonResponseCache(),
    ),
)

val manifest = addon.manifest()
val streams = addon.streams("movie", "tt1254207").streams
```

Callers may instead inject any `AddonHttpTransport`. The public API is suspend
only; cancellation of the caller propagates without being converted into an
ordinary addon failure.

## Protocol and safety

- Manifest discovery from base, `manifest.json`, and `stremio://` URLs.
- Catalog, metadata, stream, subtitle, and addon-catalog resources.
- Capability filtering before resource requests.
- Bounded timeouts, redirects, response bytes, and aggregate query results.
- Runtime normalization and validation of untrusted addon JSON.
- HTTPS/private-network/origin policy with explicit trusted-local overrides.
- Redacted typed failures that never retain addon URLs, headers, IDs, or bodies.
- Optional fail-open response caching with HTTP/protocol TTL handling and
  SHA-256 keys rather than configured URLs.

Applications that query multiple installed addons can use
`queryStremioAddons`. It runs a fixed worker count, preserves addon order,
isolates failures, skips unsupported resources, and enforces per-addon and
aggregate result limits. Installed-addon persistence remains application-owned.

## Verification

```bash
./gradlew jvmTest jsNodeTest wasmJsNodeTest --max-workers=2
```

Platform CI additionally compiles/tests Android and native targets and verifies
each Maven publication in an isolated repository. Live integration is opt-in:

```bash
./scripts/test-live-integration.sh
```

It reads local configuration from `~/.config/air-tv/integration.env`, never from
tracked files, and redacts configured endpoints and response data.

## Historical reference

The original TypeScript implementation remains available in the historical
[`get-air/stremio-addon-client`](https://github.com/get-air/stremio-addon-client)
repository as a behavioral reference. It is not shipped from this Kotlin
repository.

[![CI](https://github.com/air-tv/stremio-addon-client/actions/workflows/ci.yml/badge.svg)](https://github.com/air-tv/stremio-addon-client/actions/workflows/ci.yml)
