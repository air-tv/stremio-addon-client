# Air Stremio addon client repository guidance

- This repository is being ported from TypeScript to one Kotlin Multiplatform library. Keep it independently buildable and publishable; never turn it into a monorepo.
- Treat the TypeScript implementation and tests as the behavioral reference until every behavior has a Kotlin contract test. Remove reference code only after parity is proven.
- The library consumes remote Stremio addons. It must never execute addon code or maintain installed-addon/configuration storage.
- Public async APIs are suspend functions. Do not add callback, Promise, Effect, blocking, or platform-specific duplicate façades.
- Keep platform types out of `commonMain`. Use small transport/cache interfaces and constructor injection; no DI framework.
- Treat addon URLs and responses as untrusted. Preserve URL policy, timeout, response-size, status, JSON, schema, redirect, and capability checks.
- Cache failures remain fail-open. Never place raw configured addon URLs in cache keys or error text.
- Use immutable serializable models and validate normalized values at protocol boundaries.
- JDK 17 is canonical. Run `./gradlew jvmTest` for fast checks and the relevant host target tests before committing.
- GitHub Actions owns CI and release publishing. Never put repository or package tokens in the worktree.
