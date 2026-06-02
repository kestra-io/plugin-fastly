# Kestra Fastly Plugin

## What

- Provides plugin components under `io.kestra.plugin.fastly`.
- Implements cache invalidation tasks for the Fastly CDN API.

## Why

- Enables Kestra workflows to invalidate Fastly-cached content as a declarative task step, replacing ad-hoc `curl`/Python workarounds.
- Centralises API token handling, error surfacing, and surrogate-key conventions across all purge operations.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `fastly` — root package; contains `AbstractFastlyTask` (shared HTTP base)
- `fastly.purge` — cache invalidation tasks

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.fastly.AbstractFastlyTask` — shared base: `apiToken`, `baseUrl`, `fastlyRequest(...)` helper
- `io.kestra.plugin.fastly.purge.Url` — purge a single URL (`POST /purge/{url}`)
- `io.kestra.plugin.fastly.purge.Key` — purge a single surrogate key on a service (`POST /service/{id}/purge/{key}`)
- `io.kestra.plugin.fastly.purge.Keys` — batch purge surrogate keys (`POST /service/{id}/purge` with JSON body)
- `io.kestra.plugin.fastly.purge.All` — flush entire service cache (`POST /service/{id}/purge_all`, hard-purge only)

### Project Structure

```
plugin-fastly/
├── src/main/java/io/kestra/plugin/fastly/
│   ├── AbstractFastlyTask.java
│   ├── package-info.java
│   └── purge/
│       ├── Url.java
│       ├── Key.java
│       ├── Keys.java
│       ├── All.java
│       └── package-info.java
├── src/test/java/io/kestra/plugin/fastly/purge/
│   ├── UrlTest.java
│   ├── KeyTest.java
│   ├── KeysTest.java
│   └── AllTest.java
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
