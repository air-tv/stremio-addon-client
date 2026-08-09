# `@get-air/stremio-addon-client` repository guidance

- This repository contains exactly one publishable package. Never turn it into a monorepo.
- The package consumes remote Stremio addons. It must never execute addon code or maintain installed-addon/configuration storage.
- Keep the root API Promise-based and plain-TypeScript. Keep schemas, typed error channels, and Effect-returning APIs under `/effect`.
- Both surfaces must delegate to the same Effect implementation.
- Use `@get-air/http` for all networking and `@get-air/cache` for optional response caching.
- Treat addon URLs and responses as untrusted: preserve URL policy, timeout, response-size, status, JSON, and schema checks.
- Cache failures remain fail-open. Never place raw configured addon URLs in cache keys.
- Follow current official Stremio protocol documentation and the local `effect-best-practices` skill.
- `act` is installed locally. Run `pnpm ci:act` before pushing workflow or build changes.
- Maintain independent CI and npm trusted-publishing workflows with OIDC provenance.
