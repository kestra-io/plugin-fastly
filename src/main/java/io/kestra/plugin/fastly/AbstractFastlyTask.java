package io.kestra.plugin.fastly;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFastlyTask extends Task {

    protected static final ObjectMapper MAPPER = JacksonMapper.ofJson(false);

    @Schema(
        title = "Fastly API token",
        description = """
            Your Fastly API token. Create one in the Fastly console under Account > API tokens.
            Required scopes depend on the operation: URL and surrogate key purges require `purge_select`;
            purge-all requires `purge_all`.
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> apiToken;

    @Schema(
        title = "Fastly API base URL",
        description = "Base URL for the Fastly API. Override this only for testing."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue("https://api.fastly.com");

    @Schema(
        title = "HTTP client options",
        description = "Optional advanced HTTP settings such as timeouts or proxy configuration."
    )
    @PluginProperty(group = "advanced")
    protected HttpConfiguration options;

    /**
     * Executes a POST request against the Fastly API, injects auth and common headers,
     * optionally sets the soft-purge header, and returns the raw response body as a String.
     * Non-2xx responses are surfaced as an exception with status code and body included.
     */
    protected HttpResponse<String> fastlyRequest(
        RunContext runContext,
        String path,
        boolean softPurge,
        HttpRequest.RequestBody body
    ) throws IllegalVariableEvaluationException, HttpClientException {
        var rToken = runContext.render(apiToken).as(String.class).orElse(null);
        if (rToken == null || rToken.isBlank()) {
            throw new IllegalArgumentException("Fastly API token is required but was blank after rendering.");
        }

        var rBaseUrl = runContext.render(baseUrl).as(String.class)
            .filter(s -> !s.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("Fastly API base URL is required but was blank after rendering."));
        if (rBaseUrl.endsWith("/")) {
            rBaseUrl = rBaseUrl.substring(0, rBaseUrl.length() - 1);
        }

        var requestBuilder = HttpRequest.builder()
            .method("POST")
            .uri(java.net.URI.create(rBaseUrl + path))
            .addHeader("Fastly-Key", rToken)
            .addHeader("Accept", "application/json");

        if (softPurge) {
            requestBuilder.addHeader("Fastly-Soft-Purge", "1");
        }

        if (body != null) {
            requestBuilder.body(body);
        }

        var request = requestBuilder.build();

        var config = options != null ? options : HttpConfiguration.builder().build();
        config = config.toBuilder().allowFailed(Property.ofValue(true)).build();

        HttpResponse<String> response;
        try (var client = new HttpClient(runContext, config)) {
            response = client.request(request, String.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Fastly API", e);
        }

        if (response.getStatus() == null || response.getStatus().getCode() == 0) {
            throw new HttpClientResponseException(
                "No response received from the Fastly API (possible connectivity, DNS, or timeout issue).",
                response
            );
        }

        var statusCode = response.getStatus().getCode();
        if (statusCode < 200 || statusCode >= 300) {
            var responseBody = response.getBody();
            if (statusCode == 403) {
                throw new HttpClientResponseException(
                    "Fastly API returned 403 Forbidden. Verify your API token has the required scope (purge_select or purge_all)."
                        + " Response: " + responseBody,
                    response
                );
            }
            throw new HttpClientResponseException(
                "Fastly API request failed (HTTP " + statusCode + "): " + responseBody,
                response
            );
        }
        return response;
    }

    protected static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Renders a required string property, failing with a clear, named error when it is
     * absent or blank after rendering.
     */
    protected String renderRequired(RunContext runContext, Property<String> property, String name)
        throws IllegalVariableEvaluationException {
        return runContext.render(property).as(String.class)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException(name + " is required"));
    }

    /**
     * Parses a Fastly purge response body, tolerating an empty or absent body so a purge that
     * succeeded server-side is not reported as failed when no JSON payload is returned.
     */
    protected PurgeResponse readPurgeResponse(HttpResponse<String> response) throws IOException {
        var body = response.getBody();
        if (body == null || body.isBlank()) {
            return new PurgeResponse(null, null);
        }
        return MAPPER.readValue(body, PurgeResponse.class);
    }

    /**
     * Parses a Fastly batch-purge response body (a map of surrogate key to purge ID), tolerating
     * an empty or absent body.
     */
    protected Map<String, String> readPurgeIds(HttpResponse<String> response) throws IOException {
        var body = response.getBody();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(body, new TypeReference<Map<String, String>>() {});
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurgeResponse(String status, String id) {}
}
