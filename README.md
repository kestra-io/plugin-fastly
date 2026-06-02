# Kestra Fastly Plugin

Kestra plugin for the [Fastly CDN API](https://developer.fastly.com/reference/api/). Provides declarative task steps for cache invalidation and historical analytics, replacing ad-hoc curl/Python workarounds in your workflows.

## Tasks

### Cache Invalidation (`io.kestra.plugin.fastly.purge`)

| Task | Description |
|---|---|
| `Url` | Purge a single URL from the Fastly cache (`POST /purge/{url}`). |
| `Key` | Purge all objects tagged with a single surrogate key on a service (`POST /service/{id}/purge/{key}`). |
| `Keys` | Batch-purge a list of surrogate keys in one request (`POST /service/{id}/purge` with a JSON body). |
| `All` | Flush the entire cache for a service (`POST /service/{id}/purge_all`). Hard-purge only. |

#### Example — purge a URL

```yaml
id: purge_homepage
namespace: company.ops

tasks:
  - id: purge
    type: io.kestra.plugin.fastly.purge.Url
    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
    url: "https://www.example.com/"
```

#### Example — batch-purge surrogate keys

```yaml
id: purge_product_keys
namespace: company.ops

tasks:
  - id: purge_keys
    type: io.kestra.plugin.fastly.purge.Keys
    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
    serviceId: "{{ secret('FASTLY_SERVICE_ID') }}"
    surrogateKeys:
      - product-42
      - product-43
```

### Analytics (`io.kestra.plugin.fastly.stats`)

| Task | Description |
|---|---|
| `Stats` | Fetch per-service or all-services historical stats (`/stats/service/{id}` or `/stats`). Output `data` is always a map keyed by service id. |
| `AggregateStats` | Fetch account-wide aggregate stats across all services (`/stats/aggregate`). |
| `Usage` | Fetch bandwidth and request usage by region or per-service (`/stats/usage` or `/stats/usage_by_service`). |
| `MonthToDateUsage` | Fetch running totals for the current billing month (`/stats/usage_by_month`). No time-window parameters. |

All stats tasks return `status`, `meta`, and `data` outputs from the Fastly envelope. The API returns at most 200 data points per request; for longer windows paginate by issuing successive calls with non-overlapping `from`/`to` values.

#### Example — fetch hourly stats for a service

```yaml
id: fastly_daily_stats
namespace: company.analytics

tasks:
  - id: fetch_stats
    type: io.kestra.plugin.fastly.stats.Stats
    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
    serviceId: "{{ secret('FASTLY_SERVICE_ID') }}"
    from: "24 hours ago"
    to: "now"
    by: "hour"
```

#### Example — check month-to-date bandwidth

```yaml
id: fastly_month_to_date_usage
namespace: company.analytics

tasks:
  - id: fetch_mtd_usage
    type: io.kestra.plugin.fastly.stats.MonthToDateUsage
    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
```

### Trigger

| Trigger | Description |
|---|---|
| `StatsTrigger` | Polls `/stats/service/{serviceId}/field/{field}` on a configurable interval and fires an execution when the aggregated field value crosses a threshold. |

#### Example — alert on 5xx spike

```yaml
id: fastly_error_alert
namespace: company.ops

triggers:
  - id: high_error_rate
    type: io.kestra.plugin.fastly.stats.StatsTrigger
    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
    serviceId: "{{ secret('FASTLY_SERVICE_ID') }}"
    field: "status_5xx"
    threshold: 50
    comparator: GREATER_THAN
    window: "PT1H"
    interval: PT5M

tasks:
  - id: alert
    type: io.kestra.plugin.core.log.Log
    message: "5xx spike: {{ trigger.value }} errors (threshold: {{ trigger.threshold }})"
```

## Authentication

All tasks and the trigger require a Fastly API token set via `apiToken`. Create one in the Fastly console under **Account > API tokens**. Required scopes:

- URL and surrogate-key purges: `purge_select`
- Purge-all: `purge_all`
- Stats endpoints: `global:read`

Always pass the token via `{{ secret('FASTLY_API_TOKEN') }}` to avoid exposing it in flow definitions.

## License

Apache 2.0 © [Kestra Technologies](https://kestra.io)
