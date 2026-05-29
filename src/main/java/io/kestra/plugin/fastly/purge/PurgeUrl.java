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

import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Purge a cached URL from Fastly",
    description = """
        Sends a soft or hard purge request for a single URL across all Fastly services that cache it.
        This operation is not scoped to a specific service. Use `PurgeKey` or `PurgeKeys` for
        service-scoped, surrogate-key-based invalidation.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Purge a single URL after a deploy",
            full = true,
            code = """
                id: invalidate_homepage
                namespace: company.web

                tasks:
                  - id: deploy_static_site
                    type: io.kestra.plugin.core.log.Log
                    message: "Static site uploaded to origin"

                  - id: purge_homepage
                    type: io.kestra.plugin.fastly.purge.PurgeUrl
                    apiToken: "{{ secret('FASTLY_API_TOKEN') }}"
                    url: "https://example.com/"
                    soft: false
                """
        )
    }
)
public class PurgeUrl extends AbstractFastlyTask implements RunnableTask<PurgeUrl.Output> {

    @Schema(
        title = "URL to purge",
        description = "The full URL to purge from Fastly's cache (including scheme and path)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> url;

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

        var rUrl = renderRequired(runContext, url, "url");
        var rSoft = runContext.render(soft).as(Boolean.class).orElse(false);

        logger.info("Purging URL '{}' (soft={})", rUrl, rSoft);

        // The Fastly purge-by-URL endpoint is POST /purge/{url} where {url} includes the full URL.
        var path = "/purge/" + encodeUrl(rUrl);
        var response = fastlyRequest(runContext, path, rSoft, null);

        var result = readPurgeResponse(response);
        return Output.builder()
            .status(result.status())
            .id(result.id())
            .build();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * Encodes a full URL so it can be appended after {@code /purge/} and parsed by
     * {@link java.net.URI#create}. Fastly expects the URL to appear (effectively) literally in the
     * path, so we keep URL-structural characters ({@code : / ? @ ! $ & ' ( ) * + , ; =} and the
     * unreserved set) intact and only percent-encode characters that would otherwise make the URI
     * invalid: spaces, fragment markers, brackets, other delimiters, and any non-ASCII byte.
     * Already percent-encoded triplets ({@code %XX}) are preserved as-is.
     */
    private static String encodeUrl(String fullUrl) {
        var bytes = fullUrl.getBytes(StandardCharsets.UTF_8);
        var sb = new StringBuilder(bytes.length + 16);
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xFF;
            if (isSafeUrlChar(c)) {
                sb.append((char) c);
            } else if (c == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
                sb.append('%');
            } else {
                sb.append('%').append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    private static boolean isSafeUrlChar(int c) {
        return (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9')
            || "-._~:/?@!$&'()*+,;=".indexOf(c) >= 0;
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
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
