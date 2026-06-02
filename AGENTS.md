# Kestra Fastly Plugin

## What

- Provides plugin components under `io.kestra.plugin.fastly`.
- Implements cache invalidation tasks and historical analytics tasks for the Fastly CDN API.

## Why

- Enables Kestra workflows to invalidate Fastly-cached content and query historical analytics as declarative task steps, replacing ad-hoc `curl`/Python workarounds.
- Centralises API token handling, error surfacing, and surrogate-key conventions across all purge and stats operations.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `fastly` — root package; contains `AbstractFastlyTask` (shared HTTP base) and `FastlyClient` (static HTTP helper reusable by triggers)
- `fastly.purge` — cache invalidation tasks
- `fastly.stats` — historical analytics tasks and polling trigger

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.fastly.AbstractFastlyTask` — shared base: `apiToken`, `baseUrl`, `fastlyRequest(...)` (POST purge helper), `fastlyGet(...)` (GET analytics helper), `readStatsEnvelope(...)`
- `io.kestra.plugin.fastly.FastlyClient` — static HTTP execution layer shared by tasks and triggers; handles auth, query string encoding, and non-2xx error handling
- `io.kestra.plugin.fastly.purge.Url` — purge a single URL (`POST /purge/{url}`)
- `io.kestra.plugin.fastly.purge.Key` — purge a single surrogate key on a service (`POST /service/{id}/purge/{key}`)
- `io.kestra.plugin.fastly.purge.Keys` — batch purge surrogate keys (`POST /service/{id}/purge` with JSON body)
- `io.kestra.plugin.fastly.purge.All` — flush entire service cache (`POST /service/{id}/purge_all`, hard-purge only)
- `io.kestra.plugin.fastly.stats.Stats` — fetch per-service or all-services stats (`GET /stats/service/{id}` or `/stats`)
- `io.kestra.plugin.fastly.stats.AggregateStats` — fetch account-wide aggregate stats (`GET /stats/aggregate`)
- `io.kestra.plugin.fastly.stats.Usage` — fetch bandwidth/request usage by region or per-service (`GET /stats/usage` or `/stats/usage_by_service`)
- `io.kestra.plugin.fastly.stats.MonthToDateUsage` — fetch current billing month totals (`GET /stats/usage_by_month`)
- `io.kestra.plugin.fastly.stats.StatsTrigger` — polling trigger that fires when a Fastly field metric crosses a threshold

### Project Structure

```
plugin-fastly/
├── src/main/java/io/kestra/plugin/fastly/
│   ├── AbstractFastlyTask.java
│   ├── FastlyClient.java
│   ├── package-info.java
│   ├── purge/
│   │   ├── Url.java
│   │   ├── Key.java
│   │   ├── Keys.java
│   │   ├── All.java
│   │   └── package-info.java
│   └── stats/
│       ├── Stats.java
│       ├── AggregateStats.java
│       ├── Usage.java
│       ├── MonthToDateUsage.java
│       ├── StatsTrigger.java
│       └── package-info.java
├── src/test/java/io/kestra/plugin/fastly/
│   ├── purge/
│   │   ├── UrlTest.java
│   │   ├── KeyTest.java
│   │   ├── KeysTest.java
│   │   └── AllTest.java
│   └── stats/
│       ├── StatsTest.java
│       ├── AggregateStatsTest.java
│       ├── UsageTest.java
│       ├── MonthToDateUsageTest.java
│       └── StatsTriggerTest.java
├── src/main/resources/
│   ├── metadata/
│   │   ├── purge.yaml
│   │   └── stats.yaml
│   └── icons/
│       ├── io.kestra.plugin.fastly.purge.svg
│       └── io.kestra.plugin.fastly.stats.svg
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
