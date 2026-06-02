package io.kestra.plugin.fastly.stats;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.fastly.AbstractFastlyTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pulls historical Fastly stats for one service or all services.
 *
 * <p>The /stats family returns up to 200 data points per request. For longer time windows the
 * caller must paginate by splitting the window into successive from/to chunks. This task does
 * <em>not</em> silently truncate — it returns exactly what Fastly returns for the given window.
 *
 * <p>{@code hit_ratio} in the data points is a 0-1 float (e.g. {@code 0.92} means 92 % cache
 * hit rate). Surface it as-is; do not multiply by 100.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch Fastly service statistics",
    description = """
        Retrieves historical analytics for a single Fastly service or all services.
        When `serviceId` is provided the request targets `/stats/service/{serviceId}`;
        otherwise `/stats` is called (all services, optionally filtered by `services`).

        The Fastly API returns up to 200 data points per call. For longer time windows,
        paginate by issuing successive calls with non-overlapping `from`/`to` values.
        `hit_ratio` in the response is a 0–1 float (e.g. `0.92` = 92 % cache hit rate).
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch daily stats for a service over the past week",
            full = true,
            code = """
                id: fastly_daily_stats
                namespace: company.analytics

                tasks:
                  - id: fetch_stats
                    type: io.kestra.plugin.fastly.stats.Stats
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    serviceId: "{{ secret('FASTLY_SERVICE_ID') }}"
                    from: "7 days ago"
                    to: "now"
                    by: "day"
                """
        ),
        @Example(
            title = "Fetch per-service window stats filtered by region",
            full = true,
            code = """
                id: fastly_usa_stats
                namespace: company.analytics

                tasks:
                  - id: fetch_usa_stats
                    type: io.kestra.plugin.fastly.stats.Stats
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    serviceId: "{{ secret('FASTLY_SERVICE_ID') }}"
                    from: "1 hour ago"
                    to: "now"
                    by: "minute"
                    region: "usa"
                """
        )
    }
)
public class Stats extends AbstractFastlyTask implements RunnableTask<Stats.Output> {

    @Schema(
        title = "Service ID",
        description = """
            When set, statistics are fetched for this specific service (`/stats/service/{serviceId}`).
            When omitted, statistics for all services are returned (`/stats`).
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serviceId;

    @Schema(
        title = "Start of the time window",
        description = """
            Beginning of the reporting window. Accepts Unix timestamps (seconds) or
            Fastly natural-language strings such as `"1 hour ago"` or `"yesterday"`.
            """
    )
    @PluginProperty(group = "processing")
    private Property<String> from;

    @Schema(
        title = "End of the time window",
        description = """
            End of the reporting window. Accepts Unix timestamps (seconds) or
            Fastly natural-language strings such as `"now"`.
            """
    )
    @PluginProperty(group = "processing")
    private Property<String> to;

    @Schema(
        title = "Aggregation granularity",
        description = "Time bucket size for the returned data points. One of `minute`, `hour`, or `day`."
    )
    @PluginProperty(group = "processing")
    private Property<String> by;

    @Schema(
        title = "Region filter",
        description = """
            Restricts results to a specific POP region, e.g. `usa`, `europe`, `asia`.
            When omitted, global aggregate data is returned.
            """
    )
    @PluginProperty(group = "processing")
    private Property<String> region;

    @Schema(
        title = "Service filter (comma-separated IDs)",
        description = """
            Comma-separated list of service IDs to include when fetching all-services stats.
            Only applicable when `serviceId` is not set. Ignored otherwise.
            """
    )
    @PluginProperty(group = "processing")
    private Property<String> services;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rServiceId = runContext.render(serviceId).as(String.class).orElse(null);
        var rFrom = runContext.render(from).as(String.class).orElse(null);
        var rTo = runContext.render(to).as(String.class).orElse(null);
        var rBy = runContext.render(by).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rServices = runContext.render(services).as(String.class).orElse(null);

        String path;
        Map<String, String> query = new LinkedHashMap<>();

        if (rServiceId != null && !rServiceId.isBlank()) {
            path = "/stats/service/" + encodePathSegment(rServiceId);
            logger.info("Fetching stats for service '{}' (from={}, to={}, by={}, region={})",
                rServiceId, rFrom, rTo, rBy, rRegion);
        } else {
            path = "/stats";
            if (rServices != null) {
                query.put("services", rServices);
            }
            logger.info("Fetching all-services stats (from={}, to={}, by={}, region={})", rFrom, rTo, rBy, rRegion);
        }

        query.put("from", rFrom);
        query.put("to", rTo);
        query.put("by", rBy);
        query.put("region", rRegion);

        var response = fastlyGet(runContext, path, query);
        var envelope = readStatsEnvelope(response);

        return Output.builder()
            .status(envelope.status())
            .meta(envelope.meta())
            .data(asMap(envelope.data()))
            .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        return obj instanceof Map<?, ?> ? (Map<String, Object>) obj : null;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Response status",
            description = "Status field from the Fastly API envelope, typically `ok`."
        )
        private final String status;

        @Schema(
            title = "Response metadata",
            description = "Metadata returned by Fastly, including pagination info and request parameters."
        )
        private final Map<String, Object> meta;

        @Schema(
            title = "Stats data",
            description = """
                Analytics data returned by Fastly. Shape varies by endpoint:
                `/stats/service/{id}` returns an object keyed by service ID (each value an array of
                time-bucketed data points); `/stats` returns an object keyed by service ID across all services.
                """
        )
        private final Map<String, Object> data;
    }
}
