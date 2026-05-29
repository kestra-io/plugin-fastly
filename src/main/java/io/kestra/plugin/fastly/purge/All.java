package io.kestra.plugin.fastly.purge;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.fastly.AbstractFastlyTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Purge all cached content from a Fastly service",
    description = """
        Immediately removes all cached content for a given Fastly service (hard purge only).
        Soft purge is not supported by the Fastly `purge_all` endpoint.
        The API token must have the `purge_all` scope; a token with only `purge_select` will receive a 403.
        Use this task with care — it invalidates the entire service cache and increases origin load.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Scheduled global cache flush on a non-production service",
            full = true,
            code = """
                id: nightly_staging_flush
                namespace: company.platform

                triggers:
                  - id: nightly
                    type: io.kestra.plugin.core.trigger.Schedule
                    cron: "0 3 * * *"

                tasks:
                  - id: flush_staging_cache
                    type: io.kestra.plugin.fastly.purge.All
                    apiToken: "{{ secret('FASTLY_STAGING_TOKEN') }}"
                    serviceId: "{{ secret('FASTLY_STAGING_SERVICE_ID') }}"
                """
        )
    }
)
public class All extends AbstractFastlyTask implements RunnableTask<All.Output> {

    @Schema(
        title = "Service ID",
        description = "The Fastly service identifier whose entire cache will be purged."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> serviceId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rServiceId = renderRequired(runContext, serviceId, "serviceId");

        logger.info("Purging all cached content for service '{}' (hard purge only)", rServiceId);

        var path = "/service/" + encodePathSegment(rServiceId) + "/purge_all";
        var response = fastlyRequest(runContext, path, false, null);

        var result = readPurgeResponse(response);
        return Output.builder()
            .status(result.status())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Purge status",
            description = "Status returned by Fastly, typically 'ok'."
        )
        private final String status;
    }
}
