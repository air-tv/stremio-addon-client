# Air Stremio addon client repository guidance

- This is one independently buildable and publishable Kotlin Multiplatform library. Keep it Kotlin-only and do not turn it into a monorepo.
- The historical [`get-air/stremio-addon-client`](https://github.com/get-air/stremio-addon-client) implementation is a behavioral reference only. Preserve required behavior through Kotlin contract tests and fixtures.
- The library consumes remote Stremio addons. It must never execute addon code or maintain installed-addon/configuration storage.
- Public async APIs are suspend functions. Do not add callback, blocking, or platform-specific duplicate facades.
- Keep platform types out of `commonMain`. Use small transport/cache interfaces and constructor injection; no DI framework.
- Treat addon URLs and responses as untrusted. Preserve URL policy, timeout, response-size, status, JSON, schema, redirect, and capability checks.
- A library-owned deadline is an `AddonTimeoutException`, never structural coroutine cancellation. Parent/caller cancellation must propagate unchanged so bounded multi-addon work remains correct.
- Expected failures expose only stable redacted kind/retryability/status metadata. Never retain provider URLs, headers, IDs, or response bodies in exceptions or aggregate query failures.
- Cache failures remain fail-open. Cache only validated resource payloads, obey HTTP/protocol TTLs and private/no-store, and never place raw configured addon URLs in cache keys or error text.
- Use immutable serializable models and validate normalized values at protocol boundaries.
- JDK 17 is canonical. Run `./gradlew jvmTest` for fast checks and the relevant host target tests before committing.
- GitHub Actions owns CI and release publishing. Never put repository or package tokens in the worktree.
- Live integration is opt-in through `scripts/test-live-integration.sh` and `~/.config/air-tv/integration.env`. Redirects must be manually bounded and revalidated; cross-origin configured headers are dropped.
