package io.kestra.plugin.fastly.stats;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.fastly.AbstractFastlyTask;
import io.kestra.plugin.fastly.FastlyClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch Fastly month-to-date usage",
    description = """
        Retrieves bandwidth and request counts accumulated since the start of the current billing
        month via `/stats/usage_by_month`. No time-window parameters are accepted — Fastly always
        returns the current month's running totals. The response is keyed by region.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Check month-to-date bandwidth usage",
            full = true,
            code = """
                id: fastly_month_to_date_usage
                namespace: company.analytics

                tasks:
                  - id: fetch_mtd_usage
                    type: io.kestra.plugin.fastly.stats.MonthToDateUsage
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                """
        )
    }
)
public class MonthToDateUsage extends AbstractFastlyTask implements RunnableTask<MonthToDateUsage.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        runContext.logger().info("Fetching month-to-date usage");

        var response = fastlyGet(runContext, "/stats/usage_by_month", null);
        var envelope = readStatsEnvelope(response);

        return Output.builder()
            .status(envelope.status())
            .meta(envelope.meta())
            .data(FastlyClient.asMap(envelope.data()))
            .build();
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
            title = "Month-to-date usage data",
            description = "Usage totals since the start of the current billing month, keyed by region."
        )
        private final Map<String, Object> data;
    }
}
