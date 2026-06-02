# How to use the Fastly plugin

Purge cached content and query historical analytics from Fastly CDN services in Kestra flows.

## Authentication

All tasks require `apiToken` (your Fastly API token, required). The required token scope depends on the operation: `purge.Url`, `purge.Key`, and `purge.Keys` need `purge_select`; `purge.All` needs `purge_all`; stats tasks need `global:read`. Optionally set `baseUrl` (default `https://api.fastly.com`) and `options` for advanced HTTP settings. Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and apply connection properties globally with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

## Tasks

`purge.Url` purges a single URL across all services that cache it — set `url` (required). Optionally set `soft` (default `false`; when `true`, marks content as stale rather than removing it immediately). Outputs `status` and `id`.

`purge.Key` purges a single surrogate key from a specific service — set `serviceId` and `surrogateKey` (both required). Optionally set `soft` (default `false`). Outputs `status` and `id`.

`purge.Keys` batch-purges multiple surrogate keys from a specific service in a single API call — set `serviceId` and `surrogateKeys` (list of keys, both required). Prefer this over calling `purge.Key` in a loop. Optionally set `soft` (default `false`). Outputs `purgeIds` (a map of surrogate key to purge ID).

`purge.All` flushes the entire cache for a service — set `serviceId` (required). Hard purge only; soft purge is not supported by this endpoint. Requires the `purge_all` scope on the API token. Outputs `status`.

`stats.Stats` fetches historical analytics for a single service or all services — optionally set `serviceId` (when set, queries that service only; when omitted, queries all services). Optionally set `from` and `to` (Unix epoch seconds or Fastly natural-language strings such as `"1 hour ago"` or `"now"`), `by` (`minute`, `hour`, or `day`), `region`, and `services` (comma-separated service IDs, only applicable when `serviceId` is omitted). Outputs `status`, `meta`, and `data` (keyed by service ID).

`stats.AggregateStats` fetches account-wide aggregate analytics — optionally set `from`, `to`, `by`, and `region`. Outputs `status`, `meta`, and `data` (list of time-bucketed data points).

`stats.Usage` fetches bandwidth and request usage — optionally set `from`, `to`, and `byService` (default `false`; when `true`, returns a per-service breakdown via `/stats/usage_by_service`; when `false`, returns regional aggregates via `/stats/usage`). Outputs `status`, `meta`, and `data`.

`stats.MonthToDateUsage` fetches bandwidth and request totals since the start of the current billing month — no time-window parameters are accepted. Outputs `status`, `meta`, and `data`.

## Triggers

`stats.StatsTrigger` fires when a Fastly field metric crosses a threshold — set `apiToken`, `serviceId`, `field` (e.g. `status_5xx`, `hit_ratio`, `bandwidth`), and `threshold` (all required). Optionally set `comparator` (default `GREATER_THAN`; also `GREATER_THAN_OR_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, or `EQUAL`), `window` (observation window, default `PT1H`), `by` (granularity, default `minute`), and `interval` (polling interval, default `PT1M`). Field values are summed across all data points in the window before comparison. Outputs `value`, `field`, and `threshold`.
