package io.kestra.plugin.fastly.stats;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.fastly.FastlyClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Polls Fastly field stats for a service and fires an execution when the aggregated value
 * of the chosen field crosses a threshold.
 *
 * <p>The field values returned by `/stats/service/{serviceId}/field/{field}` are SUMmed across
 * all data points in the window. This is appropriate for counters (requests, errors, status_5xx)
 * but gives a less intuitive number for ratio fields like {@code hit_ratio}. For ratios,
 * prefer a small {@code window} (e.g. {@code PT5M}) so that only one or a few points are summed.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger when a Fastly field metric crosses a threshold",
    description = """
        Polls `/stats/service/{serviceId}/field/{field}` on the configured interval, sums all
        numeric values of the chosen field across data points in the `window`, and fires an
        execution when the observed value matches the threshold according to the `comparator`.

        Useful for alerting on error spikes (e.g. `status_5xx > 100`), hit-ratio drops
        (e.g. `hit_ratio < 0.8`), or bandwidth surges.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Alert when 5xx errors exceed 50 in the past hour",
            full = true,
            code = """
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
                    message: "5xx spike detected: {{ trigger.value }} errors (threshold: {{ trigger.threshold }})"
                """
        )
    }
)
public class StatsTrigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<StatsTrigger.Output> {

    @Schema(
        title = "Fastly API token",
        description = "Your Fastly API token. The token must have `global:read` scope to access stats endpoints."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    private Property<String> apiToken;

    @Schema(
        title = "Fastly API base URL",
        description = "Base URL for the Fastly API. Override this only for testing."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue("https://api.fastly.com");

    @Schema(
        title = "HTTP client options",
        description = "Optional advanced HTTP settings such as timeouts or proxy configuration."
    )
    @PluginProperty(group = "advanced")
    private HttpConfiguration options;

    @Schema(
        title = "Service ID",
        description = "The Fastly service to monitor."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> serviceId;

    @Schema(
        title = "Field name",
        description = "Fastly stats field to aggregate and compare, e.g. `status_5xx`, `errors`, `hit_ratio`, `bandwidth`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> field;

    @Schema(
        title = "Threshold value",
        description = "The numeric value to compare the observed field sum against."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Double> threshold;

    @Schema(
        title = "Comparator",
        description = """
            Comparison operator applied between the observed value and `threshold`.
            Available values: `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `EQUAL`.
            Defaults to `GREATER_THAN`.
            """
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Comparator> comparator = Property.ofValue(Comparator.GREATER_THAN);

    @Schema(
        title = "Observation window",
        description = "How far back to look for data points. Data points within [now - window, now] are summed."
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Duration> window = Property.ofValue(Duration.ofHours(1));

    @Schema(
        title = "Interval between polling.",
        description = """
            The interval between 2 different polls of schedule, this can avoid to overload the remote system with too many calls. For most of the triggers that depend on external systems, a minimal interval must be at least PT30S.
            See [ISO_8601 Durations](https://en.wikipedia.org/wiki/ISO_8601#Durations) for more information of available interval values."""
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Duration interval = Duration.ofMinutes(1);

    @Override
    public Duration getInterval() {
        return interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rToken = FastlyClient.renderRequired(runContext, apiToken, "apiToken");
        var rBaseUrl = FastlyClient.renderBaseUrl(runContext, baseUrl);
        var rServiceId = FastlyClient.renderRequired(runContext, serviceId, "serviceId");
        var rField = FastlyClient.renderRequired(runContext, field, "field");
        var rThreshold = runContext.render(threshold).as(Double.class)
            .orElseThrow(() -> new IllegalArgumentException("threshold is required"));
        var rComparator = runContext.render(comparator).as(Comparator.class)
            .orElse(Comparator.GREATER_THAN);
        var rWindow = runContext.render(window).as(Duration.class).orElse(Duration.ofHours(1));

        var now = Instant.now();
        var fromEpoch = String.valueOf(now.minus(rWindow).getEpochSecond());
        var toEpoch = String.valueOf(now.getEpochSecond());

        var path = "/stats/service/" + FastlyClient.encodePathSegment(rServiceId) + "/field/" + FastlyClient.encodePathSegment(rField);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", fromEpoch);
        query.put("to", toEpoch);
        query.put("by", "minute");

        logger.debug("Polling Fastly field '{}' for service '{}' (window={})", rField, rServiceId, rWindow);

        try {
            var response = FastlyClient.request(
                runContext, rToken, rBaseUrl, options, "GET", path, query, null, null
            );

            var observed = sumField(response.getBody(), rServiceId, rField);
            logger.info("Fastly field '{}' observed={} threshold={} comparator={}", rField, observed, rThreshold, rComparator);

            if (!matches(observed, rThreshold, rComparator)) {
                return Optional.empty();
            }

            var output = Output.builder()
                .value(observed)
                .field(rField)
                .threshold(rThreshold)
                .build();

            return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Fastly API", e);
        }
    }

    /**
     * Sums the numeric values of {@code fieldName} across all data points returned for
     * {@code serviceId}. The stats response shape for this endpoint is:
     * {@code {"status":"ok","meta":{...},"data":{"<serviceId>":[{"fieldName":N,...},...]}}}.
     */
    private static double sumField(String body, String serviceId, String fieldName) throws Exception {
        if (body == null || body.isBlank()) {
            return 0.0;
        }
        var envelope = FastlyClient.MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
        var rawData = envelope.get("data");
        if (!(rawData instanceof Map<?, ?> dataMap)) {
            return 0.0;
        }
        var points = dataMap.get(serviceId);
        if (!(points instanceof List<?> pointList)) {
            return 0.0;
        }
        double sum = 0.0;
        for (var point : pointList) {
            if (point instanceof Map<?, ?> pointMap) {
                var raw = pointMap.get(fieldName);
                if (raw instanceof Number n) {
                    sum += n.doubleValue();
                }
            }
        }
        return sum;
    }

    private static boolean matches(double observed, double threshold, Comparator comparator) {
        return switch (comparator) {
            case GREATER_THAN -> observed > threshold;
            case GREATER_THAN_OR_EQUAL -> observed >= threshold;
            case LESS_THAN -> observed < threshold;
            case LESS_THAN_OR_EQUAL -> observed <= threshold;
            case EQUAL -> Double.compare(observed, threshold) == 0;
        };
    }

    public enum Comparator {
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        EQUAL
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Observed value",
            description = "The sum of the monitored field across all data points in the observation window."
        )
        private final Double value;

        @Schema(
            title = "Field name",
            description = "The Fastly stats field that was monitored."
        )
        private final String field;

        @Schema(
            title = "Threshold value",
            description = "The threshold that was configured and matched."
        )
        private final Double threshold;
    }
}
