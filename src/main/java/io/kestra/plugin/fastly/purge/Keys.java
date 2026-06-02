package io.kestra.plugin.fastly.purge;

import io.kestra.core.http.HttpRequest;
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

import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Batch purge surrogate keys from a Fastly service",
    description = """
        Sends a single API call to purge multiple surrogate keys from a specific Fastly service.
        Prefer this over calling `Key` in a loop — it is more efficient and counts as one API call.
        The response contains a per-key purge ID map for traceability.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Batch purge surrogate keys for a content update",
            full = true,
            code = """
                id: invalidate_articles
                namespace: company.cms

                inputs:
                  - id: article_ids
                    type: ARRAY
                    itemType: STRING

                tasks:
                  - id: purge_articles
                    type: io.kestra.plugin.fastly.purge.Keys
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    serviceId: "{{ secret('FASTLY_SERVICE_ID') }}"
                    surrogateKeys: "{{ inputs.article_ids }}"
                    soft: true

                  - id: log_result
                    type: io.kestra.plugin.core.log.Log
                    message: "Soft-purged {{ inputs.article_ids | length }} articles"
                """
        )
    }
)
public class Keys extends AbstractFastlyTask implements RunnableTask<Keys.Output> {

    @Schema(
        title = "Service ID",
        description = "The Fastly service identifier to purge against."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> serviceId;

    @Schema(
        title = "Surrogate keys",
        description = "List of surrogate keys to purge in a single batch request."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> surrogateKeys;

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
        var rKeys = runContext.render(surrogateKeys).asList(String.class);
        var rSoft = runContext.render(soft).as(Boolean.class).orElse(false);

        if (rKeys == null || rKeys.isEmpty()) {
            throw new IllegalArgumentException("surrogateKeys must not be empty.");
        }

        logger.info("Batch purging {} surrogate key(s) on service '{}' (soft={})", rKeys.size(), rServiceId, rSoft);

        var path = "/service/" + encodePathSegment(rServiceId) + "/purge";
        var body = HttpRequest.JsonRequestBody.builder()
            .content(Map.of("surrogate_keys", rKeys))
            .build();
        var response = fastlyRequest(runContext, path, rSoft, body);

        // Batch purge response is a map of key -> purge-id, e.g. {"key1":"abc","key2":"def"}
        var purgeIds = readPurgeIds(response);
        return Output.builder()
            .purgeIds(purgeIds)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Purge IDs by key",
            description = "Map of surrogate key to its corresponding purge request ID, as returned by Fastly."
        )
        private final Map<String, String> purgeIds;
    }
}
