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
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch aggregated Fastly statistics across all services",
    description = """
        Retrieves aggregate analytics from `/stats/aggregate`, combining metrics across all services
        for the given time window and granularity. Useful for an account-level view of traffic,
        bandwidth, cache performance, and error rates.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch hourly aggregate stats for the past 24 hours",
            full = true,
            code = """
                id: fastly_aggregate_stats
                namespace: company.analytics

                tasks:
                  - id: fetch_aggregate
                    type: io.kestra.plugin.fastly.stats.AggregateStats
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    from: "24 hours ago"
                    to: "now"
                    by: "hour"
                """
        )
    }
)
public class AggregateStats extends AbstractFastlyTask implements RunnableTask<AggregateStats.Output> {

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

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rFrom = runContext.render(from).as(String.class).orElse(null);
        var rTo = runContext.render(to).as(String.class).orElse(null);
        var rBy = runContext.render(by).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);

        logger.info("Fetching aggregate stats (from={}, to={}, by={}, region={})", rFrom, rTo, rBy, rRegion);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", rFrom);
        query.put("to", rTo);
        query.put("by", rBy);
        query.put("region", rRegion);

        var response = fastlyGet(runContext, "/stats/aggregate", query);
        var envelope = readStatsEnvelope(response);

        return Output.builder()
            .status(envelope.status())
            .meta(envelope.meta())
            .data(asList(envelope.data()))
            .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object obj) {
        return obj instanceof List<?> ? (List<Object>) obj : null;
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
            title = "Aggregate stats data",
            description = "Array of time-bucketed aggregate data points across all services."
        )
        private final List<Object> data;
    }
}
