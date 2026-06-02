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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch Fastly bandwidth and request usage",
    description = """
        Retrieves bandwidth and request usage from `/stats/usage` (aggregated by region) or
        `/stats/usage_by_service` (per-service breakdown) depending on `byService`.
        Usage data is keyed by region in the response.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch usage aggregated by region for the past day",
            full = true,
            code = """
                id: fastly_usage
                namespace: company.analytics

                tasks:
                  - id: fetch_usage
                    type: io.kestra.plugin.fastly.stats.Usage
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    from: "yesterday"
                    to: "now"
                """
        ),
        @Example(
            title = "Fetch per-service usage breakdown",
            full = true,
            code = """
                id: fastly_usage_by_service
                namespace: company.analytics

                tasks:
                  - id: fetch_service_usage
                    type: io.kestra.plugin.fastly.stats.Usage
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    from: "1 week ago"
                    to: "now"
                    byService: true
                """
        )
    }
)
public class Usage extends AbstractFastlyTask implements RunnableTask<Usage.Output> {

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
        title = "Break down usage per service",
        description = """
            When `true`, calls `/stats/usage_by_service` which returns usage broken down by service.
            When `false` (default), calls `/stats/usage` which returns usage aggregated by region.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> byService = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rFrom = runContext.render(from).as(String.class).orElse(null);
        var rTo = runContext.render(to).as(String.class).orElse(null);
        var rByService = runContext.render(byService).as(Boolean.class).orElse(false);

        var path = rByService ? "/stats/usage_by_service" : "/stats/usage";
        logger.info("Fetching usage via {} (from={}, to={})", path, rFrom, rTo);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", rFrom);
        query.put("to", rTo);

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
            title = "Usage data",
            description = """
                Usage data keyed by region. When `byService` is `true`, each region value contains a
                per-service breakdown. Otherwise, each region holds aggregated request and bandwidth counts.
                """
        )
        private final Map<String, Object> data;
    }
}
