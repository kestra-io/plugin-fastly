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
    title = "Purge a single surrogate key from a Fastly service",
    description = """
        Sends a soft or hard purge request for a single surrogate key on a specific Fastly service.
        Surrogate keys are set on responses via the `Surrogate-Key` response header.
        Use `Keys` to invalidate multiple keys in a single API call.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Purge a surrogate key after a content update",
            full = true,
            code = """
                id: invalidate_product_key
                namespace: company.commerce

                tasks:
                  - id: purge_product
                    type: io.kestra.plugin.fastly.purge.Key
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    serviceId: "{{ secret('FASTLY_SERVICE_ID') }}"
                    surrogateKey: "product-42"
                    soft: true
                """
        )
    }
)
public class Key extends AbstractFastlyTask implements RunnableTask<Key.Output> {

    @Schema(
        title = "Service ID",
        description = "The Fastly service identifier to purge against."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> serviceId;

    @Schema(
        title = "Surrogate key",
        description = "The surrogate key to purge from the Fastly service cache."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> surrogateKey;

    @Schema(
        title = "Soft purge",
        description = """
            When true, marks cached content as stale instead of removing it immediately.
            Stale content is served while Fastly re-fetches fresh content from the origin.
            Defaults to false (hard purge).
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> soft = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rServiceId = renderRequired(runContext, serviceId, "serviceId");
        var rSurrogateKey = renderRequired(runContext, surrogateKey, "surrogateKey");
        var rSoft = runContext.render(soft).as(Boolean.class).orElse(false);

        logger.info("Purging surrogate key '{}' on service '{}' (soft={})", rSurrogateKey, rServiceId, rSoft);

        var path = "/service/" + encodePathSegment(rServiceId) + "/purge/" + encodePathSegment(rSurrogateKey);
        var response = fastlyRequest(runContext, path, rSoft, null);

        var result = readPurgeResponse(response);
        return Output.builder()
            .status(result.status())
            .id(result.id())
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

        @Schema(
            title = "Purge ID",
            description = "Unique identifier of the purge request, useful for traceability."
        )
        private final String id;
    }
}
